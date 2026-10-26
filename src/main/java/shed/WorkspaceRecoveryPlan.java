package shed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable inspection and guarded restoration plan for interrupted text and file-presence operations. */
record WorkspaceRecoveryPlan(List<Object> observations, List<Action> actions, List<WorkspaceRenameRecovery.Action> renames, String fingerprint,
                             String verifiedOutcome, boolean restorable, List<Object> resources) {
    record Artifact(Path path, Map<String, Object> identity) { }
    record Action(Map<String, Object> effect, WorkspaceDocument current, WorkspaceTextFile.Snapshot disk,
                  String text, byte[] bytes, boolean restoreDisk, Artifact artifact, boolean removeDisk, Artifact returnTo) {
        Action { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
        String document() { return effect.get("uri") instanceof String uri ? uri : (String) effect.get("document"); }
    }

    static WorkspaceRecoveryPlan capture(WorkspaceQueryService workspace, WorkspaceChangeJournal.Lease lease,
                                         WorkspaceChangeJournal.Entry entry) throws IOException {
        List<Object> operation = MiniJson.asArray(entry.data().get("operation"));
        if (operation == null || operation.size() > WorkspaceProposalService.MAX_FILES) throw invalid("Operation evidence is unavailable");
        List<Object> resources = new ArrayList<>();
        for (String key : List.of("resourceSteps", "restoreSteps")) {
            var steps = MiniJson.asArray(entry.data().get(key)); if (steps != null) resources.addAll(steps);
        }
        if (resources.size() > 4096) throw invalid("Recovery resource limit exceeded");
        List<Object> observations = new ArrayList<>(); List<Action> actions = new ArrayList<>();
        List<WorkspaceRenameRecovery.Action> renames = new ArrayList<>();
        boolean allBefore = true, allAfter = true, restorable = true;
        for (Object item : operation) {
            var effect = object(item);
            if ("RENAME".equals(effect.get("kind"))) {
                var rename = WorkspaceRenameRecovery.capture(workspace, lease, effect, resources);
                observations.add(rename.observation());
                if (rename.action() != null) renames.add(rename.action());
                allBefore &= rename.before(); allAfter &= rename.after(); restorable &= rename.restorable();
                continue;
            }
            if ("CREATE".equals(effect.get("kind")) || "DELETE".equals(effect.get("kind"))) {
                var presence = presence(workspace, lease, effect, resources);
                observations.add(presence.observation());
                if (presence.action() != null) actions.add(presence.action());
                allBefore &= presence.before(); allAfter &= presence.after(); restorable &= presence.restorable();
                continue;
            }
            String before = lease.getText(text(effect, "before")), after = lease.getText(text(effect, "after"));
            byte[] beforeBytes = effect.get("diskBefore") == null ? null : lease.get(text(effect, "diskBefore"));
            if (beforeBytes != null) lease.get(text(effect, "diskAfter"));
            var observation = map("document", effect.get("document"), "uri", effect.get("uri"), "originalSource", effect.get("source"));
            WorkspaceDocument current = null; WorkspaceTextFile.Snapshot disk = null;
            String state, diskState = null, reason = null;
            String document = effect.get("uri") instanceof String uri ? uri : text(effect, "document");
            try {
                current = workspace.snapshot(workspace.workspaceId(), document); observation.put("current", current.metadata());
                state = current.complete() ? classify(current.content(), before, after) : "INCOMPLETE";
                if (current.readOnly()) reason = "The current document is read-only";
            } catch (WorkspaceApiException unavailable) { state = unreadable(unavailable); observation.put("error", unavailable.toMap()); }
            if (beforeBytes != null || current != null && current.source() == WorkspaceDocument.Source.DISK) {
                try {
                    Path path = WorkspacePaths.resolve(workspace.root(), text(effect, "uri"));
                    disk = WorkspaceTextFile.capture(workspace.workspaceId(), path, true);
                    observation.put("diskVersion", disk.document().version());
                    diskState = beforeBytes == null ? classify(disk.document().content(), before, after)
                        : classify(WorkspacePaths.hash(disk.bytes()), text(effect, "diskBefore"), text(effect, "diskAfter"));
                    if (beforeBytes == null) beforeBytes = disk.image().encode(before);
                } catch (WorkspaceApiException unavailable) { diskState = unreadable(unavailable); observation.put("diskError", unavailable.toMap()); }
                observation.put("diskState", diskState);
            }
            boolean needsDisk = diskState != null && !before(diskState);
            Artifact artifact = null;
            if (needsDisk && beforeBytes != null) {
                var candidates = candidates(workspace, lease, resources, text(effect, "uri"), WorkspacePaths.hash(beforeBytes));
                observation.put("artifacts", candidates.observations()); artifact = candidates.available();
                if (candidates.changedOriginal()) reason = "A retained original changed; preserve and inspect its newer contents";
            }
            if (!known(state) && !(state.equals("MISSING") && diskState != null && diskState.equals("MISSING") && artifact != null))
                reason = "Current text differs from the recorded before/after snapshots or is unavailable";
            if (needsDisk && !diskState.equals("AFTER") && !(diskState.equals("MISSING") && artifact != null))
                reason = "Disk contents differ, or a missing file has no intact recorded original";
            if (needsDisk && beforeBytes == null) reason = "The original disk contents are unavailable";
            boolean alreadyBefore = before(state) && (diskState == null || before(diskState));
            boolean alreadyAfter = after(state) && (diskState == null || after(diskState));
            String combined = diskState == null || state.equals(diskState) ? state : known(state) && known(diskState) ? "MIXED" : "DIVERGED";
            if (diskState != null && diskState.equals("MISSING")) combined = "MISSING";
            observation.put("state", combined); observation.put("bufferState", state); observation.put("restoreReason", reason);
            observation.put("canRestore", reason == null); observations.add(observation);
            allBefore &= alreadyBefore; allAfter &= alreadyAfter; restorable &= reason == null;
            if (!alreadyBefore && reason == null) actions.add(new Action(effect, current, disk, before, beforeBytes, needsDisk, artifact, false, null));
        }
        String kind = Objects.toString(entry.data().get("operationKind"), ""); boolean undo = kind.equals("UNDO");
        if (!kind.equals("APPLY") && !undo) restorable = false;
        if (undo && MiniJson.asArray(entry.data().get("appliedOperation")) == null) restorable = false;
        String outcome = !kind.equals("APPLY") && !undo ? null
            : allAfter ? (undo ? "UNDONE" : "APPLIED") : allBefore ? (undo ? "APPLIED" : "FAILED") : null;
        if (undo && "APPLIED".equals(outcome) && MiniJson.asArray(entry.data().get("appliedOperation")) == null) outcome = null;
        String proof = WorkspacePaths.hash(WorkspaceChangeJournal.canonical(map("id", entry.id(), "revision", entry.revision(), "observations", observations)));
        return new WorkspaceRecoveryPlan(List.copyOf(observations), List.copyOf(actions), List.copyOf(renames), proof, outcome, restorable, List.copyOf(resources));
    }

    private record Presence(Map<String, Object> observation, Action action, boolean before, boolean after, boolean restorable) { }

    /** File presence is evidence: a missing created file is BEFORE, a missing deleted file is AFTER. */
    private static Presence presence(WorkspaceQueryService workspace, WorkspaceChangeJournal.Lease lease,
                                     Map<String, Object> effect, List<Object> resources) throws IOException {
        boolean create = "CREATE".equals(effect.get("kind"));
        String uri = text(effect, "uri"), before = lease.getText(text(effect, "before")), after = lease.getText(text(effect, "after"));
        byte[] bytes = lease.get(text(effect, create ? "diskAfter" : "diskBefore"));
        String expectedText = create ? after : before, digest = WorkspacePaths.hash(bytes);
        var observation = map("document", effect.get("document"), "uri", uri, "kind", effect.get("kind"), "originalSource", effect.get("source"));
        WorkspaceDocument current = null; WorkspaceTextFile.Snapshot disk = null;
        String reason = null, diskState;
        try {
            if ("EDITOR".equals(effect.get("source"))) {
                for (WorkspaceDocument open : workspace.visibleDocuments()) if (open.documentId().equals(effect.get("document"))) current = open;
            }
            if (current == null) current = workspace.snapshot(workspace.workspaceId(), uri);
            observation.put("current", current.metadata());
        } catch (WorkspaceApiException unavailable) {
            if (unavailable.code() != WorkspaceApiException.Code.NOT_FOUND) {
                unreadable(unavailable); reason = "The current document is unavailable"; observation.put("error", unavailable.toMap());
            }
        }
        Path path = WorkspacePaths.resolve(workspace.root(), uri);
        workspace.policy().checkPath(workspace.root().relativize(path), AgentPolicy.Capability.READ);
        Artifact returnTo = effect.get("retainedOriginal") == null ? null : originalReference(workspace, lease, uri, object(effect.get("retainedOriginal")));
        String retainedState = null;
        if (returnTo != null) {
            Map<String, Object> actual = null;
            try { actual = WorkspaceEditTransaction.resourceIdentity(returnTo.path()); retainedState = actual.equals(returnTo.identity()) ? "INTACT" : "CHANGED"; }
            catch (java.nio.file.NoSuchFileException missing) { retainedState = "MISSING"; }
            observation.put("retainedOriginal", map("uri", returnTo.path().toUri().toString(), "state", retainedState, "identity", actual));
        }
        try {
            disk = WorkspaceTextFile.capture(workspace.workspaceId(), path, true);
            observation.put("diskVersion", disk.document().version());
            diskState = WorkspacePaths.hash(disk.bytes()).equals(digest) ? create ? "AFTER" : "BEFORE" : "DIVERGED";
            if (known(diskState)) {
                Map<String, Object> identity = create ? returnTo == null ? null : returnTo.identity() : MiniJson.asObject(effect.get("diskIdentity"));
                if (create) for (Object item : resources) {
                    var step = object(item);
                    if ("CREATE".equals(step.get("kind")) && uri.equals(step.get("target"))) identity = object(step.get("fingerprint"));
                }
                var actual = WorkspaceEditTransaction.resourceIdentity(path); observation.put("diskIdentity", actual);
                if (identity == null || !identity.equals(actual)) diskState = "DIVERGED";
            }
        } catch (WorkspaceApiException unavailable) {
            diskState = unreadable(unavailable);
            if (diskState.equals("MISSING")) diskState = create ? "BEFORE" : "AFTER";
            else observation.put("diskError", unavailable.toMap());
        }
        boolean open = current != null && current.source() == WorkspaceDocument.Source.EDITOR;
        boolean boundHere = open && current.uri() != null && WorkspacePaths.resolve(workspace.root(), current.uri()).equals(path);
        var format = MiniJson.asObject(effect.get("editorFormat"));
        boolean formatMatches = !open || (format == null ? current.uri() == null || !current.dirty()
            : current.encoding().equals(format.get("encoding")) && current.lineEnding().equals(format.get("lineEnding"))
                && Objects.equals(current.byteOrderMark(), format.get("bom")));
        if (open && (!current.complete() || current.readOnly() || !current.content().equals(expectedText)
            || current.uri() != null && !boundHere || !formatMatches))
            reason = "The open document changed; preserve its contents and binding";
        if (!known(diskState)) reason = "The disk resource differs from the recorded creation/deletion or is unavailable";
        if (returnTo != null && !(before(diskState) && "INTACT".equals(retainedState) || after(diskState) && "MISSING".equals(retainedState)))
            reason = "The retained original and restored file do not match a complete before/after move";
        boolean consistent = reason == null;
        Artifact artifact = null;
        if (!create && !before(diskState)) {
            var candidates = candidates(workspace, lease, resources, uri, digest);
            artifact = candidates.available(); observation.put("artifacts", candidates.observations());
            if (artifact == null || candidates.changedOriginal()) reason = "The retained original is missing or changed";
        }
        boolean beforeBinding = !open || (create ? current.uri() == null : boundHere);
        boolean afterBinding = !open || (create ? boundHere : current.uri() == null);
        boolean alreadyBefore = consistent && before(diskState) && beforeBinding;
        boolean alreadyAfter = consistent && after(diskState) && afterBinding;
        observation.put("diskState", diskState);
        observation.put("state", alreadyBefore ? "BEFORE" : alreadyAfter ? "AFTER" : reason == null ? "MIXED" : "DIVERGED");
        observation.put("bufferState", open ? current.uri() == null ? "DETACHED" : "BOUND" : "CLOSED");
        observation.put("restoreReason", reason); observation.put("canRestore", reason == null);
        Action action = alreadyBefore || reason != null ? null : new Action(effect, current, disk, expectedText,
            create ? null : bytes, !before(diskState), artifact, create, returnTo);
        return new Presence(observation, action, alreadyBefore, alreadyAfter, reason == null);
    }

    static Artifact retainedOriginal(WorkspaceQueryService workspace, WorkspaceChangeJournal.Lease lease, WorkspaceChangeJournal.Entry entry, String uri,
                                     String digest, Map<String, Object> identity) throws IOException {
        var steps = MiniJson.asArray(entry.data().get("resourceSteps"));
        if (steps == null) throw invalid("Deletion resource evidence is unavailable");
        var originals = steps.stream().filter(item -> {
            var step = object(item);
            return "BACKUP".equals(step.get("kind")) && uri.equals(step.get("source")) && identity.equals(step.get("fingerprint"));
        }).toList();
        var result = candidates(workspace, lease, originals, uri, digest);
        if (result.available() == null || result.changedOriginal())
            throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "The retained deletion original is missing or changed; inspect recovery evidence");
        return result.available();
    }

    private static Artifact originalReference(WorkspaceQueryService workspace, WorkspaceChangeJournal.Lease lease, String uri, Map<String, Object> reference) throws IOException {
        String name = text(reference, "uri"); Path path = lease.retainedPath(name);
        Path target = WorkspacePaths.resolve(workspace.root(), uri);
        workspace.policy().checkPath(workspace.root().relativize(target), AgentPolicy.Capability.READ);
        if (path != null) return new Artifact(path, object(reference.get("identity")));
        path = WorkspacePaths.resolve(workspace.root(), name);
        if (!name.equals(path.toUri().toString()) || !path.getFileName().toString().equals("original")
            || path.getParent() == null || path.getParent().getFileName() == null || !path.getParent().getFileName().toString().startsWith(".shed-edit-backup-")
            || !Objects.equals(path.getParent().getParent(), target.getParent())) throw invalid("Invalid retained-original reference");
        return new Artifact(path, object(reference.get("identity")));
    }

    private record Candidates(Artifact available, List<Object> observations, boolean changedOriginal) { }
    private static Candidates candidates(WorkspaceQueryService workspace, WorkspaceChangeJournal.Lease lease, List<Object> resources, String uri, String digest) throws IOException {
        List<Object> observed = new ArrayList<>(); Artifact available = null; boolean changed = false;
        for (Object item : resources) {
            var step = object(item); boolean backup = "BACKUP".equals(step.get("kind")) && uri.equals(step.get("source"));
            boolean staged = "STAGED".equals(step.get("kind")) && uri.equals(step.get("target"));
            if (!backup && !staged) continue;
            var expected = object(step.get("fingerprint"));
            if (!digest.equals(expected.get("digest"))) continue;
            String reference = text(step, backup ? "target" : "source");
            Path path = backup ? originalReference(workspace, lease, uri, map("uri", reference, "identity", expected)).path()
                : WorkspacePaths.resolve(workspace.root(), reference);
            if (!path.toUri().toString().equals(reference)) throw invalid("Recovery artifact identity changed through an alias");
            Path original = WorkspacePaths.resolve(workspace.root(), uri);
            workspace.policy().checkPath(workspace.root().relativize(original), AgentPolicy.Capability.READ);
            boolean privateOriginal = backup && lease.retainedPath(reference) != null;
            boolean shape = backup ? privateOriginal || path.getFileName().toString().equals("original") && path.getParent().getFileName().toString().startsWith(".shed-edit-backup-")
                : path.getFileName().toString().startsWith(".shed-edit-") && path.getFileName().toString().endsWith(".tmp");
            if (!shape) throw invalid("Unexpected recovery artifact path");
            Path artifactParent = backup ? path.getParent().getParent() : path.getParent();
            if (!privateOriginal && !artifactParent.equals(original.getParent())) throw invalid("Recovery artifact is outside its original document directory");
            String state; Map<String, Object> identity = null;
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() > WorkspaceTextFile.MAX_BYTES) state = "CHANGED";
                else {
                    identity = WorkspaceEditTransaction.resourceIdentity(path);
                    state = identity.equals(expected) ? "INTACT" : "CHANGED";
                }
            } catch (java.nio.file.NoSuchFileException absent) { state = "MISSING"; }
            observed.add(map("uri", reference, "state", state, "identity", identity));
            if (state.equals("INTACT") && available == null) available = new Artifact(path, expected);
            if (backup && state.equals("CHANGED")) changed = true;
        }
        return new Candidates(available, observed, changed);
    }

    private static String classify(String current, String before, String after) {
        if (current.equals(before) && current.equals(after)) return "UNCHANGED";
        return current.equals(after) ? "AFTER" : current.equals(before) ? "BEFORE" : "DIVERGED";
    }
    private static boolean before(String state) { return state.equals("BEFORE") || state.equals("UNCHANGED"); }
    private static boolean after(String state) { return state.equals("AFTER") || state.equals("UNCHANGED"); }
    private static boolean known(String state) { return before(state) || after(state); }
    private static String unreadable(WorkspaceApiException failure) {
        if (failure.code() == WorkspaceApiException.Code.NOT_FOUND) return "MISSING";
        if (failure.code() == WorkspaceApiException.Code.UNSUPPORTED || failure.code() == WorkspaceApiException.Code.IO_ERROR) return "UNREADABLE";
        throw failure;
    }
    private static Map<String, Object> object(Object value) { var result = MiniJson.asObject(value); if (result == null) throw invalid("Invalid recovery metadata"); return result; }
    private static String text(Map<String, Object> value, String key) { if (!(value.get(key) instanceof String result)) throw invalid("Missing recovery metadata: " + key); return result; }
    private static WorkspaceApiException invalid(String message) { return new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, message); }
    private static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
}
