// SHit EDitor (Shed) Version 2.0 <Refactored Build>

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private RegisterManager registerManager;
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
    private Character pendingRegister;
    private boolean suppressDocumentEvents;
    private boolean suppressNextTypedChar;
    private boolean closingDown;
    private List<String> recentFiles;
    private File recentFilesStore;
    private File commandLogStore;
    private Deque<SpecialBufferReturnState> specialBufferReturns;
    private List<String> commandHistory;
    private int commandHistoryIndex;
    private String commandHistoryPrefix;
    private DateTimeFormatter commandLogTimeFormat;
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
    private Highlighter.HighlightPainter syntaxCommentPainter;
    private Highlighter.HighlightPainter syntaxNumberPainter;
    private File lastPreviewedMarkdown;
    private List<Integer> jumpList;
    private int jumpIndex;
    private List<Integer> changeList;
    private int changeIndex;
    private char lastFindChar;
    private char lastFindType;
    private Character recordingRegister;
    private Character lastMacroRegister;
    private List<NormalizedKeyStroke> macroBuffer;
    private int macroPlaybackDepth;
    private Character pendingTextObjectOperator;
    private Character pendingTextObjectModifier;
    private Character pendingSurroundAction;
    private Character pendingSurroundOld;
    private Character pendingSurroundTarget;
    private Map<String, LspClient> lspClients;
    private Map<String, Integer> lspDocumentVersions;
    private Map<String, String> lspErrors;
    private EditorPane treePane;
    private FileBuffer treeBuffer;
    private Map<FileBuffer, List<String>> treeLineTargets;
    private int keymapReplayDepth;

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
        pendingRegister = null;
        visualStartPos = -1;
        lastCommand = "";
        suppressDocumentEvents = false;
        suppressNextTypedChar = false;
        closingDown = false;
        recentFiles = new ArrayList<>();
        recentFilesStore = new File(System.getProperty("user.home"), ".shed_recent");
        commandLogStore = new File(System.getProperty("user.home"), ".shed_log");
        specialBufferReturns = new ArrayDeque<>();
        commandHistory = new ArrayList<>();
        commandHistoryIndex = -1;
        commandHistoryPrefix = "";
        commandLogTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        lineNumberMode = configManager.getLineNumberMode();
        searchForward = true;
        gitBranch = resolveGitBranch();
        substitutePreviewTags = new ArrayList<>();
        currentLinePainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getCurrentLineHighlightColor());
        substitutePreviewPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSubstitutePreviewColor());
        zenModeEnabled = false;
        lastInsertedText = "";
        reloadPromptActive = false;
        syntaxHighlightTags = new ArrayList<>();
        syntaxKeywordPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSyntaxKeywordColor());
        syntaxStringPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSyntaxStringColor());
        syntaxCommentPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSyntaxCommentColor());
        syntaxNumberPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSyntaxNumberColor());
        lastPreviewedMarkdown = null;
        jumpList = new ArrayList<>();
        jumpIndex = -1;
        changeList = new ArrayList<>();
        changeIndex = -1;
        lastFindChar = '\0';
        lastFindType = '\0';
        recordingRegister = null;
        lastMacroRegister = null;
        macroBuffer = new ArrayList<>();
        macroPlaybackDepth = 0;
        pendingTextObjectOperator = null;
        pendingTextObjectModifier = null;
        pendingSurroundAction = null;
        pendingSurroundOld = null;
        pendingSurroundTarget = null;
        lspClients = new HashMap<>();
        lspDocumentVersions = new HashMap<>();
        lspErrors = new HashMap<>();
        keymapReplayDepth = 0;
        treePane = null;
        treeBuffer = null;
        treeLineTargets = new HashMap<>();
        loadRecentFiles();
        lastMessage = "";

        // Initialize UI
        initializeUI();
        // Set initial mode before any status rendering hooks
        setMode(EditorMode.NORMAL);
        applyThemeColors();

        // Initialize managers that depend on UI
        clipboardManager = new ClipboardManager();
        registerManager = new RegisterManager();
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
        statusBar.setBackground(configManager.getStatusBarBackground());
        statusBar.setOpaque(true);
        statusBar.setPreferredSize(new Dimension(screenSize.width / 2, 30));
        statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusBar.setForeground(configManager.getStatusBarForeground());

        commandBar = new JLabel();
        commandBar.setBackground(configManager.getCommandBarBackground());
        commandBar.setOpaque(true);
        commandBar.setPreferredSize(new Dimension(screenSize.width / 2, 28));
        commandBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        commandBar.setForeground(configManager.getCommandBarForeground());

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
        textArea.addKeyListener(this);
        textArea.setFont(resolveEditorFont());
        textArea.setTabSize(configManager.getTabSize());
        textArea.setCaret(new BlockCaret());
        textArea.getCaret().setBlinkRate(0);
        textArea.setCaretColor(configManager.getCaretColor());
        textArea.setForeground(configManager.getEditorForeground());
        textArea.setEditable(false);
        textArea.setSelectionColor(configManager.getSelectionColor());
        textArea.setSelectedTextColor(configManager.getSelectionTextColor());

        LineNumberPanel paneLineNumberPanel = new LineNumberPanel(textArea);
        paneLineNumberPanel.setMode(lineNumberMode);
        paneLineNumberPanel.setHighlightCurrentLine(configManager.getShowCurrentLine());

        JScrollPane paneScrollPane = new JScrollPane(textArea);
        paneScrollPane.setWheelScrollingEnabled(true);
        paneScrollPane.getVerticalScrollBar().setUnitIncrement(Math.max(16, textArea.getFontMetrics(textArea.getFont()).getHeight()));
        if (lineNumberMode != LineNumberMode.NONE) {
            paneScrollPane.setRowHeaderView(paneLineNumberPanel);
        }

        SearchManager paneSearchManager = new SearchManager(textArea);
        final EditorPane[] paneRef = new EditorPane[1];
        textArea.addCaretListener(e -> {
            if (paneRef[0] != null && paneRef[0] != getActivePane()) {
                activateEditorPane(paneRef[0]);
            }
            ensureCaretVisible(textArea);
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

    private static final class BlockCaret extends DefaultCaret {
        private static final long serialVersionUID = 1L;

        @Override
        public void paint(Graphics g) {
            JTextComponent component = getComponent();
            if (component == null || !isVisible()) {
                return;
            }
            try {
                Rectangle2D modelBounds = component.modelToView2D(getDot());
                if (modelBounds == null) {
                    return;
                }
                Rectangle bounds = modelBounds.getBounds();
                int caretWidth = Math.max(1, blockWidth(component, getDot()));
                g.setXORMode(component.getBackground());
                g.fillRect(bounds.x, bounds.y, caretWidth, bounds.height);
                g.setPaintMode();
            } catch (BadLocationException ignored) {
            }
        }

        @Override
        protected synchronized void damage(Rectangle r) {
            if (r == null) {
                return;
            }
            JTextComponent component = getComponent();
            x = r.x;
            y = r.y;
            height = r.height;
            width = component == null ? Math.max(1, r.width) : Math.max(1, blockWidth(component, getDot()));
            repaint();
        }

        private int blockWidth(JTextComponent component, int dot) {
            try {
                int length = component.getDocument().getLength();
                if (dot < length) {
                    Rectangle2D current = component.modelToView2D(dot);
                    Rectangle2D next = component.modelToView2D(dot + 1);
                    if (current != null && next != null) {
                        int width = (int) Math.round(next.getX() - current.getX());
                        if (width > 0) {
                            return width;
                        }
                    }
                }
            } catch (BadLocationException ignored) {
            }
            return Math.max(1, component.getFontMetrics(component.getFont()).charWidth('W'));
        }
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
        detachActiveDocumentListener();
        activePaneIndex = index;
        bindActivePane(pane);
        attachActiveDocumentListener();
        currentBufferIndex = pane.getBuffer() == null ? -1 : buffers.indexOf(pane.getBuffer());
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
        updateZenModeLayout();
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
        EditorMode previousMode = currentMode;
        if (currentMode == EditorMode.NORMAL && recordingRegister != null && !(pendingKey == '\0' && e.getKeyChar() == 'q')) {
            macroBuffer.add(NormalizedKeyStroke.fromKeyEvent(e));
        }
        if (applyConfiguredKeybinding(e)) {
            updateStatusBar();
            return;
        }
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
        if (previousMode != EditorMode.INSERT && currentMode == EditorMode.INSERT && isPrintableKey(e)) {
            suppressNextTypedChar = true;
        }
        updateStatusBar();
    }

    private boolean applyConfiguredKeybinding(KeyEvent e) {
        if (currentMode == null || keymapReplayDepth > 32) {
            return false;
        }
        String keySpec = keySpecFromEvent(e);
        if (keySpec == null || keySpec.isEmpty()) {
            return false;
        }
        String mapping = configManager.getKeybinding(modeKey(currentMode), keySpec);
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

        keymapReplayDepth++;
        try {
            for (String token : replayTokens) {
                KeyEvent replay = keyEventFromToken(token);
                if (replay != null) {
                    keyPressed(replay);
                }
            }
        } finally {
            keymapReplayDepth--;
        }
        return true;
    }

    private String modeKey(EditorMode mode) {
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

    private String keySpecFromEvent(KeyEvent e) {
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

    private String ctrlTarget(int keyCode, char keyChar) {
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

    private List<String> parseKeySequence(String mapping) {
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

    private KeyEvent keyEventFromToken(String token) {
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
            return new KeyEvent(writingArea, KeyEvent.KEY_PRESSED, now, 0, code, c);
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
            return new KeyEvent(writingArea, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, ctrlSpec.keyCode, ctrlSpec.keyChar);
        }

        KeyStrokeSpec spec = keyStrokeSpec(inner);
        if (spec == null) {
            return null;
        }
        return new KeyEvent(writingArea, KeyEvent.KEY_PRESSED, now, 0, spec.keyCode, spec.keyChar);
    }

    private KeyStrokeSpec keyStrokeSpec(String token) {
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

    private static final class KeyStrokeSpec {
        private final int keyCode;
        private final char keyChar;

        private KeyStrokeSpec(int keyCode, char keyChar) {
            this.keyCode = keyCode;
            this.keyChar = keyChar;
        }
    }

    // Normal mode key handling
    private void handleNormalMode(KeyEvent e) {
        char c = e.getKeyChar();
        int code = e.getKeyCode();

        if (pendingTextObjectOperator != null) {
            showMessage(applyTextObjectOperator(pendingTextObjectOperator, pendingTextObjectModifier, c));
            pendingTextObjectOperator = null;
            pendingTextObjectModifier = null;
            return;
        }
        if (pendingSurroundAction != null) {
            showMessage(handleSurroundPending(c));
            return;
        }

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

        if (!pendingCount.isEmpty() && !supportsCountPrefix(e)) {
            pendingCount = "";
        }

        if (isTreePaneActive() && (code == KeyEvent.VK_ENTER || c == 'o')) {
            pendingCount = "";
            showMessage(openTreeSelection());
            return;
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
            repeatAction(consumePendingCount(), this::moveUp);
        } else if (code == KeyEvent.VK_DOWN || c == 'j') {
            repeatAction(consumePendingCount(), this::moveDown);
        } else if (code == KeyEvent.VK_LEFT || c == 'h') {
            repeatAction(consumePendingCount(), this::moveLeft);
        } else if (code == KeyEvent.VK_RIGHT || c == 'l') {
            repeatAction(consumePendingCount(), this::moveRight);
        }

        // Word movements
        else if (c == 'w') {
            repeatAction(consumePendingCount(), this::moveWordForward);
        } else if (c == 'b') {
            repeatAction(consumePendingCount(), this::moveWordBackward);
        } else if (c == 'e') {
            repeatAction(consumePendingCount(), this::moveWordEnd);
        } else if (c == 'W') {
            repeatAction(consumePendingCount(), this::moveWordForwardBig);
        } else if (c == 'B') {
            repeatAction(consumePendingCount(), this::moveWordBackwardBig);
        } else if (c == 'E') {
            repeatAction(consumePendingCount(), this::moveWordEndBig);
        }

        // Line movements
        else if (c == '0') {
            moveLineStart();
            pendingCount = "";
        } else if (c == '^') {
            moveLineFirstNonBlank();
            pendingCount = "";
        } else if (c == '$') {
            moveLineEnd();
            pendingCount = "";
        }

        // File movements
        else if (c == 'g') {
            pendingKey = 'g';
        } else if (c == 'G') {
            int count = consumePendingCount();
            if (count > 1) {
                showMessage(gotoLine(count));
            } else {
                moveFileEnd();
            }
        } else if (c == 'q') {
            if (recordingRegister != null) {
                registerManager.setMacro(recordingRegister, macroBuffer);
                lastMacroRegister = recordingRegister;
                showMessage("Recorded macro to @" + recordingRegister);
                recordingRegister = null;
                macroBuffer = new ArrayList<>();
            } else {
                pendingKey = 'q';
            }
            return;
        } else if (c == '@') {
            pendingKey = '@';
            return;
        } else if (c == '"') {
            pendingKey = '"';
        } else if (c == 'm' || c == '\'' || c == '`') {
            pendingKey = c;
        } else if (c == 'f' || c == 'F' || c == 't' || c == 'T' || c == '>' || c == '<' || c == '=' || c == 'r') {
            pendingKey = c;
        } else if (c == 'z') {
            pendingKey = 'z';
        } else if (c == '{') {
            repeatAction(consumePendingCount(), this::moveParagraphBackward);
        } else if (c == '}') {
            repeatAction(consumePendingCount(), this::moveParagraphForward);
        } else if (c == '(') {
            repeatAction(consumePendingCount(), this::moveSentenceBackward);
        } else if (c == ')') {
            repeatAction(consumePendingCount(), this::moveSentenceForward);
        } else if (c == '%') {
            int count = consumePendingCount();
            if (count > 1) {
                moveToFilePercent(count);
            } else {
                moveMatchingBracket();
            }
        } else if (c == 'H') {
            moveToScreenPosition('H');
            pendingCount = "";
        } else if (c == 'M') {
            moveToScreenPosition('M');
            pendingCount = "";
        } else if (c == 'L') {
            moveToScreenPosition('L');
            pendingCount = "";
        }

        // Clipboard operations
        else if (c == 'y') {
            pendingKey = 'y';
        } else if (c == 'd') {
            pendingKey = 'd';
        } else if (c == 'c') {
            pendingKey = 'c';
        } else if (c == 'x') {
            pendingCount = "";
            storeDelete(consumePendingRegister(), clipboardManager.deleteChar(writingArea), false);
            markModified();
        } else if (c == 'Y') {
            pendingCount = "";
            showMessage(yankToEndOfLine());
        } else if (c == 'p') {
            pendingCount = "";
            showMessage(pasteFromRegister(false));
        } else if (c == 'P') {
            pendingCount = "";
            showMessage(pasteFromRegister(true));
        } else if (c == 'D') {
            pendingCount = "";
            lastCommand = "D";
            storeDelete(consumePendingRegister(), clipboardManager.deleteToEndOfLine(writingArea), false);
            markModified();
        } else if (c == 'C') {
            pendingCount = "";
            lastCommand = "C";
            storeDelete(consumePendingRegister(), clipboardManager.deleteToEndOfLine(writingArea), false);
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
            if (c == 'w' || code == KeyEvent.VK_W) {
                pendingCount = "";
                pendingKey = '\u0017';
                return;
            } else if (c == 'p' || code == KeyEvent.VK_P) {
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

    private boolean supportsCountPrefix(KeyEvent e) {
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
                return true;
            default:
                return false;
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
            } else if (c == 'e') {
                repeatAction(consumePendingCount(), this::moveWordEndBackward);
            } else if (c == 'E') {
                repeatAction(consumePendingCount(), this::moveWordEndBackwardBig);
            } else if (c == '0') {
                moveLineStart();
                pendingCount = "";
            } else if (c == '$') {
                moveLineEnd();
                pendingCount = "";
            } else if (c == '_') {
                moveLineLastNonBlank();
                pendingCount = "";
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
                storeYank(consumePendingRegister(), clipboardManager.yankLine(writingArea), true);
                showMessage("Line yanked");
            } else if (c == 's') {
                pendingSurroundAction = 'y';
                pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                pendingTextObjectOperator = 'y';
                pendingTextObjectModifier = c;
                pendingKey = '\0';
                return;
            } else if (c == 'g') {
                pendingKey = 'Y';
                return;
            } else {
                showMessage(applyMotionOperator('y', String.valueOf(c)));
            }
            pendingKey = '\0';
            pendingCount = "";
        } else if (pendingKey == 'd') {
            if (c == 'd') {
                lastCommand = "dd";
                storeDelete(consumePendingRegister(), clipboardManager.deleteLine(writingArea), true);
                markModified();
                showMessage("Line deleted");
            } else if (c == 's') {
                pendingSurroundAction = 'd';
                pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                pendingTextObjectOperator = 'd';
                pendingTextObjectModifier = c;
                pendingKey = '\0';
                return;
            } else if (c == 'g') {
                pendingKey = 'D';
                return;
            } else if (c == 'w') {
                lastCommand = "dw";
                storeDelete(consumePendingRegister(), clipboardManager.deleteWord(writingArea), false);
                markModified();
                showMessage("Word deleted");
            } else {
                showMessage(applyMotionOperator('d', String.valueOf(c)));
            }
            pendingKey = '\0';
            pendingCount = "";
        } else if (pendingKey == 'c') {
            if (c == 'c') {
                lastCommand = "cc";
                storeDelete(consumePendingRegister(), clipboardManager.deleteLine(writingArea), true);
                lastInsertedText = "";
                setMode(EditorMode.INSERT);
            } else if (c == 's') {
                pendingSurroundAction = 'c';
                pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                pendingTextObjectOperator = 'c';
                pendingTextObjectModifier = c;
                pendingKey = '\0';
                return;
            } else if (c == 'g') {
                pendingKey = 'C';
                return;
            } else if (c == 'w') {
                lastCommand = "cw";
                storeDelete(consumePendingRegister(), clipboardManager.deleteWord(writingArea), false);
                lastInsertedText = "";
                setMode(EditorMode.INSERT);
            } else {
                showMessage(applyMotionOperator('c', String.valueOf(c)));
            }
            pendingKey = '\0';
            pendingCount = "";
        } else if (pendingKey == 'q') {
            recordingRegister = c;
            macroBuffer = new ArrayList<>();
            pendingKey = '\0';
            showMessage("recording @" + c);
        } else if (pendingKey == '@') {
            if (c == '@') {
                showMessage(playMacro(lastMacroRegister));
            } else {
                showMessage(playMacro(c));
            }
            pendingKey = '\0';
        } else if (pendingKey == '"') {
            pendingRegister = c;
            pendingKey = '\0';
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
        } else if (pendingKey == 'r') {
            showMessage(replaceCharacter(c));
            pendingKey = '\0';
        } else if (pendingKey == '>' || pendingKey == '<' || pendingKey == '=') {
            if (c == pendingKey) {
                showMessage(applyLineOperator(pendingKey));
            }
            pendingKey = '\0';
        } else if (pendingKey == 'D' || pendingKey == 'C' || pendingKey == 'Y') {
            char operator = pendingKey == 'D' ? 'd' : pendingKey == 'C' ? 'c' : 'y';
            showMessage(applyMotionOperator(operator, "g" + c));
            pendingKey = '\0';
        } else if (pendingKey == '\u0017') {
            switch (c) {
                case 's':
                    showMessage(splitWindow(false));
                    break;
                case 'v':
                    showMessage(splitWindow(true));
                    break;
                case 'c':
                    showMessage(closeActiveWindow());
                    break;
                case 'h':
                    showMessage(focusWindowDirection(-1, 0));
                    break;
                case 'j':
                    showMessage(focusWindowDirection(0, 1));
                    break;
                case 'k':
                    showMessage(focusWindowDirection(0, -1));
                    break;
                case 'l':
                    showMessage(focusWindowDirection(1, 0));
                    break;
                case 'w':
                    showMessage(cycleWindowFocus());
                    break;
                case '=':
                    showMessage(equalizeWindows());
                    break;
                default:
                    break;
            }
            pendingKey = '\0';
        } else if (pendingKey == 'z') {
            if (c == 't') {
                scrollCurrentLineTo('t');
            } else if (c == 'z') {
                scrollCurrentLineTo('z');
            } else if (c == 'b') {
                scrollCurrentLineTo('b');
            }
            pendingKey = '\0';
        }

    }

    // Insert mode key handling
    private void handleInsertMode(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            registerManager.updateLastInserted(lastInsertedText);
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
        if (lineMode) {
            normalizeVisualLineCaretForMotion();
        }

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
                storeYank(consumePendingRegister(), selected, lineMode);
                showMessage("Selection yanked");
            }
            setMode(EditorMode.NORMAL);
        } else if (c == 'd') {
            String selected = writingArea.getSelectedText();
            if (selected != null) {
                clipboardManager.yankSelection(selected);
                storeDelete(consumePendingRegister(), selected, lineMode);
                writingArea.replaceSelection("");
                markModified();
                showMessage("Selection deleted");
            }
            setMode(EditorMode.NORMAL);
        } else if (c == 'c') {
            String selected = writingArea.getSelectedText();
            if (selected != null) {
                clipboardManager.yankSelection(selected);
                storeDelete(consumePendingRegister(), selected, lineMode);
                writingArea.replaceSelection("");
                markModified();
            }
            setMode(EditorMode.INSERT);
        }
    }

    private void normalizeVisualLineCaretForMotion() {
        int selectionStart = writingArea.getSelectionStart();
        int selectionEnd = writingArea.getSelectionEnd();
        int caret = writingArea.getCaretPosition();
        if (selectionEnd > selectionStart && caret == selectionEnd && caret > 0) {
            writingArea.setCaretPosition(Math.max(selectionStart, caret - 1));
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

    private void moveWordForwardBig() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();
        while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        writingArea.setCaretPosition(Math.min(pos, text.length()));
    }

    private void moveWordBackwardBig() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();
        if (pos > 0) {
            pos--;
            while (pos > 0 && Character.isWhitespace(text.charAt(pos))) {
                pos--;
            }
            while (pos > 0 && !Character.isWhitespace(text.charAt(pos - 1))) {
                pos--;
            }
        }
        writingArea.setCaretPosition(pos);
    }

    private void moveWordEndBig() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();
        if (pos < text.length()) {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (pos > 0) {
                pos--;
            }
        }
        writingArea.setCaretPosition(Math.min(pos, text.length()));
    }

    private void moveWordEndBackward() {
        moveWordEndBackwardInternal(false);
    }

    private void moveWordEndBackwardBig() {
        moveWordEndBackwardInternal(true);
    }

    private void moveWordEndBackwardInternal(boolean bigWord) {
        String text = writingArea.getText();
        int pos = Math.max(0, writingArea.getCaretPosition() - 1);
        while (pos > 0 && Character.isWhitespace(text.charAt(pos))) {
            pos--;
        }
        while (pos > 0 && isMotionWordChar(text.charAt(pos - 1), bigWord)) {
            pos--;
        }
        if (pos < text.length()) {
            while (pos < text.length() - 1 && isMotionWordChar(text.charAt(pos + 1), bigWord)) {
                pos++;
            }
        }
        writingArea.setCaretPosition(Math.max(0, pos));
    }

    private boolean isMotionWordChar(char c, boolean bigWord) {
        return bigWord ? !Character.isWhitespace(c) : isWordCharacter(c);
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

    private void moveLineFirstNonBlank() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int lineStart = writingArea.getLineStartOffset(line);
            int lineEnd = writingArea.getLineEndOffset(line);
            String lineText = writingArea.getText().substring(lineStart, lineEnd);
            int offset = 0;
            while (offset < lineText.length() && Character.isWhitespace(lineText.charAt(offset)) && lineText.charAt(offset) != '\n') {
                offset++;
            }
            writingArea.setCaretPosition(Math.min(lineStart + offset, writingArea.getText().length()));
        } catch (BadLocationException ignored) {
        }
    }

    private void moveLineLastNonBlank() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int lineStart = writingArea.getLineStartOffset(line);
            int lineEnd = writingArea.getLineEndOffset(line);
            String lineText = writingArea.getText().substring(lineStart, lineEnd);
            int offset = lineText.length() - 1;
            while (offset > 0 && Character.isWhitespace(lineText.charAt(offset))) {
                offset--;
            }
            writingArea.setCaretPosition(Math.min(lineStart + offset, Math.max(lineStart, writingArea.getText().length())));
        } catch (BadLocationException ignored) {
        }
    }

    private void moveFileStart() {
        writingArea.setCaretPosition(0);
    }

    private void moveFileEnd() {
        writingArea.setCaretPosition(writingArea.getText().length());
    }

    private void moveParagraphForward() {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            for (int i = line + 1; i < writingArea.getLineCount(); i++) {
                if (lineText(i).isBlank()) {
                    int targetLine = Math.min(i + 1, writingArea.getLineCount() - 1);
                    writingArea.setCaretPosition(writingArea.getLineStartOffset(targetLine));
                    return;
                }
            }
            moveFileEnd();
        } catch (BadLocationException ignored) {
        }
    }

    private void moveParagraphBackward() {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            for (int i = Math.max(0, line - 1); i >= 0; i--) {
                if (lineText(i).isBlank()) {
                    int targetLine = Math.max(0, i - 1);
                    writingArea.setCaretPosition(writingArea.getLineStartOffset(targetLine));
                    return;
                }
            }
            moveFileStart();
        } catch (BadLocationException ignored) {
        }
    }

    private void moveSentenceForward() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '.' || c == '!' || c == '?') {
                pos++;
                while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                    pos++;
                }
                writingArea.setCaretPosition(Math.min(pos, text.length()));
                return;
            }
            pos++;
        }
        moveFileEnd();
    }

    private void moveSentenceBackward() {
        String text = writingArea.getText();
        int pos = Math.max(0, writingArea.getCaretPosition() - 1);
        while (pos > 0) {
            char c = text.charAt(pos);
            if (c == '.' || c == '!' || c == '?') {
                pos++;
                while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                    pos++;
                }
                writingArea.setCaretPosition(Math.min(pos, text.length()));
                return;
            }
            pos--;
        }
        moveFileStart();
    }

    private void moveMatchingBracket() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();
        if (text.isEmpty() || pos < 0 || pos >= text.length()) {
            return;
        }
        char current = text.charAt(pos);
        String opens = "([{<";
        String closes = ")]}>";
        int openIndex = opens.indexOf(current);
        int closeIndex = closes.indexOf(current);
        if (openIndex >= 0) {
            char close = closes.charAt(openIndex);
            int depth = 0;
            for (int i = pos; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == current) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        writingArea.setCaretPosition(i);
                        return;
                    }
                }
            }
        } else if (closeIndex >= 0) {
            char open = opens.charAt(closeIndex);
            int depth = 0;
            for (int i = pos; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == current) {
                    depth++;
                } else if (c == open) {
                    depth--;
                    if (depth == 0) {
                        writingArea.setCaretPosition(i);
                        return;
                    }
                }
            }
        }
    }

    private void moveToFilePercent(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        String text = writingArea.getText();
        int target = (int) Math.round((text.length() * clamped) / 100.0);
        writingArea.setCaretPosition(Math.min(target, text.length()));
    }

    private void moveToScreenPosition(char position) {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            int y;
            switch (position) {
                case 'H':
                    y = visible.y;
                    break;
                case 'L':
                    y = visible.y + visible.height;
                    break;
                case 'M':
                default:
                    y = visible.y + (visible.height / 2);
                    break;
            }
            int offset = writingArea.viewToModel2D(new Point(0, y));
            writingArea.setCaretPosition(Math.min(offset, writingArea.getText().length()));
        } catch (Exception ignored) {
        }
    }

    private void scrollCurrentLineTo(char anchor) {
        try {
            Rectangle lineBounds = writingArea.modelToView2D(writingArea.getCaretPosition()).getBounds();
            Rectangle visible = writingArea.getVisibleRect();
            int targetY = visible.y;
            switch (anchor) {
                case 'b':
                    targetY = Math.max(0, lineBounds.y - visible.height + lineBounds.height);
                    break;
                case 'z':
                    targetY = Math.max(0, lineBounds.y - (visible.height / 2));
                    break;
                case 't':
                default:
                    targetY = Math.max(0, lineBounds.y);
                    break;
            }
            writingArea.scrollRectToVisible(new Rectangle(visible.x, targetY, visible.width, visible.height));
        } catch (BadLocationException ignored) {
        }
    }

    private String lineText(int line) throws BadLocationException {
        int start = writingArea.getLineStartOffset(line);
        int end = writingArea.getLineEndOffset(line);
        return writingArea.getText().substring(start, end);
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
            if (currentLine >= anchorLine) {
                writingArea.setCaretPosition(selectionStart);
                writingArea.moveCaretPosition(selectionEnd);
            } else {
                writingArea.setCaretPosition(selectionEnd);
                writingArea.moveCaretPosition(selectionStart);
            }
        } catch (BadLocationException ignored) {
        }
    }

    private void ensureCaretVisible(JTextArea area) {
        if (area == null) {
            return;
        }
        try {
            Rectangle2D bounds = area.modelToView2D(area.getCaretPosition());
            if (bounds != null) {
                area.scrollRectToVisible(bounds.getBounds());
            }
        } catch (BadLocationException ignored) {
        }
    }

    private boolean isPrintableKey(KeyEvent e) {
        char c = e.getKeyChar();
        return c != KeyEvent.CHAR_UNDEFINED
            && !Character.isISOControl(c)
            && !e.isControlDown()
            && !e.isAltDown()
            && !e.isMetaDown();
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
        appendCommandLog(entry);
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
        knownCommands.add("registers");
        knownCommands.add("marks");
        knownCommands.add("goyo");
        knownCommands.add("normal");
        knownCommands.add("reload");
        knownCommands.add("source");
        knownCommands.addAll(configManager.getConfiguredCommandAliases());

        for (String command : knownCommands) {
            if (command.startsWith(lowered)) {
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
            pane.getLineNumberPanel().setColors(
                configManager.getLineNumberBackground(),
                configManager.getLineNumberForeground(),
                configManager.getLineNumberActiveForeground()
            );
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

    private void applyThemeColors() {
        Color editorForeground = configManager.getEditorForeground();
        Color caretColor = configManager.getCaretColor();
        Color selectionColor = configManager.getSelectionColor();
        Color selectionTextColor = configManager.getSelectionTextColor();

        currentLinePainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getCurrentLineHighlightColor());
        substitutePreviewPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSubstitutePreviewColor());
        syntaxKeywordPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSyntaxKeywordColor());
        syntaxStringPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSyntaxStringColor());
        syntaxCommentPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSyntaxCommentColor());
        syntaxNumberPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSyntaxNumberColor());

        statusBar.setBackground(configManager.getStatusBarBackground());
        statusBar.setForeground(configManager.getStatusBarForeground());
        commandBar.setBackground(configManager.getCommandBarBackground());
        commandBar.setForeground(configManager.getCommandBarForeground());

        for (EditorPane pane : editorPanes) {
            JTextArea area = pane.getTextArea();
            area.setForeground(editorForeground);
            area.setCaretColor(caretColor);
            area.setSelectionColor(selectionColor);
            area.setSelectedTextColor(selectionTextColor);
        }

        if (currentMode != null) {
            writingArea.setBackground(getModeBackground(currentMode));
        }
        updateZenModeLayout();

        refreshLineNumberPanel();
        updateCurrentLineHighlight();
        applySyntaxHighlighting();
        updateSubstitutePreview();
        updateStatusBar();
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

        Highlighter highlighter = writingArea.getHighlighter();
        boolean[] masked = new boolean[text.length()];
        FileType fileType = buffer.getFileType();

        highlightComments(highlighter, text, fileType, masked);
        highlightStrings(highlighter, text, fileType, masked);
        highlightNumbers(highlighter, text, masked);
        if (fileType == FileType.JAVA) {
            highlightJavaAnnotations(highlighter, text, masked);
        }
        highlightKeywords(highlighter, text, syntaxKeywordsFor(fileType), masked);
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
                return new String[] {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "exports", "extends", "final", "finally", "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "module", "native", "new", "non-sealed", "null", "open", "opens", "package", "permits", "private", "protected", "provides", "public", "record", "requires", "return", "sealed", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "to", "transient", "transitive", "true", "try", "uses", "var", "void", "volatile", "when", "while", "with", "yield", "false"};
            case JAVASCRIPT:
            case TYPESCRIPT:
                return new String[] {"as", "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for", "from", "function", "if", "implements", "import", "in", "instanceof", "interface", "let", "new", "null", "private", "protected", "public", "readonly", "return", "static", "super", "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "var", "void", "while", "yield"};
            case PYTHON:
                return new String[] {"and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield"};
            case RUST:
                return new String[] {"as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "Self", "self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while"};
            case GO:
                return new String[] {"break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var"};
            case C:
            case CPP:
                return new String[] {"alignas", "alignof", "asm", "auto", "bool", "break", "case", "catch", "char", "class", "const", "constexpr", "continue", "default", "delete", "do", "double", "else", "enum", "extern", "false", "float", "for", "goto", "if", "inline", "int", "long", "mutable", "namespace", "new", "nullptr", "operator", "private", "protected", "public", "register", "return", "short", "signed", "sizeof", "static", "struct", "switch", "template", "this", "throw", "true", "try", "typedef", "typename", "union", "unsigned", "using", "virtual", "void", "volatile", "while", "#include", "#define"};
            case HTML:
                return new String[] {"<!DOCTYPE", "<html", "<head", "<body", "<main", "<section", "<article", "<aside", "<nav", "<header", "<footer", "<div", "<span", "<p", "<a", "<img", "<button", "<input", "<label", "<form", "<ul", "<ol", "<li", "<table", "<tr", "<td", "<th", "<script", "<style", "class", "id", "href", "src"};
            case CSS:
                return new String[] {"display", "position", "color", "background", "background-color", "padding", "margin", "width", "height", "max-width", "min-width", "font-family", "font-size", "font-weight", "line-height", "text-align", "border", "border-radius", "box-shadow", "opacity", "flex", "flex-direction", "justify-content", "align-items", "grid", "grid-template-columns", "gap", "overflow", "z-index", "absolute", "relative", "fixed", "sticky"};
            case JSON:
                return new String[] {"true", "false", "null"};
            case MARKDOWN:
                return new String[] {"# ", "## ", "### ", "#### ", "##### ", "###### ", "- ", "* ", "> ", "```"};
            default:
                return new String[0];
        }
    }

    private void highlightJavaAnnotations(Highlighter highlighter, String text, boolean[] masked) {
        int i = 0;
        while (i < text.length()) {
            if (masked[i] || text.charAt(i) != '@') {
                i++;
                continue;
            }
            int start = i;
            int end = i + 1;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (!isIdentifierChar(c) && c != '.') {
                    break;
                }
                end++;
            }
            if (end > start + 1) {
                addSyntaxHighlight(highlighter, start, end, syntaxKeywordPainter, masked);
                i = end;
            } else {
                i++;
            }
        }
    }

    private void highlightKeywords(Highlighter highlighter, String text, String[] keywords, boolean[] masked) {
        if (keywords == null || keywords.length == 0) {
            return;
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            int index = 0;
            while (index <= text.length() - keyword.length()) {
                int match = text.indexOf(keyword, index);
                if (match < 0) {
                    break;
                }
                int end = match + keyword.length();
                if (isKeywordMatch(text, match, keyword, masked)) {
                    addSyntaxHighlight(highlighter, match, end, syntaxKeywordPainter, masked);
                }
                index = match + Math.max(1, keyword.length());
            }
        }
    }

    private boolean isKeywordMatch(String text, int start, String keyword, boolean[] masked) {
        int end = start + keyword.length();
        if (start < 0 || end > text.length() || isMasked(masked, start, end)) {
            return false;
        }
        boolean needsLeftBoundary = isIdentifierChar(keyword.charAt(0));
        boolean needsRightBoundary = isIdentifierChar(keyword.charAt(keyword.length() - 1));
        if (needsLeftBoundary && start > 0 && isIdentifierChar(text.charAt(start - 1))) {
            return false;
        }
        if (needsRightBoundary && end < text.length() && isIdentifierChar(text.charAt(end))) {
            return false;
        }
        return true;
    }

    private void highlightComments(Highlighter highlighter, String text, FileType fileType, boolean[] masked) {
        String[] linePrefixes = lineCommentPrefixesFor(fileType);
        String[][] blockPairs = blockCommentPairsFor(fileType);
        int i = 0;
        while (i < text.length()) {
            if (masked[i]) {
                i++;
                continue;
            }

            boolean matched = false;
            for (String prefix : linePrefixes) {
                if (matchesAt(text, i, prefix)) {
                    int end = i + prefix.length();
                    while (end < text.length() && text.charAt(end) != '\n') {
                        end++;
                    }
                    addSyntaxHighlight(highlighter, i, end, syntaxCommentPainter, masked);
                    i = Math.max(i + 1, end);
                    matched = true;
                    break;
                }
            }
            if (matched) {
                continue;
            }

            for (String[] pair : blockPairs) {
                String open = pair[0];
                String close = pair[1];
                if (!matchesAt(text, i, open)) {
                    continue;
                }
                int closeIndex = text.indexOf(close, i + open.length());
                int end = closeIndex < 0 ? text.length() : closeIndex + close.length();
                addSyntaxHighlight(highlighter, i, end, syntaxCommentPainter, masked);
                i = Math.max(i + 1, end);
                matched = true;
                break;
            }
            if (!matched) {
                i++;
            }
        }
    }

    private void highlightStrings(Highlighter highlighter, String text, FileType fileType, boolean[] masked) {
        int i = 0;
        while (i < text.length()) {
            if (masked[i]) {
                i++;
                continue;
            }

            if (fileType == FileType.JAVA && matchesAt(text, i, "\"\"\"")) {
                int closeIndex = text.indexOf("\"\"\"", i + 3);
                int end = closeIndex < 0 ? text.length() : closeIndex + 3;
                addSyntaxHighlight(highlighter, i, end, syntaxStringPainter, masked);
                i = Math.max(i + 1, end);
                continue;
            }

            if (fileType == FileType.PYTHON && (matchesAt(text, i, "\"\"\"") || matchesAt(text, i, "'''"))) {
                String delimiter = matchesAt(text, i, "\"\"\"") ? "\"\"\"" : "'''";
                int closeIndex = text.indexOf(delimiter, i + delimiter.length());
                int end = closeIndex < 0 ? text.length() : closeIndex + delimiter.length();
                addSyntaxHighlight(highlighter, i, end, syntaxStringPainter, masked);
                i = Math.max(i + 1, end);
                continue;
            }

            char c = text.charAt(i);
            if (!isStringDelimiter(fileType, c)) {
                i++;
                continue;
            }

            boolean multiline = c == '`';
            int end = i + 1;
            boolean escaped = false;
            while (end < text.length()) {
                char current = text.charAt(end);
                if (!multiline && current == '\n') {
                    break;
                }
                if (!escaped && current == c) {
                    end++;
                    break;
                }
                if (current == '\\' && !escaped) {
                    escaped = true;
                } else {
                    escaped = false;
                }
                end++;
            }
            addSyntaxHighlight(highlighter, i, Math.max(i + 1, end), syntaxStringPainter, masked);
            i = Math.max(i + 1, end);
        }
    }

    private void highlightNumbers(Highlighter highlighter, String text, boolean[] masked) {
        int i = 0;
        while (i < text.length()) {
            if (masked[i]) {
                i++;
                continue;
            }
            if (!Character.isDigit(text.charAt(i)) || (i > 0 && isIdentifierChar(text.charAt(i - 1)))) {
                i++;
                continue;
            }

            int start = i;
            int end = i + 1;
            while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                end++;
            }
            if (end + 1 < text.length() && text.charAt(end) == '.' && Character.isDigit(text.charAt(end + 1))) {
                end++;
                while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                    end++;
                }
            }
            if (end < text.length() && (text.charAt(end) == 'e' || text.charAt(end) == 'E')) {
                int exponent = end + 1;
                if (exponent < text.length() && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                    exponent++;
                }
                if (exponent < text.length() && Character.isDigit(text.charAt(exponent))) {
                    end = exponent + 1;
                    while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                        end++;
                    }
                }
            }
            if (end >= text.length() || !isIdentifierChar(text.charAt(end))) {
                addSyntaxHighlight(highlighter, start, end, syntaxNumberPainter, masked);
            }
            i = Math.max(i + 1, end);
        }
    }

    private void addSyntaxHighlight(Highlighter highlighter, int start, int end, Highlighter.HighlightPainter painter, boolean[] masked) {
        if (start < 0 || end <= start || start >= masked.length) {
            return;
        }
        int safeEnd = Math.min(end, masked.length);
        if (isMasked(masked, start, safeEnd)) {
            return;
        }
        try {
            syntaxHighlightTags.add(highlighter.addHighlight(start, safeEnd, painter));
            markMasked(masked, start, safeEnd);
        } catch (BadLocationException ignored) {
        }
    }

    private boolean isMasked(boolean[] masked, int start, int end) {
        for (int i = start; i < end && i < masked.length; i++) {
            if (masked[i]) {
                return true;
            }
        }
        return false;
    }

    private void markMasked(boolean[] masked, int start, int end) {
        for (int i = Math.max(0, start); i < end && i < masked.length; i++) {
            masked[i] = true;
        }
    }

    private boolean matchesAt(String text, int index, String token) {
        if (token == null || token.isEmpty() || index < 0 || index + token.length() > text.length()) {
            return false;
        }
        return text.regionMatches(index, token, 0, token.length());
    }

    private boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private boolean isStringDelimiter(FileType fileType, char c) {
        if (c == '"' || c == '\'') {
            return true;
        }
        return (fileType == FileType.JAVASCRIPT || fileType == FileType.TYPESCRIPT || fileType == FileType.MARKDOWN) && c == '`';
    }

    private String[] lineCommentPrefixesFor(FileType fileType) {
        switch (fileType) {
            case JAVA:
            case JAVASCRIPT:
            case TYPESCRIPT:
            case C:
            case CPP:
            case GO:
            case RUST:
                return new String[] {"//"};
            case PYTHON:
                return new String[] {"#"};
            default:
                return new String[0];
        }
    }

    private String[][] blockCommentPairsFor(FileType fileType) {
        switch (fileType) {
            case JAVA:
            case JAVASCRIPT:
            case TYPESCRIPT:
            case C:
            case CPP:
            case GO:
            case RUST:
            case CSS:
                return new String[][] {{"/*", "*/"}};
            case HTML:
            case MARKDOWN:
                return new String[][] {{"<!--", "-->"}};
            default:
                return new String[0][0];
        }
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
        CommandResult branch = runCommand(new File("."), List.of("git", "rev-parse", "--abbrev-ref", "HEAD"));
        if (branch.exitCode != 0) {
            return "";
        }
        String branchName = branch.stdout.strip();
        if (branchName.isEmpty()) {
            return "";
        }
        if ("HEAD".equals(branchName)) {
            CommandResult detached = runCommand(new File("."), List.of("git", "rev-parse", "--short", "HEAD"));
            if (detached.exitCode != 0) {
                return "";
            }
            return detached.stdout.strip();
        }
        return branchName;
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
        File selection = chooseWithNavigator(JFileChooser.FILES_ONLY, null, "Open File");
        if (selection == null) {
            return "File finder cancelled";
        }
        if (!selection.isFile()) {
            return "Not a file: " + selection.getPath();
        }
        try {
            openFile(selection);
            return "Opened: " + selection.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening file: " + e.getMessage();
        }
    }

    public String showFolderFinder() {
        File selection = chooseWithNavigator(JFileChooser.DIRECTORIES_ONLY, null, "Select Folder");
        if (selection == null) {
            return "Folder finder cancelled";
        }
        if (!selection.isDirectory()) {
            return "Not a folder: " + selection.getPath();
        }
        return showFileFinderFromFolder(selection);
    }

    private String showFileFinderFromFolder(File folder) {
        File selection = chooseWithNavigator(JFileChooser.FILES_ONLY, folder, "Open File in " + folder.getPath());
        if (selection == null) {
            return "Folder selected: " + folder.getPath();
        }
        if (!selection.isFile()) {
            return "Not a file: " + selection.getPath();
        }

        try {
            openFile(selection);
            return "Opened: " + selection.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening file: " + e.getMessage();
        }
    }

    private File chooseWithNavigator(int selectionMode, File startDirectory, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(selectionMode);
        chooser.setDialogTitle(title);
        chooser.setCurrentDirectory(resolveNavigatorStartDirectory(startDirectory));
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile();
    }

    private File resolveNavigatorStartDirectory(File preferred) {
        if (preferred != null && preferred.exists()) {
            return preferred;
        }
        FileBuffer buffer = getCurrentBuffer();
        if (buffer != null && buffer.hasFilePath()) {
            File parent = new File(buffer.getFilePath()).getParentFile();
            if (parent != null && parent.exists()) {
                return parent;
            }
        }
        return new File(System.getProperty("user.home"));
    }

    public String showFileTree(String pathArgument) {
        String target = pathArgument == null ? "" : pathArgument.trim();
        File root = target.isEmpty() ? new File(".") : new File(target);
        if (!root.exists()) {
            return "Path not found: " + root.getPath();
        }

        StringBuilder builder = new StringBuilder();
        List<String> lineTargets = new ArrayList<>();
        appendTreeLine(builder, lineTargets, "File tree", null);
        appendTreeLine(builder, lineTargets, "", null);
        appendTreeLine(builder, lineTargets, root.getAbsolutePath(), root.isFile() ? root.getAbsolutePath() : null);
        int[] rendered = new int[] {0};
        if (root.isDirectory()) {
            File[] children = listTreeChildren(root);
            if (children.length == 0) {
                appendTreeLine(builder, lineTargets, "(empty)", null);
            } else {
                for (int i = 0; i < children.length; i++) {
                    appendTreeEntry(builder, lineTargets, children[i], "", i == children.length - 1, rendered, 1200);
                }
            }
        } else {
            appendTreeLine(builder, lineTargets, "\\-- " + root.getName(), root.getAbsolutePath());
        }

        if (rendered[0] >= 1200) {
            appendTreeLine(builder, lineTargets, "", null);
            appendTreeLine(builder, lineTargets, "... output truncated (1200 entries)", null);
        }

        String titleSuffix = treeTitleSuffix(root);
        FileBuffer tree = createOrReplaceTreeBuffer(titleSuffix, builder.toString(), lineTargets);
        EditorPane contentPane = resolveTreeContentPaneForTreeCommand();
        if (contentPane == null) {
            return "No active window";
        }
        EditorPane pane = ensureTreePane(contentPane);
        if (pane == null) {
            return "Unable to open tree pane";
        }
        loadBufferIntoPane(pane, tree, 0);
        activateEditorPane(pane);
        pane.getTextArea().requestFocusInWindow();
        return "Tree pane opened";
    }

    private void appendTreeEntry(StringBuilder builder, List<String> lineTargets, File entry, String prefix, boolean last, int[] rendered, int maxEntries) {
        if (rendered[0] >= maxEntries) {
            return;
        }
        StringBuilder lineBuilder = new StringBuilder();
        lineBuilder.append(prefix).append(last ? "\\-- " : "|-- ");
        lineBuilder.append(entry.getName());
        if (entry.isDirectory()) {
            lineBuilder.append("/");
        }
        appendTreeLine(builder, lineTargets, lineBuilder.toString(), entry.isFile() ? entry.getAbsolutePath() : null);
        rendered[0]++;

        if (!entry.isDirectory()) {
            return;
        }

        File[] children = listTreeChildren(entry);
        String childPrefix = prefix + (last ? "    " : "|   ");
        for (int i = 0; i < children.length; i++) {
            appendTreeEntry(builder, lineTargets, children[i], childPrefix, i == children.length - 1, rendered, maxEntries);
            if (rendered[0] >= maxEntries) {
                return;
            }
        }
    }

    private void appendTreeLine(StringBuilder builder, List<String> lineTargets, String text, String targetPath) {
        builder.append(text).append("\n");
        lineTargets.add(targetPath);
    }

    private File[] listTreeChildren(File directory) {
        File[] children = directory.listFiles(file -> !shouldSkipHiddenPath(file));
        if (children == null || children.length == 0) {
            return new File[0];
        }
        java.util.Arrays.sort(children, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) {
                return left.isDirectory() ? -1 : 1;
            }
            return left.getName().compareToIgnoreCase(right.getName());
        });
        return children;
    }

    private String treeTitleSuffix(File root) {
        String name = root.getName();
        if (name == null || name.isEmpty()) {
            return root.getAbsolutePath();
        }
        return name;
    }

    private FileBuffer createOrReplaceTreeBuffer(String titleSuffix, String content, List<String> lineTargets) {
        FileBuffer replacement = FileBuffer.createScratch("[tree " + titleSuffix + "]", content);
        if (treeBuffer != null) {
            int index = buffers.indexOf(treeBuffer);
            if (index >= 0) {
                buffers.set(index, replacement);
            } else {
                buffers.add(replacement);
            }
            treeLineTargets.remove(treeBuffer);
        } else {
            buffers.add(replacement);
        }
        treeBuffer = replacement;
        treeLineTargets.put(replacement, lineTargets);
        return replacement;
    }

    private EditorPane resolveTreeContentPaneForTreeCommand() {
        EditorPane active = getActivePane();
        if (treePane != null && editorPanes.contains(treePane)) {
            if (active != null && active != treePane) {
                return active;
            }
            for (EditorPane pane : editorPanes) {
                if (pane != treePane) {
                    return pane;
                }
            }
        }
        return active;
    }

    private EditorPane ensureTreePane(EditorPane contentPane) {
        if (treePane != null && editorPanes.contains(treePane)) {
            return treePane;
        }
        if (contentPane == null) {
            return null;
        }

        Dimension size = getSize();
        EditorPane newPane = createEditorPane(size);
        editorPanes.add(newPane);
        if (windowLayoutRoot == null) {
            windowLayoutRoot = WindowLayoutNode.leaf(contentPane);
        }
        boolean split = windowLayoutRoot.splitLeaf(contentPane, newPane, WindowLayoutNode.Orientation.HORIZONTAL, true, 0.24);
        if (!split) {
            windowLayoutRoot.splitLeaf(contentPane, newPane, WindowLayoutNode.Orientation.HORIZONTAL);
        }
        renderWindowLayout();
        treePane = newPane;
        return newPane;
    }

    private boolean isTreePaneActive() {
        EditorPane active = getActivePane();
        return active != null && active == treePane && isTreeBuffer(getCurrentBuffer());
    }

    private boolean isTreeBuffer(FileBuffer buffer) {
        return buffer != null && treeLineTargets.containsKey(buffer);
    }

    private String openTreeSelection() {
        FileBuffer current = getCurrentBuffer();
        if (!isTreeBuffer(current)) {
            return "Tree pane not active";
        }

        List<String> targets = treeLineTargets.get(current);
        if (targets == null || targets.isEmpty()) {
            return "No file on this line";
        }

        int line = getCurrentCaretLine();
        if (line < 0 || line >= targets.size()) {
            return "No file on this line";
        }
        String path = targets.get(line);
        if (path == null || path.isBlank()) {
            return "No file on this line";
        }

        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return "File not found: " + path;
        }

        EditorPane contentPane = resolveTreeContentPaneForOpen();
        if (contentPane == null) {
            return "No content pane available";
        }

        try {
            FileBuffer existing = findBufferByPath(file);
            FileBuffer targetBuffer = existing != null ? existing : new FileBuffer(file, configManager);
            if (existing == null) {
                if (shouldReplaceSingleLandingBuffer()) {
                    buffers.set(0, targetBuffer);
                } else {
                    buffers.add(targetBuffer);
                }
            }

            loadBufferIntoPane(contentPane, targetBuffer, 0);
            activateEditorPane(contentPane);
            contentPane.getTextArea().requestFocusInWindow();
            addToRecentFiles(file.getAbsolutePath());
            return "Opened: " + file.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening file: " + e.getMessage();
        }
    }

    private EditorPane resolveTreeContentPaneForOpen() {
        if (treePane != null && editorPanes.contains(treePane)) {
            for (EditorPane pane : editorPanes) {
                if (pane != treePane) {
                    return pane;
                }
            }
        }
        return getActivePane();
    }

    public String handleGitCommand(String argument) {
        File gitRoot = resolveGitRoot();
        if (gitRoot == null) {
            return "Not inside a git repository";
        }

        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            return showGitStatus(gitRoot);
        }

        int split = trimmed.indexOf(' ');
        String subcommand = split < 0 ? trimmed : trimmed.substring(0, split).trim();
        String rest = split < 0 ? "" : trimmed.substring(split + 1).trim();
        subcommand = subcommand.toLowerCase(java.util.Locale.ROOT);

        switch (subcommand) {
            case "status":
            case "st":
                return showGitStatus(gitRoot);
            case "diff":
                return showGitDiff(gitRoot, rest);
            case "log":
                return showGitLog(gitRoot, rest);
            case "branch":
            case "branches":
                return showGitBranches(gitRoot);
            case "add":
                return runGitAdd(gitRoot, rest);
            case "restore":
            case "unstage":
                return runGitRestoreStaged(gitRoot, rest);
            case "commit":
                return runGitCommit(gitRoot, rest);
            case "help":
                return showGitHelp();
            default:
                return "Unknown git command: " + subcommand + " (use :git help)";
        }
    }

    private File resolveGitRoot() {
        CommandResult result = runCommand(new File("."), List.of("git", "rev-parse", "--show-toplevel"));
        if (result.exitCode != 0) {
            return null;
        }
        String path = result.stdout.strip();
        if (path.isEmpty()) {
            return null;
        }
        File root = new File(path);
        return root.exists() ? root : null;
    }

    private String showGitStatus(File gitRoot) {
        CommandResult result = runCommand(gitRoot, List.of("git", "status", "--short", "--branch"));
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "(clean working tree)";
        }
        showScratchBuffer("[git status]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n");
        return "Showing git status";
    }

    private String showGitDiff(File gitRoot, String args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("diff");
        command.addAll(splitWhitespaceArgs(args));
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "(no diff)";
        }
        showScratchBuffer("[git diff]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n");
        return "Showing git diff";
    }

    private String showGitLog(File gitRoot, String args) {
        int count = 20;
        if (args != null && !args.isBlank()) {
            try {
                count = Math.max(1, Math.min(200, Integer.parseInt(args.trim())));
            } catch (NumberFormatException e) {
                return "Usage: :git log [count]";
            }
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("log");
        command.add("--oneline");
        command.add("--decorate");
        command.add("--graph");
        command.add("-n");
        command.add(String.valueOf(count));
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "(no commits)";
        }
        showScratchBuffer("[git log]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n");
        return "Showing git log";
    }

    private String showGitBranches(File gitRoot) {
        CommandResult result = runCommand(gitRoot, List.of("git", "branch", "--all", "--verbose"));
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "(no branches)";
        }
        showScratchBuffer("[git branch]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n");
        return "Showing git branches";
    }

    private String runGitAdd(File gitRoot, String args) {
        List<String> pathSpecs = splitWhitespaceArgs(args);
        if (pathSpecs.isEmpty()) {
            return "Usage: :git add <pathspec...>";
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("add");
        command.add("--");
        command.addAll(pathSpecs);
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        return "git add complete";
    }

    private String runGitRestoreStaged(File gitRoot, String args) {
        List<String> pathSpecs = splitWhitespaceArgs(args);
        if (pathSpecs.isEmpty()) {
            return "Usage: :git restore <pathspec...>";
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("restore");
        command.add("--staged");
        command.add("--");
        command.addAll(pathSpecs);
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        return "git restore --staged complete";
    }

    private String runGitCommit(File gitRoot, String message) {
        if (message == null || message.isBlank()) {
            return "Usage: :git commit <message>";
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("commit");
        command.add("-m");
        command.add(message.trim());
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "commit created";
        }
        showScratchBuffer("[git commit]", body + "\n");
        return "Commit complete";
    }

    private String showGitHelp() {
        showScratchBuffer("[git help]",
            "Git commands\n\n"
                + ":git                  Show status\n"
                + ":git status|st        Show status\n"
                + ":git diff [args]      Show diff\n"
                + ":git log [count]      Show compact history\n"
                + ":git branch           Show branch list\n"
                + ":git add <paths...>   Stage paths\n"
                + ":git restore <paths>  Unstage paths\n"
                + ":git commit <msg>     Commit staged changes\n");
        return "Showing git help";
    }

    private List<String> splitWhitespaceArgs(String args) {
        List<String> tokens = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return tokens;
        }
        String[] parts = args.trim().split("\\s+");
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private CommandResult runCommand(File workingDirectory, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory == null ? new File(".") : workingDirectory);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, stdout, "");
        } catch (Exception e) {
            return new CommandResult(-1, "", e.getMessage());
        }
    }

    private String gitError(CommandResult result) {
        String message = result.stderr == null ? "" : result.stderr.strip();
        if (message.isEmpty()) {
            message = result.stdout == null ? "" : result.stdout.strip();
        }
        if (message.isEmpty()) {
            message = "git command failed (exit " + result.exitCode + ")";
        }
        return "Git error: " + message;
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
        List<String> lines = registerManager.getDisplayLines();
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
        Color editorBackground = getModeBackground(currentMode == null ? EditorMode.NORMAL : currentMode);
        Color marginBackground = zenModeEnabled ? fadedMarginColor(editorBackground) : editorBackground;
        editorHostPanel.setBackground(marginBackground);
        editorHostPanel.setOpaque(true);

        for (EditorPane pane : editorPanes) {
            JScrollPane scrollPane = pane.getScrollPane();
            JTextArea area = pane.getTextArea();
            area.setBackground(editorBackground);
            scrollPane.setOpaque(true);
            scrollPane.getViewport().setOpaque(true);
            scrollPane.setBackground(marginBackground);
            scrollPane.getViewport().setBackground(editorBackground);
            if (!zenModeEnabled) {
                scrollPane.setBorder(null);
                continue;
            }
            int width = getWidth();
            int desired = configManager.getZenModeWidth() * Math.max(8, area.getFontMetrics(area.getFont()).charWidth('M'));
            int horizontalPadding = Math.max(12, (width - desired) / 2);
            scrollPane.setBorder(BorderFactory.createEmptyBorder(0, horizontalPadding, 0, horizontalPadding));
        }
        editorHostPanel.revalidate();
        editorHostPanel.repaint();
    }

    private Color fadedMarginColor(Color base) {
        return blendColor(base, configManager.getEditorForeground(), 0.12);
    }

    private Color blendColor(Color base, Color overlay, double ratio) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        int r = (int) Math.round(base.getRed() * (1.0 - clamped) + overlay.getRed() * clamped);
        int g = (int) Math.round(base.getGreen() * (1.0 - clamped) + overlay.getGreen() * clamped);
        int b = (int) Math.round(base.getBlue() * (1.0 - clamped) + overlay.getBlue() * clamped);
        return new Color(r, g, b);
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
        FileBuffer buffer = getCurrentBuffer();
        String prefix = currentCompletionPrefix();
        List<String> completions = new ArrayList<>();
        LspClient client = resolveLspClient(buffer);
        String fallbackReason = null;
        if (buffer != null && client != null && buffer.hasFilePath()) {
            String uri = bufferUri(buffer);
            try {
                int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
                int column = writingArea.getCaretPosition() - writingArea.getLineStartOffset(line);
                List<LspClient.CompletionItem> items = client.completion(uri, line, column);
                for (LspClient.CompletionItem item : items) {
                    if (item.getLabel() != null && !item.getLabel().isEmpty()) {
                        completions.add(item.getLabel());
                    }
                }
            } catch (BadLocationException ignored) {
            }
        } else if (buffer != null) {
            String extension = bufferExtension(buffer);
            fallbackReason = lspErrors.get(extension);
        }

        if (completions.isEmpty()) {
            if (prefix.isEmpty()) {
                return fallbackReason == null ? "No completion prefix" : "LSP unavailable: " + fallbackReason;
            }
            completions = collectBufferCompletions(prefix);
        }
        if (completions.isEmpty()) {
            return fallbackReason == null ? "No completions" : "LSP unavailable: " + fallbackReason + "; no local completions";
        }
        String selection = showPaletteDialog("Completions", completions);
        if (selection == null || selection.isEmpty()) {
            return "Completion cancelled";
        }
        applyCompletion(prefix, selection);
        return fallbackReason == null ? "Inserted completion" : "Inserted completion (local fallback; LSP unavailable: " + fallbackReason + ")";
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

    private void applyCompletion(String prefix, String completion) {
        int caret = writingArea.getCaretPosition();
        int start = Math.max(0, caret - (prefix == null ? 0 : prefix.length()));
        writingArea.replaceRange(completion, start, caret);
        writingArea.setCaretPosition(start + completion.length());
        markModified();
    }

    private LspClient resolveLspClient(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return null;
        }
        String extension = bufferExtension(buffer);
        if (extension.isEmpty()) {
            lspErrors.put("", "file has no recognized extension");
            return null;
        }

        LspClient existing = lspClients.get(extension);
        if (existing != null && existing.isAlive()) {
            lspErrors.remove(extension);
            return existing;
        }

        String command = configManager.getLspCommand(extension);
        String[] args = configManager.getLspArgs(extension);
        if (command == null || command.isBlank()) {
            String[] builtin = builtinLspCommand(extension);
            if (builtin == null) {
                lspErrors.put(extension, "no server configured for ." + extension);
                return null;
            }
            command = builtin[0];
            args = java.util.Arrays.copyOfRange(builtin, 1, builtin.length);
        }

        try {
            LspClient client = new LspClient(command, args, new File(".").toPath());
            lspClients.put(extension, client);
            lspErrors.remove(extension);
            return client;
        } catch (IOException e) {
            lspErrors.put(extension, e.getMessage());
            return null;
        }
    }

    private void syncLspOpen(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return;
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return;
        }
        String uri = bufferUri(buffer);
        if (lspDocumentVersions.containsKey(uri)) {
            return;
        }
        client.didOpen(uri, languageId(buffer), buffer.getFullContent());
        lspDocumentVersions.put(uri, 1);
    }

    private void syncLspChange(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return;
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return;
        }
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        int version = lspDocumentVersions.getOrDefault(uri, 1) + 1;
        lspDocumentVersions.put(uri, version);
        client.didChange(uri, version, buffer.getFullContent());
    }

    public void notifyCurrentBufferSaved() {
        FileBuffer buffer = getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return;
        }
        syncLspOpen(buffer);
        LspClient client = resolveLspClient(buffer);
        if (client != null) {
            client.didSave(bufferUri(buffer));
        }
    }

    private void pollLspNotifications(FileBuffer buffer) {
        LspClient client = existingLspClient(buffer);
        if (client != null) {
            client.drainNotifications();
        }
    }

    private LspClient existingLspClient(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return null;
        }
        return lspClients.get(bufferExtension(buffer));
    }

    private String bufferUri(FileBuffer buffer) {
        return "file://" + new File(buffer.getFilePath()).getAbsolutePath();
    }

    private String languageId(FileBuffer buffer) {
        switch (buffer.getFileType()) {
            case RUST:
                return "rust";
            case PYTHON:
                return "python";
            case JAVASCRIPT:
                return "javascript";
            case TYPESCRIPT:
                return "typescript";
            case GO:
                return "go";
            case C:
            case CPP:
                return "c";
            case JAVA:
                return "java";
            case HTML:
                return "html";
            case CSS:
                return "css";
            case JSON:
                return "json";
            case MARKDOWN:
                return "markdown";
            default:
                return "text";
        }
    }

    private String bufferExtension(FileBuffer buffer) {
        String path = buffer.getFilePath();
        if (path == null) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot >= path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase();
    }

    private String[] builtinLspCommand(String extension) {
        switch (extension) {
            case "rs":
                return new String[] {"rust-analyzer"};
            case "py":
                return new String[] {"pyright-langserver", "--stdio"};
            case "js":
            case "jsx":
            case "ts":
            case "tsx":
                return new String[] {"typescript-language-server", "--stdio"};
            case "go":
                return new String[] {"gopls"};
            case "c":
            case "cpp":
            case "h":
            case "hpp":
                return new String[] {"clangd"};
            default:
                return null;
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

    private int consumePendingCount() {
        if (pendingCount == null || pendingCount.isEmpty()) {
            return 1;
        }
        int count = Integer.parseInt(pendingCount);
        pendingCount = "";
        return Math.max(1, count);
    }

    private void repeatAction(int count, Runnable action) {
        for (int i = 0; i < Math.max(1, count); i++) {
            action.run();
        }
    }

    private Character consumePendingRegister() {
        Character register = pendingRegister;
        pendingRegister = null;
        return register;
    }

    private void storeYank(Character register, String text, boolean lineWise) {
        registerManager.setYank(register, lineWise ? RegisterContent.lineWise(text) : RegisterContent.characterWise(text));
    }

    private void storeDelete(Character register, String text, boolean lineWise) {
        registerManager.setDelete(register, lineWise ? RegisterContent.lineWise(text) : RegisterContent.characterWise(text));
    }

    private String pasteFromRegister(boolean before) {
        RegisterContent content = registerManager.get(consumePendingRegister());
        if (content == null || content.getText().isEmpty()) {
            return "Register empty";
        }
        clipboardManager.pasteContent(writingArea, content.getText(), content.isLineWise(), before);
        markModified();
        return "Pasted";
    }

    private String playMacro(Character register) {
        if (register == null) {
            return "No previously executed macro";
        }
        RegisterContent content = registerManager.get(register);
        if (content == null || !content.isMacro()) {
            return "Register @" + register + " is empty or not a macro";
        }
        if (macroPlaybackDepth >= 20) {
            return "Macro recursion limit reached";
        }

        macroPlaybackDepth++;
        try {
            lastMacroRegister = register;
            for (NormalizedKeyStroke keyStroke : content.getMacroKeys()) {
                keyPressed(keyStroke.toKeyEvent(writingArea));
            }
        } finally {
            macroPlaybackDepth--;
        }
        return "Executed macro @" + register;
    }

    private String yankToEndOfLine() {
        try {
            int start = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(start);
            int end = writingArea.getLineEndOffset(line);
            String text = writingArea.getText().substring(start, end);
            storeYank(consumePendingRegister(), text, false);
            return "Yanked " + text.length() + " characters";
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String replaceCharacter(char replacement) {
        int caret = writingArea.getCaretPosition();
        String text = writingArea.getText();
        if (caret >= text.length()) {
            return "No character to replace";
        }
        writingArea.replaceRange(String.valueOf(replacement), caret, caret + 1);
        markModified();
        return "Replaced character";
    }

    private String applyMotionOperator(char operator, String motion) {
        MotionRange range = resolveMotionRange(motion);
        return applyResolvedRange(operator, range, motion);
    }

    private String applyTextObjectOperator(char operator, char modifier, char objectKey) {
        MotionRange range = resolveTextObjectRange(modifier, objectKey);
        return applyResolvedRange(operator, range, String.valueOf(modifier) + objectKey);
    }

    private String applyResolvedRange(char operator, MotionRange range, String label) {
        if (range == null || range.start == range.end) {
            return "Unsupported target: " + label;
        }

        String selected = writingArea.getText().substring(range.start, Math.min(range.end, writingArea.getText().length()));
        switch (operator) {
            case 'y':
                storeYank(consumePendingRegister(), selected, range.lineWise);
                lastCommand = "y" + label;
                return "Yanked " + selected.length() + " characters";
            case 'd':
                storeDelete(consumePendingRegister(), selected, range.lineWise);
                writingArea.replaceRange("", range.start, range.end);
                writingArea.setCaretPosition(Math.min(range.start, writingArea.getText().length()));
                lastCommand = "d" + label;
                markModified();
                return "Deleted " + selected.length() + " characters";
            case 'c':
                storeDelete(consumePendingRegister(), selected, range.lineWise);
                writingArea.replaceRange("", range.start, range.end);
                writingArea.setCaretPosition(Math.min(range.start, writingArea.getText().length()));
                lastInsertedText = "";
                lastCommand = "c" + label;
                markModified();
                setMode(EditorMode.INSERT);
                return "Changed " + selected.length() + " characters";
            default:
                return "Unsupported operator";
        }
    }

    private MotionRange resolveMotionRange(String motion) {
        try {
            int original = writingArea.getCaretPosition();
            if ("gg".equals(motion) || "G".equals(motion)) {
                int originalLine = writingArea.getLineOfOffset(original);
                if ("gg".equals(motion)) {
                    moveFileStart();
                } else {
                    moveFileEnd();
                }
                int targetLine = writingArea.getLineOfOffset(writingArea.getCaretPosition());
                writingArea.setCaretPosition(original);
                int startLine = Math.min(originalLine, targetLine);
                int endLine = Math.max(originalLine, targetLine);
                int start = writingArea.getLineStartOffset(startLine);
                int end = writingArea.getLineEndOffset(endLine);
                return new MotionRange(start, end, true);
            }

            int target = previewMotionTarget(motion);
            if (target < 0) {
                return null;
            }

            boolean inclusive = "e".equals(motion) || "E".equals(motion) || "ge".equals(motion) || "gE".equals(motion) || "$".equals(motion) || "g$".equals(motion) || "l".equals(motion);
            int start = Math.min(original, target);
            int end = Math.max(original, target);
            if (inclusive) {
                end = Math.min(end + 1, writingArea.getText().length());
            }
            return new MotionRange(start, end, false);
        } catch (BadLocationException e) {
            return null;
        }
    }

    private MotionRange resolveTextObjectRange(char modifier, char objectKey) {
        switch (objectKey) {
            case 'w':
            case 'W':
                return resolveWordObject(modifier == 'a', objectKey == 'W');
            case 'p':
                return resolveParagraphObject(modifier == 'a');
            case 's':
                return resolveSentenceObject(modifier == 'a');
            case '"':
            case '\'':
            case '`':
                return resolveQuoteObject(modifier == 'a', objectKey);
            case '(':
            case ')':
                return resolveBracketObject(modifier == 'a', '(', ')');
            case '[':
            case ']':
                return resolveBracketObject(modifier == 'a', '[', ']');
            case '{':
            case '}':
                return resolveBracketObject(modifier == 'a', '{', '}');
            case '<':
            case '>':
                return resolveBracketObject(modifier == 'a', '<', '>');
            default:
                return null;
        }
    }

    private String handleSurroundPending(char c) {
        if (pendingSurroundAction == 'c') {
            if (pendingSurroundOld == null) {
                pendingSurroundOld = c;
                return "Awaiting new surround";
            }
            String result = surroundChange(pendingSurroundOld, c);
            pendingSurroundAction = null;
            pendingSurroundOld = null;
            return result;
        }

        if (pendingSurroundAction == 'd') {
            String result = surroundDelete(c);
            pendingSurroundAction = null;
            return result;
        }

        if (pendingSurroundAction == 'y') {
            if (pendingSurroundTarget == null && isTextObjectKey(c)) {
                pendingSurroundTarget = c;
                return "Awaiting surround delimiter";
            }
            char target = pendingSurroundTarget == null ? 'w' : pendingSurroundTarget;
            String result = surroundAdd(target, c);
            pendingSurroundAction = null;
            pendingSurroundTarget = null;
            return result;
        }

        pendingSurroundAction = null;
        pendingSurroundOld = null;
        pendingSurroundTarget = null;
        return "Unsupported surround";
    }

    private boolean isTextObjectKey(char c) {
        return "wWps\"'`()[]{}<>".indexOf(c) >= 0;
    }

    private String surroundChange(char oldChar, char newChar) {
        MotionRange range = resolveSurroundRange(oldChar);
        SurroundPair newPair = surroundPair(newChar);
        if (range == null || newPair == null) {
            return "No matching surround found";
        }
        writingArea.replaceRange(String.valueOf(newPair.close), range.end - 1, range.end);
        writingArea.replaceRange(String.valueOf(newPair.open), range.start, range.start + 1);
        markModified();
        return "Surround changed";
    }

    private String surroundDelete(char target) {
        MotionRange range = resolveSurroundRange(target);
        if (range == null) {
            return "No matching surround found";
        }
        writingArea.replaceRange("", range.end - 1, range.end);
        writingArea.replaceRange("", range.start, range.start + 1);
        markModified();
        return "Surround deleted";
    }

    private String surroundAdd(char targetObject, char surroundChar) {
        MotionRange range = resolveTextObjectRange('i', targetObject);
        SurroundPair pair = surroundPair(surroundChar);
        if (range == null || pair == null) {
            return "No valid surround target";
        }
        writingArea.insert(String.valueOf(pair.close), range.end);
        writingArea.insert(String.valueOf(pair.open), range.start);
        markModified();
        return "Surround added";
    }

    private MotionRange resolveSurroundRange(char surround) {
        if (surround == '"' || surround == '\'' || surround == '`') {
            return resolveQuoteObject(true, surround);
        }
        SurroundPair pair = surroundPair(surround);
        if (pair == null) {
            return null;
        }
        return resolveBracketObject(true, pair.open, pair.close);
    }

    private SurroundPair surroundPair(char surround) {
        switch (surround) {
            case '(':
            case ')':
                return new SurroundPair('(', ')');
            case '[':
            case ']':
                return new SurroundPair('[', ']');
            case '{':
            case '}':
                return new SurroundPair('{', '}');
            case '<':
            case '>':
                return new SurroundPair('<', '>');
            case '"':
            case '\'':
            case '`':
                return new SurroundPair(surround, surround);
            default:
                return null;
        }
    }

    private MotionRange resolveWordObject(boolean around, boolean bigWord) {
        String text = writingArea.getText();
        if (text.isEmpty()) {
            return null;
        }
        int caret = Math.min(writingArea.getCaretPosition(), text.length() - 1);
        int start = caret;
        int end = caret;
        while (start > 0 && isMotionWordChar(text.charAt(start - 1), bigWord)) {
            start--;
        }
        while (end < text.length() && isMotionWordChar(text.charAt(end), bigWord)) {
            end++;
        }
        if (around) {
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
        }
        return new MotionRange(start, end, false);
    }

    private MotionRange resolveParagraphObject(boolean around) {
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
            int startLine = line;
            int endLine = line;
            while (startLine > 0 && !lineText(startLine - 1).isBlank()) {
                startLine--;
            }
            while (endLine < writingArea.getLineCount() - 1 && !lineText(endLine + 1).isBlank()) {
                endLine++;
            }
            if (around) {
                if (startLine > 0) {
                    startLine--;
                }
                if (endLine < writingArea.getLineCount() - 1) {
                    endLine++;
                }
            }
            return new MotionRange(writingArea.getLineStartOffset(startLine), writingArea.getLineEndOffset(endLine), true);
        } catch (BadLocationException e) {
            return null;
        }
    }

    private MotionRange resolveSentenceObject(boolean around) {
        String text = writingArea.getText();
        int caret = writingArea.getCaretPosition();
        int start = caret;
        int end = caret;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (c == '.' || c == '!' || c == '?') {
                break;
            }
            start--;
        }
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == '.' || c == '!' || c == '?') {
                end++;
                break;
            }
            end++;
        }
        if (around) {
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
        }
        return new MotionRange(start, end, false);
    }

    private MotionRange resolveQuoteObject(boolean around, char quote) {
        String text = writingArea.getText();
        int caret = writingArea.getCaretPosition();
        int start = text.lastIndexOf(quote, Math.max(0, caret - 1));
        int end = text.indexOf(quote, caret);
        if (start < 0 || end < 0 || start == end) {
            return null;
        }
        return around ? new MotionRange(start, end + 1, false) : new MotionRange(start + 1, end, false);
    }

    private MotionRange resolveBracketObject(boolean around, char open, char close) {
        String text = writingArea.getText();
        int caret = writingArea.getCaretPosition();
        int start = -1;
        int depth = 0;
        for (int i = Math.max(0, caret - 1); i >= 0; i--) {
            char c = text.charAt(i);
            if (c == close) {
                depth++;
            } else if (c == open) {
                if (depth == 0) {
                    start = i;
                    break;
                }
                depth--;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = -1;
        depth = 0;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                if (depth == 0) {
                    end = i;
                    break;
                }
                depth--;
            }
        }
        if (end < 0) {
            return null;
        }
        return around ? new MotionRange(start, end + 1, false) : new MotionRange(start + 1, end, false);
    }

    private int previewMotionTarget(String motion) {
        int original = writingArea.getCaretPosition();
        switch (motion) {
            case "h":
                moveLeft();
                break;
            case "l":
                moveRight();
                break;
            case "w":
                moveWordForward();
                break;
            case "b":
                moveWordBackward();
                break;
            case "e":
                moveWordEnd();
                break;
            case "W":
                moveWordForwardBig();
                break;
            case "B":
                moveWordBackwardBig();
                break;
            case "E":
                moveWordEndBig();
                break;
            case "0":
            case "g0":
                moveLineStart();
                break;
            case "^":
                moveLineFirstNonBlank();
                break;
            case "$":
            case "g$":
                moveLineEnd();
                break;
            case "g_":
                moveLineLastNonBlank();
                break;
            case "ge":
                moveWordEndBackward();
                break;
            case "gE":
                moveWordEndBackwardBig();
                break;
            default:
                return -1;
        }
        int target = writingArea.getCaretPosition();
        writingArea.setCaretPosition(original);
        return target;
    }

    // Mode management
    private void setMode(EditorMode mode) {
        this.currentMode = mode;
        writingArea.setEditable(mode.isEditable());
        writingArea.setBackground(getModeBackground(mode));
        updateZenModeLayout();
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
            pollLspNotifications(buffer);
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

        EditorMode modeForStatus = currentMode == null ? EditorMode.NORMAL : currentMode;
        status.append(modeForStatus.getDisplayName()).append("  ");

        if (buffer != null) {
            status.append(buffer.getFileType().getDisplayName()).append("  ");
            status.append(buffer.getEncoding()).append("/").append(buffer.getLineEndingLabel()).append("  ");
            appendLspStatus(status, buffer);
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

    private void appendLspStatus(StringBuilder status, FileBuffer buffer) {
        LspClient client = existingLspClient(buffer);
        if (client == null || !buffer.hasFilePath()) {
            return;
        }
        List<LspClient.Diagnostic> diagnosticEntries = client.getDiagnostics(bufferUri(buffer));
        if (!diagnosticEntries.isEmpty()) {
            status.append("diag:").append(diagnosticEntries.size()).append("  ");
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
        loadBufferIntoPane(getActivePane(), buffer, 0);
    }

    private void loadBufferIntoPane(EditorPane pane, FileBuffer buffer, int caretPosition) {
        if (pane == null || buffer == null) {
            return;
        }

        boolean activePane = pane == getActivePane();
        if (activePane) {
            detachActiveDocumentListener();
            if (searchManager != null) {
                searchManager.clearHighlights();
            }
        }

        pane.setBuffer(buffer);
        withSuppressedDocumentEvents(() -> pane.getTextArea().setDocument(buffer.getDocument()));
        pane.getTextArea().setCaretPosition(Math.min(caretPosition, pane.getTextArea().getDocument().getLength()));

        if (activePane) {
            bindActivePane(pane);
            attachActiveDocumentListener();
            undoManager = buffer.getUndoManager();
            currentBufferIndex = buffers.indexOf(buffer);
            registerManager.updateFilename(buffer.getFilePath());
            updateCurrentLineHighlight();
            applySyntaxHighlighting();
            refreshLineNumberPanel();
            maybePreviewMarkdown(buffer);
            syncLspOpen(buffer);
            updateStatusBar();
        } else {
            pane.getLineNumberPanel().repaint();
        }
    }

    private void persistCurrentBufferState() {
        EditorPane pane = getActivePane();
        if (pane != null) {
            pane.setBuffer(getCurrentBuffer());
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
            syncLspChange(buffer);
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
        } else {
            buffers.set(0, landing);
        }
        loadBufferIntoEditor(landing);
    }

    public void openFile(File file) throws IOException {
        persistCurrentBufferState();

        FileBuffer existing = findBufferByPath(file);
        if (existing != null) {
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
        } else {
            buffers.add(buffer);
        }
        loadBufferIntoEditor(buffer);
        addToRecentFiles(file.getAbsolutePath());
        if (buffer.isShowingPreviewOnly()) {
            showMessage("Large-file preview loaded");
        }
    }

    // Buffer management methods (called by CommandHandler)
    public FileBuffer getCurrentBuffer() {
        EditorPane activePane = getActivePane();
        if (activePane != null && activePane.getBuffer() != null) {
            return activePane.getBuffer();
        }
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

        currentBufferIndex = Math.max(0, buffers.indexOf(getCurrentBuffer()));
        int nextIndex = (currentBufferIndex + 1) % buffers.size();
        switchToBuffer(nextIndex);
        return "Buffer " + (currentBufferIndex + 1) + " of " + buffers.size();
    }

    public String prevBuffer() {
        if (buffers.isEmpty()) {
            return "No buffers open";
        }

        currentBufferIndex = Math.max(0, buffers.indexOf(getCurrentBuffer()));
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

        currentBufferIndex = Math.max(0, buffers.indexOf(buffer));
        if (isTreeBuffer(buffer)) {
            treeLineTargets.remove(buffer);
            if (buffer == treeBuffer) {
                treeBuffer = null;
            }
        }
        buffers.remove(buffer);
        if (buffers.isEmpty()) {
            openLandingPage();
            return "Buffer deleted";
        }
        FileBuffer replacement = buffers.get(Math.min(currentBufferIndex, buffers.size() - 1));
        for (EditorPane pane : editorPanes) {
            if (pane.getBuffer() == buffer) {
                loadBufferIntoPane(pane, replacement, 0);
            }
        }
        return "Buffer deleted";
    }

    private void switchToBuffer(int index) {
        if (index < 0 || index >= buffers.size()) {
            return;
        }

        persistCurrentBufferState();

        FileBuffer newBuffer = buffers.get(index);
        loadBufferIntoEditor(newBuffer);
    }

    public String splitWindow(boolean vertical) {
        EditorPane activePane = getActivePane();
        FileBuffer currentBuffer = getCurrentBuffer();
        if (activePane == null || currentBuffer == null) {
            return "No active window";
        }

        Dimension size = getSize();
        EditorPane newPane = createEditorPane(size);
        editorPanes.add(newPane);
        WindowLayoutNode.Orientation orientation = vertical ? WindowLayoutNode.Orientation.HORIZONTAL : WindowLayoutNode.Orientation.VERTICAL;
        if (windowLayoutRoot == null) {
            windowLayoutRoot = WindowLayoutNode.leaf(activePane);
        }
        windowLayoutRoot.splitLeaf(activePane, newPane, orientation);
        loadBufferIntoPane(newPane, currentBuffer, writingArea.getCaretPosition());
        renderWindowLayout();
        activateEditorPane(newPane);
        newPane.getTextArea().requestFocusInWindow();
        return vertical ? "Vertical split created" : "Horizontal split created";
    }

    public String closeActiveWindow() {
        if (editorPanes.size() <= 1) {
            return "Cannot close the only window";
        }

        EditorPane activePane = getActivePane();
        if (activePane == null) {
            return "No active window";
        }
        FileBuffer closingBuffer = activePane.getBuffer();
        if (activePane == treePane) {
            treePane = null;
            if (isTreeBuffer(closingBuffer)) {
                treeLineTargets.remove(closingBuffer);
                buffers.remove(closingBuffer);
                if (closingBuffer == treeBuffer) {
                    treeBuffer = null;
                }
            }
        } else if (isTreeBuffer(closingBuffer)) {
            treeLineTargets.remove(closingBuffer);
            buffers.remove(closingBuffer);
            if (closingBuffer == treeBuffer) {
                treeBuffer = null;
            }
        }

        detachActiveDocumentListener();
        editorPanes.remove(activePane);
        windowLayoutRoot = windowLayoutRoot == null ? null : windowLayoutRoot.removeLeaf(activePane);
        if (windowLayoutRoot == null) {
            windowLayoutRoot = WindowLayoutNode.leaf(editorPanes.get(0));
        }
        renderWindowLayout();
        activePaneIndex = 0;
        bindActivePane(editorPanes.get(0));
        attachActiveDocumentListener();
        updateCurrentLineHighlight();
        refreshLineNumberPanel();
        updateStatusBar();
        writingArea.requestFocusInWindow();
        return "Window closed";
    }

    public String cycleWindowFocus() {
        if (editorPanes.size() <= 1) {
            return "Only one window";
        }
        int nextIndex = (activePaneIndex + 1) % editorPanes.size();
        activateEditorPane(editorPanes.get(nextIndex));
        writingArea.requestFocusInWindow();
        return "Window focus changed";
    }

    public String equalizeWindows() {
        if (windowLayoutRoot == null) {
            return "No windows to equalize";
        }
        windowLayoutRoot.equalize();
        renderWindowLayout();
        return "Windows equalized";
    }

    public String focusWindowDirection(int dx, int dy) {
        if (editorPanes.size() <= 1) {
            return "Only one window";
        }
        EditorPane activePane = getActivePane();
        if (activePane == null) {
            return "No active window";
        }

        WindowLayoutNode.Direction direction = toLayoutDirection(dx, dy);
        List<EditorPane> candidates = windowLayoutRoot == null ? List.of() : windowLayoutRoot.findNeighborCandidates(activePane, direction);
        if (candidates.isEmpty()) {
            return "No window in that direction";
        }

        Rectangle activeBounds = paneBounds(activePane);
        EditorPane bestPane = null;
        double bestScore = Double.MAX_VALUE;

        for (EditorPane pane : candidates) {
            Rectangle candidateBounds = paneBounds(pane);
            double score = directionalAlignmentScore(activeBounds, candidateBounds, direction);
            if (score < bestScore) {
                bestScore = score;
                bestPane = pane;
            }
        }

        if (bestPane == null) {
            return "No window in that direction";
        }

        activateEditorPane(bestPane);
        writingArea.requestFocusInWindow();
        return "Window focus changed";
    }

    private WindowLayoutNode.Direction toLayoutDirection(int dx, int dy) {
        if (dx < 0) {
            return WindowLayoutNode.Direction.LEFT;
        }
        if (dx > 0) {
            return WindowLayoutNode.Direction.RIGHT;
        }
        if (dy < 0) {
            return WindowLayoutNode.Direction.UP;
        }
        return WindowLayoutNode.Direction.DOWN;
    }

    private double directionalAlignmentScore(Rectangle activeBounds, Rectangle candidateBounds, WindowLayoutNode.Direction direction) {
        double axisDistance;
        double orthogonalDistance;
        switch (direction) {
            case LEFT:
                axisDistance = activeBounds.getX() - candidateBounds.getMaxX();
                orthogonalDistance = Math.abs(activeBounds.getCenterY() - candidateBounds.getCenterY());
                break;
            case RIGHT:
                axisDistance = candidateBounds.getX() - activeBounds.getMaxX();
                orthogonalDistance = Math.abs(activeBounds.getCenterY() - candidateBounds.getCenterY());
                break;
            case UP:
                axisDistance = activeBounds.getY() - candidateBounds.getMaxY();
                orthogonalDistance = Math.abs(activeBounds.getCenterX() - candidateBounds.getCenterX());
                break;
            case DOWN:
            default:
                axisDistance = candidateBounds.getY() - activeBounds.getMaxY();
                orthogonalDistance = Math.abs(activeBounds.getCenterX() - candidateBounds.getCenterX());
                break;
        }
        if (axisDistance < 0) {
            axisDistance = 0;
        }
        return axisDistance * 1000.0 + orthogonalDistance;
    }

    private Rectangle paneBounds(EditorPane pane) {
        return SwingUtilities.convertRectangle(
            pane.getScrollPane().getParent(),
            pane.getScrollPane().getBounds(),
            editorHostPanel
        );
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

    public String getCurrentThemeName() {
        return configManager.getThemeId();
    }

    public String setThemeFromCommand(String value) {
        String appliedTheme = configManager.setTheme(value);
        if (appliedTheme == null) {
            return "Unknown theme: " + value;
        }
        applyThemeColors();
        return "Theme set to " + appliedTheme;
    }

    public String showThemes() {
        showScratchBuffer("[themes]", configManager.getThemeListText());
        return "Showing themes";
    }

    public String openSettingsBuffer() {
        File settingsFile = new File(configManager.getConfigPath());
        try {
            ensureSettingsFileSeeded(settingsFile);
            openFile(settingsFile);
            return "Opened settings: " + settingsFile.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening settings: " + e.getMessage();
        }
    }

    public String openCommandLogBuffer() {
        try {
            File parent = commandLogStore.getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            if (!commandLogStore.exists()) {
                Files.write(commandLogStore.toPath(),
                    new byte[0],
                    StandardOpenOption.CREATE);
            }
            openFile(commandLogStore);
            return "Opened command log: " + commandLogStore.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening command log: " + e.getMessage();
        }
    }

    private void ensureSettingsFileSeeded(File settingsFile) throws IOException {
        if (settingsFile == null) {
            return;
        }
        File parent = settingsFile.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        if (!settingsFile.exists() || settingsFile.length() == 0L) {
            Files.write(settingsFile.toPath(),
                configManager.defaultConfigTemplate().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        }
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
        String normalizedTopic = topic == null ? "" : topic.trim().toLowerCase();
        if (normalizedTopic.isEmpty()) {
            return "Shed v" + VERSION + "\n\n" +
                   "NORMAL MODE\n" +
                   "  h/j/k/l        Move left/down/up/right\n" +
                   "  w/b/e          Move by word\n" +
                   "  W/B/E ge/gE    WORD and backward-end motions\n" +
                   "  f/F/t/T ; ,    Find/till-char and repeat\n" +
                   "  0/^/$ g0/g$ g_ Line start/indent/end variants\n" +
                   "  gg/G 50%       File start/end and percent jump\n" +
                   "  { } ( ) H M L  Paragraph, sentence, screen motions\n" +
                   "  zt/zz/zb       Scroll current line to top/center/bottom\n" +
                   "  i/a/A/I/o/O    Insert variants\n" +
                   "  v/V/R          Visual/visual-line/replace\n" +
                   "  yy/dd/cc       Yank/delete/change line\n" +
                   "  dw/cw diw ci\"  Motion and text-object operators\n" +
                   "  D/C/Y r{char}  End-of-line yank/delete/change and replace-char\n" +
                   "  >>/<</==       Indent/dedent/auto-indent line\n" +
                   "  J/gJ           Join lines with/without space\n" +
                   "  cs/ds/ys       Surround change/delete/add\n" +
                   "  q{a-z} @a @@   Macro record and playback\n" +
                   "  m{a-z}         Set mark\n" +
                   "  '{a-z}/`{a-z}  Jump to mark\n" +
                   "  Ctrl-o/Ctrl-i  Jump back/forward\n" +
                   "  g;/g,          Previous/next change\n" +
                   "  \"ap \"+p      Register-targeted edit and paste\n" +
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
                   "  :settings      Open user settings file\n" +
                   "  :log           Open command log file\n" +
                   "  :files         File finder\n" +
                   "  :folder        Folder finder\n" +
                   "  :tree [path]   File tree in scratch buffer\n" +
                   "  :buffers       Buffer finder\n" +
                   "  :grep text     Grep finder\n" +
                   "  :git ...       Git status/diff/log/add/commit\n" +
                   "  :split/:vsplit Split the active window\n" +
                   "  Ctrl-w s/v/c   Split/vertical-split/close window\n" +
                   "  Ctrl-w h/j/k/l Move window focus\n" +
                   "  :registers     Show registers\n" +
                   "  :marks         Show marks\n" +
                   "  :themes        Show built-in themes\n" +
                   "  :goyo          Toggle zen mode\n" +
                   "  :normal keys   Replay normal keys\n" +
                   "  :!cmd          Run shell command\n" +
                   "  :set nu        Enable line numbers\n" +
                   "  :set theme=x   Switch color theme\n" +
                   "  :45            Go to line 45\n" +
                   "  :1,5d          Delete a line range\n" +
                   "  :s/a/b         Substitute current line\n" +
                   "  :1,5s/a/b/g    Substitute a range\n" +
                   "  :%s/a/b/g      Substitute whole buffer\n\n" +
                   "note: this is a help buffer. use :q to return.\n";
        }

        switch (normalizedTopic) {
            case "windows":
            case "split":
            case "vsplit":
                return "Help: windows\n\n"
                    + ":split / :sp creates a horizontal split.\n"
                    + ":vsplit / :vsp creates a vertical split.\n"
                    + ":close closes the active split when more than one window exists.\n"
                    + "Ctrl-w s/v/c mirrors the split commands.\n"
                    + "Ctrl-w h/j/k/l changes window focus.\n"
                    + "Ctrl-w w cycles focus and Ctrl-w = equalizes split ratios.\n";
            case "registers":
            case "reg":
                return "Help: registers\n\n"
                    + "Use \"{register} before yank/delete/change/paste.\n"
                    + "Supported special registers: \", 0, %, :, ., +, *, _.\n"
                    + "Named registers a-z and A-Z are also supported.\n"
                    + ":registers opens a scratch buffer with current register contents.\n";
            case "macros":
            case "macro":
                return "Help: macros\n\n"
                    + "q{register} starts recording into a named register.\n"
                    + "q stops recording.\n"
                    + "@{register} replays a macro and @@ replays the last executed macro.\n"
                    + "Macro playback is recursion-limited to avoid runaway loops.\n";
            case "textobjects":
            case "text-objects":
            case "objects":
                return "Help: text objects\n\n"
                    + "Supported forms include iw/aw, iW/aW, ip/ap, is/as,\n"
                    + "quoted objects for \", ', ` and bracket objects for () [] {} <>.\n"
                    + "Use them with d/c/y, for example diw, ci\", ya(, or dap.\n";
            case "surround":
                return "Help: surround\n\n"
                    + "cs{old}{new} changes an existing surround pair.\n"
                    + "ds{char} removes a surround pair.\n"
                    + "ys{object}{char} adds a surround around a supported text object.\n"
                    + "Examples: cs\"', ds), ysw].\n";
            case "lsp":
            case "completion":
                return "Help: completion\n\n"
                    + "Ctrl-n requests completion from an external language server for file-backed buffers.\n"
                    + "If no server is available, Shed falls back to local buffer-word completion.\n"
                    + "Configure overrides in ~/.shedrc using lsp.<ext>.command and lsp.<ext>.args.\n";
            case "git":
                return "Help: git\n\n"
                    + ":git shows status.\n"
                    + ":git diff [args], :git log [count], :git branch show repository state.\n"
                    + ":git add <paths...>, :git restore <paths...>, :git commit <message> modify staging/commits.\n";
            case "tree":
                return "Help: tree\n\n"
                    + ":tree opens/updates a left side tree pane.\n"
                    + ":tree <path> uses a specific root path.\n"
                    + "Use j/k to move and Enter or o to open the file in the other pane.\n";
            default:
                return "Shed help: " + topic + "\n\n"
                    + "No dedicated topic entry exists yet for this help topic.\n"
                    + "Use :help for the full command reference.\n";
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
        buffers.remove(current);

        int returnIndex = buffers.indexOf(state.returnBuffer);
        if (returnIndex < 0) {
            if (buffers.isEmpty()) {
                openLandingPage();
                return true;
            }
            returnIndex = 0;
        }

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

    private void appendCommandLog(String entry) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        String line = commandLogTimeFormat.format(LocalDateTime.now()) + " " + entry.strip() + "\n";
        try {
            Files.write(commandLogStore.toPath(),
                line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
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

    public void rememberExCommand(String command) {
        registerManager.updateLastCommand(command);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        if (suppressNextTypedChar) {
            suppressNextTypedChar = false;
            e.consume();
            return;
        }
        if (currentMode != EditorMode.INSERT) {
            e.consume();
        }
    }

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

    private static class MotionRange {
        private final int start;
        private final int end;
        private final boolean lineWise;

        private MotionRange(int start, int end, boolean lineWise) {
            this.start = Math.max(0, start);
            this.end = Math.max(this.start, end);
            this.lineWise = lineWise;
        }
    }

    private static class SurroundPair {
        private final char open;
        private final char close;

        private SurroundPair(char open, char close) {
            this.open = open;
            this.close = close;
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

    private static class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }

    // Main method
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Texteditor(args));
    }
}
