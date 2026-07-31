package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.io.File;

final class FocusModeController {
    private final Texteditor editor;
    private boolean limelightBeforeZen;
    private boolean minimapVisibleBeforeGoyo;
    private boolean toolWindowVisibleBeforeGoyo;
    private EditorPane minimapPaneBeforeGoyo;
    private int focusStart;
    private int focusEnd;

    FocusModeController(Texteditor editor) {
        this.editor = editor;
        focusStart = -1;
        focusEnd = -1;
    }

    String toggleZenMode() {
        if (!editor.goyoModeEnabled) {
            limelightBeforeZen = editor.limelightEnabled;
            setGoyoEnabled(true);
            setLimelightEnabled(true);
            return "Zen mode enabled";
        }
        setGoyoEnabled(false);
        setLimelightEnabled(limelightBeforeZen);
        return "Zen mode disabled";
    }

    String toggleGoyoMode() {
        setGoyoEnabled(!editor.goyoModeEnabled);
        return editor.goyoModeEnabled ? "Goyo mode enabled" : "Goyo mode disabled";
    }

    String toggleLimelight() {
        setLimelightEnabled(!editor.limelightEnabled);
        return editor.limelightEnabled ? "Limelight enabled" : "Limelight disabled";
    }

    boolean isGoyoEnabled() {
        return editor.goyoModeEnabled;
    }

    void setGoyoEnabled(boolean enabled) {
        if (editor.goyoModeEnabled == enabled) {
            syncChrome();
            return;
        }
        editor.goyoModeEnabled = enabled;
        if (enabled) {
            enterGoyo();
        } else {
            leaveGoyo();
        }
        updateZenModeLayout();
        editor.refreshLineNumberPanel();
        editor.renderWindowLayout();
        editor.requestActivePaneFocus();
    }

    private void enterGoyo() {
        minimapVisibleBeforeGoyo = editor.activeMinimapPanel != null;
        minimapPaneBeforeGoyo = editor.getActivePane();
        if (minimapVisibleBeforeGoyo) {
            toggleMinimap();
        }
        toolWindowVisibleBeforeGoyo = editor.toolWindowHost != null && editor.toolWindowHost.hideForFocusMode();
        if (editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
            if (editor.getActivePane() == editor.treePane) {
                EditorPane replacement = firstVisiblePaneExcept(editor.treePane);
                if (replacement != null) {
                    editor.activateEditorPane(replacement);
                }
            }
            editor.treePane.setHiddenByFocusMode(true);
        }
        syncChrome();
    }

    private void leaveGoyo() {
        if (editor.treePane != null) {
            editor.treePane.setHiddenByFocusMode(false);
        }
        if (editor.toolWindowHost != null) {
            editor.toolWindowHost.restoreAfterFocusMode(toolWindowVisibleBeforeGoyo);
        }
        if (minimapVisibleBeforeGoyo && editor.activeMinimapPanel == null) {
            EditorPane previous = editor.getActivePane();
            if (minimapPaneBeforeGoyo != null && editor.editorPanes.contains(minimapPaneBeforeGoyo)) {
                editor.activateEditorPane(minimapPaneBeforeGoyo);
            }
            toggleMinimap();
            if (previous != null && editor.editorPanes.contains(previous)) {
                editor.activateEditorPane(previous);
            }
        }
        minimapVisibleBeforeGoyo = false;
        minimapPaneBeforeGoyo = null;
        syncChrome();
    }

    private EditorPane firstVisiblePaneExcept(EditorPane excluded) {
        for (EditorPane pane : editor.editorPanes) {
            if (pane != excluded && !pane.isHiddenByFocusMode()) {
                return pane;
            }
        }
        return null;
    }

