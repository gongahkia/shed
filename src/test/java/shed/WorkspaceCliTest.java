package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceCliTest {
    @TempDir Path root;

    @Test void stdioAcceptsCrLfAndFinalFramesWithoutNewlineAndKeepsDiagnosticsSeparate() {
        String request = WorkspaceRpcTest.request(1, "workspace/info", Map.of());
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
        int exit = WorkspaceCli.run(args(), bytes(request + "\r\n" + request), output, errors);
        assertEquals(0, exit); assertEquals("", errors.toString(StandardCharsets.UTF_8));
        var replies = output.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, replies.size());
        assertEquals(false, MiniJson.asObject(MiniJson.asObject(MiniJson.parseStrict(replies.getFirst())).get("result")).get("editorConnected"));
    }

    @Test void malformedFramesAreReportedAndOversizedFramesTerminateTheStream() {
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
        int exit = WorkspaceCli.run(args(), bytes("{bad}\n" + WorkspaceRpcTest.request(1, "workspace/info", Map.of())), output, errors);
        assertEquals(0, exit); assertEquals(2, output.toString(StandardCharsets.UTF_8).lines().count());
        output.reset();
        exit = WorkspaceCli.run(args(), bytes(" ".repeat(WorkspaceCli.MAX_FRAME_BYTES + 1)), output, errors);
        assertEquals(65, exit); assertTrue(output.toString(StandardCharsets.UTF_8).contains("LIMIT_EXCEEDED"));
        output.reset();
        exit = WorkspaceCli.run(args(), new ByteArrayInputStream(new byte[]{(byte) 0xff, '\n'}), output, errors);
        assertEquals(0, exit); assertTrue(output.toString(StandardCharsets.UTF_8).contains("valid UTF-8"));
    }

    @Test void oneShotErrorsHaveNonzeroExitAndNeverCreateTargets() {
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
        String request = WorkspaceRpcTest.request(1, "documents/write", Map.of("document", "README.md"));
        int exit = WorkspaceCli.run(new String[]{"--workspace-api", "--root", root.toString(), "--request", request}, bytes(""), output, errors);
        assertEquals(65, exit); assertTrue(output.toString(StandardCharsets.UTF_8).contains("UNSUPPORTED"));
        assertFalse(Files.exists(root.resolve("README.md")));
        output.reset();
        assertEquals(64, WorkspaceCli.run(new String[]{"--workspace-api"}, bytes(""), output, errors));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    @Test void realApplicationEntryPointAnswersAndExitsWithHeadlessAwt() throws Exception {
        String request = WorkspaceRpcTest.request("headless", "workspace/info", Map.of());
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            "shed.Texteditor", "--workspace-api", "--root", root.toString(), "--request", request).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Headless workspace entry must exit without a window/event loop");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), errors);
            var result = MiniJson.asObject(MiniJson.parseStrict(output));
            assertEquals("headless", result.get("id")); assertNotNull(result.get("result"));
            assertEquals("", errors);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private String[] args() { return new String[]{"--workspace-api", "--root", root.toString(), "--stdio"}; }
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}
