package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainServiceTest {
    @Test
    void detectsButDoesNotAutomaticallySelectAProjectPythonEnvironment(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path python = executable(workspace.resolve(".venv/bin/python"));
        Path resolvedPython = python.toRealPath();
        Files.writeString(workspace.resolve(".venv/pyvenv.cfg"), "home = /usr/bin\n");
        ToolchainService service = new ToolchainService(temporaryDirectory.resolve("state"), Map.of("PATH", "/system/bin"));

        ToolchainService.Report report = service.report(workspace);

        assertTrue(report.usable());
        assertTrue(report.selections().isEmpty());
        assertTrue(report.candidates().stream().anyMatch(candidate -> candidate.runtime() == ToolchainService.Runtime.PYTHON
            && candidate.executable().equals(resolvedPython) && candidate.source().equals("workspace .venv")));
    }

    @Test
    void persistsAnExplicitSelectionAndBuildsAnEnvironmentForLocalProcesses(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path python = executable(workspace.resolve(".venv/bin/python"));
        Files.writeString(workspace.resolve(".venv/pyvenv.cfg"), "home = /usr/bin\n");
        Path node = executable(temporaryDirectory.resolve("node/bin/node"));
        ToolchainService service = new ToolchainService(temporaryDirectory.resolve("state"), Map.of("PATH", "/system/bin"));

        service.select(workspace, ToolchainService.Runtime.PYTHON, python.toString());
        service.select(workspace, ToolchainService.Runtime.NODE, node.toString());

        Map<String, String> environment = service.environment(workspace);
        assertEquals(python.toRealPath(), new ToolchainService(temporaryDirectory.resolve("state"), Map.of("PATH", "/system/bin"))
            .report(workspace).selections().get(ToolchainService.Runtime.PYTHON));
        assertEquals(python.getParent() + File.pathSeparator + node.getParent() + File.pathSeparator + "/system/bin", environment.get("PATH"));
        assertEquals(workspace.resolve(".venv").toString(), environment.get("VIRTUAL_ENV"));
        assertEquals("task-path", service.environment(workspace, Map.of("PATH", "task-path")).get("PATH"));
    }

    @Test
    void refusesRelativeOrUnavailableSelections(@TempDir Path temporaryDirectory) {
        ToolchainService service = new ToolchainService(temporaryDirectory.resolve("state"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> service.select(temporaryDirectory.resolve("workspace"), ToolchainService.Runtime.GO, "go"));
        assertFalse(service.report(temporaryDirectory.resolve("workspace")).selections().containsKey(ToolchainService.Runtime.GO));
    }

    @Test
    void exportsJavaHomeAndAcceptsCPlusPlusAsAnAlias(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path java = executable(temporaryDirectory.resolve("jdk/bin/java"));
        ToolchainService service = new ToolchainService(temporaryDirectory.resolve("state"), Map.of("PATH", "/system/bin"));

        service.select(workspace, ToolchainService.Runtime.JAVA, java.toString());
        service.select(workspace, ToolchainService.Runtime.parse("c++"), executable(temporaryDirectory.resolve("llvm/bin/clang++")).toString());

        assertEquals(temporaryDirectory.resolve("jdk").toString(), service.environment(workspace).get("JAVA_HOME"));
        assertEquals(ToolchainService.Runtime.CPP, ToolchainService.Runtime.parse("c++"));
    }

    @Test
    void selectsAndDiscoversTheFourteenAdditionalRuntimeBackedLanguages(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path bin = temporaryDirectory.resolve("runtimes/bin");
        for (String executable : java.util.List.of("dotnet", "php", "bash", "rustc", "pwsh", "kotlin", "ruby", "dart", "lua", "swift", "R", "perl", "scala", "ghc")) {
            executable(bin.resolve(executable));
        }
        ToolchainService service = new ToolchainService(temporaryDirectory.resolve("state"), Map.of("PATH", bin.toString()));

        assertEquals(ToolchainService.Runtime.CSHARP, ToolchainService.Runtime.parse("c#"));
        assertEquals(ToolchainService.Runtime.SHELL, ToolchainService.Runtime.parse("bash"));
        assertEquals(ToolchainService.Runtime.POWERSHELL, ToolchainService.Runtime.parse("pwsh"));
        assertEquals(14, service.report(workspace).candidates().stream().map(ToolchainService.Candidate::runtime).distinct().count());
        service.select(workspace, ToolchainService.Runtime.HASKELL, bin.resolve("ghc").toString());
        assertEquals(bin.resolve("ghc").toRealPath(), service.report(workspace).selections().get(ToolchainService.Runtime.HASKELL));
    }

    private static Path executable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        if (!path.toFile().setExecutable(true)) throw new IllegalStateException("test executable could not be made executable");
        return path;
    }
}
