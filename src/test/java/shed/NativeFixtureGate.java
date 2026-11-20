package shed;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Test-only fail-closed gate while native process isolation remains unqualified. */
public final class NativeFixtureGate implements BeforeAllCallback {
    static void requireReviewed(String name) throws Exception {
        Properties reviewed = new Properties();
        try (var input = NativeFixtureGate.class.getResourceAsStream("/shed/portable-fixture-tests.properties")) {
            if (input == null) throw new IllegalStateException("Portable fixture manifest missing");
            reviewed.load(input);
        }
        String expected = reviewed.getProperty(name);
        if (expected == null) throw new IllegalStateException("B0.1: native/unreviewed test blocked: " + name);
        Path source = Path.of("src/test/java/shed", name + ".java");
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        if (!expected.equals(actual)) throw new IllegalStateException("B0.1: reviewed test source changed: " + name);
    }

    static void requireContext() throws Exception {
        String value = System.getenv("SHED_PORTABLE_FIXTURE_ROOT");
        if (value == null) throw new IllegalStateException("B0.1: use scripts/run-portable-tests.py; native tests remain blocked");
        Path root = Path.of(value);
        if (!root.isAbsolute() || !root.equals(root.toRealPath()) || Files.isSymbolicLink(root))
            throw new IllegalStateException("B0.1: noncanonical fixture root");
        var permissions = Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
            throw new IllegalStateException("B0.1: unsafe fixture permissions");
        Path marker = root.resolve("owner.json");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || !Files.getOwner(root).equals(Files.getOwner(marker)))
            throw new IllegalStateException("B0.1: missing fixture ownership marker");
        for (String key : new String[]{"user.home", "java.io.tmpdir", "java.util.prefs.userRoot"}) {
            Path path = Path.of(System.getProperty(key)).toRealPath();
            if (!path.startsWith(root.resolve("run"))) throw new IllegalStateException("B0.1: unisolated JVM " + key);
        }
        if (!Files.isSameFile(Path.of(System.getenv("HOME")), Path.of(System.getProperty("user.home"))))
            throw new IllegalStateException("B0.1: native and JVM homes differ");
    }

    @Override public void beforeAll(ExtensionContext context) throws Exception {
        requireReviewed(context.getRequiredTestClass().getSimpleName());
        requireContext();
    }
}
