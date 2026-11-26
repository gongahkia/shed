package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/** Human-owned start/stop lifecycle. Machine requests never route through editor commands. */
final class AgentWorkspaceController implements AutoCloseable {
    private final Texteditor editor;
    private final Set<WorkspaceBridgeServer> owned = ConcurrentHashMap.newKeySet();
    private final Set<WorkspaceBridgeServer> releasing = ConcurrentHashMap.newKeySet();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final AgentSessionRegistry sessions = new AgentSessionRegistry();
    private WorkspaceBridgeServer active;
    private int startingJob = -1;
    private boolean stopping;
    private boolean proposalMode;
    private boolean terminalMode;
    private boolean applicationReady;
    private ApplicationStatus applicationStatus;
    private String status = "Agent API is stopped; no listener is running.";

    AgentWorkspaceController(Texteditor editor) { this.editor = editor; }

    void ownSession(AgentSessionService session) { sessions.own(session); }
    WorkspaceBridgeServer visualAttachment(){return active!=null&&active.controlEnabled()&&active.running()?active:null;}
    boolean visualAttachmentFailed(){return startingJob<0&&visualAttachment()==null;}
    void applicationReady() { applicationReady = true; if (applicationStatus != null) applicationStatus.ready(); }

    String handle(String argument) {
        if (active != null && !active.running()) { active = null; status = "Agent API connection has stopped."; }
        String value = argument == null ? "" : argument.trim();
        return switch (value) {
            case "delegate", "chat" -> {editor.toolWindowHost.showAgentChat();yield "Chat with your local coding agent.";}
            case "task" -> {AgentTaskLauncher.show(editor);yield "Advanced one-shot task setup.";}
            case "takeback" -> {editor.takeBackAgentControl();yield "Control returned to you.";}
            case "complete" -> editor.requestAiCompletion();
            case "edit", "ask", "run" -> {
                if (editor.toolWindowHost != null) editor.toolWindowHost.showAgentAction(
                    value.equals("edit") ? AgentAction.EDIT : value.equals("ask") ? AgentAction.ASK : AgentAction.RUN);
                yield "Describe your request in Advanced agent tools.";
            }
            case "" -> { editor.toolWindowHost.showAgentChat();yield "Chat with your local coding agent."; }
            case "watch" -> { editor.toolWindowHost.watchAgentChanges("start","Agent");yield "Watching external edits in Live changes."; }
            case "watch stop" -> {editor.toolWindowHost.watchAgentChanges("stop","Agent");yield "Watch stopped.";}
            case "changes" -> {editor.toolWindowHost.showAgentChanges();yield "Live change controls.";}
            case "proposals" -> { if (editor.toolWindowHost != null) editor.toolWindowHost.showAgentReview(); yield "Review proposals in the Agents panel."; }
            case "status", "api", "api status" -> status;
            case "api start" -> start(false, false);
            case "api start propose" -> start(true, false);
            case "api start status" -> start(false, true);
            case "api start metadata" -> start(false, false, true);
            case "api start navigation" -> start(false, false, true, true);
            case "api start control" -> start(false, false, true, true, true);
            case "api start terminal" -> start(false,false,true,true,true,true);
            case "api stop" -> stop();
            default -> "Usage: :agent [watch|watch stop|changes|proposals|edit|ask|run|complete] or :agent api start [propose|status|metadata|navigation|control|terminal]|status|stop";
        };
    }

    private String start(boolean allowProposals, boolean statusOnly) {
        return start(allowProposals, statusOnly, false);
    }

    private String start(boolean allowProposals, boolean statusOnly, boolean metadataMode) {
        return start(allowProposals, statusOnly, metadataMode, false);
    }

    private String start(boolean allowProposals, boolean statusOnly, boolean metadataMode, boolean navigationMode) {
        return start(allowProposals, statusOnly, metadataMode, navigationMode, false);
    }

