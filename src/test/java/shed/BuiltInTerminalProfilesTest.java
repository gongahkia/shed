package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltInTerminalProfilesTest {
    @Test
    void detectsExecutablePosixProfilesWithoutStartingThem() {
        String path = "/tools" + File.pathSeparator + "/other";
        List<BuiltInTerminalProfiles.Profile> profiles = BuiltInTerminalProfiles.detect(Map.of(
            "SHELL", "/custom/zsh",
            "PATH", path), executable -> executable.equals("/tools/bash") || executable.equals("/custom/zsh")
                || executable.equals("/tools/fish") || executable.equals("/tools/pwsh"), false);

        assertEquals(List.of(
            new BuiltInTerminalProfiles.Profile("bash", "Bash", List.of("/tools/bash", "-l")),
            new BuiltInTerminalProfiles.Profile("zsh", "Zsh", List.of("/custom/zsh", "-l")),
            new BuiltInTerminalProfiles.Profile("fish", "Fish", List.of("/tools/fish")),
            new BuiltInTerminalProfiles.Profile("pwsh", "PowerShell 7", List.of("/tools/pwsh", "-NoLogo"))), profiles);
        assertEquals(profiles.get(1), BuiltInTerminalProfiles.resolve("builtin:zsh", profiles));
        assertNull(BuiltInTerminalProfiles.resolve("builtin:missing", profiles));
    }

    @Test
    void detectsWindowsProfilesUsingWindowsPathRules() {
        List<BuiltInTerminalProfiles.Profile> profiles = BuiltInTerminalProfiles.detect(Map.of(
            "PATH", "C:\\Tools;C:\\Windows",
            "SystemRoot", "C:\\Windows",
            "ComSpec", "C:\\Windows\\System32\\cmd.exe"), executable -> executable.equals("C:\\Tools\\pwsh.exe")
                || executable.equals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")
                || executable.equals("C:\\Windows\\System32\\cmd.exe"), true);

        assertEquals(List.of(
            new BuiltInTerminalProfiles.Profile("pwsh", "PowerShell 7", List.of("C:\\Tools\\pwsh.exe", "-NoLogo")),
            new BuiltInTerminalProfiles.Profile("powershell", "Windows PowerShell",
                List.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo")),
            new BuiltInTerminalProfiles.Profile("cmd", "Command Prompt", List.of("C:\\Windows\\System32\\cmd.exe"))), profiles);
    }
}
