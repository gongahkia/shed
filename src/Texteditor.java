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
import java.util.ArrayList;
import java.util.List;

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
        visualStartPos = -1;
        lastCommand = "";

        // Initialize UI
        initializeUI();

        // Initialize managers that depend on UI
        clipboardManager = new ClipboardManager();
        searchManager = new SearchManager(writingArea);
        commandHandler = new CommandHandler(this);

        // Open file from command line or file chooser
        if (args.length > 0) {
            try {
                File file = new File(args[0]);
                openFile(file);
            } catch (Exception e) {
                showMessage("Error opening file: " + e.getMessage());
            }
        } else {
            openFileChooser();
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
        try {
            Font customFont = Font.createFont(Font.TRUETYPE_FONT,
                new File("assets/hackregfont.ttf")).deriveFont((float)configManager.getFontSize());
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(customFont);
            writingArea.setFont(customFont);
        } catch (Exception e) {
            writingArea.setFont(new Font("Monospaced", Font.PLAIN, configManager.getFontSize()));
        }

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
            public void insertUpdate(DocumentEvent e) { markModified(); }
            public void removeUpdate(DocumentEvent e) { markModified(); }
            public void changedUpdate(DocumentEvent e) { markModified(); }
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
            moveFileEnd();
        }

        // Clipboard operations
        else if (c == 'y') {
            pendingKey = 'y';
        } else if (c == 'd') {
            pendingKey = 'd';
        } else if (c == 'c') {
            pendingKey = 'c';
        } else if (c == 'x') {
            clipboardManager.deleteChar(writingArea);
            markModified();
        } else if (c == 'p') {
            clipboardManager.paste(writingArea, false);
            markModified();
        } else if (c == 'P') {
            clipboardManager.paste(writingArea, true);
            markModified();
        }

        // Undo/Redo
        else if (c == 'u') {
            if (undoManager.canUndo()) {
                undoManager.undo();
            }
        } else if (e.isControlDown() && c == 'r') {
            if (undoManager.canRedo()) {
                undoManager.redo();
            }
        }

        // Search navigation
        else if (c == 'n') {
            showMessage(searchManager.nextMatch());
        } else if (c == 'N') {
            showMessage(searchManager.prevMatch());
        }

        // Repeat last command
        else if (c == '.') {
            repeatLastCommand();
        }

        // Ctrl combinations
        else if (e.isControlDown()) {
            if (c == 'd' || code == KeyEvent.VK_D) {
                scrollHalfPageDown();
            } else if (c == 'u' || code == KeyEvent.VK_U) {
                scrollHalfPageUp();
            }
        }

        // Escape (no-op in normal mode, but clear any messages)
        else if (code == KeyEvent.VK_ESCAPE) {
            showMessage("Already in normal mode");
        }
    }

    // Handle pending multi-key commands
    private void handlePendingKey(char c, int code) {
        if (pendingKey == 'g') {
            if (c == 'g') {
                moveFileStart();
            }
            pendingKey = '\0';
        } else if (pendingKey == 'y') {
            if (c == 'y') {
                lastCommand = "yy";
                clipboardManager.yankLine(writingArea);
                showMessage("Line yanked");
            }
            pendingKey = '\0';
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
        }

        // Handle 'D' - delete to end of line
        if (c == 'D') {
            lastCommand = "D";
            clipboardManager.deleteToEndOfLine(writingArea);
            markModified();
            pendingKey = '\0';
        }

        // Handle 'C' - change to end of line
        if (c == 'C') {
            lastCommand = "C";
            clipboardManager.deleteToEndOfLine(writingArea);
            setMode(EditorMode.INSERT);
            pendingKey = '\0';
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
        writingArea.setBackground(mode.getBackgroundColor());
        updateStatusBar();
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

    public void openFile(File file) throws IOException {
        FileBuffer buffer = new FileBuffer(file);
        buffers.add(buffer);
        currentBufferIndex = buffers.size() - 1;

        writingArea.setText(buffer.getContent());
        undoManager = buffer.getUndoManager();
        writingArea.getDocument().addUndoableEditListener(undoManager);
        writingArea.setCaretPosition(0);

        updateStatusBar();
        addToRecentFiles(file.getAbsolutePath());
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

        currentBufferIndex = (currentBufferIndex + 1) % buffers.size();
        switchToBuffer(currentBufferIndex);
        return "Buffer " + (currentBufferIndex + 1) + " of " + buffers.size();
    }

    public String prevBuffer() {
        if (buffers.isEmpty()) {
            return "No buffers open";
        }

        currentBufferIndex--;
        if (currentBufferIndex < 0) {
            currentBufferIndex = buffers.size() - 1;
        }
        switchToBuffer(currentBufferIndex);
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
        if (!force && buffer != null && buffer.isModified()) {
            return "Error: No write since last change (use :bd! to override)";
        }

        buffers.remove(currentBufferIndex);

        if (buffers.isEmpty()) {
            closeEditor();
            return "Last buffer closed";
        } else {
            if (currentBufferIndex >= buffers.size()) {
                currentBufferIndex = buffers.size() - 1;
            }
            switchToBuffer(currentBufferIndex);
            return "Buffer deleted";
        }
    }

    private void switchToBuffer(int index) {
        if (index < 0 || index >= buffers.size()) {
            return;
        }

        // Save current buffer content
        if (currentBufferIndex >= 0 && currentBufferIndex < buffers.size()) {
            FileBuffer oldBuffer = buffers.get(currentBufferIndex);
            oldBuffer.setContent(writingArea.getText());
        }

        // Load new buffer
        FileBuffer newBuffer = buffers.get(index);
        writingArea.setText(newBuffer.getContent());
        undoManager = newBuffer.getUndoManager();
        writingArea.setCaretPosition(0);

        updateStatusBar();
    }

    // Search methods
    public String search(String pattern) {
        return searchManager.search(pattern, false);
    }

    public String substitute(String pattern, String replacement, boolean global, boolean replaceAll) {
        if (global) {
            // Search entire buffer
            String result = searchManager.search(pattern, false);
            if (searchManager.getMatchCount() == 0) {
                return "Pattern not found: " + pattern;
            }

            if (replaceAll) {
                return searchManager.replaceAll(replacement);
            } else {
                return searchManager.replaceCurrent(replacement);
            }
        } else {
            // Current line only
            return "Line-level substitute not yet implemented";
        }
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
        JTextArea textArea = new JTextArea(helpText);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(600, 400));

        JOptionPane.showMessageDialog(this, scrollPane, "Shed Help", JOptionPane.INFORMATION_MESSAGE);
    }

    private String getHelpText(String topic) {
        if (topic.isEmpty()) {
            return "SHED - SHit EDitor v" + VERSION + "\n\n" +
                   "NORMAL MODE:\n" +
                   "  h/j/k/l      - Move left/down/up/right\n" +
                   "  w/b/e        - Word forward/backward/end\n" +
                   "  0/$          - Line start/end\n" +
                   "  gg/G         - File start/end\n" +
                   "  Ctrl+d/u     - Half page down/up\n" +
                   "  i            - Enter INSERT mode\n" +
                   "  v            - Enter VISUAL mode\n" +
                   "  R            - Enter REPLACE mode\n" +
                   "  yy           - Yank (copy) line\n" +
                   "  dd           - Delete line\n" +
                   "  dw           - Delete word\n" +
                   "  D            - Delete to end of line\n" +
                   "  cc           - Change line\n" +
                   "  cw           - Change word\n" +
                   "  C            - Change to end of line\n" +
                   "  x            - Delete character\n" +
                   "  p/P          - Paste after/before\n" +
                   "  u            - Undo\n" +
                   "  Ctrl+r       - Redo\n" +
                   "  /pattern     - Search\n" +
                   "  n/N          - Next/previous match\n" +
                   "  .            - Repeat last command\n" +
                   "  :            - Enter command mode\n\n" +
                   "COMMANDS:\n" +
                   "  :w           - Write (save)\n" +
                   "  :q           - Quit\n" +
                   "  :q!          - Force quit\n" +
                   "  :wq          - Write and quit\n" +
                   "  :e file      - Edit file\n" +
                   "  :bn/:bp      - Next/previous buffer\n" +
                   "  :ls          - List buffers\n" +
                   "  :bd          - Delete buffer\n" +
                   "  :set nu      - Enable line numbers\n" +
                   "  :set nonu    - Disable line numbers\n" +
                   "  :45          - Go to line 45\n" +
                   "  :wc          - Word count\n" +
                   "  :%s/old/new/g - Replace all\n" +
                   "  :help        - Show this help\n";
        } else {
            return "No specific help for: " + topic + "\nUse :help for general help.";
        }
    }

    // Recent files management
    private void addToRecentFiles(String filepath) {
        // TODO: Implement recent files tracking
    }

    public String showRecentFiles() {
        return "Recent files feature not yet implemented";
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
        FileBuffer buffer = getCurrentBuffer();

        if (!force && buffer != null && buffer.isModified()) {
            int result = JOptionPane.showConfirmDialog(this,
                "File has unsaved changes. Quit anyway?",
                "Unsaved Changes",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }

        closeEditor();
    }

    public void closeEditor() {
        this.dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
        System.exit(0);
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void keyReleased(KeyEvent e) {}

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
