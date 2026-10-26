package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;

/** Explicit human controls. Streaming never opens a window, selects a tab, or changes focus. */
final class AgentSessionPanel implements ToolWindowHost.ToolSurface, AutoCloseable {
    record Scope(Path root, WorkspaceDocumentSource documents, Path sessions) {
        Scope(Path root, WorkspaceDocumentSource documents) { this(root, documents, null); }
    }
    private record Connected(AgentSessionService service, String error) { }
    private final AsyncJobService jobs;
    private final Supplier<Scope> scopes;
    private final Supplier<List<String>> providers;
    private final Function<String, AgentProvider> provider;
    private final Consumer<AgentSessionService> owner;
    private final Supplier<Font> uiFont, codeFont;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final JComboBox<String> providerChoice = named(new JComboBox<>(), "provider");
    private final JComboBox<AgentAction> actionChoice = named(new JComboBox<>(AgentAction.values()), "action");
    private final JLabel selectionLabel = named(new JLabel("Ask about the project and jump to relevant code."), "selection");
    private final JButton captureSelection = button("Use current selection", "captureSelection", this::captureSelection);
    private final DefaultListModel<AgentAnswer.Location> locations = new DefaultListModel<>();
    private final JList<AgentAnswer.Location> locationList = named(new JList<>(locations), "locations");
    private final JPanel referencesPanel = new JPanel(new BorderLayout(6, 4));
    private final JButton openLocation = button("Open location", "openLocation", this::openLocation);
    private Supplier<Supplier<AgentSelection>> selectionSource;
    private Supplier<AgentSelection> capturedSelection;
    private Consumer<AgentAnswer.Location> navigation = ignored -> { };
    private WorkspaceDocumentSource boundDocuments;
    private String resultProposal = "";
    private int actionJob = -1;
    private record ActionResult(String proposal, AgentAnswer answer, String summary) { }
    private final JTextField resume = named(new JTextField(), "resume");
    private final JCheckBox foreground = named(new JCheckBox("Allow sign-in windows"), "foreground");
    private final JCheckBox reviewWrites = named(new JCheckBox("Allow proposed changes", true), "reviewWrites");
    private final JTextArea transcript = area("transcript", false), prompt = area("prompt", true), diagnostics = area("diagnostics", false), permissionDetails = area("permissionDetails", false);
    private final JScrollPane conversation = new JScrollPane(transcript);
    private final JLabel status = named(new JLabel("Choose a configured agent to start a conversation."), "status");
    private final JLabel workspace = named(new JLabel("No agent connected"), "workspace");
    private final JTabbedPane views = named(new JTabbedPane(), "views");
    private final DefaultListModel<AgentSessionService.Permission> requests = new DefaultListModel<>();
    private final JList<AgentSessionService.Permission> requestList = named(new JList<>(requests), "requests");
    private final JComboBox<Map<String, Object>> options = named(new JComboBox<>(), "options"), authMethods = named(new JComboBox<>(), "authMethods");
    private final JButton connect = button("Connect", "connect", this::connect), stop = button("Stop agent", "stop", this::stop);
    private final JButton send = button("Send", "send", this::send), cancel = button("Cancel turn", "cancel", this::cancel);
    private final JButton respond = button("Respond", "respond", this::respond), authenticate = button("Sign in", "authenticate", this::authenticate);
    private final JButton begin = button("Start conversation", "begin", this::begin);
    private Consumer<String> reviewAction = ignored -> { };
    private final JButton review = button("Review changes", "review", this::reviewChanges);
    private final Timer refreshTimer;
    private AgentSessionService active;
    private boolean busy, submitting, stopping, disposed;
    private int startingJob = -1;
    private long generation, lastSequence, droppedShown;
    private String notice = "", speaker = "";
    private Path boundRoot;
    private Path boundSessions;
    private PerfService performance = new PerfService();

    AgentSessionPanel(Texteditor editor) {
        this(editor.asyncJobService, () -> {
            Path root = editor.workspaceController.activeRoot();
            return root == null ? null : new Scope(root, editor.workspaceDocuments(), Path.of(editor.configManager.getSessionDirectory()));
        }, editor.configManager::getAgentProviderNames, name -> AgentProvider.configured(editor.configManager, name),
            editor::ownAgentSession, editor::resolveUiFont, editor::resolveEditorFont);
        selectionSource = editor::captureAgentSelection;
        performance = editor.perfService;
        navigation = location -> editor.showMessage(editor.openFileAtAsync(location.path().toFile(), location.line(), location.column(), "Agent reference"));
    }

