package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.jar.Attributes;
import org.junit.jupiter.api.Test;

public class BuildInfoTest {
    @Test
    void rendersAvailableArtifactAndRuntimeMetadata() {
        Attributes manifest = new Attributes();
        manifest.putValue("Implementation-Version", "2.0.0");
        manifest.putValue("Shed-Commit", "abc123");
        manifest.putValue("Shed-Build-Java", "21");
        Properties runtime = runtimeProperties();

        String details = BuildInfo.fromManifest(manifest, runtime).render();

        assertTrue(details.contains("version: 2.0.0"));
        assertTrue(details.contains("commit: abc123"));
        assertTrue(details.contains("build java: 21"));
        assertTrue(details.contains("runtime java: 21.0.12 Test Vendor"));
        assertTrue(details.contains("os: Test OS 1.0 (test-arch)"));
    }

    @Test
    void omitsUnavailableBuildFields() {
        Attributes manifest = new Attributes();
        manifest.putValue("Implementation-Version", "2.0.0");
        Properties runtime = runtimeProperties();

        String details = BuildInfo.fromManifest(manifest, runtime).render();

        assertTrue(details.contains("version: 2.0.0"));
        assertFalse(details.contains("commit:"));
        assertFalse(details.contains("build java:"));
        assertFalse(details.contains("unknown"));
    }

    private static Properties runtimeProperties() {
        Properties properties = new Properties();
        properties.setProperty("java.runtime.version", "21.0.12");
        properties.setProperty("java.vendor", "Test Vendor");
        properties.setProperty("os.name", "Test OS");
        properties.setProperty("os.version", "1.0");
        properties.setProperty("os.arch", "test-arch");
        return properties;
    }
}
