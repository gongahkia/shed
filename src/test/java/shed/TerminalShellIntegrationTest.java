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
    void preparesAnIsolatedFishStartup() throws Exception {
        Path directory = Files.createTempDirectory("shed-shell-");
        TerminalShellIntegration.Launch launch = TerminalShellIntegration.prepare(List.of("fish"), true, directory);

        assertTrue(launch.enabled());
        assertEquals(List.of("fish", "-i"), launch.command());
        assertEquals(directory.toString(), launch.environment().get("XDG_CONFIG_HOME"));
        assertTrue(Files.readString(directory.resolve("fish.sh")).contains("fish_preexec"));
        assertTrue(Files.readString(directory.resolve("fish").resolve("config.fish")).contains("SHED_ORIGINAL_FISH_CONFIG"));

        Path fish = Path.of("/usr/bin/fish");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(fish), "Fish is unavailable");
        Process syntax = new ProcessBuilder(fish.toString(), "-n", directory.resolve("fish.sh").toString()).start();
        assertTrue(syntax.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, syntax.exitValue());
    }

    @Test
    void zshPromptHookDoesNotAssignItsReadOnlyStatusParameter() throws Exception {
        Path directory = Files.createTempDirectory("shed-shell-");
        TerminalShellIntegration.Launch launch = TerminalShellIntegration.prepare(List.of("zsh"), true, directory);

        assertTrue(launch.enabled());
        assertEquals(List.of("zsh", "-i"), launch.command());
        String hook = Files.readString(directory.resolve("zsh.sh"));
        assertTrue(hook.contains("local exit_code=$?"));
        assertFalse(hook.contains("local status=$?"));

        Path zsh = Files.isExecutable(Path.of("/usr/bin/zsh")) ? Path.of("/usr/bin/zsh") : Path.of("/bin/zsh");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(zsh), "Zsh is unavailable");
        Process invocation = new ProcessBuilder(zsh.toString(), "-df", "-c", "source \"$1\"; _shed_precmd", "zsh",
            directory.resolve("zsh.sh").toString()).start();
        assertTrue(invocation.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, invocation.exitValue());
    }

    @Test
    void preparesAnIsolatedPowerShellStartupWithoutModifyingUserProfiles() throws Exception {
        Path directory = Files.createTempDirectory("shed-shell-");
        TerminalShellIntegration.Launch launch = TerminalShellIntegration.prepare(List.of("pwsh", "-NoLogo"), true, directory);

        assertTrue(launch.enabled());
        assertEquals(List.of("pwsh", "-NoProfile", "-NoExit", "-File", directory.resolve("powershell-bootstrap.ps1").toString()), launch.command());
        assertEquals("shed", launch.environment().get("TERM_PROGRAM"));
        assertTrue(Files.readString(directory.resolve("powershell-bootstrap.ps1")).contains("$PROFILE.CurrentUserCurrentHost"));
        String hook = Files.readString(directory.resolve("powershell.ps1"));
        assertTrue(hook.contains("Set-PSReadLineKeyHandler -Chord Enter"));
        assertTrue(hook.contains("Get-History -Count 1"));
        assertTrue(hook.contains("1341;shed"));
        assertTrue(TerminalShellIntegration.prepare(List.of("powershell.exe"), true, directory).enabled());
    }

    @Test
    void leavesNonInteractiveCommandsAndUnsupportedShellsUnmodified() throws Exception {
        Path directory = Files.createTempDirectory("shed-shell-");
        assertFalse(TerminalShellIntegration.prepare(List.of("bash", "-c", "echo hi"), true, directory).enabled());
        assertFalse(TerminalShellIntegration.prepare(List.of("cmd.exe"), true, directory).enabled());
    }
}
