// SHit EDitor (Shed) Version 2.0 <Refactored Build>

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class Texteditor extends JFrame implements KeyListener {

    // Core components
    private EditorMode currentMode;
    private JTextArea writingArea;
    private JLabel statusBar;
    private LineNumberPanel lineNumberPanel;

    // Managers
    private ClipboardManager clipboardManager;
    private SearchManager searchManager;
    private CommandHandler commandHandler;
    private ConfigManager configManager;

    // Buffer management
    private List<FileBuffer> buffers;
    private int currentBufferIndex;

    // State variables
    private String commandBuffer;
    private boolean lineNumbersEnabled;
    private UndoManager undoManager;
    private String lastCommand;
    private char pendingKey; // For multi-key commands like 'gg', 'dd', etc.
    private String pendingCount;
    private boolean suppressDocumentEvents;
    private boolean closingDown;
    private List<String> recentFiles;
    private File recentFilesStore;
    private Deque<SpecialBufferReturnState> specialBufferReturns;

    // Visual mode state
    private int visualStartPos;

    // Constants
    private static final String VERSION = "2.0";

    // Constructor
    public Texteditor(String[] args) {
        // Initialize managers
        configManager = new ConfigManager();
        buffers = new ArrayList<>();
        currentBufferIndex = -1;
        commandBuffer = "";
        pendingKey = '\0';
        pendingCount = "";
        visualStartPos = -1;
        lastCommand = "";
        suppressDocumentEvents = false;
        closingDown = false;
        recentFiles = new ArrayList<>();
        recentFilesStore = new File(System.getProperty("user.home"), ".shed_recent");
        specialBufferReturns = new ArrayDeque<>();
        loadRecentFiles();

        // Initialize UI
        initializeUI();

        // Initialize managers that depend on UI
        clipboardManager = new ClipboardManager();
        searchManager = new SearchManager(writingArea);
        commandHandler = new CommandHandler(this);

        // Open file from command line or landing page
        if (args.length > 0) {
            try {
                File file = new File(args[0]);
                openFile(file);
            } catch (Exception e) {
                showMessage("Error opening file: " + e.getMessage());
            }
        } else {
            openLandingPage();
        }

        // Set initial mode
        setMode(EditorMode.NORMAL);
        updateStatusBar();

        this.setVisible(true);
    }

    // Initialize UI components
    private void initializeUI() {
        this.setTitle("Shed " + VERSION);
        this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        this.setSize(screenSize.width / 2, screenSize.height);
        this.setLayout(new BorderLayout(5, 5));

        // Create text area
        writingArea = new JTextArea();
        writingArea.setPreferredSize(new Dimension(screenSize.width / 2, screenSize.height - 130));
        writingArea.addKeyListener(this);

        // Load font
        writingArea.setFont(resolveEditorFont());

        // Configure text area
        writingArea.setTabSize(configManager.getTabSize());
        writingArea.getCaret().setBlinkRate(0);
        writingArea.setCaretColor(Color.decode("#02862a"));
        writingArea.setForeground(Color.decode("#FAF9F6"));
        writingArea.setEditable(false);

        // Setup undo manager
        undoManager = new UndoManager();
        writingArea.getDocument().addUndoableEditListener(undoManager);

        // Add document listener for modification tracking
        writingArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { handleDocumentChange(); }
            public void removeUpdate(DocumentEvent e) { handleDocumentChange(); }
            public void changedUpdate(DocumentEvent e) { handleDocumentChange(); }
        });

        // Create line number panel
        lineNumberPanel = new LineNumberPanel(writingArea);
        lineNumbersEnabled = configManager.getLineNumbers();

        // Create scroll pane with line numbers
        JScrollPane scrollPane = new JScrollPane(writingArea);
        if (lineNumbersEnabled) {
            scrollPane.setRowHeaderView(lineNumberPanel);
        }

        // Create status bar
        statusBar = new JLabel();
        statusBar.setBackground(Color.lightGray);
        statusBar.setOpaque(true);
        statusBar.setPreferredSize(new Dimension(screenSize.width / 2, 30));
        statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Add components
        this.add(scrollPane, BorderLayout.CENTER);
        this.add(statusBar, BorderLayout.SOUTH);

        // Window close handler
        this.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                handleQuit(false);
            }
        });
    }

    private Font resolveEditorFont() {
        int fontSize = configManager.getFontSize();
        String configuredFamily = configManager.getFontFamily();
        Font configuredFont = resolveInstalledFont(configuredFamily, fontSize);
        if (configuredFont != null) {
            return configuredFont;
        }

        Font bundledHackFont = loadBundledHackFont(fontSize);
        if (bundledHackFont != null) {
            return bundledHackFont;
        }

        return new Font("Monospaced", Font.PLAIN, fontSize);
    }

    private Font resolveInstalledFont(String family, int fontSize) {
        if (family == null || family.trim().isEmpty()) {
            return null;
        }

        GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String availableFamily : graphicsEnvironment.getAvailableFontFamilyNames()) {
            if (availableFamily.equalsIgnoreCase(family.trim())) {
                return new Font(availableFamily, Font.PLAIN, fontSize);
            }
        }

        return null;
    }

    private Font loadBundledHackFont(int fontSize) {
        try {
            Font hackFont = Font.createFont(Font.TRUETYPE_FONT, new File("assets/hackregfont.ttf"));
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(hackFont);
            return hackFont.deriveFont((float) fontSize);
        } catch (Exception e) {
            return null;
        }
    }

    // Key event handling
    @Override
    public void keyPressed(KeyEvent e) {
        switch (currentMode) {
            case NORMAL:
                handleNormalMode(e);
                break;
            case INSERT:
                handleInsertMode(e);
                break;
            case VISUAL:
                handleVisualMode(e);
                break;
            case REPLACE:
                handleReplaceMode(e);
                break;
            case COMMAND:
                handleCommandMode(e);
                break;
        }
        updateStatusBar();
    }

    // Normal mode key handling
    private void handleNormalMode(KeyEvent e) {
        char c = e.getKeyChar();
        int code = e.getKeyCode();

        // Handle pending keys (multi-key commands)
        if (pendingKey != '\0') {
            handlePendingKey(c, code);
            return;
        }

        // Accumulate numeric prefix for COUNTgg without breaking 0 line-start
        if (Character.isDigit(c) && (!pendingCount.isEmpty() || c != '0')) {
            pendingCount += c;
            return;
        }

        if (!pendingCount.isEmpty() && c != 'g') {
            pendingCount = "";
        }

        // Mode switches
        if (c == 'i') {
            setMode(EditorMode.INSERT);
            return;
        } else if (c == 'v') {
            setMode(EditorMode.VISUAL);
            visualStartPos = writingArea.getCaretPosition();
            return;
        } else if (c == 'R') {
            setMode(EditorMode.REPLACE);
            return;
        } else if (c == ':' || c == '/') {
            setMode(EditorMode.COMMAND);
            commandBuffer = String.valueOf(c);
            return;
        }

        // Navigation
        else if (code == KeyEvent.VK_UP || c == 'k') {
            moveUp();
        } else if (code == KeyEvent.VK_DOWN || c == 'j') {
            moveDown();
        } else if (code == KeyEvent.VK_LEFT || c == 'h') {
            moveLeft();
        } else if (code == KeyEvent.VK_RIGHT || c == 'l') {
            moveRight();
        }

        // Word movements
        else if (c == 'w') {
            moveWordForward();
        } else if (c == 'b') {
            moveWordBackward();
        } else if (c == 'e') {
            moveWordEnd();
        }

        // Line movements
        else if (c == '0') {
            moveLineStart();
        } else if (c == '$') {
            moveLineEnd();
        }

        // File movements
        else if (c == 'g') {
            pendingKey = 'g';
        } else if (c == 'G') {
            pendingCount = "";
            moveFileEnd();
        }

        // Clipboard operations
        else if (c == 'y') {
            pendingCount = "";
            pendingKey = 'y';
        } else if (c == 'd') {
            pendingCount = "";
            pendingKey = 'd';
        } else if (c == 'c') {
            pendingCount = "";
            pendingKey = 'c';
        } else if (c == 'x') {
            pendingCount = "";
            clipboardManager.deleteChar(writingArea);
            markModified();
        } else if (c == 'p') {
            pendingCount = "";
            clipboardManager.paste(writingArea, false);
            markModified();
        } else if (c == 'P') {
            pendingCount = "";
            clipboardManager.paste(writingArea, true);
            markModified();
        } else if (c == 'D') {
            pendingCount = "";
            lastCommand = "D";
            clipboardManager.deleteToEndOfLine(writingArea);
            markModified();
        } else if (c == 'C') {
            pendingCount = "";
            lastCommand = "C";
            clipboardManager.deleteToEndOfLine(writingArea);
            markModified();
            setMode(EditorMode.INSERT);
        }

        // Undo/Redo
        else if (c == 'u') {
            pendingCount = "";
            if (undoManager.canUndo()) {
                undoManager.undo();
            }
        } else if (e.isControlDown() && c == 'r') {
            pendingCount = "";
            if (undoManager.canRedo()) {
                undoManager.redo();
            }
        }

        // Search navigation
        else if (c == 'n') {
            pendingCount = "";
            showMessage(searchManager.nextMatch());
        } else if (c == 'N') {
            pendingCount = "";
            showMessage(searchManager.prevMatch());
        }

        // Repeat last command
        else if (c == '.') {
            pendingCount = "";
            repeatLastCommand();
        }

        // Ctrl combinations
        else if (e.isControlDown()) {
            if (c == 'd' || code == KeyEvent.VK_D) {
                pendingCount = "";
                scrollHalfPageDown();
            } else if (c == 'u' || code == KeyEvent.VK_U) {
                pendingCount = "";
                scrollHalfPageUp();
            }
        }

        // Escape (no-op in normal mode, but clear any messages)
        else if (code == KeyEvent.VK_ESCAPE) {
            pendingCount = "";
            pendingKey = '\0';
            showMessage("Already in normal mode");
        }
    }

    // Handle pending multi-key commands
    private void handlePendingKey(char c, int code) {
        if (pendingKey == 'g') {
            if (c == 'g') {
                if (pendingCount.isEmpty()) {
                    moveFileStart();
                } else {
                    showMessage(gotoLine(Integer.parseInt(pendingCount)));
                }
            }
            pendingKey = '\0';
            pendingCount = "";
        } else if (pendingKey == 'y') {
            if (c == 'y') {
                lastCommand = "yy";
                clipboardManager.yankLine(writingArea);
                showMessage("Line yanked");
            }
            pendingKey = '\0';
            pendingCount = "";
        } else if (pendingKey == 'd') {
            if (c == 'd') {
                lastCommand = "dd";
                clipboardManager.deleteLine(writingArea);
                markModified();
                showMessage("Line deleted");
            } else if (c == 'w') {
                lastCommand = "dw";
                clipboardManager.deleteWord(writingArea);
                markModified();
                showMessage("Word deleted");
            }
            pendingKey = '\0';
            pendingCount = "";
        } else if (pendingKey == 'c') {
            if (c == 'c') {
                lastCommand = "cc";
                clipboardManager.deleteLine(writingArea);
                setMode(EditorMode.INSERT);
            } else if (c == 'w') {
                lastCommand = "cw";
                clipboardManager.deleteWord(writingArea);
                setMode(EditorMode.INSERT);
            }
            pendingKey = '\0';
            pendingCount = "";
        }

    }

    // Insert mode key handling
    private void handleInsertMode(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            setMode(EditorMode.NORMAL);
            // Move cursor back one position (Vim behavior)
            int pos = writingArea.getCaretPosition();
            if (pos > 0) {
                writingArea.setCaretPosition(pos - 1);
            }
        }
    }

    // Visual mode key handling
    private void handleVisualMode(KeyEvent e) {
        char c = e.getKeyChar();
        int code = e.getKeyCode();

        if (code == KeyEvent.VK_ESCAPE) {
            setMode(EditorMode.NORMAL);
            writingArea.setSelectionStart(writingArea.getCaretPosition());
            writingArea.setSelectionEnd(writingArea.getCaretPosition());
            return;
        }

        // Update selection as cursor moves
        int currentPos = writingArea.getCaretPosition();

        // Navigation (same as normal mode)
        if (code == KeyEvent.VK_UP || c == 'k') moveUp();
        else if (code == KeyEvent.VK_DOWN || c == 'j') moveDown();
        else if (code == KeyEvent.VK_LEFT || c == 'h') moveLeft();
        else if (code == KeyEvent.VK_RIGHT || c == 'l') moveRight();
        else if (c == 'w') moveWordForward();
        else if (c == 'b') moveWordBackward();
        else if (c == 'e') moveWordEnd();
        else if (c == '0') moveLineStart();
        else if (c == '$') moveLineEnd();

        // Update selection
        int newPos = writingArea.getCaretPosition();
        if (visualStartPos < newPos) {
            writingArea.setSelectionStart(visualStartPos);
            writingArea.setSelectionEnd(newPos);
        } else {
            writingArea.setSelectionStart(newPos);
            writingArea.setSelectionEnd(visualStartPos);
        }

        // Operations on selection
        if (c == 'y') {
            String selected = writingArea.getSelectedText();
            if (selected != null) {
                clipboardManager.yankSelection(selected);
                showMessage("Selection yanked");
            }
            setMode(EditorMode.NORMAL);
        } else if (c == 'd') {
            String selected = writingArea.getSelectedText();
            if (selected != null) {
                clipboardManager.yankSelection(selected);
                writingArea.replaceSelection("");
                markModified();
                showMessage("Selection deleted");
            }
            setMode(EditorMode.NORMAL);
        } else if (c == 'c') {
            String selected = writingArea.getSelectedText();
            if (selected != null) {
                clipboardManager.yankSelection(selected);
                writingArea.replaceSelection("");
                markModified();
            }
            setMode(EditorMode.INSERT);
        }
    }

    // Replace mode key handling
    private void handleReplaceMode(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            setMode(EditorMode.NORMAL);
            return;
        }

        // In replace mode, overwrite character at cursor
        if (!e.isControlDown() && !e.isAltDown()) {
            char c = e.getKeyChar();
            if (c != KeyEvent.CHAR_UNDEFINED && c != '\n') {
                int pos = writingArea.getCaretPosition();
                String text = writingArea.getText();

                if (pos < text.length()) {
                    // Replace character
                    writingArea.replaceRange(String.valueOf(c), pos, pos + 1);
                    markModified();
                } else {
                    // At end of text, just insert
                    writingArea.insert(String.valueOf(c), pos);
                    markModified();
                }
            }
        }
    }

    // Command mode key handling
    private void handleCommandMode(KeyEvent e) {
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (code == KeyEvent.VK_ESCAPE) {
            commandBuffer = "";
            setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            String result = commandHandler.execute(commandBuffer);
            showMessage(result);
            commandBuffer = "";
            setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_BACK_SPACE) {
            if (commandBuffer.length() > 1) {
                commandBuffer = commandBuffer.substring(0, commandBuffer.length() - 1);
            } else {
                commandBuffer = "";
                setMode(EditorMode.NORMAL);
            }
            return;
        }

        // Append character to command buffer
        if (c != KeyEvent.CHAR_UNDEFINED && !e.isControlDown()) {
            commandBuffer += c;
        }
    }

    // Movement methods
    private void moveUp() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            if (line > 0) {
                int prevLineStart = writingArea.getLineStartOffset(line - 1);
                int prevLineEnd = writingArea.getLineEndOffset(line - 1);
                int col = pos - writingArea.getLineStartOffset(line);
                int newPos = Math.min(prevLineStart + col, prevLineEnd - 1);
                writingArea.setCaretPosition(newPos);
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void moveDown() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int totalLines = writingArea.getLineCount();
            if (line < totalLines - 1) {
                int nextLineStart = writingArea.getLineStartOffset(line + 1);
                int nextLineEnd = writingArea.getLineEndOffset(line + 1);
                int col = pos - writingArea.getLineStartOffset(line);
                int newPos = Math.min(nextLineStart + col, nextLineEnd - 1);
                writingArea.setCaretPosition(newPos);
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void moveLeft() {
        int pos = writingArea.getCaretPosition();
        if (pos > 0) {
            writingArea.setCaretPosition(pos - 1);
        }
    }

    private void moveRight() {
        int pos = writingArea.getCaretPosition();
        if (pos < writingArea.getText().length()) {
            writingArea.setCaretPosition(pos + 1);
        }
    }

    private void moveWordForward() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();

        // Skip current word
        while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        // Skip whitespace
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }

        writingArea.setCaretPosition(Math.min(pos, text.length()));
    }

    private void moveWordBackward() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();

        if (pos > 0) {
            pos--;
            // Skip whitespace
            while (pos > 0 && Character.isWhitespace(text.charAt(pos))) {
                pos--;
            }
            // Skip word
            while (pos > 0 && !Character.isWhitespace(text.charAt(pos))) {
                pos--;
            }
            if (pos > 0) pos++;
        }

        writingArea.setCaretPosition(pos);
    }

    private void moveWordEnd() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();

        if (pos < text.length()) {
            pos++;
            // Skip whitespace
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            // Move to end of word
            while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (pos > 0) pos--;
        }

        writingArea.setCaretPosition(Math.min(pos, text.length()));
    }

    private void moveLineStart() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int lineStart = writingArea.getLineStartOffset(line);
            writingArea.setCaretPosition(lineStart);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void moveLineEnd() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int lineEnd = writingArea.getLineEndOffset(line);
            writingArea.setCaretPosition(Math.max(lineEnd - 1, 0));
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void moveFileStart() {
        writingArea.setCaretPosition(0);
    }

    private void moveFileEnd() {
        writingArea.setCaretPosition(writingArea.getText().length());
    }

    private void scrollHalfPageDown() {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            Point current = new Point(visible.x, visible.y + visible.height / 2);
            writingArea.scrollRectToVisible(new Rectangle(current.x, current.y, visible.width, visible.height));

            // Move cursor down as well
            int pos = writingArea.viewToModel2D(current);
            writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void scrollHalfPageUp() {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            Point current = new Point(visible.x, Math.max(0, visible.y - visible.height / 2));
            writingArea.scrollRectToVisible(new Rectangle(current.x, current.y, visible.width, visible.height));

            // Move cursor up as well
            int pos = writingArea.viewToModel2D(current);
            writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Repeat last command
    private void repeatLastCommand() {
        if (lastCommand.isEmpty()) {
            showMessage("No command to repeat");
            return;
        }

        switch (lastCommand) {
            case "dd":
                clipboardManager.deleteLine(writingArea);
                markModified();
                break;
            case "yy":
                clipboardManager.yankLine(writingArea);
                break;
            case "dw":
                clipboardManager.deleteWord(writingArea);
                markModified();
                break;
            case "cc":
                clipboardManager.deleteLine(writingArea);
                setMode(EditorMode.INSERT);
                break;
            case "cw":
                clipboardManager.deleteWord(writingArea);
                setMode(EditorMode.INSERT);
                break;
            case "D":
                clipboardManager.deleteToEndOfLine(writingArea);
                markModified();
                break;
            case "C":
                clipboardManager.deleteToEndOfLine(writingArea);
                setMode(EditorMode.INSERT);
                break;
        }
        showMessage("Repeated: " + lastCommand);
    }

    // Mode management
    private void setMode(EditorMode mode) {
        this.currentMode = mode;
        writingArea.setEditable(mode.isEditable());
        writingArea.setBackground(getModeBackground(mode));
        updateStatusBar();
    }

    private Color getModeBackground(EditorMode mode) {
        switch (mode) {
            case INSERT:
                return configManager.getInsertColor();
            case VISUAL:
                return configManager.getVisualColor();
            case REPLACE:
                return configManager.getReplaceColor();
            case COMMAND:
                return configManager.getCommandColor();
            case NORMAL:
            default:
                return configManager.getNormalColor();
        }
    }

    // Status bar update
    private void updateStatusBar() {
        StringBuilder status = new StringBuilder();

        // Show command buffer if in command mode
        if (currentMode == EditorMode.COMMAND && !commandBuffer.isEmpty()) {
            status.append(commandBuffer).append(" ");
        } else {
            // File name
            FileBuffer buffer = getCurrentBuffer();
            if (buffer != null) {
                status.append(buffer.getDisplayName());
                if (buffer.isModified()) {
                    status.append(" [+]");
                }
                status.append("  ");
            }

            // Cursor position
            try {
                int pos = writingArea.getCaretPosition();
                int line = writingArea.getLineOfOffset(pos);
                int col = pos - writingArea.getLineStartOffset(line);
                status.append((line + 1)).append(":").append((col + 1)).append("  ");
            } catch (BadLocationException e) {
                status.append("1:1  ");
            }

            // Mode
            status.append(currentMode.getDisplayName()).append("  ");

            // Encoding
            if (buffer != null) {
                status.append(buffer.getEncoding()).append("  ");
            }

            // Line count
            int lineCount = writingArea.getLineCount();
            status.append(lineCount).append(" line").append(lineCount != 1 ? "s" : "");
        }

        statusBar.setText(status.toString());
    }

    private void handleDocumentChange() {
        if (!suppressDocumentEvents) {
            markModified();
        }
    }

    private void withSuppressedDocumentEvents(Runnable action) {
        suppressDocumentEvents = true;
        try {
            action.run();
        } finally {
            suppressDocumentEvents = false;
        }
    }

    private void detachUndoManager() {
        if (undoManager != null) {
            writingArea.getDocument().removeUndoableEditListener(undoManager);
        }
    }

    private void attachUndoManager(UndoManager newUndoManager) {
        detachUndoManager();
        undoManager = newUndoManager;
        if (undoManager != null) {
            writingArea.getDocument().addUndoableEditListener(undoManager);
        }
    }

    private void loadBufferIntoEditor(FileBuffer buffer) {
        detachUndoManager();
        withSuppressedDocumentEvents(() -> writingArea.setText(buffer.getContent()));
        attachUndoManager(buffer.getUndoManager());
        writingArea.setCaretPosition(0);
        updateStatusBar();
    }

    private void persistCurrentBufferState() {
        FileBuffer buffer = getCurrentBuffer();
        if (buffer != null) {
            buffer.setContent(writingArea.getText(), buffer.isModified());
        }
    }

    // Mark buffer as modified
    private void markModified() {
        FileBuffer buffer = getCurrentBuffer();
        if (buffer != null) {
            buffer.setModified(true);
            updateStatusBar();
        }
    }

    // Show message in status bar
    public void showMessage(String message) {
        statusBar.setText(message);

        // Reset status bar after 3 seconds
        Timer timer = new Timer(3000, e -> updateStatusBar());
        timer.setRepeats(false);
        timer.start();
    }

    // File operations
    private void openFileChooser() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        int result = fileChooser.showOpenDialog(this);

        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try {
                openFile(file);
            } catch (Exception e) {
                showMessage("Error opening file: " + e.getMessage());
            }
        }
    }

    private void openLandingPage() {
        StringBuilder builder = new StringBuilder();
        builder.append("shed ").append(VERSION).append("\n");
        builder.append("swing modal editor\n\n");
        builder.append(":help        view help\n");
        builder.append(":e <file>    open a file\n");
        builder.append(":recent      show recent files\n");
        builder.append("Ctrl-p       file finder (planned from bitsy parity)\n\n");
        builder.append("note: this is a scratch buffer and can be edited.\n");

        FileBuffer landing = FileBuffer.createScratch("[landing]", builder.toString());
        if (buffers.isEmpty()) {
            buffers.add(landing);
            currentBufferIndex = 0;
        } else {
            buffers.set(0, landing);
            currentBufferIndex = 0;
        }
        loadBufferIntoEditor(landing);
    }

    public void openFile(File file) throws IOException {
        persistCurrentBufferState();

        FileBuffer existing = findBufferByPath(file);
        if (existing != null) {
            currentBufferIndex = buffers.indexOf(existing);
            loadBufferIntoEditor(existing);
            return;
        }

        FileBuffer buffer;
        if (file.exists()) {
            buffer = new FileBuffer(file, configManager);
        } else {
            buffer = new FileBuffer(file.getAbsolutePath());
        }

        if (shouldReplaceSingleLandingBuffer()) {
            buffers.set(0, buffer);
            currentBufferIndex = 0;
        } else {
            buffers.add(buffer);
            currentBufferIndex = buffers.size() - 1;
        }
        loadBufferIntoEditor(buffer);
        addToRecentFiles(file.getAbsolutePath());
        if (buffer.isShowingPreviewOnly()) {
            showMessage("Large-file preview loaded");
        }
    }

    // Buffer management methods (called by CommandHandler)
    public FileBuffer getCurrentBuffer() {
        if (currentBufferIndex >= 0 && currentBufferIndex < buffers.size()) {
            return buffers.get(currentBufferIndex);
        }
        return null;
    }

    public JTextArea getTextArea() {
        return writingArea;
    }

    public String nextBuffer() {
        if (buffers.isEmpty()) {
            return "No buffers open";
        }

        int nextIndex = (currentBufferIndex + 1) % buffers.size();
        switchToBuffer(nextIndex);
        return "Buffer " + (currentBufferIndex + 1) + " of " + buffers.size();
    }

    public String prevBuffer() {
        if (buffers.isEmpty()) {
            return "No buffers open";
        }

        int prevIndex = currentBufferIndex - 1;
        if (prevIndex < 0) {
            prevIndex = buffers.size() - 1;
        }
        switchToBuffer(prevIndex);
        return "Buffer " + (currentBufferIndex + 1) + " of " + buffers.size();
    }

    public String listBuffers() {
        if (buffers.isEmpty()) {
            return "No buffers open";
        }

        StringBuilder list = new StringBuilder("Buffers:\n");
        for (int i = 0; i < buffers.size(); i++) {
            FileBuffer buf = buffers.get(i);
            list.append(i + 1).append(": ").append(buf.getDisplayName());
            if (buf.isModified()) {
                list.append(" [+]");
            }
            if (i == currentBufferIndex) {
                list.append(" (current)");
            }
            list.append("\n");
        }

        showBufferListDialog(list.toString());
        return "";
    }

    public String deleteBuffer(boolean force) {
        if (buffers.isEmpty()) {
            return "No buffers to delete";
        }

        FileBuffer buffer = getCurrentBuffer();
        if (!force && buffer != null && buffer.isModified() && buffers.size() > 1) {
            return "Error: No write since last change (use :bd! to override)";
        }

        if (buffers.size() == 1) {
            if (!force && hasUnsavedChanges(buffer)) {
                int result = confirmDiscardChanges("Close the last buffer and quit anyway?");
                if (result != JOptionPane.YES_OPTION) {
                    return "Buffer close cancelled";
                }
            }
            closeEditor();
            return "Last buffer closed";
        }

        buffers.remove(currentBufferIndex);

        if (currentBufferIndex >= buffers.size()) {
            currentBufferIndex = buffers.size() - 1;
        }

        loadBufferIntoEditor(buffers.get(currentBufferIndex));
        return "Buffer deleted";
    }

    private void switchToBuffer(int index) {
        if (index < 0 || index >= buffers.size()) {
            return;
        }

        persistCurrentBufferState();

        FileBuffer newBuffer = buffers.get(index);
        currentBufferIndex = index;
        loadBufferIntoEditor(newBuffer);
    }

    private FileBuffer findBufferByPath(File file) {
        if (file == null) {
            return null;
        }
        String targetPath = file.getAbsolutePath();
        for (FileBuffer buffer : buffers) {
            if (buffer.hasFilePath() && targetPath.equals(buffer.getFilePath())) {
                return buffer;
            }
        }
        return null;
    }

    private boolean shouldReplaceSingleLandingBuffer() {
        if (buffers.size() != 1) {
            return false;
        }
        FileBuffer current = buffers.get(0);
        return current.isScratch() && "[landing]".equals(current.getDisplayName()) && !current.isModified();
    }

    // Search methods
    public String search(String pattern) {
        return searchManager.search(pattern, false);
    }

    public String substitute(String pattern, String replacement, boolean wholeBuffer, boolean replaceAll) {
        if (wholeBuffer) {
            // Search entire buffer
            searchManager.search(pattern, false);
            if (searchManager.getMatchCount() == 0) {
                return "Pattern not found: " + pattern;
            }

            if (replaceAll) {
                return searchManager.replaceAll(replacement);
            } else {
                return searchManager.replaceCurrent(replacement);
            }
        } else {
            return substituteCurrentLine(pattern, replacement, replaceAll);
        }
    }

    private String substituteCurrentLine(String pattern, String replacement, boolean replaceAll) {
        try {
            int caretPosition = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(caretPosition);
            int lineStart = writingArea.getLineStartOffset(line);
            int lineEnd = writingArea.getLineEndOffset(line);
            String lineText = writingArea.getText().substring(lineStart, lineEnd);

            ReplacementResult result = replaceLiteralIgnoreCase(lineText, pattern, replacement, replaceAll);
            if (result.matchCount == 0) {
                return "Pattern not found: " + pattern;
            }

            writingArea.replaceRange(result.updatedText, lineStart, lineEnd);
            writingArea.setCaretPosition(Math.min(lineStart + result.firstMatchOffset, writingArea.getText().length()));
            searchManager.clearHighlights();

            return "Replaced " + result.matchCount + " occurrence" + (result.matchCount == 1 ? "" : "s");
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }

    private ReplacementResult replaceLiteralIgnoreCase(String text, String pattern, String replacement, boolean replaceAll) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerPattern = pattern.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        int searchFrom = 0;
        int matchCount = 0;
        int firstMatchOffset = -1;

        while (searchFrom <= text.length()) {
            int matchIndex = lowerText.indexOf(lowerPattern, searchFrom);
            if (matchIndex < 0) {
                break;
            }

            if (firstMatchOffset < 0) {
                firstMatchOffset = matchIndex;
            }

            builder.append(text, searchFrom, matchIndex);
            builder.append(replacement);
            searchFrom = matchIndex + pattern.length();
            matchCount++;

            if (!replaceAll) {
                break;
            }
        }

        if (matchCount == 0) {
            return new ReplacementResult(text, 0, -1);
        }

        builder.append(text.substring(searchFrom));
        return new ReplacementResult(builder.toString(), matchCount, firstMatchOffset);
    }

    // Line number toggle
    public void toggleLineNumbers(boolean enabled) {
        lineNumbersEnabled = enabled;
        JScrollPane scrollPane = (JScrollPane) writingArea.getParent().getParent();

        if (enabled) {
            scrollPane.setRowHeaderView(lineNumberPanel);
        } else {
            scrollPane.setRowHeaderView(null);
        }

        scrollPane.revalidate();
        scrollPane.repaint();
    }

    // Go to line
    public String gotoLine(int lineNum) {
        try {
            int totalLines = writingArea.getLineCount();
            if (lineNum < 1 || lineNum > totalLines) {
                return "Invalid line number: " + lineNum;
            }

            int offset = writingArea.getLineStartOffset(lineNum - 1);
            writingArea.setCaretPosition(offset);
            return "Line " + lineNum;
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }

    // Help system
    public void showHelp(String topic) {
        String helpText = getHelpText(topic);
        openScratchBuffer(topic == null || topic.isEmpty() ? "[help]" : "[help " + topic + "]", helpText, true);
    }

    private String getHelpText(String topic) {
        if (topic.isEmpty()) {
            return "Shed v" + VERSION + "\n\n" +
                   "NORMAL MODE\n" +
                   "  h/j/k/l        Move left/down/up/right\n" +
                   "  w/b/e          Move by word\n" +
                   "  0/$            Line start/end\n" +
                   "  gg/G           File start/end\n" +
                   "  i/v/R          Insert/visual/replace\n" +
                   "  yy/dd/cc       Yank/delete/change line\n" +
                   "  dw/cw          Delete/change word\n" +
                   "  D/C            Delete/change to end of line\n" +
                   "  p/P            Paste after/before\n" +
                   "  u/Ctrl-r       Undo/redo\n" +
                   "  /pattern       Search forward\n" +
                   "  n/N            Next/previous match\n" +
                   "  .              Repeat last command\n\n" +
                   "COMMANDS\n" +
                   "  :w [file]      Write current buffer\n" +
                   "  :q / :q!       Quit buffer/editor\n" +
                   "  :wq / :x       Write and quit\n" +
                   "  :e file        Edit file\n" +
                   "  :bn / :bp      Next/previous buffer\n" +
                   "  :ls            List buffers\n" +
                   "  :bd            Delete buffer\n" +
                   "  :recent        Show recent files\n" +
                   "  :set nu        Enable line numbers\n" +
                   "  :45            Go to line 45\n" +
                   "  :s/a/b         Substitute current line\n" +
                   "  :%s/a/b/g      Substitute whole buffer\n\n" +
                   "note: this is a help buffer. use :q to return.\n";
        } else {
            return "Shed help: " + topic + "\n\n" +
                   "General help is currently the authoritative reference.\n" +
                   "Use :q to return to the previous buffer.";
        }
    }

    // Recent files management
    private void addToRecentFiles(String filepath) {
        if (filepath == null || filepath.isEmpty()) {
            return;
        }

        recentFiles.remove(filepath);
        recentFiles.add(0, filepath);
        while (recentFiles.size() > 50) {
            recentFiles.remove(recentFiles.size() - 1);
        }
        saveRecentFiles();
    }

    public String showRecentFiles() {
        if (recentFiles.isEmpty()) {
            return "No recent files";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Recent files\n\n");
        for (int i = 0; i < recentFiles.size(); i++) {
            builder.append(i + 1).append(". ").append(recentFiles.get(i)).append("\n");
        }
        builder.append("\nuse :e <path> to reopen a file.");
        openScratchBuffer("[recent files]", builder.toString(), true);
        return "Showing recent files";
    }

    private void showBufferListDialog(String list) {
        JTextArea textArea = new JTextArea(list);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(400, 200));

        JOptionPane.showMessageDialog(this, scrollPane, "Buffer List", JOptionPane.INFORMATION_MESSAGE);
    }

    // Quit handling
    private void handleQuit(boolean force) {
        String message = requestQuit(force);
        if (!"Quitting".equals(message)) {
            showMessage(message);
        }
    }

    private boolean hasUnsavedChanges(FileBuffer buffer) {
        return buffer != null && buffer.isModified();
    }

    private int confirmDiscardChanges(String prompt) {
        return JOptionPane.showConfirmDialog(this,
            prompt,
            "Unsaved Changes",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
    }

    public String requestQuit(boolean force) {
        if (closeReturnableScratchBuffer()) {
            return "Returned from scratch buffer";
        }

        FileBuffer buffer = getCurrentBuffer();
        if (!force && hasUnsavedChanges(buffer)) {
            int result = confirmDiscardChanges("File has unsaved changes. Quit anyway?");
            if (result != JOptionPane.YES_OPTION) {
                return "Quit cancelled";
            }
        }

        closeEditor();
        return "Quitting";
    }

    public void showScratchBuffer(String title, String content) {
        openScratchBuffer(title, content, true);
    }

    private void openScratchBuffer(String title, String content, boolean returnable) {
        persistCurrentBufferState();

        FileBuffer scratchBuffer = FileBuffer.createScratch(title, content);
        FileBuffer returnBuffer = getCurrentBuffer();
        int returnCaretPosition = writingArea.getCaretPosition();

        buffers.add(scratchBuffer);
        currentBufferIndex = buffers.size() - 1;
        if (returnable && returnBuffer != null) {
            specialBufferReturns.push(new SpecialBufferReturnState(scratchBuffer, returnBuffer, returnCaretPosition));
        }
        loadBufferIntoEditor(scratchBuffer);
    }

    private boolean closeReturnableScratchBuffer() {
        FileBuffer current = getCurrentBuffer();
        if (current == null || specialBufferReturns.isEmpty()) {
            return false;
        }

        SpecialBufferReturnState state = specialBufferReturns.peek();
        if (state.scratchBuffer != current) {
            return false;
        }

        specialBufferReturns.pop();
        buffers.remove(currentBufferIndex);

        int returnIndex = buffers.indexOf(state.returnBuffer);
        if (returnIndex < 0) {
            if (buffers.isEmpty()) {
                openLandingPage();
                return true;
            }
            returnIndex = Math.max(0, currentBufferIndex - 1);
        }

        currentBufferIndex = returnIndex;
        loadBufferIntoEditor(buffers.get(returnIndex));
        writingArea.setCaretPosition(Math.min(state.returnCaretPosition, writingArea.getText().length()));
        return true;
    }

    private void loadRecentFiles() {
        recentFiles.clear();
        if (!recentFilesStore.exists()) {
            return;
        }
        try {
            recentFiles.addAll(Files.readAllLines(recentFilesStore.toPath(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private void saveRecentFiles() {
        try {
            Files.write(recentFilesStore.toPath(), recentFiles, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public void closeEditor() {
        if (closingDown) {
            return;
        }
        closingDown = true;
        dispose();
        System.exit(0);
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void keyReleased(KeyEvent e) {}

    private static class ReplacementResult {
        private final String updatedText;
        private final int matchCount;
        private final int firstMatchOffset;

        private ReplacementResult(String updatedText, int matchCount, int firstMatchOffset) {
            this.updatedText = updatedText;
            this.matchCount = matchCount;
            this.firstMatchOffset = firstMatchOffset;
        }
    }

    private static class SpecialBufferReturnState {
        private final FileBuffer scratchBuffer;
        private final FileBuffer returnBuffer;
        private final int returnCaretPosition;

        private SpecialBufferReturnState(FileBuffer scratchBuffer, FileBuffer returnBuffer, int returnCaretPosition) {
            this.scratchBuffer = scratchBuffer;
            this.returnBuffer = returnBuffer;
            this.returnCaretPosition = returnCaretPosition;
        }
    }

    // Main method
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Texteditor(args));
    }
}

// Line Number Panel for displaying line numbers
class LineNumberPanel extends JPanel {
    private JTextArea textArea;

    public LineNumberPanel(JTextArea textArea) {
        this.textArea = textArea;
        setPreferredSize(new Dimension(40, Integer.MAX_VALUE));
        setBackground(Color.LIGHT_GRAY);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        g.setColor(Color.BLACK);
        g.setFont(textArea.getFont());

        FontMetrics fm = g.getFontMetrics();
        int lineHeight = fm.getHeight();
        int lineCount = textArea.getLineCount();

        try {
            Rectangle visible = textArea.getVisibleRect();
            int startLine = textArea.getLineOfOffset(textArea.viewToModel2D(new Point(0, visible.y)));
            int endLine = textArea.getLineOfOffset(textArea.viewToModel2D(new Point(0, visible.y + visible.height)));

            for (int i = startLine; i <= endLine && i < lineCount; i++) {
                int lineStart = textArea.getLineStartOffset(i);
                Point p = textArea.modelToView2D(lineStart).getBounds().getLocation();
                int y = p.y + fm.getAscent();

                String lineNum = String.valueOf(i + 1);
                int x = getWidth() - fm.stringWidth(lineNum) - 5;
                g.drawString(lineNum, x, y);
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }
}
