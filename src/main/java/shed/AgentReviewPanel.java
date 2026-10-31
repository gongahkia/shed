package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Human review only. Incoming proposals never open this surface or take focus. */
final class AgentReviewPanel implements ToolWindowHost.ToolSurface {
    record Scope(Path root, Path sessions, WorkspaceDocumentEditor documents) {
        WorkspaceProposalService service() {
            var policy = new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY),
                List.of("**/README.md"), List.of(), true);
            var queries = new WorkspaceQueryService(root, policy, documents);
            return new WorkspaceProposalService(queries, documents, sessions.resolve("agent-workspace").resolve(queries.workspaceId()), "human");
        }
    }

    private final AsyncJobService jobs;
    private final Supplier<Scope> scopeSupplier;
    private final Supplier<Font> uiFont, codeFont;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<Map<String, Object>> proposals = new DefaultListModel<>();
    private final JList<Map<String, Object>> proposalList = named(new JList<>(proposals), "agent.proposals");
    private final JComboBox<String> documents = named(new JComboBox<>(), "agent.documents");
    private final JTextArea before = named(new JTextArea(), "agent.before");
    private final JTextArea proposed = named(new JTextArea(), "agent.proposed");
    private final JTextArea details = named(new JTextArea(), "agent.details");
    private final JTextArea recoveryDetails = named(new JTextArea(), "agent.recoveryDetails");
    private final JTextArea preservedDraft = named(new JTextArea(), "agent.preservedDraft");
    private final JLabel beforeTitle = new JLabel("Original snapshot");
    private final JLabel proposedTitle = new JLabel("Proposed text · editable before acceptance");
    private final JTabbedPane reviewTabs = new JTabbedPane();
    private final JLabel status = named(new JLabel("Select a workspace to review agent proposals."), "agent.status");
    private final DefaultTableModel hunks = new DefaultTableModel(new Object[]{"Use", "Change", "Range"}, 0) {
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0 && canEdit() && !textChanged(); }
    };
    private final JTable hunkTable = named(new JTable(hunks), "agent.hunks");
    private Consumer<AgentAnswer.Location> navigation;
    private final JButton openSource = button("Open source", "openSource", this::openSource);
    private final JButton refresh = button("Refresh", "refresh", () -> reload(false));
    private final JButton more = button("Load more", "more", () -> reload(true));
    private final JButton save = button("Save review", "save", this::saveReview);
    private final JButton discard = button("Discard local edits", "discard", this::discardLocal);
    private final JButton apply = button("Apply proposal", "apply", () -> mutate("Applying proposal…", WorkspaceProposalService::apply));
    private final JButton reject = button("Reject", "reject", () -> mutate("Rejecting proposal…", WorkspaceProposalService::reject));
    private final JButton undo = button("Undo proposal", "undo", () -> mutate("Undoing proposal…", WorkspaceProposalService::undo));
    private final JButton rebase = button("Rebase for review", "rebase", () -> mutate("Rebasing proposal…", WorkspaceProposalService::rebase));
    private final JButton inspect = button("Inspect recovery", "inspect", this::inspectRecovery);
    private final JButton reconcile = button("Record verified outcome", "reconcile", this::reconcile);
    private final JButton restore = button("Restore pre-operation", "restore", this::restore);
    private Scope scope;
    private WorkspaceProposalService.Preview preview;
    private Map<String, Object> recovery;
    private Integer nextOffset;
    private boolean busy, rendering, dirty;
    private String requestedProposal;
    private long generation;

    AgentReviewPanel(Texteditor editor) {
        this(editor.asyncJobService, () -> {
            Path root = editor.workspaceController.activeRoot();
            return root == null ? null : new Scope(root, Path.of(editor.configManager.getSessionDirectory()), editor.workspaceDocuments());
        }, editor::resolveUiFont, editor::resolveEditorFont);
        onNavigate(location -> editor.showMessage(editor.openFileAtAsync(location.path().toFile(), location.line(), location.column(), "Agent change")));
    }

    AgentReviewPanel(AsyncJobService jobs, Supplier<Scope> scopeSupplier, Supplier<Font> uiFont, Supplier<Font> codeFont) {
        this.jobs = jobs; this.scopeSupplier = scopeSupplier; this.uiFont = uiFont; this.codeFont = codeFont;
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        status.putClientProperty("html.disable", true);
        proposalList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        proposalList.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label = (JLabel) new DefaultListCellRenderer().getListCellRendererComponent(list, value, index, selected, focus);
            label.putClientProperty("html.disable", true);
            label.setText(value.get("status") + " · " + value.get("taskId") + " / " + value.get("turnId"));
            label.setToolTipText(value.get("actor") + " · " + value.get("id"));
            return label;
        });
        documents.setRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.putClientProperty("html.disable", true); return label;
            }
        });
        WorkbenchStyle.emptyText(proposalList, "No proposals. Connect an agent with file review enabled, or use :agent api start propose.");
        WorkbenchStyle.emptyText(hunkTable, "Select a proposal to review its changes");
        before.setEditable(false); details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setRows(4); details.setOpaque(false);
        JPanel left = new JPanel(new BorderLayout(4, 4)); left.add(new JScrollPane(proposalList));
        JPanel listActions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0)); listActions.add(refresh); listActions.add(more);
        left.add(listActions, BorderLayout.NORTH); left.setMinimumSize(new Dimension(180, 100));
        JPanel review = new JPanel(new BorderLayout(6, 6));
        JPanel heading = new JPanel(new BorderLayout(4, 4)); heading.add(details); heading.add(documents, BorderLayout.SOUTH);
        review.add(heading, BorderLayout.NORTH);
        JSplitPane texts = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, textPane(beforeTitle, before), textPane(proposedTitle, proposed));
        texts.setResizeWeight(0.5); texts.setDividerLocation(0.5);
        JSplitPane changes = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(hunkTable), texts);
        changes.setResizeWeight(0.18); changes.setDividerLocation(180);
        hunkTable.getColumnModel().getColumn(0).setMaxWidth(50);
        hunkTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        recoveryDetails.setEditable(false); recoveryDetails.setLineWrap(true); recoveryDetails.setWrapStyleWord(true);
        AccessibilitySupport.describe(recoveryDetails, "Recovery evidence", "Current contents compared with the recorded operation, and retained resource paths.");
        preservedDraft.setEditable(false);
        AccessibilitySupport.describe(preservedDraft, "Preserved editor draft", "The reviewed editor draft is preserved when a file is deleted or renamed.");
        reviewTabs.addTab("Changes", changes); reviewTabs.addTab("Recovery", new JScrollPane(recoveryDetails));
        reviewTabs.addTab("Preserved draft", new JScrollPane(preservedDraft)); reviewTabs.setEnabledAt(2, false);
        review.add(reviewTabs);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        for (JButton action : List.of(openSource, save, discard, rebase, reject, undo, apply, inspect, reconcile, restore)) actions.add(action);
        restore.setToolTipText("Restore the snapshots from before this interrupted apply or undo. Requires the inspected versions and intact recorded originals.");
        // A horizontal scroller keeps every action reachable in a narrow dock or at larger UI zoom.
        JScrollPane actionScroll = new JScrollPane(actions, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        review.add(actionScroll, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, review);
        split.setResizeWeight(0.24); split.setDividerLocation(260); review.setMinimumSize(new Dimension(240, 120));
        panel.add(split); panel.add(status, BorderLayout.SOUTH);
        proposalList.addListSelectionListener(event -> { if (!event.getValueIsAdjusting() && !rendering) selectProposal(); });
        documents.addActionListener(event -> { if (!rendering && documents.getSelectedIndex() >= 0) loadPreview(); });
        proposed.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { edited(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { edited(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { edited(); }
        });
        hunks.addTableModelListener(event -> { if (!rendering && event.getColumn() == 0) edited(); });
        hunkTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || rendering || preview == null || textChanged()) return;
            int row = hunkTable.getSelectedRow(); if (row < 0 || row >= preview.hunks().size()) return;
            var hunk = preview.hunks().get(row); before.select(hunk.start(), hunk.end());
            int shift = 0;
            for (int i = 0; i < row; i++) { var prior = preview.hunks().get(i); shift += prior.replacement().length() - (prior.end() - prior.start()); }
            proposed.select(hunk.start() + shift, hunk.start() + shift + hunk.replacement().length());
        });
        AccessibilitySupport.describe(proposalList, "Agent proposals", "Select a task and turn to inspect its proposed changes.");
        AccessibilitySupport.describe(documents, "Proposal document", "Choose which document to review. Apply accepts all saved selections in this proposal.");
        AccessibilitySupport.describe(before, "Original snapshot", "The document contents used to create this proposal.");
        AccessibilitySupport.describe(proposed, "Proposed text", "Edit the proposal, then save the review and inspect its updated hunks before applying.");
        AccessibilitySupport.describe(hunkTable, "Selected changes", "Toggle Use to include a change, then save the review.");
        refreshAppearance(); controls();
    }

    @Override public JPanel component() { return panel; }
    @Override public void refresh() {
        Scope current = scopeSupplier.get();
        if (!Objects.equals(current, scope)) {
            if (dirty) { message("Local review edits belong to the previous workspace. Save or discard them before switching review."); return; }
            if (busy) return;
            scope = current; clear();
        }
        if (!busy && !dirty) {
            if (requestedProposal != null) openRequestedProposal(); else reload(false);
        }
    }

    /** Explicit navigation can address a pending proposal beyond the currently loaded page. */
    void showProposal(String id) {
        requestedProposal = Objects.requireNonNull(id);
        if (dirty) { message("Save or discard the current review edits before opening the requested proposal."); return; }
        refresh();
    }

    private void openRequestedProposal() {
        if (scope == null) { message("Select a workspace to review the requested proposal."); return; }
        String id = requestedProposal; requestedProposal = null;
        run("Opening requested proposal…", service -> service.preview(id, 0), value -> {
            rendering = true;
            try {
                proposals.clear(); proposals.addElement(value.proposal()); nextOffset = null;
                proposalList.setSelectedIndex(0);
            } finally { rendering = false; }
            selectProposal(value);
        });
    }

    private void reload(boolean append) {
        if (busy || dirty) return;
        Scope current = scopeSupplier.get();
        if (!Objects.equals(current, scope)) { scope = current; clear(); }
        if (scope == null) { message("Select a workspace to review agent proposals."); controls(); return; }
        if (append && nextOffset == null) return;
        String selectedId = selectedId(); int offset = append ? nextOffset : 0;
        run("Loading proposals…", service -> service.page(offset, 50), page -> {
            rendering = true;
            try {
                if (!append) { proposals.clear(); documents.removeAllItems(); preview = null; }
                for (var proposal : page.proposals()) proposals.addElement(proposal);
                nextOffset = page.nextOffset();
                int selected = 0;
                for (int i = 0; i < proposals.size(); i++) if (Objects.equals(selectedId, proposals.get(i).get("id"))) selected = i;
                if (!proposals.isEmpty()) proposalList.setSelectedIndex(selected);
            } finally { rendering = false; }
            if (proposals.isEmpty()) { clearPreview(); message("No proposals yet. Connect an agent with file review enabled, or use :agent api start propose."); }
            else selectProposal();
        });
    }

    private void selectProposal() {
        selectProposal(null);
    }

    private void selectProposal(WorkspaceProposalService.Preview loaded) {
        if (busy || dirty) return;
        var selected = proposalList.getSelectedValue();
        rendering = true;
        try {
            documents.removeAllItems(); clearPreview();
            if (selected != null) for (Object item : MiniJson.asArray(selected.get("documents"))) {
                var document = MiniJson.asObject(item);
                String label = document.get("uri") == null ? "Unsaved · " + document.get("document")
                    : Objects.toString(document.get("path"), Objects.toString(document.get("uri")));
                documents.addItem("CREATE".equals(document.get("kind")) ? "Create · " + label
                    : "DELETE".equals(document.get("kind")) ? "Delete · " + label
                    : "RENAME".equals(document.get("kind")) ? "Rename · " + label + " → " + document.get("destinationPath") : label);
            }
        } finally { rendering = false; }
        if (selected != null) { if (loaded == null) loadPreview(); else showPreview(loaded); } else controls();
    }

    private void loadPreview() {
        if (busy || dirty || selectedId() == null || documents.getSelectedIndex() < 0) return;
        String id = selectedId(); int index = documents.getSelectedIndex();
        rendering = true;
        try { clearPreview(); } finally { rendering = false; }
        run("Loading review…", service -> service.preview(id, index), this::showPreview);
    }

    private void showPreview(WorkspaceProposalService.Preview value) {
        rendering = true;
        try {
            preview = value; dirty = false;
            boolean deletion = "DELETE".equals(value.document().get("kind")), rename = "RENAME".equals(value.document().get("kind"));
            beforeTitle.setText(deletion ? "Disk snapshot to delete" : rename ? "File at original path" : "Original snapshot");
            proposedTitle.setText(deletion ? "File absent after acceptance" : rename ? "Same contents at destination" : "Proposed text · editable before acceptance");
            AccessibilitySupport.describe(before, beforeTitle.getText(), deletion ? "The saved disk contents that this operation removes." : "The document contents used to create this proposal.");
            AccessibilitySupport.describe(proposed, proposedTitle.getText(), deletion || rename ? "This file operation is accepted or rejected as a whole." : "Edit the proposal, then save and review its hunks before applying.");
            WorkbenchStyle.emptyText(hunkTable, deletion ? "Whole-file deletion · no text hunks" : rename ? "File rename · contents preserved" : "No text hunks in this proposal");
            before.setText(value.before()); proposed.setText(value.proposed()); before.setCaretPosition(0); proposed.setCaretPosition(0);
            preservedDraft.setText(Objects.toString(value.preservedDraft(), "")); preservedDraft.setCaretPosition(0);
            reviewTabs.setEnabledAt(2, value.preservedDraft() != null);
            hunks.setRowCount(0);
            var selected = MiniJson.asArray(value.document().get("selectedHunks"));
            int position = 0, line = 1;
            for (int i = 0; i < value.hunks().size(); i++) {
                var hunk = value.hunks().get(i);
                for (; position < hunk.start(); position++) if (value.before().charAt(position) == '\n') line++;
                int first = line;
                for (; position < hunk.end(); position++) if (value.before().charAt(position) == '\n') line++;
                hunks.addRow(new Object[]{selected == null || selected.contains((long) i), "Change " + (i + 1), first == line ? "Line " + first : "Lines " + first + "–" + line});
            }
            var metadata = value.proposal();
            var format = deletion || rename ? MiniJson.asObject(value.document().get("diskFormat")) : value.document();
            details.setText("Workspace: " + scope.root() + "\n" + metadata.get("status") + " · " + metadata.get("files") + " document(s) · proposed by " + metadata.get("actor")
                + " · reviewed by " + Objects.toString(metadata.get("reviewedBy"), "nobody yet") + "\n"
                + metadata.get("taskId") + " / " + metadata.get("turnId") + " · revision " + metadata.get("revision") + "\n"
                + format.get("encoding") + " · " + format.get("lineEnding")
                + ("DELETE".equals(value.document().get("kind")) ? " · Deletes the disk file shown in Original snapshot. Open editor contents remain as an unsaved buffer."
                    : rename ? " · Rename to " + value.document().get("destination") + ". Open drafts follow the file without being saved."
                    : "CREATE".equals(value.document().get("kind")) ? " · Creates a new file on apply; an existing path is never overwritten."
                    : Boolean.TRUE.equals(metadata.get("pendingSaveAtApplication")) ? " · Applied to open buffers; saving is separate." : " · Open buffers stay unsaved; closed files are written on apply."));
            for (int i = 0; i < proposals.size(); i++) if (Objects.equals(metadata.get("id"), proposals.get(i).get("id"))) proposals.set(i, metadata);
            message(metadata.get("error") != null ? "Previous operation: " + metadata.get("error")
                : Set.of("APPLYING", "UNDOING", "RECOVERY_REQUIRED").contains(metadata.get("status"))
                    ? "An interrupted operation needs recovery. Its snapshots are retained; automatic application is disabled."
                    : metadata.get("recovery") != null ? (String) metadata.get("recovery")
                    : "CREATE".equals(value.document().get("kind")) && "PROPOSED".equals(metadata.get("status"))
                        ? "Apply creates this file even when all text hunks are deselected. Reject the proposal to skip creation."
                        : "DELETE".equals(value.document().get("kind")) ? "Deletion applies to the whole file. Undo restores its retained original without overwriting an occupied path."
                        : rename ? "Rename preserves file contents and open drafts. Undo moves the file back while preserving later edits; occupied paths require review."
                        : "Review each document. Apply accepts the saved hunk selections across the whole proposal.");
        } finally { rendering = false; }
        controls();
    }

    void onNavigate(Consumer<AgentAnswer.Location> listener) { navigation = listener; controls(); }
    private void openSource() {
        if (!openSource.isEnabled()) return;
        var captured = preview; Scope capturedScope = scope; int selectedHunk = hunkTable.getSelectedRow();
        run("Checking source location…", service -> sourceLocation(capturedScope, captured, selectedHunk), location -> {
            navigation.accept(location); message("Opened source at line " + location.line() + ".");
        });
    }
    static AgentAnswer.Location sourceLocation(Scope scope, WorkspaceProposalService.Preview preview, int selectedHunk) {
        boolean applied = "APPLIED".equals(preview.proposal().get("status"));
        String kind = Objects.toString(preview.document().get("kind"), "TEXT");
        String uri = Objects.toString(preview.document().get(applied && kind.equals("RENAME") ? "destination" : "uri"), "");
        if (uri.isBlank() || applied && kind.equals("DELETE") || !applied && kind.equals("CREATE"))
            throw WorkspaceApiException.invalidRequest("This proposal has no existing source file at this stage");
        var queries = new WorkspaceQueryService(scope.root(), AgentPolicy.readOnly(), scope.documents());
        var source = queries.snapshot(queries.workspaceId(), uri);
        String expected = applied ? preview.selected() : preview.before();
        if ((kind.equals("RENAME") || kind.equals("DELETE")) && preview.preservedDraft() != null) expected = preview.preservedDraft();
        if (!source.complete() || !source.content().equals(expected))
            throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION,
                "Source changed since this review; inspect the current file before jumping to a change");
        var changes = applied ? WorkspaceTextEdits.diff(preview.before(), preview.selected()) : preview.hunks();
        // After apply, the original hunk rows may include changes the reviewer excluded.
        // Navigate to the first actual applied change instead of mapping those rows incorrectly.
        int index = applied || selectedHunk < 0 ? 0 : Math.min(selectedHunk, changes.size() - 1);
        int offset = changes.isEmpty() ? 0 : changes.get(index).start();
        offset = Math.max(0, Math.min(offset, source.content().length()));
        int line = 1, lineStart = 0;
        for (int i = 0; i < offset; i++) if (source.content().charAt(i) == '\n') { line++; lineStart = i + 1; }
        return new AgentAnswer.Location(WorkspacePaths.resolve(queries.root(), uri), line, offset - lineStart + 1, "Reviewed change", source.version());
    }

    private void saveReview() {
        if (!canEdit() || !dirty) return;
        String id = selectedId(), replacement = textChanged() ? proposed.getText() : null;
        int revision = revision(), index = documents.getSelectedIndex();
        Set<Integer> selected = new java.util.HashSet<>();
        for (int i = 0; i < hunks.getRowCount(); i++) if (Boolean.TRUE.equals(hunks.getValueAt(i, 0))) selected.add(i);
        run("Saving review…", service -> service.revise(id, revision, index, replacement, replacement == null ? selected : null), ignored -> {
            dirty = false; loadPreview();
        });
    }

    private void discardLocal() {
        if (busy) return;
        dirty = false;
        if (!Objects.equals(scope, scopeSupplier.get())) { scope = scopeSupplier.get(); clear(); reload(false); }
        else if (preview != null) showPreview(preview);
        if (requestedProposal != null && !busy) refresh();
    }

    @FunctionalInterface private interface Mutation { Map<String, Object> apply(WorkspaceProposalService service, String id, int revision); }
    private void inspectRecovery() {
        if (busy || dirty || preview == null) return;
        String id = selectedId();
        run("Inspecting recovery…", service -> service.recovery(id), result -> {
            recovery = result;
            StringBuilder detail = new StringBuilder("Observed outcome: ").append(Objects.toString(result.get("verifiedOutcome"), "Unresolved — mixed, missing or changed contents"))
                .append("\nRecording a verified outcome updates history only.\nRestore pre-operation returns every document to its snapshot before the interrupted apply or undo. It requires intact evidence and refuses diverged contents.\n\n");
            for (Object item : MiniJson.asArray(result.get("documents"))) {
                var document = MiniJson.asObject(item);
                detail.append(Objects.toString(document.get("uri"), Objects.toString(document.get("document"))))
                    .append(document.get("destination") == null ? "" : " → " + document.get("destination"))
                    .append("\n  ").append(document.get("state"))
                    .append(document.get("restoreReason") == null ? "" : " · " + document.get("restoreReason")).append("\n");
            }
            detail.append("\nRecorded resource intents (a listed path may already have been cleaned up):\n");
            for (Object item : MiniJson.asArray(result.get("resourceSteps"))) {
                var step = MiniJson.asObject(item);
                detail.append(step.get("kind")).append(": ").append(step.get("source")).append(" → ").append(step.get("target")).append("\n");
            }
            recoveryDetails.setText(detail.toString()); recoveryDetails.setCaretPosition(0); reviewTabs.setSelectedIndex(1);
            message("Recovery evidence captured. Recording its outcome requires the same contents to still be present.");
        });
    }
    private void reconcile() {
        if (busy || dirty || preview == null || recovery == null) return;
        var inspected = MiniJson.asObject(recovery.get("proposal"));
        String id = (String) inspected.get("id"), fingerprint = (String) recovery.get("fingerprint");
        int revision = ((Number) inspected.get("revision")).intValue();
        run("Recording verified outcome…", service -> service.reconcile(id, revision, fingerprint), ignored -> loadPreview());
    }
    private void restore() {
        if (busy || dirty || preview == null || recovery == null) return;
        var inspected = MiniJson.asObject(recovery.get("proposal"));
        String id = (String) inspected.get("id"), fingerprint = (String) recovery.get("fingerprint");
        int revision = ((Number) inspected.get("revision")).intValue();
        run("Restoring pre-operation snapshots…", service -> service.restore(id, revision, fingerprint), ignored -> loadPreview());
    }
    private void mutate(String message, Mutation mutation) {
        if (busy || dirty || preview == null || !Objects.equals(scope, scopeSupplier.get())) return;
        String id = selectedId(); int revision = revision();
        run(message, service -> mutation.apply(service, id, revision), ignored -> loadPreview());
    }

    private <T> void run(String message, Function<WorkspaceProposalService, T> operation, Consumer<T> completion) {
        if (busy || scope == null) return;
        Scope captured = scope; long request = ++generation;
        busy = true; message(message); controls();
        jobs.submit("Agent review: " + message, token -> {
            var service = captured.service();
            for (int attempt = 0; ; attempt++) {
                if (token.isCancelled()) throw new java.util.concurrent.CancellationException();
                try { return operation.apply(service); }
                catch (WorkspaceApiException contention) {
                    if (!Boolean.TRUE.equals(contention.details().get("retryable")) || attempt >= 100) throw contention;
                    Thread.sleep(50);
                }
            }
        }, (snapshot, result, error) -> {
            if (request != generation) return;
            busy = false;
            if (!Objects.equals(captured, scopeSupplier.get()) && !dirty) { scope = scopeSupplier.get(); clear(); reload(false); return; }
            if (error != null) {
                message(error instanceof WorkspaceApiException failure ? failure.code() + ": " + failure.getMessage()
                    : "Review operation did not finish. Refresh to inspect its persisted outcome.");
            } else if (result != null) completion.accept(result);
            else message("Review operation was cancelled. Refresh to inspect its persisted outcome.");
            controls();
            // Continue navigation in this EDT callback so no editable frame is exposed
            // between the current preview and an already requested proposal.
            if (requestedProposal != null && !busy && !dirty) refresh();
        });
    }

    private void edited() {
        if (rendering || preview == null) return;
        dirty = true; message(textChanged() ? "Local text edits. Save review to rebuild and inspect the hunks before applying."
            : "Hunk selection changed. Save review before applying."); controls();
    }
    private boolean textChanged() { return preview != null && !proposed.getText().equals(preview.proposed()); }
    private boolean canEdit() { return !busy && preview != null && "PROPOSED".equals(preview.proposal().get("status"))
        && !"DELETE".equals(preview.document().get("kind")) && !"RENAME".equals(preview.document().get("kind")); }
    private int revision() { return ((Number) preview.proposal().get("revision")).intValue(); }
    private String selectedId() { var value = proposalList.getSelectedValue(); return value == null ? null : (String) value.get("id"); }
    private void controls() {
        boolean ready = !busy && !dirty && preview != null && Objects.equals(scope, scopeSupplier.get());
        String state = preview == null ? "" : (String) preview.proposal().get("status");
        refresh.setEnabled(!busy && !dirty); more.setEnabled(!busy && !dirty && nextOffset != null);
        proposalList.setEnabled(!busy && !dirty); documents.setEnabled(!busy && !dirty && documents.getItemCount() > 0);
        proposed.setEditable(canEdit()); hunkTable.setEnabled(canEdit() && !textChanged());
        save.setEnabled(canEdit() && dirty); discard.setEnabled(!busy && dirty);
        apply.setEnabled(ready && state.equals("PROPOSED")); reject.setEnabled(ready && state.equals("PROPOSED"));
        undo.setEnabled(ready && state.equals("APPLIED")); rebase.setEnabled(ready && Set.of("PROPOSED", "FAILED").contains(state));
        boolean interrupted = Set.of("APPLYING", "UNDOING", "RECOVERY_REQUIRED").contains(state);
        String kind = preview == null ? "" : Objects.toString(preview.document().get("kind"), "TEXT");
        openSource.setEnabled(ready && navigation != null && !interrupted
            && !(kind.equals("CREATE") && !state.equals("APPLIED")) && !(kind.equals("DELETE") && state.equals("APPLIED")));
        openSource.setToolTipText(state.equals("APPLIED") ? "Open the first applied change in this file, if its contents still match."
            : "Open the selected change in the source file, if its contents still match.");
        inspect.setEnabled(ready && interrupted);
        reconcile.setEnabled(ready && interrupted && recovery != null && recovery.get("verifiedOutcome") != null);
        restore.setEnabled(ready && interrupted && recovery != null && Boolean.TRUE.equals(recovery.get("restoreAvailable")));
        apply.setText(preview == null ? "Apply proposal" : "Apply proposal (" + preview.proposal().get("files") + ")");
        sizeButton(apply);
    }
    private void clear() { rendering = true; try { proposals.clear(); documents.removeAllItems(); nextOffset = null; clearPreview(); } finally { rendering = false; } }
    private void clearPreview() {
        preview = null; recovery = null; recoveryDetails.setText("Inspect an interrupted operation to compare its recorded and current contents.");
        reviewTabs.setSelectedIndex(0); reviewTabs.setEnabledAt(2, false); preservedDraft.setText("");
        before.setText(""); proposed.setText(""); details.setText(""); hunks.setRowCount(0);
    }
    private void message(String text) { status.setText(text); status.setToolTipText(text); status.getAccessibleContext().setAccessibleDescription(text); }
    private static JPanel textPane(JLabel label, JTextArea text) {
        JPanel pane = new JPanel(new BorderLayout(4, 4)); label.setLabelFor(text);
        pane.add(label, BorderLayout.NORTH); pane.add(new JScrollPane(text)); pane.setMinimumSize(new Dimension(80, 80)); return pane;
    }
    private static JButton button(String title, String name, Runnable action) {
        JButton button = named(new JButton(title), "agent." + name); button.addActionListener(event -> action.run()); return button;
    }
    private static <T extends JComponent> T named(T component, String name) { component.setName(name); return component; }

    @Override public void refreshAppearance() {
        applyFont(panel, uiFont.get()); before.setFont(codeFont.get()); proposed.setFont(codeFont.get()); preservedDraft.setFont(codeFont.get());
        proposalList.setFixedCellHeight(Math.max(WorkbenchStyle.px(40), uiFont.get().getSize() + WorkbenchStyle.px(18)));
        hunkTable.setRowHeight(proposalList.getFixedCellHeight());
        documents.setPreferredSize(new Dimension(240, Math.max(WorkbenchStyle.px(40), uiFont.get().getSize() + WorkbenchStyle.px(18))));
        for (JButton action : List.of(openSource, refresh, more, save, discard, apply, reject, undo, rebase, inspect, reconcile, restore)) sizeButton(action);
    }
    private static void sizeButton(JButton action) {
        action.setPreferredSize(null); Dimension preferred = action.getPreferredSize();
        action.setPreferredSize(new Dimension(preferred.width, Math.max(WorkbenchStyle.px(40), preferred.height)));
    }
    private static void applyFont(java.awt.Container parent, Font font) {
        parent.setFont(font); for (java.awt.Component child : parent.getComponents()) {
            child.setFont(font); if (child instanceof java.awt.Container nested) applyFont(nested, font);
        }
    }
}
