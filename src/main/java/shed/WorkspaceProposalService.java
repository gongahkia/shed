package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable, versioned text proposals. UI and transports share this application path. */
final class WorkspaceProposalService {
    static final int MAX_FILES = 32;
    static final int MAX_TEXT_CHARACTERS = 1024 * 1024;
    private final WorkspaceQueryService workspace;
    private final WorkspaceDocumentEditor editor;
    private final WorkspaceChangeJournal journal;
    private final String actor;

    record Input(String document, String expectedVersion, String text) { }
    record Preview(Map<String, Object> proposal, Map<String, Object> document, String before, String proposed,
                   String selected, List<WorkspaceTextEdits.Hunk> hunks) { }
    private record Effect(WorkspaceDocument before, String after, byte[] diskBefore, byte[] diskAfter) { }

    WorkspaceProposalService(WorkspaceQueryService workspace, WorkspaceDocumentEditor editor, Path storage, String actor) {
        this.workspace = java.util.Objects.requireNonNull(workspace); this.editor = editor;
        this.actor = required(actor, "actor", 256);
        Path absolute = storage.toAbsolutePath().normalize();
        Path canonical = WorkspacePaths.resolve(WorkspacePaths.root(absolute.getRoot()), absolute.toString());
        if (canonical.startsWith(workspace.root())) throw failure(WorkspaceApiException.Code.PROTECTED_PATH, "Proposal storage must be outside the workspace");
        this.journal = new WorkspaceChangeJournal(canonical);
    }

