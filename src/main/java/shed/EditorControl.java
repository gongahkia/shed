package shed;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Explicit project grant for source-only opening, bounded reads and human-reviewed edits. */
final class EditorControl implements AutoCloseable {
    static final String SCOPE = "editor-control";
    static final Set<String> OPERATIONS = Set.of("editor/file/open", "editor/source/read", "editor/edits/propose", "editor/agent/activity", "editor/agent/progress", "editor/agent/state", "editor/agent/cancel", "editor/project/read", "editor/project/propose", "editor/diagnostics", "editor/feedback", "editor/preview/open", "editor/preview/state", "editor/preview/capture");
    static boolean mutation(String method) {
        return OPERATIONS.contains(method) && !Set.of("editor/source/read","editor/project/read","editor/agent/state","editor/diagnostics","editor/feedback","editor/preview/state","editor/preview/capture").contains(method);
    }
    static final int MAX_READ = 2048, MAX_REPLACEMENT = 8192, MAX_DOCUMENT = 1024 * 1024;
    private static final Set<String> CONTEXT = Set.of("schemaVersion", "instanceId", "windowId", "projectId", "paneId", "documentId",
        "documentRevision", "contextRevision", "caretOffset", "selectionStart", "selectionEnd");
    interface Host extends EditorNavigation.Host {
        ProjectFileOpen.Request open(ProjectBoundFileAcquisition.RootPin root, String path, EditorNavigation.State expected,
                                     BooleanSupplier current, Consumer<ProjectDocumentInstall.Result> completed);
        void activity(int start, int end);
        default void progress(AgentActivityState.State state, int start, int end) { if (state.terminal()) clearActivity(); else activity(start,end); }
        default Map<String,Object> observe(String operation){throw fail(WorkspaceApiException.Code.UNSUPPORTED);}
        default java.awt.image.BufferedImage capturePreview(){throw fail(WorkspaceApiException.Code.UNSUPPORTED);}
        void review(String proposal);
        void clearActivity();
    }
    private record Services(WorkspaceQueryService workspace, WorkspaceProposalService proposals) { }
    private final AgentActivityState activity = new AgentActivityState();
    private final EditorMetadata grant;
    private final ProjectBoundFileAcquisition.RootPin root;
    private final AtomicReference<Host> host;
    private final AtomicReference<Services> services;
    private final AtomicReference<Lease> active = new AtomicReference<>();
    private final AtomicBoolean awaitingEdt = new AtomicBoolean();

    EditorControl(EditorMetadata grant, Host host, WorkspaceQueryService workspace, WorkspaceProposalService proposals) throws java.io.IOException {
        this.grant = grant; this.host = new AtomicReference<>(host);
        services = new AtomicReference<>(new Services(workspace, proposals));
        root = ProjectBoundFileAcquisition.pinAuthorizedRoot(workspace.root());
    }
    private final class Lease implements AutoCloseable {
        final AtomicBoolean live = new AtomicBoolean(true);
        final AtomicReference<ProjectFileOpen.Request> open = new AtomicReference<>();
        final BooleanSupplier connection;
        Lease(BooleanSupplier connection) { this.connection = connection; }
        boolean current() { return live.get() && host.get() != null && connection.getAsBoolean(); }
        void require() { grant.requireLive(); if (!current()) throw fail(WorkspaceApiException.Code.CANCELLED); }
        @Override public void close() {
            live.set(false); var request = open.getAndSet(null); if (request != null) request.cancel();
        }
    }

