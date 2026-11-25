package shed;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/** one explicit project grant; retains immutable metadata and a revocable, weak-owner host only. */
final class EditorMetadata implements AutoCloseable {
    static final int MAX_ROOTS = 32;
    static final int MAX_OPEN_DOCUMENTS = 1024;
    static final int MAX_PANES = 64;
    static final int EDT_WAIT_MILLIS = 200;
    private static final AgentPolicy PATH_POLICY = new AgentPolicy(Set.of(AgentPolicy.Capability.READ), List.of(),
        List.of("**/*.env", "**/*.env.*"), true);
    interface Host { Context capture(); }
    record Approval(Path root, long generation) { }
    record Context(Path activeRoot, List<Path> roots, long generation, String paneId, String documentId,
                   Path path, int length, long revision, boolean dirty, int caret, int start, int end) { }

    final String projectId = "project-" + UUID.randomUUID();
    private final ApplicationStatus application;
    private final Approval approval;
    private final Path root;
    private final Object rootKey;
    private final AtomicReference<Host> host;
    private final Semaphore admission = new Semaphore(1);
    private final AtomicBoolean awaitingEdt = new AtomicBoolean();
    private volatile long lastEdtNanos;
    final EditorNavigation navigation;
    EditorControl control;

    EditorMetadata(ApplicationStatus application, Approval approval, Host host) {
        this(application, approval, host, null);
    }

    static EditorMetadata navigation(ApplicationStatus application, Approval approval, EditorNavigation.Host host, EditorInteraction interaction) {
        return new EditorMetadata(application, approval, host, interaction);
    }

    static EditorMetadata control(ApplicationStatus application, Approval approval, EditorControl.Host host,
            EditorInteraction interaction, WorkspaceQueryService workspace, WorkspaceProposalService proposals) throws java.io.IOException {
        var grant = new EditorMetadata(application, approval, host, interaction);
        try { grant.control = new EditorControl(grant, host, workspace, proposals); return grant; }
        catch (java.io.IOException | RuntimeException failed) { grant.close(); throw failed; }
    }