    Map<String, Object> propose(String requestId, String taskId, String turnId, List<Input> inputs) {
        workspace.policy().require(AgentPolicy.Capability.PROPOSE);
        required(requestId, "requestId", 256); required(taskId, "taskId", 256); required(turnId, "turnId", 256);
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_FILES) throw failure(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Proposal file count is outside its bounds");
        List<Object> request = new ArrayList<>();
        long total = 0;
        for (Input input : inputs) {
            required(input.document(), "document", 8192); required(input.expectedVersion(), "expectedVersion", 512); validText(input.text());
            total += input.text().length();
            request.add(map("document", input.document(), "version", input.expectedVersion(), "text", input.text()));
        }
        if (total > 8L * MAX_TEXT_CHARACTERS) throw failure(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Proposal exceeds its total text limit");
        String id = "proposal-" + WorkspacePaths.hash(actor + "\n" + requestId).substring(0, 32);
        String fingerprint = WorkspacePaths.hash(WorkspaceChangeJournal.canonical(map("task", taskId, "turn", turnId, "changes", request)));
        try (var lease = journal.open(true)) {
            var existing = lease.read(id);
            if (existing != null) {
                checkRecord(existing);
                if (!fingerprint.equals(existing.data().get("requestHash"))) throw failure(WorkspaceApiException.Code.CONFLICT, "Request ID was already used for different changes");
                return summary(existing);
            }
            Set<String> seen = new java.util.HashSet<>();
            List<Object> changes = new ArrayList<>();
            for (Input input : inputs) {
                WorkspaceDocument document = workspace.snapshot(workspace.workspaceId(), input.document());
                authorize(document, AgentPolicy.Capability.PROPOSE, null);
                if (!document.complete()) throw failure(WorkspaceApiException.Code.UNSUPPORTED, "Projected documents cannot be proposed as complete text");
                if (!document.version().equals(input.expectedVersion())) throw failure(WorkspaceApiException.Code.STALE_VERSION, "Document changed before proposal");
                if (!seen.add(document.documentId())) throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Duplicate proposal document");
                validText(document.content());
                String base = lease.putText(document.content()), desired = lease.putText(input.text());
                changes.add(map("document", document.documentId(), "uri", document.uri(), "version", document.version(),
                    "source", document.source().name(), "encoding", document.encoding(), "lineEnding", document.lineEnding(),
                    "bom", document.byteOrderMark(), "before", base, "proposed", desired, "desired", desired));
            }
            var data = map("workspaceId", workspace.workspaceId(), "root", workspace.root().toUri().toString(), "actor", actor,
                "requestId", requestId, "taskId", taskId, "turnId", turnId, "requestHash", fingerprint,
                "status", "PROPOSED", "createdAt", Instant.now().toString(), "constraints", workspace.policy().toMap(), "changes", changes);
            return summary(lease.append(id, -1, "PROPOSED", data));
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    List<Map<String, Object>> list(int offset, int limit) {
        workspace.policy().require(AgentPolicy.Capability.READ);
        if (offset < 0 || limit < 1 || limit > 100) throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Invalid proposal page");
        try (var lease = journal.open(false)) {
            if (lease == null) return List.of();
            List<String> ids = lease.list().stream().filter(id -> id.startsWith("proposal-")).toList();
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = Math.min(offset, ids.size()); i < ids.size() && result.size() < limit; i++) {
                var entry = lease.read(ids.get(i));
                try { checkRecord(entry); result.add(summary(entry)); }
                catch (WorkspaceApiException denied) {
                    if (denied.code() != WorkspaceApiException.Code.EXCLUDED_PATH && denied.code() != WorkspaceApiException.Code.PROTECTED_PATH) throw denied;
                }
            }
            return List.copyOf(result);
        } catch (IOException unavailable) { throw storageFailure(null); }
    }

    Preview preview(String id, int index) {
        workspace.policy().require(AgentPolicy.Capability.READ);
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            List<Map<String, Object>> changes = changes(entry.data());
            if (index < 0 || index >= changes.size()) throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Unknown proposal document");
            var change = changes.get(index);
            String before = lease.getText(text(change, "before")), proposed = lease.getText(text(change, "proposed"));
            return new Preview(summary(entry), change, before, proposed, lease.getText(text(change, "desired")), WorkspaceTextEdits.diff(before, proposed));
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    Map<String, Object> revise(String id, int revision, int index, String replacement, Set<Integer> selectedHunks) {
        workspace.policy().require(AgentPolicy.Capability.PROPOSE);
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id); editable(entry, revision);
            if (!actor.equals(entry.data().get("actor")) && !workspace.policy().capabilities().contains(AgentPolicy.Capability.APPLY))
                throw failure(WorkspaceApiException.Code.PERMISSION_DENIED, "Only the proposal owner or reviewer may revise it");
            List<Map<String, Object>> changes = changes(entry.data());
            if (index < 0 || index >= changes.size()) throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Unknown proposal document");
            var change = new LinkedHashMap<>(changes.get(index));
            String before = lease.getText(text(change, "before")), proposed = replacement == null ? lease.getText(text(change, "proposed")) : replacement;
            validText(proposed);
            String desired = selectedHunks == null ? proposed : WorkspaceTextEdits.selected(before, proposed, selectedHunks);
            change.put("proposed", lease.putText(proposed)); change.put("desired", lease.putText(desired));
            changes.set(index, change);
            var data = new LinkedHashMap<>(entry.data()); data.put("changes", changes); data.put("reviewedBy", actor);
            return summary(lease.append(id, revision, "REVISED", data));
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    Map<String, Object> rebase(String id, int revision) {
        workspace.policy().require(AgentPolicy.Capability.APPLY);
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            if (entry.revision() != revision || !Set.of("PROPOSED", "FAILED").contains(text(entry.data(), "status")))
                throw failure(WorkspaceApiException.Code.CONFLICT, "Proposal cannot be rebased from this revision/state");
            List<Object> updated = new ArrayList<>();
            for (var stored : changes(entry.data())) {
                WorkspaceDocument current = resolve(stored);
                authorize(current, AgentPolicy.Capability.APPLY, entry.data());
                String desired = WorkspaceTextEdits.rebase(lease.getText(text(stored, "before")),
                    lease.getText(text(stored, "desired")), current.content());
                var change = new LinkedHashMap<>(stored);
                change.put("document", current.documentId()); change.put("version", current.version());
                change.put("before", lease.putText(current.content()));
                change.put("proposed", lease.putText(desired)); change.put("desired", change.get("proposed"));
                change.put("source", current.source().name()); change.put("encoding", current.encoding());
                change.put("lineEnding", current.lineEnding()); change.put("bom", current.byteOrderMark()); updated.add(change);
            }
            var data = new LinkedHashMap<>(entry.data()); data.put("changes", updated); data.put("status", "PROPOSED"); data.remove("error");
            return summary(lease.append(id, revision, "REBASED", data));
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    Map<String, Object> reject(String id, int revision) {
        workspace.policy().require(AgentPolicy.Capability.PROPOSE);
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            if ("REJECTED".equals(entry.data().get("status"))) return summary(entry);
            editable(entry, revision);
            if (!actor.equals(entry.data().get("actor")) && !workspace.policy().capabilities().contains(AgentPolicy.Capability.APPLY))
                throw failure(WorkspaceApiException.Code.PERMISSION_DENIED, "Only the proposal owner or reviewer may reject it");
            var data = new LinkedHashMap<>(entry.data()); data.put("status", "REJECTED"); data.put("reviewedBy", actor);
            return summary(lease.append(id, revision, "REJECTED", data));
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    Map<String, Object> apply(String id, int revision) { return applyOrUndo(id, revision, false); }
    Map<String, Object> undo(String id, int revision) { return applyOrUndo(id, revision, true); }

    private Map<String, Object> applyOrUndo(String id, int revision, boolean undo) {
        workspace.policy().require(AgentPolicy.Capability.APPLY);
        if (Thread.currentThread().isInterrupted()) throw failure(WorkspaceApiException.Code.CANCELLED, "Edit was cancelled before application");
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            String done = undo ? "UNDONE" : "APPLIED", approval = undo ? "undoApprovedRevision" : "approvedRevision";
            if (done.equals(entry.data().get("status")) && Long.valueOf(revision).equals(entry.data().get(approval))) return summary(entry);
            if (Set.of("APPLYING", "UNDOING", "RECOVERY_REQUIRED").contains(entry.data().get("status")))
                throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "An interrupted edit requires inspection before it can be retried");
            if (entry.revision() != revision || !(undo ? "APPLIED" : "PROPOSED").equals(entry.data().get("status")))
                throw failure(WorkspaceApiException.Code.CONFLICT, "Proposal revision/state changed; review it again");
            List<Effect> effects = new ArrayList<>();
            for (var change : changes(entry.data())) {
                WorkspaceDocument current = resolve(change);
                authorize(current, AgentPolicy.Capability.APPLY, entry.data());
                if (!current.complete() || current.readOnly()) throw failure(WorkspaceApiException.Code.PERMISSION_DENIED, "Document cannot be edited");
                String before = lease.getText(text(change, "before")), desired = lease.getText(text(change, "desired"));
                if (undo) desired = WorkspaceTextEdits.rebase(desired, before, current.content());
                else if (!current.version().equals(change.get("version")) || !current.content().equals(before))
                    throw failure(WorkspaceApiException.Code.STALE_VERSION, "Document changed after proposal; rebase and review it");
                validText(current.content()); validText(desired);
                if (desired.equals(current.content())) continue;
                if (current.source() == WorkspaceDocument.Source.DISK) {
                    var disk = WorkspaceTextFile.capture(workspace.workspaceId(), WorkspacePaths.resolve(workspace.root(), current.uri()), true);
                    if (!disk.document().version().equals(current.version())) throw failure(WorkspaceApiException.Code.STALE_VERSION, "Disk changed during preflight");
                    byte[] encoded;
                    try {
                        encoded = disk.image().encode(desired);
                        if (undo) {
                            List<Object> original = MiniJson.asArray(entry.data().get("operation"));
                            if (original == null) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Applied operation record is missing");
                            for (Object item : original) {
                                var effect = object(item);
                                if (!java.util.Objects.equals(current.uri(), effect.get("uri")) || effect.get("diskBefore") == null) continue;
                                var beforeImage = TextFileCodec.decode(lease.get(text(effect, "diskBefore")), true);
                                var afterImage = TextFileCodec.decode(lease.get(text(effect, "diskAfter")), true);
                                String raw = WorkspaceTextEdits.rebase(afterImage.rawText(), beforeImage.rawText(), disk.image().rawText());
                                if (!TextFileCodec.image(raw, current.encoding(), current.byteOrderMark()).text().equals(desired))
                                    throw failure(WorkspaceApiException.Code.CONFLICT, "Newline changes overlap; review is required");
                                encoded = TextFileCodec.encodeRaw(raw, current.encoding(), current.byteOrderMark());
                            }
                        }
                    } catch (IOException encoding) { throw failure(WorkspaceApiException.Code.UNSUPPORTED, "Proposed text cannot be encoded in the document format"); }
                    effects.add(new Effect(current, desired, disk.bytes(), encoded));
                } else {
                    if (editor == null || !editor.connected()) throw failure(WorkspaceApiException.Code.NOT_CONNECTED, "Editor document connection is unavailable");
                    // Validate format before a batch can modify any editor document.
                    try { TextFileCodec.encodeRaw(desired, current.encoding(), current.byteOrderMark()); }
                    catch (IOException encoding) { throw failure(WorkspaceApiException.Code.UNSUPPORTED, "Proposed text cannot be encoded in the document format"); }
                    effects.add(new Effect(current, desired, null, null));
                }
            }
            return execute(lease, entry, effects, revision, undo);
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    private Map<String, Object> execute(WorkspaceChangeJournal.Lease lease, WorkspaceChangeJournal.Entry initial,
                                        List<Effect> effects, int approvedRevision, boolean undo) throws IOException {
        var data = new LinkedHashMap<>(initial.data());
        List<Object> operation = new ArrayList<>();
        for (Effect effect : effects) operation.add(map("document", effect.before().documentId(), "uri", effect.before().uri(),
            "source", effect.before().source().name(), "version", effect.before().version(),
            "before", lease.putText(effect.before().content()), "after", lease.putText(effect.after()),
            "diskBefore", effect.diskBefore() == null ? null : lease.put(effect.diskBefore()),
            "diskAfter", effect.diskAfter() == null ? null : lease.put(effect.diskAfter())));
        data.put("operation", operation); data.put("status", undo ? "UNDOING" : "APPLYING");
        data.put(undo ? "undoApprovedRevision" : "approvedRevision", approvedRevision); data.put("reviewedBy", actor);
        var pending = lease.append(initial.id(), initial.revision(), undo ? "UNDOING" : "APPLYING", data);
        WorkspaceEditTransaction transaction = new WorkspaceEditTransaction();
        boolean committed = false, interrupted = false;
        try {
            List<Path> staged = new ArrayList<>();
            for (Effect effect : effects) staged.add(effect.diskAfter() == null ? null
                : transaction.stage(WorkspacePaths.resolve(workspace.root(), effect.before().uri()), effect.diskAfter()));
            for (int index = 0; index < effects.size(); index++) {
                Effect effect = effects.get(index);
                if (effect.diskAfter() == null) continue;
                authorize(effect.before(), AgentPolicy.Capability.APPLY, data);
                Path path = WorkspacePaths.resolve(workspace.root(), effect.before().uri());
                if (!effect.before().version().equals(WorkspaceTextFile.read(workspace.workspaceId(), path).version()))
                    throw failure(WorkspaceApiException.Code.STALE_VERSION, "Disk changed before mutation");
                transaction.replace(path, staged.get(index));
            }
            transaction.prepareCommit();
            List<WorkspaceDocumentEditor.TextChange> open = effects.stream().filter(effect -> effect.diskAfter() == null)
                .map(effect -> new WorkspaceDocumentEditor.TextChange(effect.before(), effect.after())).toList();
            List<WorkspaceDocument> after = open.isEmpty() ? List.of() : editor.applyTexts(open);
            // Once an atomic editor batch starts, record its actual outcome even if its caller was interrupted.
            interrupted = Thread.interrupted();
            transaction.commit(); committed = true;
            data.put("status", undo ? "UNDONE" : "APPLIED");
            data.put("editorResults", after.stream().map(WorkspaceDocument::metadata).toList());
            data.put("pendingSaveAtApplication", !after.isEmpty()); data.remove("error");
            var completed = lease.append(initial.id(), pending.revision(), undo ? "UNDONE" : "APPLIED", data);
            var report = transaction.cleanup();
            Map<String, Object> result = summary(completed);
            if (!report.failures().isEmpty()) {
                data.put("recovery", report.notice());
                result = summary(lease.append(initial.id(), completed.revision(), "CLEANUP_NOTICE", data));
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            interrupted |= Thread.interrupted();
            if (committed) throw storageFailure(initial.id());
            var report = transaction.rollback(); transaction.cleanup();
            boolean recovery = report.state() == WorkspaceEditTransaction.State.RECOVERY_REQUIRED
                || failure instanceof WorkspaceApiException domain && domain.code() == WorkspaceApiException.Code.RECOVERY_REQUIRED;
            data.put("status", recovery ? "RECOVERY_REQUIRED" : "FAILED");
            data.put("error", failure instanceof WorkspaceApiException domain ? domain.toMap()
                : map("code", "IO_ERROR", "message", "Workspace edit failed"));
            data.put("recovery", transaction.report().notice());
            lease.append(initial.id(), pending.revision(), recovery ? "RECOVERY_REQUIRED" : "FAILED", data);
            if (recovery) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Workspace edit needs recovery; its preimages were retained");
            if (failure instanceof WorkspaceApiException domain) throw domain;
            throw failure(WorkspaceApiException.Code.IO_ERROR, "Workspace edit failed and was rolled back");
        } finally {
            transaction.close();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private WorkspaceChangeJournal.Entry entry(WorkspaceChangeJournal.Lease lease, String id) throws IOException {
        if (lease == null) throw failure(WorkspaceApiException.Code.NOT_FOUND, "Proposal not found");
        var entry = lease.read(id);
        if (entry == null) throw failure(WorkspaceApiException.Code.NOT_FOUND, "Proposal not found");
        checkRecord(entry); return entry;
    }
    private void checkRecord(WorkspaceChangeJournal.Entry entry) {
        if (!workspace.workspaceId().equals(entry.data().get("workspaceId")) || !workspace.root().toUri().toString().equals(entry.data().get("root")))
            throw failure(WorkspaceApiException.Code.NOT_FOUND, "Proposal belongs to another workspace");
        for (var change : changes(entry.data())) {
            Object uri = change.get("uri");
            if (uri != null) workspace.policy().checkPath(workspace.root().relativize(WorkspacePaths.resolve(workspace.root(), (String) uri)), AgentPolicy.Capability.READ);
        }
    }
    private WorkspaceDocument resolve(Map<String, Object> change) {
        Object uri = change.get("uri");
        return workspace.snapshot(workspace.workspaceId(), uri instanceof String value ? value : text(change, "document"));
    }
    private void authorize(WorkspaceDocument document, AgentPolicy.Capability capability, Map<String, Object> proposal) {
        workspace.policy().require(capability);
        if (document.uri() == null) return;
        Path relative = workspace.root().relativize(WorkspacePaths.resolve(workspace.root(), document.uri()));
        workspace.policy().checkPath(relative, capability);
        if (proposal != null) {
            var constraints = object(proposal.get("constraints"));
            new AgentPolicy(workspace.policy().capabilities(), strings(constraints.get("protectedPaths")),
                strings(constraints.get("excludedPaths")), workspace.policy().backgroundOnly()).checkPath(relative, capability);
        }
    }
    private static void editable(WorkspaceChangeJournal.Entry entry, int revision) {
        if (entry.revision() != revision || !"PROPOSED".equals(entry.data().get("status")))
            throw failure(WorkspaceApiException.Code.CONFLICT, "Proposal revision/state changed");
    }
    private static Map<String, Object> summary(WorkspaceChangeJournal.Entry entry) {
        var data = entry.data();
        return map("id", entry.id(), "revision", entry.revision(), "status", data.get("status"), "workspaceId", data.get("workspaceId"),
            "actor", data.get("actor"), "reviewedBy", data.get("reviewedBy"), "taskId", data.get("taskId"), "turnId", data.get("turnId"),
            "requestId", data.get("requestId"), "createdAt", data.get("createdAt"), "files", changes(data).size(),
            "pendingSaveAtApplication", data.get("pendingSaveAtApplication"), "error", data.get("error"), "recovery", data.get("recovery"));
    }
    private static List<Map<String, Object>> changes(Map<String, Object> data) {
        List<Object> values = MiniJson.asArray(data.get("changes"));
        if (values == null || values.isEmpty() || values.size() > MAX_FILES) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Invalid proposal change manifest");
        return new ArrayList<>(values.stream().map(WorkspaceProposalService::object).toList());
    }
    private static Map<String, Object> object(Object value) {
        var result = MiniJson.asObject(value);
        if (result == null) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Invalid proposal metadata");
        return result;
    }
    private static List<String> strings(Object value) {
        List<Object> items = MiniJson.asArray(value);
        if (items == null || items.stream().anyMatch(item -> !(item instanceof String)))
            throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Invalid proposal constraints");
        return items.stream().map(String.class::cast).toList();
    }
    private static String text(Map<String, Object> data, String key) {
        if (!(data.get(key) instanceof String value)) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Invalid proposal metadata: " + key);
        return value;
    }
    private static String required(String value, String name, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(c -> c < 32))
            throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Invalid " + name);
        return value;
    }
    private static void validText(String value) {
        if (value == null || value.length() > MAX_TEXT_CHARACTERS) throw failure(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Proposal text exceeds its character limit");
        if (value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Proposal text must use normalized LF newlines and contain no NUL");
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static WorkspaceApiException failure(WorkspaceApiException.Code code, String message) { return new WorkspaceApiException(code, message); }
    private static WorkspaceApiException storageFailure(String id) {
        return new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Workspace proposal storage is unavailable or incomplete",
            id == null ? Map.of() : Map.of("proposalId", id));
    }
}