    private String start(boolean allowProposals, boolean statusOnly, boolean metadataMode, boolean navigationMode, boolean controlMode) {
        return start(allowProposals,statusOnly,metadataMode,navigationMode,controlMode,false);
    }
    private String start(boolean allowProposals,boolean statusOnly,boolean metadataMode,boolean navigationMode,boolean controlMode,boolean allowTerminal) {
        if (disposed.get()) return "Agent API is unavailable while the editor closes.";
        if (active != null && active.running()) return active.statusOnly() == statusOnly && active.metadataEnabled() == metadataMode
            && active.navigationEnabled() == navigationMode && active.controlEnabled() == controlMode && proposalMode == allowProposals && terminalMode==allowTerminal ? status
            : "Stop the current API before starting it with different permissions.";
        if (startingJob >= 0) return "Agent API startup is already running.";
        Path root = statusOnly ? null : editor.workspaceController.activeRoot();
        if (!statusOnly && root == null) return "Select a workspace folder before starting the agent API.";
        ApplicationStatus capturedStatus = applicationStatus;
        boolean capturedReady = applicationReady;
        if (metadataMode && editor.workspaceController.roots().size() > EditorMetadata.MAX_ROOTS) return "Too many workspace folders for bounded metadata observation.";
        var excludedRoots = statusOnly || metadataMode ? java.util.List.copyOf(editor.workspaceController.roots()) : java.util.List.<Path>of();
        var approval = metadataMode ? new EditorMetadata.Approval(root, editor.workspaceRoots.generation()) : null;
        if (navigationMode && editor.editorPanes.size() > EditorMetadata.MAX_PANES) return "Too many panes for bounded navigation.";
        var interaction = navigationMode ? editor.beginAgentNavigation() : null;
        var editorOwner = new java.lang.ref.WeakReference<>(editor);
        EditorMetadata.Host observationHost = () -> {
            var live = editorOwner.get();
            if (live == null) throw EditorMetadata.failure(WorkspaceApiException.Code.APP_SHUTTING_DOWN);
            return live.captureEditorMetadata();
        };
        EditorControl.Host navigationHost = navigationHost(editorOwner, controlMode,allowTerminal);
        long request = generation.incrementAndGet();
        stopping = false;
        Path directory = Path.of(editor.configManager.getSessionDirectory(), "agent-api");
        proposalMode = allowProposals;terminalMode=allowTerminal;
        String mode = allowTerminal?"Reviewed editor and terminal control":controlMode ? "Reviewed editor control" : navigationMode ? "Editor navigation" : metadataMode ? "Editor metadata" : statusOnly ? "Status-only" : allowProposals ? "Proposal-only" : "Read-only";
        AgentPolicy policy = allowProposals ? new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE),
            java.util.List.of("**/README.md"), java.util.List.of(), true) : AgentPolicy.readOnly();
        Path sessions = Path.of(editor.configManager.getSessionDirectory());
        status = "Starting " + mode.toLowerCase(java.util.Locale.ROOT) + " agent API…";
        startingJob = editor.asyncJobService.submit("Start local agent API", token -> {
            WorkspaceBridgeServer server;
            if (statusOnly) server = WorkspaceBridgeServer.status(capturedStatus == null ? new ApplicationStatus(capturedReady) : capturedStatus, directory, excludedRoots);
            else if (metadataMode) {
                var application = capturedStatus == null ? new ApplicationStatus(capturedReady) : capturedStatus;
                EditorMetadata observation;
                try {
                    if (controlMode) {
                        var queries = editor.workspaceQueries(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE),
                            java.util.List.of("**/README.md"), java.util.List.of("**/*.env", "**/*.env.*"), true));
                        var proposals = new WorkspaceProposalService(queries, editor.workspaceDocuments(),
                            sessions.resolve("agent-workspace").resolve(queries.workspaceId()), "editor-control");
                        observation = EditorMetadata.control(application, approval, navigationHost, interaction, queries, proposals);
                    } else observation = navigationMode ? EditorMetadata.navigation(application, approval, navigationHost, interaction)
                        : new EditorMetadata(application, approval, observationHost);
                }
                catch (RuntimeException failed) { if (interaction != null) interaction.close(); throw failed; }
                try { server = WorkspaceBridgeServer.metadata(application, observation, directory, excludedRoots); }
                catch (IOException | RuntimeException failed) { observation.close(); throw failed; }
            }
            else {
                var workspace = editor.workspaceQueries(root, policy);
                var proposals = new WorkspaceProposalService(workspace, editor.workspaceDocuments(),
                    sessions.resolve("agent-workspace").resolve(workspace.workspaceId()), "workspace-api");
                server = new WorkspaceBridgeServer(workspace, proposals, directory);
            }
            owned.add(server);
            var owner = new java.lang.ref.WeakReference<>(this);
            var ownedServers = owned; var releasingServers = releasing;
            server.onClosed(() -> {
                ownedServers.remove(server); releasingServers.remove(server);
                var live = owner.get();
                if (live != null && !live.disposed.get()) SwingUtilities.invokeLater(() -> {
                    var controller = owner.get();
                    if (controller != null && !controller.disposed.get() && controller.active == server) {
                        controller.editor.takeBackAgentControl(); controller.active = null; controller.status = "Agent API attachment has stopped or expired.";
                    }
                });
            });
            token.onCancel(() -> release(server));
            if (disposed.get() || generation.get() != request || token.isCancelled()) { release(server); return null; }
            return server;
        }, (snapshot, server, error) -> {
            if (disposed.get() || generation.get() != request) { if (server != null) release(server); if (interaction != null) interaction.close(); return; }
            startingJob = -1;
            if (error != null || snapshot.getStatus() != AsyncJobService.Status.SUCCEEDED || server == null || !server.running()) {
                if (server != null) release(server);
                if (interaction != null) interaction.close();
                status = "Agent API could not start. Check local diagnostics and owner-only session directory permissions.";
            } else {
                active = server;
                if (statusOnly || metadataMode) {
                    applicationStatus = server.statusApplication();
                    if (applicationReady) applicationStatus.ready();
                }
                status = statusOnly || metadataMode ? mode + " attachment. Descriptor: " + server.descriptor() + ". Credential file: " + server.credential()
                    : mode + " agent API for " + root + ". Connection descriptor: " + server.descriptor();
            }
            editor.showMessage(editor.agentApprovalRequired()?"Agent attachment ready; control requires your approval.":status);
        });
        return status;
    }

    private static EditorControl.Host navigationHost(java.lang.ref.WeakReference<Texteditor> editorOwner, boolean controlMode,boolean terminalMode) {
        String terminalOwner=java.util.UUID.randomUUID().toString();
        return new EditorControl.Host() {
            private Texteditor owner() {
                var live = editorOwner.get();
                if (live == null) throw EditorMetadata.failure(WorkspaceApiException.Code.APP_SHUTTING_DOWN);
                return live;
            }
            public EditorMetadata.Context capture() { return owner().captureEditorMetadata(); }
            public EditorNavigation.State state() { return owner().captureEditorNavigation(); }
            public EditorNavigation.State openingState(){return owner().captureEditorOpening();}
            public EditorNavigation.State prepareOpening(){
                var app=owner();
                app.toolWindowHost.retireAgentPresentation();
                if(app.isTreePaneActive()){
                    var source=app.resolveTreeContentPaneForOpen();app.activateEditorPane(source);
                }
                return openingState();
            }
            public EditorNavigation.Prepared prepare(EditorNavigation.Command command) { return owner().prepareEditorNavigation(command); }
            public EditorNavigation.Applied apply(EditorNavigation.Prepared prepared, java.util.function.BooleanSupplier authorized) {
                var result = owner().applyEditorNavigation(prepared, authorized);
                if (controlMode) owner().getActivePane().agentPresentation().showPending(prepared.command().start(), prepared.command().end());
                return result;
            }
            public ProjectFileOpen.Request open(ProjectBoundFileAcquisition.RootPin root, String path, EditorNavigation.State expected,
                    java.util.function.BooleanSupplier current, java.util.function.Consumer<ProjectDocumentInstall.Result> completed) {
                return owner().openAgentProjectFile(root, path, expected, current, completed);
            }
            public void activity(int start, int end) {
                var presentation = owner().getActivePane().agentPresentation();
                if (!presentation.available()) throw EditorMetadata.failure(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
                presentation.showPending(start, end);
            }
            public void reading(int start,int end,String agent) {
                var pane=owner().getActivePane();var presentation=pane.agentPresentation();
                if(!presentation.available())throw EditorMetadata.failure(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
                try{
                    var bounds=pane.getTextArea().modelToView2D(start);
                    if(bounds==null)throw EditorMetadata.failure(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
                    presentation.showReading(start,end,agent);
                    var reveal=bounds.getBounds();var viewport=pane.getScrollPane().getViewport();
                    reveal.y=Math.max(0,reveal.y-viewport.getExtentSize().height/3);reveal.height=viewport.getExtentSize().height;
                    pane.getTextArea().scrollRectToVisible(reveal);
                    owner().showMessage(agent+" · reading "+pane.getBuffer().getDisplayName());
                }catch(javax.swing.text.BadLocationException unavailable){throw EditorMetadata.failure(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);}
            }
            public void intent(AgentBufferIntent intent){
                var app=owner();var presentation=app.getActivePane().agentPresentation();
                if(!presentation.available())throw EditorMetadata.failure(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
                app.toolWindowHost.retireAgentPresentation();presentation.showIntent(intent);
                app.showMessage(intent.label(intent.focus()));
            }
            public void progress(AgentActivityState.State state, int start, int end) {
                if (state.terminal()) owner().clearAgentActivity();
                else {
                    var presentation=owner().getActivePane().agentPresentation();
                    if (!presentation.available()) throw EditorMetadata.failure(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
                    if(state.phase().equals("reading"))reading(start,end,state.agent());
                    else presentation.showProgress(start,end,state.agent(),state.phase());
                }
                owner().showMessage(state.label());
            }
            public java.util.Map<String,Object> terminal(String action,String id,String agent,String body,java.util.function.BooleanSupplier authorized){
                if(!terminalMode)throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED,"Start :agent api start terminal to request reviewed terminal commands");
                return owner().agentTerminalRequest(terminalOwner,action,id,agent,body,authorized);
            }
            public void revokeTerminals(){var live=editorOwner.get();if(live!=null)live.revokeAgentTerminalRequests(terminalOwner);}
            public EditorControl.WatchRoot watchRoot(){return new EditorControl.WatchRoot(owner().workspaceController.activeRoot(),owner().workspaceRoots.generation());}
            public java.util.Map<String,Object> watch(String action,String agent){var app=owner();if(app.agentOwnsEditor()&&java.util.Set.of("start","stop").contains(action))throw EditorMetadata.failure(WorkspaceApiException.Code.PERMISSION_DENIED);return app.toolWindowHost.watchAgentChanges(action,agent);}
            public java.util.Map<String,Object> observe(String operation){return EditorObservation.read(owner(),operation);}
            public java.awt.image.BufferedImage capturePreview(){return EditorObservation.capture(owner());}
            public void review(String proposal) { owner().toolWindowHost.showAgentProposal(proposal); }
            public java.util.Map<String,Object> delegation(String action,String id,String agent,String task){return owner().agentDelegationCommand(action,id,agent,task);}
            public boolean delegationAllowed(String id){var app=editorOwner.get();return app!=null&&app.agentDelegationAllowed(id);}
            public void clearActivity() { var live = editorOwner.get(); if (live != null) live.clearAgentActivity(); }
        };
    }

    private String stop() {
        editor.takeBackAgentControl();
        stopping = true;
        generation.incrementAndGet();
        if (startingJob >= 0) editor.asyncJobService.cancel(startingJob);
        startingJob = -1; active = null;
        if (editor.agentNavigationInteraction != null) { editor.agentNavigationInteraction.close(); editor.agentNavigationInteraction = null; }
        for (WorkspaceBridgeServer server : owned) release(server);
        status = owned.isEmpty() ? "Agent API is stopped; no listener is running."
            : "Agent API stopping; existing connections are being revoked.";
        return status;
    }

    private void release(WorkspaceBridgeServer server) {
        server.revokeObservation();
        if (!releasing.add(server)) return;
        Thread.ofVirtual().name("shed-workspace-stop").start(() -> {
            try { server.close(); }
            catch (IOException cleanup) {
                System.getLogger("shed.workspace").log(System.Logger.Level.WARNING,
                    "Workspace connection stopped; its private descriptor could not be removed.");
            } finally {
                owned.remove(server); releasing.remove(server);
                if (!disposed.get()) SwingUtilities.invokeLater(() -> {
                    if (stopping && owned.isEmpty() && startingJob < 0)
                        status = "Agent API is stopped; no listener is running.";
                });
            }
        });
    }

    @Override public void close() {
        if (applicationStatus != null) applicationStatus.stopping();
        disposed.set(true); sessions.close(); stop();
    }
}