    private EditorMetadata(ApplicationStatus application, Approval approval, Host host, EditorInteraction interaction) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Grant validation belongs off the EDT");
        this.application = Objects.requireNonNull(application);
        this.approval = Objects.requireNonNull(approval);
        this.host = new AtomicReference<>(Objects.requireNonNull(host));
        root = canonical(approval.root());
        rootKey = rootKey(root);
        navigation = interaction == null ? null : new EditorNavigation(this, (EditorNavigation.Host) host, interaction);
    }

    String scope() { return control != null ? EditorControl.SCOPE : navigation == null ? "editor-metadata" : "editor-navigation"; }
    void requireIdentity(String instance, String window) {
        if (!application.instanceId.equals(instance) || !application.windowId.equals(window)) throw failure(WorkspaceApiException.Code.UNAUTHORIZED);
    }

    static Context capture(EditorPane pane, EditorWorkspaceDocuments documents, Path activeRoot, List<Path> roots, long generation) {
        return capture(pane,documents,activeRoot,roots,generation,false);
    }
    /** Internal open precondition only; an untitled buffer's nullable path is never an observation grant. */
    static Context captureForOpen(EditorPane pane,EditorWorkspaceDocuments documents,Path activeRoot,List<Path> roots,long generation){
        return capture(pane,documents,activeRoot,roots,generation,true);
    }
    private static Context capture(EditorPane pane,EditorWorkspaceDocuments documents,Path activeRoot,List<Path> roots,long generation,boolean opening){
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Editor metadata belongs to the EDT");
        if (roots.size() > MAX_ROOTS) throw failure(WorkspaceApiException.Code.LIMIT_EXCEEDED);
        if (pane == null) throw failure(WorkspaceApiException.Code.NO_ACTIVE_EDITOR);
        if (pane.isDisposed()) throw failure(WorkspaceApiException.Code.PANE_UNAVAILABLE);
        FileBuffer buffer = pane.getBuffer();
        if (buffer == null || buffer.isScratch() || !opening&&!buffer.hasFilePath() || buffer.isLargeFile()
            || pane.getTerminalPane() != null || pane.isMarkdownPreview() || pane.getAlternateComponent() != null)
            throw failure(WorkspaceApiException.Code.NO_ACTIVE_EDITOR);
        var area = pane.getTextArea();
        if (area.getDocument() != buffer.getDocument()) throw failure(WorkspaceApiException.Code.CONTEXT_CHANGED);
        long revision = buffer.documentRevision();
        int length = buffer.getDocument().getLength();
        int caret = area.getCaretPosition(), start = area.getSelectionStart(), end = area.getSelectionEnd();
        validateCoordinates(length, revision, caret, start, end);
        Path path = buffer.hasFilePath()?buffer.getFile().toPath().toAbsolutePath().normalize():null;
        if (path!=null&&path.toString().length() > 8192) throw failure(WorkspaceApiException.Code.LIMIT_EXCEEDED);
        return new Context(activeRoot, List.copyOf(roots), generation, pane.observationId(), documents.metadataIdentity(buffer),
            path, length, revision, buffer.isModified(), caret, start, end);
    }

    static void validateCoordinates(int length, long revision, int caret, int start, int end) {
        if (length < 0 || revision < 0 || start < 0 || end < start || end > length || caret < 0 || caret > length
            || caret != start && caret != end) throw failure(WorkspaceApiException.Code.INVALID_CONTEXT);
    }

    Map<String, Object> observe(Map<String, Object> params) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Observation dispatch belongs off the EDT");
        if (!Set.of("schemaVersion", "projectId", "expectedPaneId", "expectedDocumentId").containsAll(params.keySet())
            || !params.containsKey("projectId")) throw failure(WorkspaceApiException.Code.INVALID_REQUEST);
        if (!Long.valueOf(1).equals(params.get("schemaVersion"))) throw failure(WorkspaceApiException.Code.UNSUPPORTED_PROTOCOL);
        String project = identifier(params, "projectId", true);
        String pane = identifier(params, "expectedPaneId", false), document = identifier(params, "expectedDocumentId", false);
        requireLive();
        if (!projectId.equals(project)) throw failure(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
        if (!admission.tryAcquire()) throw failure(WorkspaceApiException.Code.BUSY);
        try {
            Context first = snapshot();
            authorize(first);
            if (pane != null && !pane.equals(first.paneId())) throw failure(WorkspaceApiException.Code.PANE_UNAVAILABLE);
            if (document != null && !document.equals(first.documentId())) throw failure(WorkspaceApiException.Code.CONTEXT_CHANGED);
            Context current = snapshot();
            if (!first.equals(current)) throw failure(WorkspaceApiException.Code.CONTEXT_CHANGED);
            requireLive();
            return result(first);
        } finally { admission.release(); }
    }

    Map<String, Object> result(Context first) {
        return Map.ofEntries(Map.entry("schemaVersion", 1), Map.entry("protocolVersion", 2),
                Map.entry("instanceId", application.instanceId), Map.entry("windowId", application.windowId),
                Map.entry("capabilities", List.of("status", "editor-metadata")), Map.entry("projectId", projectId),
                Map.entry("paneId", first.paneId()), Map.entry("documentId", first.documentId()),
                Map.entry("documentLength", first.length()), Map.entry("documentRevision", first.revision()),
                Map.entry("dirty", first.dirty()), Map.entry("caretOffset", first.caret()),
                Map.entry("selectionStart", first.start()), Map.entry("selectionEnd", first.end()),
                Map.entry("coordinateUnit", "utf-16"), Map.entry("available", true), Map.entry("valid", true));
    }

    void authorizeProject(Path activeRoot,long generation){
        requireLive();if(generation!=approval.generation()){close();throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);}
        if(!root.equals(activeRoot))throw failure(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
        if(!rootKey.equals(rootKey(root))||!root.equals(canonical(approval.root()))){close();throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);}
    }
    void authorize(Context context) {
        validateCoordinates(context.length(), context.revision(), context.caret(), context.start(), context.end());
        if (context.generation() != approval.generation()) { close(); throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED); }
        if (!approval.root().equals(context.activeRoot()) || !context.roots().contains(approval.root()))
            throw failure(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
        try {
            if (!rootKey.equals(rootKey(root)) || !root.equals(canonical(approval.root()))) throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
        } catch (WorkspaceApiException unavailable) { close(); throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED); }
        Path path = canonical(context.path());
        if (!path.startsWith(root) || path.equals(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw failure(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
        try { PATH_POLICY.checkPath(root.relativize(path), AgentPolicy.Capability.READ); }
        catch (WorkspaceApiException denied) { throw failure(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED); }
        // a nested configured project never inherits its parent's observation grant
        for (Path configured : context.roots()) {
            Path candidate = canonical(configured);
            if (!candidate.equals(root) && path.startsWith(candidate) && candidate.getNameCount() >= root.getNameCount())
                throw failure(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
        }
    }

    private Context snapshot() {
        requireLive();
        if (!awaitingEdt.compareAndSet(false, true)) throw failure(WorkspaceApiException.Code.BUSY);
        FutureTask<Context> pending = new FutureTask<>(() -> {
            long started = System.nanoTime();
            try {
                requireLive();
                Host owner = host.get();
                if (owner == null) throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
                return owner.capture();
            } finally { lastEdtNanos = System.nanoTime() - started; }
        });
        // a timed-out, cancelled task keeps its queue slot until the EDT consumes it
        SwingUtilities.invokeLater(() -> { awaitingEdt.set(false); pending.run(); });
        try { return pending.get(EDT_WAIT_MILLIS, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) { throw failure(WorkspaceApiException.Code.TIMEOUT); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure(WorkspaceApiException.Code.SESSION_INVALID); }
        catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof WorkspaceApiException known) throw known;
            throw failure(WorkspaceApiException.Code.INVALID_CONTEXT);
        } finally { pending.cancel(false); }
    }

    long lastEdtNanos() { return lastEdtNanos; }
    void validateAuthorization() { requireLive(); }
    void requireLive() {
        application.requireObservationReady();
        if (host.get() == null) throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
    }
    @Override public void close() { host.set(null); if (control != null) control.close(); if (navigation != null) navigation.close(); }

    private static Path canonical(Path path) {
        try {
            if (path == null || path.toString().length() > 8192 || !path.isAbsolute() || !path.equals(path.normalize())
                || !path.equals(path.toRealPath())) throw failure(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
            return path;
        } catch (java.io.IOException | SecurityException denied) { throw failure(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED); }
    }
    private static Object rootKey(Path path) {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.fileKey() == null) throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
            return attributes.fileKey();
        } catch (java.io.IOException | SecurityException denied) { throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED); }
    }
    private static String identifier(Map<String, Object> params, String key, boolean required) {
        if (!required && !params.containsKey(key)) return null;
        if (!(params.get(key) instanceof String value) || !value.matches("[a-z]+-[a-f0-9-]{36}") || value.length() > 64)
            throw failure(WorkspaceApiException.Code.INVALID_REQUEST);
        return value;
    }
    static WorkspaceApiException failure(WorkspaceApiException.Code code) {
        return new WorkspaceApiException(code, "Editor observation unavailable: " + code.name());
    }
}
