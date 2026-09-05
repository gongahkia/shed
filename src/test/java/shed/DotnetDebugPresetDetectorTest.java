package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotnetDebugPresetDetectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void contributesSessionOnlyNetcoredbgPresetForMatchingRunnableProjectOutput() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(root.resolve("Sample.App.csproj"), "<Project />");
        Path output = Files.createDirectories(root.resolve("bin/Debug/net9.0"));
        Files.write(output.resolve("Sample.App.dll"), new byte[] {'M', 'Z'});
        Files.writeString(output.resolve("Sample.App.runtimeconfig.json"), "{}");
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        DebugAdapterRegistry.Validation effective = DotnetDebugPresetDetector.effective(base, root);
        DebugAdapterRegistry.Configuration preset = effective.configurations().get("suggested-csharp-netcoredbg-Sample-App");

        assertTrue(DotnetDebugPresetDetector.isSuggestedConfiguration(preset.name()));
        assertEquals(BuiltInDebugAdapterSupport.CSHARP_NETCOREDBG, preset.adapter());
        assertEquals("${workspaceFolder}/bin/Debug/net9.0/Sample.App.dll", preset.program());
        assertTrue(DebugAdapterRegistry.plan(effective, preset.name(), root).launchable());
        assertFalse(base.configurations().containsKey(preset.name()));
    }

    @Test
    void ignoresLibrariesAndMismatchedAssemblyNames() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(root.resolve("Sample.csproj"), "<Project />");
        Path output = Files.createDirectories(root.resolve("bin/Release/net9.0"));
        Files.write(output.resolve("Sample.dll"), new byte[] {'M', 'Z'});
        Files.writeString(output.resolve("Other.runtimeconfig.json"), "{}");
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        assertTrue(DotnetDebugPresetDetector.configurations(base, root).isEmpty());
    }
}
