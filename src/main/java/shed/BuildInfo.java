package shed;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class BuildInfo {
    private final String version;
    private final String commit;
    private final String buildJava;
    private final String runtimeJava;
    private final String runtimeVendor;
    private final String osName;
    private final String osVersion;
    private final String osArchitecture;

    private BuildInfo(
        String version,
        String commit,
        String buildJava,
        String runtimeJava,
        String runtimeVendor,
        String osName,
        String osVersion,
        String osArchitecture
    ) {
        this.version = version;
        this.commit = commit;
        this.buildJava = buildJava;
        this.runtimeJava = runtimeJava;
        this.runtimeVendor = runtimeVendor;
        this.osName = osName;
        this.osVersion = osVersion;
        this.osArchitecture = osArchitecture;
    }

    public static BuildInfo current() {
        return fromManifest(manifestAttributes(), System.getProperties());
    }

    static BuildInfo fromManifest(Attributes manifest, Properties properties) {
        Attributes attributes = manifest == null ? new Attributes() : manifest;
        Properties runtime = properties == null ? new Properties() : properties;
        return new BuildInfo(
            present(attributes.getValue("Implementation-Version")),
            present(attributes.getValue("Shed-Commit")),
            present(attributes.getValue("Shed-Build-Java")),
            present(runtime.getProperty("java.runtime.version", runtime.getProperty("java.version"))),
            present(runtime.getProperty("java.vendor")),
            present(runtime.getProperty("os.name")),
            present(runtime.getProperty("os.version")),
            present(runtime.getProperty("os.arch"))
        );
    }

    public String render() {
        StringBuilder details = new StringBuilder("Shed support information\n");
        append(details, "version", version);
        append(details, "commit", commit);
        append(details, "build java", buildJava);
        append(details, "runtime java", join(runtimeJava, runtimeVendor, " "));
        append(details, "os", osDescription());
        return details.toString();
    }

    String version() {
        return version;
    }

    private static Attributes manifestAttributes() {
        try {
            CodeSource source = BuildInfo.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return new Attributes();
            }
            Path location = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(location)) {
                return new Attributes();
            }
            try (JarFile jar = new JarFile(location.toFile())) {
                Manifest manifest = jar.getManifest();
                return manifest == null ? new Attributes() : manifest.getMainAttributes();
            }
        } catch (URISyntaxException | java.io.IOException ignored) {
            return new Attributes();
        }
    }

    private String osDescription() {
        String nameAndVersion = join(osName, osVersion, " ");
        if (nameAndVersion == null) {
            return osArchitecture;
        }
        return osArchitecture == null ? nameAndVersion : nameAndVersion + " (" + osArchitecture + ")";
    }

    private static void append(StringBuilder details, String label, String value) {
        if (value != null) {
            details.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String join(String left, String right, String separator) {
        if (left == null) {
            return right;
        }
        return right == null ? left : left + separator + right;
    }

    private static String present(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
