package shed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

final class ManagedLanguageInstaller {
    interface ArtifactFetcher {
        InputStream open(ManagedLanguageSupportTrust.CatalogArtifact artifact) throws IOException;
    }

    interface Cancellation {
        boolean isCancelled();
    }

    enum Outcome {
        INSTALLED,
        CONSENT_REQUIRED,
        CANCELLED,
        REJECTED,
        FAILED
    }

    record Review(
        ManagedLanguageSupportTrust.CatalogArtifact artifact,
        ManagedLanguageCatalog.InstallMetadata installMetadata,
        Long expectedSizeBytes,
        String fileName
    ) {
        Review {
            artifact = Objects.requireNonNull(artifact, "artifact");
            installMetadata = Objects.requireNonNull(installMetadata, "install metadata");
            if (!artifact.coordinate().equals(installMetadata.coordinate())) {
                throw new IllegalArgumentException("artifact and install metadata coordinates differ");
            }
            if (expectedSizeBytes != null && expectedSizeBytes < 0) {
                throw new IllegalArgumentException("expected size must be non-negative");
            }
            if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.equals(".") || fileName.equals("..")) {
                throw new IllegalArgumentException("file name must be one path segment");
            }
        }

        String summary() {
            String size = expectedSizeBytes == null ? "size unavailable" : expectedSizeBytes + " bytes";
            return artifact.coordinate().displayName() + "\nsource: " + artifact.source() + "\nversion: "
                + artifact.coordinate().version() + "\nsize: " + size + "\nlicense: " + installMetadata.licenseName();
        }
    }

    record Result(Outcome outcome, String detail, Path installedPath) {
        boolean installed() {
            return outcome == Outcome.INSTALLED;
        }
    }

    private final ManagedLanguageSupportTrust trust;
    private final ArtifactFetcher fetcher;
    private final Path shedDirectory;
    private final ManagedLanguageArtifactStore artifactStore;

    ManagedLanguageInstaller(ManagedLanguageSupportTrust trust, ArtifactFetcher fetcher, Path shedDirectory) {
        this.trust = Objects.requireNonNull(trust, "trust");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.shedDirectory = Objects.requireNonNull(shedDirectory, "Shed directory");
        this.artifactStore = new ManagedLanguageArtifactStore(trust, shedDirectory);
    }

    Result install(Review review, ManagedLanguageSupportTrust.Platform platform, boolean explicitConsent, Cancellation cancellation) {
        if (review == null) return new Result(Outcome.REJECTED, "install review is required", null);
        if (isCancelled(cancellation)) return new Result(Outcome.CANCELLED, "install cancelled before download", null);
        ManagedLanguageSupportTrust.Assessment assessment = trust.assess(ManagedLanguageSupportTrust.Ownership.SHED_MANAGED,
            review.artifact().coordinate(), platform, explicitConsent);
        if (assessment.decision() == ManagedLanguageSupportTrust.Decision.CONSENT_REQUIRED) {
            return new Result(Outcome.CONSENT_REQUIRED, assessment.reason(), null);
        }
        if (assessment.decision() == ManagedLanguageSupportTrust.Decision.REJECTED) {
            return new Result(Outcome.REJECTED, assessment.reason(), null);
        }
        Path target = trust.cacheDirectory(shedDirectory, review.artifact().coordinate()).resolve(review.fileName()).normalize();
        Path cacheRoot = trust.cacheRoot(shedDirectory);
        if (!target.startsWith(cacheRoot)) return new Result(Outcome.REJECTED, "install target escapes managed cache", null);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".shed-install-", ".tmp");
            Transfer transfer = transfer(review, temporary, cancellation);
            if (transfer.cancelled()) return new Result(Outcome.CANCELLED, "install cancelled", null);
            if (review.expectedSizeBytes() != null && transfer.size() != review.expectedSizeBytes()) {
                return new Result(Outcome.REJECTED, "artifact size mismatch (expected " + review.expectedSizeBytes() + ", got " + transfer.size() + ")", null);
            }
            if (!transfer.sha256().equalsIgnoreCase(review.artifact().sha256())) {
                return new Result(Outcome.REJECTED, "artifact sha256 mismatch", null);
            }
            moveAtomically(temporary, target);
            temporary = null;
            artifactStore.recordVerifiedArtifact(review.artifact(), review.fileName(), transfer.size());
            ManagedLanguageArtifactStore.Result activation = artifactStore.activate(review.artifact().coordinate(), platform);
            if (activation.outcome() != ManagedLanguageArtifactStore.Outcome.ACTIVATED) {
                return new Result(Outcome.FAILED, activation.detail(), null);
            }
            return new Result(Outcome.INSTALLED, "installed " + review.artifact().coordinate().displayName(), target);
        } catch (IOException e) {
            return new Result(Outcome.FAILED, e.getMessage() == null ? "install failed" : e.getMessage(), null);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private Transfer transfer(Review review, Path target, Cancellation cancellation) throws IOException {
        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream source = fetcher.open(review.artifact()); java.io.OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (isCancelled(cancellation)) return new Transfer(size, "", true);
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        return new Transfer(size, HexFormat.of().formatHex(digest.digest()), false);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static boolean isCancelled(Cancellation cancellation) {
        return cancellation != null && cancellation.isCancelled();
    }

    private record Transfer(long size, String sha256, boolean cancelled) { }
}
