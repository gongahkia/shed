package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class LanguageServerDetectorTest {
    @Test
    void reportsMissingExecutableAndManualRemediationWithoutRunningCommands() {
        List<List<String>> commands = new ArrayList<>();
        LanguageServerDetector detector = new LanguageServerDetector(command -> null,
            command -> { commands.add(command); return new LanguageServerDetector.CommandResult(0, "", ""); }, null);

        LanguageServerDetector.Result result = detector.detect(ManagedLanguageCatalog.python(), ManagedLanguageSupportTrust.Platform.LINUX);

        assertEquals(ManagedLanguageCatalog.Availability.EXECUTABLE_MISSING, result.status().availability());
        assertEquals("pyright-langserver", result.executable());
        assertTrue(result.failure().contains("executable not found"));
        assertTrue(result.status().remediation().contains("lsp.py.command"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void detectsCompatibleToolWithReadOnlyVersionProbes() {
        List<List<String>> commands = new ArrayList<>();
        LanguageServerDetector detector = new LanguageServerDetector(command -> Path.of("/tools/pyright-langserver"),
            command -> {
                commands.add(command);
                return command.get(0).equals("node")
                    ? new LanguageServerDetector.CommandResult(0, "v20.19.1\n", "")
                    : new LanguageServerDetector.CommandResult(0, "pyright 1.1.411\n", "");
            }, null);

        LanguageServerDetector.Result result = detector.detect(ManagedLanguageCatalog.python(), ManagedLanguageSupportTrust.Platform.LINUX);

        assertTrue(result.usable());
        assertEquals(Path.of("/tools/pyright-langserver").toString(), result.executable());
        assertEquals("pyright 1.1.411", result.serverVersion());
        assertEquals("v20.19.1", result.runtimeVersion());
        assertEquals(List.of(Path.of("/tools/pyright-langserver").toString(), "--version"), commands.get(0));
        assertEquals(List.of("node", "--version"), commands.get(1));
        assertFalse(commands.stream().flatMap(List::stream).anyMatch(value -> value.contains("install") || value.contains("update")));
    }

    @Test
    void reportsRuntimeVersionAndProbeFailuresExactly() {
        LanguageServerDetector oldRuntime = new LanguageServerDetector(command -> Path.of("/tools/remark-language-server"),
            command -> command.get(0).equals("node")
                ? new LanguageServerDetector.CommandResult(0, "v14.21.3", "")
                : new LanguageServerDetector.CommandResult(0, "remark-language-server 3.0.0", ""), null);
        LanguageServerDetector failedProbe = new LanguageServerDetector(command -> Path.of("/tools/gopls"),
            command -> new LanguageServerDetector.CommandResult(2, "", "permission denied"), null);

        LanguageServerDetector.Result old = oldRuntime.detect(ManagedLanguageCatalog.markdown(), ManagedLanguageSupportTrust.Platform.LINUX);
        LanguageServerDetector.Result failed = failedProbe.detect(ManagedLanguageCatalog.go(), ManagedLanguageSupportTrust.Platform.LINUX);

        assertEquals(ManagedLanguageCatalog.Availability.RUNTIME_VERSION_UNSUPPORTED, old.status().availability());
        assertTrue(old.status().detail().contains("Node.js 14.21.3"));
        assertEquals(ManagedLanguageCatalog.Availability.RUNTIME_VERSION_UNKNOWN, failed.status().availability());
        assertEquals("permission denied", failed.failure());
        assertEquals(Path.of("/tools/gopls").toString(), failed.executable());
    }
}
