package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    record Page(List<Map<String, Object>> proposals, int offset, Integer nextOffset, boolean truncated) { }
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
                if (!seen.add(document.uri() == null ? document.documentId() : document.uri()))
                    throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Duplicate proposal document");
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
        return page(offset, limit).proposals();
    }

    Page page(int offset, int limit) {
        workspace.policy().require(AgentPolicy.Capability.READ);
        if (offset < 0 || limit < 1 || limit > 100) throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Invalid proposal page");
        try (var lease = journal.open(false)) {
            if (lease == null) return new Page(List.of(), 0, null, false);
            List<String> ids = lease.list().stream().filter(id -> id.startsWith("proposal-")).toList();
            List<Map<String, Object>> result = new ArrayList<>();
            int start = Math.min(offset, ids.size()), cursor = start;
            while (cursor < ids.size() && result.size() < limit) {
                var entry = lease.read(ids.get(cursor++));
                try { checkRecord(entry); result.add(summary(entry)); }
                catch (WorkspaceApiException denied) {
                    if (denied.code() != WorkspaceApiException.Code.EXCLUDED_PATH && denied.code() != WorkspaceApiException.Code.PROTECTED_PATH) throw denied;
                }
            }
            return new Page(List.copyOf(result), start, cursor < ids.size() ? cursor : null, cursor < ids.size());
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
            if (selectedHunks == null) change.remove("selectedHunks");
            else change.put("selectedHunks", selectedHunks.stream().sorted().toList());
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
                change.remove("selectedHunks");
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

    Map<String, Object> recovery(String id) {
        workspace.policy().require(AgentPolicy.Capability.READ);
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            return recovery(lease, entry);
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    /** Reconciles evidence only; never rewrites a file or an editor buffer. */
    Map<String, Object> reconcile(String id, int revision, String fingerprint) {
        workspace.policy().require(AgentPolicy.Capability.APPLY);
        required(fingerprint, "fingerprint", 64);
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            if (Long.valueOf(revision).equals(entry.data().get("reconciledRevision")) && fingerprint.equals(entry.data().get("reconciledFingerprint"))) return summary(entry);
            if (entry.revision() != revision || !Set.of("APPLYING", "UNDOING", "RECOVERY_REQUIRED").contains(entry.data().get("status")))
                throw failure(WorkspaceApiException.Code.CONFLICT, "Only the inspected interrupted revision can be reconciled");
            for (var change : changes(entry.data())) {
                Object uri = change.get("uri");
                if (uri != null) {
                    Path relative = workspace.root().relativize(WorkspacePaths.resolve(workspace.root(), (String) uri));
                    workspace.policy().checkPath(relative, AgentPolicy.Capability.APPLY);
                    var constraints = object(entry.data().get("constraints"));
                    new AgentPolicy(workspace.policy().capabilities(), strings(constraints.get("protectedPaths")),
                        strings(constraints.get("excludedPaths")), workspace.policy().backgroundOnly()).checkPath(relative, AgentPolicy.Capability.APPLY);
                }
            }
            var inspection = recovery(lease, entry);
            if (!fingerprint.equals(inspection.get("fingerprint"))) throw failure(WorkspaceApiException.Code.STALE_VERSION, "Recovery evidence changed; inspect it again");
            String outcome = (String) inspection.get("verifiedOutcome");
            if (outcome == null) throw failure(WorkspaceApiException.Code.CONFLICT, "The interrupted operation has mixed, missing or changed contents; manual recovery is still required");
            boolean undo = "UNDO".equals(entry.data().get("operationKind"));
            var data = new LinkedHashMap<>(entry.data());
            data.put("status", outcome); data.put("reviewedBy", actor); data.put("reconciledRevision", revision);
            data.put("reconciledFingerprint", fingerprint); data.put("reconciledAt", Instant.now().toString());
            data.put("recoveryEvidence", inspection.get("documents")); data.remove("error");
            if (!data.containsKey("pendingSaveAtApplication")) data.put("pendingSaveAtApplication",
                MiniJson.asArray(data.get("operation")).stream().anyMatch(item -> "EDITOR".equals(object(item).get("source"))));
            if (undo && "APPLIED".equals(outcome)) {
                data.put("operation", data.get("appliedOperation")); data.put("operationKind", "APPLY");
                data.put("resourceSteps", data.getOrDefault("appliedResourceSteps", List.of()));
            }
            data.put("recovery", "Outcome reconciled from inspected contents. Retained filesystem artifacts were not removed.");
            return summary(lease.append(id, revision, "RECONCILED", data));
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    private Map<String, Object> recovery(WorkspaceChangeJournal.Lease lease, WorkspaceChangeJournal.Entry entry) throws IOException {
        List<Object> operation = MiniJson.asArray(entry.data().get("operation"));
        if (operation == null || operation.size() > MAX_FILES) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Operation evidence is unavailable");
        List<Object> observations = new ArrayList<>();
        boolean allBefore = true, allAfter = true;
        for (Object item : operation) {
            var effect = object(item);
            String before = lease.getText(text(effect, "before")), after = lease.getText(text(effect, "after"));
            Map<String, Object> observation = map("document", effect.get("document"), "uri", effect.get("uri"), "originalSource", effect.get("source"));
            String state;
            try {
                WorkspaceDocument current = resolve(effect);
                observation.put("current", current.metadata());
                state = current.complete() ? classify(current.content(), before, after) : "INCOMPLETE";
                if (effect.get("diskBefore") != null) {
                    lease.get(text(effect, "diskBefore")); lease.get(text(effect, "diskAfter"));
                    var disk = WorkspaceTextFile.capture(workspace.workspaceId(), WorkspacePaths.resolve(workspace.root(), text(effect, "uri")), true);
                    String diskState = classify(WorkspacePaths.hash(disk.bytes()), text(effect, "diskBefore"), text(effect, "diskAfter"));
                    observation.put("diskVersion", disk.document().version()); observation.put("diskState", diskState);
                    if (!state.equals(diskState)) state = "DIVERGED";
                }
            } catch (WorkspaceApiException unavailable) {
                if (!Set.of(WorkspaceApiException.Code.NOT_FOUND, WorkspaceApiException.Code.UNSUPPORTED, WorkspaceApiException.Code.IO_ERROR).contains(unavailable.code())) throw unavailable;
                state = unavailable.code() == WorkspaceApiException.Code.NOT_FOUND ? "MISSING" : "UNREADABLE";
                observation.put("error", unavailable.toMap());
            }
            observation.put("state", state); observations.add(observation);
            allBefore &= state.equals("BEFORE") || state.equals("UNCHANGED");
            allAfter &= state.equals("AFTER") || state.equals("UNCHANGED");
        }
        String kind = (String) entry.data().get("operationKind");
        boolean undo = "UNDO".equals(kind);
        String outcome = !Set.of("APPLY", "UNDO").contains(Objects.toString(kind, "")) ? null
            : allAfter ? (undo ? "UNDONE" : "APPLIED") : allBefore ? (undo ? "APPLIED" : "FAILED") : null;
        if (undo && "APPLIED".equals(outcome) && MiniJson.asArray(entry.data().get("appliedOperation")) == null) outcome = null;
        String fingerprint = WorkspacePaths.hash(WorkspaceChangeJournal.canonical(map("id", entry.id(), "revision", entry.revision(), "observations", observations)));
        List<Object> resources = MiniJson.asArray(entry.data().get("resourceSteps"));
        return map("proposal", summary(entry), "documents", observations, "fingerprint", fingerprint,
            "verifiedOutcome", outcome, "resourceSteps", resources == null ? List.of() : resources,
            "changesWorkspaceFiles", false);
    }

    private static String classify(String current, String before, String after) {
        if (current.equals(before) && current.equals(after)) return "UNCHANGED";
        return current.equals(after) ? "AFTER" : current.equals(before) ? "BEFORE" : "DIVERGED";
    }

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
            Set<String> seen = new java.util.HashSet<>();
            for (var change : changes(entry.data())) {
                WorkspaceDocument current = resolve(change);
                if (!seen.add(current.uri() == null ? current.documentId() : current.uri()))
                    throw failure(WorkspaceApiException.Code.CONFLICT, "Proposal documents now resolve to the same document");
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
                    String rawUndo = null;
                    if (undo) {
                        List<Object> original = MiniJson.asArray(entry.data().get("operation"));
                        if (original == null) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Applied operation record is missing");
                        for (Object item : original) {
                            var effect = object(item);
                            if (!Objects.equals(current.uri(), effect.get("uri")) || effect.get("diskBefore") == null) continue;
                            var beforeImage = TextFileCodec.decode(lease.get(text(effect, "diskBefore")), true);
                            var afterImage = TextFileCodec.decode(lease.get(text(effect, "diskAfter")), true);
                            rawUndo = WorkspaceTextEdits.rebase(afterImage.rawText(), beforeImage.rawText(), disk.image().rawText());
                            if (!TextFileCodec.image(rawUndo, current.encoding(), current.byteOrderMark()).text().equals(desired))
                                throw failure(WorkspaceApiException.Code.CONFLICT, "Newline changes overlap; review is required");
                        }
                    }
                    try {
                        encoded = rawUndo == null ? disk.image().encode(desired) : TextFileCodec.encodeRaw(rawUndo, current.encoding(), current.byteOrderMark());
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
        if (undo) { data.put("appliedOperation", initial.data().get("operation")); data.put("appliedResourceSteps", initial.data().get("resourceSteps")); }
        data.put("operation", operation); data.put("operationKind", undo ? "UNDO" : "APPLY");
        data.put("resourceSteps", List.of()); data.put("status", undo ? "UNDOING" : "APPLYING");
        data.put(undo ? "undoApprovedRevision" : "approvedRevision", approvedRevision); data.put("reviewedBy", actor);
        var pending = new java.util.concurrent.atomic.AtomicReference<>(lease.append(initial.id(), initial.revision(), undo ? "UNDOING" : "APPLYING", data));
        List<Object> resourceSteps = new ArrayList<>();
        WorkspaceEditTransaction transaction = new WorkspaceEditTransaction(step -> {
            resourceSteps.add(step.toMap()); data.put("resourceSteps", List.copyOf(resourceSteps));
            pending.set(lease.append(initial.id(), pending.get().revision(), "RESOURCE_PREPARED", data));
        });
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
            var completed = lease.append(initial.id(), pending.get().revision(), undo ? "UNDONE" : "APPLIED", data);
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
            lease.append(initial.id(), pending.get().revision(), recovery ? "RECOVERY_REQUIRED" : "FAILED", data);
            if (recovery) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Workspace edit needs recovery; its preimages were retained");
            if (failure instanceof WorkspaceApiException domain) throw domain;
            throw failure(WorkspaceApiException.Code.IO_ERROR, "Workspace edit failed and was rolled back");
        } finally {
            transaction.close();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private WorkspaceChangeJournal.Entry entry(WorkspaceChangeJournal.Lease lease, String id) throws IOException {
        if (id == null || !id.matches("proposal-[a-f0-9]{32}")) throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Invalid proposal ID");
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
            "documents", changes(data).stream().map(change -> map("document", change.get("document"), "uri", change.get("uri"))).toList(),
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
