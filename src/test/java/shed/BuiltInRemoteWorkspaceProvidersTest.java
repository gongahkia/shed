package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void buildsNarrowArtifactRetrievalCommands(@TempDir Path tempDir) throws Exception {
        Path destination = tempDir.resolve("remote-reports");
        URI ssh = URI.create("ssh://developer@example.test:2222/srv/project");
        URI container = URI.create("container://dev-api/workspace");

        assertEquals(List.of("rsync", "-az", "--protect-args", "--safe-links", "-e", "ssh -p 2222",
            "developer@example.test:/srv/project/target/surefire-reports/", destination + "/"),
            BuiltInRemoteWorkspaceProviders.sshFetchInvocation(ssh, "/srv/project/target/surefire-reports", destination, true));
        assertEquals(List.of("docker", "cp", "dev-api:/workspace/result.trx", destination.toString()),
            BuiltInRemoteWorkspaceProviders.containerFetchInvocation(container, "/workspace/result.trx", destination, false));
    }

    @Test
    void copiesOnlySafeWorkspaceRelativeArtifacts(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path reports = Files.createDirectories(root.resolve("target/reports"));
        Files.writeString(reports.resolve("result.xml"), "<testsuite/>");
        Path destination = tempDir.resolve("destination");

        Path copied = BuiltInRemoteWorkspaceProviders.copyWorkspacePath(root, "target/reports", destination);

        assertEquals(destination.toAbsolutePath(), copied);
        assertTrue(Files.isRegularFile(copied.resolve("result.xml")));
        assertThrows(IOException.class, () -> BuiltInRemoteWorkspaceProviders.copyWorkspacePath(root, "../outside", destination));
    }

    @Test
    void labelsCappedRemoteOutput() throws Exception {
        byte[] output = new byte[128 * 1024 + 1];
        java.util.Arrays.fill(output, (byte) 'x');

        String captured = BuiltInRemoteWorkspaceProviders.readCapped(new ByteArrayInputStream(output));

        assertTrue(captured.endsWith("\n[shed: output truncated]\n"));
    }
}
