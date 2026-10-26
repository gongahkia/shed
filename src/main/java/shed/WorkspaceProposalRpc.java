package shed;

import static shed.WorkspaceRpc.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Transport validation and bounded review pages; never supplies the authenticated actor. */
final class WorkspaceProposalRpc {
    static final List<String> OPERATIONS = List.of("proposals/list", "proposals/read", "proposals/propose", "proposals/revise",
        "proposals/reject", "proposals/apply", "proposals/undo", "proposals/rebase", "proposals/recovery", "proposals/reconcile", "proposals/restore");
    private final WorkspaceQueryService workspace;
    private final WorkspaceProposalService proposals;

    WorkspaceProposalRpc(WorkspaceQueryService workspace, WorkspaceProposalService proposals) {
        this.workspace = workspace; this.proposals = proposals;
    }

    List<String> advertised() {
        return OPERATIONS.stream().filter(method -> workspace.policy().capabilities().contains(capability(method))).toList();
    }

    Object dispatch(String method, Map<String, Object> params) {
        workspace.policy().require(capability(method));
        if (!workspace.workspaceId().equals(string(params, "workspaceId", true, 256)))
            throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_FOUND, "Workspace identity does not match this connection");
        return switch (method) {
            case "proposals/list" -> {
                fields(params, Set.of("workspaceId", "offset", "limit"));
                var page = proposals.page(integer(params, "offset", 0, 0, Integer.MAX_VALUE), integer(params, "limit", 50, 1, 100));
                var result = new LinkedHashMap<String, Object>();
                result.put("workspaceId", workspace.workspaceId()); result.put("proposals", page.proposals());
                result.put("offset", page.offset()); result.put("nextOffset", page.nextOffset()); result.put("truncated", page.truncated());
                yield result;
            }
            case "proposals/read" -> read(params);
            case "proposals/recovery" -> {
                fields(params, Set.of("workspaceId", "id")); yield proposals.recovery(id(params));
            }
            case "proposals/reconcile", "proposals/restore" -> {
                fields(params, Set.of("workspaceId", "id", "revision", "fingerprint"));
                yield method.equals("proposals/restore") ? proposals.restore(id(params), revision(params), string(params, "fingerprint", true, 64))
                    : proposals.reconcile(id(params), revision(params), string(params, "fingerprint", true, 64));
            }
            case "proposals/propose" -> {
                fields(params, Set.of("workspaceId", "requestId", "taskId", "turnId", "changes"));
                var values = MiniJson.asArray(params.get("changes"));
                if (values == null || values.isEmpty() || values.size() > WorkspaceProposalService.MAX_FILES) throw invalid("Invalid changes");
                List<WorkspaceProposalService.Input> inputs = new ArrayList<>();
                for (Object value : values) {
                    var change = MiniJson.asObject(value);
                    if (change == null) throw invalid("Invalid change");
                    String kind = string(change, "kind", false, 16);
                    if (kind == null || kind.equals("text")) {
                        fields(change, Set.of("kind", "document", "expectedVersion", "text"));
                        inputs.add(new WorkspaceProposalService.Input(string(change, "document", true, 8192),
                            string(change, "expectedVersion", true, 512), content(change, true)));
                    } else if (kind.equals("create")) {
                        fields(change, Set.of("kind", "document", "text"));
                        inputs.add(WorkspaceProposalService.Input.create(string(change, "document", true, 8192), content(change, true)));
                    } else if (kind.equals("delete")) {
                        fields(change, Set.of("kind", "document", "expectedVersion"));
                        inputs.add(WorkspaceProposalService.Input.delete(string(change, "document", true, 8192),
                            string(change, "expectedVersion", true, 512)));
                    } else if (kind.equals("rename")) {
                        fields(change, Set.of("kind", "document", "expectedVersion", "destination"));
                        inputs.add(WorkspaceProposalService.Input.rename(string(change, "document", true, 8192),
                            string(change, "expectedVersion", true, 512), string(change, "destination", true, 8192)));
                    } else throw invalid("Unknown proposal operation");
                }
                yield proposals.propose(string(params, "requestId", true, 256), string(params, "taskId", true, 256),
                    string(params, "turnId", true, 256), inputs);
            }
            case "proposals/revise" -> {
                fields(params, Set.of("workspaceId", "id", "revision", "index", "text", "selectedHunks"));
                if (!params.containsKey("text") && !params.containsKey("selectedHunks")) throw invalid("A revision needs text or selectedHunks");
                Set<Integer> selected = null;
                if (params.containsKey("selectedHunks")) {
                    var values = MiniJson.asArray(params.get("selectedHunks"));
                    if (values == null || values.size() > 100_000) throw invalid("Invalid selectedHunks");
                    selected = new java.util.HashSet<>();
                    for (Object value : values) {
                        if (value == null || !selected.add(integer(Map.of("hunk", value), "hunk", -1, 0, 100_000)))
                            throw invalid("Invalid or duplicate selected hunk");
                    }
                }
                yield proposals.revise(id(params), revision(params), integer(params, "index", 0, 0, WorkspaceProposalService.MAX_FILES - 1),
                    content(params, false), selected);
            }
            default -> {
                fields(params, Set.of("workspaceId", "id", "revision"));
                String id = id(params); int revision = revision(params);
                yield switch (method) {
                    case "proposals/reject" -> proposals.reject(id, revision);
                    case "proposals/apply" -> proposals.apply(id, revision);
                    case "proposals/undo" -> proposals.undo(id, revision);
                    case "proposals/rebase" -> proposals.rebase(id, revision);
                    default -> throw invalid("Unknown proposal method");
                };
            }
        };
    }

    private Map<String, Object> read(Map<String, Object> params) {
        fields(params, Set.of("workspaceId", "id", "index", "expectedRevision", "text", "offset", "limit", "hunkOffset", "hunkLimit"));
        int index = integer(params, "index", 0, 0, WorkspaceProposalService.MAX_FILES - 1);
        var preview = proposals.preview(id(params), index);
        int revision = ((Number) preview.proposal().get("revision")).intValue();
        if (integer(params, "expectedRevision", revision, 0, Integer.MAX_VALUE) != revision)
            throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "Proposal changed between review pages", Map.of("currentRevision", revision));
        String variant = string(params, "text", false, 16);
        if (variant == null) variant = "selected";
        String text = switch (variant) {
            case "before" -> preview.before(); case "proposed" -> preview.proposed(); case "selected" -> preview.selected();
            case "draft" -> {
                if (preview.preservedDraft() == null) throw invalid("This operation has no preserved editor draft");
                yield preview.preservedDraft();
            }
            default -> throw invalid("Unknown proposal text variant");
        };
        int offset = integer(params, "offset", 0, 0, text.length());
        int limit = integer(params, "limit", MAX_READ_CHARACTERS, 1, MAX_READ_CHARACTERS);
        if (!GraphemeBoundary.isBoundary(text, offset)) throw invalid("Offset must be a valid UTF-16 grapheme boundary");
        int end = Math.min(text.length(), offset + limit);
        if (!GraphemeBoundary.isBoundary(text, end)) end = GraphemeBoundary.floor(text, end);
        if (end == offset && offset < text.length()) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "The character limit cannot contain the next complete grapheme");
        int hunkOffset = integer(params, "hunkOffset", 0, 0, preview.hunks().size());
        int hunkEnd = Math.min(preview.hunks().size(), hunkOffset + integer(params, "hunkLimit", 100, 1, 100));
        var selected = MiniJson.asArray(preview.document().get("selectedHunks"));
        List<Object> hunks = new ArrayList<>();
        for (int i = hunkOffset; i < hunkEnd; i++) {
            var hunk = preview.hunks().get(i);
            hunks.add(Map.of("index", i, "start", hunk.start(), "end", hunk.end(), "replacementLength", hunk.replacement().length(),
                "selected", selected == null || selected.contains((long) i)));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("proposal", preview.proposal()); result.put("document", preview.document()); result.put("index", index);
        result.put("text", variant); result.put("content", text.substring(offset, end)); result.put("offset", offset);
        result.put("length", text.length()); result.put("offsetEncoding", "utf-16"); result.put("nextOffset", end); result.put("truncated", end < text.length());
        result.put("hunks", hunks); result.put("hunkCount", preview.hunks().size()); result.put("nextHunkOffset", hunkEnd);
        result.put("hunksTruncated", hunkEnd < preview.hunks().size());
        return result;
    }

    private static AgentPolicy.Capability capability(String method) {
        return switch (method) {
            case "proposals/list", "proposals/read", "proposals/recovery" -> AgentPolicy.Capability.READ;
            case "proposals/propose", "proposals/revise", "proposals/reject" -> AgentPolicy.Capability.PROPOSE;
            default -> AgentPolicy.Capability.APPLY;
        };
    }
    private static String id(Map<String, Object> params) { return string(params, "id", true, 64); }
    private static int revision(Map<String, Object> params) {
        if (!params.containsKey("revision")) throw invalid("A reviewed revision is required");
        return integer(params, "revision", -1, 0, Integer.MAX_VALUE);
    }
    private static String content(Map<String, Object> params, boolean required) {
        if (!params.containsKey("text") && !required) return null;
        if (!(params.get("text") instanceof String text) || text.length() > WorkspaceProposalService.MAX_TEXT_CHARACTERS) throw invalid("Invalid text");
        return text;
    }
}
