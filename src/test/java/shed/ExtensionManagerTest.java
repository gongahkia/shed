package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtensionManagerTest {
    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void setUpHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreHome() {
        if (originalHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", originalHome);
    }

    @Test
    void installsLoadsDisablesAndRestoresAnExplicitExternalJar() throws Exception {
        Path jar = createExtensionJar();
        ConfigManager config = new ConfigManager();
        ExtensionRegistry registry = new ExtensionRegistry();
        ExtensionManager manager = new ExtensionManager(config, registry);
        try {
            String installed = manager.handle("install \"" + jar + "\"");
            assertTrue(installed.startsWith("Installed extension fixture@1.0.0"), installed);
            assertEquals("hello:Shed", manager.executeCommand("fixture.hello", "Shed"));
            assertTrue(manager.handle("status").contains("fixture @ 1.0.0  enabled  loaded"));

            assertEquals("Disabled extension fixture", manager.handle("disable fixture"));
            assertNull(manager.executeCommand("fixture.hello", "Shed"));
            assertEquals("Enabled extension fixture", manager.handle("enable fixture"));
            assertEquals("hello:again", manager.executeCommand("fixture.hello", "again"));
        } finally {
            manager.close();
        }

        ExtensionManager restored = new ExtensionManager(new ConfigManager(), new ExtensionRegistry());
        try {
            restored.loadInstalled();
            assertEquals("hello:restored", restored.executeCommand("fixture.hello", "restored"));
        } finally {
            restored.close();
        }
    }

    @Test
    void remoteInstallRequiresAUserProvidedChecksumBeforeNetworkAccess() {
        ExtensionManager manager = new ExtensionManager(new ConfigManager(), new ExtensionRegistry());
        try {
            assertEquals("Extension install failed: HTTPS sources require --checksum=<sha256>",
                manager.handle("install https://example.invalid/fixture.jar"));
        } finally {
            manager.close();
        }
    }

    private Path createExtensionJar() throws Exception {
        Path sourceRoot = tempDir.resolve("source");
        Path source = sourceRoot.resolve("fixture/ExternalFixture.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package fixture;
            import shed.api.ExtensionContext;
            import shed.api.ShedExtension;
            public final class ExternalFixture implements ShedExtension {
                @Override public void activate(ExtensionContext context) {
                    context.registerCommand("hello", arguments -> "hello:" + arguments);
                }
            }
            """);
        Path classes = tempDir.resolve("classes");
        ToolProvider compiler = ToolProvider.findFirst("javac").orElseThrow();
        int exit;
        try (PrintWriter sink = new PrintWriter(OutputStream.nullOutputStream())) {
            exit = compiler.run(sink, sink, "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), source.toString());
        }
        assertEquals(0, exit, "fixture extension source must compile");

        Path jar = tempDir.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "META-INF/shed-extension.toml", """
                id = "fixture"
                version = "1.0.0"
                api_version = 1
                main_class = "fixture.ExternalFixture"
                """);
            put(output, "fixture/ExternalFixture.class", Files.readAllBytes(classes.resolve("fixture/ExternalFixture.class")));
        }
        return jar;
    }

    private static void put(JarOutputStream output, String name, String value) throws IOException {
        put(output, name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(JarOutputStream output, String name, byte[] value) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(value);
        output.closeEntry();
    }
}
