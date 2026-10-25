package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSearchQueryServiceTest {
    @TempDir Path temporary;

    @Test void localIgnoreRulesHonorNestedNegationDirectoryPruningEscapesAndDoubleStars() throws Exception {
        Path root = project();
        write(root, ".gitignore", "*.log\n!keep.log\n/cache/\n!cache/keep.txt\n**/secret?.txt\n\\#literal\n\\!literal\nspace\\ \n");
        write(root, "sub/.gitignore", "!debug.log\n/local.txt\n");
        for (String file : List.of("main.java", "debug.log", "keep.log", "cache/keep.txt", "sub/debug.log", "sub/local.txt", "sub/deep/local.txt",
            "sub/secret1.txt", "sub/deep/secret2.txt", "#literal", "!literal", ".git/config", ".shed-edit-backup-fixture/original")) write(root, file, "needle");
        if (!System.getProperty("os.name").startsWith("Windows")) write(root, "space ", "needle");
        var workspace = workspace(root, AgentPolicy.readOnly(), List.of());
        Map<String, Object> result = files(workspace, Map.of());
        assertEquals(List.of(".gitignore", "keep.log", "main.java", "sub/.gitignore", "sub/debug.log", "sub/deep/local.txt"), paths(result));
        assertEquals(true, result.get("complete"));
        assertEquals("workspace-gitignore-files", result.get("ignoreMode"));
        var all = paths(files(workspace, Map.of("includeIgnored", true)));
        assertTrue(all.contains("cache/keep.txt")); assertTrue(all.contains("sub/secret1.txt"));
        assertFalse(all.contains(".git/config")); assertFalse(all.contains(".shed-edit-backup-fixture/original"));
    }

    @Test void listingPagesHaveStableMembershipVersionsAndDetectChangesIncludingUnsavedFiles() throws Exception {
        Path root = project(); write(root, "a.txt", "a"); write(root, "b.txt", "b");
        var draft = document(root, "draft", "new.txt", "v7", "unsaved", true);
        var workspace = workspace(root, AgentPolicy.readOnly(), List.of(draft));
        var first = files(workspace, Map.of("limit", 1));
        assertEquals(List.of("a.txt"), paths(first)); assertEquals(1L, first.get("nextOffset"));
        assertEquals(true, first.get("scanComplete")); assertEquals(false, first.get("complete"));
        String version = (String) first.get("listingVersion");
        var second = files(workspace, Map.of("offset", 1, "limit", 2, "expectedListingVersion", version));
        assertEquals(List.of("b.txt", "new.txt"), paths(second)); assertNull(second.get("nextOffset"));
        var entry = MiniJson.asObject(MiniJson.asArray(second.get("files")).get(1));
        assertEquals("v7", entry.get("version")); assertEquals(true, entry.get("dirty")); assertNull(entry.get("diskBytes"));
        write(root, "c.txt", "human");
        assertEquals("STALE_VERSION", error(workspace, "files/list", Map.of("offset", 1, "expectedListingVersion", version)));
        assertFalse(Files.exists(root.resolve("new.txt")));
    }

    @Test void searchUsesOneUnsavedCaptureAndReturnsExactVersionsUnicodeOffsetsAndBomText() throws Exception {
        Path root = project(); write(root, "open.txt", "saved needle");
        Files.write(root.resolve("utf16.txt"), "needle\r\n👩‍💻 needle".getBytes(StandardCharsets.UTF_16));
        var open = document(root, "editor", "open.txt", "v8", "human 👩‍💻 needle\n", true);
        var scratch = document(root, "scratch", null, "v2", "scratch needle", true);
        AtomicInteger captures = new AtomicInteger();
        var workspace = new WorkspaceQueryService(root, AgentPolicy.readOnly(), () -> { captures.incrementAndGet(); return List.of(open, scratch); });
        var result = search(workspace, Map.of("query", "needle"));
        assertEquals(1, captures.get()); assertEquals(true, result.get("complete"));
        List<Map<String, Object>> matches = matches(result); assertEquals(4, matches.size());
        var editor = matches.stream().filter(match -> match.get("documentId").equals("editor")).findFirst().orElseThrow();
        assertEquals("v8", editor.get("version")); assertEquals("EDITOR", editor.get("source")); assertEquals(true, editor.get("dirty"));
        assertEquals((long) open.content().indexOf("needle"), MiniJson.asObject(editor.get("range")).get("startOffset"));
        var disk = matches.stream().filter(match -> match.get("source").equals("DISK") && MiniJson.asObject(match.get("range")).get("line").equals(2L)).findFirst().orElseThrow();
        assertEquals(7L, MiniJson.asObject(disk.get("range")).get("column"));
        var read = workspace.read(workspace.workspaceId(), (String) disk.get("documentId"), (String) disk.get("version"), 0, 100);
        assertEquals("needle\n👩‍💻 needle", read.get("content"));
        assertTrue(matches.stream().anyMatch(match -> match.get("documentId").equals("scratch") && match.get("uri") == null));
        assertEquals("saved needle", Files.readString(root.resolve("open.txt")));
    }

    @Test void policyAndIgnoreRulesApplyToOpenBuffersAndCannotBeOverriddenByQueryFilters() throws Exception {
        Path root = project(); write(root, ".gitignore", "ignored/\n");
        write(root, "secret/token.txt", "needle"); write(root, "ignored/file.txt", "needle"); write(root, "visible.txt", "needle");
        var policy = new AgentPolicy(Set.of(AgentPolicy.Capability.READ), List.of("**/README.md"), List.of("secret"), true);
        var workspace = workspace(root, policy, List.of(document(root, "secret", "secret/token.txt", "v1", "needle secret", true),
            document(root, "ignored", "ignored/file.txt", "v2", "needle ignored", true)));
        assertEquals(1, matches(search(workspace, Map.of("query", "needle"))).size());
        var included = matches(search(workspace, Map.of("query", "needle", "includeIgnored", true)));
        assertEquals(2, included.size()); assertFalse(included.stream().anyMatch(match -> match.get("documentId").equals("secret")));
        assertEquals(List.of(), matches(search(workspace, Map.of("query", "needle", "scope", "open"))));
        var pendingIgnore = document(root, "ignore-draft", ".gitignore", "v4", "visible.txt\nignored/\n", true);
        workspace = workspace(root, policy, List.of(pendingIgnore));
        assertEquals(List.of(), matches(search(workspace, Map.of("query", "needle"))));
        assertEquals("ignored/\n", Files.readString(root.resolve(".gitignore")));
    }

    @Test void matchingSharesCaseWordRegexAndPathFiltersWithEditorSearch() throws Exception {
        Path root = project(); write(root, "a.java", "Cat catfish concatenate\nCAFÉ caféine\nx=12\n"); write(root, "skip.java", "cat"); write(root, "a.txt", "cat");
        var workspace = workspace(root, AgentPolicy.readOnly(), List.of());
        assertEquals(1, matches(search(workspace, Map.of("query", "cat", "caseSensitive", false, "wholeWord", true, "include", "**/*.java", "exclude", "skip.java"))).size());
        assertEquals(1, matches(search(workspace, Map.of("query", "café", "caseSensitive", false, "wholeWord", true, "include", "a.java"))).size());
        assertEquals(3, matches(search(workspace, Map.of("query", " ", "include", "a.java"))).size());
        var regex = matches(search(workspace, Map.of("query", "x=\\d+", "regex", true, "include", "a.java"))).getFirst();
        assertEquals(3L, MiniJson.asObject(regex.get("range")).get("line"));
        assertEquals("INVALID_REQUEST", error(workspace, "search/text", Map.of("query", "[", "regex", true)));
        assertEquals("INVALID_REQUEST", error(workspace, "search/text", Map.of("query", "cat", "regex", "true")));
        assertEquals("INVALID_REQUEST", error(workspace, "search/text", Map.of("query", "cat", "include", "[")));
        assertEquals("INVALID_REQUEST", error(workspace, "search/text", Map.of("query", "cat", "scope", "all")));
    }

    @Test void limitsReportIncompleteWorkWithoutInventingExtraMatchesOrFallingBackFromPartialBuffers() throws Exception {
        Path root = project(); write(root, "a.txt", "needle needle");
        var workspace = workspace(root, AgentPolicy.readOnly(), List.of());
        var exact = search(workspace, Map.of("query", "needle", "limit", 2));
        assertEquals(true, exact.get("complete")); assertEquals(false, exact.get("truncated"));
        var limited = search(workspace, Map.of("query", "needle", "limit", 1));
        assertEquals(1, matches(limited).size()); assertTrue(MiniJson.asArray(limited.get("incompleteReasons")).contains("RESULT_LIMIT"));
        write(root, "b.txt", "needle");
        assertTrue(MiniJson.asArray(search(workspace, Map.of("query", "needle", "maxFiles", 1)).get("incompleteReasons")).contains("FILE_LIMIT"));
        assertTrue(MiniJson.asArray(search(workspace, Map.of("query", "needle", "maxFileBytes", 3)).get("incompleteReasons")).contains("FILE_SIZE_LIMIT"));
        var partial = document(root, "partial", "a.txt", "v1", "partial needle", false);
        workspace = workspace(root, AgentPolicy.readOnly(), List.of(partial));
        var incomplete = search(workspace, Map.of("query", "needle"));
        assertEquals(1, matches(incomplete).size()); assertTrue(MiniJson.asArray(incomplete.get("incompleteReasons")).contains("PARTIAL_DOCUMENT"));
    }

    @Test void unreadableIgnoreRulesFailClosedAndCancellationIsAnErrorRatherThanAnEmptySuccess() throws Exception {
        Path root = project(); write(root, ".gitignore", "x".repeat(4097)); write(root, "secret.txt", "needle");
        var workspace = workspace(root, AgentPolicy.readOnly(), List.of());
        var result = search(workspace, Map.of("query", "needle"));
        assertEquals(List.of(), matches(result)); assertEquals(false, result.get("complete"));
        assertTrue(MiniJson.asArray(result.get("incompleteReasons")).contains("IGNORE_UNAVAILABLE"));
        var service = new WorkspaceSearchQueryService(workspace);
        assertEquals(WorkspaceApiException.Code.CANCELLED, assertThrows(WorkspaceApiException.class,
            () -> service.search(workspace.workspaceId(), new WorkspaceDiscoveryService.Filters("", "", false), false,
                SearchPattern.literal("needle"), 100, 100, 1000, 5000, () -> true)).code());
    }

    @Test void expiredDeadlineAndExpensiveExpressionsRemainBounded() throws Exception {
        Path root = project(); write(root, "long.txt", "a".repeat(100_000) + "!");
        var workspace = workspace(root, AgentPolicy.readOnly(), List.of());
        var service = new WorkspaceSearchQueryService(workspace);
        long started = System.nanoTime();
        var result = service.search(workspace.workspaceId(), new WorkspaceDiscoveryService.Filters("", "", false), false,
            new SearchPattern("(a+)+$", true, true, false), 100, 100, 200_000, 50, () -> false);
        assertEquals(false, result.get("complete"));
        assertTrue(System.nanoTime() - started < 5_000_000_000L);
    }

    @Test void symbolicLinksAndRetainedArtifactsCannotExposeOutsideOrExcludedContents() throws Exception {
        Path root = project(); Path outside = write(temporary, "outside.txt", "needle outside");
        write(root, "main.txt", "needle main"); write(root, ".shed-edit-backup-fixture/original", "needle retained");
        try {
            Files.createSymbolicLink(root.resolve("outside-link"), outside);
            Files.createSymbolicLink(root.resolve("retained-link"), root.resolve(".shed-edit-backup-fixture/original"));
        } catch (UnsupportedOperationException | java.io.IOException unavailable) { org.junit.jupiter.api.Assumptions.assumeTrue(false, "Filesystem cannot create symbolic links"); }
        var workspace = workspace(root, AgentPolicy.readOnly(), List.of());
        var result = search(workspace, Map.of("query", "needle", "includeIgnored", true));
        assertEquals(1, matches(result).size()); assertTrue(matches(result).getFirst().get("preview").toString().contains("main"));
    }

    @Test void wrongWorkspaceAndReadDenialPrecedeEditorCapture() throws Exception {
        Path root = project(); AtomicInteger captures = new AtomicInteger();
        var denied = new AgentPolicy(Set.of(), List.of(), List.of(), false);
        var workspace = new WorkspaceQueryService(root, denied, () -> { captures.incrementAndGet(); return List.of(); });
        assertEquals("PERMISSION_DENIED", error(workspace, "search/text", Map.of("query", "needle")));
        assertEquals("PERMISSION_DENIED", error(workspace, "files/list", Map.of())); assertEquals(0, captures.get());
        var permitted = new WorkspaceQueryService(root, AgentPolicy.readOnly(), () -> { captures.incrementAndGet(); return List.of(); });
        assertEquals("NOT_FOUND", error(permitted, "files/list", Map.of("workspaceId", "other"))); assertEquals(0, captures.get());
        assertEquals(WorkspaceApiException.Code.CANCELLED, assertThrows(WorkspaceApiException.class,
            () -> new WorkspaceSearchQueryService(permitted).files(permitted.workspaceId(), new WorkspaceDiscoveryService.Filters("", "", false),
                false, 0, 100, null, 5000, () -> true)).code());
        assertEquals(0, captures.get());
    }

    private Path project() throws Exception { return Files.createDirectory(temporary.resolve("project")).toRealPath(); }
    private static Path write(Path root, String path, String text) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); return Files.writeString(file, text);
    }
    private static WorkspaceQueryService workspace(Path root, AgentPolicy policy, List<WorkspaceDocument> documents) {
        return new WorkspaceQueryService(root, policy, () -> documents);
    }
    private static WorkspaceDocument document(Path root, String id, String path, String version, String text, boolean complete) {
        return new WorkspaceDocument(WorkspacePaths.id(root), id, path == null ? null : root.resolve(path).toUri().toString(), version,
            text, true, false, complete, "UTF-8", "LF", false, WorkspaceDocument.Source.EDITOR);
    }
    static Map<String, Object> files(WorkspaceQueryService workspace, Map<String, Object> params) { return result(workspace, "files/list", params); }
    static Map<String, Object> search(WorkspaceQueryService workspace, Map<String, Object> params) { return result(workspace, "search/text", params); }
    private static Map<String, Object> result(WorkspaceQueryService workspace, String method, Map<String, Object> params) {
        Map<String, Object> response = response(workspace, method, params); assertFalse(response.containsKey("error"), response::toString);
        return MiniJson.asObject(response.get("result"));
    }
    private static String error(WorkspaceQueryService workspace, String method, Map<String, Object> params) {
        return (String) MiniJson.asObject(MiniJson.asObject(response(workspace, method, params).get("error")).get("data")).get("code");
    }
    private static Map<String, Object> response(WorkspaceQueryService workspace, String method, Map<String, Object> params) {
        Map<String, Object> values = new LinkedHashMap<>(); values.put("workspaceId", workspace.workspaceId()); values.putAll(params);
        return MiniJson.asObject(MiniJson.parseStrict(new WorkspaceRpc(workspace).handle(WorkspaceRpcTest.request(1, method, values))));
    }
    private static List<String> paths(Map<String, Object> result) {
        return MiniJson.asArray(result.get("files")).stream().map(value -> (String) MiniJson.asObject(value).get("path")).toList();
    }
    static List<Map<String, Object>> matches(Map<String, Object> result) {
        return MiniJson.asArray(result.get("matches")).stream().map(MiniJson::asObject).toList();
    }
}
