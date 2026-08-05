package shed;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.util.*;
import java.util.List;

final class InputController {
    private final Texteditor editor;
    private final CompletionRequestState completionRequestState;
    private int completionJobId;
    private CompletionRequestState.Snapshot activeCompletionRequest;
    private final SnippetSession snippetSession;
    private final OpenBufferCompletionIndex openBufferCompletionIndex;
    private final CompletionRanker completionRanker;
    private javax.swing.Timer quickSuggestionTimer;
    private LspClient.CompletionTriggerKind pendingQuickSuggestionKind;
    private Character pendingQuickSuggestionCharacter;
    private javax.swing.Timer wordIndexTimer;
    private FileBuffer pendingWordIndexBuffer;
    private int wordIndexGeneration;
    private int wordIndexJobId;
    private int wordIndexJobSerial;
    private int completionResolveJobId;
    private LspClient.CompletionItem resolvingCompletion;
    private final Set<LspClient.CompletionItem> completionResolveAttempts;
    private final CompletionRequestState signatureHelpRequestState;
    private int signatureHelpJobId;
    private EmacsKeymap.Prefix emacsPrefix;

    InputController(Texteditor editor) {
        this.editor = editor;
        this.completionRequestState = new CompletionRequestState();
        this.completionJobId = -1;
        this.activeCompletionRequest = null;
        this.snippetSession = new SnippetSession();
        this.openBufferCompletionIndex = new OpenBufferCompletionIndex();
        this.completionRanker = new CompletionRanker();
        this.quickSuggestionTimer = null;
        this.pendingQuickSuggestionKind = LspClient.CompletionTriggerKind.INVOKED;
        this.pendingQuickSuggestionCharacter = null;
        this.wordIndexTimer = null;
        this.pendingWordIndexBuffer = null;
        this.wordIndexGeneration = 0;
        this.wordIndexJobId = -1;
        this.wordIndexJobSerial = 0;
        this.completionResolveJobId = -1;
        this.resolvingCompletion = null;
        this.completionResolveAttempts = Collections.newSetFromMap(new IdentityHashMap<>());
        this.signatureHelpRequestState = new CompletionRequestState();
        this.signatureHelpJobId = -1;
        this.emacsPrefix = EmacsKeymap.Prefix.NONE;
    }

    void onDocumentChanged(DocumentEvent event) {
        scheduleOpenBufferWordIndex();
        if (!editor.configManager.getLspCompletionAutoShow() || editor.editorState.mode != EditorMode.INSERT) {
            cancelQuickSuggestion();
            return;
        }
        Character insertedCharacter = insertedCharacter(event);
        FileBuffer buffer = editor.getCurrentBuffer();
        LspClient client = editor.existingLspClient(buffer);
        boolean triggerCharacter = insertedCharacter != null && editor.configManager.getLspCompletionTriggerCharacters()
            && client != null && client.supports(LspCapability.COMPLETION) && client.isCompletionTriggerCharacter(insertedCharacter);
        SwingUtilities.invokeLater(() -> {
            if (editor.editorState.mode != EditorMode.INSERT) return;
            String prefix = editor.currentCompletionPrefix();
            if (!triggerCharacter && (prefix == null || prefix.length() < 2)) {
                cancelQuickSuggestion();
                return;
            }
            scheduleQuickSuggestion(triggerCharacter ? LspClient.CompletionTriggerKind.TRIGGER_CHARACTER
                : LspClient.CompletionTriggerKind.INVOKED, triggerCharacter ? insertedCharacter : null);
        });
    }

