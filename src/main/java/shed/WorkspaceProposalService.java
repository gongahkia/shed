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

/** Durable, versioned proposals. UI and transports share this application path. */
final class WorkspaceProposalService {
    static final int MAX_FILES = 32;
    static final int MAX_TEXT_CHARACTERS = 1024 * 1024;
    private final WorkspaceQueryService workspace;
    private final WorkspaceDocumentEditor editor;
    private final WorkspaceChangeJournal journal;
    private final String actor;
    private final WorkspaceEditTransaction.StepObserver observer;

    enum Kind { TEXT, CREATE, DELETE, RENAME }
    record Input(String document, String expectedVersion, String text, Kind kind, String destination) {
        Input(String document, String expectedVersion, String text, Kind kind) { this(document, expectedVersion, text, kind, null); }
        static Input rename(String document, String expectedVersion, String destination) {
            return new Input(document, expectedVersion, "", Kind.RENAME, destination);
        }
        Input(String document, String expectedVersion, String text) { this(document, expectedVersion, text, Kind.TEXT); }
        static Input create(String document, String text) { return new Input(document, "absent", text, Kind.CREATE); }
        static Input delete(String document, String expectedVersion) { return new Input(document, expectedVersion, "", Kind.DELETE); }
    }
    record Page(List<Map<String, Object>> proposals, int offset, Integer nextOffset, boolean truncated) { }
    record Preview(Map<String, Object> proposal, Map<String, Object> document, String before, String proposed,
                   String selected, List<WorkspaceTextEdits.Hunk> hunks, String preservedDraft) { }
    private record Effect(WorkspaceDocument before, String after, byte[] diskBefore, byte[] diskAfter, Kind kind,
                          Map<String, Object> diskIdentity, String uri, WorkspaceRecoveryPlan.Artifact original, boolean retainOriginal, String destination) {
        Effect(WorkspaceDocument before, String after, byte[] diskBefore, byte[] diskAfter, Kind kind,
               Map<String, Object> diskIdentity, String uri, WorkspaceRecoveryPlan.Artifact original, boolean retainOriginal) {
            this(before, after, diskBefore, diskAfter, kind, diskIdentity, uri, original, retainOriginal, null);
        }
        Effect(WorkspaceDocument before, String after, byte[] diskBefore, byte[] diskAfter, Kind kind, Map<String, Object> diskIdentity) {
            this(before, after, diskBefore, diskAfter, kind, diskIdentity, before.uri(), null, false);
        }
        Effect(WorkspaceDocument before, String after, byte[] diskBefore, byte[] diskAfter) {
            this(before, after, diskBefore, diskAfter, Kind.TEXT, null);
        }
    }

    WorkspaceProposalService(WorkspaceQueryService workspace, WorkspaceDocumentEditor editor, Path storage, String actor) {
        this(workspace, editor, storage, actor, WorkspaceEditTransaction.StepObserver.NONE);
    }

