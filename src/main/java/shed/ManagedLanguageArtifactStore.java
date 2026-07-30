package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ManagedLanguageArtifactStore {
    static final int MAX_CACHED_VERSIONS = 2;
    static final String RECEIPT_FILE_NAME = ".shed-artifact.json";
    static final String ACTIVATION_FILE_NAME = ".shed-active.json";
    private static final int RECEIPT_SCHEMA_VERSION = 1;
    private static final int ACTIVATION_SCHEMA_VERSION = 1;

    enum Outcome {
        AVAILABLE,
        REJECTED,
        ACTIVATED,
        ROLLED_BACK,
        REMOVED,
        FAILED
    }

    record Result(Outcome outcome, String detail, Path launchPath) {
        boolean launchable() {
            return outcome == Outcome.AVAILABLE && launchPath != null;
        }
    }

    private final ManagedLanguageSupportTrust trust;
    private final Path shedDirectory;

    ManagedLanguageArtifactStore(ManagedLanguageSupportTrust trust, Path shedDirectory) {
        this.trust = Objects.requireNonNull(trust, "trust");
        this.shedDirectory = Objects.requireNonNull(shedDirectory, "Shed directory");
    }

    void recordVerifiedArtifact(ManagedLanguageSupportTrust.CatalogArtifact artifact, String fileName, long size) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        if (!singleSegment(fileName)) throw new IOException("artifact file name must be one path segment");
        if (size < 0) throw new IOException("artifact size must be non-negative");
        Path directory = trust.cacheDirectory(shedDirectory, artifact.coordinate());
        Path artifactPath = directory.resolve(fileName).normalize();
        if (!artifactPath.startsWith(directory) || !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verified artifact file is unavailable");
        }
        if (Files.size(artifactPath) != size || !sha256(artifactPath).equalsIgnoreCase(artifact.sha256())) {
            throw new IOException("verified artifact no longer matches catalog integrity metadata");
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", RECEIPT_SCHEMA_VERSION);
        receipt.put("toolId", artifact.coordinate().toolId());
        receipt.put("version", artifact.coordinate().version());
        receipt.put("fileName", fileName);
        receipt.put("source", artifact.source().toString());
        receipt.put("sha256", artifact.sha256().toLowerCase(Locale.ROOT));
        receipt.put("size", size);
        receipt.put("signingKeyId", artifact.signingKeyId());
        receipt.put("signature", artifact.signature());
        AtomicFileWriter.write(directory.resolve(RECEIPT_FILE_NAME), MiniJson.stringify(receipt).getBytes(StandardCharsets.UTF_8));
    }

    Result resolveForLaunch(ManagedLanguageSupportTrust.ArtifactCoordinate coordinate, ManagedLanguageSupportTrust.Platform platform) {
        if (coordinate == null) return rejected("managed artifact coordinate is required");
        ManagedLanguageSupportTrust.Assessment assessment = trust.assess(ManagedLanguageSupportTrust.Ownership.SHED_MANAGED,
            coordinate, platform, true);
        if (assessment.decision() != ManagedLanguageSupportTrust.Decision.ALLOWED) return rejected(assessment.reason());
        Path directory = trust.cacheDirectory(shedDirectory, coordinate);
        try {
            Receipt receipt = readReceipt(directory.resolve(RECEIPT_FILE_NAME));
            String mismatch = receiptMismatch(receipt, assessment.artifact());
            if (mismatch != null) return rejected(mismatch);
            Path artifactPath = directory.resolve(receipt.fileName()).normalize();
            if (!artifactPath.startsWith(directory) || !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
                return rejected("managed artifact is incomplete: artifact file is unavailable");
            }
            if (Files.size(artifactPath) != receipt.size()) {
                return rejected("managed artifact is incomplete: artifact size does not match receipt");
            }
            if (!sha256(artifactPath).equalsIgnoreCase(receipt.sha256())) {
                return rejected("managed artifact is tampered: artifact sha256 does not match receipt");
            }
            return new Result(Outcome.AVAILABLE, "managed artifact is verified for launch", artifactPath);
        } catch (IOException | RuntimeException e) {
            return rejected("managed artifact is incomplete: " + failureDetail(e));
        }
    }

    Result activate(ManagedLanguageSupportTrust.ArtifactCoordinate coordinate, ManagedLanguageSupportTrust.Platform platform) {
        Result verified = resolveForLaunch(coordinate, platform);
        if (!verified.launchable()) return new Result(Outcome.REJECTED, verified.detail(), null);
        try {
            Path toolDirectory = toolDirectory(coordinate.toolId());
            Activation prior = readActivation(toolDirectory.resolve(ACTIVATION_FILE_NAME));
            String rollbackVersion = validRollbackVersion(coordinate.toolId(), prior == null ? null : prior.activeVersion(), platform, coordinate.version());
            writeActivation(toolDirectory, new Activation(coordinate.version(), rollbackVersion));
            prune(toolDirectory, rollbackVersion == null ? Set.of(coordinate.version()) : Set.of(coordinate.version(), rollbackVersion));
            return new Result(Outcome.ACTIVATED, "managed artifact activated: " + coordinate.displayName(), verified.launchPath());
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.FAILED, "managed artifact activation failed: " + failureDetail(e), null);
        }
    }

    Result resolveActive(String toolId, ManagedLanguageSupportTrust.Platform platform) {
        try {
            Path toolDirectory = toolDirectory(toolId);
            Activation activation = readActivation(toolDirectory.resolve(ACTIVATION_FILE_NAME));
            if (activation == null || activation.activeVersion() == null) return rejected("managed artifact has no active version");
            return resolveForLaunch(new ManagedLanguageSupportTrust.ArtifactCoordinate(toolId, activation.activeVersion()), platform);
        } catch (IOException | RuntimeException e) {
            return rejected("managed artifact activation is incomplete: " + failureDetail(e));
        }
    }

    Result rollback(String toolId, ManagedLanguageSupportTrust.Platform platform) {
        try {
            Path toolDirectory = toolDirectory(toolId);
            Activation activation = readActivation(toolDirectory.resolve(ACTIVATION_FILE_NAME));
            if (activation == null || activation.previousVersion() == null) return rejected("managed artifact has no rollback version");
            ManagedLanguageSupportTrust.ArtifactCoordinate rollback = new ManagedLanguageSupportTrust.ArtifactCoordinate(toolId, activation.previousVersion());
            Result verified = resolveForLaunch(rollback, platform);
            if (!verified.launchable()) return new Result(Outcome.REJECTED, verified.detail(), null);
            writeActivation(toolDirectory, new Activation(rollback.version(), activation.activeVersion()));
            prune(toolDirectory, Set.of(rollback.version(), activation.activeVersion()));
            return new Result(Outcome.ROLLED_BACK, "managed artifact rolled back to " + rollback.displayName(), verified.launchPath());
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.FAILED, "managed artifact rollback failed: " + failureDetail(e), null);
        }
    }

    Result remove(String toolId) {
        try {
            Path toolDirectory = toolDirectory(toolId);
            if (!Files.exists(toolDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return new Result(Outcome.REMOVED, "managed artifact cache is already absent: " + toolId, null);
            }
            if (!Files.isDirectory(toolDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return rejected("managed artifact cache path is not a directory");
            }
            deleteTree(toolDirectory);
            return new Result(Outcome.REMOVED, "removed managed artifact cache: " + toolId, null);
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.FAILED, "managed artifact removal failed: " + failureDetail(e), null);
        }
    }

    private String validRollbackVersion(String toolId, String version, ManagedLanguageSupportTrust.Platform platform, String nextVersion) {
        if (version == null || version.equals(nextVersion)) return null;
        Result prior = resolveForLaunch(new ManagedLanguageSupportTrust.ArtifactCoordinate(toolId, version), platform);
        return prior.launchable() ? version : null;
    }

    private Path toolDirectory(String toolId) {
        return trust.cacheDirectory(shedDirectory, new ManagedLanguageSupportTrust.ArtifactCoordinate(toolId, "cache")).getParent();
    }

    private void writeActivation(Path toolDirectory, Activation activation) throws IOException {
        Files.createDirectories(toolDirectory);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", ACTIVATION_SCHEMA_VERSION);
        values.put("activeVersion", activation.activeVersion());
        values.put("previousVersion", activation.previousVersion());
        AtomicFileWriter.write(toolDirectory.resolve(ACTIVATION_FILE_NAME), MiniJson.stringify(values).getBytes(StandardCharsets.UTF_8));
    }

    private Activation readActivation(Path activationPath) throws IOException {
        if (!Files.isRegularFile(activationPath, LinkOption.NOFOLLOW_LINKS)) return null;
        Map<String, Object> values = MiniJson.asObject(MiniJson.parse(Files.readString(activationPath, StandardCharsets.UTF_8)));
        if (values == null || !values.keySet().equals(Set.of("schemaVersion", "activeVersion", "previousVersion"))) {
            throw new IOException("activation receipt has unsupported or missing fields");
        }
        if (integer(values.get("schemaVersion"), "activation schema version") != ACTIVATION_SCHEMA_VERSION) {
            throw new IOException("activation receipt schema is unsupported");
        }
        String active = pathSegment(values.get("activeVersion"), "active version");
        Object previous = values.get("previousVersion");
        return new Activation(active, previous == null ? null : pathSegment(previous, "previous version"));
    }

    private Receipt readReceipt(Path receiptPath) throws IOException {
        if (!Files.isRegularFile(receiptPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("artifact receipt is unavailable");
        Map<String, Object> values = MiniJson.asObject(MiniJson.parse(Files.readString(receiptPath, StandardCharsets.UTF_8)));
        Set<String> fields = Set.of("schemaVersion", "toolId", "version", "fileName", "source", "sha256", "size", "signingKeyId", "signature");
        if (values == null || !values.keySet().equals(fields)) throw new IOException("artifact receipt has unsupported or missing fields");
        if (integer(values.get("schemaVersion"), "artifact receipt schema version") != RECEIPT_SCHEMA_VERSION) {
            throw new IOException("artifact receipt schema is unsupported");
        }
        return new Receipt(pathSegment(values.get("toolId"), "receipt tool id"), pathSegment(values.get("version"), "receipt version"),
            pathSegment(values.get("fileName"), "receipt file name"), string(values.get("source"), "receipt source"),
            string(values.get("sha256"), "receipt sha256"), nonNegativeLong(values.get("size"), "receipt size"),
            string(values.get("signingKeyId"), "receipt signing key id"), string(values.get("signature"), "receipt signature"));
    }

    private String receiptMismatch(Receipt receipt, ManagedLanguageSupportTrust.CatalogArtifact artifact) {
        if (!receipt.toolId().equals(artifact.coordinate().toolId()) || !receipt.version().equals(artifact.coordinate().version())) {
            return "managed artifact is stale: receipt coordinate does not match requested artifact";
        }
        if (!receipt.source().equals(artifact.source().toString()) || !receipt.sha256().equalsIgnoreCase(artifact.sha256())
            || !receipt.signingKeyId().equals(artifact.signingKeyId()) || !receipt.signature().equals(artifact.signature())) {
            return "managed artifact is stale: receipt identity does not match catalog";
        }
        return null;
    }

    private void prune(Path toolDirectory, Set<String> retainedVersions) throws IOException {
        if (!Files.isDirectory(toolDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(toolDirectory)) {
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || retainedVersions.contains(entry.getFileName().toString())) continue;
                deleteTree(entry);
            }
        }
    }

    private void deleteTree(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Result rejected(String detail) {
        return new Result(Outcome.REJECTED, detail, null);
    }

    private static String failureDetail(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "cache verification failed" : error.getMessage();
    }

    private static boolean singleSegment(String value) {
        return value != null && !value.isBlank() && !value.equals(".") && !value.equals("..") && !value.contains("/") && !value.contains("\\")
            && value.indexOf('\u0000') < 0;
    }

    private static String pathSegment(Object value, String field) throws IOException {
        String text = string(value, field);
        if (!singleSegment(text)) throw new IOException(field + " must be one path segment");
        return text;
    }

    private static String string(Object value, String field) throws IOException {
        if (!(value instanceof String text) || text.isBlank()) throw new IOException(field + " must be a non-empty string");
        return text;
    }

    private static int integer(Object value, String field) throws IOException {
        if (!(value instanceof Long number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IOException(field + " must be an integer");
        }
        return number.intValue();
    }

    private static long nonNegativeLong(Object value, String field) throws IOException {
        if (!(value instanceof Long number) || number < 0) throw new IOException(field + " must be non-negative");
        return number;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private record Receipt(String toolId, String version, String fileName, String source, String sha256, long size,
                           String signingKeyId, String signature) { }

    private record Activation(String activeVersion, String previousVersion) { }
}
