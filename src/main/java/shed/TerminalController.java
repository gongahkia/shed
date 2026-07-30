package shed;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TerminalController {
    private final Texteditor editor;

    TerminalController(Texteditor editor) {
        this.editor = editor;
    }

    public String openTerminal() {
        File startDirectory = resolveTerminalStartDirectory();
        String title = nextTerminalTitle();
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


    void closeAllTerminalSessions() {
        for (PtyTerminalPane terminalPane : new ArrayList<>(editor.ptyTerminalPanes.values())) {
            terminalPane.close();
        }
        editor.ptyTerminalPanes.clear();
        for (EditorPane pane : editor.editorPanes) {
            pane.closeTerminalPane();
        }
    }


    List<Map<String, Object>> serializeSessionMetadata() {
        List<Map<String, Object>> states = new ArrayList<>();
        for (int index = 0; index < editor.editorPanes.size(); index++) {
            EditorPane pane = editor.editorPanes.get(index);
            PtyTerminalPane terminalPane = pane == null ? null : pane.getTerminalPane();
            if (terminalPane == null) {
                continue;
            }
            TerminalSessionState state = new TerminalSessionState(index, terminalPane.getWorkingDirectory().getAbsolutePath());
            states.add(state.toMap());
        }
        return states;
    }


    TerminalRestoreResult restoreSessionMetadata(Object value) {
        TerminalSessionState.ParseResult parsed = TerminalSessionState.parseAll(value);
        int restored = 0;
        int unavailable = 0;
        int ignored = parsed.ignored();
        for (TerminalSessionState state : parsed.states()) {
            if (state.paneIndex() >= editor.editorPanes.size()) {
                ignored++;
                continue;
            }
            EditorPane pane = editor.editorPanes.get(state.paneIndex());
            if (!editor.configManager.getTerminalSessionRestoreEnabled()) {
                installUnavailableTerminal(pane, state, "terminal.session.restore is disabled");
                unavailable++;
                continue;
            }
            File workingDirectory = new File(state.workingDirectory());
            if (!workingDirectory.isDirectory()) {
                installUnavailableTerminal(pane, state, "saved working directory is unavailable");
                unavailable++;
                continue;
            }
            try {
                installRestoredTerminal(pane, workingDirectory);
                restored++;
            } catch (IOException | SecurityException error) {
                installUnavailableTerminal(pane, state, "fresh shell could not start: " + safeMessage(error));
                unavailable++;
            }
        }
        if (restored > 0) {
            editor.renderWindowLayout();
        }
        return new TerminalRestoreResult(restored, unavailable, ignored);
    }


    private void installRestoredTerminal(EditorPane pane, File workingDirectory) throws IOException {
        PtyTerminalPane terminalPane = PtyTerminalPane.open(workingDirectory, editor.configManager, editor.resolveEditorFont());
        FileBuffer terminalBuffer = FileBuffer.createScratch(nextTerminalTitle(), "");
        editor.buffers.add(terminalBuffer);
        pane.setBuffer(terminalBuffer);
        pane.setLargeFileProjection(null);
        pane.setTerminalPane(terminalPane);
        installTerminalActivationListeners(pane, terminalPane.getComponent());
        editor.ptyTerminalPanes.put(terminalBuffer, terminalPane);
        terminalPane.onExit(() -> SwingUtilities.invokeLater(() -> closeExitedTerminal(terminalBuffer)));
    }


    private void installUnavailableTerminal(EditorPane pane, TerminalSessionState state, String reason) {
        String content = "Terminal session not restored\n\nWorking directory: " + state.workingDirectory()
            + "\nReason: " + reason
            + "\n\nOnly the terminal pane working directory is session metadata. Commands, shell arguments, scrollback, and process state are never saved or replayed.";
        FileBuffer notice = FileBuffer.createScratch("[terminal not restored]", content);
        editor.buffers.add(notice);
        editor.loadBufferIntoPane(pane, notice, 0);
    }


    private String nextTerminalTitle() {
        return "[Terminal " + (editor.terminalBufferCounter++) + "]";
    }


    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
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


    record TerminalRestoreResult(int restored, int unavailable, int ignored) {
        TerminalRestoreResult {
            if (restored < 0 || unavailable < 0 || ignored < 0) {
                throw new IllegalArgumentException("terminal restore counts must be non-negative");
            }
        }

        String summary() {
            if (restored == 0 && unavailable == 0 && ignored == 0) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            if (restored > 0) {
                parts.add(restored + " fresh terminal " + (restored == 1 ? "shell" : "shells"));
            }
            if (unavailable > 0) {
                parts.add(unavailable + " terminal " + (unavailable == 1 ? "pane was" : "panes were") + " not restored");
            }
            if (ignored > 0) {
                parts.add(ignored + " invalid terminal " + (ignored == 1 ? "entry" : "entries") + " ignored");
            }
            return " (" + String.join("; ", parts) + ")";
        }
    }

}