    void scheduleOpenBufferWordIndex() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || buffer.isLargeFile()) return;
        pendingWordIndexBuffer = buffer;
        if (wordIndexTimer == null) {
            wordIndexTimer = new javax.swing.Timer(140, event -> rebuildOpenBufferWordIndex());
            wordIndexTimer.setRepeats(false);
        }
        wordIndexTimer.restart();
    }

    void removeOpenBufferWordIndex(FileBuffer buffer) { openBufferCompletionIndex.remove(buffer); }

    private void rebuildOpenBufferWordIndex() {
        FileBuffer buffer = pendingWordIndexBuffer;
        pendingWordIndexBuffer = null;
        if (buffer == null || buffer != editor.getCurrentBuffer() || buffer.isLargeFile()) return;
        String text = editor.writingArea.getText();
        submitOpenBufferWordIndex(buffer, text);
    }

    private void submitOpenBufferWordIndex(FileBuffer buffer, String text) {
        if (buffer == null || text == null || buffer.isLargeFile()) return;
        int generation = ++wordIndexGeneration;
        int serial = ++wordIndexJobSerial;
        if (wordIndexJobId >= 0) editor.asyncJobService.cancel(wordIndexJobId);
        wordIndexJobId = editor.asyncJobService.submit("completion word index", token -> openBufferCompletionIndex.build(text),
            (snapshot, words, error) -> {
                if (serial != wordIndexJobSerial) return;
                wordIndexJobId = -1;
                if (snapshot == null || snapshot.getStatus() != AsyncJobService.Status.SUCCEEDED || error != null
                    || generation != wordIndexGeneration || words == null || !editor.buffers.contains(buffer)) return;
                openBufferCompletionIndex.update(buffer, words);
            });
    }

    private void scheduleMissingOpenBufferWordIndex() {
        if (wordIndexJobId >= 0) return;
        FileBuffer current = editor.getCurrentBuffer();
        for (FileBuffer buffer : editor.buffers) {
            if (buffer.isLargeFile() || openBufferCompletionIndex.hasSnapshot(buffer)) continue;
            String text = buffer == current ? editor.writingArea.getText() : buffer.getFullContent();
            submitOpenBufferWordIndex(buffer, text);
            return;
        }
    }

    private void scheduleQuickSuggestion(LspClient.CompletionTriggerKind kind, Character character) {
        pendingQuickSuggestionKind = kind == null ? LspClient.CompletionTriggerKind.INVOKED : kind;
        pendingQuickSuggestionCharacter = character;
        if (quickSuggestionTimer == null) {
            quickSuggestionTimer = new javax.swing.Timer(editor.configManager.getLspCompletionDelayMs(), event -> {
                LspClient.CompletionTriggerKind pendingKind = pendingQuickSuggestionKind;
                Character pendingCharacter = pendingQuickSuggestionCharacter;
                pendingQuickSuggestionCharacter = null;
                if (editor.configManager.getLspCompletionAutoShow() && editor.editorState.mode == EditorMode.INSERT) {
                    showInlineCompletion(pendingKind, pendingCharacter, false);
                }
            });
            quickSuggestionTimer.setRepeats(false);
        }
        quickSuggestionTimer.setInitialDelay(editor.configManager.getLspCompletionDelayMs());
        quickSuggestionTimer.restart();
    }

    private void cancelQuickSuggestion() {
        if (quickSuggestionTimer != null) quickSuggestionTimer.stop();
        pendingQuickSuggestionCharacter = null;
    }

    private static Character insertedCharacter(DocumentEvent event) {
        if (event == null || event.getType() != DocumentEvent.EventType.INSERT || event.getLength() != 1) return null;
        try {
            return event.getDocument().getText(event.getOffset(), 1).charAt(0);
        } catch (BadLocationException error) {
            return null;
        }
    }

    public void keyPressed(KeyEvent e) {
        if (handlePaneShortcut(e)) {
            editor.requestStatusBarRefresh();
            return;
        }
        KeymapProfile profile = editor.configManager.getKeymapProfile();
        if (profile != KeymapProfile.EMACS) {
            emacsPrefix = EmacsKeymap.Prefix.NONE;
        }
        if (profile == KeymapProfile.PLAIN) {
            handlePlainKeymap(e);
            editor.requestStatusBarRefresh();
            return;
        }
        if (profile == KeymapProfile.EMACS) {
            handleEmacsKeymap(e);
            editor.requestStatusBarRefresh();
            return;
        }
        // Ctrl+[ as Escape alternative
        if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_OPEN_BRACKET) {
            e = new KeyEvent(e.getComponent(), e.getID(), e.getWhen(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
        }
        EditorMode previousMode = editor.editorState.mode;
        if (editor.editorState.mode == EditorMode.NORMAL && editor.recordingRegister != null && !(editor.editorState.pendingKey == '\0' && e.getKeyChar() == 'q')) {
            editor.macroBuffer.add(NormalizedKeyStroke.fromKeyEvent(e));
        }
        if (applyConfiguredKeybinding(e)) {
            editor.requestStatusBarRefresh();
            return;
        }
        if (isCommandPaletteShortcut(e)) {
            e.consume();
            editor.showMessage(editor.showCommandPalette());
            editor.requestStatusBarRefresh();
            return;
        }
        editor.modeEngine.dispatch(editor, editor.editorState, e);
        if (previousMode != EditorMode.INSERT && editor.editorState.mode == EditorMode.INSERT && editor.isPrintableKey(e)) {
            editor.suppressNextTypedChar = true;
        }
        // Ctrl+o one-shot: return to insert after one normal command completes
        if (editor.insertNormalOneShot && editor.editorState.mode == EditorMode.NORMAL && editor.editorState.pendingKey == '\0') {
            editor.insertNormalOneShot = false;
            editor.setMode(EditorMode.INSERT);
        }
        editor.requestStatusBarRefresh();
    }

    static boolean isCommandPaletteShortcut(KeyEvent event) {
        return event != null && event.getKeyCode() == KeyEvent.VK_P && event.isShiftDown()
            && (event.isControlDown() || event.isMetaDown()) && !event.isAltDown();
    }

    enum PaneShortcut { NONE, HORIZONTAL_SPLIT, VERTICAL_SPLIT, CLOSE }

    static PaneShortcut paneShortcut(KeyEvent event) {
        if (event == null || !event.isMetaDown() || event.isControlDown() || event.isAltDown()) {
            return PaneShortcut.NONE;
        }
        return switch (event.getKeyCode()) {
            case KeyEvent.VK_D -> event.isShiftDown() ? PaneShortcut.VERTICAL_SPLIT : PaneShortcut.HORIZONTAL_SPLIT;
            case KeyEvent.VK_W -> event.isShiftDown() ? PaneShortcut.NONE : PaneShortcut.CLOSE;
            default -> PaneShortcut.NONE;
        };
    }

    private boolean handlePaneShortcut(KeyEvent event) {
        PaneShortcut shortcut = paneShortcut(event);
        if (shortcut == PaneShortcut.NONE) {
            return false;
        }
        event.consume();
        String result = switch (shortcut) {
            case HORIZONTAL_SPLIT -> editor.splitWindow(false);
            case VERTICAL_SPLIT -> editor.splitWindow(true);
            case CLOSE -> editor.closeActiveWindow();
            case NONE -> "";
        };
        editor.showMessage(result);
        return true;
    }

    private void handlePlainKeymap(KeyEvent event) {
        ensureNonModalEditing();
        PlainKeymap.Action action = PlainKeymap.actionFor(event);
        if (action == PlainKeymap.Action.NONE) {
            return;
        }
        event.consume();
        editor.commandHandler.execute(action.exCommand());
    }

    private void handleEmacsKeymap(KeyEvent event) {
        ensureNonModalEditing();
        EmacsKeymap.Resolution resolution = EmacsKeymap.resolve(emacsPrefix, event);
        emacsPrefix = resolution.nextPrefix();
        if (resolution.consume()) {
            event.consume();
        }
        executeEmacsAction(resolution.action());
    }

    private void ensureNonModalEditing() {
        if (editor.editorState.mode != EditorMode.INSERT) {
            editor.setMode(EditorMode.INSERT);
        }
    }

    private void executeEmacsAction(EmacsKeymap.Action action) {
        switch (action) {
            case SAVE, FIND_FILE, BUFFERS, KILL_BUFFER, QUIT, COMMANDS, HELP -> editor.commandHandler.execute(action.exCommand());
            case FORWARD_CHAR -> editor.moveRight();
            case BACKWARD_CHAR -> editor.moveLeft();
            case NEXT_LINE -> editor.moveDown();
            case PREVIOUS_LINE -> editor.moveUp();
            case LINE_START -> editor.moveLineStart();
            case LINE_END -> editor.moveLineEnd();
            case FORWARD_WORD -> editor.moveWordForward();
            case BACKWARD_WORD -> editor.moveWordBackward();
            case FILE_START -> editor.moveFileStart();
            case FILE_END -> editor.moveFileEnd();
            case PAGE_DOWN -> editor.scrollFullPageDown();
            case PAGE_UP -> editor.scrollFullPageUp();
            case DELETE_FORWARD -> deleteEmacsForward();
            case KILL_LINE -> killEmacsLine();
            case KILL_REGION -> killEmacsRegion();
            case COPY_REGION -> copyEmacsRegion();
            case YANK -> yankEmacs();
            case CANCEL -> cancelEmacsPrefix();
            case NONE -> { }
        }
    }

    private void deleteEmacsForward() {
        if (!editor.clipboardManager.deleteChar(editor.writingArea).isEmpty()) {
            editor.markModified();
        }
    }

    private void killEmacsLine() {
        int start = editor.writingArea.getCaretPosition();
        String text = editor.writingArea.getText();
        int end = text.indexOf('\n', start);
        if (end >= 0) {
            end++;
        } else {
            end = text.length();
        }
        if (end > start) {
            editor.clipboardManager.yankSelection(text.substring(start, end));
            editor.writingArea.replaceRange("", start, end);
            editor.markModified();
        }
    }

    private void killEmacsRegion() {
        String selected = editor.writingArea.getSelectedText();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        editor.clipboardManager.yankSelection(selected);
        editor.writingArea.replaceSelection("");
        editor.markModified();
    }

    private void copyEmacsRegion() {
        String selected = editor.writingArea.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            editor.clipboardManager.yankSelection(selected);
        }
    }

    private void yankEmacs() {
        if (editor.clipboardManager.pasteAtCaret(editor.writingArea)) {
            editor.markModified();
        }
    }

    private void cancelEmacsPrefix() {
        emacsPrefix = EmacsKeymap.Prefix.NONE;
        editor.writingArea.setCaretPosition(editor.writingArea.getCaretPosition());
    }


    boolean applyConfiguredKeybinding(KeyEvent e) {
        if (editor.editorState.mode == null || editor.keymapReplayDepth > 32) {
            return false;
        }
        String keySpec = keySpecFromEvent(e);
        if (keySpec == null || keySpec.isEmpty()) {
            return false;
        }
        String mapping = editor.configManager.getKeybinding(modeKey(editor.editorState.mode), keySpec);
        if (mapping == null) {
            return false;
        }
        if (mapping.isEmpty() || mapping.equalsIgnoreCase("nop") || mapping.equalsIgnoreCase("<nop>")) {
            return true;
        }

        List<String> replayTokens = parseKeySequence(mapping);
        if (replayTokens.isEmpty()) {
            return true;
        }

        editor.keymapReplayDepth++;
        try {
            for (String token : replayTokens) {
                KeyEvent replay = keyEventFromToken(token);
                if (replay != null) {
                    keyPressed(replay);
                }
            }
        } finally {
            editor.keymapReplayDepth--;
        }
        return true;
    }


    String modeKey(EditorMode mode) {
        if (mode == null) {
            return "normal";
        }
        switch (mode) {
            case NORMAL:
                return "normal";
            case INSERT:
                return "insert";
            case VISUAL:
                return "visual";
            case VISUAL_LINE:
                return "visual_line";
            case REPLACE:
                return "replace";
            case COMMAND:
                return "command";
            case SEARCH:
                return "search";
            default:
                return "normal";
        }
    }


    String keySpecFromEvent(KeyEvent e) {
        if (e == null) {
            return null;
        }
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_ESCAPE) {
            return "<esc>";
        }
        if (code == KeyEvent.VK_ENTER) {
            return "<enter>";
        }
        if (code == KeyEvent.VK_TAB) {
            return "<tab>";
        }
        if (code == KeyEvent.VK_SPACE) {
            return "<space>";
        }
        if (code == KeyEvent.VK_BACK_SPACE) {
            return "<bs>";
        }
        if (code == KeyEvent.VK_DELETE) {
            return "<del>";
        }
        if (code == KeyEvent.VK_UP) {
            return "<up>";
        }
        if (code == KeyEvent.VK_DOWN) {
            return "<down>";
        }
        if (code == KeyEvent.VK_LEFT) {
            return "<left>";
        }
        if (code == KeyEvent.VK_RIGHT) {
            return "<right>";
        }
        char c = e.getKeyChar();
        if (e.isControlDown()) {
            String ctrlTarget = ctrlTarget(code, c);
            if (ctrlTarget != null) {
                return "<c-" + ctrlTarget + ">";
            }
        }
        if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
            return String.valueOf(c);
        }
        return null;
    }


    String ctrlTarget(int keyCode, char keyChar) {
        if (keyCode == KeyEvent.VK_ESCAPE) {
            return "esc";
        }
        if (keyCode == KeyEvent.VK_ENTER) {
            return "enter";
        }
        if (keyCode == KeyEvent.VK_TAB) {
            return "tab";
        }
        if (keyCode == KeyEvent.VK_UP) {
            return "up";
        }
        if (keyCode == KeyEvent.VK_DOWN) {
            return "down";
        }
        if (keyCode == KeyEvent.VK_LEFT) {
            return "left";
        }
        if (keyCode == KeyEvent.VK_RIGHT) {
            return "right";
        }
        if (keyCode == KeyEvent.VK_BACK_SPACE) {
            return "bs";
        }
        if (keyCode == KeyEvent.VK_DELETE) {
            return "del";
        }
        if (keyChar != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(keyChar)) {
            return String.valueOf(Character.toLowerCase(keyChar));
        }
        return null;
    }


    List<String> parseKeySequence(String mapping) {
        List<String> tokens = new ArrayList<>();
        if (mapping == null || mapping.isEmpty()) {
            return tokens;
        }
        int index = 0;
        while (index < mapping.length()) {
            char c = mapping.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '<') {
                int close = mapping.indexOf('>', index + 1);
                if (close > index + 1) {
                    tokens.add(mapping.substring(index, close + 1));
                    index = close + 1;
                    continue;
                }
            }
            tokens.add(String.valueOf(c));
            index++;
        }
        return tokens;
    }


    KeyEvent keyEventFromToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (token.length() == 1) {
            char c = token.charAt(0);
            int code = KeyEvent.getExtendedKeyCodeForChar(c);
            if (code == KeyEvent.VK_UNDEFINED) {
                code = 0;
            }
            return new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, now, 0, code, c);
        }
        if (!(token.startsWith("<") && token.endsWith(">"))) {
            return null;
        }

        String inner = token.substring(1, token.length() - 1).trim().toLowerCase();
        if (inner.isEmpty()) {
            return null;
        }

        if (inner.startsWith("c-") && inner.length() > 2) {
            KeyStrokeSpec ctrlSpec = keyStrokeSpec(inner.substring(2));
            if (ctrlSpec == null) {
                return null;
            }
            return new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, ctrlSpec.keyCode, ctrlSpec.keyChar);
        }

        KeyStrokeSpec spec = keyStrokeSpec(inner);
        if (spec == null) {
            return null;
        }
        return new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, now, 0, spec.keyCode, spec.keyChar);
    }


    KeyStrokeSpec keyStrokeSpec(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        switch (token) {
            case "esc":
                return new KeyStrokeSpec(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
            case "enter":
            case "cr":
                return new KeyStrokeSpec(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED);
            case "tab":
                return new KeyStrokeSpec(KeyEvent.VK_TAB, '\t');
            case "space":
                return new KeyStrokeSpec(KeyEvent.VK_SPACE, ' ');
            case "bs":
            case "backspace":
                return new KeyStrokeSpec(KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED);
            case "del":
            case "delete":
                return new KeyStrokeSpec(KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED);
            case "up":
                return new KeyStrokeSpec(KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED);
            case "down":
                return new KeyStrokeSpec(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED);
            case "left":
                return new KeyStrokeSpec(KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED);
            case "right":
                return new KeyStrokeSpec(KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED);
            case "lt":
                return new KeyStrokeSpec(KeyEvent.VK_UNDEFINED, '<');
            default:
                if (token.length() == 1) {
                    char c = token.charAt(0);
                    int code = KeyEvent.getExtendedKeyCodeForChar(c);
                    if (code == KeyEvent.VK_UNDEFINED) {
                        code = 0;
                    }
                    return new KeyStrokeSpec(code, c);
                }
                return null;
        }
    }


    void handleNormalMode(KeyEvent e) {
        char c = e.getKeyChar();
        int code = e.getKeyCode();

        if (editor.pendingTextObjectOperator != null) {
            editor.showMessage(editor.applyTextObjectOperator(editor.pendingTextObjectOperator, editor.pendingTextObjectModifier, c));
            editor.pendingTextObjectOperator = null;
            editor.pendingTextObjectModifier = null;
            return;
        }
        if (editor.pendingSurroundAction != null) {
            editor.showMessage(editor.handleSurroundPending(c));
            return;
        }

        // Handle pending keys (multi-key commands)
        if (editor.editorState.pendingKey != '\0') {
            handlePendingKey(c, code);
            return;
        }

        // Accumulate numeric prefix for COUNTgg without breaking 0 line-start
        if (Character.isDigit(c) && (!editor.editorState.pendingCount.isEmpty() || c != '0')) {
            editor.editorState.pendingCount += c;
            return;
        }

        if (!editor.editorState.pendingCount.isEmpty() && !supportsCountPrefix(e)) {
            editor.editorState.pendingCount = "";
        }

        if (editor.isInteractiveGitBufferActive() && (code == KeyEvent.VK_ENTER || c == 'o')) {
            editor.editorState.pendingCount = "";
            editor.showMessage(editor.openInteractiveGitSelection());
            return;
        }

        if (editor.isQuickfixBufferActive() && (code == KeyEvent.VK_ENTER || c == 'o')) {
            editor.editorState.pendingCount = "";
            editor.showMessage(editor.openQuickfixSelection());
            return;
        }

        if (editor.isTreePaneActive() && (code == KeyEvent.VK_ENTER || c == 'o')) {
            editor.editorState.pendingCount = "";
            editor.showMessage(editor.openTreeSelection());
            return;
        }

        // Mode switches
        if (c == 'i') {
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
            return;
        } else if (c == 'a') {
            editor.moveRight();
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
            return;
        } else if (c == 'A') {
            editor.moveLineEnd();
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
            return;
        } else if (c == 'I') {
            editor.moveLineIndentStart();
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
            return;
        } else if (c == 'o') {
            editor.openLineBelow();
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
            return;
        } else if (c == 'O') {
            editor.openLineAbove();
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
            return;
        } else if (c == 'v') {
            editor.setMode(EditorMode.VISUAL);
            int start = GraphemeBoundary.floor(editor.writingArea.getText(), editor.writingArea.getCaretPosition());
            editor.writingArea.setCaretPosition(start);
            editor.editorState.visualStartPos = start;
            return;
        } else if (c == 'V') {
            editor.setMode(EditorMode.VISUAL_LINE);
            editor.selectCurrentLine();
            return;
        } else if (c == 'R') {
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.REPLACE);
            return;
        } else if (c == ':') {
            editor.setMode(EditorMode.COMMAND);
            editor.editorState.commandBuffer = String.valueOf(c);
            editor.commandHistoryIndex = -1;
            editor.commandHistoryPrefix = editor.editorState.commandBuffer;
            editor.editorUiController.setCommandPromptText(editor.editorState.commandBuffer);
            editor.updateSubstitutePreview();
            return;
        } else if (c == '/' || c == '?') {
            editor.editorState.searchStartPos = editor.writingArea.getCaretPosition();
            editor.setMode(EditorMode.SEARCH);
            editor.editorState.searchForward = c == '/';
            editor.editorState.commandBuffer = String.valueOf(c);
            editor.commandHistoryIndex = -1;
            return;
        }

        // Navigation
        else if (code == KeyEvent.VK_UP || c == 'k') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveUp);
        } else if (code == KeyEvent.VK_DOWN || c == 'j') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveDown);
        } else if (code == KeyEvent.VK_LEFT || c == 'h') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveLeft);
        } else if (code == KeyEvent.VK_RIGHT || c == 'l') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveRight);
        }

        // Word movements
        else if (c == 'w') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveWordForward);
        } else if (c == 'b') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveWordBackward);
        } else if (c == 'e') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveWordEnd);
        } else if (c == 'W') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveWordForwardBig);
        } else if (c == 'B') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveWordBackwardBig);
        } else if (c == 'E') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveWordEndBig);
        }

        // Line movements
        else if (c == '0') {
            editor.moveLineStart();
            editor.editorState.pendingCount = "";
        } else if (c == '^') {
            editor.moveLineFirstNonBlank();
            editor.editorState.pendingCount = "";
        } else if (c == '$') {
            editor.moveLineEnd();
            editor.editorState.pendingCount = "";
        }

        // File movements
        else if (c == 'g') {
            setPendingKeyWithHint('g');
        } else if (c == 'G') {
            int count = editor.consumePendingCount();
            if (count > 1) {
                editor.showMessage(editor.gotoLine(count));
            } else {
                editor.moveFileEnd();
            }
        } else if (c == 'q') {
            if (editor.recordingRegister != null) {
                editor.registerManager.setMacro(editor.recordingRegister, editor.macroBuffer);
                editor.lastMacroRegister = editor.recordingRegister;
                editor.showMessage("Recorded macro to @" + editor.recordingRegister);
                editor.recordingRegister = null;
                editor.macroBuffer = new ArrayList<>();
            } else {
                setPendingKeyWithHint('q');
            }
            return;
        } else if (c == '@') {
            setPendingKeyWithHint('@');
            return;
        } else if (c == '"') {
            setPendingKeyWithHint('"');
        } else if (c == 'm' || c == '\'' || c == '`') {
            setPendingKeyWithHint(c);
        } else if (c == 'f' || c == 'F' || c == 't' || c == 'T' || c == '>' || c == '<' || c == '=' || c == 'r') {
            setPendingKeyWithHint(c);
        } else if (c == 'z') {
            setPendingKeyWithHint('z');
        } else if (c == ']') {
            setPendingKeyWithHint(']');
        } else if (c == '[') {
            setPendingKeyWithHint('[');
        } else if (c == '{') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveParagraphBackward);
        } else if (c == '}') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveParagraphForward);
        } else if (c == '(') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveSentenceBackward);
        } else if (c == ')') {
            editor.repeatAction(editor.consumePendingCount(), editor::moveSentenceForward);
        } else if (c == '%') {
            int count = editor.consumePendingCount();
            if (count > 1) {
                editor.moveToFilePercent(count);
            } else {
                editor.moveMatchingBracket();
            }
        } else if (c == 'H') {
            editor.moveToScreenPosition('H');
            editor.editorState.pendingCount = "";
        } else if (c == 'M') {
            editor.moveToScreenPosition('M');
            editor.editorState.pendingCount = "";
        } else if (c == 'L') {
            editor.moveToScreenPosition('L');
            editor.editorState.pendingCount = "";
        }

        // Clipboard operations
        else if (c == 'y') {
            setPendingKeyWithHint('y');
        } else if (c == 'd') {
            setPendingKeyWithHint('d');
        } else if (c == 'c') {
            setPendingKeyWithHint('c');
        } else if (c == 'x') {
            int count = editor.consumePendingCount();
            StringBuilder deleted = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String d = editor.clipboardManager.deleteChar(editor.writingArea);
                if (d.isEmpty()) break;
                deleted.append(d);
            }
            if (deleted.length() > 0) {
                editor.lastCommand = "x";
                editor.storeDelete(editor.consumePendingRegister(), deleted.toString(), false);
                editor.markModified();
            }
        } else if (c == 'X') {
            int count = editor.consumePendingCount();
            StringBuilder deleted = new StringBuilder();
            for (int i = 0; i < count; i++) {
                int pos = editor.writingArea.getCaretPosition();
                if (pos <= 0) break;
                String text = editor.writingArea.getText();
                GraphemeEditRange.Range range = GraphemeEditRange.previous(text, pos);
                if (range.empty()) break;
                deleted.insert(0, text, range.start(), range.end());
                editor.writingArea.replaceRange("", range.start(), range.end());
                editor.writingArea.setCaretPosition(range.start());
            }
            if (deleted.length() > 0) {
                editor.storeDelete(editor.consumePendingRegister(), deleted.toString(), false);
                editor.markModified();
            }
        } else if (c == 's') {
            int count = editor.consumePendingCount();
            StringBuilder deleted = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String d = editor.clipboardManager.deleteChar(editor.writingArea);
                if (d.isEmpty()) break;
                deleted.append(d);
            }
            if (deleted.length() > 0) {
                editor.storeDelete(editor.consumePendingRegister(), deleted.toString(), false);
                editor.markModified();
            }
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
        } else if (c == 'S') {
            editor.editorState.pendingCount = "";
            editor.lastCommand = "S";
            editor.storeDelete(editor.consumePendingRegister(), editor.clipboardManager.deleteLine(editor.writingArea), true);
            editor.markModified();
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
        } else if (c == 'Y') {
            editor.editorState.pendingCount = "";
            editor.showMessage(editor.yankToEndOfLine());
        } else if (c == 'p') {
            int count = editor.consumePendingCount();
            for (int i = 0; i < count; i++) {
                editor.pasteFromRegister(false);
            }
            editor.editorState.pendingCount = "";
        } else if (c == 'P') {
            int count = editor.consumePendingCount();
            for (int i = 0; i < count; i++) {
                editor.pasteFromRegister(true);
            }
            editor.editorState.pendingCount = "";
        } else if (c == 'D') {
            editor.editorState.pendingCount = "";
            editor.lastCommand = "D";
            editor.storeDelete(editor.consumePendingRegister(), editor.clipboardManager.deleteToEndOfLine(editor.writingArea), false);
            editor.markModified();
        } else if (c == 'C') {
            editor.editorState.pendingCount = "";
            editor.lastCommand = "C";
            editor.storeDelete(editor.consumePendingRegister(), editor.clipboardManager.deleteToEndOfLine(editor.writingArea), false);
            editor.markModified();
            editor.lastInsertedText = "";
            editor.setMode(EditorMode.INSERT);
        }

        // Undo/Redo
        else if (c == 'u') {
            editor.editorState.pendingCount = "";
            if (editor.undoManager.canUndo()) {
                editor.undoManager.undo();
            }
        } else if (e.isControlDown() && c == 'r') {
            editor.editorState.pendingCount = "";
            if (editor.undoManager.canRedo()) {
                editor.undoManager.redo();
            }
        }

        // Search navigation
        else if (c == 'n') {
            editor.editorState.pendingCount = "";
            String result = editor.searchManager.nextMatch();
            editor.showMessage(result);
        } else if (c == 'N') {
            editor.editorState.pendingCount = "";
            String result = editor.searchManager.prevMatch();
            editor.showMessage(result);
        } else if (c == '*') {
            editor.editorState.pendingCount = "";
            String result = editor.searchWordUnderCursor(true);
            editor.showMessage(result);
        } else if (c == '#') {
            editor.editorState.pendingCount = "";
            String result = editor.searchWordUnderCursor(false);
            editor.showMessage(result);
        } else if (c == ';') {
            editor.editorState.pendingCount = "";
            editor.showMessage(editor.repeatFind(false));
        } else if (c == ',') {
            editor.editorState.pendingCount = "";
            editor.showMessage(editor.repeatFind(true));
        }

        // Repeat last command
        else if (c == '.') {
            editor.editorState.pendingCount = "";
            editor.repeatLastCommand();
        } else if (c == 'J') {
            editor.editorState.pendingCount = "";
            editor.joinCurrentLine(true);
        }

        // Ctrl combinations
        else if (e.isControlDown()) {
            if (c == 'w' || code == KeyEvent.VK_W) {
                editor.editorState.pendingCount = "";
                setPendingKeyWithHint('\u0017');
                return;
            } else if (c == 'p' || code == KeyEvent.VK_P) {
                editor.editorState.pendingCount = "";
                editor.showMessage(editor.showFileFinder());
            } else if (c == 'n' || code == KeyEvent.VK_N) {
                editor.editorState.pendingCount = "";
                editor.showMessage(editor.showLspCompletionStatus());
            } else if (c == 'o' || code == KeyEvent.VK_O) {
                editor.editorState.pendingCount = "";
                editor.jumpBack();
            } else if (c == 'i' || code == KeyEvent.VK_I) {
                editor.editorState.pendingCount = "";
                editor.jumpForward();
            } else if (c == 'd' || code == KeyEvent.VK_D) {
                editor.editorState.pendingCount = "";
                if (e.isShiftDown()) {
                    editor.addCursorAtNextMatch();
                } else {
                    editor.scrollHalfPageDown();
                }
            } else if (c == 'u' || code == KeyEvent.VK_U) {
                editor.editorState.pendingCount = "";
                editor.scrollHalfPageUp();
            } else if (c == 'f' || code == KeyEvent.VK_F) {
                editor.editorState.pendingCount = "";
                editor.scrollFullPageDown();
            } else if (c == 'b' || code == KeyEvent.VK_B) {
                editor.editorState.pendingCount = "";
                editor.scrollFullPageUp();
            } else if (c == 'e' || code == KeyEvent.VK_E) {
                editor.editorState.pendingCount = "";
                editor.scrollLineDown();
            } else if (c == 'y' || code == KeyEvent.VK_Y) {
                editor.editorState.pendingCount = "";
                editor.scrollLineUp();
            } else if (c == 'g' || code == KeyEvent.VK_G) {
                editor.editorState.pendingCount = "";
                editor.showMessage(editor.showFileInfo());
            } else if (c == 'v' || code == KeyEvent.VK_V) {
                editor.editorState.pendingCount = "";
                editor.enterVisualBlockMode();
                return;
            }
        }

        // TAB: markdown fold cycling on heading lines
        else if (code == KeyEvent.VK_TAB) {
            editor.editorState.pendingCount = "";
            FileBuffer buf = editor.getCurrentBuffer();
            if (buf != null && buf.getFileType() == FileType.MARKDOWN) {
                if (e.isShiftDown()) {
                    editor.showMessage(editor.globalFoldCycle());
                } else {
                    editor.showMessage(editor.toggleFoldAtCursor());
                }
            }
        }

        // Alt combinations for multi-cursor
        else if (e.isAltDown()) {
            if (code == KeyEvent.VK_J) {
                if (e.isShiftDown()) editor.addCursorAbove();
                else editor.addCursorBelow();
            } else if (code == KeyEvent.VK_K) {
                if (e.isShiftDown()) editor.addCursorBelow();
                else editor.addCursorAbove();
            }
        }
        // Escape (no-op in normal mode, but clear any messages)
        else if (code == KeyEvent.VK_ESCAPE) {
            editor.editorState.pendingCount = "";
            editor.editorState.pendingKey = '\0';
            editor.clearExtraCursors();
            editor.showMessage("Already in normal mode");
        }
    }


    boolean supportsCountPrefix(KeyEvent e) {
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (e.isControlDown()) {
            return c == 'd' || c == 'u' || code == KeyEvent.VK_D || code == KeyEvent.VK_U;
        }

        if (code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN || code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT) {
            return true;
        }

        switch (c) {
            case 'h':
            case 'j':
            case 'k':
            case 'l':
            case 'w':
            case 'b':
            case 'e':
            case 'W':
            case 'B':
            case 'E':
            case '0':
            case '^':
            case '$':
            case 'g':
            case 'G':
            case '{':
            case '}':
            case '(':
            case ')':
            case '%':
            case 'n':
            case 'N':
            case ';':
            case ',':
            case 'd':
            case 'y':
            case 'c':
            case 'x':
            case 'X':
            case 's':
            case 'S':
            case 'p':
            case 'P':
            case 'D':
            case 'C':
            case 'Y':
            case 'J':
            case 'f':
            case 'F':
            case 't':
            case 'T':
            case 'r':
                return true;
            default:
                return false;
        }
    }


    void setPendingKeyWithHint(char pendingKey) {
        editor.editorState.pendingKey = pendingKey;
        showWhichKeyHint(pendingKey);
    }


    void showWhichKeyHint(char pendingKey) {
        if (!editor.whichKeyHintsEnabled) {
            return;
        }
        String hint = whichKeyHintText(pendingKey);
        if (hint != null && !hint.isBlank()) {
            editor.showMessage(hint);
        }
    }


    String whichKeyHintText(char pendingKey) {
        switch (pendingKey) {
            case 'g':
                return "g: gg top, gq format, gf file, gx url, g; prev change, g, next change";
            case 'z':
                return "z: zt top, zz center, zb bottom, za toggle fold, zM fold all, zR unfold all";
            case '\u0017':
                return "Ctrl-w: h/j/k/l move, s/v split, c close, w cycle, = equalize, +/- resize";
            case 'd':
                return "d: dd line, dw word, d{motion}, ds surround";
            case 'y':
                return "y: yy line, yw word, y{motion}, ys surround";
            case 'c':
                return "c: cc line, cw word, c{motion}, cs surround";
            case 'f':
            case 'F':
            case 't':
            case 'T':
                return pendingKey + ": enter target character";
            case 'r':
                return "r: replace character under cursor";
            case '"':
                return "\": choose register";
            case 'q':
                return "q: choose register to start macro recording";
            case '@':
                return "@: choose register to replay macro";
            case '>':
            case '<':
                return pendingKey + ": repeat for line op, or use with motion";
            case '[':
            case ']':
                return pendingKey + ": heading navigation";
            default:
                return null;
        }
    }


    void handlePendingKey(char c, int code) {
        if (editor.editorState.pendingKey == 'g') {
            if (c == 'g') {
                if (editor.editorState.pendingCount.isEmpty()) {
                    editor.moveFileStart();
                } else {
                    editor.showMessage(editor.gotoLine(Integer.parseInt(editor.editorState.pendingCount)));
                }
            } else if (c == 'q') {
                editor.showMessage(editor.formatParagraph());
            } else if (c == 'j') {
                editor.moveDisplayLineDown();
            } else if (c == 'k') {
                editor.moveDisplayLineUp();
            } else if (c == 'e') {
                editor.repeatAction(editor.consumePendingCount(), editor::moveWordEndBackward);
            } else if (c == 'E') {
                editor.repeatAction(editor.consumePendingCount(), editor::moveWordEndBackwardBig);
            } else if (c == '0') {
                editor.moveLineStart();
                editor.editorState.pendingCount = "";
            } else if (c == '$') {
                editor.moveLineEnd();
                editor.editorState.pendingCount = "";
            } else if (c == '_') {
                editor.moveLineLastNonBlank();
                editor.editorState.pendingCount = "";
            } else if (c == 'J') {
                editor.joinCurrentLine(false);
            } else if (c == ';') {
                editor.changePrev();
            } else if (c == ',') {
                editor.changeNext();
            } else if (c == 'c') {
                editor.editorState.pendingKey = '\u0007';
                return;
            } else if (c == 'f') {
                FileBuffer buf = editor.getCurrentBuffer();
                if (buf != null && buf.getFileType() == FileType.MARKDOWN) {
                    editor.showMessage(editor.goToMarkdownLink());
                } else {
                    editor.showMessage(editor.goToFileUnderCursor());
                }
            } else if (c == 'x') {
                editor.showMessage(editor.openBrowserUrl());
            } else if (c == 'O') {
                editor.showMessage(editor.showOutline());
            } else if (c == 'v') {
                if (editor.editorState.lastVisualStart >= 0 && editor.editorState.lastVisualEnd >= 0
                        && editor.editorState.lastVisualStart <= editor.writingArea.getText().length()
                        && editor.editorState.lastVisualEnd <= editor.writingArea.getText().length()) {
                    EditorMode vm = editor.editorState.lastVisualMode != null ? editor.editorState.lastVisualMode : EditorMode.VISUAL;
                    editor.editorState.visualStartPos = GraphemeBoundary.floor(editor.writingArea.getText(), editor.editorState.lastVisualStart);
                    editor.setMode(vm);
                    editor.writingArea.setCaretPosition(GraphemeBoundary.ceiling(editor.writingArea.getText(), editor.editorState.lastVisualEnd));
                    if (vm == EditorMode.VISUAL) {
                        selectGraphemeRange(editor.editorState.lastVisualStart, editor.editorState.lastVisualEnd);
                    } else {
                        editor.writingArea.setSelectionStart(editor.editorState.lastVisualStart);
                        editor.writingArea.setSelectionEnd(editor.editorState.lastVisualEnd);
                    }
                } else {
                    editor.showMessage("No previous visual selection");
                }
            }
            editor.editorState.pendingKey = '\0';
            editor.editorState.pendingCount = "";
        } else if (editor.editorState.pendingKey == 'y') {
            if (c == 'y') {
                int count = editor.consumePendingCount();
                editor.lastCommand = "yy";
                try {
                    int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
                    int startOffset = editor.writingArea.getLineStartOffset(line);
                    int endLine = Math.min(line + count, editor.writingArea.getLineCount()) - 1;
                    int endOffset = editor.writingArea.getLineEndOffset(endLine);
                    String yanked = editor.writingArea.getText(startOffset, endOffset - startOffset);
                    editor.clipboardManager.yankSelection(yanked);
                    editor.storeYank(editor.consumePendingRegister(), yanked, true);
                    editor.showMessage(count > 1 ? count + " lines yanked" : "Line yanked");
                } catch (BadLocationException ex) {
                    editor.showMessage("Line yanked");
                }
            } else if (c == 's') {
                editor.pendingSurroundAction = 'y';
                editor.editorState.pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                editor.pendingTextObjectOperator = 'y';
                editor.pendingTextObjectModifier = c;
                editor.editorState.pendingKey = '\0';
                return;
            } else if (c == 'g') {
                editor.editorState.pendingKey = 'Y';
                return;
            } else {
                editor.showMessage(editor.applyMotionOperator('y', String.valueOf(c)));
            }
            editor.editorState.pendingKey = '\0';
            editor.editorState.pendingCount = "";
        } else if (editor.editorState.pendingKey == 'd') {
            if (c == 'd') {
                int count = editor.consumePendingCount();
                editor.lastCommand = "dd";
                try {
                    int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
                    int startOffset = editor.writingArea.getLineStartOffset(line);
                    int endLine = Math.min(line + count, editor.writingArea.getLineCount()) - 1;
                    int endOffset = editor.writingArea.getLineEndOffset(endLine);
                    String deleted = editor.writingArea.getText(startOffset, endOffset - startOffset);
                    editor.storeDelete(editor.consumePendingRegister(), deleted, true);
                    editor.writingArea.replaceRange("", startOffset, endOffset);
                    editor.writingArea.setCaretPosition(Math.min(startOffset, editor.writingArea.getText().length()));
                    editor.markModified();
                    editor.showMessage(count > 1 ? count + " lines deleted" : "Line deleted");
                } catch (BadLocationException ex) {
                    editor.showMessage("Line deleted");
                }
            } else if (c == 's') {
                editor.pendingSurroundAction = 'd';
                editor.editorState.pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                editor.pendingTextObjectOperator = 'd';
                editor.pendingTextObjectModifier = c;
                editor.editorState.pendingKey = '\0';
                return;
            } else if (c == 'g') {
                editor.editorState.pendingKey = 'D';
                return;
            } else if (c == 'w') {
                int count = editor.consumePendingCount();
                editor.lastCommand = "dw";
                StringBuilder deleted = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String d = editor.clipboardManager.deleteWord(editor.writingArea);
                    if (d.isEmpty()) break;
                    deleted.append(d);
                }
                if (deleted.length() > 0) {
                    editor.storeDelete(editor.consumePendingRegister(), deleted.toString(), false);
                    editor.markModified();
                }
                editor.showMessage(count > 1 ? count + " words deleted" : "Word deleted");
            } else {
                editor.showMessage(editor.applyMotionOperator('d', String.valueOf(c)));
            }
            editor.editorState.pendingKey = '\0';
            editor.editorState.pendingCount = "";
        } else if (editor.editorState.pendingKey == 'c') {
            if (c == 'c') {
                int count = editor.consumePendingCount();
                editor.lastCommand = "cc";
                try {
                    int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
                    int startOffset = editor.writingArea.getLineStartOffset(line);
                    int endLine = Math.min(line + count, editor.writingArea.getLineCount()) - 1;
                    int endOffset = editor.writingArea.getLineEndOffset(endLine);
                    String deleted = editor.writingArea.getText(startOffset, endOffset - startOffset);
                    editor.storeDelete(editor.consumePendingRegister(), deleted, true);
                    editor.writingArea.replaceRange("", startOffset, endOffset);
                    editor.writingArea.setCaretPosition(Math.min(startOffset, editor.writingArea.getText().length()));
                    editor.markModified();
                } catch (BadLocationException ex) {}
                editor.lastInsertedText = "";
                editor.setMode(EditorMode.INSERT);
            } else if (c == 's') {
                editor.pendingSurroundAction = 'c';
                editor.editorState.pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                editor.pendingTextObjectOperator = 'c';
                editor.pendingTextObjectModifier = c;
                editor.editorState.pendingKey = '\0';
                return;
            } else if (c == 'g') {
                editor.editorState.pendingKey = 'C';
                return;
            } else if (c == 'w') {
                int count = editor.consumePendingCount();
                editor.lastCommand = "cw";
                StringBuilder deleted = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String d = editor.clipboardManager.deleteWord(editor.writingArea);
                    if (d.isEmpty()) break;
                    deleted.append(d);
                }
                if (deleted.length() > 0) {
                    editor.storeDelete(editor.consumePendingRegister(), deleted.toString(), false);
                    editor.markModified();
                }
                editor.lastInsertedText = "";
                editor.setMode(EditorMode.INSERT);
            } else {
                editor.showMessage(editor.applyMotionOperator('c', String.valueOf(c)));
            }
            editor.editorState.pendingKey = '\0';
            editor.editorState.pendingCount = "";
        } else if (editor.editorState.pendingKey == 'q') {
            editor.recordingRegister = c;
            editor.macroBuffer = new ArrayList<>();
            editor.editorState.pendingKey = '\0';
            editor.showMessage("recording @" + c);
        } else if (editor.editorState.pendingKey == '@') {
            if (c == '@') {
                editor.showMessage(editor.playMacro(editor.lastMacroRegister));
            } else {
                editor.showMessage(editor.playMacro(c));
            }
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == '"') {
            editor.editorState.pendingRegister = c;
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == 'm') {
            FileBuffer buffer = editor.getCurrentBuffer();
            if (buffer != null) {
                buffer.setMark(c, editor.writingArea.getCaretPosition());
                editor.showMessage("Mark set: " + c);
            }
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == '\'' || editor.editorState.pendingKey == '`') {
            FileBuffer buffer = editor.getCurrentBuffer();
            if (buffer != null) {
                Integer offset = buffer.getMark(c);
                if (offset != null) {
                    editor.recordJumpPosition();
                    if (editor.editorState.pendingKey == '\'') {
                        try {
                            int line = editor.writingArea.getLineOfOffset(Math.min(offset, editor.writingArea.getText().length()));
                            editor.writingArea.setCaretPosition(editor.writingArea.getLineStartOffset(line));
                        } catch (BadLocationException e) {
                            editor.writingArea.setCaretPosition(Math.min(offset, editor.writingArea.getText().length()));
                        }
                    } else {
                        editor.writingArea.setCaretPosition(Math.min(offset, editor.writingArea.getText().length()));
                    }
                } else {
                    editor.showMessage("Mark not set: " + c);
                }
            }
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == 'f' || editor.editorState.pendingKey == 'F' || editor.editorState.pendingKey == 't' || editor.editorState.pendingKey == 'T') {
            editor.showMessage(editor.findCharacter(editor.editorState.pendingKey, c));
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == 'r') {
            editor.showMessage(editor.replaceCharacter(c));
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == '>' || editor.editorState.pendingKey == '<' || editor.editorState.pendingKey == '=') {
            if (c == editor.editorState.pendingKey) {
                FileBuffer buf = editor.getCurrentBuffer();
                if (buf != null && buf.getFileType() == FileType.MARKDOWN && (editor.editorState.pendingKey == '>' || editor.editorState.pendingKey == '<')) {
                    editor.showMessage(editor.markdownHeadingShift(editor.editorState.pendingKey == '>'));
                } else {
                    editor.showMessage(editor.applyLineOperator(editor.editorState.pendingKey));
                }
            } else if (c == 'r' && (editor.editorState.pendingKey == '>' || editor.editorState.pendingKey == '<')) {
                FileBuffer buf = editor.getCurrentBuffer();
                if (buf != null && buf.getFileType() == FileType.MARKDOWN) {
                    editor.showMessage(editor.markdownSubtreeShift(editor.editorState.pendingKey == '>'));
                }
            }
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == 'D' || editor.editorState.pendingKey == 'C' || editor.editorState.pendingKey == 'Y') {
            char operator = editor.editorState.pendingKey == 'D' ? 'd' : editor.editorState.pendingKey == 'C' ? 'c' : 'y';
            editor.showMessage(editor.applyMotionOperator(operator, "g" + c));
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == '\u0017') {
            switch (c) {
                case 's':
                    editor.showMessage(editor.splitWindow(false));
                    break;
                case 'v':
                    editor.showMessage(editor.splitWindow(true));
                    break;
                case 'c':
                    editor.showMessage(editor.closeActiveWindow());
                    break;
                case 'h':
                    editor.showMessage(editor.focusWindowDirection(-1, 0));
                    break;
                case 'j':
                    editor.showMessage(editor.focusWindowDirection(0, 1));
                    break;
                case 'k':
                    editor.showMessage(editor.focusWindowDirection(0, -1));
                    break;
                case 'l':
                    editor.showMessage(editor.focusWindowDirection(1, 0));
                    break;
                case 'w':
                    editor.showMessage(editor.cycleWindowFocus());
                    break;
                case '=':
                    editor.showMessage(editor.equalizeWindows());
                    break;
                case '+':
                    editor.showMessage(editor.resizeActiveWindow(0.05));
                    break;
                case '-':
                    editor.showMessage(editor.resizeActiveWindow(-0.05));
                    break;
                case '>':
                    editor.showMessage(editor.resizeActiveWindow(0.05));
                    break;
                case '<':
                    editor.showMessage(editor.resizeActiveWindow(-0.05));
                    break;
                default:
                    break;
            }
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == 'z') {
            if (c == 't') {
                editor.scrollCurrentLineTo('t');
            } else if (c == 'z') {
                editor.scrollCurrentLineTo('z');
            } else if (c == 'b') {
                editor.scrollCurrentLineTo('b');
            } else if (c == 'a') {
                editor.showMessage(editor.toggleFoldAtCursor());
            } else if (c == 'M') {
                editor.showMessage(editor.foldAll());
            } else if (c == 'R') {
                editor.showMessage(editor.unfoldAll());
            }
            editor.editorState.pendingKey = '\0';
        } else if (editor.editorState.pendingKey == ']') {
            if (c == ']') {
                editor.showMessage(editor.navigateHeading(true));
            } else if (c >= '1' && c <= '6') {
                editor.showMessage(editor.navigateHeadingAtLevel(true, c - '0'));
            }
            editor.editorState.pendingKey = '\0';
            editor.editorState.pendingCount = "";
        } else if (editor.editorState.pendingKey == '[') {
            if (c == '[') {
                editor.showMessage(editor.navigateHeading(false));
            } else if (c >= '1' && c <= '6') {
                editor.showMessage(editor.navigateHeadingAtLevel(false, c - '0'));
            }
            editor.editorState.pendingKey = '\0';
            editor.editorState.pendingCount = "";
        } else if (editor.editorState.pendingKey == '\u0007') {
            // gc pending state: gcc = comment current line(s), gc{motion} = comment motion range
            if (c == 'c') {
                int count = editor.consumePendingCount();
                try {
                    int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
                    int endLine = Math.min(line + count, editor.writingArea.getLineCount()) - 1;
                    editor.toggleCommentLineRange(line, endLine);
                } catch (BadLocationException ignored) {}
            } else {
                MotionRange range = editor.resolveMotionRange(String.valueOf(c));
                if (range != null) {
                    try {
                        int startLine = editor.writingArea.getLineOfOffset(range.start);
                        int endLine = editor.writingArea.getLineOfOffset(range.end > range.start ? range.end - 1 : range.end);
                        editor.toggleCommentLineRange(startLine, endLine);
                    } catch (BadLocationException ignored) {}
                }
            }
            editor.editorState.pendingKey = '\0';
            editor.editorState.pendingCount = "";
        }

    }


    void handleInsertMode(KeyEvent e) {
        int code = e.getKeyCode();
        if ((code == KeyEvent.VK_TAB || (e.isShiftDown() && code == KeyEvent.VK_TAB)) && moveSnippetPlaceholder(e.isShiftDown() ? -1 : 1)) {
            e.consume();
            return;
        }
        if (isCompletionPopupVisible()) {
            if (code == KeyEvent.VK_DOWN || (e.isControlDown() && code == KeyEvent.VK_N)) {
                completionPopupNavigate(1); e.consume(); return;
            } else if (code == KeyEvent.VK_UP || (e.isControlDown() && code == KeyEvent.VK_P)) {
                completionPopupNavigate(-1); e.consume(); return;
            } else if (code == KeyEvent.VK_TAB || code == KeyEvent.VK_ENTER) {
                completionPopupAccept(); e.consume(); return;
            } else if (code == KeyEvent.VK_ESCAPE) {
                dismissCompletionPopup(); e.consume(); return;
            } else if (!e.isControlDown() && !e.isAltDown() && !e.isMetaDown()
                && editor.configManager.getLspCompletionCommitCharacters() && completionPopupCommits(e.getKeyChar())) {
                completionPopupAccept();
            }
        }
        if (completionJobId >= 0 || isCompletionPopupVisible()) {
            dismissCompletionPopup();
        }
        if (signatureHelpJobId >= 0 || isSignatureHelpVisible()) {
            dismissSignatureHelp();
        }
        if (code == KeyEvent.VK_ESCAPE || (e.isControlDown() && code == KeyEvent.VK_OPEN_BRACKET)) {
            dismissCompletionPopup();
            editor.registerManager.updateLastInserted(editor.lastInsertedText);
            editor.setMode(EditorMode.NORMAL);
            // Move cursor back one position (Vim behavior)
            int pos = editor.writingArea.getCaretPosition();
            if (pos > 0) {
                editor.writingArea.setCaretPosition(pos - 1);
            }
            return;
        }

        if ((code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE) && !editor.extraSelections.isEmpty()) {
            if (code == KeyEvent.VK_BACK_SPACE) {
                editor.applyMultiCursorBackspace();
            } else {
                editor.applyMultiCursorDelete();
            }
        }
        if ((code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE) && editor.extraSelections.isEmpty()
            && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
            String text = editor.writingArea.getText();
            int selectionStart = editor.writingArea.getSelectionStart();
            int selectionEnd = editor.writingArea.getSelectionEnd();
            GraphemeEditRange.Range range = selectionStart == selectionEnd
                ? code == KeyEvent.VK_BACK_SPACE
                    ? GraphemeEditRange.previous(text, editor.writingArea.getCaretPosition())
                    : GraphemeEditRange.next(text, editor.writingArea.getCaretPosition())
                : GraphemeEditRange.selection(text, selectionStart, selectionEnd);
            if (!range.empty()) {
                editor.writingArea.replaceRange("", range.start(), range.end());
                editor.writingArea.setCaretPosition(range.start());
                editor.markModified();
            }
            e.consume();
            return;
        }
        if (code == KeyEvent.VK_BACK_SPACE && editor.configManager.getAutoPairs()) {
            String text = editor.writingArea.getText();
            int pos = editor.writingArea.getCaretPosition();
            if (pos > 0 && pos < text.length()) {
                char before = text.charAt(pos - 1);
                char after = text.charAt(pos);
                Character expected = editor.autoPairCloser(before);
                if (expected != null && expected == after) {
                    editor.writingArea.replaceRange("", pos, pos + 1); // delete closing char
                }
            }
        }
        if (e.isControlDown()) {
            if (code == KeyEvent.VK_W || e.getKeyChar() == 'w') {
                // Ctrl+w: delete word backward
                editor.deleteWordBackwardInsert();
                return;
            } else if (code == KeyEvent.VK_U || e.getKeyChar() == 'u') {
                // Ctrl+u: delete to start of line
                editor.deleteToLineStartInsert();
                return;
            } else if (code == KeyEvent.VK_O || e.getKeyChar() == 'o') {
                // Ctrl+o: execute one normal mode command then return to insert
                editor.insertNormalOneShot = true;
                editor.setMode(EditorMode.NORMAL);
                return;
            } else if (code == KeyEvent.VK_J || e.getKeyChar() == 'j') {
                // Ctrl+j: snippet expand (or code fence language complete in markdown)
                FileBuffer buf = editor.getCurrentBuffer();
                if (buf != null && buf.getFileType() == FileType.MARKDOWN && editor.isOnCodeFenceLine()) {
                    editor.showMessage(editor.completeCodeFenceLanguage());
                } else {
                    editor.showMessage(editor.expandSnippetAtCursor());
                }
                return;
            } else if (code == KeyEvent.VK_N || e.getKeyChar() == 'n') {
                showInlineCompletion();
                return;
            }
        }

        if (!e.isControlDown() && !e.isAltDown()) {
            char c = e.getKeyChar();
            FileBuffer currentBuf = editor.getCurrentBuffer();
            boolean isMarkdown = currentBuf != null && currentBuf.getFileType() == FileType.MARKDOWN;
            if (c == '\t' && isMarkdown && editor.isOnTableLine()) {
                // TAB in markdown table: move to next cell
                editor.showMessage(editor.markdownTableNextCell(e.isShiftDown()));
                e.consume();
                return;
            } else if (c == '\t' && editor.configManager.getExpandTab()) {
                editor.writingArea.replaceSelection(" ".repeat(editor.writingArea.getTabSize()));
                editor.lastInsertedText += " ".repeat(editor.writingArea.getTabSize());
                e.consume();
            } else if (c == '\n') {
                if (isMarkdown) {
                    String continued = editor.handleMarkdownEnter();
                    if (continued != null) {
                        e.consume();
                        return;
                    }
                }
                if (editor.configManager.getAutoIndent()) {
                    String indent = editor.currentLineIndentation();
                    SwingUtilities.invokeLater(() -> editor.writingArea.insert(indent, editor.writingArea.getCaretPosition()));
                    editor.lastInsertedText += "\n" + indent;
                }
            } else if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
                if (editor.configManager.getAutoPairs()) {
                    Character closer = editor.autoPairCloser(c);
                    if (closer != null) {
                        // auto-insert closing pair after the char is processed
                        final char cl = closer;
                        SwingUtilities.invokeLater(() -> {
                            int p = editor.writingArea.getCaretPosition();
                            editor.writingArea.insert(String.valueOf(cl), p);
                            editor.writingArea.setCaretPosition(p);
                        });
                    } else if (editor.isClosingPairChar(c)) {
                        // skip over if next char matches
                        String text = editor.writingArea.getText();
                        int p = editor.writingArea.getCaretPosition();
                        if (p < text.length() && text.charAt(p) == c) {
                            editor.writingArea.setCaretPosition(p + 1);
                            editor.suppressNextTypedChar = true;
                            e.consume();
                            editor.lastInsertedText += c;
                            return;
                        }
                    }
                }
                editor.lastInsertedText += c;
                editor.applyMultiCursorInsert(c);
            }
        }
    }


    void handleVisualMode(KeyEvent e) {
        char c = e.getKeyChar();
        int code = e.getKeyCode();
        boolean lineMode = editor.editorState.mode == EditorMode.VISUAL_LINE;

        if (code == KeyEvent.VK_ESCAPE) {
            editor.clearExtraCursors();
            editor.setMode(EditorMode.NORMAL);
            editor.writingArea.setSelectionStart(editor.writingArea.getCaretPosition());
            editor.writingArea.setSelectionEnd(editor.writingArea.getCaretPosition());
            return;
        }

        // Ctrl+d: add cursor at next match of selection
        if (e.isControlDown() && (code == KeyEvent.VK_D || c == 'd')) {
            editor.addCursorAtNextMatch();
            return;
        }

        // Handle pending keys
        if (editor.editorState.pendingKey == 'g') {
            editor.editorState.pendingKey = '\0';
            if (c == 'c') {
                editor.toggleCommentSelection();
            }
            editor.setMode(EditorMode.NORMAL);
            return;
        } else if (editor.editorState.pendingKey == 'S') {
            editor.editorState.pendingKey = '\0';
            editor.surroundVisualSelection(c);
            editor.setMode(EditorMode.NORMAL);
            return;
        }

        // Update selection as cursor moves
        if (lineMode) {
            editor.normalizeVisualLineCaretForMotion();
        }

        // Navigation (same as normal mode)
        if (code == KeyEvent.VK_UP || c == 'k') editor.moveUp();
        else if (code == KeyEvent.VK_DOWN || c == 'j') editor.moveDown();
        else if (code == KeyEvent.VK_LEFT || c == 'h') editor.moveLeft();
        else if (code == KeyEvent.VK_RIGHT || c == 'l') editor.moveRight();
        else if (c == 'w') editor.moveWordForward();
        else if (c == 'b') editor.moveWordBackward();
        else if (c == 'e') editor.moveWordEnd();
        else if (c == '0') editor.moveLineStart();
        else if (c == '$') editor.moveLineEnd();

        // Update selection
        int newPos = editor.writingArea.getCaretPosition();
        boolean blockMode = editor.editorState.mode == EditorMode.VISUAL_BLOCK;
        if (blockMode) {
            // block selection is virtual; don't use JTextArea selection
            editor.writingArea.repaint();
        } else if (lineMode) {
            editor.selectLineRange(editor.editorState.visualStartPos, newPos);
        } else {
            selectGraphemeRange(editor.editorState.visualStartPos, newPos);
        }

        // Operations on selection
        if (c == 'g') {
            editor.editorState.pendingKey = 'g';
            return;
        } else if (c == 'S') {
            editor.editorState.pendingKey = 'S';
            return;
        } else if (c == 'y') {
            if (blockMode) { editor.yankVisualBlock(); editor.setMode(EditorMode.NORMAL); }
            else {
                String selected = editor.writingArea.getSelectedText();
                if (selected != null) { editor.clipboardManager.yankSelection(selected); editor.storeYank(editor.consumePendingRegister(), selected, lineMode); editor.showMessage("Selection yanked"); }
                editor.setMode(EditorMode.NORMAL);
            }
        } else if (c == 'd' || c == 'x') {
            if (blockMode) { editor.deleteVisualBlock(); editor.setMode(EditorMode.NORMAL); }
            else {
                String selected = editor.writingArea.getSelectedText();
                if (selected != null) { editor.clipboardManager.yankSelection(selected); editor.storeDelete(editor.consumePendingRegister(), selected, lineMode); editor.writingArea.replaceSelection(""); editor.markModified(); editor.showMessage("Selection deleted"); }
                editor.setMode(EditorMode.NORMAL);
            }
        } else if (c == 'c') {
            if (blockMode) { editor.deleteVisualBlock(); editor.setMode(EditorMode.INSERT); }
            else {
                String selected = editor.writingArea.getSelectedText();
                if (selected != null) { editor.clipboardManager.yankSelection(selected); editor.storeDelete(editor.consumePendingRegister(), selected, lineMode); editor.writingArea.replaceSelection(""); editor.markModified(); }
                editor.setMode(EditorMode.INSERT);
            }
        } else if (c == '>' || c == '<' || c == '=') {
            editor.applyVisualLineOperator(c);
            editor.setMode(EditorMode.NORMAL);
        } else if (c == '~') {
            String selected = editor.writingArea.getSelectedText();
            if (selected != null) {
                StringBuilder toggled = new StringBuilder(selected.length());
                for (char ch : selected.toCharArray()) {
                    toggled.append(Character.isUpperCase(ch) ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
                }
                editor.writingArea.replaceSelection(toggled.toString());
                editor.markModified();
            }
            editor.setMode(EditorMode.NORMAL);
        } else if (c == 'U') {
            String selected = editor.writingArea.getSelectedText();
            if (selected != null) {
                editor.writingArea.replaceSelection(selected.toUpperCase());
                editor.markModified();
            }
            editor.setMode(EditorMode.NORMAL);
        } else if (c == 'u') {
            String selected = editor.writingArea.getSelectedText();
            if (selected != null) {
                editor.writingArea.replaceSelection(selected.toLowerCase());
                editor.markModified();
            }
            editor.setMode(EditorMode.NORMAL);
        } else if (c == 'J') {
            editor.joinVisualSelection();
            editor.setMode(EditorMode.NORMAL);
        } else if (c == 'p' || c == 'P') {
            String selected = editor.writingArea.getSelectedText();
            RegisterContent content = editor.registerManager.get(editor.consumePendingRegister());
            if (selected != null && content != null && !content.getText().isEmpty()) {
                editor.storeDelete(null, selected, lineMode);
                editor.writingArea.replaceSelection(content.getText());
                editor.markModified();
                editor.showMessage("Pasted over selection");
            }
            editor.setMode(EditorMode.NORMAL);
        } else if (c == 'o') {
            int selStart = editor.writingArea.getSelectionStart();
            int selEnd = editor.writingArea.getSelectionEnd();
            int caret = editor.writingArea.getCaretPosition();
            if (caret == selStart) {
                editor.editorState.visualStartPos = selStart;
                editor.writingArea.setCaretPosition(selEnd);
            } else {
                editor.editorState.visualStartPos = selEnd;
                editor.writingArea.setCaretPosition(selStart);
            }
            // Re-apply selection after swap
            int swappedPos = editor.writingArea.getCaretPosition();
            if (lineMode) {
                editor.selectLineRange(editor.editorState.visualStartPos, swappedPos);
            } else {
                selectGraphemeRange(editor.editorState.visualStartPos, swappedPos);
            }
        }
    }

    private void selectGraphemeRange(int start, int end) {
        GraphemeEditRange.Range range = GraphemeEditRange.selection(editor.writingArea.getText(), start, end);
        editor.writingArea.setSelectionStart(range.start());
        editor.writingArea.setSelectionEnd(range.end());
    }


    void handleReplaceMode(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            editor.setMode(EditorMode.NORMAL);
            return;
        }

        // In replace mode, overwrite character at cursor
        if (!e.isControlDown() && !e.isAltDown()) {
            char c = e.getKeyChar();
            if (c != KeyEvent.CHAR_UNDEFINED && c != '\n') {
                int pos = editor.writingArea.getCaretPosition();
                String text = editor.writingArea.getText();

                if (pos < text.length()) {
                    // Replace character
                    editor.writingArea.replaceRange(String.valueOf(c), pos, pos + 1);
                    editor.markModified();
                } else {
                    // At end of text, just insert
                    editor.writingArea.insert(String.valueOf(c), pos);
                    editor.markModified();
                }
            }
        }
    }


    void handleCommandMode(KeyEvent e) {
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (e.isControlDown() && (code == KeyEvent.VK_R || c == 'r')) {
            openCommandHistorySearch();
            editor.updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_ESCAPE) {
            editor.editorState.commandBuffer = "";
            editor.clearSubstitutePreview();
            editor.setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            String result = editor.commandHandler.execute(editor.editorState.commandBuffer);
            addCommandHistory(editor.editorState.commandBuffer);
            if (!result.isEmpty()) {
                editor.showMessage(result);
            }
            editor.editorState.commandBuffer = "";
            editor.clearSubstitutePreview();
            editor.setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_UP) {
            browseCommandHistory(-1);
            editor.updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_DOWN) {
            browseCommandHistory(1);
            editor.updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_TAB) {
            editor.editorState.commandBuffer = completeCommand(editor.editorState.commandBuffer);
            editor.updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_BACK_SPACE) {
            if (editor.editorState.commandBuffer.length() > 1) {
                editor.editorState.commandBuffer = editor.editorState.commandBuffer.substring(0, editor.editorState.commandBuffer.length() - 1);
            } else {
                editor.editorState.commandBuffer = "";
                editor.clearSubstitutePreview();
                editor.setMode(EditorMode.NORMAL);
            }
            editor.updateSubstitutePreview();
            return;
        }

        // Append character to command buffer
        if (c != KeyEvent.CHAR_UNDEFINED && !e.isControlDown()) {
            editor.editorState.commandBuffer += c;
            editor.updateSubstitutePreview();
        }
    }

    boolean handleCommandPromptKeyPressed(KeyEvent e) {
        if (handlePaneShortcut(e)) {
            return true;
        }
        int code = e.getKeyCode();
        char c = e.getKeyChar();
        if (e.isControlDown() && (code == KeyEvent.VK_R || c == 'r')) {
            openCommandHistorySearch();
            editor.editorUiController.setCommandPromptText(editor.editorState.commandBuffer);
            return true;
        }
        if (code == KeyEvent.VK_ESCAPE) {
            editor.editorState.commandBuffer = "";
            editor.clearSubstitutePreview();
            editor.setMode(EditorMode.NORMAL);
            return true;
        }
        if (code == KeyEvent.VK_ENTER) {
            String command = editor.editorState.commandBuffer;
            String result = editor.commandHandler.execute(command);
            addCommandHistory(command);
            if (!result.isEmpty()) {
                editor.showMessage(result);
            }
            editor.editorState.commandBuffer = "";
            editor.clearSubstitutePreview();
            editor.setMode(EditorMode.NORMAL);
            return true;
        }
        if (code == KeyEvent.VK_UP) {
            browseCommandHistory(-1);
            editor.editorUiController.setCommandPromptText(editor.editorState.commandBuffer);
            return true;
        }
        if (code == KeyEvent.VK_DOWN) {
            browseCommandHistory(1);
            editor.editorUiController.setCommandPromptText(editor.editorState.commandBuffer);
            return true;
        }
        if (code == KeyEvent.VK_TAB) {
            if (!editor.editorUiController.acceptCommandPathSuggestion() && !editor.editorUiController.showCommandPathSuggestions()) {
                editor.editorUiController.setCommandPromptText(completeCommand(editor.editorState.commandBuffer));
            }
            return true;
        }
        return false;
    }


    void openCommandHistorySearch() {
        if (editor.commandHistory.isEmpty()) {
            editor.showMessage("No command history");
            return;
        }
        List<String> candidates = new ArrayList<>();
        for (int i = editor.commandHistory.size() - 1; i >= 0; i--) {
            String entry = editor.commandHistory.get(i);
            if (entry != null && !entry.isBlank()) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            editor.showMessage("No command history");
            return;
        }
        String selected = editor.showPaletteDialog("Command History", candidates,
            value -> value == null ? "" : "Recall history entry into : prompt");
        if (selected == null || selected.isBlank()) {
            editor.showMessage("History search cancelled");
            return;
        }
        editor.editorState.commandBuffer = selected;
        editor.commandHistoryIndex = -1;
        editor.commandHistoryPrefix = editor.editorState.commandBuffer;
        if (editor.editorState.mode == EditorMode.COMMAND) {
            editor.editorUiController.setCommandPromptText(editor.editorState.commandBuffer);
        }
    }


    void handleSearchMode(KeyEvent e) {
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (code == KeyEvent.VK_ESCAPE) {
            // Restore cursor to pre-search position
            if (editor.editorState.searchStartPos >= 0 && editor.editorState.searchStartPos <= editor.writingArea.getText().length()) {
                editor.writingArea.setCaretPosition(editor.editorState.searchStartPos);
            }
            editor.searchManager.clearHighlights();
            editor.editorState.commandBuffer = "";
            editor.setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            String pattern = editor.editorState.commandBuffer.length() > 1 ? editor.editorState.commandBuffer.substring(1) : "";
            String result = editor.editorState.searchForward ? editor.searchManager.searchForward(pattern) : editor.searchManager.searchBackward(pattern);
            if (!result.isEmpty()) {
                editor.showMessage(result);
            }
            if (!pattern.isEmpty()) {
                addCommandHistory(editor.editorState.commandBuffer);
            }
            editor.editorState.commandBuffer = "";
            editor.setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_UP) {
            browseCommandHistory(-1);
            return;
        }

        if (code == KeyEvent.VK_DOWN) {
            browseCommandHistory(1);
            return;
        }

        if (code == KeyEvent.VK_BACK_SPACE) {
            if (editor.editorState.commandBuffer.length() > 1) {
                editor.editorState.commandBuffer = editor.editorState.commandBuffer.substring(0, editor.editorState.commandBuffer.length() - 1);
            } else {
                editor.editorState.commandBuffer = "";
                if (editor.editorState.searchStartPos >= 0 && editor.editorState.searchStartPos <= editor.writingArea.getText().length()) {
                    editor.writingArea.setCaretPosition(editor.editorState.searchStartPos);
                }
                editor.searchManager.clearHighlights();
                editor.setMode(EditorMode.NORMAL);
            }
            incrementalSearchPreview();
            return;
        }

        if (c != KeyEvent.CHAR_UNDEFINED && !e.isControlDown()) {
            editor.editorState.commandBuffer += c;
            incrementalSearchPreview();
        }
    }


    void incrementalSearchPreview() {
        String pattern = editor.editorState.commandBuffer.length() > 1 ? editor.editorState.commandBuffer.substring(1) : "";
        if (pattern.isEmpty()) {
            editor.searchManager.clearHighlights();
            if (editor.editorState.searchStartPos >= 0 && editor.editorState.searchStartPos <= editor.writingArea.getText().length()) {
                editor.writingArea.setCaretPosition(editor.editorState.searchStartPos);
            }
            return;
        }
        if (editor.editorState.searchForward) {
            editor.searchManager.searchForward(pattern);
        } else {
            editor.searchManager.searchBackward(pattern);
        }
    }


    void browseCommandHistory(int direction) {
        if (editor.commandHistory.isEmpty()) {
            return;
        }
        if (editor.commandHistoryIndex < 0) {
            editor.commandHistoryPrefix = editor.editorState.commandBuffer;
            editor.commandHistoryIndex = editor.commandHistory.size();
        }

        int nextIndex = editor.commandHistoryIndex + direction;
        nextIndex = Math.max(0, Math.min(nextIndex, editor.commandHistory.size()));
        editor.commandHistoryIndex = nextIndex;

        if (editor.commandHistoryIndex >= editor.commandHistory.size()) {
            editor.editorState.commandBuffer = editor.commandHistoryPrefix;
            return;
        }

        String candidate = editor.commandHistory.get(editor.commandHistoryIndex);
        if (!editor.commandHistoryPrefix.isEmpty() && !candidate.startsWith(editor.commandHistoryPrefix.substring(0, 1))) {
            return;
        }
        editor.editorState.commandBuffer = candidate;
    }


    void addCommandHistory(String entry) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        editor.appendCommandLog(entry);
        editor.commandHistory.remove(entry);
        editor.commandHistory.add(entry);
        while (editor.commandHistory.size() > 100) {
            editor.commandHistory.remove(0);
        }
        editor.commandHistoryIndex = -1;
        editor.commandHistoryPrefix = "";
    }


    String completeCommand(String input) {
        if (input == null || input.isEmpty() || !input.startsWith(":")) {
            return input;
        }

        String withoutColon = input.substring(1);
        if (withoutColon.startsWith("e ") || withoutColon.startsWith("w ")) {
            return ":" + completePath(withoutColon.substring(0, 2), withoutColon.substring(2));
        }
        String lowered = withoutColon.toLowerCase();

        List<String> knownCommands = new ArrayList<>();
        knownCommands.add("w");
        knownCommands.add("write");
        knownCommands.add("q");
        knownCommands.add("quit");
        knownCommands.add("q!");
        knownCommands.add("wq");
        knownCommands.add("x");
        knownCommands.add("e");
        knownCommands.add("edit");
        knownCommands.add("bn");
        knownCommands.add("bp");
        knownCommands.add("ls");
        knownCommands.add("buffers");
        knownCommands.add("bd");
        knownCommands.add("set");
        knownCommands.add("settings");
        knownCommands.add("config");
        knownCommands.add("log");
        knownCommands.add("commandlog");
        knownCommands.add("session");
        knownCommands.add("sessions");
        knownCommands.add("workspace");
        knownCommands.add("ws");
        knownCommands.add("projectreplace");
        knownCommands.add("largefile");
        knownCommands.add("lf");
        knownCommands.add("preplace");
        knownCommands.add("jobs");
        knownCommands.add("jobcancel");
        knownCommands.add("jobkill");
        knownCommands.add("drop");
        knownCommands.add("task");
        knownCommands.add("help");
        knownCommands.add("wc");
        knownCommands.add("recent");
        knownCommands.add("d");
        knownCommands.add("delete");
        knownCommands.add("files");
        knownCommands.add("folder");
        knownCommands.add("folders");
        knownCommands.add("tree");
        knownCommands.add("git");
        knownCommands.add("buf");
        knownCommands.add("grep");
        knownCommands.add("copen");
        knownCommands.add("cclose");
        knownCommands.add("cnext");
        knownCommands.add("cprev");
        knownCommands.add("cfirst");
        knownCommands.add("clast");
        knownCommands.add("cc");
        knownCommands.add("lsp");
        knownCommands.add("definition");
        knownCommands.add("hover");
        knownCommands.add("references");
        knownCommands.add("diagnostics");
        knownCommands.add("diag");
        knownCommands.add("ldiag");
        knownCommands.add("dnext");
        knownCommands.add("dprev");
        knownCommands.add("symbols");
        knownCommands.add("sym");
        knownCommands.add("registers");
        knownCommands.add("marks");
        knownCommands.add("yankring");
        knownCommands.add("pastepicker");
        knownCommands.add("yr");
        knownCommands.add("zen");
        knownCommands.add("goyo");
        knownCommands.add("limelight");
        knownCommands.add("normal");
        knownCommands.add("reload");
        knownCommands.add("source");
        knownCommands.add("clean");
        knownCommands.add("shedclean");
        knownCommands.add("noh");
        knownCommands.add("nohlsearch");
        knownCommands.add("wa");
        knownCommands.add("qa");
        knownCommands.add("wqa");
        knownCommands.add("split");
        knownCommands.add("vsplit");
        knownCommands.add("close");
        knownCommands.add("themes");
        // Markdown / orgmode commands
        knownCommands.add("toc");
        knownCommands.add("outline");
        knownCommands.add("toggle");
        knownCommands.add("table");
        knownCommands.add("link");
        knownCommands.add("img");
        knownCommands.add("snippets");
        knownCommands.add("bracketcolor");
        knownCommands.add("term");
        knownCommands.add("terminal");
        knownCommands.add("conceal");
        knownCommands.addAll(editor.configManager.getConfiguredCommandAliases());

        // Exact prefix match first
        for (String command : knownCommands) {
            if (command.startsWith(lowered)) {
                return ":" + command;
            }
        }
        // Fuzzy match fallback
        List<String> fuzzy = editor.fuzzyMatchService.matchStrings(lowered, knownCommands, 1);
        if (!fuzzy.isEmpty()) {
            return ":" + fuzzy.get(0);
        }
        return input;
    }


    String completePath(String prefix, String partialPath) {
        String trimmed = partialPath.trim();
        File base = trimmed.isEmpty() ? new File(".") : new File(trimmed);
        File directory = base.isDirectory() ? base : base.getParentFile();
        String needle = base.isDirectory() ? "" : base.getName();
        if (directory == null) {
            directory = new File(".");
        }
        File[] matches = directory.listFiles((dir, name) -> name.startsWith(needle));
        if (matches == null || matches.length == 0) {
            return prefix + partialPath;
        }
        return prefix + matches[0].getPath();
    }


    void showInlineCompletion() {
        showInlineCompletion(LspClient.CompletionTriggerKind.INVOKED, null, true);
    }

    private void showInlineCompletion(LspClient.CompletionTriggerKind triggerKind, Character triggerCharacter, boolean explicit) {
        cancelCompletionJob();
        completionRequestState.invalidate();
        activeCompletionRequest = null;
        String prefix = editor.currentCompletionPrefix();
        boolean serverTrigger = triggerKind == LspClient.CompletionTriggerKind.TRIGGER_CHARACTER && triggerCharacter != null;
        if (!explicit && !serverTrigger && (prefix == null || prefix.length() < 2)) {
            dismissCompletionPopup();
            return;
        }
        FileBuffer buffer = editor.getCurrentBuffer();
        List<LspClient.CompletionItem> snippets = snippetCompletionItems(prefix);
        editor.flushLspChange(buffer);
        LspClient client = editor.existingLspClient(buffer);
        if (buffer == null || client == null || !buffer.hasFilePath() || !client.supports(LspCapability.COMPLETION)) {
            showCompletionPopup(mergeCompletionItems(snippets, localCompletionItems(prefix)), prefix);
            return;
        }
        try {
            String uri = editor.bufferUri(buffer);
            int caretOffset = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caretOffset);
            int column = caretOffset - editor.writingArea.getLineStartOffset(line);
            int documentVersion = editor.lspDocumentVersions.getOrDefault(uri, 0);
            CompletionRequestState.Snapshot request = completionRequestState.begin(uri, documentVersion, caretOffset, prefix);
            activeCompletionRequest = request;
            completionJobId = editor.asyncJobService.submit("LSP completion", token -> client.completion(uri, line, column, triggerKind, triggerCharacter),
                (snapshot, items, error) -> completeInlineCompletion(request, snapshot, items, error));
        } catch (BadLocationException ignored) {
            dismissCompletionPopup();
        }
    }


    void dismissCompletionPopup() {
        cancelQuickSuggestion();
        cancelCompletionJob();
        cancelCompletionResolve();
        completionResolveAttempts.clear();
        completionRequestState.invalidate();
        activeCompletionRequest = null;
        snippetSession.clear();
        if (editor.completionPopup != null && editor.completionPopup.isVisible()) editor.completionPopup.setVisible(false);
    }

    void dismissCompletionPopupForCaretMove() {
        cancelQuickSuggestion();
        cancelCompletionJob();
        cancelCompletionResolve();
        completionResolveAttempts.clear();
        completionRequestState.invalidate();
        activeCompletionRequest = null;
        if (editor.completionPopup != null && editor.completionPopup.isVisible()) editor.completionPopup.setVisible(false);
    }


    boolean isCompletionPopupVisible() {
        return editor.completionPopup != null && editor.completionPopup.isVisible();
    }


    void completionPopupNavigate(int direction) {
        if (editor.completionList == null || editor.completionModel.isEmpty()) return;
        int idx = editor.completionList.getSelectedIndex() + direction;
        if (idx < 0) idx = editor.completionModel.size() - 1;
        if (idx >= editor.completionModel.size()) idx = 0;
        editor.completionList.setSelectedIndex(idx);
        editor.completionList.ensureIndexIsVisible(idx);
    }


    void completionPopupAccept() {
        if (editor.completionList == null) return;
        LspClient.CompletionItem selected = editor.completionList.getSelectedValue();
        if (!isCurrentCompletion(editor.completionPrefix)) {
            dismissCompletionPopup();
            return;
        }
        String prefix = editor.completionPrefix;
        LspCompletionApplication.Result result = LspCompletionApplication.apply(editor.writingArea.getText(),
            editor.writingArea.getCaretPosition(), prefix, selected);
        dismissCompletionPopup();
        editor.suppressDocumentEvents = true;
        editor.writingArea.setText(result.text());
        editor.suppressDocumentEvents = false;
        editor.markModified();
        editor.scheduleSyntaxHighlighting();
        scheduleOpenBufferWordIndex();
        if (result.placeholders().isEmpty()) {
            editor.writingArea.setCaretPosition(Math.min(result.caret(), editor.writingArea.getDocument().getLength()));
        } else {
            snippetSession.begin(editor.writingArea, 0, result.placeholders());
        }
        if (result.fallback()) editor.showMessage("LSP completion edit was invalid; inserted label");
    }


    List<String> gatherCompletions(String prefix) {
        List<String> labels = new ArrayList<>();
        for (LspClient.CompletionItem item : mergeCompletionItems(snippetCompletionItems(prefix), localCompletionItems(prefix))) {
            labels.add(item.getLabel());
        }
        return labels;
    }

    private void completeInlineCompletion(CompletionRequestState.Snapshot request, AsyncJobService.JobSnapshot snapshot,
                                          List<LspClient.CompletionItem> items, Exception error) {
        if (snapshot == null || snapshot.getStatus() == AsyncJobService.Status.CANCELLED || error != null
            || !isCurrentCompletion(request)) {
            return;
        }
        completionJobId = -1;
        List<LspClient.CompletionItem> resolved = items == null || items.isEmpty() ? localCompletionItems(request.prefix()) : items;
        showCompletionPopup(mergeCompletionItems(snippetCompletionItems(request.prefix()), resolved), request.prefix());
    }

    private void showCompletionPopup(List<LspClient.CompletionItem> completions, String prefix) {
        if (completions == null || completions.isEmpty()) {
            dismissCompletionPopup();
            return;
        }
        try {
            editor.completionPrefix = prefix;
            ensureCompletionPopup();
            completionResolveAttempts.clear();
            editor.completionModel.clear();
            List<LspClient.CompletionItem> ranked = completionRanker.rank(prefix, completions,
                editor.configManager.getLspCompletionFuzzyMatching(), 12);
            int max = ranked.size();
            if (max == 0) {
                dismissCompletionPopup();
                return;
            }
            int selectedIndex = 0;
            for (int i = 0; i < max; i++) {
                LspClient.CompletionItem item = ranked.get(i);
                editor.completionModel.addElement(item);
                if (item.isPreselect()) selectedIndex = i;
            }
            editor.completionList.setSelectedIndex(selectedIndex);
            updateCompletionDocumentation();
            Rectangle2D caretRect = editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition());
            if (caretRect == null || !editor.writingArea.isShowing()) return;
            Point location = editor.writingArea.getLocationOnScreen();
            int px = location.x + (int) caretRect.getX();
            int py = location.y + (int) (caretRect.getY() + caretRect.getHeight());
            int lineHeight = editor.writingArea.getFontMetrics(editor.writingArea.getFont()).getHeight();
            editor.completionPopup.setLocation(px, py);
            editor.completionPopup.setSize(460, Math.min(max * lineHeight + 108, 340));
            editor.completionPopup.setVisible(true);
        } catch (Exception ignored) {
            dismissCompletionPopup();
        }
    }

    private void ensureCompletionPopup() {
        if (editor.completionPopup != null) return;
        editor.completionModel = new DefaultListModel<>();
        editor.completionList = new JList<>(editor.completionModel);
        editor.completionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        editor.completionList.setFocusable(false);
        editor.completionList.setFont(editor.writingArea.getFont().deriveFont((float) editor.writingArea.getFont().getSize()));
        editor.completionList.setBackground(editor.configManager.getCommandBarBackground());
        editor.completionList.setForeground(editor.configManager.getCommandBarForeground());
        editor.completionList.setSelectionBackground(editor.configManager.getSelectionColor());
        editor.completionList.setSelectionForeground(editor.configManager.getSelectionTextColor());
        editor.completionList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateCompletionDocumentation();
        });
        editor.completionDocumentation = new JTextArea();
        editor.completionDocumentation.setEditable(false);
        editor.completionDocumentation.setLineWrap(true);
        editor.completionDocumentation.setWrapStyleWord(true);
        editor.completionDocumentation.setBackground(editor.configManager.getCommandBarBackground());
        editor.completionDocumentation.setForeground(editor.configManager.getCommandBarForeground());
        JScrollPane listScroll = new JScrollPane(editor.completionList);
        JScrollPane documentationScroll = new JScrollPane(editor.completionDocumentation);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listScroll, documentationScroll);
        split.setResizeWeight(0.7);
        split.setBorder(BorderFactory.createLineBorder(editor.configManager.getCaretColor()));
        editor.completionPopup = new JWindow(editor);
        editor.completionPopup.add(split);
        editor.completionPopup.setFocusableWindowState(false);
    }

    private void updateCompletionDocumentation() {
        if (editor.completionDocumentation == null || editor.completionList == null) return;
        LspClient.CompletionItem selected = editor.completionList.getSelectedValue();
        if (selected == null) {
            editor.completionDocumentation.setText("");
            return;
        }
        String documentation = selected.getDocumentation();
        editor.completionDocumentation.setText(documentation.isBlank() ? selected.getDetail() : documentation);
        editor.completionDocumentation.setCaretPosition(0);
        resolveCompletionDocumentation(selected);
    }

    private void resolveCompletionDocumentation(LspClient.CompletionItem item) {
        if (item == null || !item.getDocumentation().isBlank() || item == resolvingCompletion || completionResolveAttempts.contains(item)) return;
        FileBuffer buffer = editor.getCurrentBuffer();
        LspClient client = editor.existingLspClient(buffer);
        if (client == null || !client.supportsCompletionResolve()) return;
        cancelCompletionResolve();
        resolvingCompletion = item;
        completionResolveAttempts.add(item);
        completionResolveJobId = editor.asyncJobService.submit("LSP completion detail", token -> client.resolveCompletionItem(item),
            (snapshot, resolved, error) -> {
                completionResolveJobId = -1;
                if (snapshot == null || snapshot.getStatus() != AsyncJobService.Status.SUCCEEDED || error != null
                    || resolved == null || resolved == item || !isCompletionPopupVisible() || editor.completionList == null
                    || editor.completionList.getSelectedValue() != item) return;
                int index = editor.completionList.getSelectedIndex();
                if (index < 0) return;
                editor.completionModel.set(index, resolved);
                editor.completionList.setSelectedIndex(index);
            });
    }

    private void cancelCompletionJob() {
        if (completionJobId >= 0) editor.asyncJobService.cancel(completionJobId);
        completionJobId = -1;
    }

    private void cancelCompletionResolve() {
        if (completionResolveJobId >= 0) editor.asyncJobService.cancel(completionResolveJobId);
        completionResolveJobId = -1;
        resolvingCompletion = null;
    }

    private boolean isCurrentCompletion(CompletionRequestState.Snapshot request) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) return false;
        String uri = editor.bufferUri(buffer);
        return completionRequestState.matches(request, uri, editor.lspDocumentVersions.getOrDefault(uri, 0),
            editor.writingArea.getCaretPosition(), editor.currentCompletionPrefix());
    }

    private boolean isCurrentCompletion(String prefix) {
        return activeCompletionRequest == null
            ? Objects.equals(prefix, editor.currentCompletionPrefix())
            : isCurrentCompletion(activeCompletionRequest);
    }

    private List<LspClient.CompletionItem> localCompletionItems(String prefix) {
        if (!editor.configManager.getLspCompletionLocalWords() || prefix == null || prefix.isBlank()) return List.of();
        scheduleMissingOpenBufferWordIndex();
        List<LspClient.CompletionItem> items = new ArrayList<>();
        FileBuffer current = editor.getCurrentBuffer();
        for (OpenBufferCompletionIndex.Candidate candidate : openBufferCompletionIndex.complete(editor.buffers, current, prefix,
            editor.writingArea.getCaretPosition(), 12, editor.fuzzyMatchService)) {
            items.add(new LspClient.CompletionItem(candidate.word(), "open buffer", 1));
        }
        if (current != null && !openBufferCompletionIndex.hasSnapshot(current)) {
            for (String completion : editor.collectBufferCompletions(prefix)) {
                items.add(new LspClient.CompletionItem(completion, "current buffer", 1));
            }
        }
        return items;
    }

    private boolean completionPopupCommits(char character) {
        if (character == KeyEvent.CHAR_UNDEFINED || editor.completionList == null) return false;
        LspClient.CompletionItem selected = editor.completionList.getSelectedValue();
        return selected != null && selected.getCommitCharacters().contains(String.valueOf(character));
    }

    private boolean moveSnippetPlaceholder(int direction) {
        return snippetSession.move(editor.writingArea, direction);
    }

    String expandNativeSnippetAtCursor() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null) return "No buffer";
        int caret = editor.writingArea.getCaretPosition();
        String text = editor.writingArea.getText();
        int start = caret;
        while (start > 0 && isSnippetTriggerCharacter(text.charAt(start - 1))) start--;
        if (start == caret) return "No trigger word";
        String trigger = text.substring(start, caret);
        SnippetService.Snippet snippet = editor.snippetService.findExact(buffer.getFileType(), trigger);
        if (snippet == null) return "No snippet: " + trigger;
        SnippetExpansion.Result expansion = SnippetExpansion.parse(snippet.body, snippetVariables(buffer));
        if (expansion == null) return "Invalid snippet: " + trigger;
        editor.suppressDocumentEvents = true;
        try {
            editor.writingArea.replaceRange(expansion.text(), start, caret);
        } finally {
            editor.suppressDocumentEvents = false;
        }
        editor.markModified();
        editor.scheduleSyntaxHighlighting();
        scheduleOpenBufferWordIndex();
        if (!snippetSession.begin(editor.writingArea, start, expansion.placeholders())) {
            editor.writingArea.setCaretPosition(Math.min(start + expansion.text().length(), editor.writingArea.getDocument().getLength()));
        }
        return "Expanded: " + trigger;
    }

    private boolean isSnippetTriggerCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.';
    }

    private Map<String, String> snippetVariables(FileBuffer buffer) {
        Map<String, String> values = new HashMap<>();
        String selected = editor.writingArea.getSelectedText();
        values.put("TM_SELECTED_TEXT", selected == null ? "" : selected);
        try {
            int caret = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caret);
            int start = editor.writingArea.getLineStartOffset(line);
            int end = editor.writingArea.getLineEndOffset(line);
            values.put("TM_CURRENT_LINE", editor.writingArea.getText(start, Math.max(0, end - start)).replaceAll("[\\r\\n]+$", ""));
            String word = editor.currentCompletionPrefix();
            values.put("TM_CURRENT_WORD", word == null ? "" : word);
            values.put("TM_LINE_INDEX", Integer.toString(line));
            values.put("TM_LINE_NUMBER", Integer.toString(line + 1));
        } catch (BadLocationException ignored) {
        }
        File file = buffer.getFile();
        if (file != null) {
            String filename = file.getName();
            int dot = filename.lastIndexOf('.');
            values.put("TM_FILENAME", filename);
            values.put("TM_FILENAME_BASE", dot > 0 ? filename.substring(0, dot) : filename);
            values.put("TM_DIRECTORY", file.getParent() == null ? "" : file.getParent());
            values.put("TM_FILEPATH", file.getAbsolutePath());
        }
        values.put("WORKSPACE_NAME", editor.treeRoot == null ? "" : editor.treeRoot.getName());
        return values;
    }

    private List<LspClient.CompletionItem> snippetCompletionItems(String prefix) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null) return List.of();
        List<LspClient.CompletionItem> items = new ArrayList<>();
        for (SnippetService.Snippet snippet : editor.snippetService.getSnippetsFor(buffer.getFileType(), prefix)) {
            items.add(new LspClient.CompletionItem(snippet.trigger, snippet.description, 15, snippet.description, snippet.body, true, List.of()));
        }
        return items;
    }

    private List<LspClient.CompletionItem> mergeCompletionItems(List<LspClient.CompletionItem> first, List<LspClient.CompletionItem> second) {
        LinkedHashMap<String, LspClient.CompletionItem> merged = new LinkedHashMap<>();
        for (LspClient.CompletionItem item : first) merged.put(item.getLabel() + '\u0000' + item.getInsertText(), item);
        for (LspClient.CompletionItem item : second) merged.putIfAbsent(item.getLabel() + '\u0000' + item.getInsertText(), item);
        return List.copyOf(merged.values());
    }

    private void showSignatureHelp() {
        dismissSignatureHelp();
        FileBuffer buffer = editor.getCurrentBuffer();
        editor.flushLspChange(buffer);
        LspClient client = editor.existingLspClient(buffer);
        if (buffer == null || client == null || !buffer.hasFilePath() || !client.supports(LspCapability.SIGNATURE_HELP)) return;
        try {
            String uri = editor.bufferUri(buffer);
            int caret = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caret);
            int column = caret - editor.writingArea.getLineStartOffset(line);
            CompletionRequestState.Snapshot request = signatureHelpRequestState.begin(uri,
                editor.lspDocumentVersions.getOrDefault(uri, 0), caret, "");
            signatureHelpJobId = editor.asyncJobService.submit("LSP signature help", token -> client.signatureHelp(uri, line, column),
                (snapshot, help, error) -> completeSignatureHelp(request, snapshot, help, error));
        } catch (BadLocationException ignored) {
        }
    }

    private void completeSignatureHelp(CompletionRequestState.Snapshot request, AsyncJobService.JobSnapshot snapshot,
                                       LspClient.SignatureHelp help, Exception error) {
        if (snapshot == null || snapshot.getStatus() == AsyncJobService.Status.CANCELLED || error != null
            || !isCurrentSignatureHelp(request) || help == null) return;
        signatureHelpJobId = -1;
        ensureSignatureHelpPopup();
        String parameter = help.getActiveParameter() < 0 ? "" : "\nParameter " + (help.getActiveParameter() + 1);
        String text = help.getDocumentation().isBlank() ? help.getLabel() + parameter
            : help.getLabel() + parameter + "\n" + help.getDocumentation();
        editor.signatureHelpText.setText(text);
        editor.signatureHelpText.setCaretPosition(0);
        try {
            Rectangle2D caretRect = editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition());
            if (caretRect == null || !editor.writingArea.isShowing()) return;
            Point location = editor.writingArea.getLocationOnScreen();
            editor.signatureHelpPopup.setLocation(location.x + (int) caretRect.getX(), location.y + (int) caretRect.getY() - 88);
            editor.signatureHelpPopup.setSize(460, 84);
            editor.signatureHelpPopup.setVisible(true);
        } catch (Exception ignored) {
            dismissSignatureHelp();
        }
    }

    private void ensureSignatureHelpPopup() {
        if (editor.signatureHelpPopup != null) return;
        editor.signatureHelpText = new JTextArea();
        editor.signatureHelpText.setEditable(false);
        editor.signatureHelpText.setLineWrap(true);
        editor.signatureHelpText.setWrapStyleWord(true);
        editor.signatureHelpText.setBackground(editor.configManager.getCommandBarBackground());
        editor.signatureHelpText.setForeground(editor.configManager.getCommandBarForeground());
        editor.signatureHelpPopup = new JWindow(editor);
        editor.signatureHelpPopup.add(new JScrollPane(editor.signatureHelpText));
        editor.signatureHelpPopup.setFocusableWindowState(false);
    }

    private boolean isCurrentSignatureHelp(CompletionRequestState.Snapshot request) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) return false;
        String uri = editor.bufferUri(buffer);
        return signatureHelpRequestState.matches(request, uri, editor.lspDocumentVersions.getOrDefault(uri, 0),
            editor.writingArea.getCaretPosition(), "");
    }

    private void dismissSignatureHelp() {
        if (signatureHelpJobId >= 0) editor.asyncJobService.cancel(signatureHelpJobId);
        signatureHelpJobId = -1;
        signatureHelpRequestState.invalidate();
        if (editor.signatureHelpPopup != null && editor.signatureHelpPopup.isVisible()) editor.signatureHelpPopup.setVisible(false);
    }

    private boolean isSignatureHelpVisible() {
        return editor.signatureHelpPopup != null && editor.signatureHelpPopup.isVisible();
    }


    public void keyTyped(KeyEvent e) {
        if (!editor.configManager.getKeymapProfile().usesVimModeHandling()) {
            return;
        }
        if (editor.suppressNextTypedChar) {
            editor.suppressNextTypedChar = false;
            e.consume();
            return;
        }
        if (editor.editorState.mode != EditorMode.INSERT) {
            e.consume();
            return;
        }
        if (e.getKeyChar() == '(' || e.getKeyChar() == ',') SwingUtilities.invokeLater(this::showSignatureHelp);
    }


    public void keyReleased(KeyEvent e) {}

}