    void syncChrome() {
        if (editor.footerPanel == null) {
            return;
        }
        if (!editor.goyoModeEnabled) {
            editor.footerPanel.setVisible(true);
            if (editor.statusBar != null) editor.statusBar.setVisible(true);
            if (editor.commandBar != null) editor.commandBar.setVisible(true);
        } else {
            boolean commandActive = editor.editorState != null
                && (editor.editorState.mode == EditorMode.COMMAND || editor.editorState.mode == EditorMode.SEARCH);
            editor.footerPanel.setVisible(commandActive);
            if (editor.statusBar != null) editor.statusBar.setVisible(false);
            if (editor.commandBar != null) editor.commandBar.setVisible(commandActive);
        }
        editor.getContentPane().revalidate();
        editor.getContentPane().repaint();
    }

    void updateZenModeLayout() {
        if (editor.editorHostPanel == null) {
            return;
        }
        Color background = editor.getModeBackground(editor.editorState.mode == null ? EditorMode.NORMAL : editor.editorState.mode);
        editor.getContentPane().setBackground(background);
        editor.editorHostPanel.setBackground(background);
        editor.editorHostPanel.setOpaque(true);
        if (editor.editorToolSplit != null) editor.editorToolSplit.setBackground(background);
        for (EditorPane pane : editor.editorPanes) {
            JScrollPane scrollPane = pane.getScrollPane();
            JTextArea area = pane.getTextArea();
            area.setBackground(background);
            scrollPane.setOpaque(true);
            scrollPane.getViewport().setOpaque(true);
            scrollPane.setBackground(background);
            scrollPane.getViewport().setBackground(background);
            if (!editor.goyoModeEnabled || pane.isHiddenByFocusMode()) {
                scrollPane.setBorder(null);
                continue;
            }
            int width = Math.max(0, editor.editorHostPanel.getWidth());
            int desired = editor.configManager.getZenModeWidth() * Math.max(8, area.getFontMetrics(area.getFont()).charWidth('M'));
            int horizontalPadding = Math.max(12, (width - desired) / 2);
            scrollPane.setBorder(BorderFactory.createEmptyBorder(0, horizontalPadding, 0, horizontalPadding));
        }
        syncChrome();
        editor.editorHostPanel.revalidate();
        editor.editorHostPanel.repaint();
    }

    String toggleMinimap() {
        EditorPane pane = editor.getActivePane();
        if (pane == null) return "No active pane";
        JScrollPane scrollPane = pane.getScrollPane();
        if (editor.activeMinimapPanel != null && editor.activeMinimapPanel.getParent() != null) {
            MinimapPanel panel = editor.activeMinimapPanel;
            Container parent = panel.getParent();
            parent.remove(panel);
            editor.activeMinimapPanel = null;
            parent.revalidate();
            parent.repaint();
            scrollPane.revalidate();
            scrollPane.repaint();
            return "Minimap hidden";
        }
        editor.activeMinimapPanel = new MinimapPanel(pane.getTextArea(), editor.perfService);
        editor.activeMinimapPanel.setColors(editor.configManager.getLineNumberBackground(), editor.configManager.getEditorForeground());
        editor.activeMinimapPanel.setPixelWidth(editor.configManager.getMinimapWidth());
        Container parent = scrollPane.getParent();
        if (parent instanceof JPanel panel) {
            panel.add(editor.activeMinimapPanel, BorderLayout.EAST);
        } else {
            JPanel wrapper = new JPanel(new BorderLayout());
            if (parent != null) {
                int index = -1;
                for (int i = 0; i < parent.getComponentCount(); i++) {
                    if (parent.getComponent(i) == scrollPane) {
                        index = i;
                        break;
                    }
                }
                if (index >= 0) parent.remove(index);
                wrapper.add(scrollPane, BorderLayout.CENTER);
                wrapper.add(editor.activeMinimapPanel, BorderLayout.EAST);
                if (index >= 0) parent.add(wrapper, index);
            }
        }
        scrollPane.getViewport().addChangeListener(event -> {
            if (editor.activeMinimapPanel != null) editor.activeMinimapPanel.repaint();
        });
        scrollPane.revalidate();
        scrollPane.repaint();
        return "Minimap shown";
    }

    void setLimelightEnabled(boolean enabled) {
        if (editor.limelightEnabled == enabled) {
            refreshLimelight();
            return;
        }
        editor.limelightEnabled = enabled;
        if (!enabled) {
            focusStart = -1;
            focusEnd = -1;
        }
        refreshLimelight();
    }

