package shed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Version-one workspace JSON-RPC adapter. Advertises only implemented operations. */
public final class WorkspaceRpc {
    public static final int MAX_READ_CHARACTERS = 65_536;
    private final WorkspaceQueryService workspace;
    private final WorkspaceProposalRpc proposals;

    public WorkspaceRpc(WorkspaceQueryService workspace) { this(workspace, null); }

    WorkspaceRpc(WorkspaceQueryService workspace, WorkspaceProposalService proposals) {
        this.workspace = Objects.requireNonNull(workspace);
        this.proposals = proposals == null ? null : new WorkspaceProposalRpc(workspace, proposals);
    }

    /** Null is a valid notification's no-response result. */
    public String handle(String json) {
        Object decoded;
        try { decoded = MiniJson.parseStrict(json); }
        catch (IllegalArgumentException error) { return error(null, -32700, "Invalid JSON", Map.of("code", "INVALID_REQUEST")); }
        Map<String, Object> request = MiniJson.asObject(decoded);
        if (request == null) return error(null, -32600, "Expected one JSON-RPC request object", Map.of("code", "INVALID_REQUEST"));
        Object id = request.get("id");
        if (!validId(id)) return error(null, -32600, "Request ID must be a bounded string, integer or null", Map.of("code", "INVALID_REQUEST"));
        boolean notification = !request.containsKey("id");
        if (!validEnvelope(request))
            return error(id, -32600, "Expected a supported JSON-RPC 2.0 request envelope", Map.of("code", "INVALID_REQUEST"));
        String method = (String) request.get("method");
        if (!WorkspaceQueryService.OPERATIONS.contains(method) && (proposals == null || !WorkspaceProposalRpc.OPERATIONS.contains(method))) return notification ? null
            : error(id, -32601, "Workspace operation is not supported", Map.of("code", "UNSUPPORTED", "method", method));
        try {
            Map<String, Object> params = request.containsKey("params") ? MiniJson.asObject(request.get("params")) : Map.of();
            if (params == null) throw invalid("Parameters must be an object");
            Object result = dispatch(method, params);
            return notification ? null : MiniJson.stringify(response(id, "result", result));
        } catch (WorkspaceApiException failure) {
            int code = failure.code() == WorkspaceApiException.Code.INVALID_REQUEST ? -32602 : -32000;
            return notification ? null : error(id, code, failure.getMessage(), failure.toMap());
        } catch (RuntimeException failure) {
            return notification ? null : error(id, -32603, "Internal workspace error", Map.of("code", "IO_ERROR"));
        }
    }

    static boolean expectsResponse(String json) {
        try {
            Map<String, Object> request = MiniJson.asObject(MiniJson.parseStrict(json));
            return request == null || request.containsKey("id") || !validEnvelope(request);
        } catch (IllegalArgumentException invalid) { return true; }
    }

    private static boolean validEnvelope(Map<String, Object> request) {
        return Set.of("jsonrpc", "id", "method", "params").containsAll(request.keySet())
            && "2.0".equals(request.get("jsonrpc")) && request.get("method") instanceof String method
            && !method.isBlank() && method.length() <= 128;
    }

