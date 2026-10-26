package shed;

import java.awt.BorderLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;

/** Independent conversations share reviewed workspace transactions, never an active prompt or mutable scope. */
final class AgentWorkbenchPanel implements ToolWindowHost.ToolSurface, AutoCloseable {
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new JTabbedPane(), conversations = new JTabbedPane();
    private final List<AgentSessionPanel> sessions = new ArrayList<>();
    private final AgentReviewPanel review;
    private final Supplier<AgentSessionPanel> factory;
    private final Supplier<Font> uiFont;
    private final Runnable dismissCompletion;
    private final JButton add = new JButton("New conversation"), remove = new JButton("Close conversation"), stopAll = new JButton("Stop all");
    private AgentSessionPanel reviewOwner;
    private int nextConversation;
    private boolean closed;

    AgentWorkbenchPanel(Texteditor editor) {
        this(() -> new AgentSessionPanel(editor), owner -> {
            var root = owner == null || owner.boundRoot() == null ? editor.workspaceController.activeRoot() : owner.boundRoot();
            var storage = owner == null || owner.boundSessions() == null ? java.nio.file.Path.of(editor.configManager.getSessionDirectory()) : owner.boundSessions();
            return root == null ? null : new AgentReviewPanel.Scope(root, storage, editor.workspaceDocuments());
        }, editor.asyncJobService, editor::resolveUiFont, editor::resolveEditorFont, editor::stopAiCompletionRequests);
    }

    AgentWorkbenchPanel(Supplier<AgentSessionPanel> factory, Function<AgentSessionPanel, AgentReviewPanel.Scope> reviewScope,
                        AsyncJobService jobs, Supplier<Font> uiFont, Supplier<Font> codeFont, Runnable dismissCompletion) {
        this.factory = factory; this.uiFont = uiFont; this.dismissCompletion = dismissCompletion;
        review = new AgentReviewPanel(jobs, () -> reviewScope.apply(reviewOwner == null ? current() : reviewOwner), uiFont, codeFont);
        conversations.setName("agent.conversations"); conversations.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        add.setName("agent.newConversation"); remove.setName("agent.closeConversation"); stopAll.setName("agent.stopAll");
        add.setToolTipText("Create an independent conversation. Connect explicitly to start its OpenCode process.");
        remove.setToolTipText("Close this conversation and cancel its running action. Other conversations continue.");
        stopAll.setToolTipText("Stop all Assistant connections, command requests and pending inline suggestions.");
        add.addActionListener(e -> newConversation()); remove.addActionListener(e -> closeConversation()); stopAll.addActionListener(e -> stopAll());
        var actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 6, 0)); actions.add(add); actions.add(remove); actions.add(stopAll);
        var scroll = new JScrollPane(actions, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED); scroll.setBorder(BorderFactory.createEmptyBorder());
        var assistant = new JPanel(new BorderLayout(4, 4)); assistant.add(scroll, BorderLayout.NORTH); assistant.add(conversations, BorderLayout.CENTER);
        tabs.addTab("Assistant", assistant); tabs.addTab("Review changes", review.component());
        tabs.addChangeListener(e -> refresh()); conversations.addChangeListener(e -> refresh());
        panel.add(tabs, BorderLayout.CENTER); newConversation(); refreshAppearance();
    }
    private AgentSessionPanel current() {
        int index = conversations.getSelectedIndex(); return index < 0 || index >= sessions.size() ? null : sessions.get(index);
    }
    private void newConversation() {
        if (closed || sessions.size() >= 8) return;
        AgentSessionPanel session = factory.get(); sessions.add(session);
        session.onReview(id -> { reviewOwner = session; review.showProposal(id); tabs.setSelectedIndex(1); });
        String title = "Conversation " + ++nextConversation;
        conversations.addTab(title, session.component());
        session.onActivity(working -> {
            int index = sessions.indexOf(session); if (closed || index < 0 || index >= conversations.getTabCount()) return;
            String caption = title + (working ? " · Working" : "");
            if (!caption.equals(conversations.getTitleAt(index))) conversations.setTitleAt(index, caption);
        });
        conversations.setSelectedComponent(session.component()); tabs.setSelectedIndex(0);
        add.setEnabled(sessions.size() < 8); refresh();
    }
    private void closeConversation() {
        if (closed) return;
        int index = conversations.getSelectedIndex(); if (index < 0) return;
        AgentSessionPanel session = sessions.remove(index); session.close(); conversations.removeTabAt(index);
        if (sessions.isEmpty()) newConversation();
        add.setEnabled(sessions.size() < 8); refresh();
    }
    private void stopAll() {
        if (closed) return;
        sessions.forEach(AgentSessionPanel::stopRequests); dismissCompletion.run();
    }
    @Override public JPanel component() { return panel; }
    void showReview() { reviewOwner = current(); tabs.setSelectedIndex(1); review.refresh(); }
    void showAction(AgentAction action) { var current = current(); if (current != null) current.prepare(action); tabs.setSelectedIndex(0); }
    @Override public void refresh() {
        if (closed) return;
        if (tabs.getSelectedIndex() == 0) { var current = current(); if (current != null) current.refresh(); }
        else review.refresh();
    }
    @Override public void refreshAppearance() {
        tabs.setFont(uiFont.get()); conversations.setFont(uiFont.get());
        for (JButton button : List.of(add, remove, stopAll)) { button.setFont(uiFont.get()); WorkbenchStyle.quiet(button); button.setPreferredSize(null);
            var size = button.getPreferredSize(); button.setPreferredSize(new java.awt.Dimension(size.width, Math.max(size.height, WorkbenchStyle.px(40)))); }
        sessions.forEach(AgentSessionPanel::refreshAppearance); review.refreshAppearance();
    }
    @Override public void close() { if (closed) return; closed = true; sessions.forEach(AgentSessionPanel::close); }
}