    AgentSessionPanel(AsyncJobService jobs, Supplier<Scope> scopes, Supplier<List<String>> providers,
                      Function<String, AgentProvider> provider, Consumer<AgentSessionService> owner,
                      Supplier<Font> uiFont, Supplier<Font> codeFont) {
        this.jobs = jobs; this.scopes = scopes; this.providers = providers; this.provider = provider; this.owner = owner;
        this.uiFont = uiFont; this.codeFont = codeFont;
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10)); providerChoice.setEditable(true);
        providerChoice.setRenderer(plainRenderer()); options.setRenderer(plainRenderer()); authMethods.setRenderer(plainRenderer());
        status.putClientProperty("html.disable", true); workspace.putClientProperty("html.disable", true);
        JPanel top = new JPanel(new BorderLayout(8, 5));
        JPanel connection = row(new JLabel("Agent"), providerChoice, connect, stop);
        top.add(scrollRow(connection), BorderLayout.NORTH); top.add(workspace, BorderLayout.CENTER); panel.add(top, BorderLayout.NORTH);
        JPanel activity = new JPanel(new BorderLayout(6, 6)); activity.add(conversation, BorderLayout.CENTER);
        locationList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.putClientProperty("html.disable", true);
                if (value instanceof AgentAnswer.Location location) {
                    String path = boundRoot != null && location.path().startsWith(boundRoot) ? boundRoot.relativize(location.path()).toString() : location.path().toString();
                    label.setText(path + ":" + location.line() + ":" + location.column() + " — " + location.note());
                    label.setToolTipText(location.path().toString());
                }
                return label;
            }
        }); locationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        locationList.addListSelectionListener(event -> openLocation.setEnabled(locationList.getSelectedValue() != null));
        locationList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) { if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) openLocation(); }
        });
        locationList.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
        locationList.getActionMap().put("open", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent event) { openLocation(); } });
        JPanel references = referencesPanel;
        var referencesScroll = new JScrollPane(locationList); referencesScroll.setPreferredSize(new Dimension(200, WorkbenchStyle.px(100)));
        references.add(referencesScroll, BorderLayout.CENTER); references.add(openLocation, BorderLayout.EAST); openLocation.setEnabled(false);
        references.setVisible(false); activity.add(references, BorderLayout.SOUTH); views.addTab("Activity", activity);
        requestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); requestList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.putClientProperty("html.disable", true);
                if (value instanceof AgentSessionService.Permission permission) label.setText(Objects.toString(permission.tool().get("title"), "Requested action"));
                return label;
            }
        });
        requestList.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) { showPermission(); controls(active == null ? null : active.snapshot()); } });
        JPanel permission = new JPanel(new BorderLayout(6, 6));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(requestList), new JScrollPane(permissionDetails)); split.setResizeWeight(.3);
        permission.add(split, BorderLayout.CENTER); permission.add(scrollRow(row(options, respond)), BorderLayout.SOUTH);
        views.addTab("Requests", permission);
        ConnectionDetails setup = new ConnectionDetails();
        setup.addRow(help("OpenCode: install and sign in with its CLI, then choose opencode and Connect.\n"
            + "User settings: agent.opencode.command, .args and optional .model.\n"
            + "The preset reads/searches and returns proposals for review. Actions do not run shell commands.\n"
            + "Connect starts the local program in this workspace. It retains its operating-system access; Shed does not sandbox it."));
        setup.addRow(new JLabel("Resume session ID (optional)")); setup.addRow(resume);
        setup.addRow(reviewWrites); setup.addRow(foreground);
        reviewWrites.setToolTipText("Enable Edit selection and Run task. Proposed changes need your approval in Review changes. Disable before connecting to use Ask/search only.");
        foreground.setToolTipText("Enable before connecting to allow explicit sign-in requests. Stop and reconnect to change this permission.");
        setup.addRow(new JLabel("Authentication")); setup.addRow(authMethods);
        setup.addRow(scrollRow(row(authenticate, begin)));
        setup.addRow(new JLabel("Connection diagnostics")); diagnostics.setRows(5);
        setup.addRow(new JScrollPane(diagnostics));
        var details = named(new JScrollPane(setup, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), "connectionDetails");
        details.setBorder(BorderFactory.createEmptyBorder());
        views.addTab("Connection", details); panel.add(views, BorderLayout.CENTER);
        prompt.setRows(3); transcript.setLineWrap(true); transcript.setWrapStyleWord(true); prompt.setLineWrap(true); prompt.setWrapStyleWord(true);
        JPanel compose = new JPanel(new BorderLayout(6, 6)); compose.add(new JScrollPane(prompt), BorderLayout.CENTER);
        JPanel intent = new JPanel(new BorderLayout(6, 4)); intent.add(scrollRow(row(actionChoice, captureSelection)), BorderLayout.NORTH);
        intent.add(selectionLabel, BorderLayout.SOUTH); compose.add(intent, BorderLayout.NORTH);
        selectionLabel.putClientProperty("html.disable", true); actionChoice.setRenderer(plainRenderer());
        actionChoice.addActionListener(event -> updateAction()); updateAction();
        JPanel actions = new JPanel(new BorderLayout(6, 4)); actions.add(scrollRow(row(send, cancel, review)), BorderLayout.NORTH);
        actions.add(status, BorderLayout.SOUTH); compose.add(actions, BorderLayout.SOUTH); panel.add(compose, BorderLayout.SOUTH);
        prompt.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK), "send");
        prompt.getActionMap().put("send", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent event) { send(); } });
        AccessibilitySupport.describe(providerChoice, "Agent provider", "Choose a configured local agent. Nothing starts until Connect is pressed.");
        AccessibilitySupport.describe(resume, "Resume session ID", "Optional provider session to load when connecting, if supported.");
        AccessibilitySupport.describe(prompt, "Message to agent", "Enter a message. Control Enter sends it; Enter adds a new line.");
        AccessibilitySupport.describe(transcript, "Agent conversation", "Streamed conversation. Incoming activity does not move keyboard focus.");
        AccessibilitySupport.describe(requestList, "Pending agent requests", "Select an action to inspect its details and respond.");
        AccessibilitySupport.describe(options, "Permission response", "Choose an option, then press Respond. Selection alone grants nothing.");
        AccessibilitySupport.describe(authMethods, "Authentication method", "Select a sign-in method advertised by the connected agent.");
        refreshTimer = new Timer(250, event -> refresh());
        panel.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (panel.isShowing() && !disposed) refreshTimer.start(); else refreshTimer.stop();
            }
        });
        refreshAppearance(); refresh();
    }

    @Override public JPanel component() { return panel; }
    Path boundRoot() { return boundRoot; }
    Path boundSessions() { return boundSessions; }
    void onReview(Consumer<String> action) { reviewAction = Objects.requireNonNull(action); }
    void selectionSource(Supplier<Supplier<AgentSelection>> source) { selectionSource = source; }
    void onNavigate(Consumer<AgentAnswer.Location> action) { navigation = action; }
    void performance(PerfService value) { performance = Objects.requireNonNull(value); }
    void prepare(AgentAction action) {
        if (submitting || busy) { message("Wait for the current action or cancel it first."); return; }
        actionChoice.setSelectedItem(action);
        if (action == AgentAction.EDIT) captureSelection();
    }
    private void updateAction() {
        var action = (AgentAction) actionChoice.getSelectedItem();
        captureSelection.setVisible(action == AgentAction.EDIT);
        send.setText(action == AgentAction.EDIT ? "Propose edit" : action == AgentAction.ASK ? "Ask" : "Run task");
        selectionLabel.setText(action == AgentAction.EDIT ? capturedSelection == null ? "Select code, then use the current selection." : "Selection captured. Describe the change below."
            : action == AgentAction.ASK ? "Ask about the project and jump to relevant code." : "Describe changes to make across the project. Review before applying.");
        refreshAppearance();
    }
    private void captureSelection() {
        capturedSelection = null;
        if (selectionSource == null) { updateAction(); message("Select code in an editor buffer first."); return; }
        try { capturedSelection = selectionSource.get(); updateAction(); }
        catch (RuntimeException invalid) { updateAction(); message(failure(invalid)); }
    }
    private void reviewChanges() {
        if (!resultProposal.isEmpty()) { reviewAction.accept(resultProposal); return; }
        if (active != null) active.snapshot().writes().stream().filter(write -> !write.proposal().isEmpty()).findFirst()
            .ifPresent(write -> reviewAction.accept(write.proposal()));
    }

    private void connect() {
        if (busy || stopping || disposed || active != null && !inactive(active.snapshot().state())) return;
        Scope scope = scopes.get(); if (scope == null) { message("Select a workspace folder before connecting an agent."); return; }
        AgentProvider selected;
        try { selected = provider.apply(Objects.toString(providerChoice.getEditor().getItem(), "").trim()); }
        catch (RuntimeException invalid) { message(invalid.getMessage()); return; }
        boolean allowForeground = foreground.isSelected(); String resumeId = resume.getText().trim();
        var capabilities = new java.util.HashSet<>(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.START_AGENT));
        boolean allowWrites = reviewWrites.isSelected() && scope.sessions() != null;
        if (allowWrites) capabilities.add(AgentPolicy.Capability.PROPOSE);
        if (allowForeground) capabilities.add(AgentPolicy.Capability.OPEN_UI);
        // Context exports must not bypass OpenCode's environment-file read restrictions.
        var excluded = selected.id().equals("opencode") ? List.of("**/*.env", "**/*.env.*") : List.<String>of();
        var policy = new AgentPolicy(capabilities, List.of("**/README.md"), excluded, !allowForeground);
        long request = ++generation; busy = true; notice = "Connecting…"; active = null;
        transcript.setText(""); diagnostics.setText(""); locations.clear(); referencesPanel.setVisible(false); resultProposal = "";
        lastSequence = droppedShown = 0; speaker = ""; boundRoot = scope.root(); boundSessions = scope.sessions(); boundDocuments = scope.documents();
        workspace.setText("Workspace: " + scope.root()); controls(null);
        startingJob = jobs.submitLongRunning("Connect agent " + selected.id(), token -> {
            var queries = new WorkspaceQueryService(scope.root(), policy, scope.documents());
            var session = new AgentSessionService(selected, queries,
                scope.documents() instanceof WorkspaceDocumentEditor documents ? documents : null,
                allowWrites ? scope.sessions().resolve("agent-workspace").resolve(queries.workspaceId()) : null);
            try {
                owner.accept(session); token.onCancel(() -> background(session::close));
                session.initialize().get();
                if (token.isCancelled()) { session.close(); return null; }
                openSession(session, resumeId).get(); return new Connected(session, "");
            } catch (java.util.concurrent.ExecutionException | AcpException failed) { return new Connected(session, failure(failed)); }
            catch (Exception failed) { session.close(); throw failed; }
        }, (job, connected, error) -> {
            if (request != generation || disposed) { if (connected != null) background(connected.service()::close); return; }
            startingJob = -1; busy = false;
            if (connected == null) { notice = error == null ? "Connection cancelled." : failure(error); }
            else { active = connected.service(); notice = connected.error(); boundRoot = active.root(); workspace.setText("Workspace: " + active.root()); }
            refresh();
        });
    }

    private static CompletableFuture<Map<String, Object>> openSession(AgentSessionService session, String id) {
        return id.isBlank() ? session.newSession() : session.loadSession(id);
    }
    private void begin() {
        String id = resume.getText().trim(); operation("Start agent conversation", false, session -> openSession(session, id));
    }
    private void authenticate() {
        var method = selected(authMethods); if (method == null) return;
        String id = resume.getText().trim();
        operation("Sign in to agent", false, session -> session.authenticate((String) method.get("id")).thenCompose(result -> openSession(session, id)));
    }
    private void send() {
        if (active == null || busy || submitting || active.snapshot().state() != AgentSessionService.State.IDLE) return;
        String text = prompt.getText(); if (text.isBlank()) { message("Enter a message first."); return; }
        AgentAction action = (AgentAction) actionChoice.getSelectedItem();
        sendFocused(action, text);
    }
    private void cancel() {
        if (actionJob >= 0) { jobs.cancel(actionJob); notice = "Cancelling action…"; refresh(); return; }
        if (active == null || active.snapshot().state() != AgentSessionService.State.PROMPTING) return;
        AgentSessionService session = active; long request = generation;
        jobs.submitLongRunning("Cancel agent turn", token -> { session.cancel(); return null; }, (job, ignored, error) -> {
            if (generation == request && active == session) { notice = error == null ? "" : failure(error); refresh(); }
        });
    }

    private void sendFocused(AgentAction action, String instruction) {
        Supplier<AgentSelection> selection = capturedSelection;
        if (action == AgentAction.EDIT && selection == null) { message("Select code, then press Use current selection."); return; }
        if (action != AgentAction.ASK && (boundSessions == null || !active.policy().capabilities().contains(AgentPolicy.Capability.PROPOSE))) {
            message("Connect with reviewed changes enabled before proposing changes."); return;
        }
        long request = generation; AgentSessionService session = active;
        Path root = boundRoot, storage = boundSessions; WorkspaceDocumentSource documents = boundDocuments;
        submitting = true; notice = "Preparing " + action.toString().toLowerCase(java.util.Locale.ROOT) + "…";
        locations.clear(); resultProposal = ""; referencesPanel.setVisible(false); controls(session.snapshot());
        var proposalOwner = new java.util.concurrent.atomic.AtomicReference<WorkspaceProposalService>();
        var proposalId = new java.util.concurrent.atomic.AtomicReference<String>("");
        var unusableReply = new java.util.concurrent.atomic.AtomicReference<String>("");
        long started = System.nanoTime(); String metric = "ai." + action.name().toLowerCase(java.util.Locale.ROOT);
        actionJob = jobs.submitLongRunning(action.toString(), token -> {
            var capturedDocuments = List.copyOf(documents.snapshots());
            var queries = new WorkspaceQueryService(root, session.policy(), () -> capturedDocuments);
            try (var context = session.context(queries.visibleDocuments(), token::isCancelled)) {
                AgentSelection target = action == AgentAction.EDIT ? selection.get() : null;
                AgentTaskPlan task = action == AgentAction.RUN ? AgentTaskPlan.capture(queries, token::isCancelled) : null;
                if (target != null) {
                    queries.checkWorkspace(target.document().workspaceId());
                    queries.policy().require(AgentPolicy.Capability.PROPOSE);
                    if (target.document().uri() != null) queries.policy().checkPath(queries.root().relativize(
                        WorkspacePaths.resolve(queries.root(), target.document().uri())), AgentPolicy.Capability.PROPOSE);
                }
                if (action != AgentAction.ASK) proposalOwner.set(new WorkspaceProposalService(queries, documents instanceof WorkspaceDocumentEditor editor ? editor : null,
                    storage.resolve("agent-workspace").resolve(queries.workspaceId()), "focused:" + java.util.UUID.randomUUID()));
                String wire = task != null ? task.prompt(instruction) : target == null ? AgentAnswer.prompt(instruction, queries, token::isCancelled) : target.prompt(instruction);
                if (context != null) wire += context.instruction();
                String originalWire = wire;
                performance.recordDuration(metric + ".prepare", started, "context and baseline");
                for (int attempt = 0; ; attempt++) {
                    if (token.isCancelled()) throw new java.util.concurrent.CancellationException();
                    long providerStarted = System.nanoTime();
                    var pending = session.actionPrompt(attempt == 0 ? instruction : "Repair response format", wire, false);
                    token.onCancel(() -> background(() -> {
                        session.cancelAction(pending);
                        withdraw(proposalOwner.get(), proposalId.get());
                    }));
                    if (attempt == 0) SwingUtilities.invokeLater(() -> {
                        if (generation != request || active != session) return;
                        notice = "";
                        if (context != null && context.count() > 0) append("\nContext\nShared complete snapshots of " + context.count() + " unsaved buffers for this action.\n");
                        if (prompt.getText().equals(instruction)) prompt.setText("");
                        if (target != null && capturedSelection == selection) { selectionLabel.setText(target.label()); selectionLabel.setToolTipText(target.label()); }
                        refresh();
                    });
                    AgentSessionService.Reply reply;
                    try { reply = pending.get(); }
                    finally { performance.recordDuration(metric + ".provider", providerStarted, "attempt=" + (attempt + 1)); }
                    if (token.isCancelled() || reply.stopReason().equals("cancelled")) throw new java.util.concurrent.CancellationException();
                    if (!reply.stopReason().equals("end_turn")) throw new IllegalArgumentException("Agent stopped without a complete result: " + reply.stopReason());
                    try {
                        if (action == AgentAction.ASK) return new ActionResult("", AgentAnswer.parse(reply.text(), queries), "");
                        String id, summary;
                        if (task != null) {
                            var result = task.propose(proposalOwner.get(), java.util.UUID.randomUUID().toString(), reply.turnId(), reply.text());
                            id = result.proposal(); summary = result.summary();
                        } else {
                            var proposal = target.propose(proposalOwner.get(), java.util.UUID.randomUUID().toString(), reply.turnId(), reply.text());
                            id = (String) proposal.get("id"); summary = "Selection change proposed.";
                        }
                        proposalId.set(id);
                        if (token.isCancelled()) { withdraw(proposalOwner.get(), id); throw new java.util.concurrent.CancellationException(); }
                        return new ActionResult(id, null, summary);
                    } catch (AgentReplyFormatException malformed) {
                        int end = GraphemeBoundary.floor(reply.text(), Math.min(2048, reply.text().length()));
                        unusableReply.set(reply.text().substring(0, end) + (end < reply.text().length() ? "\n[Reply shortened]" : ""));
                        if (attempt >= 1) throw malformed;
                        wire = malformed.correction(action, originalWire);
                        SwingUtilities.invokeLater(() -> {
                            if (generation != request || active != session || disposed) return;
                            notice = "Correcting the agent response format once…";
                            append("\nResponse correction\nThe reply did not match the required format. Asking the agent to correct it; no changes were applied.\n");
                            refresh();
                        });
                    }
                }
            }
        }, (job, result, error) -> {
            performance.recordDuration(metric + ".total", started, job.getStatus().name().toLowerCase(java.util.Locale.ROOT));
            if (generation != request || active != session || disposed) { if (result != null) background(() -> withdraw(proposalOwner.get(), result.proposal())); return; }
            actionJob = -1; submitting = false;
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
                background(() -> withdraw(proposalOwner.get(), proposalId.get())); notice = "Action cancelled.";
                if (prompt.getText().isBlank()) prompt.setText(instruction);
            } else if (error != null) {
                if (prompt.getText().isBlank()) prompt.setText(instruction);
                Throwable cause = rootFailure(error);
                boolean stale = cause instanceof WorkspaceApiException workspaceError && workspaceError.code() == WorkspaceApiException.Code.STALE_VERSION;
                if (stale && action == AgentAction.EDIT) { capturedSelection = null; updateAction(); }
                notice = failure(error) + (stale && action == AgentAction.EDIT
                    ? ". Use current selection to capture the changed code, then try again."
                    : stale ? ". Retry to capture the current files."
                    : ". Your request is available to adjust and retry; check Connection diagnostics for provider errors.");
                append("\nAction not completed\n" + notice + "\n");
                if (!unusableReply.get().isBlank()) append("\nUnusable reply (not applied)\n" + unusableReply.get() + "\n");
            }
            else if (result != null && !result.proposal().isEmpty()) {
                resultProposal = result.proposal(); notice = "Proposed changes are ready in Review changes.";
                append("\nProposal\n" + result.summary() + "\n[Review changes before applying.]\n");
            } else if (result != null && result.answer() != null) {
                var answer = result.answer(); append("\nAnswer\n" + answer.text() + "\n");
                answer.locations().forEach(locations::addElement);
                referencesPanel.setVisible(!locations.isEmpty());
                notice = answer.omittedLocations() > 0 ? answer.omittedLocations() + " unavailable references omitted."
                    : locations.isEmpty() ? "Answer complete · no code locations returned." : "Answer complete · choose a code location below.";
            } else if (result != null) {
                append("\nTask result\n" + result.summary() + "\n"); notice = "Task complete · no changes proposed.";
            }
            refresh();
        });
        controls(session.snapshot());
    }

    private static void withdraw(WorkspaceProposalService proposals, String id) {
        if (proposals == null || id == null || id.isEmpty()) return;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try { proposals.withdraw(id); return; }
            catch (WorkspaceApiException unavailable) {
                if (Boolean.TRUE.equals(unavailable.details().get("retryable")) && System.nanoTime() < deadline) {
                    try { Thread.sleep(50); continue; }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                System.getLogger("shed.agent").log(System.Logger.Level.WARNING, "Cancelled proposal could not be withdrawn; inspect Review changes: " + id); return;
            }
        }
    }

    private void openLocation() {
        AgentAnswer.Location location = locationList.getSelectedValue(); if (location == null || active == null) return;
        Path root = boundRoot; WorkspaceDocumentSource documents = boundDocuments; AgentPolicy policy = active.policy(); long request = generation;
        jobs.submit("Check agent location", token -> {
            AgentAnswer.validateNavigation(location, new WorkspaceQueryService(root, policy, documents)); return location;
        }, (job, checked, error) -> {
            if (request != generation || disposed) return;
            if (error != null) message(failure(error)); else if (checked != null) navigation.accept(checked);
        });
    }
    private void respond() {
        var permission = requestList.getSelectedValue(); var option = selected(options); if (permission == null || option == null) return;
        AgentSessionService session = active; long request = generation;
        jobs.submitLongRunning("Respond to agent request", token -> { session.decide(permission.id(), (String) option.get("optionId")); return null; }, (job, ignored, error) -> {
            if (generation == request && active == session) { notice = error == null ? "" : failure(error); refresh(); }
        });
    }
    private void operation(String title, boolean turn, Function<AgentSessionService, CompletableFuture<Map<String, Object>>> action) {
        if (active == null || busy || disposed || stopping) return;
        AgentSessionService session = active; long request = generation;
        if (turn) submitting = true; else busy = true; notice = ""; controls(session.snapshot());
        jobs.submitLongRunning(title, token -> {
            token.onCancel(() -> background(() -> { if (turn) session.cancel(); else session.close(); }));
            return action.apply(session).get();
        }, (job, result, error) -> {
            if (request != generation || active != session || disposed) return;
            if (turn) submitting = false; else busy = false;
            notice = error == null ? "" : failure(error); refresh();
        });
    }
    private void stop() {
        ++generation;
        if (actionJob >= 0) jobs.cancel(actionJob);
        actionJob = -1;
        if (startingJob >= 0) jobs.cancel(startingJob);
        startingJob = -1; busy = submitting = false;
        AgentSessionService session = active;
        if (session == null) { notice = "Agent startup cancelled."; refresh(); return; }
        stopping = true; notice = "Stopping agent…"; controls(session.snapshot()); long request = generation;
        Thread.ofVirtual().name("shed-agent-panel-stop").start(() -> {
            session.close(); session.termination().whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                if (request == generation && !disposed) { stopping = false; notice = "Agent stopped."; refresh(); }
            }));
        });
    }

    @Override public void refresh() {
        if (disposed) return;
        if (active == null && !busy) {
            String selected = Objects.toString(providerChoice.getEditor().getItem(), "");
            List<String> names = providers.get();
            if (!java.util.stream.IntStream.range(0, providerChoice.getItemCount()).mapToObj(providerChoice::getItemAt).toList().equals(names)) {
                providerChoice.removeAllItems(); names.forEach(providerChoice::addItem);
                if (!selected.isBlank()) providerChoice.setSelectedItem(selected);
            }
        }
        var snapshot = active == null ? null : active.snapshot();
        if (snapshot != null) {
            if (snapshot.droppedEvents() > droppedShown) { append("\n[Earlier activity was discarded from this bounded session view.]\n"); droppedShown = snapshot.droppedEvents(); }
            for (var event : snapshot.events()) if (event.sequence() > lastSequence) { render(event); lastSequence = event.sequence(); }
            String selectedId = requestList.getSelectedValue() == null ? null : requestList.getSelectedValue().id();
            if (!java.util.stream.IntStream.range(0, requests.size()).mapToObj(index -> requests.get(index).id()).toList()
                .equals(snapshot.permissions().stream().map(AgentSessionService.Permission::id).toList())) {
                requests.clear(); snapshot.permissions().forEach(requests::addElement);
                for (int i = 0; i < requests.size(); i++) if (requests.get(i).id().equals(selectedId)) requestList.setSelectedIndex(i);
                if (requestList.getSelectedIndex() < 0 && !requests.isEmpty()) requestList.setSelectedIndex(0);
                showPermission();
            }
            views.setTitleAt(1, snapshot.permissions().isEmpty() ? "Requests" : "Requests (" + snapshot.permissions().size() + ")");
            var oldAuth = java.util.stream.IntStream.range(0, authMethods.getItemCount()).mapToObj(authMethods::getItemAt).toList();
            if (!oldAuth.equals(snapshot.authMethods())) { authMethods.removeAllItems(); snapshot.authMethods().forEach(authMethods::addItem); }
            if (views.getSelectedIndex() == 2) {
                String detail = "Session: " + Objects.toString(snapshot.sessionId(), "not created") + "\n"
                    + "State: " + snapshot.state() + "\n" + "Capabilities: " + snapshot.capabilities() + "\n\n"
                    + snapshot.diagnostics().failure() + "\n" + snapshot.diagnostics().stderr()
                    + (snapshot.diagnostics().truncated() ? "\n[Earlier diagnostics discarded]" : "");
                if (!diagnostics.getText().equals(detail)) diagnostics.setText(detail);
            }
        }
        controls(snapshot);
    }

    private void render(AgentSessionService.Event event) {
        var data = event.data();
        switch (event.type()) {
            case "user_prompt" -> { append("\nYou\n" + data.get("text") + "\n"); speaker = "you"; }
            case "session_update" -> {
                var update = MiniJson.asObject(data.get("update"));
                if (update == null) return;
                if ("agent_message_chunk".equals(update.get("sessionUpdate")) || "user_message_chunk".equals(update.get("sessionUpdate"))) {
                    if (Boolean.TRUE.equals(data.get("focusedAction")) && "agent_message_chunk".equals(update.get("sessionUpdate"))) return;
                    var content = MiniJson.asObject(update.get("content"));
                    String nextSpeaker = "user_message_chunk".equals(update.get("sessionUpdate")) ? "you" : "agent";
                    if (!speaker.equals(nextSpeaker)) append(nextSpeaker.equals("you") ? "\nYou\n" : "\nAgent\n");
                    speaker = nextSpeaker;
                    if (content != null && "text".equals(content.get("type"))) append(Objects.toString(content.get("text"), ""));
                    else append("\n[This content cannot be displayed as text.]\n");
                } else if ("tool_call".equals(update.get("sessionUpdate")) || "tool_call_update".equals(update.get("sessionUpdate")))
                    append("\n[" + Objects.toString(update.get("title"), Objects.toString(update.get("toolCallId"), "Action"))
                        + " · " + Objects.toString(update.get("status"), "pending") + "]\n");
            }
            case "turn_finished" -> append("\n[" + switch (Objects.toString(data.get("stopReason"))) {
                case "end_turn" -> "Turn complete"; case "cancelled" -> "Cancelled"; case "refusal" -> "Agent declined";
                case "max_tokens" -> "Token limit reached"; case "max_turn_requests" -> "Turn limit reached"; default -> "Turn ended";
            } + "]\n");
            case "cancel_requested" -> append("\n[Cancellation requested]\n");
            case "request_failed" -> append("\n[" + data.get("message") + "]\n");
            case "write_proposed" -> append("\n[File change awaiting review: " + data.get("path") + "]\n");
            case "write_applied" -> append("\n[Reviewed change applied" + (Boolean.TRUE.equals(data.get("pendingSave")) ? " to the unsaved buffer" : "") + ": " + data.get("path") + "]\n");
            case "write_failed", "write_cleanup_required" -> append("\n[" + data.get("message") + "]\n");
            default -> { }
        }
    }
    private void append(String text) {
        var scroll = conversation.getVerticalScrollBar();
        boolean follow = transcript.getSelectionStart() == transcript.getSelectionEnd()
            && (conversation.getViewport().getHeight() == 0
                || scroll.getValue() + scroll.getVisibleAmount() >= scroll.getMaximum() - 2);
        var caret = (javax.swing.text.DefaultCaret) transcript.getCaret(); caret.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        transcript.append(text.length() > 65536 ? text.substring(0, GraphemeBoundary.floor(text, 65536)) + "\n[Message shortened in this view]\n" : text);
        if (transcript.getDocument().getLength() > 131072) {
            String retained = transcript.getText();
            transcript.replaceRange("[Earlier conversation shortened in this view]\n", 0, GraphemeBoundary.ceiling(retained, retained.length() - 98304));
        }
        if (follow) transcript.setCaretPosition(transcript.getDocument().getLength());
    }
    private void showPermission() {
        var permission = requestList.getSelectedValue(); options.removeAllItems(); permissionDetails.setText("");
        if (permission != null) {
            var tool = permission.tool();
            String detail = Objects.toString(tool.get("title"), "Requested action") + "\nAction: " + Objects.toString(tool.get("kind"), "unspecified");
            var locations = MiniJson.asArray(tool.get("locations"));
            if (locations != null) for (Object value : locations) {
                var location = MiniJson.asObject(value);
                if (location != null) detail += "\nFile: " + Objects.toString(location.get("path"), "unspecified")
                    + (location.get("line") == null ? "" : " · line " + location.get("line"));
            }
            if (tool.get("rawInput") != null) detail += "\n\nRequested input\n" + MiniJson.stringify(tool.get("rawInput"));
            permissionDetails.setText(detail.length() > 65536 ? detail.substring(0, GraphemeBoundary.floor(detail, 65536)) + "\n[Details shortened]" : detail); permissionDetails.setCaretPosition(0);
            permission.options().forEach(options::addItem);
            for (var option : permission.options()) if ("reject_once".equals(option.get("kind"))) { options.setSelectedItem(option); break; }
        }
    }
    private void controls(AgentSessionService.Snapshot snapshot) {
        var state = snapshot == null ? null : snapshot.state(); boolean disconnected = snapshot == null || inactive(state);
        connect.setEnabled(!busy && !stopping && disconnected); providerChoice.setEnabled(connect.isEnabled()); foreground.setEnabled(connect.isEnabled());
        Scope available = scopes.get(); reviewWrites.setEnabled(connect.isEnabled() && available != null && available.sessions() != null);
        review.setEnabled(!resultProposal.isEmpty() || snapshot != null && snapshot.writes().stream().anyMatch(write -> !write.proposal().isEmpty()));
        actionChoice.setEnabled(!busy && !submitting && !stopping); captureSelection.setEnabled(actionChoice.isEnabled());
        stop.setEnabled(!stopping && (busy || !disconnected));
        send.setEnabled(!busy && !submitting && !stopping && state == AgentSessionService.State.IDLE && snapshot.writes().isEmpty());
        cancel.setEnabled(!stopping && (actionJob >= 0 || state == AgentSessionService.State.PROMPTING));
        respond.setEnabled(!stopping && state == AgentSessionService.State.PROMPTING && requestList.getSelectedValue() != null);
        resume.setEnabled(!busy && (disconnected || state == AgentSessionService.State.READY));
        begin.setEnabled(!busy && state == AgentSessionService.State.READY);
        authenticate.setEnabled(begin.isEnabled() && authMethods.getItemCount() > 0 && active.policy().capabilities().contains(AgentPolicy.Capability.OPEN_UI) && !active.policy().backgroundOnly());
        authenticate.setToolTipText(authenticate.isEnabled() ? "Start the selected sign-in method. This may open a sign-in window."
            : "Sign-in requires a connected agent and Allow sign-in windows enabled before connecting.");
        String text = !notice.isBlank() ? notice : snapshot == null ? "Choose a configured agent to start a conversation."
            : !snapshot.error().isBlank() ? snapshot.error() : switch (state) {
                case IDLE -> snapshot.writes().isEmpty() ? "Ready · " + snapshot.provider() : "Finishing previous file reviews…";
                case PROMPTING -> !snapshot.writes().isEmpty() ? "Agent is waiting for file changes in Review changes."
                    : snapshot.permissions().isEmpty() ? "Agent is working…" : "Agent is waiting for your response in Requests.";
                case CANCELLING -> "Waiting for cancellation acknowledgement…";
                case READY -> "Agent connected. Start a conversation or choose a sign-in method in Connection.";
                default -> "Agent " + state.name().toLowerCase(java.util.Locale.ROOT);
            };
        status.setText(text); status.setToolTipText(text);
    }
    private void message(String value) { notice = Objects.toString(value, "Agent operation failed"); controls(active == null ? null : active.snapshot()); }
    private static boolean inactive(AgentSessionService.State state) { return state == AgentSessionService.State.CLOSED || state == AgentSessionService.State.FAILED; }
    private static Throwable rootFailure(Throwable error) {
        while (error.getCause() != null && (error instanceof java.util.concurrent.ExecutionException || error instanceof java.util.concurrent.CompletionException)) error = error.getCause();
        return error;
    }
    private static String failure(Throwable error) {
        error = rootFailure(error);
        return error instanceof AcpException || error instanceof WorkspaceApiException || error instanceof IllegalArgumentException
            ? Objects.toString(error.getMessage(), "Agent operation failed") : "Agent operation failed. Check the configured command and Connection diagnostics.";
    }
    private static void background(Runnable action) { Thread.ofVirtual().name("shed-agent-panel-cancel").start(action); }
    @Override public void close() { disposed = true; refreshTimer.stop(); stop(); }
    @Override public void refreshAppearance() {
        fonts(panel, uiFont.get()); transcript.setFont(codeFont.get()); prompt.setFont(codeFont.get()); diagnostics.setFont(codeFont.get());
        for (JButton button : List.of(connect, stop, send, cancel, respond, authenticate, begin, review, captureSelection, openLocation)) {
            WorkbenchStyle.quiet(button); button.setPreferredSize(null); var size = button.getPreferredSize();
            button.setPreferredSize(new Dimension(size.width, Math.max(WorkbenchStyle.px(40), size.height)));
        }
        providerChoice.setPreferredSize(new Dimension(WorkbenchStyle.px(170), WorkbenchStyle.px(40)));
        resume.setPreferredSize(new Dimension(WorkbenchStyle.px(240), WorkbenchStyle.px(40))); requestList.setFixedCellHeight(WorkbenchStyle.px(40));
        actionChoice.setPreferredSize(new Dimension(WorkbenchStyle.px(160), WorkbenchStyle.px(40))); locationList.setFixedCellHeight(WorkbenchStyle.px(40));
    }
    private static JPanel row(java.awt.Component... components) { JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)); for (var item : components) row.add(item); return row; }
    private static JTextArea help(String text) {
        JTextArea help = area("connectionHelp", false); help.setText(text); help.setLineWrap(true); help.setWrapStyleWord(true);
        help.setOpaque(false); help.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4)); return help;
    }
    /** Track dock width while keeping every setup control reachable in a short tool window. */
    private static final class ConnectionDetails extends JPanel implements Scrollable {
        private int nextRow;
        ConnectionDetails() { super(new java.awt.GridBagLayout()); }
        void addRow(JComponent component) {
            var constraints = new java.awt.GridBagConstraints(); constraints.gridx = 0; constraints.gridy = nextRow++;
            constraints.weightx = 1; constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
            constraints.anchor = java.awt.GridBagConstraints.NORTHWEST; constraints.insets = new java.awt.Insets(3, 4, 3, 4);
            add(component, constraints);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) { return WorkbenchStyle.px(24); }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) { return Math.max(WorkbenchStyle.px(24), visible.height - WorkbenchStyle.px(24)); }
    }
    private static JScrollPane scrollRow(JPanel row) { var scroll = new JScrollPane(row, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED); scroll.setBorder(BorderFactory.createEmptyBorder()); return scroll; }
    private static JTextArea area(String name, boolean editable) { JTextArea area = named(new JTextArea(), name); area.setEditable(editable); return area; }
    private static JButton button(String title, String name, Runnable action) { JButton button = named(new JButton(title), name); button.addActionListener(event -> action.run()); return button; }
    private static <T extends JComponent> T named(T component, String name) { component.setName("agent.session." + name); return component; }
    private static DefaultListCellRenderer plainRenderer() {
        return new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus); label.putClientProperty("html.disable", true);
                if (value instanceof Map<?, ?> option) label.setText(Objects.toString(option.get("name"), Objects.toString(option.get("id"), "Option"))); return label;
            }
        };
    }
    private static Map<String, Object> selected(JComboBox<Map<String, Object>> combo) { return MiniJson.asObject(combo.getSelectedItem()); }
    private static void fonts(java.awt.Container parent, Font font) { parent.setFont(font); for (var child : parent.getComponents()) { child.setFont(font); if (child instanceof java.awt.Container container) fonts(container, font); } }
}
