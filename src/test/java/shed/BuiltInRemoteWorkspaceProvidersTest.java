package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltInRemoteWorkspaceProvidersTest {
    @Test
    void routesGitAndSshUrisWithoutAcceptingPasswordUris() {
        var providers = BuiltInRemoteWorkspaceProviders.all();
        var git = providers.stream().filter(provider -> provider.id().equals("git")).findFirst().orElseThrow();
        var ssh = providers.stream().filter(provider -> provider.id().equals("ssh")).findFirst().orElseThrow();
        var container = providers.stream().filter(provider -> provider.id().equals("container")).findFirst().orElseThrow();

        assertTrue(git.supports(URI.create("git+https://example.test/group/project.git")));
        assertTrue(ssh.supports(URI.create("ssh://developer@example.test/var/project")));
        assertFalse(git.supports(URI.create("https://user:secret@example.test/group/project.git")));
        assertFalse(ssh.supports(URI.create("ssh://developer@example.test/var/../project")));
        assertTrue(container.supports(URI.create("container://dev-api/workspace")));
        assertFalse(container.supports(URI.create("container://dev-api/workspace/../etc")));
    }

    @Test
    void buildsDirectArgvRemoteLanguageServerInvocations() throws Exception {
        assertEquals(List.of("ssh", "-p", "2222", "developer@example.test", "cd -- '/srv/project' && exec 'pyright-langserver' '--stdio'"),
            BuiltInRemoteWorkspaceProviders.sshLanguageServerInvocation(URI.create("ssh://developer@example.test:2222/srv/project"),
                List.of("pyright-langserver", "--stdio")));
        assertEquals(List.of("docker", "exec", "-i", "--workdir", "/workspace", "dev-api", "pylsp"),
            BuiltInRemoteWorkspaceProviders.containerLanguageServerInvocation(URI.create("container://dev-api/workspace"), List.of("pylsp")));
        assertEquals(List.of("wsl.exe", "-d", "Ubuntu", "--cd", "/workspace", "--", "gopls", "serve"),
            BuiltInRemoteWorkspaceProviders.wslLanguageServerInvocation(URI.create("wsl://Ubuntu/workspace"), List.of("gopls", "serve")));
    }
}
