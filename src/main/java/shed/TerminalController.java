package shed;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class TerminalController {
    private final Texteditor editor;

    TerminalController(Texteditor editor) {
        this.editor = editor;
    }

    public String openTerminal() {
        File startDirectory = resolveTerminalStartDirectory();
        String title = "[Terminal " + (editor.terminalBufferCounter++) + "]";
        PtyTerminalPane terminalPane;
        try {
            terminalPane = PtyTerminalPane.open(startDirectory, editor.configManager, editor.resolveEditorFont());
        } catch (IOException e) {
            return "Terminal failed: " + e.getMessage();
        }

        FileBuffer termBuffer = FileBuffer.createScratch(title, "");
        editor.buffers.add(termBuffer);

        EditorPane activePane = editor.getActivePane();
        if (activePane == null) {
            terminalPane.close();
            return "No active window";
        }
        Dimension size = editor.getSize();
        EditorPane terminalEditorPane = editor.createEditorPane(size);
        terminalEditorPane.setBuffer(termBuffer);
        terminalEditorPane.setTerminalPane(terminalPane);
        installTerminalActivationListeners(terminalEditorPane, terminalPane.getComponent());
        editor.editorPanes.add(terminalEditorPane);
        if (editor.windowLayoutRoot == null) {
            editor.windowLayoutRoot = WindowLayoutNode.leaf(activePane);
        }
        double startRatio = editor.dramaticPanelAnimationsEnabled && editor.dramaticMotionAllowed() ? 0.12 : 0.5;
        editor.windowLayoutRoot.splitLeaf(activePane, terminalEditorPane, WindowLayoutNode.Orientation.VERTICAL, false, startRatio);
        editor.ptyTerminalPanes.put(termBuffer, terminalPane);
        terminalPane.onExit(() -> SwingUtilities.invokeLater(() -> closeExitedTerminal(termBuffer)));
        editor.renderWindowLayout();
        editor.animateSplitForPane(terminalEditorPane, startRatio, 0.5);
        editor.activateEditorPane(terminalEditorPane);
        editor.setMode(EditorMode.INSERT);
        terminalPane.requestFocusInWindow();
        return "Terminal opened";
    }


    File resolveTerminalStartDirectory() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer != null && buffer.getFile() != null && buffer.getFile().getParentFile() != null) {
            return buffer.getFile().getParentFile();
        }
        return new File(".");
    }


    void closeTerminalSession(FileBuffer buffer) {
        if (buffer == null) {
            return;
        }
        PtyTerminalPane terminalPane = editor.ptyTerminalPanes.remove(buffer);
        if (terminalPane != null) {
            terminalPane.close();
        }
    }


    void closeExitedTerminal(FileBuffer buffer) {
        if (buffer == null || !editor.ptyTerminalPanes.containsKey(buffer)) {
            return;
        }
        List<EditorPane> panes = new ArrayList<>();
        for (EditorPane pane : editor.editorPanes) {
            if (pane.getBuffer() == buffer) {
                panes.add(pane);
            }
        }
        if (panes.isEmpty()) {
            closeTerminalSession(buffer);
            editor.buffers.remove(buffer);
            return;
        }
        for (EditorPane pane : panes) {
            if (!editor.editorPanes.contains(pane)) {
                continue;
            }
            if (editor.editorPanes.size() > 1) {
                editor.closePane(pane);
                continue;
            }
            closeTerminalSession(buffer);
            editor.buffers.remove(buffer);
            FileBuffer replacement = editor.buffers.isEmpty() ? null : editor.buffers.get(0);
            if (replacement == null) {
                editor.openLandingPage();
            } else {
                editor.loadBufferIntoPane(pane, replacement, 0);
            }
        }
        editor.buffers.remove(buffer);
        editor.currentBufferIndex = editor.buffers.isEmpty() ? -1 : Math.min(Math.max(0, editor.currentBufferIndex), editor.buffers.size() - 1);
        editor.showMessage("Terminal exited");
    }


    void installTerminalActivationListeners(EditorPane pane, Component component) {
        if (pane == null || component == null) {
            return;
        }
        component.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                editor.activateEditorPane(pane);
            }
        });
        component.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                editor.activateEditorPane(pane);
            }
        });
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                installTerminalActivationListeners(pane, child);
            }
        }
    }

}
