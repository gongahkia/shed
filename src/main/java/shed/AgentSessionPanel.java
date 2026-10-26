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
    record Scope(Path root, WorkspaceDocumentSource documents) { }
    private record Connected(AgentSessionService service, String error) { }
    private final AsyncJobService jobs;
    private final Supplier<Scope> scopes;
    private final Supplier<List<String>> providers;
    private final Function<String, AgentProvider> provider;
    private final Consumer<AgentSessionService> owner;
    private final Supplier<Font> uiFont, codeFont;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final JComboBox<String> providerChoice = named(new JComboBox<>(), "provider");
    private final JTextField resume = named(new JTextField(), "resume");
    private final JCheckBox foreground = named(new JCheckBox("Allow sign-in windows"), "foreground");
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
    private final Timer refreshTimer;
    private AgentSessionService active;
    private boolean busy, submitting, stopping, disposed;
    private int startingJob = -1;
    private long generation, lastSequence, droppedShown;
    private String notice = "", speaker = "";
    private Path boundRoot;

    AgentSessionPanel(Texteditor editor) {
        this(editor.asyncJobService, () -> {
            Path root = editor.workspaceController.activeRoot();
            return root == null ? null : new Scope(root, editor.workspaceDocuments());
        }, editor.configManager::getAgentProviderNames, name -> AgentProvider.configured(editor.configManager, name),
            editor::ownAgentSession, editor::resolveUiFont, editor::resolveEditorFont);
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
        views.addTab("Conversation", conversation);
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
        JPanel setup = new JPanel(); setup.setLayout(new BoxLayout(setup, BoxLayout.Y_AXIS));
        setup.add(new JLabel("Configure agent.<name>.command and optional .args in your settings."));
        setup.add(new JLabel("Connect starts that local program in the workspace shown above."));
        setup.add(new JLabel("The program retains its operating-system access; Shed does not sandbox it."));
        setup.add(row(new JLabel("Resume session ID (optional)"), resume)); setup.add(foreground);
        foreground.setToolTipText("Enable before connecting to allow explicit sign-in requests. Stop and reconnect to change this permission.");
        setup.add(row(new JLabel("Authentication"), authMethods, authenticate, begin));
        setup.add(new JLabel("Shed currently serves file reads. Reviewed writes and terminal requests are not connected yet."));
        JPanel details = new JPanel(new BorderLayout(8, 8)); details.add(setup, BorderLayout.NORTH); details.add(new JScrollPane(diagnostics), BorderLayout.CENTER);
        views.addTab("Connection", details); panel.add(views, BorderLayout.CENTER);
        prompt.setRows(3); transcript.setLineWrap(true); transcript.setWrapStyleWord(true); prompt.setLineWrap(true); prompt.setWrapStyleWord(true);
        JPanel compose = new JPanel(new BorderLayout(6, 6)); compose.add(new JScrollPane(prompt), BorderLayout.CENTER);
        compose.add(scrollRow(row(send, cancel)), BorderLayout.EAST); compose.add(status, BorderLayout.SOUTH); panel.add(compose, BorderLayout.SOUTH);
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

    private void connect() {
        if (busy || stopping || disposed || active != null && !inactive(active.snapshot().state())) return;
        Scope scope = scopes.get(); if (scope == null) { message("Select a workspace folder before connecting an agent."); return; }
        AgentProvider selected;
        try { selected = provider.apply(Objects.toString(providerChoice.getEditor().getItem(), "").trim()); }
        catch (RuntimeException invalid) { message(invalid.getMessage()); return; }
        boolean allowForeground = foreground.isSelected(); String resumeId = resume.getText().trim();
        var capabilities = new java.util.HashSet<>(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.START_AGENT));
        if (allowForeground) capabilities.add(AgentPolicy.Capability.OPEN_UI);
        var policy = new AgentPolicy(capabilities, List.of("**/README.md"), List.of(), !allowForeground);
        long request = ++generation; busy = true; notice = "Connecting…"; active = null;
        transcript.setText(""); diagnostics.setText(""); lastSequence = droppedShown = 0; speaker = ""; boundRoot = scope.root();
        workspace.setText("Workspace: " + scope.root()); controls(null);
        startingJob = jobs.submitLongRunning("Connect agent " + selected.id(), token -> {
            var session = new AgentSessionService(selected, new WorkspaceQueryService(scope.root(), policy, scope.documents()));
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
        if (text.length() > 65536) { message("This message is too long; shorten it to 65,536 characters or fewer."); return; }
        long request = generation; AgentSessionService session = active;
        operation("Agent prompt", true, current -> {
            var reply = current.prompt(text);
            SwingUtilities.invokeLater(() -> { if (generation == request && active == session && prompt.getText().equals(text)) prompt.setText(""); });
            return reply;
        });
    }
    private void cancel() {
        if (active == null || active.snapshot().state() != AgentSessionService.State.PROMPTING) return;
        AgentSessionService session = active; long request = generation;
        jobs.submitLongRunning("Cancel agent turn", token -> { session.cancel(); return null; }, (job, ignored, error) -> {
            if (generation == request && active == session) { notice = error == null ? "" : failure(error); refresh(); }
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
        stop.setEnabled(!stopping && (busy || !disconnected));
        send.setEnabled(!busy && !submitting && !stopping && state == AgentSessionService.State.IDLE);
        cancel.setEnabled(!stopping && state == AgentSessionService.State.PROMPTING);
        respond.setEnabled(!stopping && state == AgentSessionService.State.PROMPTING && requestList.getSelectedValue() != null);
        resume.setEnabled(!busy && (disconnected || state == AgentSessionService.State.READY));
        begin.setEnabled(!busy && state == AgentSessionService.State.READY);
        authenticate.setEnabled(begin.isEnabled() && authMethods.getItemCount() > 0 && active.policy().capabilities().contains(AgentPolicy.Capability.OPEN_UI) && !active.policy().backgroundOnly());
        authenticate.setToolTipText(authenticate.isEnabled() ? "Start the selected sign-in method. This may open a sign-in window."
            : "Sign-in requires a connected agent and Allow sign-in windows enabled before connecting.");
        String text = !notice.isBlank() ? notice : snapshot == null ? "Choose a configured agent to start a conversation."
            : !snapshot.error().isBlank() ? snapshot.error() : switch (state) {
                case IDLE -> "Ready · " + snapshot.provider();
                case PROMPTING -> snapshot.permissions().isEmpty() ? "Agent is working…" : "Agent is waiting for your response in Requests.";
                case CANCELLING -> "Waiting for cancellation acknowledgement…";
                case READY -> "Agent connected. Start a conversation or choose a sign-in method in Connection.";
                default -> "Agent " + state.name().toLowerCase(java.util.Locale.ROOT);
            };
        status.setText(text); status.setToolTipText(text);
    }
    private void message(String value) { notice = Objects.toString(value, "Agent operation failed"); controls(active == null ? null : active.snapshot()); }
    private static boolean inactive(AgentSessionService.State state) { return state == AgentSessionService.State.CLOSED || state == AgentSessionService.State.FAILED; }
    private static String failure(Throwable error) {
        while (error.getCause() != null && (error instanceof java.util.concurrent.ExecutionException || error instanceof java.util.concurrent.CompletionException)) error = error.getCause();
        return error instanceof AcpException || error instanceof WorkspaceApiException || error instanceof IllegalArgumentException
            ? Objects.toString(error.getMessage(), "Agent operation failed") : "Agent operation failed. Check the configured command and Connection diagnostics.";
    }
    private static void background(Runnable action) { Thread.ofVirtual().name("shed-agent-panel-cancel").start(action); }
    @Override public void close() { disposed = true; refreshTimer.stop(); stop(); }
    @Override public void refreshAppearance() {
        fonts(panel, uiFont.get()); transcript.setFont(codeFont.get()); prompt.setFont(codeFont.get()); diagnostics.setFont(codeFont.get());
        for (JButton button : List.of(connect, stop, send, cancel, respond, authenticate, begin)) {
            WorkbenchStyle.quiet(button); button.setPreferredSize(null); var size = button.getPreferredSize();
            button.setPreferredSize(new Dimension(size.width, Math.max(WorkbenchStyle.px(40), size.height)));
        }
        providerChoice.setPreferredSize(new Dimension(WorkbenchStyle.px(170), WorkbenchStyle.px(40)));
        resume.setPreferredSize(new Dimension(WorkbenchStyle.px(240), WorkbenchStyle.px(40))); requestList.setFixedCellHeight(WorkbenchStyle.px(40));
    }
    private static JPanel row(java.awt.Component... components) { JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)); for (var item : components) row.add(item); return row; }
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
