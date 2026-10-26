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
        Process process = startIsolated(new ProcessBuilder(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            "shed.Texteditor", "--workspace-api", "--root", root.toString(), "--request", request));
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

    @Test void headlessSearchWorksWithoutGitOnPathAndDoesNotCreateWorkspaceOrHomeState() throws Exception {
        Path project = Files.createDirectory(root.resolve("project")).toRealPath();
        Path home = Files.createDirectory(root.resolve("home"));
        Files.writeString(project.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(project.resolve("ignored.txt"), "needle private");
        Files.writeString(project.resolve("source.txt"), "needle public");
        String request = WorkspaceRpcTest.request("search", "search/text", Map.of("workspaceId", WorkspacePaths.id(project), "query", "needle"));
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(executable, "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
            "shed.Texteditor", "--workspace-api", "--root", project.toString(), "--request", request);
        builder.environment().put("PATH", root.resolve("no-executables").toString());
        Process process = startIsolated(builder);
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Headless search must exit");
            String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), errors); assertEquals("", errors);
            var response = MiniJson.asObject(MiniJson.parseStrict(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
            var result = MiniJson.asObject(response.get("result"));
            assertEquals(true, result.get("complete"));
            var matches = WorkspaceSearchQueryServiceTest.matches(result);
            assertEquals(1, matches.size()); assertEquals("needle public", matches.getFirst().get("preview"));
            try (var files = Files.list(project)) { assertEquals(3, files.count()); }
            try (var files = Files.list(home)) { assertEquals(0, files.count()); }
            assertEquals("needle private", Files.readString(project.resolve("ignored.txt")));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void filePaginationCanContinueFromAFreshHeadlessProcess() throws Exception {
        Path project = Files.createDirectory(root.resolve("project")).toRealPath();
        Files.writeString(project.resolve("a.txt"), "a"); Files.writeString(project.resolve("b.txt"), "b");
        String workspace = WorkspacePaths.id(project);
        Map<String, Object> first = filePage(project, Map.of("workspaceId", workspace, "limit", 1));
        Map<String, Object> second = filePage(project, Map.of("workspaceId", workspace, "limit", 1,
            "offset", 1, "expectedListingVersion", first.get("listingVersion")));
        assertEquals(first.get("listingVersion"), second.get("listingVersion"));
        assertEquals("b.txt", MiniJson.asObject(MiniJson.asArray(second.get("files")).getFirst()).get("path"));
        assertNull(second.get("nextOffset"));
    }

    private static Map<String, Object> filePage(Path root, Map<String, Object> params) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = startIsolated(new ProcessBuilder(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            "shed.Texteditor", "--workspace-api", "--root", root.toString(), "--request", WorkspaceRpcTest.request(1, "files/list", params)));
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output + new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            return MiniJson.asObject(MiniJson.asObject(MiniJson.parseStrict(output)).get("result"));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static Process startIsolated(ProcessBuilder builder) throws java.io.IOException {
        // These children set headless mode explicitly. Inherited launcher options emit JVM
        // banners on stderr and would contaminate assertions about the application's diagnostics.
        for (String name : java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(name);
        return builder.start();
    }

    private String[] args() { return new String[]{"--workspace-api", "--root", root.toString(), "--stdio"}; }
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}
