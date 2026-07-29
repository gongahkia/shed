package shed;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ManagedLanguageSupportTrust {
    static final String CACHE_DIRECTORY_NAME = "managed-languages";

    enum Ownership {
        USER_MANAGED,
        SHED_MANAGED
    }

    enum Platform {
        MACOS,
        WINDOWS,
        LINUX
    }

    enum Decision {
        ALLOWED,
        CONSENT_REQUIRED,
        REJECTED
    }

    record ArtifactCoordinate(String toolId, String version) {
        ArtifactCoordinate {
            toolId = requirePathSegment(toolId, "tool id");
            version = requirePathSegment(version, "version");
        }

        String displayName() {
            return toolId + "@" + version;
        }
    }

    record CatalogArtifact(
        ArtifactCoordinate coordinate,
        URI source,
        String sha256,
        String signingKeyId,
        String signature,
        Set<Platform> supportedPlatforms
    ) {
        CatalogArtifact {
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            source = Objects.requireNonNull(source, "source");
            supportedPlatforms = supportedPlatforms == null ? Set.of() : Set.copyOf(supportedPlatforms);
        }
    }

    record Assessment(Ownership ownership, Decision decision, String reason, CatalogArtifact artifact) {
        boolean permitsManagedNetwork() {
            return ownership == Ownership.SHED_MANAGED && decision == Decision.ALLOWED;
        }

        boolean permitsManagedCacheWrite() {
            return permitsManagedNetwork();
        }
    }

    private final Map<ArtifactCoordinate, CatalogArtifact> catalog;
    private final Set<ArtifactCoordinate> revokedArtifacts;

    ManagedLanguageSupportTrust(List<CatalogArtifact> artifacts, Set<ArtifactCoordinate> revokedArtifacts) {
        Map<ArtifactCoordinate, CatalogArtifact> entries = new LinkedHashMap<>();
        if (artifacts != null) {
            for (CatalogArtifact artifact : artifacts) {
                if (artifact == null) {
                    throw new IllegalArgumentException("catalog artifact required");
                }
                if (entries.putIfAbsent(artifact.coordinate(), artifact) != null) {
                    throw new IllegalArgumentException("duplicate catalog artifact: " + artifact.coordinate().displayName());
                }
            }
        }
        this.catalog = Map.copyOf(entries);
        this.revokedArtifacts = revokedArtifacts == null ? Set.of() : Set.copyOf(revokedArtifacts);
    }

    Assessment assess(Ownership ownership, ArtifactCoordinate coordinate, Platform platform, boolean explicitConsent) {
        if (ownership == null) {
            return rejected(null, "tool ownership is required", null);
        }
        if (ownership == Ownership.USER_MANAGED) {
            return new Assessment(ownership, Decision.ALLOWED,
                "user-managed tool: Shed does not download, cache, update, or revoke it", null);
        }
        if (coordinate == null) {
            return rejected(ownership, "managed artifact is required", null);
        }
        CatalogArtifact artifact = catalog.get(coordinate);
        if (artifact == null) {
            return rejected(ownership, "unknown managed artifact: " + coordinate.displayName(), null);
        }
        if (revokedArtifacts.contains(coordinate)) {
            return rejected(ownership, "managed artifact is revoked: " + coordinate.displayName(), artifact);
        }
        String metadataFailure = metadataFailure(artifact);
        if (metadataFailure != null) {
            return rejected(ownership, metadataFailure, artifact);
        }
        if (platform == null || !artifact.supportedPlatforms().contains(platform)) {
            return rejected(ownership, "managed artifact is unsupported on " + platformName(platform), artifact);
        }
        if (!explicitConsent) {
            return new Assessment(ownership, Decision.CONSENT_REQUIRED,
                "explicit user consent is required before managed download or installation", artifact);
        }
        return new Assessment(ownership, Decision.ALLOWED,
            "managed artifact is cataloged, signed, integrity-pinned, platform-supported, and user-approved", artifact);
    }

    Path cacheRoot(Path shedDirectory) {
        if (shedDirectory == null) {
            throw new IllegalArgumentException("Shed directory is required");
        }
        return shedDirectory.toAbsolutePath().normalize().resolve(CACHE_DIRECTORY_NAME);
    }

    Path cacheDirectory(Path shedDirectory, ArtifactCoordinate coordinate) {
        if (coordinate == null) {
            throw new IllegalArgumentException("artifact coordinate is required");
        }
        Path root = cacheRoot(shedDirectory);
        Path directory = root.resolve(coordinate.toolId()).resolve(coordinate.version()).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("managed cache path escapes cache root");
        }
        return directory;
    }

    private Assessment rejected(Ownership ownership, String reason, CatalogArtifact artifact) {
        return new Assessment(ownership, Decision.REJECTED, reason, artifact);
    }

    private String metadataFailure(CatalogArtifact artifact) {
        URI source = artifact.source();
        if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null || source.getHost().isBlank()) {
            return "managed artifact source must be an HTTPS URI";
        }
        if (artifact.sha256() == null || !artifact.sha256().matches("[0-9a-fA-F]{64}")) {
            return "managed artifact has invalid sha256 integrity metadata";
        }
        if (artifact.signingKeyId() == null || artifact.signingKeyId().isBlank()) {
            return "managed artifact is unsigned: signing key id is missing";
        }
        if (artifact.signature() == null || artifact.signature().isBlank()) {
            return "managed artifact is unsigned: detached signature is missing";
        }
        if (artifact.supportedPlatforms().isEmpty()) {
            return "managed artifact has no supported platforms";
        }
        return null;
    }

    private static String platformName(Platform platform) {
        return platform == null ? "an unknown platform" : platform.name().toLowerCase();
    }

    private static String requirePathSegment(String value, String label) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
            || value.contains("/") || value.contains("\\") || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(label + " must be one path segment");
        }
        return value;
    }
}
