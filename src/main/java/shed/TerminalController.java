package shed;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.util.*;
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


    TerminalSession getActiveTerminalSession() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null) {
            return null;
        }
        return editor.terminalSessions.get(buffer);
    }


    boolean handleTerminalInsertMode(TerminalSession session, KeyEvent e) {
        if (session == null || editor.writingArea == null) {
            return false;
        }
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (code == KeyEvent.VK_ESCAPE || (e.isControlDown() && code == KeyEvent.VK_OPEN_BRACKET)) {
            return false;
        }

        enforceTerminalInputBoundary(session);

        if (session.runningJobId >= 0) {
            if (e.isControlDown() && (code == KeyEvent.VK_C || c == 'c')) {
                editor.asyncJobService.cancel(session.runningJobId);
                editor.showMessage("Terminal command cancelled");
                return true;
            }
            if (code == KeyEvent.VK_ENTER || (!e.isControlDown() && !e.isAltDown() && c != KeyEvent.CHAR_UNDEFINED)) {
                editor.showMessage("Terminal command running (Ctrl+C to cancel)");
                return true;
            }
            return true;
        }

        if (e.isControlDown() && (code == KeyEvent.VK_C || c == 'c')) {
            appendTerminalText(session, "^C\n");
            appendTerminalPrompt(session);
            return true;
        }

        if (code == KeyEvent.VK_ENTER) {
            executeTerminalLine(session);
            return true;
        }
        if (code == KeyEvent.VK_UP) {
            terminalHistoryPrevious(session);
            return true;
        }
        if (code == KeyEvent.VK_DOWN) {
            terminalHistoryNext(session);
            return true;
        }
        if (code == KeyEvent.VK_HOME) {
            editor.writingArea.setCaretPosition(session.promptOffset);
            return true;
        }
        if (code == KeyEvent.VK_END) {
            editor.writingArea.setCaretPosition(editor.writingArea.getText().length());
            return true;
        }
        if (code == KeyEvent.VK_LEFT) {
            int caret = editor.writingArea.getCaretPosition();
            if (caret > session.promptOffset) {
                editor.writingArea.setCaretPosition(caret - 1);
            }
            return true;
        }
        if (code == KeyEvent.VK_RIGHT) {
            int caret = editor.writingArea.getCaretPosition();
            if (caret < editor.writingArea.getText().length()) {
                editor.writingArea.setCaretPosition(caret + 1);
            }
            return true;
        }
        if (code == KeyEvent.VK_BACK_SPACE) {
            int caret = editor.writingArea.getCaretPosition();
            if (caret > session.promptOffset) {
                editor.withSuppressedDocumentEvents(() -> editor.writingArea.replaceRange("", caret - 1, caret));
                session.buffer.setModified(false);
            }
            return true;
        }
        if (code == KeyEvent.VK_DELETE) {
            int caret = editor.writingArea.getCaretPosition();
            if (caret >= session.promptOffset && caret < editor.writingArea.getText().length()) {
                editor.withSuppressedDocumentEvents(() -> editor.writingArea.replaceRange("", caret, caret + 1));
                session.buffer.setModified(false);
            }
            return true;
        }
        if (code == KeyEvent.VK_TAB) {
            insertTerminalInputText(session, "    ");
            return true;
        }

        if (!e.isControlDown() && !e.isAltDown() && c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
            insertTerminalInputText(session, String.valueOf(c));
            return true;
        }
        return true;
    }


    void enforceTerminalInputBoundary(TerminalSession session) {
        int caret = editor.writingArea.getCaretPosition();
        if (caret < session.promptOffset) {
            editor.writingArea.setCaretPosition(editor.writingArea.getText().length());
        }
    }


    void insertTerminalInputText(TerminalSession session, String text) {
        enforceTerminalInputBoundary(session);
        int caret = editor.writingArea.getCaretPosition();
        editor.withSuppressedDocumentEvents(() -> editor.writingArea.insert(text, caret));
        session.buffer.setModified(false);
        session.historyIndex = session.history.size();
        session.historyDraft = currentTerminalInput(session);
    }


    void executeTerminalLine(TerminalSession session) {
        enforceTerminalInputBoundary(session);
        String command = currentTerminalInput(session);
        appendTerminalText(session, "\n");
        String trimmed = command.trim();
        if (!trimmed.isEmpty()) {
            if (session.history.isEmpty() || !trimmed.equals(session.history.get(session.history.size() - 1))) {
                session.history.add(trimmed);
            }
        }
        session.historyIndex = session.history.size();
        session.historyDraft = "";

        if (trimmed.isEmpty()) {
            appendTerminalPrompt(session);
            return;
        }

        String builtinResult = handleTerminalBuiltin(session, command);
        if (builtinResult != null) {
            if (!builtinResult.isEmpty()) {
                appendTerminalText(session, builtinResult + (builtinResult.endsWith("\n") ? "" : "\n"));
            }
            appendTerminalPrompt(session);
            return;
        }

        String validationError = editor.validateShellCommand(command);
        if (validationError != null) {
            appendTerminalText(session, validationError + "\n");
            appendTerminalPrompt(session);
            return;
        }

        int jobId = editor.asyncJobService.submit(
            "terminal: " + command,
            token -> editor.runExternalCommand(
                ShellCommand.forCommand(command),
                session.workingDirectory,
                null,
                token,
                editor.configManager.getProcessTimeoutMs(),
                editor.configManager.getProcessOutputMaxBytes(),
                true
            ),
            (snapshot, result, error) -> SwingUtilities.invokeLater(() ->
                handleTerminalCommandCompletion(session, command, snapshot, result, error))
        );
        session.runningJobId = jobId;
    }


    String handleTerminalBuiltin(TerminalSession session, String rawCommand) {
        List<String> args = editor.parseQuotedArguments(rawCommand);
        if (args.isEmpty()) {
            return "";
        }
        String head = args.get(0).toLowerCase(Locale.ROOT);
        if ("clear".equals(head) || "cls".equals(head)) {
            session.buffer.setContent("", false);
            if (editor.getCurrentBuffer() == session.buffer) {
                editor.writingArea.setCaretPosition(0);
            }
            return "";
        }
        if ("pwd".equals(head)) {
            return session.workingDirectory.getAbsolutePath();
        }
        if ("cd".equals(head)) {
            String target = args.size() >= 2 ? args.get(1) : System.getProperty("user.home");
            if (target == null || target.isBlank()) {
                target = ".";
            }
            File destination = new File(target);
            if (!destination.isAbsolute()) {
                destination = new File(session.workingDirectory, target);
            }
            try {
                destination = destination.getCanonicalFile();
            } catch (IOException ignored) {
                destination = destination.getAbsoluteFile();
            }
            if (!destination.exists()) {
                return "cd: no such file or directory: " + target;
            }
            if (!destination.isDirectory()) {
                return "cd: not a directory: " + target;
            }
            session.workingDirectory = destination;
            return "";
        }
        return null;
    }


    void handleTerminalCommandCompletion(
        TerminalSession session,
        String command,
        AsyncJobService.JobSnapshot snapshot,
        CommandResult result,
        Exception error
    ) {
        if (session == null || !editor.terminalSessions.containsKey(session.buffer)) {
            return;
        }
        int finishedId = snapshot == null ? -1 : snapshot.getId();
        if (session.runningJobId == finishedId || snapshot == null) {
            session.runningJobId = -1;
        }

        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED) {
            appendTerminalText(session, "^C\n");
            appendTerminalPrompt(session);
            return;
        }
        if (error != null || result == null) {
            String message = error == null ? "unknown error" : error.getMessage();
            appendTerminalText(session, "error: " + (message == null ? "" : message) + "\n");
            appendTerminalPrompt(session);
            return;
        }

        String output = result.stdout == null ? "" : result.stdout;
        if (!output.isEmpty()) {
            appendTerminalText(session, output.endsWith("\n") ? output : output + "\n");
            List<QuickfixService.Entry> parsedEntries = editor.parseQuickfixEntries(output, "terminal");
            if (!parsedEntries.isEmpty()) {
                editor.updateQuickfixEntries("terminal: " + command, parsedEntries);
            }
        }
        if (result.exitCode != 0) {
            appendTerminalText(session, "[exit " + result.exitCode + "]\n");
        }
        appendTerminalPrompt(session);
    }


    void terminalHistoryPrevious(TerminalSession session) {
        if (session.history.isEmpty()) {
            return;
        }
        if (session.historyIndex == session.history.size()) {
            session.historyDraft = currentTerminalInput(session);
        }
        if (session.historyIndex > 0) {
            session.historyIndex--;
        } else {
            session.historyIndex = 0;
        }
        replaceTerminalInput(session, session.history.get(session.historyIndex));
    }


    void terminalHistoryNext(TerminalSession session) {
        if (session.history.isEmpty()) {
            return;
        }
        if (session.historyIndex < session.history.size() - 1) {
            session.historyIndex++;
            replaceTerminalInput(session, session.history.get(session.historyIndex));
            return;
        }
        session.historyIndex = session.history.size();
        replaceTerminalInput(session, session.historyDraft == null ? "" : session.historyDraft);
    }


    void replaceTerminalInput(TerminalSession session, String input) {
        String safeInput = input == null ? "" : input;
        String current = editor.writingArea.getText();
        int start = Math.max(0, Math.min(session.promptOffset, current.length()));
        editor.withSuppressedDocumentEvents(() -> editor.writingArea.replaceRange(safeInput, start, current.length()));
        session.buffer.setModified(false);
        editor.writingArea.setCaretPosition(editor.writingArea.getText().length());
    }


    String currentTerminalInput(TerminalSession session) {
        String text = editor.writingArea.getText();
        int start = Math.max(0, Math.min(session.promptOffset, text.length()));
        return text.substring(start);
    }


    void appendTerminalPrompt(TerminalSession session) {
        String prompt = terminalPrompt(session);
        appendTerminalText(session, prompt);
        session.promptOffset = session.buffer.getContent().length();
        if (editor.getCurrentBuffer() == session.buffer) {
            editor.writingArea.setCaretPosition(editor.writingArea.getText().length());
        }
    }


    String terminalPrompt(TerminalSession session) {
        String dir = session.workingDirectory == null ? "." : session.workingDirectory.getAbsolutePath();
        return dir + " $ ";
    }


    void appendTerminalText(TerminalSession session, String text) {
        if (session == null || text == null || text.isEmpty()) {
            return;
        }
        FileBuffer buffer = session.buffer;
        String current = buffer.getContent();
        buffer.setContent(current + text, false);
        if (editor.getCurrentBuffer() == buffer) {
            editor.writingArea.setCaretPosition(editor.writingArea.getText().length());
        }
    }


    void closeTerminalSession(FileBuffer buffer) {
        if (buffer == null) {
            return;
        }
        PtyTerminalPane terminalPane = editor.ptyTerminalPanes.remove(buffer);
        if (terminalPane != null) {
            terminalPane.close();
        }
        TerminalSession session = editor.terminalSessions.remove(buffer);
        if (session != null && session.runningJobId >= 0) {
            editor.asyncJobService.cancel(session.runningJobId);
            session.runningJobId = -1;
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
