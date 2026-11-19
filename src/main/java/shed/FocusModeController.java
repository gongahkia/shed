package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.io.File;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.border.Border;

final class FocusModeController {
    static final int MAX_CENTERED_WRAPPED_CHARS = 100 * 1024;
    private final Texteditor editor;
    private boolean limelightBeforeZen;
    private boolean zenEnabled;
    private boolean centeredWriting;
    private boolean closed;
    private boolean layoutPending;
    private boolean tabsPreference;
    private FocusState focusState;
    private final Map<EditorPane, CenteredBorder> centeredBorders = new WeakHashMap<>();
    private int focusStart;
    private int focusEnd;

    private record CenteredBorder(Border original, Border applied, int padding) { }

    private static final class FocusState {
        final Map<JComponent, Boolean> visibility = new WeakHashMap<>();
        final Map<EditorPane, Boolean> hiddenPanes = new WeakHashMap<>();
        final boolean centeredBefore;
        final boolean tabsPreference;
        ToolWindowHost.FocusState tools;

        FocusState(boolean centeredBefore, boolean tabsPreference) {
            this.centeredBefore = centeredBefore;
            this.tabsPreference = tabsPreference;
        }

        void remember(JComponent component) {
            if (component != null) visibility.putIfAbsent(component, component.isVisible());
        }

        void restore() {
            visibility.forEach((component, visible) -> {
                if (component.isDisplayable()) component.setVisible(visible);
            });
        }
    }

    FocusModeController(Texteditor editor) {
        this.editor = editor;
        tabsPreference = editor.configManager.getOpenEditorsVisible();
        focusStart = -1;
        focusEnd = -1;
    }

    String toggleZenMode() {
        if (!editor.goyoModeEnabled) {
            limelightBeforeZen = editor.limelightEnabled;
            setGoyoEnabled(true);
            if (!editor.goyoModeEnabled) return "No editor available for Zen mode";
            setLimelightEnabled(true);
            zenEnabled = true;
            return "Zen mode enabled";
        }
        setGoyoEnabled(false);
        return "Zen mode disabled";
    }

    String toggleGoyoMode() {
        setGoyoEnabled(!editor.goyoModeEnabled);
        if (!editor.goyoModeEnabled && hasLargeWrappedSource()) return "Goyo mode unavailable for large wrapped panes";
        return editor.goyoModeEnabled ? "Goyo mode enabled" : "Goyo mode disabled";
    }

    String toggleLimelight() {
        setLimelightEnabled(!editor.limelightEnabled);
        return editor.limelightEnabled ? "Limelight enabled" : "Limelight disabled";
    }

    boolean isGoyoEnabled() {
        return editor.goyoModeEnabled;
    }

    String handleFocusCommand(String argument) {
        String operation = argument == null ? "" : argument.trim().toLowerCase(java.util.Locale.ROOT);
        switch (operation) {
            case "", "toggle" -> setGoyoEnabled(!editor.goyoModeEnabled);
            case "on" -> setGoyoEnabled(true);
            case "off" -> setGoyoEnabled(false);
            default -> { return "Usage: :focus [on|off|toggle]"; }
        }
        if (!editor.goyoModeEnabled && !operation.equals("off") && hasLargeWrappedSource()) return "Focus mode unavailable for large wrapped panes";
        return editor.goyoModeEnabled ? "Focus mode enabled" : "Focus mode disabled";
    }

    String handleCenterCommand(String argument) {
        String operation = argument == null ? "" : argument.trim().toLowerCase(java.util.Locale.ROOT);
        switch (operation) {
            case "", "toggle" -> setCenteredWriting(!centeredWriting);
            case "on" -> setCenteredWriting(true);
            case "off" -> setCenteredWriting(false);
            default -> { return "Usage: :center [on|off|toggle]"; }
        }
        if (!centeredWriting && !operation.equals("off") && hasLargeWrappedSource()) return "Centered writing unavailable for large wrapped panes";
        return centeredWriting ? "Centered writing enabled" : "Centered writing disabled";
    }

