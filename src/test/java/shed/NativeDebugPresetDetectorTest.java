package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeDebugPresetDetectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void contributesSessionOnlyGdbAndLldbPresetsForConcreteDirectBuildArtifacts() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path artifact = Files.write(Files.createDirectories(root.resolve("build")).resolve("demo-app"), new byte[] {0x7f, 'E', 'L', 'F'});
        assertTrue(artifact.toFile().setExecutable(true));
        Path library = Files.write(root.resolve("build/libdemo.so"), new byte[] {0x7f, 'E', 'L', 'F'});
        assertTrue(library.toFile().setExecutable(true));
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        DebugAdapterRegistry.Validation effective = NativeDebugPresetDetector.effective(base, root);
        DebugAdapterRegistry.Configuration gdb = effective.configurations().get("suggested-native-gdb-demo-app");
        DebugAdapterRegistry.Configuration lldb = effective.configurations().get("suggested-native-lldb-demo-app");

        assertTrue(NativeDebugPresetDetector.isSuggestedConfiguration(gdb.name()));
        assertEquals(BuiltInDebugAdapterSupport.NATIVE_GDB, gdb.adapter());
        assertEquals(BuiltInDebugAdapterSupport.NATIVE_LLDB, lldb.adapter());
        assertEquals("${workspaceFolder}/build/demo-app", gdb.program());
        assertTrue(DebugAdapterRegistry.plan(effective, gdb.name(), root).launchable());
        assertFalse(effective.configurations().containsKey("suggested-native-gdb-libdemo-so"));
        assertFalse(base.configurations().containsKey(gdb.name()));
    }

    @Test
    void ignoresNestedAndSymbolicLinkArtifacts() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path nested = Files.write(Files.createDirectories(root.resolve("build/nested")).resolve("ignored"), new byte[] {0x7f, 'E', 'L', 'F'});
        assertTrue(nested.toFile().setExecutable(true));
        Path external = Files.write(temporaryDirectory.resolve("outside"), new byte[] {0x7f, 'E', 'L', 'F'});
        assertTrue(external.toFile().setExecutable(true));
        try {
            Files.createSymbolicLink(root.resolve("build/linked"), external);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // The direct nested artifact still verifies that discovery does not recurse.
        }
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        assertTrue(NativeDebugPresetDetector.configurations(base, root).isEmpty());
    }
}
