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
    void directCommandPreservesQuotedArgumentsWithoutStartingAShell() {
        assertEquals(
            java.util.List.of("tool", "two words", "plain value", ""),
            ShellCommand.directCommand("tool \"two words\" plain\\ value \"\"")
        );
        assertThrows(IllegalArgumentException.class, () -> ShellCommand.directCommand("tool 'unterminated"));
    }
}