    private boolean hasLargeWrappedSource() {
        return editor.editorPanes.stream().anyMatch(pane -> !pane.isHiddenByFocusMode() && !pane.isMarkdownPreview()
            && pane.getTerminalPane() == null && pane.getAlternateComponent() == null && pane.getTextArea().getLineWrap()
            && pane.getTextArea().getDocument().getLength() > MAX_CENTERED_WRAPPED_CHARS);
    }

    boolean isCenteredWriting() { return centeredWriting; }

    void requestLayoutUpdate() {
        if (closed || !centeredWriting || layoutPending) return;
        layoutPending = true;
        SwingUtilities.invokeLater(() -> {
            layoutPending = false;
            if (!closed && centeredWriting) updateZenModeLayout();
        });
    }

    void setCenteredWriting(boolean enabled) {
        if (closed || centeredWriting == enabled) return;
        if (enabled && hasLargeWrappedSource()) return;
        centeredWriting = enabled;
        updateZenModeLayout();
    }

    void setGoyoEnabled(boolean enabled) {
        if (closed) return;
        if (editor.goyoModeEnabled == enabled) {
            syncChrome();
            return;
        }
        if (enabled) {
            if (hasLargeWrappedSource()) return;
            if (editor.getActivePane() == editor.treePane && firstVisiblePaneExcept(editor.treePane) == null) return;
            if (editor.windowLayoutRoot != null) editor.windowLayoutRoot.rememberDividerLocations();
            focusState = new FocusState(centeredWriting, editor.configManager.getOpenEditorsVisible());
            for (JComponent component : new JComponent[]{editor.workbenchHeader, editor.openEditorsBar,
                    editor.footerPanel, editor.statusBar, editor.commandBar}) focusState.remember(component);
            editor.goyoModeEnabled = true;
            centeredWriting = true;
            enterGoyo();
        } else {
            editor.goyoModeEnabled = false;
            leaveGoyo();
            if (zenEnabled) {
                zenEnabled = false;
                setLimelightEnabled(limelightBeforeZen);
            }
        }
        updateZenModeLayout();
        editor.refreshLineNumberPanel();
        editor.renderWindowLayout();
        editor.requestActivePaneFocus();
    }

    private void enterGoyo() {
        if (editor.toolWindowHost != null) focusState.tools = editor.toolWindowHost.hideForFocusMode();
        hideTree();
        syncChrome();
    }

    private void hideTree() {
        if (editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
            focusState.hiddenPanes.putIfAbsent(editor.treePane, editor.treePane.isHiddenByFocusMode());
            if (editor.getActivePane() == editor.treePane) {
                EditorPane replacement = firstVisiblePaneExcept(editor.treePane);
                if (replacement != null) {
                    editor.activateEditorPane(replacement);
                }
            }
            editor.treePane.setHiddenByFocusMode(true);
        }
    }

    private void leaveGoyo() {
        FocusState previous = focusState;
        if (previous == null) return;
        previous.hiddenPanes.forEach((pane, hidden) -> {
            if (editor.editorPanes.contains(pane)) pane.setHiddenByFocusMode(hidden);
        });
        if (editor.toolWindowHost != null && previous.tools != null) editor.toolWindowHost.restoreAfterFocusMode(previous.tools);
        previous.restore();
        if (editor.openEditorsBar != null && previous.tabsPreference != editor.configManager.getOpenEditorsVisible())
            editor.openEditorsBar.setVisible(editor.configManager.getOpenEditorsVisible());
        centeredWriting = previous.centeredBefore;
        focusState = null;
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
        if (closed) return;
        if (!editor.goyoModeEnabled || focusState == null) {
            boolean current = editor.configManager.getOpenEditorsVisible();
            if (tabsPreference != current && editor.openEditorsBar != null) editor.openEditorsBar.setVisible(current);
            tabsPreference = current;
            return;
        }
        hideTree();
        for (JComponent component : new JComponent[]{editor.workbenchHeader, editor.openEditorsBar, editor.activeMinimapPanel}) {
            if (component != null) { focusState.remember(component); component.setVisible(false); }
        }
        if (editor.footerPanel == null) {
            return;
        }
        boolean commandActive = editor.editorState != null
            && (editor.editorState.mode == EditorMode.COMMAND || editor.editorState.mode == EditorMode.SEARCH);
        editor.footerPanel.setVisible(commandActive);
        if (editor.statusBar != null) editor.statusBar.setVisible(false);
        if (editor.commandBar != null) editor.commandBar.setVisible(commandActive);
        editor.getContentPane().revalidate();
        editor.getContentPane().repaint();
    }

