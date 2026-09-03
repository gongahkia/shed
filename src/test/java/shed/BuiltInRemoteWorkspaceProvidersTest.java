package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class BuiltInRemoteWorkspaceProvidersTest {
    @Test
    void routesGitAndSshUrisWithoutAcceptingPasswordUris() {
        var providers = BuiltInRemoteWorkspaceProviders.all();
        var git = providers.stream().filter(provider -> provider.id().equals("git")).findFirst().orElseThrow();
        var ssh = providers.stream().filter(provider -> provider.id().equals("ssh")).findFirst().orElseThrow();

        assertTrue(git.supports(URI.create("git+https://example.test/group/project.git")));
        assertTrue(ssh.supports(URI.create("ssh://developer@example.test/var/project")));
        assertFalse(git.supports(URI.create("https://user:secret@example.test/group/project.git")));
        assertFalse(ssh.supports(URI.create("ssh://developer@example.test/var/../project")));
    }
}
