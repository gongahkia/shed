package shed;

import java.awt.BorderLayout;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** Conversation and human review share one dock without redirecting incoming activity. */
final class AgentWorkbenchPanel implements ToolWindowHost.ToolSurface {
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new JTabbedPane();
    private final AgentSessionPanel session;
    private final AgentReviewPanel review;
    private final java.util.function.Supplier<java.awt.Font> uiFont;

    AgentWorkbenchPanel(Texteditor editor) {
        uiFont = editor::resolveUiFont;
        session = new AgentSessionPanel(editor);
        review = new AgentReviewPanel(editor.asyncJobService, () -> {
            var root = session.boundRoot() == null ? editor.workspaceController.activeRoot() : session.boundRoot();
            return root == null ? null : new AgentReviewPanel.Scope(root, java.nio.file.Path.of(editor.configManager.getSessionDirectory()), editor.workspaceDocuments());
        }, editor::resolveUiFont, editor::resolveEditorFont);
        tabs.addTab("Conversation", session.component()); tabs.addTab("Review changes", review.component());
        tabs.addChangeListener(event -> refresh()); panel.add(tabs, BorderLayout.CENTER); refreshAppearance();
    }
    @Override public JPanel component() { return panel; }
    void showReview() { tabs.setSelectedIndex(1); }
    @Override public void refresh() { if (tabs.getSelectedIndex() == 0) session.refresh(); else review.refresh(); }
    @Override public void refreshAppearance() { tabs.setFont(uiFont.get()); session.refreshAppearance(); review.refreshAppearance(); }
}