    void updateZenModeLayout() {
        if (closed || editor.editorHostPanel == null) {
            return;
        }
        if ((editor.goyoModeEnabled || centeredWriting) && hasLargeWrappedSource()) {
            setGoyoEnabled(false);
            setCenteredWriting(false);
            return;
        }
        Color background = editor.getModeBackground(editor.editorState.mode == null ? EditorMode.NORMAL : editor.editorState.mode);
        editor.getContentPane().setBackground(background);
        editor.editorHostPanel.setBackground(background);
        editor.editorHostPanel.setOpaque(true);
        if (editor.editorToolSplit != null) editor.editorToolSplit.setBackground(background);
        centeredBorders.keySet().removeIf(pane -> !editor.editorPanes.contains(pane));
        for (EditorPane pane : editor.editorPanes) {
            JScrollPane scrollPane = pane.getScrollPane();
            JTextArea area = pane.getTextArea();
            area.setBackground(background);
            scrollPane.setOpaque(true);
            scrollPane.getViewport().setOpaque(true);
            scrollPane.setBackground(background);
            scrollPane.getViewport().setBackground(background);
            if (!centeredWriting || pane.isHiddenByFocusMode() || pane.isMarkdownPreview()
                    || pane.getTerminalPane() != null || pane.getAlternateComponent() != null
                    || area.getLineWrap() && area.getDocument().getLength() > MAX_CENTERED_WRAPPED_CHARS) {
                CenteredBorder saved = centeredBorders.remove(pane);
                if (saved != null && scrollPane.getBorder() == saved.applied()) scrollPane.setBorder(saved.original());
                continue;
            }
            CenteredBorder saved = centeredBorders.get(pane);
            Border original = saved != null && scrollPane.getBorder() == saved.applied() ? saved.original() : scrollPane.getBorder();
            Insets insets = original == null ? new Insets(0, 0, 0, 0) : original.getBorderInsets(scrollPane);
            int overhead = insets.left + insets.right;
            if (scrollPane.getRowHeader() != null && scrollPane.getRowHeader().isVisible()) overhead += scrollPane.getRowHeader().getWidth();
            if (scrollPane.getVerticalScrollBar().isVisible()) overhead += scrollPane.getVerticalScrollBar().getWidth();
            Border viewportBorder = scrollPane.getViewportBorder();
            if (viewportBorder != null) { Insets v = viewportBorder.getBorderInsets(scrollPane); overhead += v.left + v.right; }
            int padding = centeredPadding(scrollPane.getWidth(), overhead, editor.configManager.getZenModeWidth(), area.getFontMetrics(area.getFont()).charWidth('M'));
            if (saved == null || saved.original() != original || saved.padding() != padding || scrollPane.getBorder() != saved.applied()) {
                Border applied = BorderFactory.createCompoundBorder(original, BorderFactory.createEmptyBorder(0, padding, 0, padding));
                centeredBorders.put(pane, new CenteredBorder(original, applied, padding));
                scrollPane.setBorder(applied);
            }
        }
        syncChrome();
        editor.editorHostPanel.revalidate();
        editor.editorHostPanel.repaint();
    }

    static int centeredPadding(int width, int overhead, int columns, int charWidth) {
        long desired = (long) Math.max(20, Math.min(240, columns)) * Math.max(1, charWidth);
        return (int) Math.max(0, (Math.max(0L, (long) width - overhead) - desired) / 2);
    }

    Map<String, Object> dockStateForSession() {
        return focusState != null && focusState.tools != null ? focusState.tools.dockState() : editor.toolWindowHost.captureDockState();
    }

    void endForLayoutReplacement() {
        setGoyoEnabled(false);
        setCenteredWriting(false);
    }

    void close() {
        closed = true;
        editor.goyoModeEnabled = false;
        centeredWriting = false;
        focusState = null;
        centeredBorders.clear();
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
