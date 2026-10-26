package shed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Both names and the editor binding are evidence for a recoverable exclusive rename. */
final class WorkspaceRenameRecovery {
    private WorkspaceRenameRecovery() { }

    record Inspection(Map<String, Object> observation, Action action, boolean before, boolean after, boolean restorable) { }
    record Action(Map<String, Object> effect, Path source, Path destination, Map<String, Object> identity,
                  Map<String, Object> sourceIdentity, Map<String, Object> destinationIdentity,
                  WorkspaceDocument current, String diskState, String digest) {
        void validate(WorkspaceQueryService workspace) throws IOException {
            if (!source.equals(path(workspace, (String) effect.get("uri")))
                || !destination.equals(path(workspace, (String) effect.get("destination")))
                || !Objects.equals(sourceIdentity, WorkspaceRenameRecovery.identity(source)) || !Objects.equals(destinationIdentity, WorkspaceRenameRecovery.identity(destination)))
                throw stale("Rename recovery paths changed after inspection");
            if (current != null) {
                var observed = workspace.snapshot(workspace.workspaceId(), current.documentId());
                if (!current.version().equals(observed.version())) throw stale("Rename recovery editor changed after inspection");
            }
            for (var open : workspace.visibleDocuments()) if (onPath(open, source) || onPath(open, destination)) {
                if (current == null || !current.documentId().equals(open.documentId())) throw stale("Another editor opened a rename recovery path");
            }
        }

        void restore(WorkspaceEditTransaction transaction) throws IOException {
            if (diskState.equals("AFTER")) transaction.renameFile(destination, source, identity);
            else if (diskState.equals("LINKED")) transaction.unlinkAlias(destination, source, identity);
        }

        WorkspaceDocumentEditor.TextChange editorChange(WorkspaceQueryService workspace) throws IOException {
            if (current == null) return null;
            var binding = WorkspaceDiskBinding.capture(workspace.workspaceId(), source);
            if (!java.util.Arrays.equals(binding.digest(), java.util.HexFormat.of().parseHex(digest)))
                throw stale("Restored rename contents changed before editor adoption");
            return new WorkspaceDocumentEditor.TextChange(current, current.content(), binding);
        }
    }

    static Inspection capture(WorkspaceQueryService workspace, WorkspaceChangeJournal.Lease lease,
                              Map<String, Object> effect, List<Object> resources) throws IOException {
        String uri = (String) effect.get("uri"), target = (String) effect.get("destination");
        Path source = path(workspace, uri), destination = path(workspace, target);
        var expected = MiniJson.asObject(effect.get("diskIdentity"));
        if (expected == null || source.equals(destination)) throw new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Invalid rename evidence");
        byte[] bytes = lease.get((String) effect.get("diskBefore")); String digest = WorkspacePaths.hash(bytes);
        if (!digest.equals(expected.get("digest")) || !digest.equals(effect.get("diskAfter")))
            throw new WorkspaceApiException(WorkspaceApiException.Code.RECOVERY_REQUIRED, "Rename preimage evidence is inconsistent");
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("kind", "RENAME"); observation.put("document", effect.get("document"));
        observation.put("uri", uri); observation.put("destination", target); observation.put("originalSource", effect.get("source"));
        Map<String, Object> from = identity(source), to = identity(destination);
        observation.put("sourceIdentity", from); observation.put("destinationIdentity", to);
        String state = expected.equals(from) && to == null ? "BEFORE" : from == null && expected.equals(to) ? "AFTER" : "DIVERGED";
        if (expected.equals(from) && expected.equals(to) && Files.isSameFile(source, destination)) {
            boolean owned = resources.stream().map(MiniJson::asObject).filter(Objects::nonNull).anyMatch(step ->
                "UNLINK_SOURCE".equals(step.get("kind")) && expected.equals(step.get("fingerprint"))
                    && (uri.equals(step.get("source")) && target.equals(step.get("target"))
                        || target.equals(step.get("source")) && uri.equals(step.get("target"))));
            if (owned) state = "LINKED";
        }
        String reason = state.equals("DIVERGED") ? "Both paths need inspection: a file changed, was replaced, or has no recorded link ownership" : null;
        WorkspaceDocument current = null;
        for (var open : workspace.visibleDocuments()) {
            boolean original = "EDITOR".equals(effect.get("source")) && open.documentId().equals(effect.get("document"));
            if (original || onPath(open, source) || onPath(open, destination)) {
                if (current != null) reason = "Multiple editor documents refer to the rename paths";
                current = open;
            }
        }
        String bufferState = "CLOSED";
        if (current != null) {
            observation.put("current", current.metadata());
            String text = "EDITOR".equals(effect.get("source")) ? lease.getText((String) effect.get("before")) : TextFileCodec.decode(bytes, true).text();
            Map<String, Object> format = MiniJson.asObject(effect.get("editorFormat"));
            var diskImage = TextFileCodec.decode(bytes, true);
            boolean formatMatches = format == null
                ? current.encoding().equals(diskImage.encoding()) && current.byteOrderMark() == diskImage.bom()
                    && current.lineEnding().equals(diskImage.lineEnding())
                : current.encoding().equals(format.get("encoding")) && current.lineEnding().equals(format.get("lineEnding"))
                    && Boolean.valueOf(current.byteOrderMark()).equals(format.get("bom"));
            if (!current.complete() || current.readOnly() || !current.content().equals(text) || !formatMatches)
                reason = "Editor contents or format changed after the recorded rename";
            bufferState = onPath(current, source) ? "BEFORE" : onPath(current, destination) ? "AFTER" : "DIVERGED";
            if (bufferState.equals("DIVERGED")) reason = "The editor was rebound outside the rename paths";
        }
        boolean before = reason == null && state.equals("BEFORE") && (current == null || bufferState.equals("BEFORE"));
        boolean after = reason == null && state.equals("AFTER") && (current == null || bufferState.equals("AFTER"));
        observation.put("diskState", state); observation.put("bufferState", bufferState);
        observation.put("state", reason != null ? "DIVERGED" : before ? "BEFORE" : after ? "AFTER" : state.equals("LINKED") ? "LINKED" : "MIXED");
        observation.put("restoreReason", reason); observation.put("canRestore", reason == null);
        return new Inspection(observation, reason == null && !before
            ? new Action(effect, source, destination, expected, from, to, current, state, digest) : null, before, after, reason == null);
    }

    static Path path(WorkspaceQueryService workspace, String uri) {
        Path path = WorkspacePaths.resolve(workspace.root(), uri);
        workspace.policy().checkPath(workspace.root().relativize(path), AgentPolicy.Capability.READ);
        if (!path.toUri().toString().equals(uri)) throw stale("A reviewed rename path now resolves through a different alias");
        return path;
    }

    private static Map<String, Object> identity(Path path) throws IOException {
        try { return WorkspaceEditTransaction.resourceIdentity(path); }
        catch (NoSuchFileException missing) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw missing;
            return null;
        }
    }

    private static boolean onPath(WorkspaceDocument document, Path path) {
        return document.uri() != null && WorkspaceRootResolver.normalizedIdentity(Path.of(java.net.URI.create(document.uri()))).equals(path);
    }
    private static WorkspaceApiException stale(String message) { return new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, message); }
}
