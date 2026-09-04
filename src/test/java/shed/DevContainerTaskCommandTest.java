package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import shed.api.RemoteCommandRequest;

class DevContainerTaskCommandTest {
    @Test
    void buildsDirectRootTaskWithRemoteEnvironment() throws Exception {
        List<String> command = JobQuickfixController.devContainerInvocation(
            Path.of("/host/project"), "/workspaces/project",
            new RemoteCommandRequest(List.of("cargo", "test"), "", Map.of("CI", "true")),
            TaskService.ShellPolicy.DIRECT);

        assertEquals(List.of("devcontainer", "exec", "--workspace-folder", "/host/project",
            "--remote-env", "CI=true", "cargo", "test"), command);
    }

    @Test
    void preservesLoginTaskAndQuotesSubdirectory() throws Exception {
        List<String> command = JobQuickfixController.devContainerInvocation(
            Path.of("/host/project"), "/workspaces/project",
            new RemoteCommandRequest(List.of("sh", "-lc", "npm test"), "packages/web", Map.of()),
            TaskService.ShellPolicy.LOGIN);

        assertEquals(List.of("devcontainer", "exec", "--workspace-folder", "/host/project", "/bin/sh", "-lc",
            "cd -- '/workspaces/project/packages/web' && exec 'sh' '-lc' 'npm test'"), command);
    }

    @Test
    void preservesNonLoginShellTaskAndQuotesSubdirectory() throws Exception {
        List<String> command = JobQuickfixController.devContainerInvocation(
            Path.of("/host/project"), "/workspaces/project",
            new RemoteCommandRequest(List.of("sh", "-c", "'printf' '%s' 'two words'"), "packages/web", Map.of()),
            TaskService.ShellPolicy.SHELL);

        assertEquals(List.of("devcontainer", "exec", "--workspace-folder", "/host/project", "/bin/sh", "-c",
            "cd -- '/workspaces/project/packages/web' && exec 'sh' '-c' ''\"'\"'printf'\"'\"' '\"'\"'%s'\"'\"' '\"'\"'two words'\"'\"''"), command);
    }

    @Test
    void rejectsSubdirectoryForDirectTask() {
        assertThrows(IOException.class, () -> JobQuickfixController.devContainerInvocation(
            Path.of("/host/project"), "/workspaces/project",
            new RemoteCommandRequest(List.of("cargo", "test"), "crates/api", Map.of()),
            TaskService.ShellPolicy.DIRECT));
    }

    @Test
    void acceptsLastWorkspacePathFromCliOutput() {
        assertEquals("/workspaces/project", DevContainerWorkspace.remoteWorkingDirectory("[22 ms] Start: Run\n/workspaces/project\n"));
    }
}