    Map<String, Object> dispatch(String method, Map<String, Object> params, BooleanSupplier connection) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Control dispatch belongs off the EDT");
        if (!OPERATIONS.contains(method)) throw fail(WorkspaceApiException.Code.UNSUPPORTED);
        var fields = new java.util.HashSet<>(CONTEXT);
        fields.addAll(switch (method) {
            case "editor/file/open" -> Set.of("path");
            case "editor/source/read" -> Set.of("offset", "limit");
            case "editor/edits/propose" -> Set.of("start", "end", "text", "requestId");
            case "editor/diagnostics", "editor/feedback", "editor/preview/open", "editor/preview/state", "editor/preview/capture" -> Set.of();
            case "editor/project/read" -> Set.of("path", "offset", "limit");
            case "editor/project/propose" -> Set.of("changes", "requestId");
            case "editor/agent/progress" -> Set.of("start", "end", "sessionId", "agent", "phase", "completed", "total");
            case "editor/agent/state" -> Set.of();
            case "editor/agent/cancel" -> Set.of("sessionId");
            default -> Set.of("start", "end");
        });
        if (Set.of("editor/edits/propose","editor/project/propose").contains(method) && params.containsKey("sessionId")) fields.add("sessionId");
        if (!params.keySet().equals(fields)) throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
        if (!Long.valueOf(1).equals(params.get("schemaVersion"))) throw fail(WorkspaceApiException.Code.UNSUPPORTED_PROTOCOL);
        if (!grant.projectId.equals(text(params, "projectId", 64))) throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
        grant.requireIdentity(text(params, "instanceId", 64), text(params, "windowId", 64));
        Lease lease = new Lease(connection);
        if (!active.compareAndSet(null, lease)) throw fail(WorkspaceApiException.Code.BUSY);
        try (lease) {
            lease.require();
            EditorNavigation.State before = ui(lease, () -> owner().state());
            verify(params, before); grant.authorize(before.metadata());
            Services current = services.get();
            if (current == null) throw fail(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
            if (method.equals("editor/file/open")) return open(lease, params, before, current);
            if (method.equals("editor/diagnostics")||method.equals("editor/feedback")||method.startsWith("editor/preview/")) {
                Map<String,Object> observation;
                if(method.endsWith("capture")) {
                    var image=ui(lease,()->{requireState(lease,before);return owner().capturePreview();});
                    try {
                        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
                        if(bytes.size()>20000)throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
                        observation=Map.of("kind","capture","format","png","width",image.getWidth(),"height",image.getHeight(),"data",java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
                    } catch(java.io.IOException invalid){throw fail(WorkspaceApiException.Code.IO_ERROR);} finally {image.flush();}
                    check(lease,before);
                } else observation=ui(lease,()->{requireState(lease,before);return owner().observe(method);});
                var after=ui(lease,()->owner().state());grant.authorize(after.metadata());
                var reply=result(method,after,"observed");reply.put("observation",observation);return reply;
            }
            if (method.startsWith("editor/project/")) return project(lease,method,params,before,current);
            if (method.equals("editor/agent/state") || method.equals("editor/agent/cancel")) {
                if (method.endsWith("cancel")) {
                    var state = activity.cancel(text(params,"sessionId",64));
                    ui(lease, () -> { requireState(lease,before); owner().progress(state,0,0); return null; });
                }
                var reply=result(method,before,"activity");reply.put("activity",activity.snapshot());return reply;
            }
            if (before.metadata().length() > MAX_DOCUMENT) throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
            WorkspaceDocument source = current.workspace().snapshot(current.workspace().workspaceId(), before.metadata().documentId());
            if (source.source() != WorkspaceDocument.Source.EDITOR || !source.documentId().equals(before.metadata().documentId()) || !source.complete())
                throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
            check(lease, before);
            if (method.equals("editor/source/read")) {
                int offset = offset(params, "offset"), limit = offset(params, "limit");
                if (limit < 1 || limit > MAX_READ || offset > source.content().length()) throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
                int end = Math.min(source.content().length(), offset + limit);
                if (!GraphemeBoundary.isBoundary(source.content(), offset)) throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
                while (end > offset && !GraphemeBoundary.isBoundary(source.content(), end)) end--;
                if (end == offset && offset < source.content().length()) throw fail(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
                var result = result(method, before, "read"); result.put("offset", offset); result.put("nextOffset", end);
                result.put("content", source.content().substring(offset, end)); result.put("truncated", end < source.content().length());
                return result;
            }
            int start = offset(params, "start"), end = offset(params, "end");
            if (start > end || end > source.content().length() || !GraphemeBoundary.isBoundary(source.content(), start)
                    || !GraphemeBoundary.isBoundary(source.content(), end)) throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
            if (method.equals("editor/agent/activity")) {
                ui(lease, () -> { requireState(lease, before); owner().activity(start, end); return null; });
                return result(method, before, "shown");
            }
            if (method.equals("editor/agent/progress")) {
                var state=activity.update(text(params,"sessionId",64),text(params,"agent",40),text(params,"phase",20),number(params,"completed"),number(params,"total"));
                ui(lease, () -> { requireState(lease,before); owner().progress(state,start,end); return null; });
                var reply=result(method,before,"activity");reply.put("activity",state.json());return reply;
            }
            if (params.containsKey("sessionId")) activity.requireActive(text(params,"sessionId",64));
            String replacement = text(params, "text", MAX_REPLACEMENT), requestId = text(params, "requestId", 64);
            String after = source.content().substring(0, start) + replacement + source.content().substring(end);
            if (after.length() > MAX_DOCUMENT) throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
            ui(lease, () -> { requireState(lease, before); owner().activity(start, end); return null; });
            lease.require();
            var proposed = current.proposals().propose(requestId, "editor-control", "edit",
                List.of(new WorkspaceProposalService.Input(source.documentId(), source.version(), after)));
            String proposal = (String) proposed.get("id");
            // persistence may have succeeded even if delivery or review presentation subsequently fails
            try { ui(lease, () -> { owner().review(proposal); return null; }); }
            catch (RuntimeException uncertain) { throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN); }
            var result = result(method, before, "proposed"); result.put("proposalId", proposal); result.put("reviewRequired", true);
            return result;
        } finally { active.compareAndSet(lease, null); }
    }

    private Map<String,Object> project(Lease lease,String method,Map<String,Object> params,EditorNavigation.State before,Services current) {
        if(method.equals("editor/project/read")) {
            String name=EditorProjectChanges.path(params,"path");authorizePath(name,before,current.workspace(),AgentPolicy.Capability.READ);
            int offset=offset(params,"offset"),limit=offset(params,"limit");
            if(limit<1||limit>MAX_READ)throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
            var source=current.workspace().snapshot(current.workspace().workspaceId(),name);
            if(!source.complete()||source.content().length()>MAX_DOCUMENT)throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
            if(offset>source.content().length()||!GraphemeBoundary.isBoundary(source.content(),offset))throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
            int end=Math.min(source.content().length(),offset+limit);while(end>offset&&!GraphemeBoundary.isBoundary(source.content(),end))end--;
            if(end==offset&&offset<source.content().length())throw fail(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
            check(lease,before);var reply=result(method,before,"read");
            reply.put("file",Map.of("path",name,"version",source.version(),"content",source.content().substring(offset,end),"offset",offset,
                "nextOffset",end,"length",source.content().length(),"truncated",end<source.content().length(),"source",source.source().name()));return reply;
        }
        var changes=EditorProjectChanges.parse(params.get("changes"));
        if(params.containsKey("sessionId"))activity.requireActive(text(params,"sessionId",64));
        for(var change:changes){
            authorizePath(change.document(),before,current.workspace(),AgentPolicy.Capability.PROPOSE);
            if(change.destination()!=null)authorizePath(change.destination(),before,current.workspace(),AgentPolicy.Capability.PROPOSE);
        }
        check(lease,before);lease.require();
        var proposed=current.proposals().propose(text(params,"requestId",64),"project changes","review",changes);
        String id=(String)proposed.get("id");
        try {ui(lease,()->{owner().review(id);return null;});}catch(RuntimeException uncertain){throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN);}
        var reply=result(method,before,"proposed");reply.put("proposalId",id);reply.put("reviewRequired",true);return reply;
    }
    private void authorizePath(String name,EditorNavigation.State before,WorkspaceQueryService workspace,AgentPolicy.Capability capability){
        Path target=WorkspacePaths.resolve(root.path(),name);workspace.policy().checkPath(root.path().relativize(target),capability);
        for(Path configured:before.metadata().roots())if(!configured.equals(root.path())&&target.startsWith(configured))throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
    }

    private Map<String, Object> open(Lease lease, Map<String, Object> params, EditorNavigation.State before, Services current) {
        String path = text(params, "path", 4096);
        try {
            Path relative = ProjectBoundFileAcquisition.checkedRelativePath(path);
            current.workspace().policy().checkPath(relative, AgentPolicy.Capability.READ);
            var m = before.metadata();
            grant.authorize(new EditorMetadata.Context(m.activeRoot(), m.roots(), m.generation(), m.paneId(), m.documentId(),
                root.path().resolve(relative), m.length(), m.revision(), m.dirty(), m.caret(), m.start(), m.end()));
        } catch (java.io.IOException invalid) { throw fail(WorkspaceApiException.Code.PROTECTED_PATH); }
        var completed = new CompletableFuture<ProjectDocumentInstall.Result>();
        ui(lease, () -> {
            requireState(lease, before);
            var request = owner().open(root, path, before, lease::current, completed::complete);
            lease.open.set(request); if (!lease.current()) request.cancel(); return null;
        });
        final ProjectDocumentInstall.Result outcome;
        try { outcome = completed.get(2500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw openTimeout(lease); }
        catch (TimeoutException timeout) { throw openTimeout(lease); }
        catch (ExecutionException failed) { throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN); }
        if (outcome.outcome() != ProjectDocumentInstall.Outcome.APPLIED) throw fail(switch (outcome.outcome()) {
            case CANCELLED -> WorkspaceApiException.Code.CANCELLED;
            case REJECTED -> WorkspaceApiException.Code.CONTEXT_CHANGED;
            default -> WorkspaceApiException.Code.OUTCOME_UNKNOWN;
        });
        try {
            var state = ui(lease, () -> owner().state()); grant.authorize(state.metadata());
            return result("editor/file/open", state, "applied");
        } catch (RuntimeException changed) { throw fail(WorkspaceApiException.Code.OUTCOME_UNKNOWN); }
    }

    private WorkspaceApiException openTimeout(Lease lease) {
        var request = lease.open.get();
        return fail(request == null || request.cancel() || request.outcome() == ProjectDocumentInstall.Outcome.CANCELLED
            ? WorkspaceApiException.Code.TIMEOUT : WorkspaceApiException.Code.OUTCOME_UNKNOWN);
    }
    private void verify(Map<String, Object> p, EditorNavigation.State state) {
        EditorNavigation.verify(new EditorNavigation.Command("control", text(p,"instanceId",64), text(p,"windowId",64),
            text(p,"projectId",64), text(p,"paneId",64), text(p,"documentId",64), number(p,"documentRevision"),
            number(p,"contextRevision"), offset(p,"caretOffset"), offset(p,"selectionStart"), offset(p,"selectionEnd"), 0, 0), state);
    }
    private void check(Lease lease, EditorNavigation.State expected) {
        ui(lease, () -> { requireState(lease, expected); return null; });
    }
    private void requireState(Lease lease, EditorNavigation.State expected) {
        lease.require(); if (!expected.equals(owner().state())) throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
    }
    private Host owner() { var value = host.get(); if (value == null) throw fail(WorkspaceApiException.Code.AUTHORIZATION_REVOKED); return value; }
    private <T> T ui(Lease lease, Callable<T> action) {
        lease.require();
        if (!awaitingEdt.compareAndSet(false, true)) throw fail(WorkspaceApiException.Code.BUSY);
        FutureTask<T> task = new FutureTask<>(() -> { lease.require(); return action.call(); });
        SwingUtilities.invokeLater(() -> { awaitingEdt.set(false); task.run(); });
        try { return task.get(EditorMetadata.EDT_WAIT_MILLIS, TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) { task.cancel(false); throw fail(WorkspaceApiException.Code.TIMEOUT); }
        catch (InterruptedException interrupted) { task.cancel(false); Thread.currentThread().interrupt(); throw fail(WorkspaceApiException.Code.CANCELLED); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof WorkspaceApiException known) throw known;
            throw fail(WorkspaceApiException.Code.INVALID_CONTEXT);
        }
    }
    private LinkedHashMap<String, Object> result(String method, EditorNavigation.State state, String outcome) {
        var value = new LinkedHashMap<>(grant.navigation.result(state));
        value.put("capabilities", List.of("status", "editor-metadata", "editor-navigation", SCOPE));
        value.put("operation", method); value.put("outcome", outcome); return value;
    }
    static String text(Map<String, Object> p, String key, int maximum) {
        if (!(p.get(key) instanceof String value) || value.length() > maximum || value.indexOf('\0') >= 0)
            throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
        return value;
    }
    private static long number(Map<String, Object> p, String key) {
        if (!(p.get(key) instanceof Long value) || value < 0) throw fail(WorkspaceApiException.Code.INVALID_REQUEST); return value;
    }
    private static int offset(Map<String, Object> p, String key) {
        long value = number(p,key); if (value > Integer.MAX_VALUE) throw fail(WorkspaceApiException.Code.INVALID_OFFSET); return (int)value;
    }
    private static WorkspaceApiException fail(WorkspaceApiException.Code code) { return new WorkspaceApiException(code, "Editor control unavailable: " + code); }
    @Override public void close() {
        var lease = active.get(); if (lease != null) lease.close();
        Host old = host.getAndSet(null); services.set(null); activity.close();
        if (old != null) SwingUtilities.invokeLater(old::clearActivity);
    }
}
