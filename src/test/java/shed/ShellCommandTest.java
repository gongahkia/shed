package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class ShellCommandTest {
    @Test
    void usesShellEnvironmentWhenSupportedAndExecutable() {
        assertEquals(
            java.util.List.of("/usr/bin/bash", "-lc", "echo ok"),
            ShellCommand.forCommand("echo ok", Map.of("SHELL", "/usr/bin/bash"), "/usr/bin/bash"::equals)
        );
    }

    @Test
    void fallsBackToLinuxBashWhenShellEnvironmentIsUnsupported() {
        assertEquals(
            java.util.List.of("/bin/bash", "-lc", "echo ok"),
            ShellCommand.forCommand("echo ok", Map.of("SHELL", "/usr/bin/fish"), "/bin/bash"::equals)
        );
    }

    @Test
    void usesFishWhenTheConfiguredShellIsExecutable() {
        assertEquals(
            java.util.List.of("/usr/bin/fish", "-c", "echo ok"),
            ShellCommand.forCommand("echo ok", Map.of("SHELL", "/usr/bin/fish"), "/usr/bin/fish"::equals)
        );
        assertEquals(
            java.util.List.of("/usr/bin/fish"),
            ShellCommand.interactiveCommand(Map.of("SHELL", "/usr/bin/fish"), "/usr/bin/fish"::equals)
        );
    }

    @Test
    void fallsBackToPosixShellWhenOnlyShExists() {
        assertEquals(
            java.util.List.of("/bin/sh", "-c", "echo ok"),
            ShellCommand.forCommand("echo ok", Map.of(), "/bin/sh"::equals)
        );
    }

    @Test
    void keepsPathLookupFallbackWhenNoKnownShellPathExists() {
        assertEquals(
            java.util.List.of("sh", "-c", "echo ok"),
            ShellCommand.forCommand("echo ok", Map.of(), path -> false)
        );
    }

    @Test
    void interactiveShellUsesLoginModeForBashAndZsh() {
        assertEquals(
            java.util.List.of("/bin/zsh", "-l"),
            ShellCommand.interactiveCommand(Map.of("SHELL", "/bin/zsh"), "/bin/zsh"::equals)
        );
    }

    @Test
    void interactiveShellUsesPlainCommandForPosixSh() {
        assertEquals(
            java.util.List.of("/bin/sh"),
            ShellCommand.interactiveCommand(Map.of("SHELL", "/bin/sh"), "/bin/sh"::equals)
        );
    }

    @Test
    void resolvesNativeWindowsPowerShellAndUsesItsDirectCommandConvention() {
        Map<String, String> environment = Map.of("SystemRoot", "C:\\Windows", "ComSpec", "C:\\Windows\\System32\\cmd.exe");
        java.util.function.Predicate<String> executable = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"::equals;

        assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            ShellCommand.resolveShell(environment, executable, true));
        assertEquals(java.util.List.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo"),
            ShellCommand.interactiveCommand(environment, executable, true));
        assertEquals(java.util.List.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoProfile", "-Command", "Get-Date"),
            ShellCommand.forCommand("Get-Date", environment, executable, true));
    }

    @Test
    void fallsBackToCmdWhenWindowsPowerShellIsUnavailable() {
        Map<String, String> environment = Map.of("ComSpec", "C:\\Windows\\System32\\cmd.exe");
        java.util.function.Predicate<String> executable = "C:\\Windows\\System32\\cmd.exe"::equals;

        assertEquals(java.util.List.of("C:\\Windows\\System32\\cmd.exe", "/d", "/s", "/c", "echo ok"),
            ShellCommand.forCommand("echo ok", environment, executable, true));
        assertEquals(java.util.List.of("C:\\Windows\\System32\\cmd.exe"), ShellCommand.interactiveCommand(environment, executable, true));
    }

    @Test
    void directCommandPreservesQuotedArgumentsWithoutStartingAShell() {
        assertEquals(
            java.util.List.of("tool", "two words", "plain value", ""),
            ShellCommand.directCommand("tool \"two words\" plain\\ value \"\"")
        );
        assertThrows(IllegalArgumentException.class, () -> ShellCommand.directCommand("tool 'unterminated"));
    }

    @Test
    void nonLoginShellAndPosixQuotingPreserveLiteralArguments() {
        assertEquals(
            java.util.List.of("/usr/bin/bash", "-c", "echo ok"),
            ShellCommand.nonLoginForCommand("echo ok", Map.of("SHELL", "/usr/bin/bash"), "/usr/bin/bash"::equals)
        );
        assertEquals("'printf' '%s' 'file'\"'\"'s value' '$HOME'",
            ShellCommand.posixQuotedCommand(java.util.List.of("printf", "%s", "file's value", "$HOME")));
    }
}
