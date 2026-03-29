// SHit EDitor (Shed) Version 2.0 <Refactored Build>

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class Texteditor extends JFrame implements KeyListener {
    private static final long serialVersionUID = 1L;

    // Core components
    private EditorMode currentMode;
    private JTextArea writingArea;
    private JLabel statusBar;
    private JLabel commandBar;
    private LineNumberPanel lineNumberPanel;
    private JScrollPane editorScrollPane;
    private JPanel editorHostPanel;

    // Managers
    private ClipboardManager clipboardManager;
    private SearchManager searchManager;
    private CommandHandler commandHandler;
    private ConfigManager configManager;

    // Buffer management
    private List<FileBuffer> buffers;
    private int currentBufferIndex;
    private List<EditorPane> editorPanes;
    private int activePaneIndex;
    private WindowLayoutNode windowLayoutRoot;
    private Component renderedLayoutComponent;

    // State variables
    private String commandBuffer;
    private String lastMessage;
    private Timer messageResetTimer;
    private UndoManager undoManager;
    private DocumentListener bufferDocumentListener;
    private String lastCommand;
    private char pendingKey; // For multi-key commands like 'gg', 'dd', etc.
    private String pendingCount;
    private boolean suppressDocumentEvents;
    private boolean closingDown;
    private List<String> recentFiles;
    private File recentFilesStore;
    private Deque<SpecialBufferReturnState> specialBufferReturns;
    private List<String> commandHistory;
    private int commandHistoryIndex;
    private String commandHistoryPrefix;
    private LineNumberMode lineNumberMode;
    private boolean searchForward;
    private String gitBranch;
    private Object currentLineHighlightTag;
    private List<Object> substitutePreviewTags;
    private Highlighter.HighlightPainter currentLinePainter;
    private Highlighter.HighlightPainter substitutePreviewPainter;
    private boolean zenModeEnabled;
    private String lastInsertedText;
    private Timer externalChangeTimer;
    private boolean reloadPromptActive;
    private List<Object> syntaxHighlightTags;
    private Highlighter.HighlightPainter syntaxKeywordPainter;
    private Highlighter.HighlightPainter syntaxStringPainter;
    private File lastPreviewedMarkdown;
    private List<Integer> jumpList;
    private int jumpIndex;
    private List<Integer> changeList;
    private int changeIndex;
    private char lastFindChar;
    private char lastFindType;

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
        editorPanes = new ArrayList<>();
        activePaneIndex = -1;
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
        commandHistory = new ArrayList<>();
        commandHistoryIndex = -1;
        commandHistoryPrefix = "";
        lineNumberMode = configManager.getLineNumberMode();
        searchForward = true;
        gitBranch = resolveGitBranch();
        substitutePreviewTags = new ArrayList<>();
        currentLinePainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x2A, 0x32, 0x3B));
        substitutePreviewPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x6D, 0x59, 0x3A));
        zenModeEnabled = false;
        lastInsertedText = "";
        reloadPromptActive = false;
        syntaxHighlightTags = new ArrayList<>();
        syntaxKeywordPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x2B, 0x4C, 0x7E));
        syntaxStringPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x5E, 0x3C, 0x4C));
        lastPreviewedMarkdown = null;
        jumpList = new ArrayList<>();
        jumpIndex = -1;
        changeList = new ArrayList<>();
        changeIndex = -1;
        lastFindChar = '\0';
        lastFindType = '\0';
        loadRecentFiles();
        lastMessage = "";

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

        externalChangeTimer = new Timer(2000, e -> checkForExternalChanges());
        externalChangeTimer.start();
    }

    // Initialize UI components
    private void initializeUI() {
        this.setTitle("Shed " + VERSION);
        this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        this.setSize(screenSize.width / 2, screenSize.height);
        this.setLayout(new BorderLayout(5, 5));
        editorHostPanel = new JPanel(new BorderLayout());
        undoManager = new UndoManager();
        bufferDocumentListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { handleDocumentChange(); }
            public void removeUpdate(DocumentEvent e) { handleDocumentChange(); }
            public void changedUpdate(DocumentEvent e) { handleDocumentChange(); }
        };

        EditorPane initialPane = createEditorPane(screenSize);
        editorPanes.add(initialPane);
        activePaneIndex = 0;
        bindActivePane(initialPane);
        windowLayoutRoot = WindowLayoutNode.leaf(initialPane);
        renderWindowLayout();

        // Create footer
        statusBar = new JLabel();
        statusBar.setBackground(Color.decode("#1D242B"));
        statusBar.setOpaque(true);
        statusBar.setPreferredSize(new Dimension(screenSize.width / 2, 30));
        statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusBar.setForeground(Color.decode("#FAF9F6"));

        commandBar = new JLabel();
        commandBar.setBackground(Color.decode("#12181F"));
        commandBar.setOpaque(true);
        commandBar.setPreferredSize(new Dimension(screenSize.width / 2, 28));
        commandBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        commandBar.setForeground(Color.decode("#C9D1D9"));

        JPanel footerPanel = new JPanel(new GridLayout(2, 1));
        footerPanel.add(statusBar);
        footerPanel.add(commandBar);

        // Add components
        this.add(editorHostPanel, BorderLayout.CENTER);
        this.add(footerPanel, BorderLayout.SOUTH);

        // Window close handler
        this.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                handleQuit(false);
            }
        });
    }

    private EditorPane createEditorPane(Dimension screenSize) {
        JTextArea textArea = new JTextArea();
        textArea.setPreferredSize(new Dimension(screenSize.width / 2, screenSize.height - 130));
        textArea.addKeyListener(this);
        textArea.setFont(resolveEditorFont());
        textArea.setTabSize(configManager.getTabSize());
        textArea.getCaret().setBlinkRate(0);
        textArea.setCaretColor(Color.decode("#02862a"));
        textArea.setForeground(Color.decode("#FAF9F6"));
        textArea.setEditable(false);
        textArea.setSelectionColor(new Color(0x4E, 0x5D, 0x6C));
        textArea.setSelectedTextColor(Color.decode("#FAF9F6"));

        LineNumberPanel paneLineNumberPanel = new LineNumberPanel(textArea);
        paneLineNumberPanel.setMode(lineNumberMode);
        paneLineNumberPanel.setHighlightCurrentLine(configManager.getShowCurrentLine());

        JScrollPane paneScrollPane = new JScrollPane(textArea);
        if (lineNumberMode != LineNumberMode.NONE) {
            paneScrollPane.setRowHeaderView(paneLineNumberPanel);
        }

        SearchManager paneSearchManager = new SearchManager(textArea);
        final EditorPane[] paneRef = new EditorPane[1];
        textArea.addCaretListener(e -> {
            if (paneRef[0] != null && paneRef[0] != getActivePane()) {
                activateEditorPane(paneRef[0]);
            }
            updateCurrentLineHighlight();
            if (lineNumberPanel != null) {
                lineNumberPanel.repaint();
            }
            updateStatusBar();
        });
        textArea.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                if (paneRef[0] != null) {
                    activateEditorPane(paneRef[0]);
                }
            }
        });
        textArea.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (paneRef[0] != null) {
                    activateEditorPane(paneRef[0]);
                }
            }
        });

        EditorPane pane = new EditorPane(textArea, paneLineNumberPanel, paneScrollPane, paneSearchManager);
        paneRef[0] = pane;
        return pane;
    }

    private void bindActivePane(EditorPane pane) {
        if (pane == null) {
            return;
        }
        writingArea = pane.getTextArea();
        lineNumberPanel = pane.getLineNumberPanel();
        editorScrollPane = pane.getScrollPane();
        searchManager = pane.getSearchManager();
    }

    private EditorPane getActivePane() {
        if (activePaneIndex < 0 || activePaneIndex >= editorPanes.size()) {
            return null;
        }
        return editorPanes.get(activePaneIndex);
    }

    private void activateEditorPane(EditorPane pane) {
        int index = editorPanes.indexOf(pane);
        if (index < 0 || index == activePaneIndex) {
            return;
        }
        activePaneIndex = index;
        bindActivePane(pane);
        updateCurrentLineHighlight();
        refreshLineNumberPanel();
        updateStatusBar();
    }

    private void renderWindowLayout() {
        if (renderedLayoutComponent != null) {
            editorHostPanel.remove(renderedLayoutComponent);
        }
        renderedLayoutComponent = windowLayoutRoot == null ? new JPanel() : windowLayoutRoot.render();
        editorHostPanel.add(renderedLayoutComponent, BorderLayout.CENTER);
        editorHostPanel.revalidate();
        editorHostPanel.repaint();
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
            case VISUAL_LINE:
                handleVisualMode(e);
                break;
            case REPLACE:
                handleReplaceMode(e);
                break;
            case COMMAND:
                handleCommandMode(e);
                break;
            case SEARCH:
                handleSearchMode(e);
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
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
            return;
        } else if (c == 'a') {
            moveRight();
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
            return;
        } else if (c == 'A') {
            moveLineEnd();
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
            return;
        } else if (c == 'I') {
            moveLineIndentStart();
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
            return;
        } else if (c == 'o') {
            openLineBelow();
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
            return;
        } else if (c == 'O') {
            openLineAbove();
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
            return;
        } else if (c == 'v') {
            setMode(EditorMode.VISUAL);
            visualStartPos = writingArea.getCaretPosition();
            return;
        } else if (c == 'V') {
            setMode(EditorMode.VISUAL_LINE);
            selectCurrentLine();
            return;
        } else if (c == 'R') {
            lastInsertedText = "";
            setMode(EditorMode.REPLACE);
            return;
        } else if (c == ':') {
            setMode(EditorMode.COMMAND);
            commandBuffer = String.valueOf(c);
            commandHistoryIndex = -1;
            commandHistoryPrefix = commandBuffer;
            updateSubstitutePreview();
            return;
        } else if (c == '/' || c == '?') {
            setMode(EditorMode.SEARCH);
            searchForward = c == '/';
            commandBuffer = String.valueOf(c);
            commandHistoryIndex = -1;
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
        } else if (c == 'm' || c == '\'' || c == '`') {
            pendingKey = c;
        } else if (c == 'f' || c == 'F' || c == 't' || c == 'T' || c == '>' || c == '<' || c == '=') {
            pendingKey = c;
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
            lastInsertedText = "";
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
        } else if (c == '*') {
            pendingCount = "";
            showMessage(searchWordUnderCursor(true));
        } else if (c == '#') {
            pendingCount = "";
            showMessage(searchWordUnderCursor(false));
        } else if (c == ';') {
            pendingCount = "";
            showMessage(repeatFind(false));
        } else if (c == ',') {
            pendingCount = "";
            showMessage(repeatFind(true));
        }

        // Repeat last command
        else if (c == '.') {
            pendingCount = "";
            repeatLastCommand();
        } else if (c == 'J') {
            pendingCount = "";
            joinCurrentLine(true);
        }

        // Ctrl combinations
        else if (e.isControlDown()) {
            if (c == 'p' || code == KeyEvent.VK_P) {
                pendingCount = "";
                showMessage(showFileFinder());
            } else if (c == 'n' || code == KeyEvent.VK_N) {
                pendingCount = "";
                showMessage(showLspCompletionStatus());
            } else if (c == 'o' || code == KeyEvent.VK_O) {
                pendingCount = "";
                jumpBack();
            } else if (c == 'i' || code == KeyEvent.VK_I) {
                pendingCount = "";
                jumpForward();
            } else if (c == 'd' || code == KeyEvent.VK_D) {
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
            } else if (c == 'J') {
                joinCurrentLine(false);
            } else if (c == ';') {
                changePrev();
            } else if (c == ',') {
                changeNext();
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
                lastInsertedText = "";
                setMode(EditorMode.INSERT);
            } else if (c == 'w') {
                lastCommand = "cw";
                clipboardManager.deleteWord(writingArea);
                lastInsertedText = "";
                setMode(EditorMode.INSERT);
            }
            pendingKey = '\0';
            pendingCount = "";
        } else if (pendingKey == 'm') {
            FileBuffer buffer = getCurrentBuffer();
            if (buffer != null) {
                buffer.setMark(c, writingArea.getCaretPosition());
                showMessage("Mark set: " + c);
            }
            pendingKey = '\0';
        } else if (pendingKey == '\'' || pendingKey == '`') {
            FileBuffer buffer = getCurrentBuffer();
            if (buffer != null) {
                Integer offset = buffer.getMark(c);
                if (offset != null) {
                    recordJumpPosition();
                    if (pendingKey == '\'') {
                        try {
                            int line = writingArea.getLineOfOffset(Math.min(offset, writingArea.getText().length()));
                            writingArea.setCaretPosition(writingArea.getLineStartOffset(line));
                        } catch (BadLocationException e) {
                            writingArea.setCaretPosition(Math.min(offset, writingArea.getText().length()));
                        }
                    } else {
                        writingArea.setCaretPosition(Math.min(offset, writingArea.getText().length()));
                    }
                } else {
                    showMessage("Mark not set: " + c);
                }
            }
            pendingKey = '\0';
        } else if (pendingKey == 'f' || pendingKey == 'F' || pendingKey == 't' || pendingKey == 'T') {
            showMessage(findCharacter(pendingKey, c));
            pendingKey = '\0';
        } else if (pendingKey == '>' || pendingKey == '<' || pendingKey == '=') {
            if (c == pendingKey) {
                showMessage(applyLineOperator(pendingKey));
            }
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
            return;
        }

        if (!e.isControlDown() && !e.isAltDown()) {
            char c = e.getKeyChar();
            if (c == '\t' && configManager.getExpandTab()) {
                writingArea.replaceSelection(" ".repeat(writingArea.getTabSize()));
                lastInsertedText += " ".repeat(writingArea.getTabSize());
                e.consume();
            } else if (c == '\n' && configManager.getAutoIndent()) {
                String indent = currentLineIndentation();
                SwingUtilities.invokeLater(() -> writingArea.insert(indent, writingArea.getCaretPosition()));
                lastInsertedText += "\n" + indent;
            } else if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
                lastInsertedText += c;
            }
        }
    }

    // Visual mode key handling
    private void handleVisualMode(KeyEvent e) {
        char c = e.getKeyChar();
        int code = e.getKeyCode();
        boolean lineMode = currentMode == EditorMode.VISUAL_LINE;

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
        if (lineMode) {
            selectLineRange(visualStartPos, newPos);
        } else {
            if (visualStartPos < newPos) {
                writingArea.setSelectionStart(visualStartPos);
                writingArea.setSelectionEnd(newPos);
            } else {
                writingArea.setSelectionStart(newPos);
                writingArea.setSelectionEnd(visualStartPos);
            }
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
            clearSubstitutePreview();
            setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            String result = commandHandler.execute(commandBuffer);
            addCommandHistory(commandBuffer);
            if (!result.isEmpty()) {
                showMessage(result);
            }
            commandBuffer = "";
            clearSubstitutePreview();
            setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_UP) {
            browseCommandHistory(-1);
            updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_DOWN) {
            browseCommandHistory(1);
            updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_TAB) {
            commandBuffer = completeCommand(commandBuffer);
            updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_BACK_SPACE) {
            if (commandBuffer.length() > 1) {
                commandBuffer = commandBuffer.substring(0, commandBuffer.length() - 1);
            } else {
                commandBuffer = "";
                clearSubstitutePreview();
                setMode(EditorMode.NORMAL);
            }
            updateSubstitutePreview();
            return;
        }

        // Append character to command buffer
        if (c != KeyEvent.CHAR_UNDEFINED && !e.isControlDown()) {
            commandBuffer += c;
            updateSubstitutePreview();
        }
    }

    private void handleSearchMode(KeyEvent e) {
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (code == KeyEvent.VK_ESCAPE) {
            commandBuffer = "";
            setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            String pattern = commandBuffer.length() > 1 ? commandBuffer.substring(1) : "";
            String result = searchForward ? searchManager.searchForward(pattern) : searchManager.searchBackward(pattern);
            if (!result.isEmpty()) {
                showMessage(result);
            }
            if (!pattern.isEmpty()) {
                addCommandHistory(commandBuffer);
            }
            commandBuffer = "";
            setMode(EditorMode.NORMAL);
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
            if (commandBuffer.length() > 1) {
                commandBuffer = commandBuffer.substring(0, commandBuffer.length() - 1);
            } else {
                commandBuffer = "";
                setMode(EditorMode.NORMAL);
            }
            return;
        }

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

    private void selectCurrentLine() {
        int caret = writingArea.getCaretPosition();
        selectLineRange(caret, caret);
        visualStartPos = writingArea.getSelectionStart();
    }

    private void selectLineRange(int anchorPosition, int currentPosition) {
        try {
            int anchorLine = writingArea.getLineOfOffset(anchorPosition);
            int currentLine = writingArea.getLineOfOffset(currentPosition);
            int startLine = Math.min(anchorLine, currentLine);
            int endLine = Math.max(anchorLine, currentLine);
            int selectionStart = writingArea.getLineStartOffset(startLine);
            int selectionEnd = writingArea.getLineEndOffset(endLine);
            writingArea.setSelectionStart(selectionStart);
            writingArea.setSelectionEnd(selectionEnd);
        } catch (BadLocationException ignored) {
        }
    }

    private String searchWordUnderCursor(boolean forward) {
        String text = writingArea.getText();
        int caret = writingArea.getCaretPosition();
        if (text.isEmpty() || caret >= text.length()) {
            return "No word under cursor";
        }

        int start = caret;
        int end = caret;
        while (start > 0 && isWordCharacter(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isWordCharacter(text.charAt(end))) {
            end++;
        }
        if (start == end) {
            return "No word under cursor";
        }

        String word = text.substring(start, end);
        return forward ? searchManager.searchForward(word) : searchManager.searchBackward(word);
    }

    private boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void browseCommandHistory(int direction) {
        if (commandHistory.isEmpty()) {
            return;
        }
        if (commandHistoryIndex < 0) {
            commandHistoryPrefix = commandBuffer;
            commandHistoryIndex = commandHistory.size();
        }

        int nextIndex = commandHistoryIndex + direction;
        nextIndex = Math.max(0, Math.min(nextIndex, commandHistory.size()));
        commandHistoryIndex = nextIndex;

        if (commandHistoryIndex >= commandHistory.size()) {
            commandBuffer = commandHistoryPrefix;
            return;
        }

        String candidate = commandHistory.get(commandHistoryIndex);
        if (!commandHistoryPrefix.isEmpty() && !candidate.startsWith(commandHistoryPrefix.substring(0, 1))) {
            return;
        }
        commandBuffer = candidate;
    }

    private void addCommandHistory(String entry) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        commandHistory.remove(entry);
        commandHistory.add(entry);
        while (commandHistory.size() > 100) {
            commandHistory.remove(0);
        }
        commandHistoryIndex = -1;
        commandHistoryPrefix = "";
    }

    private String completeCommand(String input) {
        if (input == null || input.isEmpty() || !input.startsWith(":")) {
            return input;
        }

        String withoutColon = input.substring(1);
        if (withoutColon.startsWith("e ") || withoutColon.startsWith("w ")) {
            return ":" + completePath(withoutColon.substring(0, 2), withoutColon.substring(2));
        }

        String[] knownCommands = {
            "w", "write", "q", "quit", "q!", "wq", "x", "e", "edit", "bn", "bp",
            "ls", "buffers", "bd", "set", "help", "wc", "recent", "d", "delete",
            "Files", "Buffers", "grep", "registers", "marks", "Goyo", "normal"
        };

        for (String command : knownCommands) {
            if (command.startsWith(withoutColon)) {
                return ":" + command;
            }
        }
        return input;
    }

    private String completePath(String prefix, String partialPath) {
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

    private void updateCurrentLineHighlight() {
        Highlighter highlighter = writingArea.getHighlighter();
        if (currentLineHighlightTag != null) {
            highlighter.removeHighlight(currentLineHighlightTag);
            currentLineHighlightTag = null;
        }

        if (!configManager.getShowCurrentLine() || currentMode == EditorMode.VISUAL || currentMode == EditorMode.VISUAL_LINE) {
            return;
        }

        try {
            int caret = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(caret);
            int start = writingArea.getLineStartOffset(line);
            int end = writingArea.getLineEndOffset(line);
            currentLineHighlightTag = highlighter.addHighlight(start, end, currentLinePainter);
        } catch (BadLocationException ignored) {
        }
    }

    private void refreshLineNumberPanel() {
        for (EditorPane pane : editorPanes) {
            pane.getLineNumberPanel().setMode(lineNumberMode);
            pane.getLineNumberPanel().setHighlightCurrentLine(configManager.getShowCurrentLine());
            if (lineNumberMode == LineNumberMode.NONE) {
                pane.getScrollPane().setRowHeaderView(null);
            } else {
                pane.getScrollPane().setRowHeaderView(pane.getLineNumberPanel());
            }
            pane.getLineNumberPanel().repaint();
        }
        editorHostPanel.revalidate();
        editorHostPanel.repaint();
    }

    private void applySyntaxHighlighting() {
        clearSyntaxHighlighting();

        FileBuffer buffer = getCurrentBuffer();
        if (buffer == null) {
            return;
        }

        String text = writingArea.getText();
        if (text.isEmpty()) {
            return;
        }

        String[] keywords = syntaxKeywordsFor(buffer.getFileType());
        Highlighter highlighter = writingArea.getHighlighter();
        for (String keyword : keywords) {
            int index = 0;
            while (index <= text.length() - keyword.length()) {
                int match = text.indexOf(keyword, index);
                if (match < 0) {
                    break;
                }
                if (isWordBoundary(text, match - 1) && isWordBoundary(text, match + keyword.length())) {
                    try {
                        syntaxHighlightTags.add(highlighter.addHighlight(match, match + keyword.length(), syntaxKeywordPainter));
                    } catch (BadLocationException ignored) {
                    }
                }
                index = match + keyword.length();
            }
        }

        int stringStart = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (stringStart < 0 && (c == '"' || c == '\'')) {
                stringStart = i;
            } else if (stringStart >= 0 && (c == '"' || c == '\'')) {
                try {
                    syntaxHighlightTags.add(highlighter.addHighlight(stringStart, i + 1, syntaxStringPainter));
                } catch (BadLocationException ignored) {
                }
                stringStart = -1;
            }
        }
    }

    private void clearSyntaxHighlighting() {
        Highlighter highlighter = writingArea.getHighlighter();
        for (Object tag : syntaxHighlightTags) {
            highlighter.removeHighlight(tag);
        }
        syntaxHighlightTags.clear();
    }

    private String[] syntaxKeywordsFor(FileType fileType) {
        switch (fileType) {
            case JAVA:
                return new String[] {"class", "public", "private", "protected", "static", "void", "new", "return", "if", "else", "try", "catch"};
            case JAVASCRIPT:
            case TYPESCRIPT:
                return new String[] {"function", "const", "let", "var", "return", "if", "else", "class", "import", "export"};
            case PYTHON:
                return new String[] {"def", "class", "return", "if", "elif", "else", "import", "from", "for", "while", "try", "except"};
            case RUST:
                return new String[] {"fn", "let", "mut", "impl", "struct", "enum", "match", "pub", "use", "mod", "return"};
            case GO:
                return new String[] {"func", "package", "import", "return", "if", "else", "struct", "interface", "type"};
            case C:
            case CPP:
                return new String[] {"int", "char", "void", "return", "if", "else", "struct", "class", "include"};
            case HTML:
                return new String[] {"<html", "<body", "<div", "<span", "<script", "<style", "<head", "<section"};
            case CSS:
                return new String[] {"display", "position", "color", "background", "padding", "margin", "flex", "grid"};
            case JSON:
                return new String[] {"true", "false", "null"};
            case MARKDOWN:
                return new String[] {"# ", "## ", "### ", "- ", "* "};
            default:
                return new String[0];
        }
    }

    private boolean isWordBoundary(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        char c = text.charAt(index);
        return !Character.isLetterOrDigit(c) && c != '_';
    }

    private void updateSubstitutePreview() {
        clearSubstitutePreview();
        if (currentMode != EditorMode.COMMAND || commandBuffer == null || !commandBuffer.startsWith(":")) {
            return;
        }

        String command = commandBuffer.substring(1);
        SubstitutePreview preview = parseSubstitutePreview(command);
        if (preview == null || preview.pattern.isEmpty()) {
            return;
        }

        try {
            int startOffset = writingArea.getLineStartOffset(Math.max(0, preview.startLine));
            int endLine = Math.min(writingArea.getLineCount() - 1, preview.endLine);
            int endOffset = writingArea.getLineEndOffset(endLine);
            String text = writingArea.getText();
            Highlighter highlighter = writingArea.getHighlighter();
            int searchFrom = startOffset;
            while (searchFrom <= endOffset - preview.pattern.length()) {
                int match = text.indexOf(preview.pattern, searchFrom);
                if (match < 0 || match >= endOffset) {
                    break;
                }
                substitutePreviewTags.add(highlighter.addHighlight(match, match + preview.pattern.length(), substitutePreviewPainter));
                searchFrom = match + Math.max(1, preview.pattern.length());
            }
        } catch (BadLocationException ignored) {
        }
    }

    private void clearSubstitutePreview() {
        Highlighter highlighter = writingArea.getHighlighter();
        for (Object tag : substitutePreviewTags) {
            highlighter.removeHighlight(tag);
        }
        substitutePreviewTags.clear();
    }

    private SubstitutePreview parseSubstitutePreview(String command) {
        String working = command;
        int startLine = getCurrentCaretLine();
        int endLine = startLine;

        if (working.startsWith("%")) {
            startLine = 0;
            endLine = Math.max(0, writingArea.getLineCount() - 1);
            working = working.substring(1);
        } else {
            int rangeEnd = findRangeCommandStart(working);
            if (rangeEnd > 0) {
                String rangePart = working.substring(0, rangeEnd);
                String[] parts = rangePart.split(",", -1);
                try {
                    if (parts.length == 2) {
                        startLine = Math.max(0, Integer.parseInt(parts[0]) - 1);
                        endLine = Math.max(startLine, Integer.parseInt(parts[1]) - 1);
                    } else if (parts.length == 1) {
                        startLine = Math.max(0, Integer.parseInt(parts[0]) - 1);
                        endLine = startLine;
                    }
                    working = working.substring(rangeEnd);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (!working.startsWith("s/")) {
            return null;
        }

        String[] parts = working.substring(2).split("/", -1);
        if (parts.length == 0) {
            return null;
        }
        return new SubstitutePreview(parts[0], startLine, endLine);
    }

    private int findRangeCommandStart(String command) {
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (!Character.isDigit(c) && c != ',') {
                return i;
            }
        }
        return -1;
    }

    private int getCurrentCaretLine() {
        try {
            return writingArea.getLineOfOffset(writingArea.getCaretPosition());
        } catch (BadLocationException e) {
            return 0;
        }
    }

    private String resolveGitBranch() {
        try {
            File headFile = new File(".git/HEAD");
            if (!headFile.exists()) {
                return "";
            }
            String head = Files.readString(headFile.toPath(), StandardCharsets.UTF_8).trim();
            if (head.startsWith("ref:")) {
                int slash = head.lastIndexOf('/');
                return slash >= 0 ? head.substring(slash + 1) : head;
            }
            return head.length() > 7 ? head.substring(0, 7) : head;
        } catch (IOException e) {
            return "";
        }
    }

    private String currentLineIndentation() {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            int start = writingArea.getLineStartOffset(line);
            int end = writingArea.getLineEndOffset(line);
            String lineText = writingArea.getText().substring(start, end);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < lineText.length(); i++) {
                char c = lineText.charAt(i);
                if (c == ' ' || c == '\t') {
                    builder.append(c);
                } else {
                    break;
                }
            }
            return builder.toString();
        } catch (BadLocationException e) {
            return "";
        }
    }

    private void moveLineIndentStart() {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            int start = writingArea.getLineStartOffset(line);
            int end = writingArea.getLineEndOffset(line);
            String lineText = writingArea.getText().substring(start, end);
            int offset = 0;
            while (offset < lineText.length() && Character.isWhitespace(lineText.charAt(offset)) && lineText.charAt(offset) != '\n') {
                offset++;
            }
            writingArea.setCaretPosition(start + offset);
        } catch (BadLocationException ignored) {
        }
    }

    private void openLineBelow() {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            int lineEnd = writingArea.getLineEndOffset(line);
            String indent = configManager.getAutoIndent() ? currentLineIndentation() : "";
            writingArea.insert("\n" + indent, lineEnd - 1);
            writingArea.setCaretPosition(lineEnd + indent.length());
            markModified();
        } catch (BadLocationException ignored) {
        }
    }

    private void openLineAbove() {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            int lineStart = writingArea.getLineStartOffset(line);
            String indent = configManager.getAutoIndent() ? currentLineIndentation() : "";
            writingArea.insert(indent + "\n", lineStart);
            writingArea.setCaretPosition(lineStart + indent.length());
            markModified();
        } catch (BadLocationException ignored) {
        }
    }

    private void joinCurrentLine(boolean withSpace) {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            if (line >= writingArea.getLineCount() - 1) {
                showMessage("Already on last line");
                return;
            }
            int lineEnd = writingArea.getLineEndOffset(line);
            int nextLineStart = writingArea.getLineStartOffset(line + 1);
            int nextLineEnd = writingArea.getLineEndOffset(line + 1);
            String nextLine = writingArea.getText().substring(nextLineStart, nextLineEnd).stripLeading();
            writingArea.replaceRange(withSpace ? " " + nextLine : nextLine, lineEnd - 1, nextLineEnd);
            markModified();
        } catch (BadLocationException ignored) {
        }
    }

    private String applyLineOperator(char operator) {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            int start = writingArea.getLineStartOffset(line);
            int end = writingArea.getLineEndOffset(line);
            String text = writingArea.getText().substring(start, end);
            switch (operator) {
                case '>':
                    String indent = configManager.getExpandTab() ? " ".repeat(writingArea.getTabSize()) : "\t";
                    writingArea.replaceRange(indent + text, start, end);
                    break;
                case '<':
                    int removeCount = Math.min(writingArea.getTabSize(), leadingWhitespace(text));
                    writingArea.replaceRange(text.substring(removeCount), start, end);
                    break;
                case '=':
                    String previousIndent = line > 0 ? indentationForLine(line - 1) : "";
                    writingArea.replaceRange(previousIndent + text.stripLeading(), start, end);
                    break;
                default:
                    return "";
            }
            markModified();
            return "Line updated";
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }

    private int leadingWhitespace(String text) {
        int count = 0;
        while (count < text.length() && Character.isWhitespace(text.charAt(count)) && text.charAt(count) != '\n') {
            count++;
        }
        return count;
    }

    private String indentationForLine(int line) {
        try {
            int start = writingArea.getLineStartOffset(line);
            int end = writingArea.getLineEndOffset(line);
            String lineText = writingArea.getText().substring(start, end);
            int count = 0;
            while (count < lineText.length() && Character.isWhitespace(lineText.charAt(count)) && lineText.charAt(count) != '\n') {
                count++;
            }
            return lineText.substring(0, count);
        } catch (BadLocationException e) {
            return "";
        }
    }

    private String findCharacter(char type, char target) {
        String text = writingArea.getText();
        int caret = writingArea.getCaretPosition();
        int result = -1;
        switch (type) {
            case 'f':
                result = text.indexOf(target, Math.min(caret + 1, text.length()));
                break;
            case 'F':
                result = text.lastIndexOf(target, Math.max(0, caret - 1));
                break;
            case 't':
                result = text.indexOf(target, Math.min(caret + 1, text.length()));
                if (result > caret) {
                    result -= 1;
                }
                break;
            case 'T':
                result = text.lastIndexOf(target, Math.max(0, caret - 1));
                if (result >= 0) {
                    result += 1;
                }
                break;
            default:
                break;
        }
        if (result < 0 || result >= text.length()) {
            return "Character not found: " + target;
        }
        writingArea.setCaretPosition(result);
        lastFindType = type;
        lastFindChar = target;
        return "Moved to " + target;
    }

    private String repeatFind(boolean reverse) {
        if (lastFindType == '\0' || lastFindChar == '\0') {
            return "No previous find command";
        }
        char repeatType = lastFindType;
        if (reverse) {
            switch (lastFindType) {
                case 'f':
                    repeatType = 'F';
                    break;
                case 'F':
                    repeatType = 'f';
                    break;
                case 't':
                    repeatType = 'T';
                    break;
                case 'T':
                    repeatType = 't';
                    break;
                default:
                    break;
            }
        }
        return findCharacter(repeatType, lastFindChar);
    }

    private void recordJumpPosition() {
        int position = writingArea.getCaretPosition();
        if (jumpList.isEmpty() || jumpList.get(jumpList.size() - 1) != position) {
            if (jumpIndex >= 0 && jumpIndex < jumpList.size() - 1) {
                jumpList = new ArrayList<>(jumpList.subList(0, jumpIndex + 1));
            }
            jumpList.add(position);
            jumpIndex = jumpList.size() - 1;
        }
    }

    private void jumpBack() {
        if (jumpList.isEmpty() || jumpIndex <= 0) {
            showMessage("At oldest jump");
            return;
        }
        jumpIndex--;
        writingArea.setCaretPosition(Math.min(jumpList.get(jumpIndex), writingArea.getText().length()));
    }

    private void jumpForward() {
        if (jumpList.isEmpty() || jumpIndex >= jumpList.size() - 1) {
            showMessage("At newest jump");
            return;
        }
        jumpIndex++;
        writingArea.setCaretPosition(Math.min(jumpList.get(jumpIndex), writingArea.getText().length()));
    }

    private void recordChangePosition() {
        int position = writingArea.getCaretPosition();
        if (changeList.isEmpty() || changeList.get(changeList.size() - 1) != position) {
            changeList.add(position);
            if (changeList.size() > 100) {
                changeList.remove(0);
            }
            changeIndex = changeList.size() - 1;
        }
    }

    private void changePrev() {
        if (changeList.isEmpty() || changeIndex <= 0) {
            showMessage("At oldest change");
            return;
        }
        changeIndex--;
        writingArea.setCaretPosition(Math.min(changeList.get(changeIndex), writingArea.getText().length()));
    }

    private void changeNext() {
        if (changeList.isEmpty() || changeIndex >= changeList.size() - 1) {
            showMessage("At newest change");
            return;
        }
        changeIndex++;
        writingArea.setCaretPosition(Math.min(changeList.get(changeIndex), writingArea.getText().length()));
    }

    private void checkForExternalChanges() {
        if (reloadPromptActive) {
            return;
        }
        FileBuffer buffer = getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isModified()) {
            return;
        }
        if (!buffer.hasExternalChanges()) {
            return;
        }

        reloadPromptActive = true;
        int result = JOptionPane.showConfirmDialog(
            this,
            "File changed on disk. Reload it?",
            "External Change",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        reloadPromptActive = false;

        if (result == JOptionPane.YES_OPTION) {
            try {
                buffer.load(configManager);
                loadBufferIntoEditor(buffer);
                showMessage("Reloaded from disk");
            } catch (IOException e) {
                showMessage("Reload failed: " + e.getMessage());
            }
        } else {
            buffer.refreshExternalTimestamp();
        }
    }

    private void maybePreviewMarkdown(FileBuffer buffer) {
        if (buffer == null || buffer.getFileType() != FileType.MARKDOWN || buffer.getFile() == null) {
            return;
        }
        if (buffer.getFile().equals(lastPreviewedMarkdown)) {
            return;
        }
        lastPreviewedMarkdown = buffer.getFile();
        try {
            File previewFile = File.createTempFile("shed-markdown-", ".html");
            previewFile.deleteOnExit();
            String html = renderMarkdownPreview(buffer.getFullContent(), buffer.getDisplayName());
            Files.writeString(previewFile.toPath(), html, StandardCharsets.UTF_8);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(previewFile.toURI());
            }
        } catch (IOException ignored) {
        }
    }

    private String renderMarkdownPreview(String markdown, String title) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<title>").append(title).append("</title>");
        html.append("<style>body{font-family:Georgia,serif;max-width:880px;margin:40px auto;padding:0 24px;line-height:1.6;background:#faf7ef;color:#1f2933;}pre{background:#111827;color:#f9fafb;padding:16px;overflow:auto;}code{background:#e5e7eb;padding:2px 4px;}h1,h2,h3{line-height:1.2;}blockquote{border-left:4px solid #cbd5e1;padding-left:12px;color:#475569;}</style>");
        html.append("</head><body>");
        boolean inCode = false;
        for (String line : markdown.split("\n", -1)) {
            String escaped = escapeHtml(line);
            if (line.startsWith("```")) {
                html.append(inCode ? "</pre>" : "<pre>");
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                html.append(escaped).append("\n");
                continue;
            }
            if (line.startsWith("### ")) {
                html.append("<h3>").append(escapeHtml(line.substring(4))).append("</h3>");
            } else if (line.startsWith("## ")) {
                html.append("<h2>").append(escapeHtml(line.substring(3))).append("</h2>");
            } else if (line.startsWith("# ")) {
                html.append("<h1>").append(escapeHtml(line.substring(2))).append("</h1>");
            } else if (line.startsWith("> ")) {
                html.append("<blockquote>").append(escapeHtml(line.substring(2))).append("</blockquote>");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                html.append("<p>&bull; ").append(escapeHtml(line.substring(2))).append("</p>");
            } else if (line.isBlank()) {
                html.append("<br/>");
            } else {
                html.append("<p>").append(escaped).append("</p>");
            }
        }
        if (inCode) {
            html.append("</pre>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    public String deleteLineRange(int startLine, int endLine) {
        try {
            int safeStart = Math.max(1, startLine);
            int safeEnd = Math.max(safeStart, endLine);
            int maxLines = writingArea.getLineCount();
            if (safeStart > maxLines) {
                return "Invalid range";
            }
            safeEnd = Math.min(safeEnd, maxLines);

            int startOffset = writingArea.getLineStartOffset(safeStart - 1);
            int endOffset = writingArea.getLineEndOffset(safeEnd - 1);
            if (endOffset < writingArea.getText().length()) {
                endOffset = Math.min(endOffset + 1, writingArea.getText().length());
            }
            writingArea.replaceRange("", startOffset, endOffset);
            writingArea.setCaretPosition(Math.min(startOffset, writingArea.getText().length()));
            markModified();
            int deleted = safeEnd - safeStart + 1;
            return deleted + " line" + (deleted == 1 ? "" : "s") + " deleted";
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }

    public String substituteRange(String pattern, String replacement, int startLine, int endLine, boolean replaceAll) {
        try {
            int maxLines = writingArea.getLineCount();
            int safeStart = Math.max(1, Math.min(startLine, maxLines));
            int safeEnd = Math.max(safeStart, Math.min(endLine, maxLines));
            int startOffset = writingArea.getLineStartOffset(safeStart - 1);
            int endOffset = writingArea.getLineEndOffset(safeEnd - 1);
            String rangeText = writingArea.getText().substring(startOffset, endOffset);
            ReplacementResult result = replaceLiteral(rangeText, pattern, replacement, replaceAll);
            if (result.matchCount == 0) {
                return "Pattern not found: " + pattern;
            }
            writingArea.replaceRange(result.updatedText, startOffset, endOffset);
            writingArea.setCaretPosition(Math.min(startOffset + Math.max(0, result.firstMatchOffset), writingArea.getText().length()));
            markModified();
            return "Replaced " + result.matchCount + " occurrence" + (result.matchCount == 1 ? "" : "s");
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }

    public String runShellCommand(String command) {
        try {
            ProcessBuilder builder = new ProcessBuilder("zsh", "-lc", command);
            builder.directory(new File("."));
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            String output = stdout.isEmpty() ? stderr : stdout + (stderr.isEmpty() ? "" : "\n" + stderr);
            output = output.stripTrailing();
            if (output.isEmpty()) {
                return "Shell command exited " + exitCode;
            }
            if (output.lines().count() <= 1) {
                return output;
            }
            showScratchBuffer("[shell output]", output);
            return ":q to return to previous buffer";
        } catch (Exception e) {
            return "Shell error: " + e.getMessage();
        }
    }

    public String filterRangeWithCommand(int startLine, int endLine, String command) {
        try {
            int safeStart = Math.max(1, Math.min(startLine, writingArea.getLineCount()));
            int safeEnd = Math.max(safeStart, Math.min(endLine, writingArea.getLineCount()));
            int startOffset = writingArea.getLineStartOffset(safeStart - 1);
            int endOffset = writingArea.getLineEndOffset(safeEnd - 1);
            String input = writingArea.getText().substring(startOffset, endOffset);

            ProcessBuilder builder = new ProcessBuilder("zsh", "-lc", command);
            builder.directory(new File("."));
            Process process = builder.start();
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return stderr.isEmpty() ? "Shell command failed" : stderr.strip();
            }

            writingArea.replaceRange(stdout, startOffset, endOffset);
            writingArea.setCaretPosition(Math.min(startOffset, writingArea.getText().length()));
            markModified();
            return (safeEnd - safeStart + 1) + " line filter applied";
        } catch (Exception e) {
            return "Shell error: " + e.getMessage();
        }
    }

    public String showFileFinder() {
        List<String> candidates = new ArrayList<>();
        collectFiles(new File("."), candidates);
        String selection = showPaletteDialog("Files", candidates);
        if (selection == null || selection.isEmpty()) {
            return "File finder cancelled";
        }
        try {
            openFile(new File(selection));
            return "Opened: " + selection;
        } catch (IOException e) {
            return "Error opening file: " + e.getMessage();
        }
    }

    public String showBufferFinder() {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < buffers.size(); i++) {
            candidates.add((i + 1) + ": " + buffers.get(i).getDisplayName());
        }
        String selection = showPaletteDialog("Buffers", candidates);
        if (selection == null || selection.isEmpty()) {
            return "Buffer finder cancelled";
        }
        int colon = selection.indexOf(':');
        if (colon > 0) {
            try {
                int bufferIndex = Integer.parseInt(selection.substring(0, colon).trim()) - 1;
                switchToBuffer(bufferIndex);
                return "Switched to buffer";
            } catch (NumberFormatException ignored) {
            }
        }
        return "Buffer finder cancelled";
    }

    public String showGrepFinder(String pattern) {
        List<String> candidates = grepFiles(pattern);
        String selection = showPaletteDialog("Grep", candidates);
        if (selection == null || selection.isEmpty()) {
            return "Grep cancelled";
        }

        String[] parts = selection.split(":", 3);
        if (parts.length < 2) {
            return "Invalid grep selection";
        }

        try {
            openFile(new File(parts[0]));
            return gotoLine(Integer.parseInt(parts[1]));
        } catch (Exception e) {
            return "Error opening grep match: " + e.getMessage();
        }
    }

    private void collectFiles(File directory, List<String> results) {
        if (directory == null || results.size() >= 200 || shouldSkipHiddenPath(directory)) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (results.size() >= 200) {
                return;
            }
            if (file.isDirectory()) {
                collectFiles(file, results);
            } else {
                results.add(file.getPath());
            }
        }
    }

    private List<String> grepFiles(String pattern) {
        List<String> results = new ArrayList<>();
        if (pattern == null || pattern.isEmpty()) {
            return results;
        }
        grepFilesRecursive(new File("."), pattern, results);
        return results;
    }

    private void grepFilesRecursive(File directory, String pattern, List<String> results) {
        if (directory == null || results.size() >= 200 || shouldSkipHiddenPath(directory)) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (results.size() >= 200) {
                return;
            }
            if (file.isDirectory()) {
                grepFilesRecursive(file, pattern, results);
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(pattern)) {
                        results.add(file.getPath() + ":" + (i + 1) + ":" + lines.get(i).trim());
                    }
                    if (results.size() >= 200) {
                        return;
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }

    private String showPaletteDialog(String title, List<String> candidates) {
        JDialog dialog = new JDialog(this, title, true);
        dialog.setLayout(new BorderLayout(8, 8));
        JTextField filterField = new JTextField();
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String candidate : candidates) {
            model.addElement(candidate);
        }
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refilter(); }
            public void removeUpdate(DocumentEvent e) { refilter(); }
            public void changedUpdate(DocumentEvent e) { refilter(); }

            private void refilter() {
                String query = filterField.getText().toLowerCase();
                model.clear();
                for (String candidate : candidates) {
                    if (query.isEmpty() || candidate.toLowerCase().contains(query)) {
                        model.addElement(candidate);
                    }
                }
                if (!model.isEmpty()) {
                    list.setSelectedIndex(0);
                }
            }
        });

        final String[] selection = new String[1];
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selection[0] = list.getSelectedValue();
                    dialog.dispose();
                }
            }
        });
        filterField.addActionListener(e -> {
            selection[0] = list.getSelectedValue();
            dialog.dispose();
        });

        dialog.add(filterField, BorderLayout.NORTH);
        dialog.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.setSize(720, 420);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        return selection[0];
    }

    private boolean shouldSkipHiddenPath(File file) {
        if (file == null) {
            return true;
        }
        String path = file.getPath();
        if (".".equals(path) || "./".equals(path)) {
            return false;
        }
        return file.getName().startsWith(".");
    }

    public String showRegisters() {
        List<String> lines = new ArrayList<>();
        String unnamed = clipboardManager.getClipboardContent();
        if (!unnamed.isEmpty()) {
            lines.add("\" " + trimForRegisterDisplay(unnamed));
            lines.add("0 " + trimForRegisterDisplay(unnamed));
        }
        FileBuffer buffer = getCurrentBuffer();
        if (buffer != null && buffer.getFilePath() != null) {
            lines.add("% " + buffer.getFilePath());
        }
        if (!lastCommand.isEmpty()) {
            lines.add(": " + lastCommand);
        }
        if (!lastInsertedText.isEmpty()) {
            lines.add(". " + trimForRegisterDisplay(lastInsertedText));
        }
        if (lines.isEmpty()) {
            return "No registers populated";
        }
        showScratchBuffer("[registers]", String.join("\n", lines));
        return "Showing registers";
    }

    public String showMarks() {
        FileBuffer buffer = getCurrentBuffer();
        if (buffer == null || buffer.getMarks().isEmpty()) {
            return "No marks set";
        }
        List<String> lines = new ArrayList<>();
        for (java.util.Map.Entry<Character, Integer> entry : buffer.getMarks().entrySet()) {
            lines.add(entry.getKey() + " " + describeOffset(entry.getValue()));
        }
        showScratchBuffer("[marks]", String.join("\n", lines));
        return "Showing marks";
    }

    private String trimForRegisterDisplay(String value) {
        String singleLine = value.replace("\n", "\\n");
        if (singleLine.length() > 80) {
            return singleLine.substring(0, 77) + "...";
        }
        return singleLine;
    }

    private String describeOffset(int offset) {
        try {
            int line = writingArea.getLineOfOffset(Math.min(offset, writingArea.getText().length()));
            int col = offset - writingArea.getLineStartOffset(line);
            return (line + 1) + ":" + (col + 1);
        } catch (BadLocationException e) {
            return "1:1";
        }
    }

    public String toggleZenMode() {
        zenModeEnabled = !zenModeEnabled;
        updateZenModeLayout();
        return zenModeEnabled ? "Zen mode enabled" : "Zen mode disabled";
    }

    private void updateZenModeLayout() {
        for (EditorPane pane : editorPanes) {
            if (!zenModeEnabled) {
                pane.getScrollPane().setBorder(null);
                continue;
            }
            int width = getWidth();
            int desired = configManager.getZenModeWidth() * Math.max(8, pane.getTextArea().getFontMetrics(pane.getTextArea().getFont()).charWidth('M'));
            int horizontalPadding = Math.max(12, (width - desired) / 2);
            pane.getScrollPane().setBorder(BorderFactory.createEmptyBorder(0, horizontalPadding, 0, horizontalPadding));
        }
    }

    public String executeNormalKeys(String keys, int startLine, int endLine) {
        if (keys == null || keys.isEmpty()) {
            return "Error: :normal requires keys";
        }
        try {
            int safeStart = Math.max(1, startLine);
            int safeEnd = Math.max(safeStart, endLine);
            for (int line = safeStart; line <= safeEnd; line++) {
                int offset = writingArea.getLineStartOffset(Math.min(line - 1, writingArea.getLineCount() - 1));
                writingArea.setCaretPosition(offset);
                for (int i = 0; i < keys.length(); i++) {
                    char c = keys.charAt(i);
                    handleNormalMode(new KeyEvent(writingArea, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, c));
                }
            }
            return "Executed normal keys";
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }

    public int getCurrentLineNumber() {
        return getCurrentCaretLine() + 1;
    }

    public String showLspCompletionStatus() {
        String prefix = currentCompletionPrefix();
        if (prefix.isEmpty()) {
            return "No completion prefix";
        }

        List<String> completions = collectBufferCompletions(prefix);
        if (completions.isEmpty()) {
            return "No completions";
        }
        return "Completions: " + String.join(", ", completions);
    }

    private String currentCompletionPrefix() {
        String text = writingArea.getText();
        int caret = Math.min(writingArea.getCaretPosition(), text.length());
        int start = caret;
        while (start > 0 && isWordCharacter(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, caret);
    }

    private List<String> collectBufferCompletions(String prefix) {
        List<String> matches = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) {
            return matches;
        }

        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        String text = writingArea.getText();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isWordCharacter(c)) {
                word.append(c);
            } else if (!word.isEmpty()) {
                addCompletionCandidate(prefix, unique, word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty()) {
            addCompletionCandidate(prefix, unique, word.toString());
        }

        for (String candidate : unique) {
            matches.add(candidate);
            if (matches.size() >= 10) {
                break;
            }
        }
        return matches;
    }

    private void addCompletionCandidate(String prefix, java.util.LinkedHashSet<String> unique, String candidate) {
        if (candidate.length() <= prefix.length()) {
            return;
        }
        if (candidate.startsWith(prefix)) {
            unique.add(candidate);
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
        if (mode != EditorMode.COMMAND) {
            clearSubstitutePreview();
        }
        updateStatusBar();
    }

    private Color getModeBackground(EditorMode mode) {
        switch (mode) {
            case INSERT:
                return configManager.getInsertColor();
            case VISUAL:
            case VISUAL_LINE:
                return configManager.getVisualColor();
            case REPLACE:
                return configManager.getReplaceColor();
            case COMMAND:
            case SEARCH:
                return configManager.getCommandColor();
            case NORMAL:
            default:
                return configManager.getNormalColor();
        }
    }

    // Status bar update
    private void updateStatusBar() {
        FileBuffer buffer = getCurrentBuffer();
        StringBuilder status = new StringBuilder();

        if (buffer != null) {
            status.append(buffer.getDisplayName());
            if (buffer.isModified()) {
                status.append(" [+]");
            }
            status.append("  ");
        }

        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int col = pos - writingArea.getLineStartOffset(line);
            status.append((line + 1)).append(":").append((col + 1)).append("  ");
        } catch (BadLocationException e) {
            status.append("1:1  ");
        }

        status.append(currentMode.getDisplayName()).append("  ");

        if (buffer != null) {
            status.append(buffer.getFileType().getDisplayName()).append("  ");
            status.append(buffer.getEncoding()).append("/").append(buffer.getLineEndingLabel()).append("  ");
        }

        if (gitBranch != null && !gitBranch.isEmpty()) {
            status.append("git:").append(gitBranch).append("  ");
        }

        int lineCount = writingArea.getLineCount();
        status.append(lineCount).append(" line").append(lineCount != 1 ? "s" : "");
        if (buffer != null && buffer.isLargeFile() && buffer.isShowingPreviewOnly()) {
            status.append("  preview");
        }

        statusBar.setText(status.toString());

        if ((currentMode == EditorMode.COMMAND || currentMode == EditorMode.SEARCH) && !commandBuffer.isEmpty()) {
            commandBar.setText(commandBuffer);
        } else if (lastMessage != null && !lastMessage.isEmpty()) {
            commandBar.setText(lastMessage);
        } else {
            commandBar.setText("");
        }
    }

    private void handleDocumentChange() {
        if (!suppressDocumentEvents) {
            markModified();
            updateCurrentLineHighlight();
            applySyntaxHighlighting();
            lineNumberPanel.repaint();
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

    private void detachActiveDocumentListener() {
        if (bufferDocumentListener != null && writingArea.getDocument() != null) {
            writingArea.getDocument().removeDocumentListener(bufferDocumentListener);
        }
    }

    private void attachActiveDocumentListener() {
        if (bufferDocumentListener != null && writingArea.getDocument() != null) {
            writingArea.getDocument().addDocumentListener(bufferDocumentListener);
        }
    }

    private void loadBufferIntoEditor(FileBuffer buffer) {
        detachActiveDocumentListener();
        searchManager.clearHighlights();
        withSuppressedDocumentEvents(() -> writingArea.setDocument(buffer.getDocument()));
        attachActiveDocumentListener();
        undoManager = buffer.getUndoManager();
        writingArea.setCaretPosition(0);
        updateCurrentLineHighlight();
        applySyntaxHighlighting();
        refreshLineNumberPanel();
        maybePreviewMarkdown(buffer);
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
            try {
                buffer.createBackup();
            } catch (IOException ignored) {
            }
            recordChangePosition();
            updateStatusBar();
        }
    }

    // Show message in status bar
    public void showMessage(String message) {
        lastMessage = message == null ? "" : message;
        if (messageResetTimer != null) {
            messageResetTimer.stop();
        }
        messageResetTimer = new Timer(3000, e -> {
            lastMessage = "";
            updateStatusBar();
        });
        messageResetTimer.setRepeats(false);
        messageResetTimer.start();
        updateStatusBar();
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
        builder.append(":ls          list open buffers\n\n");
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
        recordJumpPosition();
        String result = searchManager.searchForward(pattern);
        if (!configManager.getHighlightSearch()) {
            searchManager.clearHighlights();
        }
        return result;
    }

    public String searchBackward(String pattern) {
        recordJumpPosition();
        String result = searchManager.searchBackward(pattern);
        if (!configManager.getHighlightSearch()) {
            searchManager.clearHighlights();
        }
        return result;
    }

    public String substitute(String pattern, String replacement, boolean wholeBuffer, boolean replaceAll) {
        if (wholeBuffer) {
            ReplacementResult result = replaceLiteral(writingArea.getText(), pattern, replacement, replaceAll);
            if (result.matchCount == 0) {
                return "Pattern not found: " + pattern;
            }
            writingArea.setText(result.updatedText);
            writingArea.setCaretPosition(Math.min(Math.max(0, result.firstMatchOffset), writingArea.getText().length()));
            markModified();
            searchManager.clearHighlights();
            return "Replaced " + result.matchCount + " occurrence" + (result.matchCount == 1 ? "" : "s");
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

            ReplacementResult result = replaceLiteral(lineText, pattern, replacement, replaceAll);
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

    private ReplacementResult replaceLiteral(String text, String pattern, String replacement, boolean replaceAll) {
        StringBuilder builder = new StringBuilder();
        int searchFrom = 0;
        int matchCount = 0;
        int firstMatchOffset = -1;

        while (searchFrom <= text.length()) {
            int matchIndex = text.indexOf(pattern, searchFrom);
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
        lineNumberMode = enabled ? LineNumberMode.ABSOLUTE : LineNumberMode.NONE;
        configManager.setLineNumberMode(lineNumberMode);
        refreshLineNumberPanel();
    }

    public void setLineNumberMode(LineNumberMode mode) {
        lineNumberMode = mode == null ? LineNumberMode.ABSOLUTE : mode;
        configManager.setLineNumberMode(lineNumberMode);
        refreshLineNumberPanel();
    }

    public String setLineNumberMode(String value) {
        setLineNumberMode(LineNumberMode.fromConfigValue(value));
        return "Line numbers set to " + lineNumberMode.toConfigValue();
    }

    public void setHighlightSearch(boolean enabled) {
        configManager.set("highlight.search", String.valueOf(enabled));
        if (!enabled) {
            searchManager.clearHighlights();
        }
        updateStatusBar();
    }

    public void setAutoIndent(boolean enabled) {
        configManager.set("auto.indent", String.valueOf(enabled));
    }

    public void setExpandTab(boolean enabled) {
        configManager.set("expand.tab", String.valueOf(enabled));
    }

    public void setShowCurrentLine(boolean enabled) {
        configManager.set("show.current.line", String.valueOf(enabled));
        updateCurrentLineHighlight();
        refreshLineNumberPanel();
    }

    public String setTabSizeFromCommand(String value) {
        try {
            int parsed = Math.max(1, Math.min(16, Integer.parseInt(value)));
            configManager.set("tab.size", String.valueOf(parsed));
            for (EditorPane pane : editorPanes) {
                pane.getTextArea().setTabSize(parsed);
            }
            return "Tab size set to " + parsed;
        } catch (NumberFormatException e) {
            return "Invalid tab size: " + value;
        }
    }

    // Go to line
    public String gotoLine(int lineNum) {
        try {
            int totalLines = writingArea.getLineCount();
            if (lineNum < 1 || lineNum > totalLines) {
                return "Invalid line number: " + lineNum;
            }

            recordJumpPosition();
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
                   "  f/F/t/T ; ,    Find/till-char and repeat\n" +
                   "  0/$            Line start/end\n" +
                   "  gg/G           File start/end\n" +
                   "  i/a/A/I/o/O    Insert variants\n" +
                   "  v/V/R          Visual/visual-line/replace\n" +
                   "  yy/dd/cc       Yank/delete/change line\n" +
                   "  dw/cw          Delete/change word\n" +
                   "  D/C            Delete/change to end of line\n" +
                   "  >>/<</==       Indent/dedent/auto-indent line\n" +
                   "  J/gJ           Join lines with/without space\n" +
                   "  m{a-z}         Set mark\n" +
                   "  '{a-z}/`{a-z}  Jump to mark\n" +
                   "  Ctrl-o/Ctrl-i  Jump back/forward\n" +
                   "  g;/g,          Previous/next change\n" +
                   "  p/P            Paste after/before\n" +
                   "  u/Ctrl-r       Undo/redo\n" +
                   "  /pattern       Search forward\n" +
                   "  ?pattern       Search backward\n" +
                   "  n/N            Next/previous match\n" +
                   "  * / #          Search word under cursor\n" +
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
                   "  :Files         File finder\n" +
                   "  :Buffers       Buffer finder\n" +
                   "  :grep text     Grep finder\n" +
                   "  :registers     Show registers\n" +
                   "  :marks         Show marks\n" +
                   "  :Goyo          Toggle zen mode\n" +
                   "  :normal keys   Replay normal keys\n" +
                   "  :!cmd          Run shell command\n" +
                   "  :set nu        Enable line numbers\n" +
                   "  :45            Go to line 45\n" +
                   "  :1,5d          Delete a line range\n" +
                   "  :s/a/b         Substitute current line\n" +
                   "  :1,5s/a/b/g    Substitute a range\n" +
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

    private static class SubstitutePreview {
        private final String pattern;
        private final int startLine;
        private final int endLine;

        private SubstitutePreview(String pattern, int startLine, int endLine) {
            this.pattern = pattern;
            this.startLine = startLine;
            this.endLine = endLine;
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
