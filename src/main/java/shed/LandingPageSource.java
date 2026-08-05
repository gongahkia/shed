package shed;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class LandingPageSource {
    record Resolved(File file, URI remoteUri) {
        boolean isRemote() {
            return remoteUri != null;
        }
    }

    private LandingPageSource() {
    }

    static Resolved resolve(ConfigManager config) throws IOException {
        String source = config.getLandingSource();
        if (startsWithIgnoreCase(source, "https://")) {
            try {
                URI remote = URI.create(source);
                if (remote.getHost() == null || remote.getHost().isBlank()) {
                    throw new IOException("landing HTTPS URL must include a host");
                }
                return new Resolved(resolveLocalPath(config.getLandingRemoteCachePath()).toFile(), remote);
            } catch (IllegalArgumentException error) {
                throw new IOException("invalid landing HTTPS URL", error);
            }
        }
        if (startsWithIgnoreCase(source, "http://")) {
            throw new IOException("remote landing source must use HTTPS");
        }
        if (startsWithIgnoreCase(source, "file:")) {
            try {
                return new Resolved(Path.of(URI.create(source)).toFile(), null);
            } catch (IllegalArgumentException error) {
                throw new IOException("invalid landing file URI", error);
            }
        }
        if (source.contains("://")) {
            throw new IOException("unsupported landing source scheme");
        }
        return new Resolved(resolveLocalPath(source).toFile(), null);
    }

    static File ensureLocalFile(Resolved source, String initialContent) throws IOException {
        Path path = source.file().toPath().toAbsolutePath().normalize();
        if (Files.exists(path)) {
            if (!Files.isRegularFile(path)) throw new IOException("landing path is not a regular file: " + path);
            return path.toFile();
        }
        Path parent = path.getParent();
        if (parent == null) throw new IOException("landing path has no parent directory: " + path);
        Files.createDirectories(parent);
        Files.writeString(path, initialContent == null ? "" : initialContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return path.toFile();
    }

    private static Path resolveLocalPath(String configured) throws IOException {
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) throw new IOException("landing path is empty");
        try {
            Path home = Path.of(System.getProperty("user.home"));
            if (value.equals("~")) return home;
            if (value.startsWith("~/") || value.startsWith("~" + File.separator)) return home.resolve(value.substring(2)).normalize();
            Path path = Path.of(value);
            return path.isAbsolute() ? path.normalize() : home.resolve(path).normalize();
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid landing path", error);
        }
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value != null && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
