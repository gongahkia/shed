package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** static Java-child admission checks; this class never starts a process. */
class WorkspaceCliProcessGuardTest {
    static ProcessBuilder builder(String... args) {
        var command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), "shed.Texteditor"));
        command.addAll(List.of(args)); return new ProcessBuilder(command);
    }

    @Test void onlyTheHeadlessCliAndDisposablePathsAreAdmitted() throws Exception {
        var prepared = WorkspaceCliTestProcess.prepare(builder("--workspace-api", "--root", System.getProperty("user.home"), "--stdio"));
        assertTrue(prepared.command().contains("-Djava.awt.headless=true"));
        assertTrue(prepared.command().contains("-Duser.home=" + System.getProperty("user.home")));
        assertTrue(prepared.command().contains("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir")));
        assertTrue(prepared.command().contains("-Djava.util.prefs.userRoot=" + System.getProperty("java.util.prefs.userRoot")));
        assertEquals(System.getProperty("user.home"), prepared.environment().get("HOME"));
        assertFalse(prepared.environment().containsKey("JAVA_TOOL_OPTIONS")); assertFalse(prepared.environment().containsKey("SSH_AUTH_SOCK"));
        assertEquals(10, prepared.environment().size());
    }

    @Test void guiArbitraryMainsInjectedJvmOptionsAndOutsidePathsFailBeforeLaunch() {
        assertThrows(IOException.class, () -> WorkspaceCliTestProcess.prepare(builder(".")));
        assertThrows(IOException.class, () -> WorkspaceCliTestProcess.prepare(builder("--workspace-api", "--root", "/")));
        assertThrows(IOException.class, () -> WorkspaceCliTestProcess.prepare(builder("--workspace-api", "--execute", "synthetic")));
        var injected = builder("--workspace-api", "--status"); injected.command().add(1, "-javaagent:synthetic");
        assertThrows(IOException.class, () -> WorkspaceCliTestProcess.prepare(injected));
        var wrong = builder("--workspace-api", "--status"); wrong.command().set(4, "synthetic.OtherMain");
        assertThrows(IOException.class, () -> WorkspaceCliTestProcess.prepare(wrong));
    }
}
