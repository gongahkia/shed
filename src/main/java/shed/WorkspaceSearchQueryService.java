package shed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Versioned snapshot search. Reads no global configuration, writes no index, and starts no processes. */
final class WorkspaceSearchQueryService {
    static final int DEFAULT_FILE_BYTES = 2 * 1024 * 1024;
    private final WorkspaceQueryService workspace;

    WorkspaceSearchQueryService(WorkspaceQueryService workspace) { this.workspace = workspace; }

    Map<String, Object> files(String workspaceId, WorkspaceDiscoveryService.Filters filters, boolean openOnly,
                              int offset, int limit, String expectedListingVersion, int timeoutMillis, BooleanSupplier cancelled) {
        workspace.checkWorkspace(workspaceId);
        if (offset < 0 || offset > WorkspaceQueryBudget.MAX_ENTRIES || limit < 1 || limit > 1000) throw WorkspaceApiException.invalidRequest("Invalid file page bounds");
        var budget = new WorkspaceQueryBudget(timeoutMillis, cancelled);
        List<WorkspaceDiscoveryService.Entry> entries = List.of();
        try {
            budget.check();
            var discovery = new WorkspaceDiscoveryService(workspace, workspace.visibleDocuments(), filters, budget);
            entries = discovery.scan(openOnly);
        } catch (WorkspaceQueryBudget.Stopped stopped) { }
        boolean scanComplete = budget.complete();
        String version = listingVersion(entries, filters, openOnly, budget.metadata());
        if (expectedListingVersion != null && !version.equals(expectedListingVersion))
            throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "Workspace listing changed; restart pagination", Map.of("currentListingVersion", version));
        if (offset > entries.size()) throw WorkspaceApiException.invalidRequest("File page offset is beyond the available listing");
        List<Map<String, Object>> page = new ArrayList<>();
        int end = offset, characters = 0;
        while (end < entries.size() && page.size() < limit) {
            Map<String, Object> metadata = entries.get(end).metadata(workspace.root());
            int size = MiniJson.stringify(metadata).length();
            if (characters + size > WorkspaceQueryBudget.MAX_RESPONSE_CHARACTERS) { budget.incomplete("RESPONSE_LIMIT"); break; }
            characters += size; page.add(metadata); end++;
        }
        if (end < entries.size()) budget.incomplete("PAGE_LIMIT");
        Map<String, Object> result = base(filters, budget);
        result.put("files", List.copyOf(page)); result.put("listingVersion", version);
        result.put("scanComplete", scanComplete); result.put("offset", offset);
        result.put("nextOffset", end < entries.size() ? end : null);
        result.put("totalKnown", entries.size()); result.put("total", scanComplete ? entries.size() : null);
        result.put("truncated", !budget.complete());
        return result;
    }

    Map<String, Object> search(String workspaceId, WorkspaceDiscoveryService.Filters filters, boolean openOnly,
                               SearchPattern query, int limit, int maxFiles, int maxFileBytes, int timeoutMillis, BooleanSupplier cancelled) {
        workspace.checkWorkspace(workspaceId);
        if (limit < 1 || limit > 1000 || maxFiles < 1 || maxFiles > WorkspaceQueryBudget.MAX_ENTRIES
            || maxFileBytes < 1 || maxFileBytes > WorkspaceTextFile.MAX_BYTES) throw WorkspaceApiException.invalidRequest("Invalid search bounds");
        Pattern compiled;
        try { compiled = query.compile(); }
        catch (IllegalArgumentException invalid) { throw WorkspaceApiException.invalidRequest(invalid.getMessage()); }
        var budget = new WorkspaceQueryBudget(timeoutMillis, cancelled);
        List<Map<String, Object>> matches = new ArrayList<>();
        Search search = new Search(query, compiled, limit, maxFiles, maxFileBytes, matches, budget);
        try {
            budget.check();
            var discovery = new WorkspaceDiscoveryService(workspace, workspace.visibleDocuments(), filters, budget);
            for (var entry : discovery.scan(openOnly)) {
                budget.check();
                search.nextFile();
                WorkspaceDocument document = entry.open();
                if (document == null) {
                    try {
                        Path path = WorkspacePaths.resolve(workspace.root(), entry.path().toString());
                        if (!path.equals(entry.path())) { budget.incomplete("PATH_CHANGED"); budget.count("changedFiles"); continue; }
                        workspace.policy().checkPath(workspace.root().relativize(path), AgentPolicy.Capability.READ);
                        document = WorkspaceTextFile.capture(workspace.workspaceId(), path, false, maxFileBytes, budget::cancelled).document();
                    } catch (WorkspaceApiException failure) {
                        if (failure.code() == WorkspaceApiException.Code.CANCELLED) throw failure;
                        budget.incomplete(failure.code() == WorkspaceApiException.Code.LIMIT_EXCEEDED ? "FILE_SIZE_LIMIT" : "UNAVAILABLE_DOCUMENT");
                        budget.count("unavailableDocuments"); continue;
                    }
                }
                search.document(document);
            }
            for (WorkspaceDocument document : discovery.scratchDocuments()) {
                budget.check(); search.nextFile(); search.document(document);
            }
        } catch (WorkspaceQueryBudget.Stopped stopped) { }
        catch (java.util.concurrent.CancellationException stopped) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED, "Workspace search cancelled");
        } catch (IllegalArgumentException | StackOverflowError expensive) {
            budget.incomplete("EXPRESSION_LIMIT");
        }
        Map<String, Object> result = base(filters, budget);
        result.put("matches", List.copyOf(matches)); result.put("truncated", !budget.complete());
        result.put("scope", openOnly ? "open" : "workspace"); result.put("lineOriented", true);
        result.put("offsetEncoding", "utf-16"); result.put("positionBase", 1);
        result.put("limits", Map.of("matches", limit, "files", maxFiles, "fileBytes", maxFileBytes,
            "documentCharacters", maxFileBytes, "timeoutMillis", timeoutMillis));
        return result;
    }

    private Map<String, Object> base(WorkspaceDiscoveryService.Filters filters, WorkspaceQueryBudget budget) {
        Map<String, Object> result = new LinkedHashMap<>(budget.metadata());
        result.put("workspaceId", workspace.workspaceId());
        result.put("ignoreMode", filters.includeIgnored() ? "explicit-filters-only" : "workspace-gitignore-files");
        result.put("globalGitConfigurationApplied", false);
        result.put("atomicWorkspaceSnapshot", false);
        return result;
    }

    private String listingVersion(List<WorkspaceDiscoveryService.Entry> entries, WorkspaceDiscoveryService.Filters filters,
                                  boolean openOnly, Map<String, Object> scan) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(MiniJson.stringify(new java.util.TreeMap<>(Map.of("workspaceId", workspace.workspaceId(), "include", filters.include(),
                "exclude", filters.exclude(), "includeIgnored", filters.includeIgnored(), "openOnly", openOnly,
                "complete", scan.get("complete"), "reasons", scan.get("incompleteReasons")))).getBytes(StandardCharsets.UTF_8));
            for (var entry : entries) digest.update(MiniJson.stringify(entry.metadata(workspace.root())).getBytes(StandardCharsets.UTF_8));
            return "listing-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Search {
        private final SearchPattern query;
        private final Pattern compiled;
        private final int limit, maxFiles, maxCharacters;
        private final List<Map<String, Object>> matches;
        private final WorkspaceQueryBudget budget;
        private int files, responseCharacters;
        Search(SearchPattern query, Pattern compiled, int limit, int maxFiles, int maxCharacters,
               List<Map<String, Object>> matches, WorkspaceQueryBudget budget) {
            this.query = query; this.compiled = compiled; this.limit = limit; this.maxFiles = maxFiles;
            this.maxCharacters = maxCharacters; this.matches = matches; this.budget = budget;
        }
        void nextFile() { if (files++ == maxFiles) budget.stop("FILE_LIMIT"); }
        void document(WorkspaceDocument document) {
            budget.check();
            if (!document.complete()) { budget.incomplete("PARTIAL_DOCUMENT"); budget.count("partialDocuments"); return; }
            String text = document.content();
            if (text.length() > maxCharacters) { budget.incomplete("FILE_SIZE_LIMIT"); budget.count("oversizedDocuments"); return; }
            budget.text(text.length()); budget.count("readDocuments");
            int line = 1, start = 0;
            while (start <= text.length()) {
                budget.check();
                int end = text.indexOf('\n', start); if (end < 0) end = text.length();
                var matcher = query.matcher(compiled, text.substring(start, end), budget::cancelled);
                while (matcher.find()) {
                    budget.check();
                    if (matches.size() == limit) budget.stop("RESULT_LIMIT");
                    int first = start + matcher.start(), last = start + matcher.end();
                    Map<String, Object> match = new LinkedHashMap<>();
                    match.put("documentId", document.documentId()); match.put("uri", document.uri()); match.put("version", document.version());
                    match.put("source", document.source().name()); match.put("dirty", document.dirty());
                    match.put("range", Map.of("startOffset", first, "endOffset", last, "line", line,
                        "column", matcher.start() + 1, "endColumn", matcher.end() + 1));
                    int previewStart = GraphemeBoundary.floor(text, Math.max(start, first - 80));
                    int previewEnd = GraphemeBoundary.floor(text, Math.min(end, previewStart + 320));
                    match.put("preview", text.substring(previewStart, previewEnd)); match.put("previewStartOffset", previewStart);
                    match.put("previewTruncated", previewStart != start || previewEnd != end);
                    int size = MiniJson.stringify(match).length();
                    if (responseCharacters + size > WorkspaceQueryBudget.MAX_RESPONSE_CHARACTERS) budget.stop("RESPONSE_LIMIT");
                    responseCharacters += size; matches.add(match);
                }
                if (end == text.length()) break;
                start = end + 1; line++;
            }
            budget.count("searchedDocuments");
        }
    }
}
