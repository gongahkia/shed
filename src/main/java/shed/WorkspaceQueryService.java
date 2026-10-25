package shed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicitly scoped, non-UI queries. No command dispatch, process launch, or network access. */
public final class WorkspaceQueryService {
    public static final int MAX_PAGE_SIZE = 1000;
    public static final int MAX_READ_CHARACTERS = 1024 * 1024;
    public static final List<String> OPERATIONS = List.of("workspace/info", "documents/list", "documents/read", "files/list", "search/text");
    private final Path root;
    private final String workspaceId;
    private final AgentPolicy policy;
    private final WorkspaceDocumentSource documents;

    public WorkspaceQueryService(Path root, AgentPolicy policy, WorkspaceDocumentSource documents) {
        this.root = WorkspacePaths.root(Objects.requireNonNull(root));
        this.workspaceId = WorkspacePaths.id(this.root);
        this.policy = Objects.requireNonNull(policy);
        this.documents = Objects.requireNonNull(documents);
    }

    public String workspaceId() { return workspaceId; }
    public Path root() { return root; }
    public AgentPolicy policy() { return policy; }

    public Map<String, Object> info() {
        policy.require(AgentPolicy.Capability.READ);
        return Map.of("schemaVersion", 1, "workspaceId", workspaceId, "root", root.toUri().toString(),
            "editorConnected", documents.connected(), "policy", policy.toMap(),
            "operations", OPERATIONS);
    }

    public Map<String, Object> listDocuments(String requestedWorkspace, int offset, int limit) {
        checkWorkspace(requestedWorkspace);
        bounds(offset, limit, MAX_PAGE_SIZE);
        List<WorkspaceDocument> snapshots = visibleDocuments();
        int start = Math.min(offset, snapshots.size()), end = (int) Math.min((long) start + limit, snapshots.size());
        return Map.of("workspaceId", workspaceId, "documents", snapshots.subList(start, end).stream().map(WorkspaceDocument::metadata).toList(),
            "offset", start, "nextOffset", end, "truncated", end < snapshots.size(), "total", snapshots.size());
    }

    public WorkspaceDocument snapshot(String requestedWorkspace, String document) {
        checkWorkspace(requestedWorkspace);
        if (document == null || document.isBlank()) throw invalid("A document ID, path or file URI is required");
        List<WorkspaceDocument> captured = visibleDocuments();
        for (WorkspaceDocument current : captured) if (current.documentId().equals(document)) return current;
        Path path = WorkspacePaths.resolve(root, document);
        policy.checkPath(root.relativize(path), AgentPolicy.Capability.READ);
        for (WorkspaceDocument current : captured) {
            if (current.uri() != null && WorkspacePaths.resolve(root, current.uri()).equals(path)) return current;
        }
        return WorkspaceTextFile.read(workspaceId, path);
    }

    public Map<String, Object> read(String requestedWorkspace, String document, String expectedVersion, int offset, int limit) {
        bounds(offset, limit, MAX_READ_CHARACTERS);
        WorkspaceDocument snapshot = snapshot(requestedWorkspace, document);
        if (!snapshot.complete()) throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED,
            "The open document is a partial projection, not a complete snapshot");
        if (expectedVersion != null && !expectedVersion.equals(snapshot.version()))
            throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "Document version changed", Map.of("currentVersion", snapshot.version()));
        String text = snapshot.content();
        if (offset > text.length() || !GraphemeBoundary.isBoundary(text, offset)) throw invalid("Offset must be a valid UTF-16 grapheme boundary");
        int end = (int) Math.min((long) offset + limit, text.length());
        if (!GraphemeBoundary.isBoundary(text, end)) end = GraphemeBoundary.floor(text, end);
        if (end == offset && offset < text.length()) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED,
            "The character limit cannot contain the next complete grapheme");
        Map<String, Object> result = new java.util.LinkedHashMap<>(snapshot.metadata());
        result.put("content", text.substring(offset, end)); result.put("offset", offset);
        result.put("nextOffset", end); result.put("truncated", end < text.length());
        return java.util.Collections.unmodifiableMap(result);
    }

    List<WorkspaceDocument> visibleDocuments() {
        policy.require(AgentPolicy.Capability.READ);
        List<WorkspaceDocument> captured = List.copyOf(documents.snapshots());
        List<WorkspaceDocument> visible = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (WorkspaceDocument document : captured) {
            if (!workspaceId.equals(document.workspaceId())) continue;
            if (document.uri() != null) {
                try { policy.checkPath(root.relativize(WorkspacePaths.resolve(root, document.uri())), AgentPolicy.Capability.READ); }
                catch (WorkspaceApiException denied) {
                    if (denied.code() == WorkspaceApiException.Code.OUTSIDE_WORKSPACE || denied.code() == WorkspaceApiException.Code.EXCLUDED_PATH
                        || denied.code() == WorkspaceApiException.Code.PROTECTED_PATH) continue;
                    throw denied;
                }
            }
            if (!ids.add(document.documentId())) throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "Duplicate editor document identities");
            visible.add(document);
        }
        return List.copyOf(visible);
    }

    void checkWorkspace(String requested) {
        policy.require(AgentPolicy.Capability.READ);
        if (!workspaceId.equals(requested)) throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_FOUND, "Workspace identity does not match this connection");
    }

    private static void bounds(int offset, int limit, int maximum) {
        if (offset < 0 || limit < 1 || limit > maximum) throw invalid("Offset or result limit is outside its supported bounds");
    }
    private static WorkspaceApiException invalid(String message) { return new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST, message); }
}