    void refreshLimelight() {
        if (!editor.limelightEnabled || editor.writingArea == null) {
            focusStart = -1;
            focusEnd = -1;
        } else {
            updateFocusRange(editor.writingArea);
        }
        for (EditorPane pane : editor.editorPanes) {
            pane.getTextArea().repaint();
        }
    }

    private void updateFocusRange(JTextArea area) {
        try {
            int selectionStart = area.getSelectionStart();
            int selectionEnd = area.getSelectionEnd();
            if (selectionStart != selectionEnd) {
                focusStart = selectionStart;
                focusEnd = selectionEnd;
                return;
            }
            int line = area.getLineOfOffset(area.getCaretPosition());
            if (isBlankLine(area, line)) {
                focusStart = area.getLineStartOffset(line);
                focusEnd = area.getLineEndOffset(line);
                return;
            }
            int first = line;
            int last = line;
            while (first > 0 && !isBlankLine(area, first - 1)) first--;
            while (last + 1 < area.getLineCount() && !isBlankLine(area, last + 1)) last++;
            int span = editor.configManager.getLimelightParagraphSpan();
            for (int i = 0; i < span && first > 0; i++) {
                first = previousParagraphStart(area, first - 1);
            }
            for (int i = 0; i < span && last + 1 < area.getLineCount(); i++) {
                last = nextParagraphEnd(area, last + 1);
            }
            focusStart = area.getLineStartOffset(first);
            focusEnd = area.getLineEndOffset(last);
        } catch (BadLocationException ignored) {
            focusStart = -1;
            focusEnd = -1;
        }
    }

    private boolean isBlankLine(JTextArea area, int line) throws BadLocationException {
        int start = area.getLineStartOffset(line);
        int end = area.getLineEndOffset(line);
        return area.getText(start, Math.max(0, end - start)).trim().isEmpty();
    }

    private int previousParagraphStart(JTextArea area, int line) throws BadLocationException {
        int cursor = line;
        while (cursor >= 0 && isBlankLine(area, cursor)) cursor--;
        while (cursor > 0 && !isBlankLine(area, cursor - 1)) cursor--;
        return Math.max(0, cursor);
    }

    private int nextParagraphEnd(JTextArea area, int line) throws BadLocationException {
        int cursor = line;
        while (cursor < area.getLineCount() && isBlankLine(area, cursor)) cursor++;
        while (cursor + 1 < area.getLineCount() && !isBlankLine(area, cursor + 1)) cursor++;
        return Math.min(area.getLineCount() - 1, cursor);
    }

    void paintLimelightOverlay(Graphics graphics, JTextArea area) {
        if (!editor.limelightEnabled || area != editor.writingArea || focusStart < 0 || focusEnd < focusStart) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int alpha = (int) Math.round(255 * editor.configManager.getLimelightCoefficient());
            if (alpha <= 0) return;
            Color background = area.getBackground();
            g.setColor(new Color(background.getRed(), background.getGreen(), background.getBlue(), Math.min(255, alpha)));
            Rectangle clip = g.getClipBounds();
            int startY = clip == null ? 0 : clip.y;
            int endY = clip == null ? area.getHeight() : clip.y + clip.height;
            int firstLine = Math.max(0, area.getLineOfOffset(area.viewToModel2D(new Point(0, startY))));
            int lastLine = Math.min(area.getLineCount() - 1, area.getLineOfOffset(area.viewToModel2D(new Point(0, endY))));
            for (int line = firstLine; line <= lastLine; line++) {
                int lineStart = area.getLineStartOffset(line);
                int lineEnd = area.getLineEndOffset(line);
                if (lineEnd > focusStart && lineStart < focusEnd) continue;
                Rectangle bounds = area.modelToView2D(lineStart).getBounds();
                g.fillRect(0, bounds.y, area.getWidth(), Math.max(1, bounds.height));
            }
        } catch (BadLocationException ignored) {
        } finally {
            g.dispose();
        }
    }
}
