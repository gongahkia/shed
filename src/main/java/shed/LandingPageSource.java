package shed;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

final class LandingPageSource {
    private static final long MAX_LEGACY_DEFAULT_BYTES = 8 * 1024;
    private static final Pattern LEGACY_DEFAULT_CONTENT = Pattern.compile(
        "\\Ashed [^\\r\\n]+\\R"
            + "swing modal editor\\R\\R"
            + ":help        view help\\R"
            + ":e <file>    open a file\\R"
            + ":recent      show recent files\\R"
            + ":ls          list open buffers\\R\\R"
            + "edit and save this local landing file to customize it\\.\\R?\\z"
    );

    record Resolved(File file, URI remoteUri) {
        boolean isRemote() {
            return remoteUri != null;
        }
    }

    record StartupTarget(Resolved source, boolean showNativeWelcome) { }

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

    /**
     * The native welcome surface deliberately owns only Shed's untouched default
     * landing source. A configured file or remote URL remains an editable buffer.
     */
    static StartupTarget resolveStartupTarget(ConfigManager config, String legacyDefaultContent) throws IOException {
        Resolved source = resolve(config);
        boolean useWelcome = config.getLandingWelcomeEnabled()
            && !source.isRemote()
            && source.file().toPath().toAbsolutePath().normalize().equals(defaultLandingPath(config))
            && isMissingOrLegacyDefault(source.file().toPath(), legacyDefaultContent);
        return new StartupTarget(source, useWelcome);
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

    private static boolean isMissingOrLegacyDefault(Path path, String legacyDefaultContent) {
        try {
            if (Files.notExists(path)) {
                return true;
            }
            if (!Files.isRegularFile(path) || legacyDefaultContent == null) {
                return false;
            }
            long size = Files.size(path);
            if (size > MAX_LEGACY_DEFAULT_BYTES) {
                return false;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return legacyDefaultContent.equals(content) || LEGACY_DEFAULT_CONTENT.matcher(content).matches();
        } catch (IOException | SecurityException error) {
            // Preserve a pre-existing or unreadable user file by using the legacy path.
            return false;
        }
    }

    private static Path defaultLandingPath(ConfigManager config) {
        return Path.of(config.getShedDirectoryPath()).resolve("landing.md").toAbsolutePath().normalize();
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
