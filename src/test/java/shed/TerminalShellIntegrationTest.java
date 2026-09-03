package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalShellIntegrationTest {
    @Test
    void preparesAnIsolatedBashStartupAndTracksOnlyValidEvents() throws Exception {
        Path directory = Files.createTempDirectory("shed-shell-");
        TerminalShellIntegration.Launch launch = TerminalShellIntegration.prepare(List.of("/bin/bash", "-l"), true, directory);

        assertTrue(launch.enabled());
        assertEquals(List.of("/bin/bash", "--noprofile", "--rcfile", directory.resolve("bashrc").toString(), "-i"), launch.command());
        assertTrue(Files.readString(directory.resolve("bash.sh")).contains("1341;shed"));

        TerminalShellIntegrationTracker tracker = new TerminalShellIntegrationTracker();
        tracker.accept(List.of("shed", "cwd", "/tmp/project"));
        tracker.accept(List.of("shed", "finished", "0"));
        tracker.accept(List.of("other", "command", "ignored"));
        assertEquals("/tmp/project", tracker.currentDirectory());
        assertEquals(2, tracker.events().size());

        Path bash = java.nio.file.Files.isExecutable(Path.of("/usr/bin/bash")) ? Path.of("/usr/bin/bash") : Path.of("/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(bash), "Bash is unavailable");
        Process syntax = new ProcessBuilder(bash.toString(), "-n", directory.resolve("bash.sh").toString()).start();
        assertTrue(syntax.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, syntax.exitValue());
    }

    @Test
    void leavesNonInteractiveCommandsAndUnsupportedShellsUnmodified() throws Exception {
        Path directory = Files.createTempDirectory("shed-shell-");
        assertFalse(TerminalShellIntegration.prepare(List.of("bash", "-c", "echo hi"), true, directory).enabled());
        assertFalse(TerminalShellIntegration.prepare(List.of("fish"), true, directory).enabled());
    }
}
