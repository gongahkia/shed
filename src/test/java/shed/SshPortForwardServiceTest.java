package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class SshPortForwardServiceTest {
    @Test
    void buildsLoopbackOnlyBatchModeSshInvocation() throws Exception {
        List<String> invocation = SshPortForwardService.invocation(
            URI.create("ssh://developer@example.test:2222/srv/project"),
            new SshPortForwardService.Spec(8080, "127.0.0.1", 3000));

        assertEquals(List.of("ssh", "-N", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "ExitOnForwardFailure=yes",
            "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3", "-L", "127.0.0.1:8080:127.0.0.1:3000",
            "-p", "2222", "developer@example.test"), invocation);
    }

    @Test
    void rejectsUnsafeEndpointAndForwardInputs() {
        SshPortForwardService.Spec valid = new SshPortForwardService.Spec(8080, "database.internal", 5432);

        assertThrows(IOException.class, () -> SshPortForwardService.invocation(URI.create("https://example.test/project"), valid));
        assertThrows(IOException.class, () -> SshPortForwardService.invocation(URI.create("ssh://developer:secret@example.test/project"), valid));
        assertThrows(IOException.class, () -> SshPortForwardService.invocation(URI.create("ssh://example.test:0/project"), valid));
        assertThrows(IllegalArgumentException.class, () -> new SshPortForwardService.Spec(0, "database.internal", 5432));
        assertThrows(IllegalArgumentException.class, () -> new SshPortForwardService.Spec(8080, "db;bad", 5432));
        assertThrows(IllegalArgumentException.class, () -> new SshPortForwardService.Spec(8080, "database.internal", 65536));
    }
}