    WorkspaceProposalService(WorkspaceQueryService workspace, WorkspaceDocumentEditor editor, Path storage, String actor,
                             WorkspaceEditTransaction.StepObserver observer) {
        this.workspace = java.util.Objects.requireNonNull(workspace); this.editor = editor;
        this.observer = java.util.Objects.requireNonNull(observer);
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
            if (input == null || input.kind() == null)
                throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Unsupported proposal operation");
            required(input.document(), "document", 8192); required(input.expectedVersion(), "expectedVersion", 512); validText(input.text());
            if (input.kind() == Kind.CREATE && !input.expectedVersion().equals("absent"))
                throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Creation requires an absent target");
            if ((input.kind() == Kind.DELETE || input.kind() == Kind.RENAME) && !input.text().isEmpty())
                throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "File removal or rename cannot include replacement text");
            if (input.kind() == Kind.RENAME) required(input.destination(), "destination", 8192);
            else if (input.destination() != null) throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Only rename accepts a destination");
            total += input.text().length();
            var requested = map("document", input.document(), "version", input.expectedVersion(), "text", input.text());
            if (input.kind() != Kind.TEXT) requested.put("kind", input.kind().name());
            if (input.kind() == Kind.RENAME) requested.put("destination", input.destination());
            request.add(requested);
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
            Set<Path> targets = new java.util.HashSet<>();
            List<Object> changes = new ArrayList<>();
            for (Input input : inputs) {
                WorkspaceDocument document = input.kind() == Kind.CREATE ? absent(input.document(), AgentPolicy.Capability.PROPOSE, null)
                    : workspace.snapshot(workspace.workspaceId(), input.document());
                authorize(document, AgentPolicy.Capability.PROPOSE, null);
                if (!document.complete()) throw failure(WorkspaceApiException.Code.UNSUPPORTED, "Projected documents cannot be proposed as complete text");
                if (!document.version().equals(input.expectedVersion())) throw failure(WorkspaceApiException.Code.STALE_VERSION, "Document changed before proposal");
                if (!seen.add(document.uri() == null ? document.documentId() : document.uri()))
                    throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Duplicate proposal document");
                if (document.uri() != null) {
                    Path path = WorkspacePaths.resolve(workspace.root(), document.uri());
                    if (targets.stream().anyMatch(other -> path.startsWith(other) || other.startsWith(path)))
                        throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Proposal file targets cannot contain one another");
                    targets.add(path);
                }
                validText(document.content());
                String base = lease.putText(document.content()), desired = lease.putText(input.kind() == Kind.RENAME ? document.content() : input.text());
                var change = map("kind", input.kind().name(), "document", document.documentId(), "uri", document.uri(), "version", document.version(),
                    "source", document.source().name(), "encoding", document.encoding(), "lineEnding", document.lineEnding(),
                    "bom", document.byteOrderMark(), "before", base, "proposed", desired, "desired", desired);
                if (input.kind() == Kind.DELETE || input.kind() == Kind.RENAME) captureFileOperation(lease, change, document);
                if (input.kind() == Kind.RENAME) {
                    var destination = absent(input.destination(), AgentPolicy.Capability.PROPOSE, null);
                    Path path = WorkspacePaths.resolve(workspace.root(), destination.uri());
                    if (targets.stream().anyMatch(other -> path.startsWith(other) || other.startsWith(path)))
                        throw failure(WorkspaceApiException.Code.INVALID_REQUEST, "Rename paths must be disjoint from all proposal paths");
                    targets.add(path); change.put("destination", destination.uri());
                }
                changes.add(change);
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

    record ReviewState(String status, int revision, List<String> appliedText, boolean pendingSave) { }

    /** Observe the committed decision without computing a diff or reading mutable workspace contents. */
    ReviewState reviewState(String id) {
        workspace.policy().require(AgentPolicy.Capability.READ);
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            return reviewState(lease, entry);
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    /** Owner cancellation serializes with review/application under the same cross-process lease. */
    ReviewState withdraw(String id) {
        workspace.policy().require(AgentPolicy.Capability.PROPOSE);
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            if (!actor.equals(entry.data().get("actor")))
                throw failure(WorkspaceApiException.Code.PERMISSION_DENIED, "Only the proposal owner may withdraw it");
            if (Set.of("PROPOSED", "FAILED").contains(entry.data().get("status"))) {
                var data = new LinkedHashMap<>(entry.data()); data.put("status", "REJECTED");
                data.put("withdrawnBy", actor); data.put("withdrawnAt", Instant.now().toString());
                entry = lease.append(id, entry.revision(), "WITHDRAWN", data);
            }
            return reviewState(lease, entry);
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    private ReviewState reviewState(WorkspaceChangeJournal.Lease lease, WorkspaceChangeJournal.Entry entry) throws IOException {
        String status = text(entry.data(), "status");
        List<String> applied = new ArrayList<>();
        if (status.equals("APPLIED")) for (var change : changes(entry.data())) applied.add(lease.getText(text(change, "desired")));
        return new ReviewState(status, entry.revision(), List.copyOf(applied), Boolean.TRUE.equals(entry.data().get("pendingSaveAtApplication")));
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
            boolean deletion = "DELETE".equals(change.get("kind")), rename = "RENAME".equals(change.get("kind"));
            String draft = (deletion || rename) && "EDITOR".equals(change.get("source")) ? before : null;
            if (deletion || rename) before = lease.getText(text(change, "diskText"));
            if (rename) proposed = before;
            return new Preview(summary(entry), change, before, proposed, rename ? before : lease.getText(text(change, "desired")),
                deletion || rename ? List.of() : WorkspaceTextEdits.diff(before, proposed), draft);
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
            if ("DELETE".equals(change.get("kind")) || "RENAME".equals(change.get("kind"))) throw failure(WorkspaceApiException.Code.INVALID_REQUEST,
                "This is a whole-file operation; reject it or explicitly rebase it for review");
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
                if ("CREATE".equals(stored.get("kind"))) {
                    absent(text(stored, "uri"), AgentPolicy.Capability.APPLY, entry.data());
                    updated.add(stored); continue;
                }
                WorkspaceDocument current = resolve(stored);
                authorize(current, AgentPolicy.Capability.APPLY, entry.data());
                boolean deletion = "DELETE".equals(stored.get("kind")), rename = "RENAME".equals(stored.get("kind"));
                if (rename) absent(text(stored, "destination"), AgentPolicy.Capability.APPLY, entry.data());
                String desired = deletion ? "" : rename ? current.content() : WorkspaceTextEdits.rebase(lease.getText(text(stored, "before")),
                    lease.getText(text(stored, "desired")), current.content());
                var change = new LinkedHashMap<>(stored);
                change.put("document", current.documentId()); change.put("version", current.version());
                change.put("before", lease.putText(current.content()));
                change.put("proposed", lease.putText(desired)); change.put("desired", change.get("proposed"));
                change.remove("selectedHunks");
                change.put("source", current.source().name()); change.put("encoding", current.encoding());
                change.put("lineEnding", current.lineEnding()); change.put("bom", current.byteOrderMark());
                if (deletion || rename) captureFileOperation(lease, change, current);
                updated.add(change);
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
            for (var change : changes(entry.data())) for (String field : List.of("uri", "destination")) {
                Object uri = change.get(field);
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
        var plan = WorkspaceRecoveryPlan.capture(workspace, lease, entry);
        return map("proposal", summary(entry), "documents", plan.observations(), "fingerprint", plan.fingerprint(),
            "verifiedOutcome", plan.verifiedOutcome(), "restoreAvailable", plan.restorable(), "resourceSteps", plan.resources(),
            "changesWorkspaceFiles", false);
    }

    Map<String, Object> restore(String id, int revision, String fingerprint) {
        workspace.policy().require(AgentPolicy.Capability.APPLY);
        required(fingerprint, "fingerprint", 64);
        if (Thread.currentThread().isInterrupted()) throw failure(WorkspaceApiException.Code.CANCELLED, "Restoration was cancelled before application");
        try (var lease = journal.open(false)) {
            var entry = entry(lease, id);
            if (Long.valueOf(revision).equals(entry.data().get("restoredRevision")) && fingerprint.equals(entry.data().get("restoredFingerprint"))) return summary(entry);
            if (entry.revision() != revision || !Set.of("APPLYING", "UNDOING", "RECOVERY_REQUIRED").contains(entry.data().get("status")))
                throw failure(WorkspaceApiException.Code.CONFLICT, "Only an inspected interrupted revision can be restored");
            var plan = WorkspaceRecoveryPlan.capture(workspace, lease, entry);
            if (!fingerprint.equals(plan.fingerprint())) throw failure(WorkspaceApiException.Code.STALE_VERSION, "Recovery evidence changed; inspect it again");
            if (!plan.restorable()) throw failure(WorkspaceApiException.Code.CONFLICT, "Restoration would overwrite changed or unavailable contents; inspect the recovery details");
            for (var action : plan.actions()) validateRestore(action, entry.data());
            for (var action : plan.renames()) validateRenameRestore(action, entry.data());
            return restore(lease, entry, plan, fingerprint);
        } catch (IOException unavailable) { throw storageFailure(id); }
    }

    private void validateRenameRestore(WorkspaceRenameRecovery.Action action, Map<String, Object> proposal) throws IOException {
        for (Path path : List.of(action.source(), action.destination()))
            authorize(new WorkspaceDocument(workspace.workspaceId(), path.toUri().toString(), path.toUri().toString(), "recovery",
                "", false, false, true, "UTF-8", "LF", false, WorkspaceDocument.Source.DISK), AgentPolicy.Capability.APPLY, proposal);
        if (action.current() != null && (editor == null || !editor.connected()))
            throw failure(WorkspaceApiException.Code.NOT_CONNECTED, "Editor recovery connection is unavailable");
        action.validate(workspace);
    }

    private void validateRestore(WorkspaceRecoveryPlan.Action action, Map<String, Object> proposal) throws IOException {
        if (action.effect().get("uri") instanceof String uri) {
            authorize(new WorkspaceDocument(workspace.workspaceId(), action.document(), uri, "recovery", "", false,
                false, true, "UTF-8", "LF", false, WorkspaceDocument.Source.DISK), AgentPolicy.Capability.APPLY, proposal);
        }
        if (action.current() == null) {
            Path path = WorkspacePaths.resolve(workspace.root(), action.document());
            // Authorize missing targets through a synthetic snapshot; no text or version is inferred from it.
            authorize(new WorkspaceDocument(workspace.workspaceId(), action.document(), path.toUri().toString(), "missing", "", false,
                false, true, "UTF-8", "LF", false, WorkspaceDocument.Source.DISK), AgentPolicy.Capability.APPLY, proposal);
            try {
                workspace.snapshot(workspace.workspaceId(), action.document());
                throw failure(WorkspaceApiException.Code.STALE_VERSION, "A missing recovery document reappeared");
            } catch (WorkspaceApiException missing) { if (missing.code() != WorkspaceApiException.Code.NOT_FOUND) throw missing; }
        } else {
            authorize(action.current(), AgentPolicy.Capability.APPLY, proposal);
            var current = workspace.snapshot(workspace.workspaceId(), action.current().source() == WorkspaceDocument.Source.EDITOR
                ? action.current().documentId() : action.document());
            if (!current.version().equals(action.current().version())) throw failure(WorkspaceApiException.Code.STALE_VERSION, "Recovery document changed before restoration");
            if (current.source() == WorkspaceDocument.Source.EDITOR && (editor == null || !editor.connected()))
                throw failure(WorkspaceApiException.Code.NOT_CONNECTED, "Editor recovery connection is unavailable");
        }
        if (action.restoreDisk()) {
            Path path = WorkspacePaths.resolve(workspace.root(), action.document());
            if (action.disk() == null) {
                if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw failure(WorkspaceApiException.Code.STALE_VERSION, "Recovery target is no longer missing");
            } else if (!WorkspaceTextFile.capture(workspace.workspaceId(), path, true).document().version().equals(action.disk().document().version()))
                throw failure(WorkspaceApiException.Code.STALE_VERSION, "Recovery disk contents changed");
            if (action.artifact() != null && !WorkspaceEditTransaction.resourceIdentity(action.artifact().path()).equals(action.artifact().identity()))
                throw failure(WorkspaceApiException.Code.STALE_VERSION, "Retained original changed before restoration");
        }
        if (action.returnTo() != null) {
            if (action.restoreDisk()) {
                if (java.nio.file.Files.exists(action.returnTo().path(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw failure(WorkspaceApiException.Code.STALE_VERSION, "The retained-original location is now occupied");
            } else if (!WorkspaceEditTransaction.resourceIdentity(action.returnTo().path()).equals(action.returnTo().identity()))
                throw failure(WorkspaceApiException.Code.STALE_VERSION, "The retained original changed before editor restoration");
        }
    }

    private Map<String, Object> restore(WorkspaceChangeJournal.Lease lease, WorkspaceChangeJournal.Entry initial,
                                        WorkspaceRecoveryPlan plan, String fingerprint) throws IOException {
        var data = new LinkedHashMap<>(initial.data());
        var previousSteps = MiniJson.asArray(data.get("restoreSteps"));
        List<Object> steps = previousSteps == null ? new ArrayList<>() : new ArrayList<>(previousSteps);
        data.put("status", "RECOVERY_REQUIRED"); data.put("restoreApprovedRevision", initial.revision()); data.put("reviewedBy", actor);
        var pending = new java.util.concurrent.atomic.AtomicReference<>(lease.append(initial.id(), initial.revision(), "RESTORING", data));
        WorkspaceEditTransaction transaction = new WorkspaceEditTransaction(step -> {
            steps.add(step.toMap()); data.put("restoreSteps", List.copyOf(steps));
            pending.set(lease.append(initial.id(), pending.get().revision(), "RESTORE_PREPARED", data));
            observer.prepared(step);
        });
        boolean committed = false, interrupted = false;
        try {
            List<Path> staged = new ArrayList<>();
            for (var action : plan.actions()) staged.add(action.restoreDisk() && !action.removeDisk() && action.artifact() == null
                ? transaction.stage(WorkspacePaths.resolve(workspace.root(), action.document()), action.bytes()) : null);
            for (int i = 0; i < plan.actions().size(); i++) {
                var action = plan.actions().get(i); validateRestore(action, data);
                if (!action.restoreDisk()) continue;
                Path path = WorkspacePaths.resolve(workspace.root(), action.document());
                if (action.removeDisk()) {
                    if (action.returnTo() == null) transaction.delete(path);
                    else transaction.rename(path, action.returnTo().path(), action.returnTo().identity(), null);
                }
                else if (action.artifact() != null) {
                    if (action.disk() != null) transaction.delete(path);
                    transaction.rename(action.artifact().path(), path, false);
                } else transaction.replace(path, staged.get(i));
            }
            for (var action : plan.renames()) {
                validateRenameRestore(action, data); action.restore(transaction);
            }
            List<WorkspaceDocumentEditor.TextChange> open = new ArrayList<>();
            for (var action : plan.renames()) {
                var change = action.editorChange(workspace); if (change != null) open.add(change);
            }
            for (var action : plan.actions()) {
                WorkspaceDocument current = action.current(); WorkspaceDiskBinding binding = null;
                if (action.removeDisk()) {
                    if (current != null && current.source() == WorkspaceDocument.Source.EDITOR)
                        open.add(WorkspaceDocumentEditor.TextChange.detach(current));
                    continue;
                }
                if (action.restoreDisk() || action.effect().get("diskBefore") != null && current != null && current.source() == WorkspaceDocument.Source.EDITOR) {
                    Path path = WorkspacePaths.resolve(workspace.root(), action.document());
                    binding = WorkspaceDiskBinding.capture(workspace.workspaceId(), path);
                    if (!java.util.Arrays.equals(binding.digest(), java.util.HexFormat.of().parseHex(WorkspacePaths.hash(action.bytes()))))
                        throw failure(WorkspaceApiException.Code.STALE_VERSION, "Restored disk contents changed before editor adoption");
                    var observed = workspace.snapshot(workspace.workspaceId(), action.document());
                    if (observed.source() == WorkspaceDocument.Source.EDITOR && (current == null || current.source() != WorkspaceDocument.Source.EDITOR)) {
                        if (!observed.content().equals(action.text())) throw failure(WorkspaceApiException.Code.STALE_VERSION, "A document opened during restoration needs inspection");
                        current = observed;
                    }
                }
                if (current != null && current.source() == WorkspaceDocument.Source.EDITOR) {
                    if (editor == null || !editor.connected()) throw failure(WorkspaceApiException.Code.NOT_CONNECTED, "Editor recovery connection is unavailable");
                    open.add(new WorkspaceDocumentEditor.TextChange(current, action.text(), binding));
                }
            }
            transaction.prepareCommit();
            List<Path> unbound = new ArrayList<>(plan.actions().stream().filter(WorkspaceRecoveryPlan.Action::removeDisk)
                .map(action -> WorkspacePaths.resolve(workspace.root(), action.document())).toList());
            for (var action : plan.renames()) {
                unbound.add(action.destination()); if (action.current() == null) unbound.add(action.source());
            }
            List<WorkspaceDocument> after = editor == null || open.isEmpty() && unbound.isEmpty() ? List.of() : editor.applyTexts(open, unbound);
            interrupted = Thread.interrupted(); transaction.commit(); committed = true;
            boolean undo = "UNDO".equals(data.get("operationKind"));
            data.put("status", undo ? "APPLIED" : "FAILED"); data.put("restoredRevision", initial.revision()); data.put("restoredFingerprint", fingerprint);
            data.put("restoredAt", Instant.now().toString()); data.put("editorResults", after.stream().map(WorkspaceDocument::metadata).toList());
            if (undo) { data.put("operation", data.get("appliedOperation")); data.put("operationKind", "APPLY"); data.put("resourceSteps", data.getOrDefault("appliedResourceSteps", List.of())); }
            data.remove("error"); data.put("recovery", "Original snapshots restored. Unused recovery artifacts were retained.");
            var completed = lease.append(initial.id(), pending.get().revision(), "RESTORED", data);
            var cleanup = transaction.cleanup();
            if (!cleanup.failures().isEmpty()) {
                data.put("recovery", cleanup.notice()); completed = lease.append(initial.id(), completed.revision(), "CLEANUP_NOTICE", data);
            }
            return summary(completed);
        } catch (IOException | RuntimeException failed) {
            interrupted |= Thread.interrupted();
            if (committed) throw storageFailure(initial.id());
            transaction.rollback(); transaction.cleanup();
            data.put("status", "RECOVERY_REQUIRED"); data.put("recovery", transaction.report().notice());
            data.put("error", failed instanceof WorkspaceApiException domain ? domain.toMap() : map("code", "IO_ERROR", "message", "Snapshot restoration failed"));
            lease.append(initial.id(), pending.get().revision(), "RESTORE_FAILED", data);
            if (failed instanceof WorkspaceApiException domain) throw domain;
            throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Restoration did not finish; retained snapshots and artifacts remain available");
        } finally { transaction.close(); if (interrupted) Thread.currentThread().interrupt(); }
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
                if ("RENAME".equals(change.get("kind"))) {
                    Effect effect = rename(lease, entry, change, undo);
                    if (!seen.add(effect.uri()) || !seen.add(effect.destination()))
                        throw failure(WorkspaceApiException.Code.CONFLICT, "Duplicate proposal path");
                    effects.add(effect); continue;
                }
                if ("CREATE".equals(change.get("kind")) || "DELETE".equals(change.get("kind"))) {
                    Effect effect = "DELETE".equals(change.get("kind")) ? deletion(lease, entry, change, undo) : creation(lease, entry, change, undo);
                    if (!seen.add(effect.uri())) throw failure(WorkspaceApiException.Code.CONFLICT, "Duplicate proposal path");
                    effects.add(effect); continue;
                }
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
        for (Effect effect : effects) operation.add(map("kind", effect.kind().name(), "document", effect.before().documentId(), "uri",
            effect.kind() == Kind.TEXT ? effect.uri() : WorkspacePaths.resolve(workspace.root(), effect.uri()).toUri().toString(),
            "destination", effect.destination(), "source", effect.before().source().name(), "version", effect.before().version(),
            "before", lease.putText(effect.before().content()), "after", lease.putText(effect.after()),
            "diskBefore", effect.diskBefore() == null ? null : lease.put(effect.diskBefore()),
            "diskAfter", effect.diskAfter() == null ? null : lease.put(effect.diskAfter()), "diskIdentity", effect.diskIdentity(),
            "editorFormat", effect.before().source() == WorkspaceDocument.Source.EDITOR ? map("encoding", effect.before().encoding(),
                "lineEnding", effect.before().lineEnding(), "bom", effect.before().byteOrderMark()) : null,
            "retainedOriginal", effect.original() == null ? null : map("uri", effect.original().path().toUri().toString(), "identity", effect.original().identity())));
        if (undo) { data.put("appliedOperation", initial.data().get("operation")); data.put("appliedResourceSteps", initial.data().get("resourceSteps")); }
        data.put("operation", operation); data.put("operationKind", undo ? "UNDO" : "APPLY");
        data.put("resourceSteps", List.of()); data.put("restoreSteps", List.of()); data.put("status", undo ? "UNDOING" : "APPLYING");
        data.put(undo ? "undoApprovedRevision" : "approvedRevision", approvedRevision); data.put("reviewedBy", actor);
        var pending = new java.util.concurrent.atomic.AtomicReference<>(lease.append(initial.id(), initial.revision(), undo ? "UNDOING" : "APPLYING", data));
        List<Object> resourceSteps = new ArrayList<>();
        WorkspaceEditTransaction transaction = new WorkspaceEditTransaction(step -> {
            resourceSteps.add(step.toMap()); data.put("resourceSteps", List.copyOf(resourceSteps));
            pending.set(lease.append(initial.id(), pending.get().revision(), "RESOURCE_PREPARED", data));
            observer.prepared(step);
        });
        boolean committed = false, interrupted = false;
        try {
            List<Path> staged = new ArrayList<>();
            for (Effect effect : effects) staged.add(effect.diskAfter() == null || effect.original() != null || effect.kind() == Kind.RENAME ? null
                : transaction.stage(WorkspacePaths.resolve(workspace.root(), effect.uri()), effect.diskAfter()));
            for (int index = 0; index < effects.size(); index++) {
                Effect effect = effects.get(index);
                Path path = effect.uri() == null ? null : WorkspacePaths.resolve(workspace.root(), effect.uri());
                if (effect.kind() == Kind.RENAME) {
                    authorize(effect.before(), AgentPolicy.Capability.APPLY, data);
                    absent(effect.destination(), AgentPolicy.Capability.APPLY, data);
                    transaction.renameFile(WorkspaceRenameRecovery.path(workspace, effect.uri()),
                        WorkspaceRenameRecovery.path(workspace, effect.destination()), effect.diskIdentity());
                    continue;
                }
                if (effect.kind() == Kind.CREATE) {
                    absent(effect.uri(), AgentPolicy.Capability.APPLY, data);
                    if (effect.original() != null) transaction.rename(effect.original().path(), path, effect.original().identity(), null);
                    else transaction.create(path, staged.get(index));
                    continue;
                }
                if (effect.kind() == Kind.DELETE) {
                    authorize(effect.before(), AgentPolicy.Capability.APPLY, data);
                    if (effect.retainOriginal()) {
                        Path retention = lease.retentionDirectory(effect.diskBefore().length);
                        if (!java.nio.file.Files.getFileStore(path).equals(java.nio.file.Files.getFileStore(retention)))
                            throw failure(WorkspaceApiException.Code.UNSUPPORTED, "Durable deletion requires history on the same filesystem as the file");
                        transaction.deleteRetaining(path, effect.diskIdentity(), retention);
                    }
                    else transaction.delete(path, effect.diskIdentity());
                    continue;
                }
                if (effect.diskAfter() == null) continue;
                authorize(effect.before(), AgentPolicy.Capability.APPLY, data);
                if (!effect.before().version().equals(WorkspaceTextFile.read(workspace.workspaceId(), path).version()))
                    throw failure(WorkspaceApiException.Code.STALE_VERSION, "Disk changed before mutation");
                transaction.replace(path, staged.get(index));
            }
            List<WorkspaceDocumentEditor.TextChange> open = new ArrayList<>();
            for (Effect effect : effects) if (effect.before().source() == WorkspaceDocument.Source.EDITOR) {
                WorkspaceDiskBinding binding = effect.kind() == Kind.CREATE || effect.kind() == Kind.RENAME ? WorkspaceDiskBinding.capture(workspace.workspaceId(),
                    WorkspacePaths.resolve(workspace.root(), effect.kind() == Kind.RENAME ? effect.destination() : effect.uri())) : null;
                if (binding != null && !java.util.Arrays.equals(binding.digest(), java.util.HexFormat.of().parseHex(WorkspacePaths.hash(effect.diskAfter()))))
                    throw failure(WorkspaceApiException.Code.STALE_VERSION, "Restored file changed before editor adoption");
                open.add(effect.kind() == Kind.DELETE ? WorkspaceDocumentEditor.TextChange.detach(effect.before())
                    : new WorkspaceDocumentEditor.TextChange(effect.before(), effect.after(), binding));
            }
            transaction.prepareCommit();
            List<Path> unbound = new ArrayList<>(effects.stream().filter(effect -> effect.kind() == Kind.DELETE || effect.kind() == Kind.RENAME
                    || effect.kind() == Kind.CREATE && effect.before().source() != WorkspaceDocument.Source.EDITOR)
                .map(effect -> WorkspacePaths.resolve(workspace.root(), effect.uri())).toList());
            for (var effect : effects) if (effect.kind() == Kind.RENAME && effect.before().source() != WorkspaceDocument.Source.EDITOR)
                unbound.add(WorkspacePaths.resolve(workspace.root(), effect.destination()));
            List<WorkspaceDocument> after = editor == null || open.isEmpty() && unbound.isEmpty() ? List.of() : editor.applyTexts(open, unbound);
            // Once an atomic editor batch starts, record its actual outcome even if its caller was interrupted.
            interrupted = Thread.interrupted();
            transaction.commit(); committed = true;
            data.put("status", undo ? "UNDONE" : "APPLIED");
            data.put("editorResults", after.stream().map(WorkspaceDocument::metadata).toList());
            data.put("pendingSaveAtApplication", after.stream().anyMatch(WorkspaceDocument::dirty)); data.remove("error");
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
            data.put("status", recovery ? "RECOVERY_REQUIRED" : undo ? "APPLIED" : "FAILED");
            if (undo && !recovery) {
                data.put("operation", data.get("appliedOperation")); data.put("operationKind", "APPLY");
                data.put("resourceSteps", data.getOrDefault("appliedResourceSteps", List.of()));
            }
            data.put("error", failure instanceof WorkspaceApiException domain ? domain.toMap()
                : map("code", "IO_ERROR", "message", "Workspace edit failed"));
            data.put("recovery", transaction.report().notice());
            lease.append(initial.id(), pending.get().revision(), recovery ? "RECOVERY_REQUIRED" : undo ? "UNDO_FAILED" : "FAILED", data);
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
    private WorkspaceDocument absent(String document, AgentPolicy.Capability capability, Map<String, Object> proposal) {
        Path path = WorkspacePaths.resolve(workspace.root(), document);
        var missing = new WorkspaceDocument(workspace.workspaceId(), "file-" + WorkspacePaths.hash(path.toUri().toString()),
            path.toUri().toString(), "absent", "", false, false, true, "UTF-8", "LF", false, WorkspaceDocument.Source.DISK);
        authorize(missing, capability, proposal);
        try {
            workspace.snapshot(workspace.workspaceId(), path.toUri().toString());
            throw failure(WorkspaceApiException.Code.CONFLICT, "Creation target already exists on disk or in an open editor");
        } catch (WorkspaceApiException absent) { if (absent.code() != WorkspaceApiException.Code.NOT_FOUND) throw absent; }
        if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw failure(WorkspaceApiException.Code.CONFLICT, "Creation target is occupied");
        return missing;
    }

    private void captureFileOperation(WorkspaceChangeJournal.Lease lease, Map<String, Object> change, WorkspaceDocument document) throws IOException {
        if (document.uri() == null || !document.complete() || document.readOnly())
            throw failure(WorkspaceApiException.Code.UNSUPPORTED, "File operations require a complete, editable file-backed document");
        validText(document.content());
        Path path = WorkspacePaths.resolve(workspace.root(), document.uri());
        var disk = WorkspaceTextFile.capture(workspace.workspaceId(), path, true);
        if (document.source() == WorkspaceDocument.Source.DISK && !document.version().equals(disk.document().version()))
            throw failure(WorkspaceApiException.Code.STALE_VERSION, "File changed while preparing the file operation");
        var identity = WorkspaceEditTransaction.resourceIdentity(path);
        if (!WorkspacePaths.hash(disk.bytes()).equals(identity.get("digest")))
            throw failure(WorkspaceApiException.Code.STALE_VERSION, "File changed while capturing its identity");
        validText(disk.document().content());
        change.put("uri", path.toUri().toString()); change.put("diskIdentity", identity);
        change.put("diskBefore", lease.put(disk.bytes())); change.put("diskText", lease.putText(disk.document().content()));
        change.put("diskVersion", disk.document().version()); change.put("diskFormat", map("encoding", disk.document().encoding(),
            "lineEnding", disk.document().lineEnding(), "bom", disk.document().byteOrderMark()));
    }

    private Effect rename(WorkspaceChangeJournal.Lease lease, WorkspaceChangeJournal.Entry entry,
                          Map<String, Object> change, boolean undo) throws IOException {
        String source = text(change, undo ? "destination" : "uri"), destination = text(change, undo ? "uri" : "destination");
        Path path = WorkspaceRenameRecovery.path(workspace, source), target = WorkspaceRenameRecovery.path(workspace, destination);
        WorkspaceDocument current = workspace.snapshot(workspace.workspaceId(), source);
        authorize(current, AgentPolicy.Capability.APPLY, entry.data());
        absent(destination, AgentPolicy.Capability.APPLY, entry.data());
        if (!current.complete() || current.readOnly()) throw failure(WorkspaceApiException.Code.PERMISSION_DENIED, "Document cannot be renamed");
        validText(current.content());
        if (current.source() == WorkspaceDocument.Source.EDITOR && (editor == null || !editor.connected()))
            throw failure(WorkspaceApiException.Code.NOT_CONNECTED, "Editor document connection is unavailable");
        var disk = WorkspaceTextFile.capture(workspace.workspaceId(), path, true);
        var identity = WorkspaceEditTransaction.resourceIdentity(path);
        if (!WorkspacePaths.hash(disk.bytes()).equals(identity.get("digest")))
            throw failure(WorkspaceApiException.Code.STALE_VERSION, "File changed during rename preflight");
        if (!undo) {
            if (!current.version().equals(change.get("version")) || !current.content().equals(lease.getText(text(change, "before")))
                || !disk.document().version().equals(change.get("diskVersion")) || !identity.equals(change.get("diskIdentity")))
                throw failure(WorkspaceApiException.Code.STALE_VERSION, "Document or disk changed after rename was proposed; rebase and review it");
        } else {
            var operations = MiniJson.asArray(entry.data().get("operation"));
            var applied = operations == null ? null : operations.stream().map(WorkspaceProposalService::object)
                .filter(effect -> "RENAME".equals(effect.get("kind")) && source.equals(effect.get("destination")) && destination.equals(effect.get("uri")))
                .findFirst().orElse(null);
            if (applied == null) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Applied rename evidence is unavailable");
            var original = object(applied.get("diskIdentity"));
            boolean sameFile = identity.equals(original) || !Objects.toString(original.get("key"), "").isEmpty()
                && original.get("key").equals(identity.get("key"));
            if (!sameFile && current.source() == WorkspaceDocument.Source.EDITOR && current.documentId().equals(applied.get("document"))) {
                var binding = WorkspaceDiskBinding.capture(workspace.workspaceId(), path);
                sameFile = java.util.Arrays.equals(binding.digest(), java.util.HexFormat.of().parseHex(WorkspacePaths.hash(disk.bytes())))
                    && editor.matchesSavedDisk(current, binding) && identity.equals(WorkspaceEditTransaction.resourceIdentity(path));
            }
            if (!sameFile) throw failure(WorkspaceApiException.Code.CONFLICT, "The renamed file was replaced; review a new rename proposal");
        }
        Path ancestor = target.getParent();
        while (ancestor != null && !java.nio.file.Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null || !java.nio.file.Files.getFileStore(path).equals(java.nio.file.Files.getFileStore(ancestor)))
            throw failure(WorkspaceApiException.Code.UNSUPPORTED, "Reviewed rename requires both paths on the same filesystem");
        return new Effect(current, current.content(), disk.bytes(), disk.bytes(), Kind.RENAME, identity, source, null, false, destination);
    }

    private Effect deletion(WorkspaceChangeJournal.Lease lease, WorkspaceChangeJournal.Entry entry,
                            Map<String, Object> change, boolean undo) throws IOException {
        String uri = text(change, "uri");
        if (!undo) {
            WorkspaceDocument current = resolve(change); authorize(current, AgentPolicy.Capability.APPLY, entry.data());
            if (!current.complete() || current.readOnly()) throw failure(WorkspaceApiException.Code.PERMISSION_DENIED, "Document cannot be deleted");
            if (!current.version().equals(change.get("version")) || !current.content().equals(lease.getText(text(change, "before"))))
                throw failure(WorkspaceApiException.Code.STALE_VERSION, "Document changed after deletion was proposed; rebase and review it");
            Path path = WorkspacePaths.resolve(workspace.root(), uri);
            var disk = WorkspaceTextFile.capture(workspace.workspaceId(), path, true);
            var identity = object(change.get("diskIdentity"));
            if (!disk.document().version().equals(change.get("diskVersion")) || !identity.equals(WorkspaceEditTransaction.resourceIdentity(path)))
                throw failure(WorkspaceApiException.Code.STALE_VERSION, "Disk changed after deletion was proposed; rebase and review it");
            if (current.source() == WorkspaceDocument.Source.EDITOR && (editor == null || !editor.connected()))
                throw failure(WorkspaceApiException.Code.NOT_CONNECTED, "Editor document connection is unavailable");
            return new Effect(current, "", disk.bytes(), null, Kind.DELETE, identity, uri, null, true);
        }
        WorkspaceDocument missing = absent(uri, AgentPolicy.Capability.APPLY, entry.data());
        var operation = MiniJson.asArray(entry.data().get("operation"));
        Map<String, Object> applied = operation == null ? null : operation.stream().map(WorkspaceProposalService::object)
            .filter(effect -> "DELETE".equals(effect.get("kind")) && uri.equals(effect.get("uri"))).findFirst().orElse(null);
        if (applied == null) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Applied deletion evidence is unavailable");
        byte[] bytes = lease.get(text(applied, "diskBefore"));
        var original = WorkspaceRecoveryPlan.retainedOriginal(workspace, lease, entry, uri, WorkspacePaths.hash(bytes), object(applied.get("diskIdentity")));
        Path ancestor = WorkspacePaths.resolve(workspace.root(), uri).getParent();
        while (ancestor != null && !java.nio.file.Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null || !java.nio.file.Files.getFileStore(original.path()).equals(java.nio.file.Files.getFileStore(ancestor)))
            throw failure(WorkspaceApiException.Code.UNSUPPORTED, "Restoring a retained original across filesystems is unavailable");
        WorkspaceDocument current = missing;
        if ("EDITOR".equals(applied.get("source"))) for (WorkspaceDocument open : workspace.visibleDocuments()) {
            if (open.documentId().equals(applied.get("document")) && open.uri() == null) { current = open; break; }
        }
        if (!current.complete() || current.readOnly()) throw failure(WorkspaceApiException.Code.PERMISSION_DENIED, "Detached document cannot be rebound");
        validText(current.content());
        if (current.source() == WorkspaceDocument.Source.EDITOR && (editor == null || !editor.connected()))
            throw failure(WorkspaceApiException.Code.NOT_CONNECTED, "Editor document connection is unavailable");
        // A later human Save As owns its new binding. Only the still-detached original buffer is rebound.
        String after = current.source() == WorkspaceDocument.Source.EDITOR ? current.content() : TextFileCodec.decode(bytes, true).text();
        return new Effect(current, after, null, bytes, Kind.CREATE, null, uri, original, false);
    }

    private Effect creation(WorkspaceChangeJournal.Lease lease, WorkspaceChangeJournal.Entry entry,
                            Map<String, Object> change, boolean undo) throws IOException {
        String uri = text(change, "uri"), desired = lease.getText(text(change, "desired"));
        if (!undo) return new Effect(absent(uri, AgentPolicy.Capability.APPLY, entry.data()), desired, null,
            TextFileCodec.encodeRaw(desired, "UTF-8", false), Kind.CREATE, null);
        WorkspaceDocument current = resolve(change);
        authorize(current, AgentPolicy.Capability.APPLY, entry.data());
        if (!current.complete() || current.readOnly() || current.dirty() || !current.content().equals(desired))
            throw failure(WorkspaceApiException.Code.CONFLICT, "The created document has changed; undo cannot remove human work");
        Path path = WorkspacePaths.resolve(workspace.root(), uri);
        var disk = WorkspaceTextFile.capture(workspace.workspaceId(), path, true);
        if (!java.util.Arrays.equals(disk.bytes(), TextFileCodec.encodeRaw(desired, "UTF-8", false)))
            throw failure(WorkspaceApiException.Code.CONFLICT, "The created file has changed on disk");
        var steps = MiniJson.asArray(entry.data().get("resourceSteps"));
        Map<String, Object> identity = null;
        if (steps != null) for (Object item : steps) {
            var step = object(item);
            if ("CREATE".equals(step.get("kind")) && uri.equals(step.get("target"))) identity = object(step.get("fingerprint"));
        }
        if (identity == null) throw failure(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Creation identity is unavailable");
        if (!identity.equals(WorkspaceEditTransaction.resourceIdentity(path)))
            throw failure(WorkspaceApiException.Code.CONFLICT, "The created resource was changed or replaced");
        if (current.source() == WorkspaceDocument.Source.EDITOR && (editor == null || !editor.connected()))
            throw failure(WorkspaceApiException.Code.NOT_CONNECTED, "Editor document connection is unavailable");
        return new Effect(current, "", disk.bytes(), null, Kind.DELETE, identity);
    }
    private void checkRecord(WorkspaceChangeJournal.Entry entry) {
        if (!workspace.workspaceId().equals(entry.data().get("workspaceId")) || !workspace.root().toUri().toString().equals(entry.data().get("root")))
            throw failure(WorkspaceApiException.Code.NOT_FOUND, "Proposal belongs to another workspace");
        for (var change : changes(entry.data())) {
            for (String field : List.of("uri", "destination")) {
                Object uri = change.get(field);
                if (uri != null) workspace.policy().checkPath(workspace.root().relativize(WorkspacePaths.resolve(workspace.root(), (String) uri)), AgentPolicy.Capability.READ);
            }
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
    private Map<String, Object> summary(WorkspaceChangeJournal.Entry entry) {
        var data = entry.data();
        return map("id", entry.id(), "revision", entry.revision(), "status", data.get("status"), "workspaceId", data.get("workspaceId"),
            "actor", data.get("actor"), "reviewedBy", data.get("reviewedBy"), "taskId", data.get("taskId"), "turnId", data.get("turnId"),
            "requestId", data.get("requestId"), "createdAt", data.get("createdAt"), "files", changes(data).size(),
            "documents", changes(data).stream().map(change -> map("kind", change.getOrDefault("kind", "TEXT"), "document", change.get("document"),
                "uri", change.get("uri"), "destination", change.get("destination"),
                "destinationPath", change.get("destination") instanceof String destination
                    ? workspace.root().relativize(WorkspacePaths.resolve(workspace.root(), destination)).toString() : null, "path", change.get("uri") instanceof String uri
                    ? workspace.root().relativize(WorkspacePaths.resolve(workspace.root(), uri)).toString() : null)).toList(),
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