    private Object dispatch(String method, Map<String, Object> params) {
        if (proposals != null && WorkspaceProposalRpc.OPERATIONS.contains(method)) return proposals.dispatch(method, params);
        return switch (method) {
            case "files/list", "search/text" -> search(method, params);
            case "workspace/info" -> {
                fields(params, Set.of("workspaceId", "schemaVersion"));
                int version = integer(params, "schemaVersion", 1, 1, Integer.MAX_VALUE);
                if (version != 1) throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Unsupported workspace API schema version");
                String requested = string(params, "workspaceId", false, 256);
                if (requested != null && !requested.equals(workspace.workspaceId()))
                    throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_FOUND, "Workspace identity does not match this connection");
                var info = new LinkedHashMap<>(workspace.info());
                if (proposals != null) {
                    var operations = new java.util.ArrayList<>(WorkspaceQueryService.OPERATIONS);
                    operations.addAll(proposals.advertised());
                    info.put("operations", operations);
                }
                yield info;
            }
            case "documents/list" -> {
                fields(params, Set.of("workspaceId", "offset", "limit"));
                yield workspace.listDocuments(string(params, "workspaceId", true, 256),
                    integer(params, "offset", 0, 0, Integer.MAX_VALUE), integer(params, "limit", 100, 1, WorkspaceQueryService.MAX_PAGE_SIZE));
            }
            case "documents/read" -> {
                fields(params, Set.of("workspaceId", "document", "expectedVersion", "offset", "limit"));
                yield workspace.read(string(params, "workspaceId", true, 256), string(params, "document", true, 8192),
                    string(params, "expectedVersion", false, 512), integer(params, "offset", 0, 0, Integer.MAX_VALUE),
                    integer(params, "limit", MAX_READ_CHARACTERS, 1, MAX_READ_CHARACTERS));
            }
            default -> throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Workspace operation is not supported", Map.of("method", method));
        };
    }

    private Object search(String method, Map<String, Object> params) {
        boolean files = method.equals("files/list");
        fields(params, files ? Set.of("workspaceId", "include", "exclude", "includeIgnored", "scope", "offset", "limit", "expectedListingVersion", "timeoutMillis")
            : Set.of("workspaceId", "include", "exclude", "includeIgnored", "scope", "query", "caseSensitive", "regex", "wholeWord", "limit", "maxFiles", "maxFileBytes", "timeoutMillis"));
        String workspaceId = string(params, "workspaceId", true, 256);
        String scope = string(params, "scope", false, 32);
        if (scope != null && !scope.equals("workspace") && !scope.equals("open")) throw invalid("scope must be workspace or open");
        var filters = new WorkspaceDiscoveryService.Filters(filter(params, "include"), filter(params, "exclude"), bool(params, "includeIgnored", false));
        int timeout = integer(params, "timeoutMillis", 5000, 1, 10_000);
        int limit = integer(params, "limit", 200, 1, 1000);
        var service = new WorkspaceSearchQueryService(workspace);
        if (files) return service.files(workspaceId, filters, "open".equals(scope), integer(params, "offset", 0, 0, WorkspaceQueryBudget.MAX_ENTRIES),
            limit, string(params, "expectedListingVersion", false, 512), timeout, () -> false);
        SearchPattern pattern;
        try { pattern = new SearchPattern(filter(params, "query"), bool(params, "caseSensitive", true), bool(params, "regex", false), bool(params, "wholeWord", false)); }
        catch (IllegalArgumentException invalid) { throw invalid(invalid.getMessage()); }
        return service.search(workspaceId, filters, "open".equals(scope), pattern, limit,
            integer(params, "maxFiles", 10_000, 1, WorkspaceQueryBudget.MAX_ENTRIES),
            integer(params, "maxFileBytes", WorkspaceSearchQueryService.DEFAULT_FILE_BYTES, 1, WorkspaceTextFile.MAX_BYTES), timeout, () -> false);
    }

    private static String filter(Map<String, Object> params, String key) {
        if (!params.containsKey(key)) return "";
        if (!(params.get(key) instanceof String value) || value.length() > 4096 || value.indexOf('\0') >= 0) throw invalid("Invalid " + key);
        return value;
    }
    private static boolean bool(Map<String, Object> params, String key, boolean fallback) {
        if (!params.containsKey(key)) return fallback;
        if (!(params.get(key) instanceof Boolean value)) throw invalid("Invalid " + key);
        return value;
    }

    static String error(Object id, int code, String message, Map<String, Object> data) {
        return MiniJson.stringify(response(id, "error", Map.of("code", code, "message", message, "data", data)));
    }
    private static Map<String, Object> response(Object id, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("jsonrpc", "2.0"); result.put("id", id); result.put(key, value); return result;
    }
    private static boolean validId(Object id) { return id == null || id instanceof Long || id instanceof String value && value.length() <= 256; }
    static void fields(Map<String, Object> params, Set<String> allowed) {
        if (!allowed.containsAll(params.keySet())) throw invalid("Unsupported parameter fields");
    }
    static String string(Map<String, Object> params, String key, boolean required, int max) {
        Object value = params.get(key);
        if (value == null && !required && !params.containsKey(key)) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw invalid("Invalid " + key);
        return text;
    }
    static int integer(Map<String, Object> params, String key, int fallback, int minimum, int maximum) {
        if (!params.containsKey(key)) return fallback;
        Object value = params.get(key);
        long number;
        try {
            if (value instanceof Long integral) number = integral;
            else if (value instanceof java.math.BigDecimal decimal) number = decimal.longValueExact();
            else throw invalid("Invalid " + key);
        } catch (ArithmeticException error) { throw invalid("Invalid " + key); }
        if (number < minimum || number > maximum) throw invalid("Invalid " + key);
        return (int) number;
    }
    static WorkspaceApiException invalid(String message) { return WorkspaceApiException.invalidRequest(message); }
}
