package shed;

// SHit EDitor (Shed) Version 2.0 <Refactored Build>

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;
import javax.swing.border.Border;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

public class Texteditor extends JFrame implements KeyListener {
    static final long serialVersionUID = 1L;

    // Core components
    EditorState editorState;
    ModeEngine modeEngine;
    JTextArea writingArea;
    JLabel statusBar;
    JLabel commandBar;
    LineNumberPanel lineNumberPanel;
    JScrollPane editorScrollPane;
    JPanel editorHostPanel;

    // Managers
    ClipboardManager clipboardManager;
    RegisterManager registerManager;
    SearchManager searchManager;
    CommandHandler commandHandler;
    ConfigManager configManager;
    HelpService helpService;
    GitService gitService;
    TreeService treeService;
    LspService lspService;
    SyntaxHighlightService syntaxHighlightService;
    AsyncJobService asyncJobService;
    QuickfixService quickfixService;
    PluginManager pluginManager;
    TreeGitController treeGitController;
    LspController lspController;
    JobQuickfixController jobQuickfixController;
    TerminalController terminalController;
    MarkdownController markdownController;
    PaneBufferController paneBufferController;

    // Buffer management
    List<FileBuffer> buffers;
    int currentBufferIndex;
    List<EditorPane> editorPanes;
    int activePaneIndex;
    WindowLayoutNode windowLayoutRoot;
    Component renderedLayoutComponent;

    // State variables
    String lastMessage;
    Timer messageResetTimer;
    UndoManager undoManager;
    DocumentListener bufferDocumentListener;
    String lastCommand;
    boolean suppressDocumentEvents;
    boolean suppressNextTypedChar;
    boolean closingDown;
    List<String> recentFiles;
    File recentFilesStore;
    File commandLogStore;
    File recoveryStoreDir;
    File trustedProjectsStore;
    Set<String> trustedProjectRoots;
    Deque<SpecialBufferReturnState> specialBufferReturns;
    List<String> commandHistory;
    int commandHistoryIndex;
    String commandHistoryPrefix;
    DateTimeFormatter commandLogTimeFormat;
    LineNumberMode lineNumberMode;
    String gitBranch;
    Object currentLineHighlightTag;
    List<Object> substitutePreviewTags;
    Highlighter.HighlightPainter currentLinePainter;
    Highlighter.HighlightPainter substitutePreviewPainter;
    boolean zenModeEnabled;
    String lastInsertedText;
    Timer externalChangeTimer;
    Timer recoverySnapshotTimer;
    boolean reloadPromptActive;
    List<Object> syntaxHighlightTags;
    List<SyntaxSpan> syntaxForegroundSpans;
    Color syntaxKeywordColor;
    Color syntaxStringColor;
    Color syntaxCommentColor;
    Color syntaxNumberColor;
    File lastPreviewedMarkdown;
    List<Integer> jumpList;
    int jumpIndex;
    List<Integer> changeList;
    int changeIndex;
    char lastFindChar;
    char lastFindType;
    Character recordingRegister;
    Character lastMacroRegister;
    List<NormalizedKeyStroke> macroBuffer;
    int macroPlaybackDepth;
    Character pendingTextObjectOperator;
    Character pendingTextObjectModifier;
    Character pendingSurroundAction;
    Character pendingSurroundOld;
    Character pendingSurroundTarget;
    boolean insertNormalOneShot;
    final List<Integer> extraCursors = new ArrayList<>();
    Map<String, LspClient> lspClients;
    Map<String, Integer> lspDocumentVersions;
    Map<String, String> lspErrors;
    List<LspClient.TextEdit> pendingLspRenameEdits;
    String pendingLspRenameTarget;
    EditorPane treePane;
    FileBuffer treeBuffer;
    File treeRoot;
    Map<FileBuffer, List<String>> treeLineTargets;
    FileBuffer quickfixBuffer;
    int keymapReplayDepth;
    List<RegisterContent> yankRing;
    Map<FileBuffer, TerminalSession> terminalSessions;
    Map<FileBuffer, PtyTerminalPane> ptyTerminalPanes;
    int terminalBufferCounter;

    // Markdown / orgmode features
    MarkdownService markdownService;
    FuzzyMatchService fuzzyMatchService;
    SnippetService snippetService;
    BracketColorService bracketColorService;
    SymbolService symbolService;
    TaskService taskService;
    FileWatcherService fileWatcherService;
    SubstituteService substituteService;
    List<int[]> diagnosticRanges = new ArrayList<>(); // [startOffset, endOffset, severity]
    javax.swing.Timer diagnosticRefreshTimer;
    Map<Integer, Boolean> foldedLines; // headingLine -> folded
    String foldedContent; // stores hidden content per fold
    Map<Integer, String> foldHiddenContent; // headingLine -> hidden text
    int concealLevel; // 0=show all, 1=conceal some, 2=full conceal
    List<Object> bracketHighlightTags;
    final List<Object> matchBracketTags = new ArrayList<>();
    List<Object> markdownHighlightTags;
    boolean bracketColorEnabled;
    // Dramatic UI runtime settings
    boolean dramaticUiEnabled;
    boolean dramaticIdentityEnabled;
    boolean dramaticModeTransitionsEnabled;
    boolean dramaticCommandPaletteEnabled;
    boolean dramaticEditingFeedbackEnabled;
    boolean dramaticPanelAnimationsEnabled;
    boolean dramaticSoundEnabled;
    String dramaticSoundPack;
    int dramaticSoundVolume;
    boolean dramaticSoundModeCueEnabled;
    boolean dramaticSoundNavigateCueEnabled;
    boolean dramaticSoundSuccessCueEnabled;
    boolean dramaticSoundErrorCueEnabled;
    boolean dramaticReducedMotionEnabled;
    boolean dramaticPerformanceGuardrailsEnabled;
    double dramaticPerformanceCpuThreshold;
    int dramaticPerformanceLineThreshold;
    boolean whichKeyHintsEnabled;
    double cachedProcessCpuLoad;
    long cachedProcessCpuLoadAtMillis;
    int dramaticAnimationMs;
    int dramaticMinimapWidth;
    Timer modeTransitionTimer;
    Timer feedbackPulseTimer;
    Object feedbackPulseTag;
    Timer hostTintTimer;
    Timer splitAnimationTimer;
    Timer minimapWidthTimer;
    Timer paneJumpFlashTimer;
    EditorPane paneJumpFlashTarget;
    Border paneJumpFlashOriginalBorder;

    // Constants
    static final String VERSION = "2.0";
    static final Pattern QUICKFIX_PATTERN = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:(.*)$");
    static final Pattern HEX_COLOR_VALUE_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{3})?$");
    static final String WORKSPACE_PROFILE_PREFIX = "workspace-";

    // Constructor
    public Texteditor(String[] args) {
        // Initialize managers
        configManager = new ConfigManager();
        helpService = new HelpService();
        gitService = new GitService();
        treeService = new TreeService();
        treeGitController = new TreeGitController(this);
        lspService = new LspService();
        lspController = new LspController(this);
        syntaxHighlightService = new SyntaxHighlightService();
        asyncJobService = new AsyncJobService();
        quickfixService = new QuickfixService();
        jobQuickfixController = new JobQuickfixController(this);
        editorState = new EditorState();
        modeEngine = new ModeEngine();
        buffers = new ArrayList<>();
        currentBufferIndex = -1;
        editorPanes = new ArrayList<>();
        activePaneIndex = -1;
        lastCommand = "";
        suppressDocumentEvents = false;
        suppressNextTypedChar = false;
        closingDown = false;
        recentFiles = new ArrayList<>();
        File shedDirectory = new File(configManager.getShedDirectoryPath());
        if (!shedDirectory.exists()) {
            shedDirectory.mkdirs();
        }
        recentFilesStore = new File(shedDirectory, "recent");
        commandLogStore = new File(shedDirectory, "command.log");
        recoveryStoreDir = new File(shedDirectory, "recovery");
        trustedProjectsStore = new File(shedDirectory, "trusted-projects");
        trustedProjectRoots = new HashSet<>();
        specialBufferReturns = new ArrayDeque<>();
        commandHistory = new ArrayList<>();
        commandHistoryIndex = -1;
        commandHistoryPrefix = "";
        commandLogTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        lineNumberMode = configManager.getLineNumberMode();
        gitBranch = resolveGitBranch();
        substitutePreviewTags = new ArrayList<>();
        currentLinePainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getCurrentLineHighlightColor());
        substitutePreviewPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSubstitutePreviewColor());
        zenModeEnabled = false;
        lastInsertedText = "";
        reloadPromptActive = false;
        recoverySnapshotTimer = null;
        syntaxHighlightTags = new ArrayList<>();
        syntaxForegroundSpans = new ArrayList<>();
        syntaxKeywordColor = configManager.getSyntaxKeywordColor();
        syntaxStringColor = configManager.getSyntaxStringColor();
        syntaxCommentColor = configManager.getSyntaxCommentColor();
        syntaxNumberColor = configManager.getSyntaxNumberColor();
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
        insertNormalOneShot = false;
        lspClients = new HashMap<>();
        lspDocumentVersions = new HashMap<>();
        lspErrors = new HashMap<>();
        pendingLspRenameEdits = null;
        pendingLspRenameTarget = null;
        keymapReplayDepth = 0;
        yankRing = new ArrayList<>();
        terminalSessions = new HashMap<>();
        ptyTerminalPanes = new HashMap<>();
        terminalBufferCounter = 1;
        terminalController = new TerminalController(this);
        treePane = null;
        treeBuffer = null;
        treeRoot = null;
        treeLineTargets = new HashMap<>();
        quickfixBuffer = null;
        markdownService = new MarkdownService();
        fuzzyMatchService = new FuzzyMatchService();
        snippetService = new SnippetService();
        bracketColorService = new BracketColorService();
        markdownController = new MarkdownController(this);
        paneBufferController = new PaneBufferController(this);
        symbolService = new SymbolService();
        taskService = new TaskService();
        fileWatcherService = new FileWatcherService();
        substituteService = new SubstituteService();
        foldedLines = new HashMap<>();
        foldHiddenContent = new HashMap<>();
        concealLevel = 0;
        bracketHighlightTags = new ArrayList<>();
        markdownHighlightTags = new ArrayList<>();
        bracketColorEnabled = false;
        dramaticUiEnabled = false;
        dramaticIdentityEnabled = false;
        dramaticModeTransitionsEnabled = false;
        dramaticCommandPaletteEnabled = false;
        dramaticEditingFeedbackEnabled = false;
        dramaticPanelAnimationsEnabled = false;
        dramaticSoundEnabled = false;
        dramaticSoundPack = "default";
        dramaticSoundVolume = 75;
        dramaticSoundModeCueEnabled = true;
        dramaticSoundNavigateCueEnabled = true;
        dramaticSoundSuccessCueEnabled = true;
        dramaticSoundErrorCueEnabled = true;
        dramaticReducedMotionEnabled = false;
        dramaticPerformanceGuardrailsEnabled = true;
        dramaticPerformanceCpuThreshold = 0.80;
        dramaticPerformanceLineThreshold = 20000;
        whichKeyHintsEnabled = true;
        cachedProcessCpuLoad = -1.0;
        cachedProcessCpuLoadAtMillis = 0L;
        dramaticAnimationMs = 220;
        dramaticMinimapWidth = 84;
        modeTransitionTimer = null;
        feedbackPulseTimer = null;
        feedbackPulseTag = null;
        hostTintTimer = null;
        splitAnimationTimer = null;
        minimapWidthTimer = null;
        paneJumpFlashTimer = null;
        paneJumpFlashTarget = null;
        paneJumpFlashOriginalBorder = null;
        refreshDramaticSettings();
        loadRecentFiles();
        loadTrustedProjectRoots();
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
        pluginManager = new PluginManager(configManager, this);

        // Open file from command line or landing page
        if (args.length > 0) {
            try {
                File file = new File(args[0]);
                openFile(file);
            } catch (Exception e) {
                showMessage("Error opening file: " + e.getMessage());
            }
        } else {
            if (configManager.getSessionRestoreOnStart()) {
                String restored = loadSession(configManager.getSessionAutoloadName(), true);
                if (!restored.startsWith("Restored session")) {
                    openLandingPage();
                } else {
                    showMessage(restored);
                }
            } else {
                openLandingPage();
            }
        }

        updateStatusBar();

        this.setVisible(true);

        externalChangeTimer = new Timer(2000, e -> checkForExternalChanges());
        externalChangeTimer.start();
        startRecoverySnapshotTimer();
        promptRecoveryRestoreIfAvailable();
        fileWatcherService.start();
    }

    // Initialize UI components
    void initializeUI() {
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

    EditorPane createEditorPane(Dimension screenSize) {
        JTextArea textArea = new JTextArea() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                FontMetrics fm = g.getFontMetrics(getFont());
                int charW = fm.charWidth(' ');
                int lineH = fm.getHeight();
                int insetLeft = getInsets().left;

                // Column ruler
                int rulerCol = configManager.getRulerColumn();
                if (rulerCol > 0) {
                    int x = charW * rulerCol + insetLeft;
                    g.setColor(new Color(255, 255, 255, 30));
                    g.drawLine(x, 0, x, getHeight());
                }

                // Indent guides
                int tabSize = getTabSize();
                if (tabSize > 0) {
                    g.setColor(new Color(255, 255, 255, 15));
                    Rectangle clip = g.getClipBounds();
                    int startY = clip != null ? clip.y : 0;
                    int endY = clip != null ? clip.y + clip.height : getHeight();
                    String text = getText();
                    try {
                        int startLine = getLineOfOffset(viewToModel2D(new Point(0, startY)));
                        int endLine = Math.min(getLineCount() - 1, getLineOfOffset(viewToModel2D(new Point(0, endY))));
                        for (int line = startLine; line <= endLine; line++) {
                            int ls = getLineStartOffset(line);
                            int le = getLineEndOffset(line);
                            String lineText = text.substring(ls, Math.min(le, text.length()));
                            int indent = 0;
                            for (int j = 0; j < lineText.length() && (lineText.charAt(j) == ' ' || lineText.charAt(j) == '\t'); j++) {
                                indent += lineText.charAt(j) == '\t' ? tabSize : 1;
                            }
                            Rectangle2D r = modelToView2D(ls);
                            if (r == null) continue;
                            int y1 = (int) r.getY();
                            int y2 = y1 + lineH;
                            for (int col = tabSize; col < indent; col += tabSize) {
                                int x = charW * col + insetLeft;
                                g.drawLine(x, y1, x, y2);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                paintSyntaxForegroundOverlay(g, this);
                paintDiagnosticOverlay(g, this);
                paintVisualBlockOverlay(g, this);
                if (getLineWrap()) paintWrapIndicators(g, this);
                paintColorPreviews(g, this);
            }
        };
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
            updateMatchingBracketHighlight();
            if (lineNumberPanel != null) {
                lineNumberPanel.repaint();
            }
            dismissCompletionPopup();
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

    void bindActivePane(EditorPane pane) {
        if (pane == null) {
            return;
        }
        writingArea = pane.getTextArea();
        lineNumberPanel = pane.getLineNumberPanel();
        editorScrollPane = pane.getScrollPane();
        searchManager = pane.getSearchManager();
    }

    EditorPane getActivePane() {
        if (activePaneIndex < 0 || activePaneIndex >= editorPanes.size()) {
            return null;
        }
        return editorPanes.get(activePaneIndex);
    }

    void activateEditorPane(EditorPane pane) {
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

    void requestActivePaneFocus() {
        EditorPane pane = getActivePane();
        if (pane != null && pane.getTerminalPane() != null) {
            pane.getTerminalPane().requestFocusInWindow();
            return;
        }
        if (writingArea != null) {
            writingArea.requestFocusInWindow();
        }
    }

    void renderWindowLayout() {
        if (renderedLayoutComponent != null) {
            editorHostPanel.remove(renderedLayoutComponent);
        }
        renderedLayoutComponent = windowLayoutRoot == null ? new JPanel() : windowLayoutRoot.render();
        editorHostPanel.add(renderedLayoutComponent, BorderLayout.CENTER);
        updateZenModeLayout();
        editorHostPanel.revalidate();
        editorHostPanel.repaint();
    }

    Font resolveEditorFont() {
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

    Font resolveInstalledFont(String family, int fontSize) {
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

    Font loadBundledHackFont(int fontSize) {
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
        // Ctrl+[ as Escape alternative
        if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_OPEN_BRACKET) {
            e = new KeyEvent(e.getComponent(), e.getID(), e.getWhen(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
        }
        EditorMode previousMode = editorState.mode;
        if (editorState.mode == EditorMode.NORMAL && recordingRegister != null && !(editorState.pendingKey == '\0' && e.getKeyChar() == 'q')) {
            macroBuffer.add(NormalizedKeyStroke.fromKeyEvent(e));
        }
        if (applyConfiguredKeybinding(e)) {
            updateStatusBar();
            return;
        }
        modeEngine.dispatch(this, editorState, e);
        if (previousMode != EditorMode.INSERT && editorState.mode == EditorMode.INSERT && isPrintableKey(e)) {
            suppressNextTypedChar = true;
        }
        // Ctrl+o one-shot: return to insert after one normal command completes
        if (insertNormalOneShot && editorState.mode == EditorMode.NORMAL && editorState.pendingKey == '\0') {
            insertNormalOneShot = false;
            setMode(EditorMode.INSERT);
        }
        updateStatusBar();
    }

    boolean applyConfiguredKeybinding(KeyEvent e) {
        if (editorState.mode == null || keymapReplayDepth > 32) {
            return false;
        }
        String keySpec = keySpecFromEvent(e);
        if (keySpec == null || keySpec.isEmpty()) {
            return false;
        }
        String mapping = configManager.getKeybinding(modeKey(editorState.mode), keySpec);
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

    // Normal mode key handling
    void handleNormalMode(KeyEvent e) {
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
        if (editorState.pendingKey != '\0') {
            handlePendingKey(c, code);
            return;
        }

        // Accumulate numeric prefix for COUNTgg without breaking 0 line-start
        if (Character.isDigit(c) && (!editorState.pendingCount.isEmpty() || c != '0')) {
            editorState.pendingCount += c;
            return;
        }

        if (!editorState.pendingCount.isEmpty() && !supportsCountPrefix(e)) {
            editorState.pendingCount = "";
        }

        if (isQuickfixBufferActive() && (code == KeyEvent.VK_ENTER || c == 'o')) {
            editorState.pendingCount = "";
            showMessage(openQuickfixSelection());
            return;
        }

        if (isTreePaneActive() && (code == KeyEvent.VK_ENTER || c == 'o')) {
            editorState.pendingCount = "";
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
            editorState.visualStartPos = writingArea.getCaretPosition();
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
            editorState.commandBuffer = String.valueOf(c);
            commandHistoryIndex = -1;
            commandHistoryPrefix = editorState.commandBuffer;
            updateSubstitutePreview();
            return;
        } else if (c == '/' || c == '?') {
            editorState.searchStartPos = writingArea.getCaretPosition();
            setMode(EditorMode.SEARCH);
            editorState.searchForward = c == '/';
            editorState.commandBuffer = String.valueOf(c);
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
            editorState.pendingCount = "";
        } else if (c == '^') {
            moveLineFirstNonBlank();
            editorState.pendingCount = "";
        } else if (c == '$') {
            moveLineEnd();
            editorState.pendingCount = "";
        }

        // File movements
        else if (c == 'g') {
            setPendingKeyWithHint('g');
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
            editorState.pendingCount = "";
        } else if (c == 'M') {
            moveToScreenPosition('M');
            editorState.pendingCount = "";
        } else if (c == 'L') {
            moveToScreenPosition('L');
            editorState.pendingCount = "";
        }

        // Clipboard operations
        else if (c == 'y') {
            setPendingKeyWithHint('y');
        } else if (c == 'd') {
            setPendingKeyWithHint('d');
        } else if (c == 'c') {
            setPendingKeyWithHint('c');
        } else if (c == 'x') {
            int count = consumePendingCount();
            StringBuilder deleted = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String d = clipboardManager.deleteChar(writingArea);
                if (d.isEmpty()) break;
                deleted.append(d);
            }
            if (deleted.length() > 0) {
                lastCommand = "x";
                storeDelete(consumePendingRegister(), deleted.toString(), false);
                markModified();
            }
        } else if (c == 'X') {
            int count = consumePendingCount();
            StringBuilder deleted = new StringBuilder();
            for (int i = 0; i < count; i++) {
                int pos = writingArea.getCaretPosition();
                if (pos <= 0) break;
                String text = writingArea.getText();
                deleted.insert(0, text.charAt(pos - 1));
                writingArea.replaceRange("", pos - 1, pos);
            }
            if (deleted.length() > 0) {
                storeDelete(consumePendingRegister(), deleted.toString(), false);
                markModified();
            }
        } else if (c == 's') {
            int count = consumePendingCount();
            StringBuilder deleted = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String d = clipboardManager.deleteChar(writingArea);
                if (d.isEmpty()) break;
                deleted.append(d);
            }
            if (deleted.length() > 0) {
                storeDelete(consumePendingRegister(), deleted.toString(), false);
                markModified();
            }
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
        } else if (c == 'S') {
            editorState.pendingCount = "";
            lastCommand = "S";
            storeDelete(consumePendingRegister(), clipboardManager.deleteLine(writingArea), true);
            markModified();
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
        } else if (c == 'Y') {
            editorState.pendingCount = "";
            showMessage(yankToEndOfLine());
        } else if (c == 'p') {
            int count = consumePendingCount();
            for (int i = 0; i < count; i++) {
                pasteFromRegister(false);
            }
            editorState.pendingCount = "";
        } else if (c == 'P') {
            int count = consumePendingCount();
            for (int i = 0; i < count; i++) {
                pasteFromRegister(true);
            }
            editorState.pendingCount = "";
        } else if (c == 'D') {
            editorState.pendingCount = "";
            lastCommand = "D";
            storeDelete(consumePendingRegister(), clipboardManager.deleteToEndOfLine(writingArea), false);
            markModified();
        } else if (c == 'C') {
            editorState.pendingCount = "";
            lastCommand = "C";
            storeDelete(consumePendingRegister(), clipboardManager.deleteToEndOfLine(writingArea), false);
            markModified();
            lastInsertedText = "";
            setMode(EditorMode.INSERT);
        }

        // Undo/Redo
        else if (c == 'u') {
            editorState.pendingCount = "";
            if (undoManager.canUndo()) {
                undoManager.undo();
            }
        } else if (e.isControlDown() && c == 'r') {
            editorState.pendingCount = "";
            if (undoManager.canRedo()) {
                undoManager.redo();
            }
        }

        // Search navigation
        else if (c == 'n') {
            editorState.pendingCount = "";
            String result = searchManager.nextMatch();
            showMessage(result);
            if (result.startsWith("Match")) {
                pulseCaretLine(blendColor(configManager.getSelectionColor(), configManager.getCaretColor(), 0.35));
            }
        } else if (c == 'N') {
            editorState.pendingCount = "";
            String result = searchManager.prevMatch();
            showMessage(result);
            if (result.startsWith("Match")) {
                pulseCaretLine(blendColor(configManager.getSelectionColor(), configManager.getCaretColor(), 0.35));
            }
        } else if (c == '*') {
            editorState.pendingCount = "";
            String result = searchWordUnderCursor(true);
            showMessage(result);
            if (result.startsWith("Match")) {
                pulseCaretLine(blendColor(configManager.getSelectionColor(), configManager.getCaretColor(), 0.35));
            }
        } else if (c == '#') {
            editorState.pendingCount = "";
            String result = searchWordUnderCursor(false);
            showMessage(result);
            if (result.startsWith("Match")) {
                pulseCaretLine(blendColor(configManager.getSelectionColor(), configManager.getCaretColor(), 0.35));
            }
        } else if (c == ';') {
            editorState.pendingCount = "";
            showMessage(repeatFind(false));
        } else if (c == ',') {
            editorState.pendingCount = "";
            showMessage(repeatFind(true));
        }

        // Repeat last command
        else if (c == '.') {
            editorState.pendingCount = "";
            repeatLastCommand();
        } else if (c == 'J') {
            editorState.pendingCount = "";
            joinCurrentLine(true);
        }

        // Ctrl combinations
        else if (e.isControlDown()) {
            if (c == 'w' || code == KeyEvent.VK_W) {
                editorState.pendingCount = "";
                setPendingKeyWithHint('\u0017');
                return;
            } else if (c == 'p' || code == KeyEvent.VK_P) {
                editorState.pendingCount = "";
                showMessage(showFileFinder());
            } else if (c == 'n' || code == KeyEvent.VK_N) {
                editorState.pendingCount = "";
                showMessage(showLspCompletionStatus());
            } else if (c == 'o' || code == KeyEvent.VK_O) {
                editorState.pendingCount = "";
                jumpBack();
            } else if (c == 'i' || code == KeyEvent.VK_I) {
                editorState.pendingCount = "";
                jumpForward();
            } else if (c == 'd' || code == KeyEvent.VK_D) {
                editorState.pendingCount = "";
                if (e.isShiftDown()) {
                    addCursorAtNextMatch();
                } else {
                    scrollHalfPageDown();
                }
            } else if (c == 'u' || code == KeyEvent.VK_U) {
                editorState.pendingCount = "";
                scrollHalfPageUp();
            } else if (c == 'f' || code == KeyEvent.VK_F) {
                editorState.pendingCount = "";
                scrollFullPageDown();
            } else if (c == 'b' || code == KeyEvent.VK_B) {
                editorState.pendingCount = "";
                scrollFullPageUp();
            } else if (c == 'e' || code == KeyEvent.VK_E) {
                editorState.pendingCount = "";
                scrollLineDown();
            } else if (c == 'y' || code == KeyEvent.VK_Y) {
                editorState.pendingCount = "";
                scrollLineUp();
            } else if (c == 'g' || code == KeyEvent.VK_G) {
                editorState.pendingCount = "";
                showMessage(showFileInfo());
            } else if (c == 'v' || code == KeyEvent.VK_V) {
                editorState.pendingCount = "";
                enterVisualBlockMode();
                return;
            }
        }

        // TAB: markdown fold cycling on heading lines
        else if (code == KeyEvent.VK_TAB) {
            editorState.pendingCount = "";
            FileBuffer buf = getCurrentBuffer();
            if (buf != null && buf.getFileType() == FileType.MARKDOWN) {
                if (e.isShiftDown()) {
                    showMessage(globalFoldCycle());
                } else {
                    showMessage(toggleFoldAtCursor());
                }
            }
        }

        // Alt combinations for multi-cursor
        else if (e.isAltDown()) {
            if (code == KeyEvent.VK_J) {
                if (e.isShiftDown()) addCursorAbove();
                else addCursorBelow();
            } else if (code == KeyEvent.VK_K) {
                if (e.isShiftDown()) addCursorBelow();
                else addCursorAbove();
            }
        }
        // Escape (no-op in normal mode, but clear any messages)
        else if (code == KeyEvent.VK_ESCAPE) {
            editorState.pendingCount = "";
            editorState.pendingKey = '\0';
            clearExtraCursors();
            showMessage("Already in normal mode");
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
        editorState.pendingKey = pendingKey;
        showWhichKeyHint(pendingKey);
    }

    void showWhichKeyHint(char pendingKey) {
        if (!whichKeyHintsEnabled) {
            return;
        }
        String hint = whichKeyHintText(pendingKey);
        if (hint != null && !hint.isBlank()) {
            showMessage(hint);
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

    // Handle pending multi-key commands
    void handlePendingKey(char c, int code) {
        if (editorState.pendingKey == 'g') {
            if (c == 'g') {
                if (editorState.pendingCount.isEmpty()) {
                    moveFileStart();
                } else {
                    showMessage(gotoLine(Integer.parseInt(editorState.pendingCount)));
                }
            } else if (c == 'q') {
                showMessage(formatParagraph());
            } else if (c == 'j') {
                moveDisplayLineDown();
            } else if (c == 'k') {
                moveDisplayLineUp();
            } else if (c == 'e') {
                repeatAction(consumePendingCount(), this::moveWordEndBackward);
            } else if (c == 'E') {
                repeatAction(consumePendingCount(), this::moveWordEndBackwardBig);
            } else if (c == '0') {
                moveLineStart();
                editorState.pendingCount = "";
            } else if (c == '$') {
                moveLineEnd();
                editorState.pendingCount = "";
            } else if (c == '_') {
                moveLineLastNonBlank();
                editorState.pendingCount = "";
            } else if (c == 'J') {
                joinCurrentLine(false);
            } else if (c == ';') {
                changePrev();
            } else if (c == ',') {
                changeNext();
            } else if (c == 'c') {
                editorState.pendingKey = '\u0007';
                return;
            } else if (c == 'f') {
                FileBuffer buf = getCurrentBuffer();
                if (buf != null && buf.getFileType() == FileType.MARKDOWN) {
                    showMessage(goToMarkdownLink());
                } else {
                    showMessage(goToFileUnderCursor());
                }
            } else if (c == 'x') {
                showMessage(openBrowserUrl());
            } else if (c == 'O') {
                showMessage(showOutline());
            } else if (c == 'v') {
                if (editorState.lastVisualStart >= 0 && editorState.lastVisualEnd >= 0
                        && editorState.lastVisualStart <= writingArea.getText().length()
                        && editorState.lastVisualEnd <= writingArea.getText().length()) {
                    EditorMode vm = editorState.lastVisualMode != null ? editorState.lastVisualMode : EditorMode.VISUAL;
                    editorState.visualStartPos = editorState.lastVisualStart;
                    setMode(vm);
                    writingArea.setSelectionStart(editorState.lastVisualStart);
                    writingArea.setSelectionEnd(editorState.lastVisualEnd);
                    writingArea.setCaretPosition(editorState.lastVisualEnd);
                } else {
                    showMessage("No previous visual selection");
                }
            }
            editorState.pendingKey = '\0';
            editorState.pendingCount = "";
        } else if (editorState.pendingKey == 'y') {
            if (c == 'y') {
                int count = consumePendingCount();
                lastCommand = "yy";
                try {
                    int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
                    int startOffset = writingArea.getLineStartOffset(line);
                    int endLine = Math.min(line + count, writingArea.getLineCount()) - 1;
                    int endOffset = writingArea.getLineEndOffset(endLine);
                    String yanked = writingArea.getText(startOffset, endOffset - startOffset);
                    clipboardManager.yankSelection(yanked);
                    storeYank(consumePendingRegister(), yanked, true);
                    showMessage(count > 1 ? count + " lines yanked" : "Line yanked");
                } catch (BadLocationException ex) {
                    showMessage("Line yanked");
                }
            } else if (c == 's') {
                pendingSurroundAction = 'y';
                editorState.pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                pendingTextObjectOperator = 'y';
                pendingTextObjectModifier = c;
                editorState.pendingKey = '\0';
                return;
            } else if (c == 'g') {
                editorState.pendingKey = 'Y';
                return;
            } else {
                showMessage(applyMotionOperator('y', String.valueOf(c)));
            }
            editorState.pendingKey = '\0';
            editorState.pendingCount = "";
        } else if (editorState.pendingKey == 'd') {
            if (c == 'd') {
                int count = consumePendingCount();
                lastCommand = "dd";
                try {
                    int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
                    int startOffset = writingArea.getLineStartOffset(line);
                    int endLine = Math.min(line + count, writingArea.getLineCount()) - 1;
                    int endOffset = writingArea.getLineEndOffset(endLine);
                    String deleted = writingArea.getText(startOffset, endOffset - startOffset);
                    storeDelete(consumePendingRegister(), deleted, true);
                    writingArea.replaceRange("", startOffset, endOffset);
                    writingArea.setCaretPosition(Math.min(startOffset, writingArea.getText().length()));
                    markModified();
                    showMessage(count > 1 ? count + " lines deleted" : "Line deleted");
                } catch (BadLocationException ex) {
                    showMessage("Line deleted");
                }
            } else if (c == 's') {
                pendingSurroundAction = 'd';
                editorState.pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                pendingTextObjectOperator = 'd';
                pendingTextObjectModifier = c;
                editorState.pendingKey = '\0';
                return;
            } else if (c == 'g') {
                editorState.pendingKey = 'D';
                return;
            } else if (c == 'w') {
                int count = consumePendingCount();
                lastCommand = "dw";
                StringBuilder deleted = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String d = clipboardManager.deleteWord(writingArea);
                    if (d.isEmpty()) break;
                    deleted.append(d);
                }
                if (deleted.length() > 0) {
                    storeDelete(consumePendingRegister(), deleted.toString(), false);
                    markModified();
                }
                showMessage(count > 1 ? count + " words deleted" : "Word deleted");
            } else {
                showMessage(applyMotionOperator('d', String.valueOf(c)));
            }
            editorState.pendingKey = '\0';
            editorState.pendingCount = "";
        } else if (editorState.pendingKey == 'c') {
            if (c == 'c') {
                int count = consumePendingCount();
                lastCommand = "cc";
                try {
                    int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
                    int startOffset = writingArea.getLineStartOffset(line);
                    int endLine = Math.min(line + count, writingArea.getLineCount()) - 1;
                    int endOffset = writingArea.getLineEndOffset(endLine);
                    String deleted = writingArea.getText(startOffset, endOffset - startOffset);
                    storeDelete(consumePendingRegister(), deleted, true);
                    writingArea.replaceRange("", startOffset, endOffset);
                    writingArea.setCaretPosition(Math.min(startOffset, writingArea.getText().length()));
                    markModified();
                } catch (BadLocationException ex) {}
                lastInsertedText = "";
                setMode(EditorMode.INSERT);
            } else if (c == 's') {
                pendingSurroundAction = 'c';
                editorState.pendingKey = '\0';
                return;
            } else if (c == 'i' || c == 'a') {
                pendingTextObjectOperator = 'c';
                pendingTextObjectModifier = c;
                editorState.pendingKey = '\0';
                return;
            } else if (c == 'g') {
                editorState.pendingKey = 'C';
                return;
            } else if (c == 'w') {
                int count = consumePendingCount();
                lastCommand = "cw";
                StringBuilder deleted = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String d = clipboardManager.deleteWord(writingArea);
                    if (d.isEmpty()) break;
                    deleted.append(d);
                }
                if (deleted.length() > 0) {
                    storeDelete(consumePendingRegister(), deleted.toString(), false);
                    markModified();
                }
                lastInsertedText = "";
                setMode(EditorMode.INSERT);
            } else {
                showMessage(applyMotionOperator('c', String.valueOf(c)));
            }
            editorState.pendingKey = '\0';
            editorState.pendingCount = "";
        } else if (editorState.pendingKey == 'q') {
            recordingRegister = c;
            macroBuffer = new ArrayList<>();
            editorState.pendingKey = '\0';
            showMessage("recording @" + c);
        } else if (editorState.pendingKey == '@') {
            if (c == '@') {
                showMessage(playMacro(lastMacroRegister));
            } else {
                showMessage(playMacro(c));
            }
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == '"') {
            editorState.pendingRegister = c;
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == 'm') {
            FileBuffer buffer = getCurrentBuffer();
            if (buffer != null) {
                buffer.setMark(c, writingArea.getCaretPosition());
                showMessage("Mark set: " + c);
            }
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == '\'' || editorState.pendingKey == '`') {
            FileBuffer buffer = getCurrentBuffer();
            if (buffer != null) {
                Integer offset = buffer.getMark(c);
                if (offset != null) {
                    recordJumpPosition();
                    if (editorState.pendingKey == '\'') {
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
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == 'f' || editorState.pendingKey == 'F' || editorState.pendingKey == 't' || editorState.pendingKey == 'T') {
            showMessage(findCharacter(editorState.pendingKey, c));
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == 'r') {
            showMessage(replaceCharacter(c));
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == '>' || editorState.pendingKey == '<' || editorState.pendingKey == '=') {
            if (c == editorState.pendingKey) {
                FileBuffer buf = getCurrentBuffer();
                if (buf != null && buf.getFileType() == FileType.MARKDOWN && (editorState.pendingKey == '>' || editorState.pendingKey == '<')) {
                    showMessage(markdownHeadingShift(editorState.pendingKey == '>'));
                } else {
                    showMessage(applyLineOperator(editorState.pendingKey));
                }
            } else if (c == 'r' && (editorState.pendingKey == '>' || editorState.pendingKey == '<')) {
                FileBuffer buf = getCurrentBuffer();
                if (buf != null && buf.getFileType() == FileType.MARKDOWN) {
                    showMessage(markdownSubtreeShift(editorState.pendingKey == '>'));
                }
            }
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == 'D' || editorState.pendingKey == 'C' || editorState.pendingKey == 'Y') {
            char operator = editorState.pendingKey == 'D' ? 'd' : editorState.pendingKey == 'C' ? 'c' : 'y';
            showMessage(applyMotionOperator(operator, "g" + c));
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == '\u0017') {
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
                case '+':
                    showMessage(resizeActiveWindow(0.05));
                    break;
                case '-':
                    showMessage(resizeActiveWindow(-0.05));
                    break;
                case '>':
                    showMessage(resizeActiveWindow(0.05));
                    break;
                case '<':
                    showMessage(resizeActiveWindow(-0.05));
                    break;
                default:
                    break;
            }
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == 'z') {
            if (c == 't') {
                scrollCurrentLineTo('t');
            } else if (c == 'z') {
                scrollCurrentLineTo('z');
            } else if (c == 'b') {
                scrollCurrentLineTo('b');
            } else if (c == 'a') {
                showMessage(toggleFoldAtCursor());
            } else if (c == 'M') {
                showMessage(foldAll());
            } else if (c == 'R') {
                showMessage(unfoldAll());
            }
            editorState.pendingKey = '\0';
        } else if (editorState.pendingKey == ']') {
            if (c == ']') {
                showMessage(navigateHeading(true));
            } else if (c >= '1' && c <= '6') {
                showMessage(navigateHeadingAtLevel(true, c - '0'));
            }
            editorState.pendingKey = '\0';
            editorState.pendingCount = "";
        } else if (editorState.pendingKey == '[') {
            if (c == '[') {
                showMessage(navigateHeading(false));
            } else if (c >= '1' && c <= '6') {
                showMessage(navigateHeadingAtLevel(false, c - '0'));
            }
            editorState.pendingKey = '\0';
            editorState.pendingCount = "";
        } else if (editorState.pendingKey == '\u0007') {
            // gc pending state: gcc = comment current line(s), gc{motion} = comment motion range
            if (c == 'c') {
                int count = consumePendingCount();
                try {
                    int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
                    int endLine = Math.min(line + count, writingArea.getLineCount()) - 1;
                    toggleCommentLineRange(line, endLine);
                } catch (BadLocationException ignored) {}
            } else {
                MotionRange range = resolveMotionRange(String.valueOf(c));
                if (range != null) {
                    try {
                        int startLine = writingArea.getLineOfOffset(range.start);
                        int endLine = writingArea.getLineOfOffset(range.end > range.start ? range.end - 1 : range.end);
                        toggleCommentLineRange(startLine, endLine);
                    } catch (BadLocationException ignored) {}
                }
            }
            editorState.pendingKey = '\0';
            editorState.pendingCount = "";
        }

    }

    // Insert mode key handling
    void handleInsertMode(KeyEvent e) {
        int code = e.getKeyCode();
        if (isCompletionPopupVisible()) {
            if (code == KeyEvent.VK_DOWN || (e.isControlDown() && code == KeyEvent.VK_N)) {
                completionPopupNavigate(1); e.consume(); return;
            } else if (code == KeyEvent.VK_UP || (e.isControlDown() && code == KeyEvent.VK_P)) {
                completionPopupNavigate(-1); e.consume(); return;
            } else if (code == KeyEvent.VK_TAB || code == KeyEvent.VK_ENTER) {
                completionPopupAccept(); e.consume(); return;
            } else if (code == KeyEvent.VK_ESCAPE) {
                dismissCompletionPopup(); e.consume(); return;
            }
        }
        if (code == KeyEvent.VK_ESCAPE || (e.isControlDown() && code == KeyEvent.VK_OPEN_BRACKET)) {
            dismissCompletionPopup();
            registerManager.updateLastInserted(lastInsertedText);
            setMode(EditorMode.NORMAL);
            // Move cursor back one position (Vim behavior)
            int pos = writingArea.getCaretPosition();
            if (pos > 0) {
                writingArea.setCaretPosition(pos - 1);
            }
            return;
        }

        if (code == KeyEvent.VK_BACK_SPACE && !extraCursors.isEmpty()) {
            applyMultiCursorBackspace();
        }
        if (code == KeyEvent.VK_BACK_SPACE && configManager.getAutoPairs()) {
            String text = writingArea.getText();
            int pos = writingArea.getCaretPosition();
            if (pos > 0 && pos < text.length()) {
                char before = text.charAt(pos - 1);
                char after = text.charAt(pos);
                Character expected = autoPairCloser(before);
                if (expected != null && expected == after) {
                    writingArea.replaceRange("", pos, pos + 1); // delete closing char
                }
            }
        }
        if (e.isControlDown()) {
            if (code == KeyEvent.VK_W || e.getKeyChar() == 'w') {
                // Ctrl+w: delete word backward
                deleteWordBackwardInsert();
                return;
            } else if (code == KeyEvent.VK_U || e.getKeyChar() == 'u') {
                // Ctrl+u: delete to start of line
                deleteToLineStartInsert();
                return;
            } else if (code == KeyEvent.VK_O || e.getKeyChar() == 'o') {
                // Ctrl+o: execute one normal mode command then return to insert
                insertNormalOneShot = true;
                setMode(EditorMode.NORMAL);
                return;
            } else if (code == KeyEvent.VK_J || e.getKeyChar() == 'j') {
                // Ctrl+j: snippet expand (or code fence language complete in markdown)
                FileBuffer buf = getCurrentBuffer();
                if (buf != null && buf.getFileType() == FileType.MARKDOWN && isOnCodeFenceLine()) {
                    showMessage(completeCodeFenceLanguage());
                } else {
                    showMessage(expandSnippetAtCursor());
                }
                return;
            } else if (code == KeyEvent.VK_N || e.getKeyChar() == 'n') {
                showInlineCompletion();
                return;
            }
        }

        if (!e.isControlDown() && !e.isAltDown()) {
            char c = e.getKeyChar();
            FileBuffer currentBuf = getCurrentBuffer();
            boolean isMarkdown = currentBuf != null && currentBuf.getFileType() == FileType.MARKDOWN;
            if (c == '\t' && isMarkdown && isOnTableLine()) {
                // TAB in markdown table: move to next cell
                showMessage(markdownTableNextCell(e.isShiftDown()));
                e.consume();
                return;
            } else if (c == '\t' && configManager.getExpandTab()) {
                writingArea.replaceSelection(" ".repeat(writingArea.getTabSize()));
                lastInsertedText += " ".repeat(writingArea.getTabSize());
                e.consume();
            } else if (c == '\n') {
                if (isMarkdown) {
                    String continued = handleMarkdownEnter();
                    if (continued != null) {
                        e.consume();
                        return;
                    }
                }
                if (configManager.getAutoIndent()) {
                    String indent = currentLineIndentation();
                    SwingUtilities.invokeLater(() -> writingArea.insert(indent, writingArea.getCaretPosition()));
                    lastInsertedText += "\n" + indent;
                }
            } else if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
                if (configManager.getAutoPairs()) {
                    Character closer = autoPairCloser(c);
                    if (closer != null) {
                        // auto-insert closing pair after the char is processed
                        final char cl = closer;
                        SwingUtilities.invokeLater(() -> {
                            int p = writingArea.getCaretPosition();
                            writingArea.insert(String.valueOf(cl), p);
                            writingArea.setCaretPosition(p);
                        });
                    } else if (isClosingPairChar(c)) {
                        // skip over if next char matches
                        String text = writingArea.getText();
                        int p = writingArea.getCaretPosition();
                        if (p < text.length() && text.charAt(p) == c) {
                            writingArea.setCaretPosition(p + 1);
                            suppressNextTypedChar = true;
                            e.consume();
                            lastInsertedText += c;
                            return;
                        }
                    }
                }
                lastInsertedText += c;
                applyMultiCursorInsert(c);
            }
        }
    }

    // Visual mode key handling
    void handleVisualMode(KeyEvent e) {
        char c = e.getKeyChar();
        int code = e.getKeyCode();
        boolean lineMode = editorState.mode == EditorMode.VISUAL_LINE;

        if (code == KeyEvent.VK_ESCAPE) {
            clearExtraCursors();
            setMode(EditorMode.NORMAL);
            writingArea.setSelectionStart(writingArea.getCaretPosition());
            writingArea.setSelectionEnd(writingArea.getCaretPosition());
            return;
        }

        // Ctrl+d: add cursor at next match of selection
        if (e.isControlDown() && (code == KeyEvent.VK_D || c == 'd')) {
            addCursorAtNextMatch();
            return;
        }

        // Handle pending keys
        if (editorState.pendingKey == 'g') {
            editorState.pendingKey = '\0';
            if (c == 'c') {
                toggleCommentSelection();
            }
            setMode(EditorMode.NORMAL);
            return;
        } else if (editorState.pendingKey == 'S') {
            editorState.pendingKey = '\0';
            surroundVisualSelection(c);
            setMode(EditorMode.NORMAL);
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
        boolean blockMode = editorState.mode == EditorMode.VISUAL_BLOCK;
        if (blockMode) {
            // block selection is virtual; don't use JTextArea selection
            writingArea.repaint();
        } else if (lineMode) {
            selectLineRange(editorState.visualStartPos, newPos);
        } else {
            if (editorState.visualStartPos < newPos) {
                writingArea.setSelectionStart(editorState.visualStartPos);
                writingArea.setSelectionEnd(newPos);
            } else {
                writingArea.setSelectionStart(newPos);
                writingArea.setSelectionEnd(editorState.visualStartPos);
            }
        }

        // Operations on selection
        if (c == 'g') {
            editorState.pendingKey = 'g';
            return;
        } else if (c == 'S') {
            editorState.pendingKey = 'S';
            return;
        } else if (c == 'y') {
            if (blockMode) { yankVisualBlock(); setMode(EditorMode.NORMAL); }
            else {
                String selected = writingArea.getSelectedText();
                if (selected != null) { clipboardManager.yankSelection(selected); storeYank(consumePendingRegister(), selected, lineMode); showMessage("Selection yanked"); }
                setMode(EditorMode.NORMAL);
            }
        } else if (c == 'd' || c == 'x') {
            if (blockMode) { deleteVisualBlock(); setMode(EditorMode.NORMAL); }
            else {
                String selected = writingArea.getSelectedText();
                if (selected != null) { clipboardManager.yankSelection(selected); storeDelete(consumePendingRegister(), selected, lineMode); writingArea.replaceSelection(""); markModified(); showMessage("Selection deleted"); }
                setMode(EditorMode.NORMAL);
            }
        } else if (c == 'c') {
            if (blockMode) { deleteVisualBlock(); setMode(EditorMode.INSERT); }
            else {
                String selected = writingArea.getSelectedText();
                if (selected != null) { clipboardManager.yankSelection(selected); storeDelete(consumePendingRegister(), selected, lineMode); writingArea.replaceSelection(""); markModified(); }
                setMode(EditorMode.INSERT);
            }
        } else if (c == '>' || c == '<' || c == '=') {
            applyVisualLineOperator(c);
            setMode(EditorMode.NORMAL);
        } else if (c == '~') {
            String selected = writingArea.getSelectedText();
            if (selected != null) {
                StringBuilder toggled = new StringBuilder(selected.length());
                for (char ch : selected.toCharArray()) {
                    toggled.append(Character.isUpperCase(ch) ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
                }
                writingArea.replaceSelection(toggled.toString());
                markModified();
            }
            setMode(EditorMode.NORMAL);
        } else if (c == 'U') {
            String selected = writingArea.getSelectedText();
            if (selected != null) {
                writingArea.replaceSelection(selected.toUpperCase());
                markModified();
            }
            setMode(EditorMode.NORMAL);
        } else if (c == 'u') {
            String selected = writingArea.getSelectedText();
            if (selected != null) {
                writingArea.replaceSelection(selected.toLowerCase());
                markModified();
            }
            setMode(EditorMode.NORMAL);
        } else if (c == 'J') {
            joinVisualSelection();
            setMode(EditorMode.NORMAL);
        } else if (c == 'p' || c == 'P') {
            String selected = writingArea.getSelectedText();
            RegisterContent content = registerManager.get(consumePendingRegister());
            if (selected != null && content != null && !content.getText().isEmpty()) {
                storeDelete(null, selected, lineMode);
                writingArea.replaceSelection(content.getText());
                markModified();
                showMessage("Pasted over selection");
            }
            setMode(EditorMode.NORMAL);
        } else if (c == 'o') {
            int selStart = writingArea.getSelectionStart();
            int selEnd = writingArea.getSelectionEnd();
            int caret = writingArea.getCaretPosition();
            if (caret == selStart) {
                editorState.visualStartPos = selStart;
                writingArea.setCaretPosition(selEnd);
            } else {
                editorState.visualStartPos = selEnd;
                writingArea.setCaretPosition(selStart);
            }
            // Re-apply selection after swap
            int swappedPos = writingArea.getCaretPosition();
            if (lineMode) {
                selectLineRange(editorState.visualStartPos, swappedPos);
            } else {
                writingArea.setSelectionStart(Math.min(editorState.visualStartPos, swappedPos));
                writingArea.setSelectionEnd(Math.max(editorState.visualStartPos, swappedPos));
            }
        }
    }

    void normalizeVisualLineCaretForMotion() {
        int selectionStart = writingArea.getSelectionStart();
        int selectionEnd = writingArea.getSelectionEnd();
        int caret = writingArea.getCaretPosition();
        if (selectionEnd > selectionStart && caret == selectionEnd && caret > 0) {
            writingArea.setCaretPosition(Math.max(selectionStart, caret - 1));
        }
    }

    void applyVisualLineOperator(char operator) {
        try {
            int selStart = writingArea.getSelectionStart();
            int selEnd = writingArea.getSelectionEnd();
            int startLine = writingArea.getLineOfOffset(selStart);
            int endLine = writingArea.getLineOfOffset(selEnd);
            if (selEnd == writingArea.getLineStartOffset(endLine) && endLine > startLine) {
                endLine--;
            }
            String indent = configManager.getExpandTab() ? " ".repeat(writingArea.getTabSize()) : "\t";
            String text = writingArea.getText();
            int replaceStart = writingArea.getLineStartOffset(startLine);
            int replaceEnd = writingArea.getLineEndOffset(endLine);
            StringBuilder sb = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) {
                int ls = writingArea.getLineStartOffset(i);
                int le = writingArea.getLineEndOffset(i);
                String line = text.substring(ls, le);
                switch (operator) {
                    case '>':
                        sb.append(indent).append(line);
                        break;
                    case '<':
                        int removeCount = Math.min(writingArea.getTabSize(), leadingWhitespace(line));
                        sb.append(line.substring(removeCount));
                        break;
                    case '=':
                        String prevIndent = i > 0 ? indentationForLine(i - 1) : "";
                        sb.append(prevIndent).append(line.stripLeading());
                        break;
                }
            }
            writingArea.replaceRange(sb.toString(), replaceStart, replaceEnd);
            markModified();
            showMessage("Selection " + (operator == '>' ? "indented" : operator == '<' ? "dedented" : "auto-indented"));
        } catch (BadLocationException ignored) {
        }
        if (!substitutePreviewTags.isEmpty()) {
            pulseCaretLine(configManager.getSubstitutePreviewColor());
        }
    }

    void joinVisualSelection() {
        try {
            int selStart = writingArea.getSelectionStart();
            int selEnd = writingArea.getSelectionEnd();
            int startLine = writingArea.getLineOfOffset(selStart);
            int endLine = writingArea.getLineOfOffset(selEnd);
            if (selEnd == writingArea.getLineStartOffset(endLine) && endLine > startLine) {
                endLine--;
            }
            int joins = endLine - startLine;
            for (int i = 0; i < joins; i++) {
                joinCurrentLine(true);
            }
        } catch (BadLocationException ignored) {
        }
    }

    void surroundVisualSelection(char surroundChar) {
        SurroundPair pair = surroundPair(surroundChar);
        if (pair == null) {
            showMessage("Unknown surround: " + surroundChar);
            return;
        }
        int selStart = writingArea.getSelectionStart();
        int selEnd = writingArea.getSelectionEnd();
        if (selStart == selEnd) return;
        writingArea.insert(String.valueOf(pair.close), selEnd);
        writingArea.insert(String.valueOf(pair.open), selStart);
        markModified();
        showMessage("Surround added");
    }

    void toggleCommentSelection() {
        try {
            int selStart = writingArea.getSelectionStart();
            int selEnd = writingArea.getSelectionEnd();
            int startLine = writingArea.getLineOfOffset(selStart);
            int endLine = writingArea.getLineOfOffset(selEnd);
            if (selEnd == writingArea.getLineStartOffset(endLine) && endLine > startLine) {
                endLine--;
            }
            toggleCommentLineRange(startLine, endLine);
        } catch (BadLocationException ignored) {
        }
    }

    void toggleCommentLineRange(int startLine, int endLine) {
        try {
            FileBuffer buffer = getCurrentBuffer();
            if (buffer == null) return;
            String[] prefixes = lineCommentPrefixesFor(buffer.getFileType());
            if (prefixes.length == 0) {
                showMessage("No comment syntax for this file type");
                return;
            }
            String prefix = prefixes[0];
            String text = writingArea.getText();

            boolean allCommented = true;
            for (int i = startLine; i <= endLine; i++) {
                int ls = writingArea.getLineStartOffset(i);
                int le = writingArea.getLineEndOffset(i);
                String trimmed = text.substring(ls, le).stripLeading();
                if (!trimmed.isEmpty() && !trimmed.startsWith(prefix)) {
                    allCommented = false;
                    break;
                }
            }

            int replaceStart = writingArea.getLineStartOffset(startLine);
            int replaceEnd = writingArea.getLineEndOffset(endLine);
            StringBuilder sb = new StringBuilder();

            if (allCommented) {
                for (int i = startLine; i <= endLine; i++) {
                    int ls = writingArea.getLineStartOffset(i);
                    int le = writingArea.getLineEndOffset(i);
                    String line = text.substring(ls, le);
                    int idx = line.indexOf(prefix);
                    if (idx >= 0) {
                        int afterPrefix = idx + prefix.length();
                        boolean hasSpace = afterPrefix < line.length() && line.charAt(afterPrefix) == ' ';
                        sb.append(line, 0, idx);
                        sb.append(line.substring(hasSpace ? afterPrefix + 1 : afterPrefix));
                    } else {
                        sb.append(line);
                    }
                }
            } else {
                int minIndent = Integer.MAX_VALUE;
                for (int i = startLine; i <= endLine; i++) {
                    int ls = writingArea.getLineStartOffset(i);
                    int le = writingArea.getLineEndOffset(i);
                    String line = text.substring(ls, le).stripTrailing();
                    if (line.isEmpty()) continue;
                    int indent = 0;
                    for (char ch : line.toCharArray()) {
                        if (ch == ' ' || ch == '\t') indent++;
                        else break;
                    }
                    minIndent = Math.min(minIndent, indent);
                }
                if (minIndent == Integer.MAX_VALUE) minIndent = 0;

                for (int i = startLine; i <= endLine; i++) {
                    int ls = writingArea.getLineStartOffset(i);
                    int le = writingArea.getLineEndOffset(i);
                    String line = text.substring(ls, le);
                    if (line.stripTrailing().isEmpty()) {
                        sb.append(line);
                    } else {
                        sb.append(line, 0, minIndent);
                        sb.append(prefix).append(' ');
                        sb.append(line.substring(minIndent));
                    }
                }
            }

            writingArea.replaceRange(sb.toString(), replaceStart, replaceEnd);
            markModified();
            showMessage(allCommented ? "Uncommented" : "Commented");
        } catch (BadLocationException ignored) {
        }
    }

    // Replace mode key handling
    void handleReplaceMode(KeyEvent e) {
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
    void handleCommandMode(KeyEvent e) {
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (e.isControlDown() && (code == KeyEvent.VK_R || c == 'r')) {
            openCommandHistorySearch();
            updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_ESCAPE) {
            editorState.commandBuffer = "";
            clearSubstitutePreview();
            setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            String result = commandHandler.execute(editorState.commandBuffer);
            addCommandHistory(editorState.commandBuffer);
            if (!result.isEmpty()) {
                showMessage(result);
            }
            editorState.commandBuffer = "";
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
            editorState.commandBuffer = completeCommand(editorState.commandBuffer);
            updateSubstitutePreview();
            return;
        }

        if (code == KeyEvent.VK_BACK_SPACE) {
            if (editorState.commandBuffer.length() > 1) {
                editorState.commandBuffer = editorState.commandBuffer.substring(0, editorState.commandBuffer.length() - 1);
            } else {
                editorState.commandBuffer = "";
                clearSubstitutePreview();
                setMode(EditorMode.NORMAL);
            }
            updateSubstitutePreview();
            return;
        }

        // Append character to command buffer
        if (c != KeyEvent.CHAR_UNDEFINED && !e.isControlDown()) {
            editorState.commandBuffer += c;
            updateSubstitutePreview();
        }
    }

    void openCommandHistorySearch() {
        if (commandHistory.isEmpty()) {
            showMessage("No command history");
            return;
        }
        List<String> candidates = new ArrayList<>();
        for (int i = commandHistory.size() - 1; i >= 0; i--) {
            String entry = commandHistory.get(i);
            if (entry != null && !entry.isBlank()) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            showMessage("No command history");
            return;
        }
        String selected = showPaletteDialog("Command History", candidates,
            value -> value == null ? "" : "Recall history entry into : prompt");
        if (selected == null || selected.isBlank()) {
            showMessage("History search cancelled");
            return;
        }
        editorState.commandBuffer = selected;
        commandHistoryIndex = -1;
        commandHistoryPrefix = editorState.commandBuffer;
    }

    void handleSearchMode(KeyEvent e) {
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (code == KeyEvent.VK_ESCAPE) {
            // Restore cursor to pre-search position
            if (editorState.searchStartPos >= 0 && editorState.searchStartPos <= writingArea.getText().length()) {
                writingArea.setCaretPosition(editorState.searchStartPos);
            }
            searchManager.clearHighlights();
            editorState.commandBuffer = "";
            setMode(EditorMode.NORMAL);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            String pattern = editorState.commandBuffer.length() > 1 ? editorState.commandBuffer.substring(1) : "";
            String result = editorState.searchForward ? searchManager.searchForward(pattern) : searchManager.searchBackward(pattern);
            if (!result.isEmpty()) {
                showMessage(result);
                if (result.startsWith("Match")) {
                    pulseCaretLine(blendColor(configManager.getSelectionColor(), configManager.getCaretColor(), 0.35));
                }
            }
            if (!pattern.isEmpty()) {
                addCommandHistory(editorState.commandBuffer);
            }
            editorState.commandBuffer = "";
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
            if (editorState.commandBuffer.length() > 1) {
                editorState.commandBuffer = editorState.commandBuffer.substring(0, editorState.commandBuffer.length() - 1);
            } else {
                editorState.commandBuffer = "";
                if (editorState.searchStartPos >= 0 && editorState.searchStartPos <= writingArea.getText().length()) {
                    writingArea.setCaretPosition(editorState.searchStartPos);
                }
                searchManager.clearHighlights();
                setMode(EditorMode.NORMAL);
            }
            incrementalSearchPreview();
            return;
        }

        if (c != KeyEvent.CHAR_UNDEFINED && !e.isControlDown()) {
            editorState.commandBuffer += c;
            incrementalSearchPreview();
        }
    }

    void incrementalSearchPreview() {
        String pattern = editorState.commandBuffer.length() > 1 ? editorState.commandBuffer.substring(1) : "";
        if (pattern.isEmpty()) {
            searchManager.clearHighlights();
            if (editorState.searchStartPos >= 0 && editorState.searchStartPos <= writingArea.getText().length()) {
                writingArea.setCaretPosition(editorState.searchStartPos);
            }
            return;
        }
        if (editorState.searchForward) {
            searchManager.searchForward(pattern);
        } else {
            searchManager.searchBackward(pattern);
        }
    }

    static int vimCharClass(char c) {
        if (Character.isLetterOrDigit(c) || c == '_') return 1;
        if (Character.isWhitespace(c)) return 0;
        return 2;
    }

    // Movement methods
    void moveUp() {
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

    void moveDown() {
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

    void moveLeft() {
        int pos = writingArea.getCaretPosition();
        if (pos > 0) {
            writingArea.setCaretPosition(pos - 1);
        }
    }

    void moveRight() {
        int pos = writingArea.getCaretPosition();
        if (pos < writingArea.getText().length()) {
            writingArea.setCaretPosition(pos + 1);
        }
    }

    void moveWordForward() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();
        int len = text.length();
        if (pos >= len) return;

        int cls = vimCharClass(text.charAt(pos));
        if (cls > 0) {
            while (pos < len && vimCharClass(text.charAt(pos)) == cls) pos++;
        }
        while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;

        writingArea.setCaretPosition(Math.min(pos, len));
    }

    void moveWordBackward() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();
        if (pos <= 0) return;

        pos--;
        while (pos > 0 && Character.isWhitespace(text.charAt(pos))) pos--;
        if (pos >= 0) {
            int cls = vimCharClass(text.charAt(pos));
            while (pos > 0 && vimCharClass(text.charAt(pos - 1)) == cls) pos--;
        }

        writingArea.setCaretPosition(pos);
    }

    void moveWordEnd() {
        String text = writingArea.getText();
        int pos = writingArea.getCaretPosition();
        int len = text.length();
        if (pos >= len - 1) return;

        pos++;
        while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;
        if (pos < len) {
            int cls = vimCharClass(text.charAt(pos));
            while (pos + 1 < len && vimCharClass(text.charAt(pos + 1)) == cls) pos++;
        }

        writingArea.setCaretPosition(Math.min(pos, len));
    }

    void moveWordForwardBig() {
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

    void moveWordBackwardBig() {
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

    void moveWordEndBig() {
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

    void moveWordEndBackward() {
        moveWordEndBackwardInternal(false);
    }

    void moveWordEndBackwardBig() {
        moveWordEndBackwardInternal(true);
    }

    void moveWordEndBackwardInternal(boolean bigWord) {
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

    boolean isMotionWordChar(char c, boolean bigWord) {
        return bigWord ? !Character.isWhitespace(c) : isWordCharacter(c);
    }

    void moveLineStart() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int lineStart = writingArea.getLineStartOffset(line);
            writingArea.setCaretPosition(lineStart);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    void moveLineEnd() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int lineEnd = writingArea.getLineEndOffset(line);
            writingArea.setCaretPosition(Math.max(lineEnd - 1, 0));
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    void moveLineFirstNonBlank() {
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

    void moveLineLastNonBlank() {
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

    void moveFileStart() {
        writingArea.setCaretPosition(0);
    }

    void moveFileEnd() {
        writingArea.setCaretPosition(writingArea.getText().length());
    }

    void moveParagraphForward() {
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

    void moveParagraphBackward() {
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

    void moveSentenceForward() {
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

    void moveSentenceBackward() {
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

    void moveMatchingBracket() {
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

    void moveToFilePercent(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        String text = writingArea.getText();
        int target = (int) Math.round((text.length() * clamped) / 100.0);
        writingArea.setCaretPosition(Math.min(target, text.length()));
    }

    void moveToScreenPosition(char position) {
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

    void scrollCurrentLineTo(char anchor) {
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

    String lineText(int line) throws BadLocationException {
        int start = writingArea.getLineStartOffset(line);
        int end = writingArea.getLineEndOffset(line);
        return writingArea.getText().substring(start, end);
    }

    void scrollHalfPageDown() {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            Point current = new Point(visible.x, visible.y + visible.height / 2);
            writingArea.scrollRectToVisible(new Rectangle(current.x, current.y, visible.width, visible.height));
            int pos = writingArea.viewToModel2D(current);
            writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void scrollHalfPageUp() {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            Point current = new Point(visible.x, Math.max(0, visible.y - visible.height / 2));
            writingArea.scrollRectToVisible(new Rectangle(current.x, current.y, visible.width, visible.height));
            int pos = writingArea.viewToModel2D(current);
            writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void scrollFullPageDown() {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            Point target = new Point(visible.x, visible.y + visible.height);
            writingArea.scrollRectToVisible(new Rectangle(target.x, target.y, visible.width, visible.height));
            int pos = writingArea.viewToModel2D(target);
            writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void scrollFullPageUp() {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            Point target = new Point(visible.x, Math.max(0, visible.y - visible.height));
            writingArea.scrollRectToVisible(new Rectangle(target.x, target.y, visible.width, visible.height));
            int pos = writingArea.viewToModel2D(target);
            writingArea.setCaretPosition(pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void scrollLineDown() {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            int lineHeight = writingArea.getFontMetrics(writingArea.getFont()).getHeight();
            writingArea.scrollRectToVisible(new Rectangle(visible.x, visible.y + lineHeight, visible.width, visible.height));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void scrollLineUp() {
        try {
            Rectangle visible = writingArea.getVisibleRect();
            int lineHeight = writingArea.getFontMetrics(writingArea.getFont()).getHeight();
            writingArea.scrollRectToVisible(new Rectangle(visible.x, Math.max(0, visible.y - lineHeight), visible.width, visible.height));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    String showFileInfo() {
        try {
            FileBuffer buffer = getCurrentBuffer();
            String name = buffer != null ? buffer.getDisplayName() : "[No file]";
            int totalLines = writingArea.getLineCount();
            int currentLine = writingArea.getLineOfOffset(writingArea.getCaretPosition()) + 1;
            int percent = totalLines > 0 ? (currentLine * 100) / totalLines : 0;
            return "\"" + name + "\" " + totalLines + " lines --" + percent + "%--";
        } catch (Exception e) {
            return "Error getting file info";
        }
    }

    String goToFileUnderCursor() {
        try {
            String text = writingArea.getText();
            int pos = writingArea.getCaretPosition();
            // Expand from cursor to find a file-path-like string
            int start = pos;
            int end = pos;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1)) && text.charAt(start - 1) != '"' && text.charAt(start - 1) != '\'' && text.charAt(start - 1) != '<') {
                start--;
            }
            while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '"' && text.charAt(end) != '\'' && text.charAt(end) != '>') {
                end++;
            }
            if (start == end) return "No file path under cursor";
            String path = text.substring(start, end);

            File file = new File(path);
            if (!file.isAbsolute()) {
                FileBuffer buffer = getCurrentBuffer();
                File baseDir = buffer != null && buffer.getFile() != null ? buffer.getFile().getParentFile() : new File(".");
                file = new File(baseDir, path);
            }
            if (file.exists() && file.isFile()) {
                openFile(file);
                return "Opened " + file.getName();
            }
            return "File not found: " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    String openBrowserUrl() {
        try {
            String text = writingArea.getText();
            int pos = writingArea.getCaretPosition();
            int start = pos;
            int end = pos;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            if (start == end) return "No URL under cursor";
            String url = text.substring(start, end);
            // Strip surrounding markdown link syntax
            if (url.startsWith("[")) {
                int urlStart = url.indexOf("](");
                int urlEnd = url.indexOf(")", urlStart);
                if (urlStart >= 0 && urlEnd > urlStart) {
                    url = url.substring(urlStart + 2, urlEnd);
                }
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(new URI(url));
                    return "Opened: " + url;
                }
                return "Desktop not supported";
            }
            return "Not a URL: " + url;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    void selectCurrentLine() {
        int caret = writingArea.getCaretPosition();
        selectLineRange(caret, caret);
        editorState.visualStartPos = writingArea.getSelectionStart();
    }

    void selectLineRange(int anchorPosition, int currentPosition) {
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

    void ensureCaretVisible(JTextArea area) {
        if (area == null) return;
        try {
            Rectangle2D bounds = area.modelToView2D(area.getCaretPosition());
            if (bounds == null) return;
            int scrolloff = configManager.getScrolloff();
            if (scrolloff > 0) {
                int lineHeight = area.getFontMetrics(area.getFont()).getHeight();
                Rectangle expanded = bounds.getBounds();
                expanded.y -= scrolloff * lineHeight;
                expanded.height += 2 * scrolloff * lineHeight;
                area.scrollRectToVisible(expanded);
            } else {
                area.scrollRectToVisible(bounds.getBounds());
            }
        } catch (BadLocationException ignored) {
        }
    }

    boolean isPrintableKey(KeyEvent e) {
        char c = e.getKeyChar();
        return c != KeyEvent.CHAR_UNDEFINED
            && !Character.isISOControl(c)
            && !e.isControlDown()
            && !e.isAltDown()
            && !e.isMetaDown();
    }

    String searchWordUnderCursor(boolean forward) {
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

    boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    void browseCommandHistory(int direction) {
        if (commandHistory.isEmpty()) {
            return;
        }
        if (commandHistoryIndex < 0) {
            commandHistoryPrefix = editorState.commandBuffer;
            commandHistoryIndex = commandHistory.size();
        }

        int nextIndex = commandHistoryIndex + direction;
        nextIndex = Math.max(0, Math.min(nextIndex, commandHistory.size()));
        commandHistoryIndex = nextIndex;

        if (commandHistoryIndex >= commandHistory.size()) {
            editorState.commandBuffer = commandHistoryPrefix;
            return;
        }

        String candidate = commandHistory.get(commandHistoryIndex);
        if (!commandHistoryPrefix.isEmpty() && !candidate.startsWith(commandHistoryPrefix.substring(0, 1))) {
            return;
        }
        editorState.commandBuffer = candidate;
    }

    void addCommandHistory(String entry) {
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
        knownCommands.add("theater");
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
        knownCommands.addAll(configManager.getConfiguredCommandAliases());

        // Exact prefix match first
        for (String command : knownCommands) {
            if (command.startsWith(lowered)) {
                return ":" + command;
            }
        }
        // Fuzzy match fallback
        List<String> fuzzy = fuzzyMatchService.matchStrings(lowered, knownCommands, 1);
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

    void updateCurrentLineHighlight() {
        Highlighter highlighter = writingArea.getHighlighter();
        if (currentLineHighlightTag != null) {
            highlighter.removeHighlight(currentLineHighlightTag);
            currentLineHighlightTag = null;
        }

        if (!configManager.getShowCurrentLine() || editorState.mode == EditorMode.VISUAL || editorState.mode == EditorMode.VISUAL_LINE) {
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

    String getGitBlameForCurrentLine(FileBuffer buffer) {
        if (buffer == null || buffer.getFile() == null || buffer.isModified()) return null;
        try {
            int line = writingArea.getLineOfOffset(writingArea.getCaretPosition()) + 1;
            File file = buffer.getFile();
            ProcessBuilder pb = new ProcessBuilder("git", "blame", "-L", line + "," + line, "--porcelain", file.getName());
            pb.directory(file.getParentFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            String author = null;
            String summary = null;
            for (String l : output.split("\n")) {
                if (l.startsWith("author ")) author = l.substring(7);
                else if (l.startsWith("summary ")) summary = l.substring(8);
            }
            if (author != null && summary != null) {
                return author + ": " + summary;
            }
        } catch (Exception ignored) {}
        return null;
    }

    String findCurrentBreadcrumb() {
        try {
            FileBuffer buffer = getCurrentBuffer();
            if (buffer == null) {
                return null;
            }
            int caret = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(caret) + 1;
            List<SymbolService.Symbol> symbols = symbolService.collectSymbols(writingArea.getText(), buffer.getFileType());
            if (!symbols.isEmpty()) {
                List<SymbolService.Symbol> trail = symbolService.breadcrumbTrail(symbols, line);
                if (!trail.isEmpty()) {
                    StringBuilder breadcrumb = new StringBuilder();
                    for (int i = 0; i < trail.size(); i++) {
                        if (i > 0) {
                            breadcrumb.append(" > ");
                        }
                        breadcrumb.append(trail.get(i).getName());
                    }
                    String value = breadcrumb.toString();
                    if (value.length() > 88) {
                        return "..." + value.substring(value.length() - 85);
                    }
                    return value;
                }
            }
        } catch (BadLocationException ignored) {
        }
        return findCurrentScopeHeuristic();
    }

    String findCurrentScopeHeuristic() {
        try {
            String text = writingArea.getText();
            int caret = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(caret);
            // Search backward from current line for a function/class/method definition
            for (int i = line; i >= Math.max(0, line - 200); i--) {
                int ls = writingArea.getLineStartOffset(i);
                int le = writingArea.getLineEndOffset(i);
                String lineText = text.substring(ls, le).trim();
                // Match common patterns: function/def/fn/func/class/impl/pub fn/public/private/protected
                if (lineText.matches("^(public|private|protected|static|async|export|default)?\\s*(class|interface|enum|struct|impl|trait)\\s+\\w+.*")
                        || lineText.matches("^(public|private|protected|static|abstract|final)?\\s*(\\w+\\s+)*\\w+\\s*\\([^)]*\\).*\\{?\\s*$")
                        || lineText.matches("^(def|fn|func|function|sub|proc|method)\\s+\\w+.*")
                        || lineText.matches("^(pub\\s+)?(fn|async fn)\\s+\\w+.*")
                        || lineText.matches("^(const|let|var)\\s+\\w+\\s*=\\s*(function|\\([^)]*\\)\\s*=>).*")) {
                    // Extract just the name portion
                    String name = lineText.replaceAll("[{(].*", "").replaceAll("\\s*->.*", "").trim();
                    if (name.length() > 50) name = name.substring(0, 50) + "...";
                    return name;
                }
            }
        } catch (BadLocationException ignored) {}
        return null;
    }

    void updateMatchingBracketHighlight() {
        Highlighter highlighter = writingArea.getHighlighter();
        for (Object tag : matchBracketTags) {
            highlighter.removeHighlight(tag);
        }
        matchBracketTags.clear();

        try {
            String text = writingArea.getText();
            int caret = writingArea.getCaretPosition();
            if (text.isEmpty()) return;

            // Check char at caret and before caret
            int bracketPos = -1;
            if (caret < text.length() && isBracketChar(text.charAt(caret))) {
                bracketPos = caret;
            } else if (caret > 0 && isBracketChar(text.charAt(caret - 1))) {
                bracketPos = caret - 1;
            }
            if (bracketPos < 0) return;

            char bracket = text.charAt(bracketPos);
            int matchPos = findMatchingBracketPos(text, bracketPos, bracket);
            if (matchPos < 0) return;

            Highlighter.HighlightPainter matchPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x44FFFFFF, true));
            matchBracketTags.add(highlighter.addHighlight(bracketPos, bracketPos + 1, matchPainter));
            matchBracketTags.add(highlighter.addHighlight(matchPos, matchPos + 1, matchPainter));
        } catch (BadLocationException ignored) {
        }
    }

    boolean isBracketChar(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}';
    }

    int findMatchingBracketPos(String text, int pos, char bracket) {
        char match;
        int direction;
        switch (bracket) {
            case '(': match = ')'; direction = 1; break;
            case ')': match = '('; direction = -1; break;
            case '[': match = ']'; direction = 1; break;
            case ']': match = '['; direction = -1; break;
            case '{': match = '}'; direction = 1; break;
            case '}': match = '{'; direction = -1; break;
            default: return -1;
        }
        int depth = 0;
        for (int i = pos; i >= 0 && i < text.length(); i += direction) {
            char c = text.charAt(i);
            if (c == bracket) depth++;
            else if (c == match) depth--;
            if (depth == 0) return i;
        }
        return -1;
    }

    void refreshLineNumberPanel() {
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

    void applyThemeColors() {
        Color editorForeground = configManager.getEditorForeground();
        Color caretColor = configManager.getCaretColor();
        Color selectionColor = configManager.getSelectionColor();
        Color selectionTextColor = configManager.getSelectionTextColor();

        currentLinePainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getCurrentLineHighlightColor());
        substitutePreviewPainter = new DefaultHighlighter.DefaultHighlightPainter(configManager.getSubstitutePreviewColor());
        syntaxKeywordColor = configManager.getSyntaxKeywordColor();
        syntaxStringColor = configManager.getSyntaxStringColor();
        syntaxCommentColor = configManager.getSyntaxCommentColor();
        syntaxNumberColor = configManager.getSyntaxNumberColor();

        statusBar.setBackground(configManager.getStatusBarBackground());
        statusBar.setForeground(configManager.getStatusBarForeground());
        commandBar.setBackground(configManager.getCommandBarBackground());
        commandBar.setForeground(configManager.getCommandBarForeground());
        applyDramaticFooterStyling();

        for (EditorPane pane : editorPanes) {
            JTextArea area = pane.getTextArea();
            area.setForeground(editorForeground);
            area.setCaretColor(caretColor);
            area.setSelectionColor(selectionColor);
            area.setSelectedTextColor(selectionTextColor);
        }

        if (editorState.mode != null) {
            writingArea.setBackground(getModeBackground(editorState.mode));
        }
        updateZenModeLayout();

        refreshLineNumberPanel();
        updateCurrentLineHighlight();
        applySyntaxHighlighting();
        updateSubstitutePreview();
        updateStatusBar();
    }

    void applySyntaxHighlighting() {
        clearSyntaxHighlighting();

        FileBuffer buffer = getCurrentBuffer();
        if (buffer == null) {
            return;
        }

        String text = writingArea.getText();
        if (text.isEmpty()) {
            return;
        }

        boolean[] masked = new boolean[text.length()];
        FileType fileType = buffer.getFileType();

        highlightComments(text, fileType, masked);
        highlightStrings(text, fileType, masked);
        highlightNumbers(text, masked);
        if (fileType == FileType.JAVA) {
            highlightJavaAnnotations(text, masked);
        }
        highlightScopeRules(text, fileType, masked);
        highlightKeywords(text, syntaxKeywordsFor(fileType), masked);
        if (configManager.getShowWhitespace()) {
            Highlighter highlighter = writingArea.getHighlighter();
            highlightTrailingWhitespace(highlighter, text);
        }
        applyBracketHighlighting();
        writingArea.repaint();
    }

    void clearSyntaxHighlighting() {
        Highlighter highlighter = writingArea.getHighlighter();
        for (Object tag : syntaxHighlightTags) {
            highlighter.removeHighlight(tag);
        }
        syntaxHighlightTags.clear();
        syntaxForegroundSpans.clear();
        clearBracketHighlighting();
    }

    String[] syntaxKeywordsFor(FileType fileType) {
        return syntaxHighlightService.keywordsFor(fileType);
    }

    void highlightJavaAnnotations(String text, boolean[] masked) {
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
                addSyntaxHighlight(start, end, syntaxKeywordColor, masked);
                i = end;
            } else {
                i++;
            }
        }
    }

    void highlightKeywords(String text, String[] keywords, boolean[] masked) {
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
                    addSyntaxHighlight(match, end, syntaxKeywordColor, masked);
                }
                index = match + Math.max(1, keyword.length());
            }
        }
    }

    boolean isKeywordMatch(String text, int start, String keyword, boolean[] masked) {
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

    void highlightComments(String text, FileType fileType, boolean[] masked) {
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
                    addSyntaxHighlight(i, end, syntaxCommentColor, masked);
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
                addSyntaxHighlight(i, end, syntaxCommentColor, masked);
                i = Math.max(i + 1, end);
                matched = true;
                break;
            }
            if (!matched) {
                i++;
            }
        }
    }

    void highlightStrings(String text, FileType fileType, boolean[] masked) {
        int i = 0;
        while (i < text.length()) {
            if (masked[i]) {
                i++;
                continue;
            }

            if (fileType == FileType.JAVA && matchesAt(text, i, "\"\"\"")) {
                int closeIndex = text.indexOf("\"\"\"", i + 3);
                int end = closeIndex < 0 ? text.length() : closeIndex + 3;
                addSyntaxHighlight(i, end, syntaxStringColor, masked);
                i = Math.max(i + 1, end);
                continue;
            }

            if (fileType == FileType.PYTHON && (matchesAt(text, i, "\"\"\"") || matchesAt(text, i, "'''"))) {
                String delimiter = matchesAt(text, i, "\"\"\"") ? "\"\"\"" : "'''";
                int closeIndex = text.indexOf(delimiter, i + delimiter.length());
                int end = closeIndex < 0 ? text.length() : closeIndex + delimiter.length();
                addSyntaxHighlight(i, end, syntaxStringColor, masked);
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
            addSyntaxHighlight(i, Math.max(i + 1, end), syntaxStringColor, masked);
            i = Math.max(i + 1, end);
        }
    }

    void highlightTrailingWhitespace(Highlighter highlighter, String text) {
        Highlighter.HighlightPainter trailingPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x80FF4444, true));
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                int trailStart = i;
                while (trailStart > lineStart && (text.charAt(trailStart - 1) == ' ' || text.charAt(trailStart - 1) == '\t')) {
                    trailStart--;
                }
                if (trailStart < i) {
                    try {
                        syntaxHighlightTags.add(highlighter.addHighlight(trailStart, i, trailingPainter));
                    } catch (BadLocationException ignored) {}
                }
                lineStart = i + 1;
            }
        }
    }

    void highlightScopeRules(String text, FileType fileType, boolean[] masked) {
        List<SyntaxHighlightService.SyntaxRule> rules = syntaxHighlightService.scopeRulesFor(fileType);
        for (SyntaxHighlightService.SyntaxRule rule : rules) {
            java.util.regex.Matcher m = rule.pattern.matcher(text);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                if (start < masked.length && masked[start]) continue;
                Color color;
                switch (rule.scope) {
                    case "type": color = configManager.getSyntaxTypeColor(); break;
                    case "function": color = configManager.getSyntaxFunctionColor(); break;
                    case "constant": color = configManager.getSyntaxConstantColor(); break;
                    case "annotation": color = configManager.getSyntaxAnnotationColor(); break;
                    case "number": color = configManager.getSyntaxNumberColor(); break;
                    default: continue;
                }
                syntaxForegroundSpans.add(new SyntaxSpan(start, Math.min(end, text.length()), color));
            }
        }
    }

    void highlightNumbers(String text, boolean[] masked) {
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
                addSyntaxHighlight(start, end, syntaxNumberColor, masked);
            }
            i = Math.max(i + 1, end);
        }
    }

    void addSyntaxHighlight(int start, int end, Color color, boolean[] masked) {
        if (start < 0 || end <= start || start >= masked.length) {
            return;
        }
        int safeEnd = Math.min(end, masked.length);
        if (isMasked(masked, start, safeEnd)) {
            return;
        }
        syntaxForegroundSpans.add(new SyntaxSpan(start, safeEnd, color));
        markMasked(masked, start, safeEnd);
    }

    boolean isMasked(boolean[] masked, int start, int end) {
        for (int i = start; i < end && i < masked.length; i++) {
            if (masked[i]) {
                return true;
            }
        }
        return false;
    }

    void markMasked(boolean[] masked, int start, int end) {
        for (int i = Math.max(0, start); i < end && i < masked.length; i++) {
            masked[i] = true;
        }
    }

    boolean matchesAt(String text, int index, String token) {
        if (token == null || token.isEmpty() || index < 0 || index + token.length() > text.length()) {
            return false;
        }
        return text.regionMatches(index, token, 0, token.length());
    }

    boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    boolean isStringDelimiter(FileType fileType, char c) {
        return syntaxHighlightService.isStringDelimiter(fileType, c);
    }

    String[] lineCommentPrefixesFor(FileType fileType) {
        return syntaxHighlightService.lineCommentPrefixesFor(fileType);
    }

    String[][] blockCommentPairsFor(FileType fileType) {
        return syntaxHighlightService.blockCommentPairsFor(fileType);
    }

    void updateSubstitutePreview() {
        clearSubstitutePreview();
        if (editorState.mode != EditorMode.COMMAND || editorState.commandBuffer == null || !editorState.commandBuffer.startsWith(":")) {
            return;
        }

        String command = editorState.commandBuffer.substring(1);
        SubstitutePreview preview = parseSubstitutePreview(command);
        if (preview == null || preview.pattern.isEmpty()) {
            return;
        }

        try {
            int startOffset = writingArea.getLineStartOffset(Math.max(0, preview.startLine));
            int endLine = Math.min(writingArea.getLineCount() - 1, preview.endLine);
            int endOffset = writingArea.getLineEndOffset(endLine);
            String text = writingArea.getText();
            String region = text.substring(startOffset, endOffset);
            Highlighter highlighter = writingArea.getHighlighter();
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(preview.pattern).matcher(region);
                while (m.find()) {
                    int ms = startOffset + m.start();
                    int me = startOffset + m.end();
                    if (me > endOffset) break;
                    substitutePreviewTags.add(highlighter.addHighlight(ms, me, substitutePreviewPainter));
                }
            } catch (java.util.regex.PatternSyntaxException e) {
                int searchFrom = startOffset;
                while (searchFrom <= endOffset - preview.pattern.length()) {
                    int match = text.indexOf(preview.pattern, searchFrom);
                    if (match < 0 || match >= endOffset) break;
                    substitutePreviewTags.add(highlighter.addHighlight(match, match + preview.pattern.length(), substitutePreviewPainter));
                    searchFrom = match + Math.max(1, preview.pattern.length());
                }
            }
        } catch (BadLocationException ignored) {
        }
    }

    void clearSubstitutePreview() {
        Highlighter highlighter = writingArea.getHighlighter();
        for (Object tag : substitutePreviewTags) {
            highlighter.removeHighlight(tag);
        }
        substitutePreviewTags.clear();
    }

    SubstitutePreview parseSubstitutePreview(String command) {
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

    int findRangeCommandStart(String command) {
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (!Character.isDigit(c) && c != ',') {
                return i;
            }
        }
        return -1;
    }

    int getCurrentCaretLine() {
        try {
            return writingArea.getLineOfOffset(writingArea.getCaretPosition());
        } catch (BadLocationException e) {
            return 0;
        }
    }

    String resolveGitBranch() {
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

    javax.swing.JWindow completionPopup;
    javax.swing.JList<String> completionList;
    javax.swing.DefaultListModel<String> completionModel;
    String completionPrefix;
    void showInlineCompletion() {
        try {
            String prefix = currentCompletionPrefix();
            if (prefix == null || prefix.length() < 2) { dismissCompletionPopup(); return; }
            List<String> completions = gatherCompletions(prefix);
            if (completions.isEmpty()) { dismissCompletionPopup(); return; }
            completionPrefix = prefix;
            if (completionPopup == null) {
                completionModel = new javax.swing.DefaultListModel<>();
                completionList = new javax.swing.JList<>(completionModel);
                completionList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
                completionList.setFocusable(false);
                completionList.setFont(writingArea.getFont().deriveFont((float) writingArea.getFont().getSize()));
                completionList.setBackground(configManager.getCommandBarBackground());
                completionList.setForeground(configManager.getCommandBarForeground());
                completionList.setSelectionBackground(configManager.getSelectionColor());
                completionList.setSelectionForeground(configManager.getSelectionTextColor());
                completionPopup = new javax.swing.JWindow(this);
                javax.swing.JScrollPane sp = new javax.swing.JScrollPane(completionList);
                sp.setBorder(javax.swing.BorderFactory.createLineBorder(configManager.getCaretColor()));
                completionPopup.add(sp);
                completionPopup.setFocusableWindowState(false);
            }
            completionModel.clear();
            int max = Math.min(completions.size(), 12);
            for (int i = 0; i < max; i++) completionModel.addElement(completions.get(i));
            completionList.setSelectedIndex(0);
            Rectangle2D caretRect = writingArea.modelToView2D(writingArea.getCaretPosition());
            if (caretRect == null) return;
            if (!writingArea.isShowing()) return;
            java.awt.Point loc = writingArea.getLocationOnScreen();
            int px = loc.x + (int) caretRect.getX();
            int py = loc.y + (int) (caretRect.getY() + caretRect.getHeight());
            int lineH = writingArea.getFontMetrics(writingArea.getFont()).getHeight();
            completionPopup.setLocation(px, py);
            completionPopup.setSize(300, Math.min(max * lineH + 4, 240));
            completionPopup.setVisible(true);
        } catch (Exception ignored) { dismissCompletionPopup(); }
    }
    void dismissCompletionPopup() {
        if (completionPopup != null && completionPopup.isVisible()) completionPopup.setVisible(false);
    }
    boolean isCompletionPopupVisible() {
        return completionPopup != null && completionPopup.isVisible();
    }
    void completionPopupNavigate(int direction) {
        if (completionList == null || completionModel.isEmpty()) return;
        int idx = completionList.getSelectedIndex() + direction;
        if (idx < 0) idx = completionModel.size() - 1;
        if (idx >= completionModel.size()) idx = 0;
        completionList.setSelectedIndex(idx);
        completionList.ensureIndexIsVisible(idx);
    }
    void completionPopupAccept() {
        if (completionList == null) return;
        String selected = completionList.getSelectedValue();
        dismissCompletionPopup();
        if (selected != null && completionPrefix != null) {
            applyCompletion(completionPrefix, selected);
            markModified();
        }
    }
    List<String> gatherCompletions(String prefix) {
        List<String> completions = new ArrayList<>();
        FileBuffer buffer = getCurrentBuffer();
        LspClient client = resolveLspClient(buffer);
        if (buffer != null && client != null && buffer.hasFilePath()) {
            String uri = bufferUri(buffer);
            try {
                int line = writingArea.getLineOfOffset(writingArea.getCaretPosition());
                int col = writingArea.getCaretPosition() - writingArea.getLineStartOffset(line);
                for (LspClient.CompletionItem item : client.completion(uri, line, col)) {
                    if (item.getLabel() != null && !item.getLabel().isEmpty()) completions.add(item.getLabel());
                }
            } catch (BadLocationException ignored) {}
        }
        if (completions.isEmpty()) completions = collectBufferCompletions(prefix);
        return completions;
    }

    void addCursorAtNextMatch() {
        String text = writingArea.getText();
        String selected = writingArea.getSelectedText();
        if (selected == null || selected.isEmpty()) {
            // Get word under cursor
            int pos = writingArea.getCaretPosition();
            int start = pos, end = pos;
            while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) start--;
            while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) end++;
            if (start == end) return;
            selected = text.substring(start, end);
        }
        // Find next occurrence after last cursor
        int searchFrom = writingArea.getCaretPosition();
        for (int ec : extraCursors) {
            searchFrom = Math.max(searchFrom, ec);
        }
        int nextIdx = text.indexOf(selected, searchFrom + 1);
        if (nextIdx < 0) nextIdx = text.indexOf(selected); // wrap around
        if (nextIdx >= 0 && !extraCursors.contains(nextIdx)) {
            extraCursors.add(nextIdx);
            showMessage("Added cursor (" + extraCursors.size() + " extra)");
        }
    }

    String formatParagraph() {
        int tw = configManager.getTextWidth();
        if (tw <= 0) return "textwidth not set (use :set tw=80)";
        try {
            int caretPos = writingArea.getCaretPosition();
            int startLine = writingArea.getLineOfOffset(caretPos);
            int endLine = startLine;
            String text = writingArea.getText();
            // expand to paragraph boundaries (blank lines)
            while (startLine > 0) {
                int ls = writingArea.getLineStartOffset(startLine - 1);
                int le = writingArea.getLineEndOffset(startLine - 1);
                if (text.substring(ls, le).trim().isEmpty()) break;
                startLine--;
            }
            while (endLine < writingArea.getLineCount() - 1) {
                int ls = writingArea.getLineStartOffset(endLine + 1);
                int le = writingArea.getLineEndOffset(endLine + 1);
                if (text.substring(ls, le).trim().isEmpty()) break;
                endLine++;
            }
            int startOff = writingArea.getLineStartOffset(startLine);
            int endOff = writingArea.getLineEndOffset(endLine);
            String paraRaw = text.substring(startOff, endOff);
            // preserve leading indent from first line
            String indent = "";
            for (int i2 = 0; i2 < paraRaw.length(); i2++) {
                char ic = paraRaw.charAt(i2);
                if (ic == ' ' || ic == '\t') indent += ic;
                else break;
            }
            String paragraph = paraRaw.trim();
            String[] words = paragraph.split("\\s+");
            StringBuilder formatted = new StringBuilder();
            int col = indent.length();
            formatted.append(indent);
            for (String word : words) {
                if (col > 0 && col + 1 + word.length() > tw) {
                    formatted.append("\n").append(indent);
                    col = indent.length();
                }
                if (col > indent.length()) { formatted.append(" "); col++; }
                formatted.append(word);
                col += word.length();
            }
            formatted.append("\n");
            writingArea.replaceRange(formatted.toString(), startOff, endOff);
            markModified();
            return "Formatted paragraph to " + tw + " columns";
        } catch (BadLocationException e) { return "Error: " + e.getMessage(); }
    }
    void moveDisplayLineDown() {
        try {
            Rectangle2D r = writingArea.modelToView2D(writingArea.getCaretPosition());
            if (r == null) return;
            int lineH = writingArea.getFontMetrics(writingArea.getFont()).getHeight();
            int newY = (int) r.getY() + lineH;
            int newPos = writingArea.viewToModel2D(new java.awt.geom.Point2D.Double(r.getX(), newY));
            if (newPos >= 0 && newPos <= writingArea.getText().length()) writingArea.setCaretPosition(newPos);
        } catch (BadLocationException ignored) {}
    }
    void moveDisplayLineUp() {
        try {
            Rectangle2D r = writingArea.modelToView2D(writingArea.getCaretPosition());
            if (r == null) return;
            int lineH = writingArea.getFontMetrics(writingArea.getFont()).getHeight();
            int newY = (int) r.getY() - lineH;
            if (newY < 0) return;
            int newPos = writingArea.viewToModel2D(new java.awt.geom.Point2D.Double(r.getX(), newY));
            if (newPos >= 0 && newPos <= writingArea.getText().length()) writingArea.setCaretPosition(newPos);
        } catch (BadLocationException ignored) {}
    }
    void enterVisualBlockMode() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            int col = pos - writingArea.getLineStartOffset(line);
            editorState.visualBlockStartLine = line;
            editorState.visualBlockStartCol = col;
            editorState.visualStartPos = pos;
            setMode(EditorMode.VISUAL_BLOCK);
        } catch (BadLocationException ignored) {}
    }

    int[] getVisualBlockBounds() {
        if (editorState.visualBlockStartLine < 0 || editorState.visualBlockStartCol < 0) return null;
        try {
            int caretPos = writingArea.getCaretPosition();
            int curLine = writingArea.getLineOfOffset(caretPos);
            int curCol = caretPos - writingArea.getLineStartOffset(curLine);
            int startLine = Math.min(editorState.visualBlockStartLine, curLine);
            int endLine = Math.max(editorState.visualBlockStartLine, curLine);
            int startCol = Math.min(editorState.visualBlockStartCol, curCol);
            int endCol = Math.max(editorState.visualBlockStartCol, curCol);
            return new int[]{startLine, endLine, startCol, endCol};
        } catch (BadLocationException ignored) { return null; }
    }

    void deleteVisualBlock() {
        int[] bounds = getVisualBlockBounds();
        if (bounds == null) return;
        int startLine = bounds[0], endLine = bounds[1], startCol = bounds[2], endCol = bounds[3];
        try {
            StringBuilder yanked = new StringBuilder();
            for (int line = endLine; line >= startLine; line--) {
                int ls = writingArea.getLineStartOffset(line);
                int le = writingArea.getLineEndOffset(line);
                String lineText = writingArea.getText().substring(ls, le);
                int sc = Math.min(startCol, lineText.length());
                int ec = Math.min(endCol + 1, lineText.length());
                if (sc < ec) {
                    if (line < endLine) yanked.insert(0, "\n");
                    yanked.insert(0, lineText.substring(sc, ec));
                    writingArea.replaceRange("", ls + sc, ls + ec);
                }
            }
            clipboardManager.yankSelection(yanked.toString());
            storeDelete(consumePendingRegister(), yanked.toString(), false);
            markModified();
            showMessage("Block deleted");
        } catch (BadLocationException ignored) {}
    }

    void yankVisualBlock() {
        int[] bounds = getVisualBlockBounds();
        if (bounds == null) return;
        int startLine = bounds[0], endLine = bounds[1], startCol = bounds[2], endCol = bounds[3];
        try {
            StringBuilder yanked = new StringBuilder();
            for (int line = startLine; line <= endLine; line++) {
                int ls = writingArea.getLineStartOffset(line);
                int le = writingArea.getLineEndOffset(line);
                String lineText = writingArea.getText().substring(ls, le);
                int sc = Math.min(startCol, lineText.length());
                int ec = Math.min(endCol + 1, lineText.length());
                if (line > startLine) yanked.append("\n");
                if (sc < ec) yanked.append(lineText, sc, ec);
            }
            clipboardManager.yankSelection(yanked.toString());
            storeYank(consumePendingRegister(), yanked.toString(), false);
            showMessage("Block yanked");
        } catch (BadLocationException ignored) {}
    }

    static Character autoPairCloser(char c) {
        switch (c) {
            case '(': return ')';
            case '[': return ']';
            case '{': return '}';
            case '"': return '"';
            case '\'': return '\'';
            case '`': return '`';
            default: return null;
        }
    }
    static boolean isClosingPairChar(char c) {
        return c == ')' || c == ']' || c == '}' || c == '"' || c == '\'' || c == '`';
    }
    void applyMultiCursorInsert(char c) {
        if (extraCursors.isEmpty()) return;
        // Sort cursors descending so insertions don't shift earlier positions
        List<Integer> sorted = new ArrayList<>(extraCursors);
        sorted.sort(Collections.reverseOrder());
        String s = String.valueOf(c);
        for (int pos : sorted) {
            if (pos >= 0 && pos <= writingArea.getText().length()) {
                writingArea.insert(s, pos);
            }
        }
        // Shift all cursors forward by 1
        for (int i = 0; i < extraCursors.size(); i++) {
            extraCursors.set(i, extraCursors.get(i) + 1);
        }
    }

    void applyMultiCursorBackspace() {
        if (extraCursors.isEmpty()) return;
        List<Integer> sorted = new ArrayList<>(extraCursors);
        sorted.sort(Collections.reverseOrder());
        for (int pos : sorted) {
            if (pos > 0 && pos <= writingArea.getText().length()) {
                writingArea.replaceRange("", pos - 1, pos);
            }
        }
        for (int i = 0; i < extraCursors.size(); i++) {
            extraCursors.set(i, Math.max(0, extraCursors.get(i) - 1));
        }
    }
    void applyMultiCursorDelete() {
        if (extraCursors.isEmpty()) return;
        List<Integer> sorted = new ArrayList<>(extraCursors);
        sorted.sort(Collections.reverseOrder());
        for (int pos : sorted) {
            if (pos >= 0 && pos < writingArea.getText().length()) {
                writingArea.replaceRange("", pos, pos + 1);
            }
        }
    }
    void addCursorAbove() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            if (line <= 0) return;
            int col = pos - writingArea.getLineStartOffset(line);
            int prevLineStart = writingArea.getLineStartOffset(line - 1);
            int prevLineEnd = writingArea.getLineEndOffset(line - 1);
            int newPos = Math.min(prevLineStart + col, prevLineEnd - 1);
            if (!extraCursors.contains(newPos)) {
                extraCursors.add(newPos);
                showMessage("Added cursor above (" + extraCursors.size() + " extra)");
            }
        } catch (BadLocationException ignored) {}
    }
    void addCursorBelow() {
        try {
            int pos = writingArea.getCaretPosition();
            int line = writingArea.getLineOfOffset(pos);
            if (line >= writingArea.getLineCount() - 1) return;
            int col = pos - writingArea.getLineStartOffset(line);
            int nextLineStart = writingArea.getLineStartOffset(line + 1);
            int nextLineEnd = writingArea.getLineEndOffset(line + 1);
            int newPos = Math.min(nextLineStart + col, nextLineEnd - 1);
            if (!extraCursors.contains(newPos)) {
                extraCursors.add(newPos);
                showMessage("Added cursor below (" + extraCursors.size() + " extra)");
            }
        } catch (BadLocationException ignored) {}
    }
    void clearExtraCursors() {
        if (!extraCursors.isEmpty()) {
            extraCursors.clear();
        }
    }

    void deleteWordBackwardInsert() {
        try {
            String text = writingArea.getText();
            int pos = writingArea.getCaretPosition();
            if (pos <= 0) return;
            int start = pos - 1;
            // Skip whitespace
            while (start > 0 && Character.isWhitespace(text.charAt(start)) && text.charAt(start) != '\n') start--;
            if (start > 0 && text.charAt(start) != '\n') {
                int cls = vimCharClass(text.charAt(start));
                while (start > 0 && vimCharClass(text.charAt(start - 1)) == cls) start--;
            }
            writingArea.replaceRange("", start, pos);
        } catch (Exception ignored) {}
    }

    void deleteToLineStartInsert() {
        try {
            String text = writingArea.getText();
            int pos = writingArea.getCaretPosition();
            int lineStart = text.lastIndexOf('\n', pos - 1) + 1;
            if (pos > lineStart) {
                writingArea.replaceRange("", lineStart, pos);
            }
        } catch (Exception ignored) {}
    }

    String currentLineIndentation() {
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

    void moveLineIndentStart() {
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

    void openLineBelow() {
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

    void openLineAbove() {
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

    void joinCurrentLine(boolean withSpace) {
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

    String applyLineOperator(char operator) {
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

    int leadingWhitespace(String text) {
        int count = 0;
        while (count < text.length() && Character.isWhitespace(text.charAt(count)) && text.charAt(count) != '\n') {
            count++;
        }
        return count;
    }

    String indentationForLine(int line) {
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

    String findCharacter(char type, char target) {
        String text = writingArea.getText();
        int caret = writingArea.getCaretPosition();
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        int lineEnd = text.indexOf('\n', caret);
        if (lineEnd < 0) lineEnd = text.length();

        int result = -1;
        switch (type) {
            case 'f':
                for (int i = caret + 1; i < lineEnd; i++) {
                    if (text.charAt(i) == target) { result = i; break; }
                }
                break;
            case 'F':
                for (int i = caret - 1; i >= lineStart; i--) {
                    if (text.charAt(i) == target) { result = i; break; }
                }
                break;
            case 't':
                for (int i = caret + 1; i < lineEnd; i++) {
                    if (text.charAt(i) == target) { result = i - 1; break; }
                }
                break;
            case 'T':
                for (int i = caret - 1; i >= lineStart; i--) {
                    if (text.charAt(i) == target) { result = i + 1; break; }
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

    String repeatFind(boolean reverse) {
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

    void recordJumpPosition() {
        int position = writingArea.getCaretPosition();
        if (jumpList.isEmpty() || jumpList.get(jumpList.size() - 1) != position) {
            if (jumpIndex >= 0 && jumpIndex < jumpList.size() - 1) {
                jumpList = new ArrayList<>(jumpList.subList(0, jumpIndex + 1));
            }
            jumpList.add(position);
            jumpIndex = jumpList.size() - 1;
        }
    }

    void jumpBack() {
        if (jumpList.isEmpty() || jumpIndex <= 0) {
            showMessage("At oldest jump");
            return;
        }
        jumpIndex--;
        writingArea.setCaretPosition(Math.min(jumpList.get(jumpIndex), writingArea.getText().length()));
    }

    void jumpForward() {
        if (jumpList.isEmpty() || jumpIndex >= jumpList.size() - 1) {
            showMessage("At newest jump");
            return;
        }
        jumpIndex++;
        writingArea.setCaretPosition(Math.min(jumpList.get(jumpIndex), writingArea.getText().length()));
    }

    void recordChangePosition() {
        int position = writingArea.getCaretPosition();
        if (changeList.isEmpty() || changeList.get(changeList.size() - 1) != position) {
            changeList.add(position);
            if (changeList.size() > 100) {
                changeList.remove(0);
            }
            changeIndex = changeList.size() - 1;
        }
    }

    void changePrev() {
        if (changeList.isEmpty() || changeIndex <= 0) {
            showMessage("At oldest change");
            return;
        }
        changeIndex--;
        writingArea.setCaretPosition(Math.min(changeList.get(changeIndex), writingArea.getText().length()));
    }

    void changeNext() {
        if (changeList.isEmpty() || changeIndex >= changeList.size() - 1) {
            showMessage("At newest change");
            return;
        }
        changeIndex++;
        writingArea.setCaretPosition(Math.min(changeList.get(changeIndex), writingArea.getText().length()));
    }

    void checkForExternalChanges() {
        if (reloadPromptActive) {
            return;
        }
        int autoReloaded = 0;
        for (FileBuffer buffer : buffers) {
            if (buffer == null || !buffer.hasFilePath() || !buffer.hasExternalChanges()) {
                continue;
            }
            if (!buffer.isModified()) {
                try {
                    int caret = 0;
                    if (buffer == getCurrentBuffer()) {
                        caret = writingArea.getCaretPosition();
                    }
                    buffer.load(configManager);
                    if (buffer == getCurrentBuffer()) {
                        loadBufferIntoEditor(buffer);
                        writingArea.setCaretPosition(Math.min(caret, writingArea.getText().length()));
                    }
                    autoReloaded++;
                } catch (IOException e) {
                    showMessage("Reload failed: " + e.getMessage());
                }
                continue;
            }
            promptExternalConflictForModifiedBuffer(buffer);
        }
        if (autoReloaded > 0) {
            showMessage("Auto-reloaded " + autoReloaded + " externally changed buffer" + (autoReloaded == 1 ? "" : "s"));
        }
    }

    void promptExternalConflictForModifiedBuffer(FileBuffer buffer) {
        if (buffer == null || buffer.getFile() == null) {
            return;
        }
        reloadPromptActive = true;
        String[] options = {"Keep Mine", "Reload Theirs", "View Both"};
        int result = JOptionPane.showOptionDialog(
            this,
            "File changed on disk while modified in editor:\n"
                + buffer.getDisplayName()
                + "\nChoose how to resolve this conflict.",
            "External Change Conflict",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        );
        reloadPromptActive = false;

        if (result == 1) {
            try {
                int caret = buffer == getCurrentBuffer() ? writingArea.getCaretPosition() : 0;
                buffer.load(configManager);
                if (buffer == getCurrentBuffer()) {
                    loadBufferIntoEditor(buffer);
                    writingArea.setCaretPosition(Math.min(caret, writingArea.getText().length()));
                }
                showMessage("Reloaded from disk");
            } catch (IOException e) {
                showMessage("Reload failed: " + e.getMessage());
            }
            return;
        }
        if (result == 2) {
            showExternalConflictPreview(buffer);
            buffer.refreshExternalTimestamp();
            return;
        }
        buffer.refreshExternalTimestamp();
    }

    void showExternalConflictPreview(FileBuffer buffer) {
        if (buffer == null || buffer.getFile() == null) {
            return;
        }
        try {
            String disk = Files.readString(buffer.getFile().toPath(), StandardCharsets.UTF_8);
            StringBuilder preview = new StringBuilder();
            preview.append("External Conflict Preview\n");
            preview.append("File: ").append(buffer.getFilePath()).append("\n\n");
            preview.append("===== YOUR BUFFER =====\n");
            preview.append(buffer.getContent()).append("\n");
            preview.append("===== DISK VERSION =====\n");
            preview.append(disk).append("\n");
            preview.append("Tip: copy needed parts, then save.\n");
            showScratchBuffer("[external conflict] " + buffer.getDisplayName(), preview.toString());
        } catch (IOException e) {
            showMessage("Conflict preview failed: " + e.getMessage());
        }
    }

    void startRecoverySnapshotTimer() {
        if (recoverySnapshotTimer != null) {
            recoverySnapshotTimer.stop();
        }
        recoverySnapshotTimer = new Timer(5000, e -> persistRecoverySnapshotsSafely());
        recoverySnapshotTimer.setRepeats(true);
        recoverySnapshotTimer.start();
    }

    void persistRecoverySnapshotsSafely() {
        try {
            persistRecoverySnapshots();
        } catch (Exception ignored) {
        }
    }

    void persistRecoverySnapshots() throws IOException {
        if (recoveryStoreDir == null) {
            return;
        }
        if (!recoveryStoreDir.exists()) {
            Files.createDirectories(recoveryStoreDir.toPath());
        }

        Set<String> activeSnapshotFiles = new HashSet<>();
        int scratchIndex = 1;
        for (FileBuffer buffer : buffers) {
            if (buffer == null || !buffer.isModified() || buffer == treeBuffer || buffer == quickfixBuffer) {
                continue;
            }
            String snapshotId = buffer.hasFilePath()
                ? "file-" + Integer.toHexString(buffer.getFilePath().hashCode())
                : "scratch-" + (scratchIndex++);
            String snapshotFileName = snapshotId + ".json";
            activeSnapshotFiles.add(snapshotFileName);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", snapshotId);
            payload.put("name", buffer.getDisplayName());
            payload.put("path", buffer.hasFilePath() ? buffer.getFilePath() : null);
            payload.put("modified", true);
            payload.put("content", buffer.getContent());
            payload.put("savedAt", commandLogTimeFormat.format(LocalDateTime.now()));

            Files.writeString(
                new File(recoveryStoreDir, snapshotFileName).toPath(),
                MiniJson.stringify(payload),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        }

        File[] existing = recoveryStoreDir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (existing == null) {
            return;
        }
        for (File file : existing) {
            if (!activeSnapshotFiles.contains(file.getName())) {
                Files.deleteIfExists(file.toPath());
            }
        }
    }

    void clearRecoverySnapshots() {
        if (recoveryStoreDir == null || !recoveryStoreDir.exists()) {
            return;
        }
        File[] snapshots = recoveryStoreDir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (snapshots == null) {
            return;
        }
        for (File snapshot : snapshots) {
            try {
                Files.deleteIfExists(snapshot.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    void promptRecoveryRestoreIfAvailable() {
        if (recoveryStoreDir == null || !recoveryStoreDir.exists()) {
            return;
        }
        File[] snapshots = recoveryStoreDir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (snapshots == null || snapshots.length == 0) {
            return;
        }
        java.util.Arrays.sort(snapshots, Comparator.comparing(File::getName));
        int result = JOptionPane.showConfirmDialog(
            this,
            snapshots.length + " crash-recovery snapshot(s) were found. Restore now?",
            "Crash Recovery",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.YES_OPTION) {
            return;
        }

        int restored = 0;
        FileBuffer lastRestored = null;
        for (File snapshot : snapshots) {
            FileBuffer restoredBuffer = restoreRecoverySnapshot(snapshot);
            if (restoredBuffer != null) {
                restored++;
                lastRestored = restoredBuffer;
            }
            try {
                Files.deleteIfExists(snapshot.toPath());
            } catch (IOException ignored) {
            }
        }
        if (lastRestored != null) {
            loadBufferIntoEditor(lastRestored);
        }
        if (restored > 0) {
            showMessage("Recovered " + restored + " buffer" + (restored == 1 ? "" : "s") + " from crash snapshots");
        }
    }

    FileBuffer restoreRecoverySnapshot(File snapshotFile) {
        if (snapshotFile == null || !snapshotFile.isFile()) {
            return null;
        }
        try {
            String json = Files.readString(snapshotFile.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> payload = MiniJson.asObject(MiniJson.parse(json));
            if (payload == null) {
                return null;
            }
            String content = MiniJson.asString(payload.get("content"));
            String path = MiniJson.asString(payload.get("path"));
            String name = MiniJson.asString(payload.get("name"));
            String restoredContent = content == null ? "" : content;

            if (path != null && !path.isBlank()) {
                File file = new File(path);
                FileBuffer existing = findBufferByPath(file);
                if (existing == null) {
                    FileBuffer buffer = file.exists() ? new FileBuffer(file, configManager) : new FileBuffer(file.getAbsolutePath());
                    if (shouldReplaceSingleLandingBuffer()) {
                        buffers.set(0, buffer);
                    } else {
                        buffers.add(buffer);
                    }
                    registerFileWatch(buffer);
                    addToRecentFiles(file.getAbsolutePath());
                    existing = buffer;
                }
                existing.setContent(restoredContent, true);
                return existing;
            }

            String scratchName = name == null || name.isBlank() ? "[Recovered Scratch]" : "[Recovered] " + name;
            FileBuffer scratch = FileBuffer.createScratch(scratchName, restoredContent);
            scratch.setModified(true);
            if (shouldReplaceSingleLandingBuffer()) {
                buffers.set(0, scratch);
            } else {
                buffers.add(scratch);
            }
            return scratch;
        } catch (Exception ignored) {
            return null;
        }
    }

    void maybePreviewMarkdown(FileBuffer buffer) {
        markdownController.maybePreviewMarkdown(buffer);
    }

    String renderMarkdownPreview(String markdown, String title) {
        return markdownController.renderMarkdownPreview(markdown, title);
    }

    String escapeHtml(String value) {
        return markdownController.escapeHtml(value);
    }

    // ========== Markdown / Orgmode features ==========

    String[] getCurrentLines() {
        return markdownController.getCurrentLines();
    }

    // --- Heading folding ---

    String toggleFoldAtCursor() {
        return markdownController.toggleFoldAtCursor();
    }

    String foldHeading(int headingLine, MarkdownService.FoldRange range, String[] lines) {
        return markdownController.foldHeading(headingLine, range, lines);
    }

    String unfoldHeading(int headingLine, String[] lines) {
        return markdownController.unfoldHeading(headingLine, lines);
    }

    String foldAll() {
        return markdownController.foldAll();
    }

    String unfoldAll() {
        return markdownController.unfoldAll();
    }

    String globalFoldCycle() {
        return markdownController.globalFoldCycle();
    }

    // --- Heading navigation ---

    String navigateHeading(boolean forward) {
        return markdownController.navigateHeading(forward);
    }

    String navigateHeadingAtLevel(boolean forward, int level) {
        return markdownController.navigateHeadingAtLevel(forward, level);
    }

    public String showTableOfContents() {
        return markdownController.showTableOfContents();
    }

    public String showOutline() {
        return markdownController.showOutline();
    }

    // --- Heading promotion/demotion ---

    String markdownHeadingShift(boolean demote) {
        return markdownController.markdownHeadingShift(demote);
    }

    String markdownSubtreeShift(boolean demote) {
        return markdownController.markdownSubtreeShift(demote);
    }

    // --- Table editing ---

    boolean isOnTableLine() {
        return markdownController.isOnTableLine();
    }

    String markdownTableNextCell(boolean reverse) {
        return markdownController.markdownTableNextCell(reverse);
    }

    public String alignMarkdownTable() {
        return markdownController.alignMarkdownTable();
    }

    public String sortMarkdownTable(String args) {
        return markdownController.sortMarkdownTable(args);
    }

    public String insertTableColumn(String args) {
        return markdownController.insertTableColumn(args);
    }

    public String deleteTableColumn(String args) {
        return markdownController.deleteTableColumn(args);
    }

    public String insertTableTemplate(String args) {
        return markdownController.insertTableTemplate(args);
    }

    // --- Checkbox toggling ---

    public String toggleCheckbox() {
        return markdownController.toggleCheckbox();
    }

    // --- Smart list continuation ---

    String handleMarkdownEnter() {
        return markdownController.handleMarkdownEnter();
    }

    // --- Link helpers ---

    public String insertLink() {
        return markdownController.insertLink();
    }

    public String insertImage() {
        return markdownController.insertImage();
    }

    public String goToMarkdownLink() {
        return markdownController.goToMarkdownLink();
    }

    // --- Concealment ---

    public String setConcealLevel(int level) {
        return markdownController.setConcealLevel(level);
    }

    // --- Snippet expansion ---

    boolean isOnCodeFenceLine() {
        return markdownController.isOnCodeFenceLine();
    }

    String completeCodeFenceLanguage() {
        return markdownController.completeCodeFenceLanguage();
    }

    String expandSnippetAtCursor() {
        return markdownController.expandSnippetAtCursor();
    }

    public String listSnippets() {
        return markdownController.listSnippets();
    }

    // --- Bracket pair colorization ---

    public String toggleBracketColors() {
        return markdownController.toggleBracketColors();
    }

    void applyBracketHighlighting() {
        markdownController.applyBracketHighlighting();
    }

    void clearBracketHighlighting() {
        markdownController.clearBracketHighlighting();
    }

    // --- File watcher integration ---

    public void registerFileWatch(FileBuffer buffer) {
        if (buffer == null || buffer.getFile() == null || buffer.isScratch()) return;
        fileWatcherService.watch(buffer.getFile(), file -> {
            SwingUtilities.invokeLater(() -> {
                if (!reloadPromptActive && buffer.getFile() != null && buffer.getFile().equals(file)) {
                    checkForExternalChanges();
                }
            });
        });
    }

    // --- Fuzzy command completion ---

    public List<String> fuzzyCompleteCommand(String prefix) {
        List<String> allCommands = getAllCommandNames();
        return fuzzyMatchService.matchStrings(prefix, allCommands, 10);
    }

    List<String> getAllCommandNames() {
        List<String> commands = new ArrayList<>();
        commands.add("w"); commands.add("write"); commands.add("q"); commands.add("quit");
        commands.add("wq"); commands.add("x"); commands.add("e"); commands.add("edit");
        commands.add("bn"); commands.add("bp"); commands.add("ls"); commands.add("buffers");
        commands.add("bd"); commands.add("set"); commands.add("settings"); commands.add("config");
        commands.add("log"); commands.add("session"); commands.add("workspace"); commands.add("jobs"); commands.add("jobcancel");
        commands.add("drop"); commands.add("task"); commands.add("help"); commands.add("wc"); commands.add("recent");
        commands.add("d"); commands.add("delete"); commands.add("files"); commands.add("folder");
        commands.add("tree"); commands.add("git"); commands.add("grep"); commands.add("copen");
        commands.add("cclose"); commands.add("cnext"); commands.add("cprev"); commands.add("cc");
        commands.add("lsp"); commands.add("definition"); commands.add("hover"); commands.add("references");
        commands.add("diagnostics"); commands.add("diag"); commands.add("dnext"); commands.add("dprev"); commands.add("symbols"); commands.add("sym");
        commands.add("registers"); commands.add("yankring"); commands.add("marks"); commands.add("zen"); commands.add("theater"); commands.add("normal");
        commands.add("reload"); commands.add("source"); commands.add("clean"); commands.add("shedclean");
        commands.add("noh"); commands.add("split");
        commands.add("vsplit"); commands.add("close"); commands.add("themes");
        // New markdown commands
        commands.add("toc"); commands.add("outline"); commands.add("toggle");
        commands.add("table"); commands.add("link"); commands.add("img");
        commands.add("snippets"); commands.add("bracketcolor");
        commands.add("term"); commands.add("terminal");
        commands.addAll(configManager.getConfiguredCommandAliases());
        return commands;
    }

    // --- Integrated terminal ---

    public String openTerminal() {
        return terminalController.openTerminal();
    }

    File resolveTerminalStartDirectory() {
        return terminalController.resolveTerminalStartDirectory();
    }

    TerminalSession getActiveTerminalSession() {
        return terminalController.getActiveTerminalSession();
    }

    boolean handleTerminalInsertMode(TerminalSession session, KeyEvent e) {
        return terminalController.handleTerminalInsertMode(session, e);
    }

    void enforceTerminalInputBoundary(TerminalSession session) {
        terminalController.enforceTerminalInputBoundary(session);
    }

    void insertTerminalInputText(TerminalSession session, String text) {
        terminalController.insertTerminalInputText(session, text);
    }

    void executeTerminalLine(TerminalSession session) {
        terminalController.executeTerminalLine(session);
    }

    String handleTerminalBuiltin(TerminalSession session, String rawCommand) {
        return terminalController.handleTerminalBuiltin(session, rawCommand);
    }

    void handleTerminalCommandCompletion( TerminalSession session, String command, AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error ) {
        terminalController.handleTerminalCommandCompletion(session, command, snapshot, result, error);
    }

    void terminalHistoryPrevious(TerminalSession session) {
        terminalController.terminalHistoryPrevious(session);
    }

    void terminalHistoryNext(TerminalSession session) {
        terminalController.terminalHistoryNext(session);
    }

    void replaceTerminalInput(TerminalSession session, String input) {
        terminalController.replaceTerminalInput(session, input);
    }

    String currentTerminalInput(TerminalSession session) {
        return terminalController.currentTerminalInput(session);
    }

    void appendTerminalPrompt(TerminalSession session) {
        terminalController.appendTerminalPrompt(session);
    }

    String terminalPrompt(TerminalSession session) {
        return terminalController.terminalPrompt(session);
    }

    void appendTerminalText(TerminalSession session, String text) {
        terminalController.appendTerminalText(session, text);
    }

    void closeTerminalSession(FileBuffer buffer) {
        terminalController.closeTerminalSession(buffer);
    }

    void closeExitedTerminal(FileBuffer buffer) {
        terminalController.closeExitedTerminal(buffer);
    }

    void installTerminalActivationListeners(EditorPane pane, Component component) {
        terminalController.installTerminalActivationListeners(pane, component);
    }

    // ========== End of Markdown / Orgmode features ==========

    public String deleteLineRange(int startLine, int endLine) {
        return jobQuickfixController.deleteLineRange(startLine, endLine);
    }

    public String substituteRange(String pattern, String replacement, int startLine, int endLine, boolean replaceAll) {
        return jobQuickfixController.substituteRange(pattern, replacement, startLine, endLine, replaceAll);
    }

    public String runShellCommand(String command) {
        return jobQuickfixController.runShellCommand(command);
    }

    public String runDropCommand(String command) {
        return jobQuickfixController.runDropCommand(command);
    }

    public String handleTaskCommand(String argument) {
        return jobQuickfixController.handleTaskCommand(argument);
    }

    File resolveTaskProjectRoot() {
        return jobQuickfixController.resolveTaskProjectRoot();
    }

    File detectTaskProjectRoot(File file) {
        return jobQuickfixController.detectTaskProjectRoot(file);
    }

    String showProjectTasks(File projectRoot, Map<String, String> tasks) {
        return jobQuickfixController.showProjectTasks(projectRoot, tasks);
    }

    String saveProjectTask(File projectRoot, String name, String command) {
        return jobQuickfixController.saveProjectTask(projectRoot, name, command);
    }

    String removeProjectTask(File projectRoot, String name) {
        return jobQuickfixController.removeProjectTask(projectRoot, name);
    }

    String runNamedTask(String taskName, File projectRoot, Map<String, String> tasks) {
        return jobQuickfixController.runNamedTask(taskName, projectRoot, tasks);
    }

    String inferBuiltInTaskCommand(String taskName, File projectRoot) {
        return jobQuickfixController.inferBuiltInTaskCommand(taskName, projectRoot);
    }

    void handleTaskJobCompletion(String taskName, AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error) {
        jobQuickfixController.handleTaskJobCompletion(taskName, snapshot, result, error);
    }

    public String filterRangeWithCommand(int startLine, int endLine, String command) {
        return jobQuickfixController.filterRangeWithCommand(startLine, endLine, command);
    }

    public String showJobs() {
        return jobQuickfixController.showJobs();
    }

    public String cancelJob(String jobIdArgument) {
        return jobQuickfixController.cancelJob(jobIdArgument);
    }

    public String openQuickfixList() {
        return jobQuickfixController.openQuickfixList();
    }

    public String quickfixNext() {
        return jobQuickfixController.quickfixNext();
    }

    public String quickfixPrev() {
        return jobQuickfixController.quickfixPrev();
    }

    public String quickfixFirst() {
        return jobQuickfixController.quickfixFirst();
    }

    public String quickfixLast() {
        return jobQuickfixController.quickfixLast();
    }

    public String quickfixCurrent(String argument) {
        return jobQuickfixController.quickfixCurrent(argument);
    }

    public String closeQuickfixList() {
        return jobQuickfixController.closeQuickfixList();
    }

    void pruneSpecialBufferReturns(FileBuffer scratchBuffer) {
        jobQuickfixController.pruneSpecialBufferReturns(scratchBuffer);
    }

    boolean isQuickfixBufferActive() {
        return jobQuickfixController.isQuickfixBufferActive();
    }

    String openQuickfixSelection() {
        return jobQuickfixController.openQuickfixSelection();
    }

    String jumpToQuickfixEntry(QuickfixService.Entry entry) {
        return jobQuickfixController.jumpToQuickfixEntry(entry);
    }

    void updateQuickfixEntries(String title, List<QuickfixService.Entry> entries) {
        jobQuickfixController.updateQuickfixEntries(title, entries);
    }

    List<QuickfixService.Entry> parseQuickfixEntries(String output, String defaultSource) {
        return jobQuickfixController.parseQuickfixEntries(output, defaultSource);
    }

    String validateShellCommand(String command) {
        return jobQuickfixController.validateShellCommand(command);
    }

    CommandResult runShellProcess(String command, String input, AsyncJobService.JobToken token) throws Exception {
        return jobQuickfixController.runShellProcess(command, input, token);
    }

    CommandResult runExternalCommand( List<String> command, File workingDirectory, String input, AsyncJobService.JobToken token, int timeoutMs, int outputLimitBytes, boolean redirectErrorStream ) {
        return jobQuickfixController.runExternalCommand(command, workingDirectory, input, token, timeoutMs, outputLimitBytes, redirectErrorStream);
    }

    void readInputStreamCapped(InputStream stream, ByteArrayOutputStream out, int maxBytes, boolean[] truncated) {
        jobQuickfixController.readInputStreamCapped(stream, out, maxBytes, truncated);
    }

    void handleShellJobCompletion(AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error) {
        jobQuickfixController.handleShellJobCompletion(snapshot, result, error);
    }

    void handleFilterJobCompletion( AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error, FileBuffer targetBuffer, int startOffset, int endOffset, String originalInput, int startLine, int endLine ) {
        jobQuickfixController.handleFilterJobCompletion(snapshot, result, error, targetBuffer, startOffset, endOffset, originalInput, startLine, endLine);
    }

    public String showFileFinder() {
        return treeGitController.showFileFinder();
    }

    void collectProjectFiles(File dir, String rootPath, List<String> result, int limit) {
        treeGitController.collectProjectFiles(dir, rootPath, result, limit);
    }

    public String showFolderFinder() {
        return treeGitController.showFolderFinder();
    }

    String showFileFinderFromFolder(File folder) {
        return treeGitController.showFileFinderFromFolder(folder);
    }

    File chooseWithNavigator(int selectionMode, File startDirectory, String title) {
        return treeGitController.chooseWithNavigator(selectionMode, startDirectory, title);
    }

    File resolveNavigatorStartDirectory(File preferred) {
        return treeGitController.resolveNavigatorStartDirectory(preferred);
    }

    public String handleTreeCommand(String argument) {
        return treeGitController.handleTreeCommand(argument);
    }

    String revealCurrentInTree() {
        return treeGitController.revealCurrentInTree();
    }

    String treeCreateFile(String pathArgument) {
        return treeGitController.treeCreateFile(pathArgument);
    }

    String treeCreateDirectory(String pathArgument) {
        return treeGitController.treeCreateDirectory(pathArgument);
    }

    String treeRename(String argument) {
        return treeGitController.treeRename(argument);
    }

    String treeDelete(String argument, boolean force) {
        return treeGitController.treeDelete(argument, force);
    }

    String validateTreeDeleteTarget(File target) {
        return treeGitController.validateTreeDeleteTarget(target);
    }

    public String showFileTree(String pathArgument) {
        return treeGitController.showFileTree(pathArgument);
    }

    String closeTreePane() {
        return treeGitController.closeTreePane();
    }

    void appendTreeEntry(StringBuilder builder, List<String> lineTargets, File entry, String prefix, boolean last, int[] rendered, int maxEntries) {
        treeGitController.appendTreeEntry(builder, lineTargets, entry, prefix, last, rendered, maxEntries);
    }

    void appendTreeLine(StringBuilder builder, List<String> lineTargets, String text, String targetPath) {
        treeGitController.appendTreeLine(builder, lineTargets, text, targetPath);
    }

    File[] listTreeChildren(File directory) {
        return treeGitController.listTreeChildren(directory);
    }

    String treeTitleSuffix(File root) {
        return treeGitController.treeTitleSuffix(root);
    }

    FileBuffer createOrReplaceTreeBuffer(String titleSuffix, String content, List<String> lineTargets) {
        return treeGitController.createOrReplaceTreeBuffer(titleSuffix, content, lineTargets);
    }

    EditorPane resolveTreeContentPaneForTreeCommand() {
        return treeGitController.resolveTreeContentPaneForTreeCommand();
    }

    EditorPane ensureTreePane(EditorPane contentPane) {
        return treeGitController.ensureTreePane(contentPane);
    }

    boolean isTreePaneActive() {
        return treeGitController.isTreePaneActive();
    }

    boolean isTreeBuffer(FileBuffer buffer) {
        return treeGitController.isTreeBuffer(buffer);
    }

    String openTreeSelection() {
        return treeGitController.openTreeSelection();
    }

    EditorPane resolveTreeContentPaneForOpen() {
        return treeGitController.resolveTreeContentPaneForOpen();
    }

    public String handleGitCommand(String argument) {
        return treeGitController.handleGitCommand(argument);
    }

    File resolveGitRoot() {
        return treeGitController.resolveGitRoot();
    }

    String showGitStatus(File gitRoot) {
        return treeGitController.showGitStatus(gitRoot);
    }

    String showGitDiff(File gitRoot, String args) {
        return treeGitController.showGitDiff(gitRoot, args);
    }

    void refreshGitGutter() {
        treeGitController.refreshGitGutter();
    }

    void parseUnifiedDiffForGutter(String diff, Set<Integer> added, Set<Integer> modified, Set<Integer> deletedAfter) {
        treeGitController.parseUnifiedDiffForGutter(diff, added, modified, deletedAfter);
    }

    String showGitLog(File gitRoot, String args) {
        return treeGitController.showGitLog(gitRoot, args);
    }

    String showGitBranches(File gitRoot) {
        return treeGitController.showGitBranches(gitRoot);
    }

    String runGitAdd(File gitRoot, String args) {
        return treeGitController.runGitAdd(gitRoot, args);
    }

    String runGitRestoreStaged(File gitRoot, String args) {
        return treeGitController.runGitRestoreStaged(gitRoot, args);
    }

    String runGitCommit(File gitRoot, String message) {
        return treeGitController.runGitCommit(gitRoot, message);
    }

    String runGitAmend(File gitRoot, String argument) {
        return treeGitController.runGitAmend(gitRoot, argument);
    }

    boolean gitHeadExists(File gitRoot) {
        return treeGitController.gitHeadExists(gitRoot);
    }

    String runGitCheckout(File gitRoot, String argument) {
        return treeGitController.runGitCheckout(gitRoot, argument);
    }

    String runGitSwitch(File gitRoot, String argument) {
        return treeGitController.runGitSwitch(gitRoot, argument);
    }

    String runGitHunkCommand(File gitRoot, String argument) {
        return treeGitController.runGitHunkCommand(gitRoot, argument);
    }

    String selectGitHunkPatch(String diff, int line) {
        return treeGitController.selectGitHunkPatch(diff, line);
    }

    boolean gitHunkContainsLine(String marker, int line) {
        return treeGitController.gitHunkContainsLine(marker, line);
    }

    String relativizeAgainstGitRoot(File gitRoot, File file) {
        return treeGitController.relativizeAgainstGitRoot(gitRoot, file);
    }

    String showGitHelp() {
        return treeGitController.showGitHelp();
    }

    List<String> splitWhitespaceArgs(String args) {
        return treeGitController.splitWhitespaceArgs(args);
    }

    List<String> parseQuotedArguments(String raw) {
        return treeGitController.parseQuotedArguments(raw);
    }

    CommandResult runCommand(File workingDirectory, List<String> command) {
        return treeGitController.runCommand(workingDirectory, command);
    }

    String gitError(CommandResult result) {
        return treeGitController.gitError(result);
    }

    public String showCommandPalette() {
        List<String> commands = commandHandler.getCommandNames();
        List<String> candidates = new ArrayList<>();
        for (String cmd : commands) {
            candidates.add(":" + cmd);
        }
        String selected = showPaletteDialog("Command Palette", candidates, this::describeCommandPaletteCandidate);
        if (selected == null || selected.isEmpty()) return "Command palette cancelled";
        String cmd = selected.startsWith(":") ? selected.substring(1) : selected;
        return commandHandler.execute(cmd);
    }

    public String showBufferFinder() {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < buffers.size(); i++) {
            candidates.add((i + 1) + ": " + buffers.get(i).getDisplayName());
        }
        String selection = showPaletteDialog("Buffers", candidates, value -> "Switch to " + value);
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
        updateQuickfixEntries("grep " + (pattern == null ? "" : pattern), parseQuickfixEntries(String.join("\n", candidates), "grep"));
        String selection = showPaletteDialog("Grep", candidates, this::describeGrepCandidate);
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

    public String showSymbols(String argument) {
        FileBuffer buffer = getCurrentBuffer();
        if (buffer == null) {
            return "No buffer";
        }
        List<SymbolService.Symbol> symbols = symbolService.collectSymbols(writingArea.getText(), buffer.getFileType());
        if (symbols.isEmpty()) {
            return "No symbols found";
        }
        String query = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        List<SymbolService.Symbol> filtered = new ArrayList<>();
        for (SymbolService.Symbol symbol : symbols) {
            if (query.isEmpty()) {
                filtered.add(symbol);
                continue;
            }
            String haystack = (symbol.getName() + " " + symbol.getKind()).toLowerCase(Locale.ROOT);
            if (haystack.contains(query)) {
                filtered.add(symbol);
            }
        }
        if (filtered.isEmpty()) {
            return "No symbols matched: " + query;
        }

        Map<String, SymbolService.Symbol> candidateMap = new LinkedHashMap<>();
        for (SymbolService.Symbol symbol : filtered) {
            String candidate = formatSymbolCandidate(symbol);
            if (candidateMap.containsKey(candidate)) {
                candidate = candidate + "  [#" + symbol.getLine() + "]";
            }
            candidateMap.put(candidate, symbol);
        }
        List<String> candidates = new ArrayList<>(candidateMap.keySet());
        String selection = showPaletteDialog("Symbols", candidates, value -> describeSymbolCandidate(value, candidateMap, symbols));
        if (selection == null || selection.isEmpty()) {
            return "Symbols cancelled";
        }
        SymbolService.Symbol selected = candidateMap.get(selection);
        if (selected == null) {
            return "Invalid symbol selection";
        }
        return gotoLine(selected.getLine());
    }

    String formatSymbolCandidate(SymbolService.Symbol symbol) {
        StringBuilder indent = new StringBuilder();
        for (int i = 1; i < symbol.getLevel(); i++) {
            indent.append("  ");
        }
        return String.format("%4d  %-8s  %s%s",
            symbol.getLine(),
            symbol.getKind(),
            indent,
            symbol.getName());
    }

    String describeSymbolCandidate(
        String selection,
        Map<String, SymbolService.Symbol> candidateMap,
        List<SymbolService.Symbol> allSymbols
    ) {
        if (selection == null || selection.isBlank()) {
            return "Select a symbol to jump.";
        }
        SymbolService.Symbol symbol = candidateMap.get(selection);
        if (symbol == null) {
            return selection;
        }
        List<SymbolService.Symbol> trail = symbolService.breadcrumbTrail(allSymbols, symbol.getLine());
        StringBuilder breadcrumb = new StringBuilder();
        for (int i = 0; i < trail.size(); i++) {
            if (i > 0) {
                breadcrumb.append(" > ");
            }
            breadcrumb.append(trail.get(i).getName());
        }
        return "Line " + symbol.getLine()
            + " [" + symbol.getKind() + "]\n"
            + (breadcrumb.length() == 0 ? symbol.getName() : breadcrumb.toString());
    }

    void collectFiles(File directory, List<String> results) {
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

    List<String> grepFiles(String pattern) {
        List<String> results = new ArrayList<>();
        if (pattern == null || pattern.isEmpty()) {
            return results;
        }
        grepFilesRecursive(new File("."), pattern, results);
        return results;
    }

    void grepFilesRecursive(File directory, String pattern, List<String> results) {
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

    String describeCommandPaletteCandidate(String selection) {
        if (selection == null || selection.isBlank()) {
            return "Type to fuzzy-filter commands, then press Enter.";
        }
        String cmd = selection.startsWith(":") ? selection.substring(1) : selection;
        int split = cmd.indexOf(' ');
        String base = (split >= 0 ? cmd.substring(0, split) : cmd).toLowerCase(Locale.ROOT);
        switch (base) {
            case "w":
            case "write":
                return "Write current buffer to disk.";
            case "q":
            case "quit":
            case "q!":
                return "Quit current buffer/editor.";
            case "wq":
            case "x":
                return "Write buffer, then quit.";
            case "e":
            case "edit":
                return "Open file into a buffer.";
            case "bn":
            case "bnext":
                return "Switch to next buffer.";
            case "bp":
            case "bprev":
                return "Switch to previous buffer.";
            case "ls":
                return "List open buffers.";
            case "buffers":
            case "buf":
                return "Open buffer picker.";
            case "bd":
            case "bdelete":
                return "Delete current buffer.";
            case "set":
                return "Set runtime option (use :set! key=value to persist).";
            case "settings":
            case "shedrc":
                return "Open global settings file.";
            case "config":
                return "Open settings or persist with :config save.";
            case "log":
            case "commandlog":
                return "Open command log scratch buffer.";
            case "session":
            case "sessions":
                return "Save/load/list named sessions.";
            case "workspace":
            case "ws":
                return "Save/load/list workspace profiles (layout + UI settings).";
            case "jobs":
                return "Show async job list.";
            case "jobcancel":
            case "jobkill":
                return "Cancel async job by id.";
            case "drop":
                return "Run async command against current file path.";
            case "task":
                return "Run project tasks (:task test/build) with quickfix integration.";
            case "help":
            case "h":
                return "Open help text (topic optional).";
            case "wc":
            case "wordcount":
                return "Show line/word/character counts.";
            case "recent":
                return "Show recent files scratch buffer.";
            case "d":
            case "delete":
                return "Delete current line or a range.";
            case "files":
                return "Open project file finder.";
            case "folder":
            case "folders":
                return "Pick folder, then open file picker.";
            case "split":
            case "sp":
                return "Create horizontal split.";
            case "vsplit":
            case "vsp":
                return "Create vertical split.";
            case "close":
            case "clo":
                return "Close active split/window.";
            case "tree":
                return "Open tree pane and perform file operations.";
            case "git":
                return "Run integrated git subcommands.";
            case "grep":
            case "rg":
                return "Search project text and populate quickfix.";
            case "copen":
                return "Open quickfix list.";
            case "cclose":
                return "Close quickfix list.";
            case "cnext":
            case "cn":
                return "Jump to next quickfix entry.";
            case "cprev":
            case "cp":
                return "Jump to previous quickfix entry.";
            case "cfirst":
                return "Jump to first quickfix entry.";
            case "clast":
                return "Jump to last quickfix entry.";
            case "cc":
                return "Jump to selected quickfix entry.";
            case "lsp":
                return "Run LSP actions and server management.";
            case "definition":
                return "Jump to symbol definition.";
            case "hover":
                return "Show hover docs in scratch buffer.";
            case "references":
                return "Find references and open quickfix.";
            case "diagnostics":
            case "diag":
            case "ldiag":
                return "Push diagnostics into quickfix.";
            case "dnext":
            case "dn":
                return "Jump to next diagnostic.";
            case "dprev":
            case "dp":
                return "Jump to previous diagnostic.";
            case "symbols":
            case "sym":
                return "Open symbol picker and jump by class/function/heading.";
            case "registers":
            case "reg":
                return "Show register contents.";
            case "yankring":
            case "pastepicker":
            case "yr":
                return "Pick from yank/delete history and paste.";
            case "marks":
                return "Show mark list for active buffer.";
            case "themes":
                return "Show and switch built-in themes.";
            case "theater":
                return "Apply dramatic UI preset: off/subtle/full.";
            case "zen":
                return "Toggle centered zen layout.";
            case "minimap":
                return "Toggle minimap side panel.";
            case "normal":
            case "norm":
                return "Execute normal-mode keys on current/ranged lines.";
            case "reload":
            case "source":
                return "Reload ~/.shed/shedrc from disk.";
            case "clean":
            case "shedclean":
                return "Remove Shed metadata files.";
            case "noh":
            case "nohlsearch":
                return "Clear search highlights.";
            case "plugin":
            case "plugins":
                return "Manage plugins and package install/update/pin flows.";
            case "palette":
            case "commands":
                return "Open command palette.";
            case "undolist":
            case "undotree":
                return "Show undo history.";
            case "wa":
            case "wall":
                return "Write all modified buffers.";
            case "qa":
            case "qall":
                return "Quit all buffers/windows.";
            case "wqa":
            case "wqall":
            case "xa":
            case "xall":
                return "Write all buffers, then quit all.";
            case "toc":
                return "Open markdown table of contents.";
            case "outline":
                return "Open markdown outline split.";
            case "toggle":
            case "checkbox":
                return "Toggle markdown checkbox under cursor.";
            case "table":
                return "Insert/align/sort/edit markdown table.";
            case "link":
                return "Insert markdown link template.";
            case "img":
            case "image":
                return "Insert markdown image template.";
            case "snippets":
            case "snippet":
                return "List snippets for current file type.";
            case "bracketcolor":
            case "bracketcolors":
                return "Toggle bracket pair colorization.";
            case "term":
            case "terminal":
                return "Open an integrated shell split.";
            case "conceal":
            case "conceallevel":
                return "Set markdown conceal level (0/1/2).";
            default:
                return "Run command :" + base;
        }
    }

    String describeGrepCandidate(String selection) {
        if (selection == null || selection.isBlank()) {
            return "No match selected.";
        }
        String[] parts = selection.split(":", 3);
        if (parts.length >= 3) {
            return "Open " + parts[0] + " line " + parts[1] + "\n" + parts[2];
        }
        return selection;
    }

    String showPaletteDialog(String title, List<String> candidates) {
        return showPaletteDialog(title, candidates, null);
    }

    void animatePaletteDialogOpen(JDialog dialog, Dimension targetSize) {
        if (!dramaticCommandPaletteEnabled || !dramaticMotionAllowed()) {
            return;
        }
        int steps = Math.max(5, Math.min(12, dramaticAnimationMs / 20));
        int startWidth = Math.max(420, (int) Math.round(targetSize.width * 0.88));
        int startHeight = Math.max(260, (int) Math.round(targetSize.height * 0.88));
        Point target = dialog.getLocation();
        int dx = (targetSize.width - startWidth) / 2;
        int dy = 18;
        dialog.setSize(startWidth, startHeight);
        dialog.setLocation(target.x + dx, target.y + dy);
        Timer timer = new Timer(animationDelayForSteps(steps), null);
        final int[] tick = new int[] {0};
        timer.addActionListener(ev -> {
            double t = easeOut((double) tick[0] / steps);
            int width = (int) Math.round(startWidth + (targetSize.width - startWidth) * t);
            int height = (int) Math.round(startHeight + (targetSize.height - startHeight) * t);
            int x = target.x + (targetSize.width - width) / 2;
            int y = target.y + (int) Math.round(dy * (1.0 - t));
            dialog.setSize(width, height);
            dialog.setLocation(x, y);
            tick[0]++;
            if (tick[0] > steps) {
                timer.stop();
                dialog.setSize(targetSize);
                dialog.setLocation(target);
            }
        });
        timer.start();
    }

    String showPaletteDialog(String title, List<String> candidates, PalettePreviewProvider previewProvider) {
        // undecorated modal dialog styled as floating picker
        JDialog dialog = new JDialog(this, title, true);
        dialog.setUndecorated(true);
        dialog.getRootPane().setBorder(javax.swing.BorderFactory.createLineBorder(configManager.getCaretColor(), 1));
        dialog.setLayout(new BorderLayout(6, 6));
        dialog.getContentPane().setBackground(configManager.getCommandBarBackground());
        JTextField filterField = new JTextField();
        filterField.setFont(writingArea.getFont());
        filterField.setBackground(configManager.getCommandBarBackground());
        filterField.setForeground(configManager.getCommandBarForeground());
        filterField.setCaretColor(configManager.getCaretColor());
        filterField.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, configManager.getCaretColor()),
            javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String candidate : candidates) model.addElement(candidate);
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(writingArea.getFont());
        list.setBackground(configManager.getCommandBarBackground());
        list.setForeground(configManager.getCommandBarForeground());
        list.setSelectionBackground(configManager.getSelectionColor());
        list.setSelectionForeground(configManager.getSelectionTextColor());
        if (!model.isEmpty()) list.setSelectedIndex(0);
        JLabel titleLabel = new JLabel(" " + title);
        titleLabel.setForeground(configManager.getCaretColor());
        titleLabel.setFont(writingArea.getFont().deriveFont(java.awt.Font.BOLD));
        titleLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 2, 6));
        JTextArea previewArea = new JTextArea();
        previewArea.setEditable(false);
        previewArea.setLineWrap(true);
        previewArea.setWrapStyleWord(true);
        previewArea.setFocusable(false);
        previewArea.setPreferredSize(new Dimension(260, 320));
        previewArea.setFont(writingArea.getFont().deriveFont(Math.max(11f, writingArea.getFont().getSize2D() - 1f)));
        previewArea.setBackground(configManager.getStatusBarBackground());
        previewArea.setForeground(configManager.getStatusBarForeground());
        previewArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, blendColor(configManager.getCaretColor(), configManager.getCommandBarBackground(), 0.45)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        previewArea.setVisible(previewProvider != null && dramaticCommandPaletteEnabled);
        final Runnable syncPreview = () -> {
            String value = list.getSelectedValue();
            if (previewProvider == null) {
                previewArea.setText(value == null ? "" : value);
                return;
            }
            String preview = previewProvider.preview(value);
            previewArea.setText(preview == null ? "" : preview);
            previewArea.setCaretPosition(0);
        };
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refilter(); }
            public void removeUpdate(DocumentEvent e) { refilter(); }
            public void changedUpdate(DocumentEvent e) { refilter(); }
            private void refilter() {
                String query = filterField.getText();
                model.clear();
                if (query.isEmpty()) { for (String c2 : candidates) model.addElement(c2); }
                else { for (String m : fuzzyMatchService.matchStrings(query, candidates, 0)) model.addElement(m); }
                if (!model.isEmpty()) list.setSelectedIndex(0);
                syncPreview.run();
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncPreview.run();
            }
        });
        final String[] selection = new String[1];
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) { selection[0] = list.getSelectedValue(); dialog.dispose(); } }
        });
        filterField.addActionListener(e -> { selection[0] = list.getSelectedValue(); dialog.dispose(); });
        filterField.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) dialog.dispose();
                else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN) { int idx = list.getSelectedIndex(); if (idx < model.getSize() - 1) list.setSelectedIndex(idx + 1); e.consume(); }
                else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_UP) { int idx = list.getSelectedIndex(); if (idx > 0) list.setSelectedIndex(idx - 1); e.consume(); }
            }
        });
        dialog.add(titleLabel, BorderLayout.NORTH);
        dialog.add(filterField, BorderLayout.CENTER);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(600, 320));
        sp.setBorder(null);
        dialog.add(sp, BorderLayout.SOUTH);
        dialog.add(previewArea, BorderLayout.EAST);
        syncPreview.run();
        Dimension targetSize = dramaticCommandPaletteEnabled ? new Dimension(720, 420) : new Dimension(620, 400);
        dialog.setSize(targetSize);
        dialog.setLocationRelativeTo(this);
        animatePaletteDialogOpen(dialog, targetSize);
        playCue(CueType.SUCCESS);
        dialog.setVisible(true);
        return selection[0];
    }

    boolean shouldSkipHiddenPath(File file) {
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

    String trimForRegisterDisplay(String value) {
        String singleLine = value.replace("\n", "\\n");
        if (singleLine.length() > 80) {
            return singleLine.substring(0, 77) + "...";
        }
        return singleLine;
    }

    String describeOffset(int offset) {
        try {
            int line = writingArea.getLineOfOffset(Math.min(offset, writingArea.getText().length()));
            int col = offset - writingArea.getLineStartOffset(line);
            return (line + 1) + ":" + (col + 1);
        } catch (BadLocationException e) {
            return "1:1";
        }
    }

    MinimapPanel activeMinimapPanel;
    public String toggleMinimap() {
        EditorPane pane = getActivePane();
        if (pane == null) return "No active pane";
        JScrollPane sp = pane.getScrollPane();
        if (activeMinimapPanel != null && activeMinimapPanel.getParent() != null) {
            MinimapPanel panelToRemove = activeMinimapPanel;
            java.awt.Container parent = panelToRemove.getParent();
            Runnable removePanel = () -> {
                if (parent != null) {
                    parent.remove(panelToRemove);
                    parent.revalidate();
                    parent.repaint();
                }
                if (activeMinimapPanel == panelToRemove) {
                    activeMinimapPanel = null;
                }
                sp.revalidate();
                sp.repaint();
            };
            animateMinimapWidth(panelToRemove, panelToRemove.getPixelWidth(), 0, removePanel);
            animateEditorHostTint(configManager.getCommandColor());
            return "Minimap hidden";
        }
        activeMinimapPanel = new MinimapPanel(pane.getTextArea());
        activeMinimapPanel.setColors(configManager.getLineNumberBackground(), configManager.getEditorForeground());
        int initialWidth = dramaticPanelAnimationsEnabled && dramaticMotionAllowed() ? 0 : dramaticMinimapWidth;
        activeMinimapPanel.setPixelWidth(initialWidth);
        java.awt.Container parent = sp.getParent();
        if (parent instanceof JPanel) {
            ((JPanel) parent).add(activeMinimapPanel, BorderLayout.EAST);
        } else {
            // wrap the scroll pane
            JPanel wrapper = new JPanel(new BorderLayout());
            if (parent != null) {
                int idx = -1;
                for (int i = 0; i < parent.getComponentCount(); i++) {
                    if (parent.getComponent(i) == sp) { idx = i; break; }
                }
                if (idx >= 0) parent.remove(idx);
                wrapper.add(sp, BorderLayout.CENTER);
                wrapper.add(activeMinimapPanel, BorderLayout.EAST);
                if (idx >= 0) parent.add(wrapper, idx);
            }
        }
        sp.getViewport().addChangeListener(e -> { if (activeMinimapPanel != null) activeMinimapPanel.repaint(); });
        sp.revalidate();
        sp.repaint();
        animateMinimapWidth(activeMinimapPanel, initialWidth, dramaticMinimapWidth, null);
        animateEditorHostTint(configManager.getVisualColor());
        return "Minimap shown";
    }

    public String toggleZenMode() {
        zenModeEnabled = !zenModeEnabled;
        updateZenModeLayout();
        return zenModeEnabled ? "Zen mode enabled" : "Zen mode disabled";
    }

    void updateZenModeLayout() {
        Color editorBackground = getModeBackground(editorState.mode == null ? EditorMode.NORMAL : editorState.mode);
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

    Color fadedMarginColor(Color base) {
        return blendColor(base, configManager.getEditorForeground(), 0.12);
    }

    Color blendColor(Color base, Color overlay, double ratio) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        int r = (int) Math.round(base.getRed() * (1.0 - clamped) + overlay.getRed() * clamped);
        int g = (int) Math.round(base.getGreen() * (1.0 - clamped) + overlay.getGreen() * clamped);
        int b = (int) Math.round(base.getBlue() * (1.0 - clamped) + overlay.getBlue() * clamped);
        return new Color(r, g, b);
    }

    void refreshDramaticSettings() {
        boolean wasEnabled = dramaticUiEnabled;
        dramaticUiEnabled = configManager.getDramaticUiEnabled();
        dramaticIdentityEnabled = dramaticUiEnabled && configManager.getDramaticIdentityEnabled();
        dramaticModeTransitionsEnabled = dramaticUiEnabled && configManager.getDramaticModeTransitionsEnabled();
        dramaticCommandPaletteEnabled = dramaticUiEnabled && configManager.getDramaticCommandPaletteEnabled();
        dramaticEditingFeedbackEnabled = dramaticUiEnabled && configManager.getDramaticEditingFeedbackEnabled();
        dramaticPanelAnimationsEnabled = dramaticUiEnabled && configManager.getDramaticPanelAnimationsEnabled();
        dramaticSoundEnabled = dramaticUiEnabled && configManager.getDramaticSoundEnabled();
        dramaticSoundPack = configManager.getDramaticSoundPack();
        dramaticSoundVolume = configManager.getDramaticSoundVolume();
        dramaticSoundModeCueEnabled = configManager.getDramaticSoundModeCueEnabled();
        dramaticSoundNavigateCueEnabled = configManager.getDramaticSoundNavigateCueEnabled();
        dramaticSoundSuccessCueEnabled = configManager.getDramaticSoundSuccessCueEnabled();
        dramaticSoundErrorCueEnabled = configManager.getDramaticSoundErrorCueEnabled();
        dramaticReducedMotionEnabled = configManager.getDramaticReducedMotionEnabled();
        dramaticPerformanceGuardrailsEnabled = configManager.getDramaticPerformanceGuardrailsEnabled();
        dramaticPerformanceCpuThreshold = Math.max(0.1, Math.min(1.0, configManager.getDramaticPerformanceCpuThreshold()));
        dramaticPerformanceLineThreshold = configManager.getDramaticPerformanceLineThreshold();
        dramaticAnimationMs = Math.max(80, configManager.getDramaticAnimationMs());
        dramaticMinimapWidth = Math.max(40, configManager.getDramaticMinimapWidth());
        whichKeyHintsEnabled = configManager.getWhichKeyHintsEnabled();
        if (wasEnabled && !dramaticUiEnabled) {
            if (modeTransitionTimer != null) modeTransitionTimer.stop();
            if (feedbackPulseTimer != null) feedbackPulseTimer.stop();
            if (hostTintTimer != null) hostTintTimer.stop();
            if (splitAnimationTimer != null) splitAnimationTimer.stop();
            if (minimapWidthTimer != null) minimapWidthTimer.stop();
            modeTransitionTimer = null;
            feedbackPulseTimer = null;
            hostTintTimer = null;
            splitAnimationTimer = null;
            minimapWidthTimer = null;
            clearFeedbackPulse();
        }
    }

    boolean dramaticMotionAllowed() {
        return dramaticUiEnabled
            && !dramaticReducedMotionEnabled
            && dramaticAnimationMs > 0
            && !isDramaticPerformanceThrottled();
    }

    boolean isDramaticPerformanceThrottled() {
        if (!dramaticPerformanceGuardrailsEnabled) {
            return false;
        }
        FileBuffer buffer = getCurrentBuffer();
        if (buffer != null && buffer.isLargeFile()) {
            return true;
        }
        if (writingArea != null && writingArea.getLineCount() >= dramaticPerformanceLineThreshold) {
            return true;
        }
        double cpuLoad = cachedProcessCpuLoad();
        return cpuLoad >= 0.0 && cpuLoad >= dramaticPerformanceCpuThreshold;
    }

    double cachedProcessCpuLoad() {
        long now = System.currentTimeMillis();
        if (now - cachedProcessCpuLoadAtMillis < 1200) {
            return cachedProcessCpuLoad;
        }
        cachedProcessCpuLoadAtMillis = now;
        cachedProcessCpuLoad = readProcessCpuLoad();
        return cachedProcessCpuLoad;
    }

    double readProcessCpuLoad() {
        try {
            Object osBean = ManagementFactory.getOperatingSystemMXBean();
            Method method = osBean.getClass().getMethod("getProcessCpuLoad");
            method.setAccessible(true);
            Object value = method.invoke(osBean);
            if (value instanceof Number) {
                double load = ((Number) value).doubleValue();
                if (load >= 0.0 && load <= 1.0) {
                    return load;
                }
            }
        } catch (Exception ignored) {
        }
        return -1.0;
    }

    int animationDelayForSteps(int steps) {
        return Math.max(12, dramaticAnimationMs / Math.max(1, steps));
    }

    double easeOut(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        double inverse = 1.0 - clamped;
        return 1.0 - inverse * inverse * inverse;
    }

    void applyDramaticFooterStyling() {
        if (statusBar == null || commandBar == null || editorState == null) {
            return;
        }
        Color baseStatus = configManager.getStatusBarBackground();
        Color baseCommand = configManager.getCommandBarBackground();
        Color modeAccent = getModeBackground(editorState.mode == null ? EditorMode.NORMAL : editorState.mode);

        if (dramaticIdentityEnabled) {
            Color statusBg = blendColor(baseStatus, modeAccent, 0.20);
            Color commandBg = blendColor(baseCommand, modeAccent, 0.15);
            statusBar.setBackground(statusBg);
            commandBar.setBackground(commandBg);
            statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, modeAccent),
                BorderFactory.createEmptyBorder(5, 8, 5, 10)
            ));
            commandBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, blendColor(modeAccent, configManager.getEditorForeground(), 0.45)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)
            ));
            if (writingArea != null) {
                Font baseFont = writingArea.getFont();
                statusBar.setFont(baseFont.deriveFont(Font.BOLD, baseFont.getSize2D() + 1.0f));
                commandBar.setFont(baseFont.deriveFont(Font.PLAIN, baseFont.getSize2D()));
            }
            return;
        }

        statusBar.setBackground(baseStatus);
        commandBar.setBackground(baseCommand);
        statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        commandBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        if (writingArea != null) {
            Font baseFont = writingArea.getFont();
            statusBar.setFont(baseFont.deriveFont(Font.PLAIN, baseFont.getSize2D()));
            commandBar.setFont(baseFont.deriveFont(Font.PLAIN, baseFont.getSize2D()));
        }
    }

    void animateModeTransition(EditorMode fromMode, EditorMode toMode) {
        if (!dramaticModeTransitionsEnabled) {
            return;
        }
        if (fromMode == toMode || writingArea == null) {
            return;
        }
        playCue(CueType.MODE_CHANGE);
        Color fromColor = getModeBackground(fromMode == null ? toMode : fromMode);
        Color toColor = getModeBackground(toMode);
        if (!dramaticMotionAllowed()) {
            writingArea.setBackground(toColor);
            applyDramaticFooterStyling();
            return;
        }

        if (modeTransitionTimer != null) {
            modeTransitionTimer.stop();
        }

        int steps = Math.max(6, Math.min(20, dramaticAnimationMs / 14));
        final int[] tick = new int[] {0};
        modeTransitionTimer = new Timer(animationDelayForSteps(steps), ev -> {
            double t = easeOut((double) tick[0] / steps);
            Color blended = blendColor(fromColor, toColor, t);
            writingArea.setBackground(blended);
            if (editorHostPanel != null) {
                editorHostPanel.setBackground(zenModeEnabled ? fadedMarginColor(blended) : blended);
                editorHostPanel.repaint();
            }
            tick[0]++;
            if (tick[0] > steps) {
                modeTransitionTimer.stop();
                modeTransitionTimer = null;
                updateZenModeLayout();
                applyDramaticFooterStyling();
            }
        });
        modeTransitionTimer.start();
    }

    void clearFeedbackPulse() {
        if (feedbackPulseTag != null && writingArea != null) {
            writingArea.getHighlighter().removeHighlight(feedbackPulseTag);
            feedbackPulseTag = null;
        }
    }

    void pulseCaretLine(Color color) {
        if (!dramaticEditingFeedbackEnabled || writingArea == null) {
            return;
        }
        if (feedbackPulseTimer != null) {
            feedbackPulseTimer.stop();
        }
        clearFeedbackPulse();

        int line;
        int start;
        int end;
        try {
            line = Math.max(0, writingArea.getLineOfOffset(writingArea.getCaretPosition()));
            start = writingArea.getLineStartOffset(line);
            end = writingArea.getLineEndOffset(line);
        } catch (BadLocationException e) {
            return;
        }

        if (!dramaticMotionAllowed()) {
            try {
                feedbackPulseTag = writingArea.getHighlighter().addHighlight(start, end, new DefaultHighlighter.DefaultHighlightPainter(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80)));
            } catch (BadLocationException ignored) {
                return;
            }
            Timer cleanup = new Timer(140, ev -> {
                clearFeedbackPulse();
                ((Timer) ev.getSource()).stop();
            });
            cleanup.setRepeats(false);
            cleanup.start();
            return;
        }

        int steps = Math.max(5, Math.min(14, dramaticAnimationMs / 18));
        final int[] tick = new int[] {0};
        feedbackPulseTimer = new Timer(animationDelayForSteps(steps), ev -> {
            clearFeedbackPulse();
            double progress = (double) tick[0] / steps;
            int alpha = (int) Math.round(130 * (1.0 - progress));
            alpha = Math.max(0, Math.min(255, alpha));
            try {
                feedbackPulseTag = writingArea.getHighlighter().addHighlight(
                    start,
                    end,
                    new DefaultHighlighter.DefaultHighlightPainter(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha))
                );
            } catch (BadLocationException ignored) {
            }
            tick[0]++;
            if (tick[0] > steps) {
                feedbackPulseTimer.stop();
                feedbackPulseTimer = null;
                clearFeedbackPulse();
            }
        });
        feedbackPulseTimer.start();
    }

    void animateEditorHostTint(Color tint) {
        if (!dramaticPanelAnimationsEnabled || editorHostPanel == null || tint == null) {
            return;
        }
        if (hostTintTimer != null) {
            hostTintTimer.stop();
        }
        if (!dramaticMotionAllowed()) {
            editorHostPanel.setBackground(blendColor(editorHostPanel.getBackground(), tint, 0.20));
            editorHostPanel.repaint();
            return;
        }

        final Color base = editorHostPanel.getBackground();
        int steps = Math.max(6, Math.min(20, dramaticAnimationMs / 14));
        final int[] tick = new int[] {0};
        hostTintTimer = new Timer(animationDelayForSteps(steps), ev -> {
            double progress = (double) tick[0] / steps;
            double ratio = 0.30 * (1.0 - progress);
            editorHostPanel.setBackground(blendColor(base, tint, ratio));
            editorHostPanel.repaint();
            tick[0]++;
            if (tick[0] > steps) {
                hostTintTimer.stop();
                hostTintTimer = null;
                updateZenModeLayout();
            }
        });
        hostTintTimer.start();
    }

    void animateSplitForPane(EditorPane pane, double startRatio, double targetRatio) {
        if (!dramaticPanelAnimationsEnabled || pane == null || windowLayoutRoot == null) {
            return;
        }
        if (!dramaticMotionAllowed()) {
            return;
        }
        if (splitAnimationTimer != null) {
            splitAnimationTimer.stop();
        }

        int steps = Math.max(5, Math.min(16, dramaticAnimationMs / 16));
        final int[] tick = new int[] {0};
        final double delta = (targetRatio - startRatio) / Math.max(1, steps);
        splitAnimationTimer = new Timer(animationDelayForSteps(steps), ev -> {
            boolean changed = windowLayoutRoot.adjustRatio(pane, delta);
            if (changed) {
                renderWindowLayout();
            }
            tick[0]++;
            if (tick[0] > steps || !changed) {
                splitAnimationTimer.stop();
                splitAnimationTimer = null;
            }
        });
        splitAnimationTimer.start();
    }

    void animateMinimapWidth(MinimapPanel panel, int fromWidth, int toWidth, Runnable onFinish) {
        if (panel == null) {
            if (onFinish != null) {
                onFinish.run();
            }
            return;
        }
        if (minimapWidthTimer != null) {
            minimapWidthTimer.stop();
        }
        if (!dramaticPanelAnimationsEnabled || !dramaticMotionAllowed()) {
            panel.setPixelWidth(toWidth);
            if (onFinish != null) {
                onFinish.run();
            }
            return;
        }
        int steps = Math.max(5, Math.min(14, dramaticAnimationMs / 18));
        final int[] tick = new int[] {0};
        minimapWidthTimer = new Timer(animationDelayForSteps(steps), ev -> {
            double t = easeOut((double) tick[0] / steps);
            int width = (int) Math.round(fromWidth + (toWidth - fromWidth) * t);
            panel.setPixelWidth(width);
            tick[0]++;
            if (tick[0] > steps) {
                minimapWidthTimer.stop();
                minimapWidthTimer = null;
                panel.setPixelWidth(toWidth);
                if (onFinish != null) {
                    onFinish.run();
                }
            }
        });
        minimapWidthTimer.start();
    }

    void clearPaneJumpFlash() {
        if (paneJumpFlashTarget != null && paneJumpFlashTarget.getScrollPane() != null) {
            paneJumpFlashTarget.getScrollPane().setBorder(paneJumpFlashOriginalBorder);
            paneJumpFlashTarget.getScrollPane().revalidate();
            paneJumpFlashTarget.getScrollPane().repaint();
        }
        paneJumpFlashTarget = null;
        paneJumpFlashOriginalBorder = null;
    }

    void flashPaneJump(EditorPane pane) {
        if (!dramaticPanelAnimationsEnabled || pane == null || pane.getScrollPane() == null) {
            return;
        }
        if (paneJumpFlashTimer != null) {
            paneJumpFlashTimer.stop();
            paneJumpFlashTimer = null;
        }
        clearPaneJumpFlash();

        JScrollPane scrollPane = pane.getScrollPane();
        paneJumpFlashTarget = pane;
        paneJumpFlashOriginalBorder = scrollPane.getBorder();
        Color accent = blendColor(configManager.getCaretColor(), configManager.getSelectionColor(), 0.35);
        animateEditorHostTint(accent);

        if (!dramaticMotionAllowed()) {
            scrollPane.setBorder(BorderFactory.createLineBorder(accent, 2));
            paneJumpFlashTimer = new Timer(120, ev -> {
                clearPaneJumpFlash();
                paneJumpFlashTimer.stop();
                paneJumpFlashTimer = null;
            });
            paneJumpFlashTimer.setRepeats(false);
            paneJumpFlashTimer.start();
            return;
        }

        int steps = Math.max(4, Math.min(12, dramaticAnimationMs / 20));
        final int[] tick = new int[] {0};
        paneJumpFlashTimer = new Timer(animationDelayForSteps(steps), ev -> {
            double t = (double) tick[0] / steps;
            int alpha = (int) Math.round((1.0 - t) * 180);
            alpha = Math.max(0, Math.min(255, alpha));
            scrollPane.setBorder(BorderFactory.createLineBorder(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), alpha), 2));
            tick[0]++;
            if (tick[0] > steps) {
                paneJumpFlashTimer.stop();
                paneJumpFlashTimer = null;
                clearPaneJumpFlash();
            }
        });
        paneJumpFlashTimer.start();
    }

    void playCue(CueType cueType) {
        if (!dramaticSoundEnabled) {
            return;
        }
        if (dramaticSoundVolume <= 0) {
            return;
        }
        if (cueType == CueType.MODE_CHANGE && !dramaticSoundModeCueEnabled) {
            return;
        }
        if (cueType == CueType.NAVIGATE && !dramaticSoundNavigateCueEnabled) {
            return;
        }
        if (cueType == CueType.SUCCESS && !dramaticSoundSuccessCueEnabled) {
            return;
        }
        if (cueType == CueType.ERROR && !dramaticSoundErrorCueEnabled) {
            return;
        }

        int[] pattern = cuePattern(cueType);
        for (int delay : pattern) {
            Timer beep = new Timer(Math.max(0, delay), ev -> {
                Toolkit.getDefaultToolkit().beep();
                ((Timer) ev.getSource()).stop();
            });
            beep.setRepeats(false);
            beep.start();
        }
    }

    int[] cuePattern(CueType cueType) {
        String pack = dramaticSoundPack == null ? "default" : dramaticSoundPack;
        int[] base;
        switch (pack) {
            case "soft":
                switch (cueType) {
                    case MODE_CHANGE: base = new int[] {0}; break;
                    case NAVIGATE: base = new int[] {0}; break;
                    case SUCCESS: base = new int[] {0, 80}; break;
                    case ERROR: base = new int[] {0, 120}; break;
                    default: base = new int[] {0}; break;
                }
                break;
            case "cinema":
            case "dramatic":
                switch (cueType) {
                    case MODE_CHANGE: base = new int[] {0, 35}; break;
                    case NAVIGATE: base = new int[] {0, 45}; break;
                    case SUCCESS: base = new int[] {0, 60, 120}; break;
                    case ERROR: base = new int[] {0, 60, 120, 180}; break;
                    default: base = new int[] {0}; break;
                }
                break;
            default:
                switch (cueType) {
                    case MODE_CHANGE: base = new int[] {0}; break;
                    case NAVIGATE: base = new int[] {0}; break;
                    case SUCCESS: base = new int[] {0, 70}; break;
                    case ERROR: base = new int[] {0, 90, 180}; break;
                    default: base = new int[] {0}; break;
                }
                break;
        }
        int maxBeeps;
        if (dramaticSoundVolume >= 80) {
            maxBeeps = base.length;
        } else if (dramaticSoundVolume >= 50) {
            maxBeeps = Math.max(1, base.length - 1);
        } else {
            maxBeeps = 1;
        }
        int[] limited = new int[maxBeeps];
        System.arraycopy(base, 0, limited, 0, maxBeeps);
        return limited;
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

    String getWordAtCaret() {
        return lspController.getWordAtCaret();
    }

    public String showLspCompletionStatus() {
        return lspController.showLspCompletionStatus();
    }

    public String handleLspCommand(String argument) {
        return lspController.handleLspCommand(argument);
    }

    public String lspStatus() {
        return lspController.lspStatus();
    }

    public String lspRestart(String ext) {
        return lspController.lspRestart(ext);
    }

    public String lspStop(String ext) {
        return lspController.lspStop(ext);
    }

    public String lspServers() {
        return lspController.lspServers();
    }

    public String lspLog() {
        return lspController.lspLog();
    }

    String currentBufferExtension() {
        return lspController.currentBufferExtension();
    }

    public String lspGoToDefinition() {
        return lspController.lspGoToDefinition();
    }

    public String lspHover() {
        return lspController.lspHover();
    }

    public String lspReferences() {
        return lspController.lspReferences();
    }

    public String lspRename(String newName) {
        return lspController.lspRename(newName);
    }

    public String lspRenameApply() {
        return lspController.lspRenameApply();
    }

    public String lspRenameCancel() {
        return lspController.lspRenameCancel();
    }

    String buildLspRenamePreview(String targetName, List<LspClient.TextEdit> edits) {
        return lspController.buildLspRenamePreview(targetName, edits);
    }

    public String lspCodeActions(String selectionArgument) {
        return lspController.lspCodeActions(selectionArgument);
    }

    List<LspClient.CodeAction> collectCursorCodeActions(LspClient client, String uri, int line, int column) {
        return lspController.collectCursorCodeActions(client, uri, line, column);
    }

    int parseOneBasedIndex(String value) {
        return lspController.parseOneBasedIndex(value);
    }

    public String showDiagnostics() {
        return lspController.showDiagnostics();
    }

    public String diagnosticsNext() {
        return lspController.diagnosticsNext();
    }

    public String diagnosticsPrev() {
        return lspController.diagnosticsPrev();
    }

    String jumpDiagnostic(boolean forward) {
        return lspController.jumpDiagnostic(forward);
    }

    List<QuickfixService.Entry> diagnosticsToQuickfixEntries(String filePath, List<LspClient.Diagnostic> diagnostics) {
        return lspController.diagnosticsToQuickfixEntries(filePath, diagnostics);
    }

    String diagnosticSeverityLabel(int severity) {
        return lspController.diagnosticSeverityLabel(severity);
    }

    String openLspLocation(LspClient.Location location, String label) {
        return lspController.openLspLocation(location, label);
    }

    WorkspaceEditApplyResult applyWorkspaceTextEdits(List<LspClient.TextEdit> edits) {
        return lspController.applyWorkspaceTextEdits(edits);
    }

    int applyTextEditsToCurrentArea(List<LspClient.TextEdit> edits) {
        return lspController.applyTextEditsToCurrentArea(edits);
    }

    int applyTextEditsToBuffer(FileBuffer buffer, List<LspClient.TextEdit> edits) {
        return lspController.applyTextEditsToBuffer(buffer, edits);
    }

    int applyTextEditsToFile(String filePath, List<LspClient.TextEdit> edits) {
        return lspController.applyTextEditsToFile(filePath, edits);
    }

    String applyResolvedTextEdits(String text, List<ResolvedTextEdit> resolvedEdits) {
        return lspController.applyResolvedTextEdits(text, resolvedEdits);
    }

    List<ResolvedTextEdit> resolveTextEdits(String text, List<LspClient.TextEdit> edits) {
        return lspController.resolveTextEdits(text, edits);
    }

    List<Integer> lineStartOffsets(String text) {
        return lspController.lineStartOffsets(text);
    }

    int offsetForLineCharacter(String text, List<Integer> lineStarts, int line, int character) {
        return lspController.offsetForLineCharacter(text, lineStarts, line, character);
    }

    String filePathFromUri(String uri) {
        return lspController.filePathFromUri(uri);
    }

    String currentCompletionPrefix() {
        return lspController.currentCompletionPrefix();
    }

    List<String> collectBufferCompletions(String prefix) {
        return lspController.collectBufferCompletions(prefix);
    }

    void addCompletionCandidate(String prefix, java.util.LinkedHashSet<String> unique, String candidate) {
        lspController.addCompletionCandidate(prefix, unique, candidate);
    }

    void applyCompletion(String prefix, String completion) {
        lspController.applyCompletion(prefix, completion);
    }

    LspClient resolveLspClient(FileBuffer buffer) {
        return lspController.resolveLspClient(buffer);
    }

    void syncLspOpen(FileBuffer buffer) {
        lspController.syncLspOpen(buffer);
    }

    void syncLspChange(FileBuffer buffer) {
        lspController.syncLspChange(buffer);
    }

    void scheduleDiagnosticRefresh() {
        lspController.scheduleDiagnosticRefresh();
    }

    public void notifyCurrentBufferSaved() {
        lspController.notifyCurrentBufferSaved();
    }

    void pollLspNotifications(FileBuffer buffer) {
        lspController.pollLspNotifications(buffer);
    }

    LspClient existingLspClient(FileBuffer buffer) {
        return lspController.existingLspClient(buffer);
    }

    String bufferUri(FileBuffer buffer) {
        return lspController.bufferUri(buffer);
    }

    String languageId(FileBuffer buffer) {
        return lspController.languageId(buffer);
    }

    String bufferExtension(FileBuffer buffer) {
        return lspController.bufferExtension(buffer);
    }

    String[] builtinLspCommand(String extension) {
        return lspController.builtinLspCommand(extension);
    }

    // Repeat last command
    void repeatLastCommand() {
        if (lastCommand.isEmpty()) {
            showMessage("No command to repeat");
            return;
        }

        switch (lastCommand) {
            case "dd":
                storeDelete(null, clipboardManager.deleteLine(writingArea), true);
                markModified();
                break;
            case "yy":
                storeYank(null, clipboardManager.yankLine(writingArea), true);
                break;
            case "dw":
                storeDelete(null, clipboardManager.deleteWord(writingArea), false);
                markModified();
                break;
            case "cw":
                storeDelete(null, clipboardManager.deleteWord(writingArea), false);
                markModified();
                insertLastText();
                break;
            case "cc":
            case "S":
                storeDelete(null, clipboardManager.deleteLine(writingArea), true);
                markModified();
                insertLastText();
                break;
            case "D":
                storeDelete(null, clipboardManager.deleteToEndOfLine(writingArea), false);
                markModified();
                break;
            case "C":
                storeDelete(null, clipboardManager.deleteToEndOfLine(writingArea), false);
                markModified();
                insertLastText();
                break;
            case "x":
                storeDelete(null, clipboardManager.deleteChar(writingArea), false);
                markModified();
                break;
            default:
                // Handle operator+motion (d$, y}, etc.) and operator+textobject (diw, ca(, etc.)
                if (lastCommand.length() >= 2) {
                    char op = lastCommand.charAt(0);
                    String target = lastCommand.substring(1);
                    if (op == 'd' || op == 'y' || op == 'c') {
                        if (target.length() == 2 && (target.charAt(0) == 'i' || target.charAt(0) == 'a')) {
                            showMessage(applyTextObjectOperator(op, target.charAt(0), target.charAt(1)));
                        } else {
                            showMessage(applyMotionOperator(op, target));
                        }
                        if (op == 'c') {
                            insertLastText();
                        }
                        break;
                    }
                }
                showMessage("Repeated: " + lastCommand);
                return;
        }
        showMessage("Repeated: " + lastCommand);
    }

    void insertLastText() {
        if (lastInsertedText != null && !lastInsertedText.isEmpty()) {
            int pos = writingArea.getCaretPosition();
            writingArea.insert(lastInsertedText, pos);
            writingArea.setCaretPosition(pos + lastInsertedText.length());
            markModified();
        }
    }

    int consumePendingCount() {
        if (editorState.pendingCount == null || editorState.pendingCount.isEmpty()) {
            return 1;
        }
        int count = Integer.parseInt(editorState.pendingCount);
        editorState.pendingCount = "";
        return Math.max(1, count);
    }

    void repeatAction(int count, Runnable action) {
        for (int i = 0; i < Math.max(1, count); i++) {
            action.run();
        }
    }

    Character consumePendingRegister() {
        Character register = editorState.pendingRegister;
        editorState.pendingRegister = null;
        return register;
    }

    void storeYank(Character register, String text, boolean lineWise) {
        RegisterContent content = lineWise ? RegisterContent.lineWise(text) : RegisterContent.characterWise(text);
        registerManager.setYank(register, content);
        addToYankRing(content);
    }

    void storeDelete(Character register, String text, boolean lineWise) {
        RegisterContent content = lineWise ? RegisterContent.lineWise(text) : RegisterContent.characterWise(text);
        registerManager.setDelete(register, content);
        addToYankRing(content);
    }

    void addToYankRing(RegisterContent content) {
        if (content == null || content.isMacro()) {
            return;
        }
        String text = content.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        yankRing.removeIf(existing -> existing != null
            && !existing.isMacro()
            && existing.isLineWise() == content.isLineWise()
            && text.equals(existing.getText()));
        yankRing.add(0, content);
        while (yankRing.size() > 80) {
            yankRing.remove(yankRing.size() - 1);
        }
    }

    public String showYankRingPicker() {
        if (yankRing.isEmpty()) {
            return "Yank ring empty";
        }
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < yankRing.size(); i++) {
            RegisterContent content = yankRing.get(i);
            String kind = content.isLineWise() ? "[L]" : "[C]";
            candidates.add(String.format("%02d %s %s", i + 1, kind, safePreviewText(content.getText(), 100)));
        }
        String selected = showPaletteDialog("Yank Ring", candidates,
            value -> value == null ? "" : "Enter to paste selected ring entry");
        if (selected == null || selected.isBlank()) {
            return "Yank ring cancelled";
        }
        int index = candidates.indexOf(selected);
        if (index < 0 || index >= yankRing.size()) {
            return "Invalid yank ring selection";
        }
        RegisterContent content = yankRing.get(index);
        clipboardManager.pasteContent(writingArea, content.getText(), content.isLineWise(), false);
        markModified();
        return "Pasted yank ring item " + (index + 1);
    }

    String pasteFromRegister(boolean before) {
        RegisterContent content = registerManager.get(consumePendingRegister());
        if (content == null || content.getText().isEmpty()) {
            return "Register empty";
        }
        clipboardManager.pasteContent(writingArea, content.getText(), content.isLineWise(), before);
        markModified();
        return "Pasted";
    }

    String playMacro(Character register) {
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

    String yankToEndOfLine() {
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

    String replaceCharacter(char replacement) {
        int caret = writingArea.getCaretPosition();
        String text = writingArea.getText();
        if (caret >= text.length()) {
            return "No character to replace";
        }
        writingArea.replaceRange(String.valueOf(replacement), caret, caret + 1);
        markModified();
        return "Replaced character";
    }

    String applyMotionOperator(char operator, String motion) {
        MotionRange range = resolveMotionRange(motion);
        return applyResolvedRange(operator, range, motion);
    }

    String applyTextObjectOperator(char operator, char modifier, char objectKey) {
        MotionRange range = resolveTextObjectRange(modifier, objectKey);
        return applyResolvedRange(operator, range, String.valueOf(modifier) + objectKey);
    }

    String applyResolvedRange(char operator, MotionRange range, String label) {
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

    MotionRange resolveMotionRange(String motion) {
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

    MotionRange resolveTextObjectRange(char modifier, char objectKey) {
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

    String handleSurroundPending(char c) {
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

    boolean isTextObjectKey(char c) {
        return "wWps\"'`()[]{}<>".indexOf(c) >= 0;
    }

    String surroundChange(char oldChar, char newChar) {
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

    String surroundDelete(char target) {
        MotionRange range = resolveSurroundRange(target);
        if (range == null) {
            return "No matching surround found";
        }
        writingArea.replaceRange("", range.end - 1, range.end);
        writingArea.replaceRange("", range.start, range.start + 1);
        markModified();
        return "Surround deleted";
    }

    String surroundAdd(char targetObject, char surroundChar) {
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

    MotionRange resolveSurroundRange(char surround) {
        if (surround == '"' || surround == '\'' || surround == '`') {
            return resolveQuoteObject(true, surround);
        }
        SurroundPair pair = surroundPair(surround);
        if (pair == null) {
            return null;
        }
        return resolveBracketObject(true, pair.open, pair.close);
    }

    SurroundPair surroundPair(char surround) {
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

    MotionRange resolveWordObject(boolean around, boolean bigWord) {
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

    MotionRange resolveParagraphObject(boolean around) {
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

    MotionRange resolveSentenceObject(boolean around) {
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

    MotionRange resolveQuoteObject(boolean around, char quote) {
        String text = writingArea.getText();
        int caret = writingArea.getCaretPosition();
        int start = text.lastIndexOf(quote, Math.max(0, caret - 1));
        int end = text.indexOf(quote, caret);
        if (start < 0 || end < 0 || start == end) {
            return null;
        }
        return around ? new MotionRange(start, end + 1, false) : new MotionRange(start + 1, end, false);
    }

    MotionRange resolveBracketObject(boolean around, char open, char close) {
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

    int previewMotionTarget(String motion) {
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
    void setMode(EditorMode mode) {
        EditorMode oldMode = this.editorState.mode;
        if ((oldMode == EditorMode.VISUAL || oldMode == EditorMode.VISUAL_LINE) && mode != EditorMode.VISUAL && mode != EditorMode.VISUAL_LINE) {
            editorState.lastVisualStart = writingArea.getSelectionStart();
            editorState.lastVisualEnd = writingArea.getSelectionEnd();
            editorState.lastVisualMode = oldMode;
        }
        this.editorState.mode = mode;
        writingArea.setEditable(mode.isEditable());
        writingArea.setBackground(getModeBackground(mode));
        updateZenModeLayout();
        if (mode != EditorMode.COMMAND) {
            clearSubstitutePreview();
        }
        updateStatusBar();
        if (oldMode != mode) {
            animateModeTransition(oldMode, mode);
            firePluginEvent("ModeChange");
        }
    }

    Color getModeBackground(EditorMode mode) {
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
    void updateStatusBar() {
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

        String breadcrumb = findCurrentBreadcrumb();
        if (breadcrumb != null && !breadcrumb.isBlank()) {
            status.append(breadcrumb).append("  ");
        }

        EditorMode modeForStatus = editorState.mode == null ? EditorMode.NORMAL : editorState.mode;
        status.append(modeForStatus.getDisplayName()).append("  ");
        if (dramaticUiEnabled && isDramaticPerformanceThrottled()) {
            status.append("dramatic:throttled").append("  ");
        }

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

        String inlinePeek = inlinePeekMessage(buffer);
        if ((editorState.mode == EditorMode.COMMAND || editorState.mode == EditorMode.SEARCH) && !editorState.commandBuffer.isEmpty()) {
            commandBar.setText(editorState.commandBuffer);
        } else if (lastMessage != null && !lastMessage.isEmpty()) {
            commandBar.setText(lastMessage);
        } else if (inlinePeek != null) {
            commandBar.setText(inlinePeek);
        } else {
            String blame = getGitBlameForCurrentLine(buffer);
            commandBar.setText(blame != null ? blame : "");
        }
        applyDramaticFooterStyling();
    }

    String inlinePeekMessage(FileBuffer buffer) {
        String quickfixPeek = quickfixInlinePeek();
        if (quickfixPeek != null) {
            return quickfixPeek;
        }
        String diagnosticPeek = diagnosticInlinePeek(buffer);
        if (diagnosticPeek != null) {
            return diagnosticPeek;
        }
        return null;
    }

    String quickfixInlinePeek() {
        if (!isQuickfixBufferActive()) {
            return null;
        }
        int line = getCurrentCaretLine() + 1;
        QuickfixService.Entry entry = quickfixService.atLine(line);
        if (entry == null) {
            return "quickfix: no entry on current line";
        }
        String source = entry.getSource() == null || entry.getSource().isBlank() ? "qf" : entry.getSource();
        String fileName = entry.getFilePath() == null ? "" : new File(entry.getFilePath()).getName();
        String location = fileName.isEmpty() ? "" : fileName + ":" + entry.getLine() + ":" + entry.getColumn() + " ";
        return ("peek [" + source + "] " + location + safePreviewText(entry.getMessage(), 120)).trim();
    }

    String diagnosticInlinePeek(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return null;
        }
        LspClient client = existingLspClient(buffer);
        if (client == null) {
            return null;
        }
        List<LspClient.Diagnostic> diagnostics = client.getDiagnostics(bufferUri(buffer));
        if (diagnostics == null || diagnostics.isEmpty()) {
            return null;
        }
        int caretLine = getCurrentCaretLine();
        LspClient.Diagnostic best = null;
        for (LspClient.Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null || diagnostic.getLine() != caretLine) {
                continue;
            }
            if (best == null || diagnostic.getSeverity() < best.getSeverity()) {
                best = diagnostic;
            }
        }
        if (best == null) {
            return null;
        }
        String severity = diagnosticSeverityLabel(best.getSeverity()).toLowerCase(Locale.ROOT);
        return "peek [diag " + severity + "] " + safePreviewText(best.getMessage(), 120);
    }

    String safePreviewText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    void appendLspStatus(StringBuilder status, FileBuffer buffer) {
        LspClient client = existingLspClient(buffer);
        if (client == null || !buffer.hasFilePath()) {
            return;
        }
        List<LspClient.Diagnostic> diagnosticEntries = client.getDiagnostics(bufferUri(buffer));
        if (diagnosticEntries.isEmpty()) {
            return;
        }
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        for (LspClient.Diagnostic diagnostic : diagnosticEntries) {
            if (diagnostic == null) {
                continue;
            }
            switch (diagnostic.getSeverity()) {
                case 1:
                    errors++;
                    break;
                case 2:
                    warnings++;
                    break;
                case 3:
                case 4:
                default:
                    infos++;
                    break;
            }
        }
        status.append("diag:");
        if (errors > 0) {
            status.append("E").append(errors);
        }
        if (warnings > 0) {
            if (errors > 0) {
                status.append("/");
            }
            status.append("W").append(warnings);
        }
        if (errors == 0 && warnings == 0) {
            status.append(diagnosticEntries.size());
        } else if (infos > 0) {
            status.append("+").append(infos);
        }
        status.append("  ");
    }

    void handleDocumentChange() {
        paneBufferController.handleDocumentChange();
    }

    void withSuppressedDocumentEvents(Runnable action) {
        paneBufferController.withSuppressedDocumentEvents(action);
    }

    void detachActiveDocumentListener() {
        paneBufferController.detachActiveDocumentListener();
    }

    void attachActiveDocumentListener() {
        paneBufferController.attachActiveDocumentListener();
    }

    void loadBufferIntoEditor(FileBuffer buffer) {
        paneBufferController.loadBufferIntoEditor(buffer);
    }

    void loadBufferIntoPane(EditorPane pane, FileBuffer buffer, int caretPosition) {
        paneBufferController.loadBufferIntoPane(pane, buffer, caretPosition);
    }

    void persistCurrentBufferState() {
        paneBufferController.persistCurrentBufferState();
    }

    // Mark buffer as modified
    void markModified() {
        paneBufferController.markModified();
    }

    void updateDiffGutter(FileBuffer buffer) {
        paneBufferController.updateDiffGutter(buffer);
    }

    // Show message in status bar
    public void showMessage(String message) {
        lastMessage = message == null ? "" : message;
        if (!lastMessage.isEmpty()) {
            String normalized = lastMessage.toLowerCase();
            if (normalized.startsWith("error") || normalized.startsWith("invalid") || normalized.contains(" failed")) {
                playCue(CueType.ERROR);
            } else if (normalized.contains("opened") || normalized.contains("saved") || normalized.contains("loaded")) {
                playCue(CueType.SUCCESS);
            }
        }
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
    void openFileChooser() {
        paneBufferController.openFileChooser();
    }

    void openLandingPage() {
        paneBufferController.openLandingPage();
    }

    public void openFile(File file) throws IOException {
        paneBufferController.openFile(file);
    }

    // Buffer management methods (called by CommandHandler)
    public FileBuffer getCurrentBuffer() {
        return paneBufferController.getCurrentBuffer();
    }

    public JTextArea getTextArea() {
        return paneBufferController.getTextArea();
    }

    public String nextBuffer() {
        return paneBufferController.nextBuffer();
    }

    public String prevBuffer() {
        return paneBufferController.prevBuffer();
    }

    public String listBuffers() {
        return paneBufferController.listBuffers();
    }

    public String deleteBuffer(boolean force) {
        return paneBufferController.deleteBuffer(force);
    }

    void switchToBuffer(int index) {
        paneBufferController.switchToBuffer(index);
    }

    public String splitWindow(boolean vertical) {
        return paneBufferController.splitWindow(vertical);
    }

    public String closeActiveWindow() {
        return paneBufferController.closeActiveWindow();
    }

    String closePane(EditorPane paneToClose) {
        return paneBufferController.closePane(paneToClose);
    }

    public String cycleWindowFocus() {
        return paneBufferController.cycleWindowFocus();
    }

    public String resizeActiveWindow(double delta) {
        return paneBufferController.resizeActiveWindow(delta);
    }

    public String equalizeWindows() {
        return paneBufferController.equalizeWindows();
    }

    public String focusWindowDirection(int dx, int dy) {
        return paneBufferController.focusWindowDirection(dx, dy);
    }

    WindowLayoutNode.Direction toLayoutDirection(int dx, int dy) {
        return paneBufferController.toLayoutDirection(dx, dy);
    }

    double directionalAlignmentScore(Rectangle activeBounds, Rectangle candidateBounds, WindowLayoutNode.Direction direction) {
        return paneBufferController.directionalAlignmentScore(activeBounds, candidateBounds, direction);
    }

    Rectangle paneBounds(EditorPane pane) {
        return paneBufferController.paneBounds(pane);
    }

    FileBuffer findBufferByPath(File file) {
        return paneBufferController.findBufferByPath(file);
    }

    boolean shouldReplaceSingleLandingBuffer() {
        return paneBufferController.shouldReplaceSingleLandingBuffer();
    }

    // Search methods
    public String search(String pattern) {
        recordJumpPosition();
        String result = searchManager.searchForward(pattern);
        if (!configManager.getHighlightSearch()) {
            searchManager.clearHighlights();
        }
        if (result.startsWith("Match")) {
            pulseCaretLine(blendColor(configManager.getSelectionColor(), configManager.getCaretColor(), 0.35));
        }
        return result;
    }

    public String searchBackward(String pattern) {
        recordJumpPosition();
        String result = searchManager.searchBackward(pattern);
        if (!configManager.getHighlightSearch()) {
            searchManager.clearHighlights();
        }
        if (result.startsWith("Match")) {
            pulseCaretLine(blendColor(configManager.getSelectionColor(), configManager.getCaretColor(), 0.35));
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
            pulseCaretLine(configManager.getSubstitutePreviewColor());
            return "Replaced " + result.matchCount + " occurrence" + (result.matchCount == 1 ? "" : "s");
        } else {
            return substituteCurrentLine(pattern, replacement, replaceAll);
        }
    }

    String substituteCurrentLine(String pattern, String replacement, boolean replaceAll) {
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
            pulseCaretLine(configManager.getSubstitutePreviewColor());

            return "Replaced " + result.matchCount + " occurrence" + (result.matchCount == 1 ? "" : "s");
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }

    ReplacementResult replaceLiteral(String text, String pattern, String replacement, boolean replaceAll) {
        SubstituteService.Result r = substituteService.replaceRegex(text, pattern, replacement, replaceAll);
        return new ReplacementResult(r.getUpdatedText(), r.getMatchCount(), r.getFirstMatchOffset());
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

    public void setWrap(boolean enabled) {
        writingArea.setLineWrap(enabled);
        writingArea.setWrapStyleWord(enabled);
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

    public List<String> getThemeIdsForPlugins() {
        return configManager.getThemeIds();
    }

    public Map<String, String> getActiveThemePaletteHex() {
        Map<String, String> palette = new LinkedHashMap<>();
        palette.put("theme", configManager.getThemeId());
        palette.put("color.normal", colorToHex(configManager.getNormalColor()));
        palette.put("color.insert", colorToHex(configManager.getInsertColor()));
        palette.put("color.command", colorToHex(configManager.getCommandColor()));
        palette.put("color.visual", colorToHex(configManager.getVisualColor()));
        palette.put("color.replace", colorToHex(configManager.getReplaceColor()));
        palette.put("ui.foreground", colorToHex(configManager.getEditorForeground()));
        palette.put("ui.caret", colorToHex(configManager.getCaretColor()));
        palette.put("ui.selection", colorToHex(configManager.getSelectionColor()));
        palette.put("ui.selection.text", colorToHex(configManager.getSelectionTextColor()));
        palette.put("ui.status.background", colorToHex(configManager.getStatusBarBackground()));
        palette.put("ui.status.foreground", colorToHex(configManager.getStatusBarForeground()));
        palette.put("ui.command.background", colorToHex(configManager.getCommandBarBackground()));
        palette.put("ui.command.foreground", colorToHex(configManager.getCommandBarForeground()));
        palette.put("ui.linenumber.background", colorToHex(configManager.getLineNumberBackground()));
        palette.put("ui.linenumber.foreground", colorToHex(configManager.getLineNumberForeground()));
        palette.put("ui.currentline", colorToHex(configManager.getCurrentLineHighlightColor()));
        palette.put("ui.syntax.keyword", colorToHex(configManager.getSyntaxKeywordColor()));
        palette.put("ui.syntax.string", colorToHex(configManager.getSyntaxStringColor()));
        palette.put("ui.syntax.comment", colorToHex(configManager.getSyntaxCommentColor()));
        palette.put("ui.syntax.type", colorToHex(configManager.getSyntaxTypeColor()));
        palette.put("ui.syntax.function", colorToHex(configManager.getSyntaxFunctionColor()));
        palette.put("ui.syntax.constant", colorToHex(configManager.getSyntaxConstantColor()));
        palette.put("ui.syntax.annotation", colorToHex(configManager.getSyntaxAnnotationColor()));
        palette.put("ui.syntax.number", colorToHex(configManager.getSyntaxNumberColor()));
        return palette;
    }

    public String resolveCommandAlias(String command) {
        return configManager.resolveCommandAlias(command);
    }

    public String setThemeFromCommand(String value) {
        String appliedTheme = configManager.setTheme(value);
        if (appliedTheme == null) {
            return "Unknown theme: " + value;
        }
        applyThemeColors();
        firePluginEvent("ThemeChange");
        return "Theme set to " + appliedTheme;
    }

    public String applyThemeFromPlugin(String value, boolean persist) {
        String appliedTheme = configManager.setTheme(value);
        if (appliedTheme == null) {
            return "Unknown theme: " + value;
        }
        if (persist) {
            try {
                configManager.setAndPersist("theme", appliedTheme);
            } catch (IOException e) {
                return "Error saving theme: " + e.getMessage();
            }
        }
        applyThemeColors();
        firePluginEvent("ThemeChange");
        return persist ? "Theme set and saved to " + appliedTheme : "Theme set to " + appliedTheme;
    }

    public String applyPaletteOverridesFromPlugin(Map<String, String> overrides, boolean persist) {
        if (overrides == null || overrides.isEmpty()) {
            return "No palette overrides";
        }
        int applied = 0;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String mappedKey = mapPaletteAliasToConfigKey(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (mappedKey == null || value.isEmpty()) {
                continue;
            }
            if (!HEX_COLOR_VALUE_PATTERN.matcher(value).matches()) {
                continue;
            }
            configManager.set(mappedKey, value);
            applied++;
        }
        if (applied == 0) {
            return "No valid palette keys/colors";
        }
        applyRuntimeConfigFromSettings();
        if (persist) {
            try {
                configManager.persistCurrentConfig();
            } catch (IOException e) {
                return "Applied " + applied + " palette key(s), but failed to save: " + e.getMessage();
            }
        }
        firePluginEvent("ThemeChange");
        return (persist ? "Applied and saved " : "Applied ") + applied + " palette key" + (applied == 1 ? "" : "s");
    }

    String mapPaletteAliasToConfigKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "normal": return "color.normal";
            case "insert": return "color.insert";
            case "command": return "color.command";
            case "visual": return "color.visual";
            case "replace": return "color.replace";
            case "foreground": return "ui.foreground";
            case "caret": return "ui.caret";
            case "selection": return "ui.selection";
            case "selection_text":
            case "selectiontext": return "ui.selection.text";
            case "status_bg":
            case "statusbar_bg": return "ui.status.background";
            case "status_fg":
            case "statusbar_fg": return "ui.status.foreground";
            case "command_bg":
            case "commandbar_bg": return "ui.command.background";
            case "command_fg":
            case "commandbar_fg": return "ui.command.foreground";
            case "line_number_bg":
            case "linenumber_bg": return "ui.linenumber.background";
            case "line_number_fg":
            case "linenumber_fg": return "ui.linenumber.foreground";
            case "current_line":
            case "currentline": return "ui.currentline";
            case "syntax_keyword": return "ui.syntax.keyword";
            case "syntax_string": return "ui.syntax.string";
            case "syntax_comment": return "ui.syntax.comment";
            case "syntax_type": return "ui.syntax.type";
            case "syntax_function": return "ui.syntax.function";
            case "syntax_constant": return "ui.syntax.constant";
            case "syntax_annotation": return "ui.syntax.annotation";
            case "syntax_number": return "ui.syntax.number";
            default:
                if (key.startsWith("color.") || key.startsWith("ui.")) {
                    return key;
                }
                return null;
        }
    }

    String colorToHex(Color color) {
        if (color == null) {
            return "#000000";
        }
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    public String setConfigOption(String key, String value) {
        if (key == null || key.isEmpty()) {
            return "Error: Missing config key";
        }
        configManager.set(key, value == null ? "" : value);
        applyRuntimeConfigFromSettings();
        if (isThemeRelatedConfigKey(key)) {
            firePluginEvent("ThemeChange");
        }
        return "Set " + key;
    }

    public String setConfigOptionPersistent(String key, String value) {
        if (key == null || key.isEmpty()) {
            return "Error: Missing config key";
        }
        try {
            configManager.setAndPersist(key, value == null ? "" : value);
            applyRuntimeConfigFromSettings();
            if (isThemeRelatedConfigKey(key)) {
                firePluginEvent("ThemeChange");
            }
            return "Set and saved " + key;
        } catch (IOException e) {
            return "Error saving config: " + e.getMessage();
        }
    }

    boolean isThemeRelatedConfigKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("theme")
            || normalized.startsWith("color.")
            || normalized.startsWith("ui.");
    }

    public String saveConfigToDisk() {
        try {
            int persisted = configManager.persistCurrentConfig();
            return "Saved config (" + persisted + " key" + (persisted == 1 ? "" : "s") + ")";
        } catch (IOException e) {
            return "Error saving config: " + e.getMessage();
        }
    }

    public String applyTheaterPreset(String presetArgument) {
        String preset = presetArgument == null ? "" : presetArgument.trim().toLowerCase(Locale.ROOT);
        if (preset.isEmpty()) {
            return "Usage: :theater off|subtle|full";
        }

        if ("off".equals(preset)) {
            configManager.set("ui.dramatic", "false");
            applyRuntimeConfigFromSettings();
            return "Theater preset applied: off";
        }

        if ("subtle".equals(preset)) {
            configManager.set("ui.dramatic", "true");
            configManager.set("ui.dramatic.identity", "true");
            configManager.set("ui.dramatic.mode.transitions", "true");
            configManager.set("ui.dramatic.command.palette", "true");
            configManager.set("ui.dramatic.editing.feedback", "true");
            configManager.set("ui.dramatic.panel.animations", "false");
            configManager.set("ui.dramatic.sound", "false");
            configManager.set("ui.dramatic.sound.pack", "soft");
            configManager.set("ui.dramatic.sound.volume", "40");
            configManager.set("ui.dramatic.reduced.motion", "false");
            configManager.set("ui.dramatic.animation.ms", "140");
            applyRuntimeConfigFromSettings();
            return "Theater preset applied: subtle";
        }

        if ("full".equals(preset)) {
            configManager.set("ui.dramatic", "true");
            configManager.set("ui.dramatic.identity", "true");
            configManager.set("ui.dramatic.mode.transitions", "true");
            configManager.set("ui.dramatic.command.palette", "true");
            configManager.set("ui.dramatic.editing.feedback", "true");
            configManager.set("ui.dramatic.panel.animations", "true");
            configManager.set("ui.dramatic.sound", "true");
            configManager.set("ui.dramatic.sound.pack", "cinema");
            configManager.set("ui.dramatic.sound.volume", "85");
            configManager.set("ui.dramatic.reduced.motion", "false");
            configManager.set("ui.dramatic.animation.ms", "240");
            applyRuntimeConfigFromSettings();
            return "Theater preset applied: full";
        }

        return "Unknown theater preset: " + preset + " (expected off|subtle|full)";
    }

    public String reloadConfigFromDisk() {
        configManager.reload();
        applyRuntimeConfigFromSettings();
        return "Settings reloaded";
    }

    public String reloadConfigIfSettingsBuffer(FileBuffer buffer) {
        return reloadConfigIfSettingsBuffer(buffer, null, null);
    }

    public String reloadConfigIfSettingsBuffer(FileBuffer buffer, String previousContent, String updatedContent) {
        if (buffer == null || buffer.getFile() == null) {
            return null;
        }
        if (!isSettingsFile(buffer.getFile())) {
            return null;
        }
        boolean canDetectThemeChange = previousContent != null && updatedContent != null;
        boolean themeChangedInFile = canDetectThemeChange && didConfigKeyChange(previousContent, updatedContent, "theme");
        String activeThemeBeforeReload = configManager.getThemeId();
        configManager.reload();
        if (canDetectThemeChange && !themeChangedInFile) {
            configManager.setTheme(activeThemeBeforeReload);
        }
        applyRuntimeConfigFromSettings();
        return "Settings reloaded";
    }

    boolean isSettingsFile(File file) {
        if (file == null) {
            return false;
        }
        try {
            File settings = new File(configManager.getConfigPath());
            return file.getCanonicalFile().equals(settings.getCanonicalFile());
        } catch (IOException e) {
            return file.getAbsolutePath().equals(new File(configManager.getConfigPath()).getAbsolutePath());
        }
    }

    boolean didConfigKeyChange(String previousContent, String updatedContent, String key) {
        String previousValue = extractConfigValue(previousContent, key);
        String updatedValue = extractConfigValue(updatedContent, key);
        return !Objects.equals(previousValue, updatedValue);
    }

    String extractConfigValue(String content, String key) {
        if (content == null || key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = key.trim();
        String[] lines = content.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String parsedKey = trimmed.substring(0, separator).trim();
            if (!parsedKey.equalsIgnoreCase(normalizedKey)) {
                continue;
            }
            return trimmed.substring(separator + 1).trim();
        }
        return null;
    }

    void applyRuntimeConfigFromSettings() {
        refreshDramaticSettings();
        lineNumberMode = configManager.getLineNumberMode();
        Font editorFont = resolveEditorFont();
        int tabSize = Math.max(1, configManager.getTabSize());
        for (EditorPane pane : editorPanes) {
            JTextArea area = pane.getTextArea();
            area.setFont(editorFont);
            area.setTabSize(tabSize);
            pane.getScrollPane().getVerticalScrollBar().setUnitIncrement(Math.max(16, area.getFontMetrics(area.getFont()).getHeight()));
        }
        if (!configManager.getHighlightSearch() && searchManager != null) {
            searchManager.clearHighlights();
        }
        applyThemeColors();
        refreshLineNumberPanel();
        updateCurrentLineHighlight();
        if (activeMinimapPanel != null) {
            activeMinimapPanel.setPixelWidth(dramaticMinimapWidth);
        }
        updateStatusBar();
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
            ensureStoreDirectory(commandLogStore);
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

    public String cleanShedDataFiles() {
        Path root = new File(configManager.getShedDirectoryPath()).toPath();
        if (!Files.exists(root)) {
            return "No Shed data found: " + root.toAbsolutePath();
        }
        int deleted = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                if (path.equals(root)) {
                    continue;
                }
                if (Files.deleteIfExists(path)) {
                    deleted++;
                }
            }
            Files.createDirectories(root);
            recentFiles.clear();
            commandHistory.clear();
            commandHistoryIndex = -1;
            commandHistoryPrefix = "";
            reloadConfigFromDisk();
            return "Cleaned Shed data: " + deleted + " path(s)";
        } catch (IOException e) {
            return "Shed clean failed: " + e.getMessage();
        }
    }

    public String handleSessionCommand(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            return "Usage: :session save [name] | load[!] [name] | list";
        }
        int split = trimmed.indexOf(' ');
        String subcommand = split < 0 ? trimmed.toLowerCase() : trimmed.substring(0, split).toLowerCase();
        String args = split < 0 ? "" : trimmed.substring(split + 1).trim();
        switch (subcommand) {
            case "save":
                return saveSession(args);
            case "load":
                return loadSession(args, false);
            case "load!":
                return loadSession(args, true);
            case "list":
                return listSessions();
            default:
                return "Usage: :session save [name] | load[!] [name] | list";
        }
    }

    public String handleWorkspaceProfileCommand(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            return "Usage: :workspace save [name] | load[!] [name] | list";
        }
        int split = trimmed.indexOf(' ');
        String subcommand = split < 0 ? trimmed.toLowerCase(Locale.ROOT) : trimmed.substring(0, split).toLowerCase(Locale.ROOT);
        String args = split < 0 ? "" : trimmed.substring(split + 1).trim();
        String profileName = args.isBlank() ? defaultWorkspaceProfileName() : sanitizeSessionName(args);
        String sessionName = WORKSPACE_PROFILE_PREFIX + profileName;
        switch (subcommand) {
            case "save":
                return saveSession(sessionName);
            case "load":
                return loadSession(sessionName, false);
            case "load!":
                return loadSession(sessionName, true);
            case "list":
                return listWorkspaceProfiles();
            default:
                return "Usage: :workspace save [name] | load[!] [name] | list";
        }
    }

    String saveSession(String nameArgument) {
        File sessionFile = resolveSessionFile(nameArgument);
        File sessionDir = sessionFile.getParentFile();
        if (sessionDir != null && !sessionDir.exists()) {
            sessionDir.mkdirs();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 2);
        payload.put("cwd", new File(".").getAbsolutePath());
        Map<FileBuffer, String> bufferIds = new HashMap<>();
        List<Map<String, Object>> serializedBuffers = serializeSessionBuffers(bufferIds);
        payload.put("buffers", serializedBuffers);
        payload.put("panes", serializeSessionPanes(bufferIds));
        payload.put("layout", serializeWindowLayout(windowLayoutRoot));
        payload.put("activePaneIndex", activePaneIndex);
        FileBuffer current = getCurrentBuffer();
        if (current != null) {
            String activeBufferId = bufferIds.get(current);
            if (activeBufferId != null) {
                payload.put("activeBufferId", activeBufferId);
            }
            payload.put("activeCaret", writingArea.getCaretPosition());
        }
        if (treeRoot != null) {
            payload.put("treeRoot", treeRoot.getAbsolutePath());
        }
        payload.put("uiSettings", captureSessionUiSettings());
        payload.put("savedAt", commandLogTimeFormat.format(LocalDateTime.now()));
        try {
            Files.writeString(sessionFile.toPath(),
                MiniJson.stringify(payload),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            return "Session saved: " + sessionFile.getAbsolutePath();
        } catch (IOException e) {
            return "Session save failed: " + e.getMessage();
        }
    }

    String loadSession(String nameArgument, boolean force) {
        File sessionFile = resolveSessionFile(nameArgument);
        if (!sessionFile.exists()) {
            return "Session not found: " + sessionFile.getAbsolutePath();
        }
        if (!force && hasUnsavedChangesInAnyBuffer()) {
            return "Unsaved buffers exist (use :session load! <name>)";
        }

        try {
            String json = Files.readString(sessionFile.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> payload = MiniJson.asObject(MiniJson.parse(json));
            if (payload == null) {
                return "Session file is invalid";
            }
            if (restoreSessionV2(payload)) {
                return "Restored session: " + sessionFile.getAbsolutePath();
            }
            if (restoreLegacySession(payload)) {
                return "Restored legacy session: " + sessionFile.getAbsolutePath();
            }
            openLandingPage();
            return "Session loaded (no existing files)";
        } catch (IOException e) {
            return "Session load failed: " + e.getMessage();
        }
    }

    boolean restoreSessionV2(Map<String, Object> payload) {
        Map<String, FileBuffer> idToBuffer = new HashMap<>();
        List<FileBuffer> restoredBuffers = deserializeSessionBuffers(payload.get("buffers"), idToBuffer);
        if (restoredBuffers.isEmpty()) {
            return false;
        }

        specialBufferReturns.clear();
        treeLineTargets.clear();
        treeBuffer = null;
        treePane = null;
        quickfixBuffer = null;
        buffers.clear();
        buffers.addAll(restoredBuffers);

        List<Object> paneObjects = MiniJson.asArray(payload.get("panes"));
        int paneCount = paneObjects == null || paneObjects.isEmpty() ? 1 : paneObjects.size();
        Map<String, Object> layoutObject = MiniJson.asObject(payload.get("layout"));
        resetEditorPanesForSession(paneCount, layoutObject);
        if (editorPanes.isEmpty()) {
            return false;
        }

        FileBuffer defaultBuffer = buffers.get(0);
        for (int i = 0; i < editorPanes.size(); i++) {
            EditorPane pane = editorPanes.get(i);
            FileBuffer paneBuffer = defaultBuffer;
            int caret = 0;
            if (paneObjects != null && i < paneObjects.size()) {
                Map<String, Object> paneState = MiniJson.asObject(paneObjects.get(i));
                if (paneState != null) {
                    String bufferId = MiniJson.asString(paneState.get("bufferId"));
                    Integer paneCaret = MiniJson.asInt(paneState.get("caret"));
                    if (bufferId != null && idToBuffer.containsKey(bufferId)) {
                        paneBuffer = idToBuffer.get(bufferId);
                    }
                    if (paneCaret != null) {
                        caret = Math.max(0, paneCaret);
                    }
                }
            }
            loadBufferIntoPane(pane, paneBuffer, caret);
        }

        Integer activePane = MiniJson.asInt(payload.get("activePaneIndex"));
        int activeIndex = activePane == null ? 0 : Math.max(0, Math.min(activePane, editorPanes.size() - 1));
        activateEditorPane(editorPanes.get(activeIndex));

        String activeBufferId = MiniJson.asString(payload.get("activeBufferId"));
        if (activeBufferId != null && idToBuffer.containsKey(activeBufferId)) {
            loadBufferIntoEditor(idToBuffer.get(activeBufferId));
        }
        Integer activeCaret = MiniJson.asInt(payload.get("activeCaret"));
        if (activeCaret != null) {
            writingArea.setCaretPosition(Math.min(Math.max(0, activeCaret), writingArea.getText().length()));
        }

        String savedTreeRoot = MiniJson.asString(payload.get("treeRoot"));
        if (savedTreeRoot != null && !savedTreeRoot.isBlank()) {
            File root = new File(savedTreeRoot);
            treeRoot = root.exists() ? root : null;
        } else {
            treeRoot = null;
        }
        applySessionUiSettings(MiniJson.asObject(payload.get("uiSettings")));
        return true;
    }

    boolean restoreLegacySession(Map<String, Object> payload) {
        List<String> filePaths = extractSessionFilePaths(payload.get("files"));
        if (filePaths.isEmpty()) {
            return false;
        }
        String activePath = MiniJson.asString(payload.get("activePath"));
        Integer activeCaret = MiniJson.asInt(payload.get("activeCaret"));
        String savedTreeRoot = MiniJson.asString(payload.get("treeRoot"));

        specialBufferReturns.clear();
        treeLineTargets.clear();
        treeBuffer = null;
        treePane = null;
        quickfixBuffer = null;
        buffers.clear();

        for (String filePath : filePaths) {
            if (filePath == null || filePath.isBlank()) {
                continue;
            }
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                continue;
            }
            try {
                buffers.add(new FileBuffer(file, configManager));
            } catch (IOException ignored) {
            }
        }
        if (buffers.isEmpty()) {
            return false;
        }

        resetEditorPanesForSession(1, null);
        FileBuffer primary = buffers.get(0);
        loadBufferIntoPane(editorPanes.get(0), primary, 0);
        FileBuffer target = primary;
        if (activePath != null && !activePath.isBlank()) {
            FileBuffer maybe = findBufferByPath(new File(activePath));
            if (maybe != null) {
                target = maybe;
            }
        }
        loadBufferIntoEditor(target);
        if (activeCaret != null) {
            writingArea.setCaretPosition(Math.min(Math.max(0, activeCaret), writingArea.getText().length()));
        }
        if (savedTreeRoot != null && !savedTreeRoot.isBlank()) {
            File root = new File(savedTreeRoot);
            treeRoot = root.exists() ? root : null;
        } else {
            treeRoot = null;
        }
        return true;
    }

    Map<String, Object> captureSessionUiSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        String[] keys = {
            "theme",
            "ui.dramatic",
            "ui.dramatic.identity",
            "ui.dramatic.mode.transitions",
            "ui.dramatic.command.palette",
            "ui.dramatic.editing.feedback",
            "ui.dramatic.panel.animations",
            "ui.dramatic.sound",
            "ui.dramatic.sound.pack",
            "ui.dramatic.sound.volume",
            "ui.dramatic.reduced.motion",
            "ui.dramatic.animation.ms",
            "minimap",
            "ui.whichkey.hints"
        };
        for (String key : keys) {
            String value = configManager.get(key);
            if (value != null) {
                settings.put(key, value);
            }
        }
        return settings;
    }

    void applySessionUiSettings(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = entry.getValue();
            configManager.set(key, value == null ? "" : String.valueOf(value));
        }
        applyRuntimeConfigFromSettings();
    }

    List<Map<String, Object>> serializeSessionBuffers(Map<FileBuffer, String> bufferIds) {
        List<Map<String, Object>> entries = new ArrayList<>();
        int scratchIndex = 1;
        for (FileBuffer buffer : buffers) {
            if (buffer == null) {
                continue;
            }
            String id;
            if (buffer.hasFilePath()) {
                id = "file:" + buffer.getFilePath();
            } else {
                id = "scratch:" + scratchIndex++;
            }
            bufferIds.put(buffer, id);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            if (buffer.hasFilePath()) {
                entry.put("type", "file");
                entry.put("path", buffer.getFilePath());
                entry.put("modified", buffer.isModified());
                if (buffer.isModified()) {
                    entry.put("content", buffer.getContent());
                }
            } else {
                entry.put("type", "scratch");
                entry.put("name", buffer.getDisplayName());
                entry.put("content", buffer.getContent());
                entry.put("modified", buffer.isModified());
            }
            entries.add(entry);
        }
        return entries;
    }

    List<Map<String, Object>> serializeSessionPanes(Map<FileBuffer, String> bufferIds) {
        List<Map<String, Object>> panes = new ArrayList<>();
        for (EditorPane pane : editorPanes) {
            if (pane == null) {
                continue;
            }
            Map<String, Object> state = new LinkedHashMap<>();
            String bufferId = bufferIds.get(pane.getBuffer());
            if (bufferId != null) {
                state.put("bufferId", bufferId);
            }
            state.put("caret", pane.getTextArea().getCaretPosition());
            panes.add(state);
        }
        return panes;
    }

    List<FileBuffer> deserializeSessionBuffers(Object bufferObject, Map<String, FileBuffer> idToBuffer) {
        List<FileBuffer> restored = new ArrayList<>();
        List<Object> items = MiniJson.asArray(bufferObject);
        if (items == null) {
            return restored;
        }
        for (Object item : items) {
            Map<String, Object> entry = MiniJson.asObject(item);
            if (entry == null) {
                continue;
            }
            String id = MiniJson.asString(entry.get("id"));
            String type = MiniJson.asString(entry.get("type"));
            boolean modified = Boolean.TRUE.equals(entry.get("modified"));
            FileBuffer restoredBuffer = null;
            try {
                if ("file".equals(type)) {
                    String path = MiniJson.asString(entry.get("path"));
                    if (path == null || path.isBlank()) {
                        continue;
                    }
                    File file = new File(path);
                    if (file.exists() && file.isFile()) {
                        restoredBuffer = new FileBuffer(file, configManager);
                    } else {
                        String content = MiniJson.asString(entry.get("content"));
                        if (content != null) {
                            restoredBuffer = new FileBuffer(path);
                            restoredBuffer.setContent(content, true);
                        }
                    }
                    if (restoredBuffer != null && modified) {
                        String content = MiniJson.asString(entry.get("content"));
                        if (content != null) {
                            restoredBuffer.setContent(content, true);
                        } else {
                            restoredBuffer.setModified(true);
                        }
                    }
                } else if ("scratch".equals(type)) {
                    String name = MiniJson.asString(entry.get("name"));
                    String content = MiniJson.asString(entry.get("content"));
                    restoredBuffer = FileBuffer.createScratch(name == null ? "[scratch]" : name, content == null ? "" : content);
                    restoredBuffer.setModified(modified);
                }
            } catch (IOException ignored) {
            }

            if (restoredBuffer != null) {
                restored.add(restoredBuffer);
                if (id != null && !id.isBlank()) {
                    idToBuffer.put(id, restoredBuffer);
                }
            }
        }
        return restored;
    }

    Map<String, Object> serializeWindowLayout(WindowLayoutNode node) {
        if (node == null) {
            return null;
        }
        Map<String, Object> serialized = new LinkedHashMap<>();
        if (node.isLeaf()) {
            serialized.put("type", "leaf");
            serialized.put("paneIndex", editorPanes.indexOf(node.getPane()));
            return serialized;
        }
        serialized.put("type", "split");
        WindowLayoutNode.Orientation orientation = node.getOrientation();
        serialized.put("orientation", orientation == WindowLayoutNode.Orientation.HORIZONTAL ? "horizontal" : "vertical");
        serialized.put("ratio", node.getRatio());
        serialized.put("first", serializeWindowLayout(node.getFirst()));
        serialized.put("second", serializeWindowLayout(node.getSecond()));
        return serialized;
    }

    WindowLayoutNode deserializeWindowLayout(Map<String, Object> layout, List<EditorPane> panes) {
        if (layout == null || panes == null || panes.isEmpty()) {
            return null;
        }
        String type = MiniJson.asString(layout.get("type"));
        if ("leaf".equals(type)) {
            Integer paneIndex = MiniJson.asInt(layout.get("paneIndex"));
            if (paneIndex == null || paneIndex < 0 || paneIndex >= panes.size()) {
                return WindowLayoutNode.leaf(panes.get(0));
            }
            return WindowLayoutNode.leaf(panes.get(paneIndex));
        }
        if ("split".equals(type)) {
            String orientationRaw = MiniJson.asString(layout.get("orientation"));
            WindowLayoutNode.Orientation orientation = "vertical".equalsIgnoreCase(orientationRaw)
                ? WindowLayoutNode.Orientation.VERTICAL
                : WindowLayoutNode.Orientation.HORIZONTAL;
            double ratio = 0.5;
            Object ratioObject = layout.get("ratio");
            if (ratioObject instanceof Number) {
                ratio = ((Number) ratioObject).doubleValue();
            }
            WindowLayoutNode first = deserializeWindowLayout(MiniJson.asObject(layout.get("first")), panes);
            WindowLayoutNode second = deserializeWindowLayout(MiniJson.asObject(layout.get("second")), panes);
            if (first == null || second == null) {
                return null;
            }
            return WindowLayoutNode.split(orientation, ratio, first, second);
        }
        return null;
    }

    void resetEditorPanesForSession(int paneCount, Map<String, Object> layoutObject) {
        detachActiveDocumentListener();
        editorPanes.clear();
        int totalPanes = Math.max(1, paneCount);
        Dimension size = getSize();
        for (int i = 0; i < totalPanes; i++) {
            editorPanes.add(createEditorPane(size));
        }
        activePaneIndex = 0;
        bindActivePane(editorPanes.get(0));
        WindowLayoutNode restoredLayout = deserializeWindowLayout(layoutObject, editorPanes);
        if (restoredLayout == null) {
            restoredLayout = defaultLayoutForPanes(editorPanes);
        }
        windowLayoutRoot = restoredLayout;
        renderWindowLayout();
        attachActiveDocumentListener();
    }

    WindowLayoutNode defaultLayoutForPanes(List<EditorPane> panes) {
        if (panes == null || panes.isEmpty()) {
            return null;
        }
        WindowLayoutNode root = WindowLayoutNode.leaf(panes.get(0));
        EditorPane splitTarget = panes.get(0);
        for (int i = 1; i < panes.size(); i++) {
            root.splitLeaf(splitTarget, panes.get(i), WindowLayoutNode.Orientation.HORIZONTAL);
            splitTarget = panes.get(i);
        }
        return root;
    }

    List<String> extractSessionFilePaths(Object filesObject) {
        List<String> paths = new ArrayList<>();
        List<Object> files = MiniJson.asArray(filesObject);
        if (files == null) {
            return paths;
        }
        for (Object item : files) {
            String direct = MiniJson.asString(item);
            if (direct != null) {
                paths.add(direct);
                continue;
            }
            Map<String, Object> object = MiniJson.asObject(item);
            if (object == null) {
                continue;
            }
            String path = MiniJson.asString(object.get("path"));
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }

    String listSessions() {
        File dir = new File(configManager.getSessionDirectory());
        if (!dir.exists() || !dir.isDirectory()) {
            return "No sessions";
        }
        File[] files = dir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (files == null || files.length == 0) {
            return "No sessions";
        }
        java.util.Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        StringBuilder builder = new StringBuilder();
        builder.append("Sessions\n\n");
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                name = name.substring(0, name.length() - ".json".length());
            }
            builder.append(name).append("  ").append(file.getAbsolutePath()).append("\n");
        }
        showScratchBuffer("[sessions]", builder.toString().stripTrailing() + "\n");
        return "Showing sessions";
    }

    String listWorkspaceProfiles() {
        File dir = new File(configManager.getSessionDirectory());
        if (!dir.exists() || !dir.isDirectory()) {
            return "No workspace profiles";
        }
        File[] files = dir.listFiles(file -> file.isFile()
            && file.getName().startsWith(WORKSPACE_PROFILE_PREFIX)
            && file.getName().endsWith(".json"));
        if (files == null || files.length == 0) {
            return "No workspace profiles";
        }
        java.util.Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        StringBuilder builder = new StringBuilder();
        builder.append("Workspace Profiles\n\n");
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                name = name.substring(0, name.length() - ".json".length());
            }
            if (name.startsWith(WORKSPACE_PROFILE_PREFIX)) {
                name = name.substring(WORKSPACE_PROFILE_PREFIX.length());
            }
            builder.append(name).append("  ").append(file.getAbsolutePath()).append("\n");
        }
        showScratchBuffer("[workspace profiles]", builder.toString().stripTrailing() + "\n");
        return "Showing workspace profiles";
    }

    String defaultWorkspaceProfileName() {
        FileBuffer current = getCurrentBuffer();
        if (current != null && current.hasFilePath()) {
            File root = detectProjectTrustRoot(new File(current.getFilePath()));
            if (root != null) {
                String name = root.getName();
                if (name != null && !name.isBlank()) {
                    return sanitizeSessionName(name);
                }
            }
        }
        return "default";
    }

    File resolveSessionFile(String nameArgument) {
        String rawName = nameArgument == null || nameArgument.isBlank()
            ? configManager.getSessionAutoloadName()
            : nameArgument.trim();
        String safeName = sanitizeSessionName(rawName);
        File dir = new File(configManager.getSessionDirectory());
        return new File(dir, safeName + ".json");
    }

    String sanitizeSessionName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "default";
        }
        StringBuilder builder = new StringBuilder(rawName.length());
        for (int i = 0; i < rawName.length(); i++) {
            char c = rawName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        String sanitized = builder.toString().trim();
        return sanitized.isEmpty() ? "default" : sanitized;
    }

    boolean hasUnsavedChangesInAnyBuffer() {
        for (FileBuffer buffer : buffers) {
            if (buffer != null && buffer.isModified()) {
                return true;
            }
        }
        return false;
    }

    void ensureSettingsFileSeeded(File settingsFile) throws IOException {
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

    String getHelpText(String topic) {
        return helpService.getHelpText(topic, VERSION);
    }

    // Recent files management
    void addToRecentFiles(String filepath) {
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

    void showBufferListDialog(String list) {
        JTextArea textArea = new JTextArea(list);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(400, 200));

        JOptionPane.showMessageDialog(this, scrollPane, "Buffer List", JOptionPane.INFORMATION_MESSAGE);
    }

    // Quit handling
    void handleQuit(boolean force) {
        String message = requestQuit(force);
        if (!"Quitting".equals(message)) {
            showMessage(message);
        }
    }

    boolean hasUnsavedChanges(FileBuffer buffer) {
        return buffer != null && buffer.isModified();
    }

    int confirmDiscardChanges(String prompt) {
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

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    void firePluginEvent(String event) {
        if (pluginManager == null) return;
        pluginManager.fireEvent(event);
    }

    public String reloadPlugins() {
        pluginManager.reload();
        int count = pluginManager.getPlugins().size();
        return "Reloaded " + count + " plugin(s)";
    }

    public String showPluginList() {
        showScratchBuffer("[plugins]", pluginManager.getPluginListText());
        return "Showing plugins";
    }

    public String showPluginPackages() {
        showScratchBuffer("[plugin packages]", pluginManager.getPackageListText());
        return "Showing plugin packages";
    }

    public String enablePlugin(String name) {
        return pluginManager.enablePlugin(name);
    }

    public String disablePlugin(String name) {
        return pluginManager.disablePlugin(name);
    }

    public String showPluginInfo(String name) {
        String text = pluginManager.getPluginInfoText(name);
        showScratchBuffer("[plugin " + name + "]", text);
        return "Showing plugin info";
    }

    public String showPluginPath() {
        String path = pluginManager.getPluginsDirectoryPath();
        List<String> disabled = pluginManager.listDisabledPlugins();
        StringBuilder sb = new StringBuilder();
        sb.append("Plugin directory: ").append(path).append("\n\n");
        if (!disabled.isEmpty()) {
            sb.append("Disabled plugins:\n");
            for (String d : disabled) sb.append("  ").append(d).append("\n");
        }
        showScratchBuffer("[plugin path]", sb.toString());
        return path;
    }

    public String createAndOpenPlugin(String name) {
        try {
            File file = pluginManager.createPluginFile(name);
            openFile(file);
            pluginManager.reload();
            return "Opened plugin: " + file.getName();
        } catch (IOException e) {
            return "Error creating plugin: " + e.getMessage();
        }
    }

    public String installPluginPackage(String args) {
        return pluginManager.installPackage(args);
    }

    public String updatePluginPackage(String args) {
        return pluginManager.updatePackage(args);
    }

    public String removePluginPackage(String name) {
        return pluginManager.removePackage(name);
    }

    public String pinPluginPackage(String name) {
        return pluginManager.setPackagePinned(name, true);
    }

    public String unpinPluginPackage(String name) {
        return pluginManager.setPackagePinned(name, false);
    }

    public String executeCommand(String cmd) {
        if (commandHandler == null) return "";
        return commandHandler.execute(cmd);
    }

    public String getModeName() {
        return editorState.mode == null ? "normal" : editorState.mode.getDisplayName().toLowerCase();
    }

    public String runUserCommand(String name, String shellCmd) {
        try {
            FileBuffer buf = getCurrentBuffer();
            String filePath = (buf != null && buf.hasFilePath()) ? buf.getFilePath() : "";
            int line = getCurrentLineNumber();
            int col = 0;
            try { col = writingArea.getCaretPosition() - writingArea.getLineStartOffset(getCurrentCaretLine()); } catch (BadLocationException ignored) {}
            String word = getWordAtCaret();
            String selection = writingArea.getSelectedText();
            String interpolated = PluginManager.interpolate(shellCmd, filePath, line, col, word, selection);
            String validationError = validateShellCommand(interpolated);
            if (validationError != null) {
                return validationError;
            }
            File workingDirectory = new File(".");
            if (buf != null && buf.getFile() != null && buf.getFile().getParentFile() != null) {
                workingDirectory = buf.getFile().getParentFile();
            }
            CommandResult result = runExternalCommand(
                ShellCommand.forCommand(interpolated),
                workingDirectory,
                null,
                null,
                configManager.getProcessTimeoutMs(),
                configManager.getProcessOutputMaxBytes(),
                true
            );
            String output = result.stdout == null ? "" : result.stdout.stripTrailing();
            if (result.exitCode != 0) {
                if (!output.isEmpty()) {
                    showScratchBuffer("[" + name + "]", output + "\n");
                }
                return ":" + name + " failed (exit " + result.exitCode + ")";
            }
            if (output.isEmpty()) {
                return ":" + name + " completed";
            }
            showScratchBuffer("[" + name + "]", output + "\n");
            return ":" + name + " completed";
        } catch (Exception e) {
            return "Error running user command: " + e.getMessage();
        }
    }

    public String showUndoHistory() {
        StringBuilder sb = new StringBuilder();
        sb.append("Undo History\n");
        sb.append("=".repeat(40)).append("\n\n");
        // UndoManager doesn't expose its edit list, but we can show state
        int canUndo = 0;
        int canRedo = 0;
        javax.swing.undo.UndoManager um = undoManager;
        // Count available undos by trying to get presentation names
        try {
            while (um.canUndo()) {
                canUndo++;
                um.undo();
            }
            // Redo them all back
            int redone = 0;
            while (um.canRedo() && redone < canUndo) {
                um.redo();
                redone++;
            }
            // Count remaining redos
            javax.swing.undo.UndoManager probe = um;
            while (probe.canRedo()) {
                canRedo++;
                probe.redo();
            }
            // Undo back to current position
            for (int i = 0; i < canRedo; i++) {
                probe.undo();
            }
        } catch (Exception ignored) {
            sb.append("(unable to inspect undo state)\n");
        }
        sb.append("Position: ").append(canUndo).append(" edits deep\n");
        sb.append("Can undo: ").append(canUndo).append(" steps\n");
        sb.append("Can redo: ").append(canRedo).append(" steps\n");
        sb.append("Total edits: ").append(canUndo + canRedo).append("\n\n");
        sb.append("  u     = undo one step\n");
        sb.append("  Ctrl+r = redo one step\n");
        showScratchBuffer("[Undo History]", sb.toString());
        return "Showing undo history";
    }

    public String clearSearchHighlights() {
        searchManager.clearHighlights();
        return "Search highlights cleared";
    }

    public String writeAll() {
        int saved = 0;
        for (FileBuffer buffer : buffers) {
            if (buffer.isModified() && buffer.getFile() != null) {
                try {
                    buffer.save();
                    saved++;
                } catch (Exception e) {
                    return "Error saving " + buffer.getDisplayName() + ": " + e.getMessage();
                }
            }
        }
        return saved + " file(s) written";
    }

    public String quitAll(boolean force) {
        if (!force) {
            for (FileBuffer buffer : buffers) {
                if (hasUnsavedChanges(buffer)) {
                    int result = confirmDiscardChanges("There are unsaved changes. Quit anyway?");
                    if (result != JOptionPane.YES_OPTION) {
                        return "Quit cancelled";
                    }
                    break;
                }
            }
        }
        closeEditor();
        return "Quitting";
    }

    public void showScratchBuffer(String title, String content) {
        openScratchBuffer(title, content, true);
    }

    void openScratchBuffer(String title, String content, boolean returnable) {
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

    boolean closeReturnableScratchBuffer() {
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
        if (current == quickfixBuffer) {
            quickfixBuffer = null;
        }

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

    void loadRecentFiles() {
        recentFiles.clear();
        if (!recentFilesStore.exists()) {
            return;
        }
        try {
            recentFiles.addAll(Files.readAllLines(recentFilesStore.toPath(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    void saveRecentFiles() {
        try {
            ensureStoreDirectory(recentFilesStore);
            Files.write(recentFilesStore.toPath(), recentFiles, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    void loadTrustedProjectRoots() {
        trustedProjectRoots.clear();
        if (trustedProjectsStore == null || !trustedProjectsStore.exists()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(trustedProjectsStore.toPath(), StandardCharsets.UTF_8)) {
                String normalized = line == null ? "" : line.trim();
                if (!normalized.isEmpty()) {
                    trustedProjectRoots.add(normalized);
                }
            }
        } catch (IOException ignored) {
        }
    }

    void saveTrustedProjectRoots() {
        if (trustedProjectsStore == null) {
            return;
        }
        try {
            ensureStoreDirectory(trustedProjectsStore);
            List<String> roots = new ArrayList<>(trustedProjectRoots);
            Collections.sort(roots);
            Files.write(
                trustedProjectsStore.toPath(),
                roots,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException ignored) {
        }
    }

    boolean ensureProjectTrustForFile(File file) {
        File projectRoot = detectProjectTrustRoot(file);
        if (projectRoot == null) {
            return true;
        }
        String canonicalRoot;
        try {
            canonicalRoot = projectRoot.getCanonicalPath();
        } catch (IOException e) {
            canonicalRoot = projectRoot.getAbsolutePath();
        }
        if (trustedProjectRoots.contains(canonicalRoot)) {
            return true;
        }
        if (!hasProjectLocalExecutionSurface(projectRoot)) {
            return true;
        }

        int result = JOptionPane.showConfirmDialog(
            this,
            "This project contains local execution surfaces (.shedrc.local and/or .shed/plugins).\n"
                + "Trust this project root for local config/plugin behavior?\n"
                + canonicalRoot,
            "Project Trust",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (result == JOptionPane.YES_OPTION) {
            trustedProjectRoots.add(canonicalRoot);
            saveTrustedProjectRoots();
            return true;
        }
        return false;
    }

    File detectProjectTrustRoot(File file) {
        if (file == null) {
            return null;
        }
        File cursor = file.isDirectory() ? file : file.getParentFile();
        File firstConfigRoot = null;
        while (cursor != null) {
            File gitDir = new File(cursor, ".git");
            if (gitDir.exists()) {
                return cursor;
            }
            File localConfig = new File(cursor, ".shedrc.local");
            if (localConfig.isFile() && firstConfigRoot == null) {
                firstConfigRoot = cursor;
            }
            cursor = cursor.getParentFile();
        }
        return firstConfigRoot;
    }

    boolean hasProjectLocalExecutionSurface(File projectRoot) {
        if (projectRoot == null || !projectRoot.isDirectory()) {
            return false;
        }
        File localConfig = new File(projectRoot, ".shedrc.local");
        if (localConfig.isFile()) {
            return true;
        }
        File pluginDir = new File(projectRoot, ".shed/plugins");
        if (!pluginDir.isDirectory()) {
            return false;
        }
        File[] pluginFiles = pluginDir.listFiles(file -> file.isFile()
            && (file.getName().endsWith(".shed") || file.getName().endsWith(".lua")));
        return pluginFiles != null && pluginFiles.length > 0;
    }

    void appendCommandLog(String entry) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        String line = commandLogTimeFormat.format(LocalDateTime.now()) + " " + entry.strip() + "\n";
        try {
            ensureStoreDirectory(commandLogStore);
            Files.write(commandLogStore.toPath(),
                line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    void ensureStoreDirectory(File store) throws IOException {
        if (store == null) {
            return;
        }
        File parent = store.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
    }

    static final Pattern HEX_COLOR_PATTERN = Pattern.compile("#[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{3})?\\b");
    void paintColorPreviews(Graphics g, JTextArea area) {
        String text = area.getText();
        if (text.isEmpty()) return;
        FontMetrics fm = g.getFontMetrics(area.getFont());
        Rectangle clip = g.getClipBounds();
        java.util.regex.Matcher m = HEX_COLOR_PATTERN.matcher(text);
        while (m.find()) {
            try {
                Rectangle2D r = area.modelToView2D(m.end());
                if (r == null) continue;
                if (clip != null && ((int) r.getY() < clip.y - 20 || (int) r.getY() > clip.y + clip.height + 20)) continue;
                String hex = m.group();
                if (hex.length() == 4) { // expand #RGB to #RRGGBB
                    hex = "#" + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2) + hex.charAt(3) + hex.charAt(3);
                }
                Color c = Color.decode(hex);
                int size = fm.getHeight() - 4;
                int x = (int) r.getX() + 2;
                int y = (int) r.getY() + 2;
                g.setColor(c);
                g.fillRect(x, y, size, size);
                g.setColor(new Color(255, 255, 255, 80));
                g.drawRect(x, y, size, size);
            } catch (Exception ignored) {}
        }
    }
    void paintWrapIndicators(Graphics g, JTextArea area) {
        FontMetrics fm = g.getFontMetrics(area.getFont());
        int lineH = fm.getHeight();
        g.setColor(new Color(255, 255, 255, 40));
        Rectangle clip = g.getClipBounds();
        int startY = clip != null ? clip.y : 0;
        int endY = clip != null ? clip.y + clip.height : area.getHeight();
        try {
            int startLine = area.getLineOfOffset(area.viewToModel2D(new Point(0, startY)));
            int endLine = Math.min(area.getLineCount() - 1, area.getLineOfOffset(area.viewToModel2D(new Point(0, endY))));
            for (int line = startLine; line <= endLine; line++) {
                int ls = area.getLineStartOffset(line);
                int le = area.getLineEndOffset(line);
                Rectangle2D rStart = area.modelToView2D(ls);
                Rectangle2D rEnd = area.modelToView2D(Math.max(ls, le - 1));
                if (rStart == null || rEnd == null) continue;
                if ((int) rEnd.getY() > (int) rStart.getY()) {
                    // wrapped line: draw arrow at right edge for each wrapped row
                    int rows = ((int) rEnd.getY() - (int) rStart.getY()) / lineH;
                    int rightX = area.getWidth() - 10;
                    for (int r = 0; r < rows; r++) {
                        int y = (int) rStart.getY() + (r + 1) * lineH - lineH / 2;
                        g.drawString("\u21B5", rightX, y); // ↵
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    void paintVisualBlockOverlay(Graphics g, JTextArea area) {
        if (editorState.mode != EditorMode.VISUAL_BLOCK || area != writingArea) return;
        int[] bounds = getVisualBlockBounds();
        if (bounds == null) return;
        int startLine = bounds[0], endLine = bounds[1], startCol = bounds[2], endCol = bounds[3];
        Graphics2D g2 = (Graphics2D) g;
        Color sel = configManager.getSelectionColor();
        g2.setColor(new Color(sel.getRed(), sel.getGreen(), sel.getBlue(), 100));
        FontMetrics fm = g2.getFontMetrics(area.getFont());
        int charW = fm.charWidth(' ');
        try {
            for (int line = startLine; line <= endLine && line < area.getLineCount(); line++) {
                int ls = area.getLineStartOffset(line);
                Rectangle2D r = area.modelToView2D(ls);
                if (r == null) continue;
                int x1 = (int) r.getX() + startCol * charW;
                int x2 = (int) r.getX() + (endCol + 1) * charW;
                g2.fillRect(x1, (int) r.getY(), x2 - x1, fm.getHeight());
            }
        } catch (BadLocationException ignored) {}
    }

    void paintDiagnosticOverlay(Graphics g, JTextArea area) {
        if (area == null || diagnosticRanges.isEmpty()) return;
        Graphics2D g2 = (Graphics2D) g;
        FontMetrics fm = g2.getFontMetrics(area.getFont());
        int ascent = fm.getAscent();
        int descent = fm.getDescent();
        int docLen = area.getDocument().getLength();
        for (int[] dr : diagnosticRanges) {
            int start = dr[0], end = dr[1], severity = dr[2];
            if (start >= docLen || end > docLen || start >= end) continue;
            Color c;
            switch (severity) {
                case 1: c = new Color(0xFF, 0x44, 0x44, 0xCC); break; // error
                case 2: c = new Color(0xFF, 0xCC, 0x00, 0xCC); break; // warning
                case 3: c = new Color(0x55, 0x99, 0xFF, 0xCC); break; // info
                default: c = new Color(0x99, 0x99, 0x99, 0xCC); break; // hint
            }
            g2.setColor(c);
            try {
                Rectangle2D r1 = area.modelToView2D(start);
                Rectangle2D r2 = area.modelToView2D(end);
                if (r1 == null || r2 == null) continue;
                int y = (int) (r1.getY() + ascent + descent);
                int x1 = (int) r1.getX();
                int x2 = (int) r2.getX();
                if ((int) r1.getY() != (int) r2.getY()) {
                    // multiline: just underline first line to EOL
                    x2 = area.getWidth();
                }
                // draw wavy underline
                for (int x = x1; x < x2; x += 4) {
                    int amp = (x / 4 % 2 == 0) ? 0 : 2;
                    int nextAmp = ((x + 4) / 4 % 2 == 0) ? 0 : 2;
                    g2.drawLine(x, y + amp, Math.min(x + 4, x2), y + nextAmp);
                }
            } catch (BadLocationException ignored) {}
        }
    }

    void refreshDiagnosticRanges() {
        diagnosticRanges.clear();
        EditorPane diagPane = getActivePane();
        if (diagPane != null && diagPane.getLineNumberPanel() != null) diagPane.getLineNumberPanel().updateDiagnosticMarkers(null);
        FileBuffer buffer = getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) { writingArea.repaint(); return; }
        LspClient client = lspClients.get(bufferExtension(buffer));
        if (client == null || !client.isAlive()) { writingArea.repaint(); return; }
        String uri = bufferUri(buffer);
        List<LspClient.Diagnostic> diags = client.getDiagnostics(uri);
        if (diags == null || diags.isEmpty()) { writingArea.repaint(); return; }
        try {
            for (LspClient.Diagnostic d : diags) {
                int line = d.getLine();
                if (line >= writingArea.getLineCount()) continue;
                int lineStart = writingArea.getLineStartOffset(line);
                int lineEnd = writingArea.getLineEndOffset(line);
                int startOff = Math.min(lineStart + d.getCharacter(), lineEnd);
                int endOff = Math.min(startOff + 1, lineEnd); // at least 1 char wide
                // try to expand to end of token
                String text = writingArea.getText();
                while (endOff < lineEnd && endOff < text.length() && !Character.isWhitespace(text.charAt(endOff))) endOff++;
                diagnosticRanges.add(new int[]{startOff, endOff, d.getSeverity()});
            }
        } catch (BadLocationException ignored) {}
        // update gutter diagnostic markers
        java.util.HashMap<Integer, Integer> severityByLine = new java.util.HashMap<>();
        for (LspClient.Diagnostic d : diags) {
            int line = d.getLine();
            Integer existing = severityByLine.get(line);
            if (existing == null || d.getSeverity() < existing) severityByLine.put(line, d.getSeverity());
        }
        EditorPane pane = getActivePane();
        if (pane != null && pane.getLineNumberPanel() != null) pane.getLineNumberPanel().updateDiagnosticMarkers(severityByLine);
        writingArea.repaint();
    }

    void paintSyntaxForegroundOverlay(Graphics g, JTextArea area) {
        if (area == null || area != writingArea || syntaxForegroundSpans.isEmpty()) {
            return;
        }
        int docLength = area.getDocument().getLength();
        if (docLength <= 0) {
            return;
        }
        int visibleStart = 0;
        int visibleEnd = docLength;
        Rectangle clip = g.getClipBounds();
        if (clip != null) {
            int start = area.viewToModel2D(new Point(clip.x, clip.y));
            int end = area.viewToModel2D(new Point(clip.x + clip.width, clip.y + clip.height));
            if (start < 0) {
                start = 0;
            }
            if (end < start) {
                end = start;
            }
            visibleStart = Math.min(start, docLength);
            visibleEnd = Math.min(docLength, end + 1);
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setFont(area.getFont());
        FontMetrics metrics = area.getFontMetrics(area.getFont());
        int ascent = metrics.getAscent();
        int tabPixels = Math.max(1, area.getTabSize() * metrics.charWidth(' '));
        TabExpander tabExpander = (x, tabOffset) -> ((int) x / tabPixels + 1) * tabPixels;

        for (SyntaxSpan span : syntaxForegroundSpans) {
            if (span.end <= visibleStart || span.start >= visibleEnd) {
                continue;
            }
            int spanStart = Math.max(span.start, visibleStart);
            int spanEnd = Math.min(span.end, visibleEnd);
            if (spanEnd <= spanStart) {
                continue;
            }
            try {
                int startLine = area.getLineOfOffset(spanStart);
                int endLine = area.getLineOfOffset(Math.max(spanStart, spanEnd - 1));
                for (int line = startLine; line <= endLine; line++) {
                    int lineStart = area.getLineStartOffset(line);
                    int lineEnd = area.getLineEndOffset(line);
                    int segmentStart = Math.max(spanStart, lineStart);
                    int segmentEnd = Math.min(spanEnd, lineEnd);
                    while (segmentEnd > segmentStart) {
                        char tail = area.getText(segmentEnd - 1, 1).charAt(0);
                        if (tail == '\n' || tail == '\r') {
                            segmentEnd--;
                        } else {
                            break;
                        }
                    }
                    if (segmentEnd <= segmentStart) {
                        continue;
                    }
                    Rectangle2D rect = area.modelToView2D(segmentStart);
                    if (rect == null) {
                        continue;
                    }
                    String text = area.getText(segmentStart, segmentEnd - segmentStart);
                    Segment segment = new Segment(text.toCharArray(), 0, text.length());
                    int x = (int) Math.round(rect.getX());
                    int y = (int) Math.round(rect.getY()) + ascent;
                    g2.setColor(span.color);
                    Utilities.drawTabbedText(segment, x, y, (Graphics) g2, tabExpander, segmentStart);
                }
            } catch (BadLocationException ignored) {
            }
        }
        g2.dispose();
    }

    public void closeEditor() {
        if (closingDown) {
            return;
        }
        closingDown = true;
        if (recoverySnapshotTimer != null) {
            recoverySnapshotTimer.stop();
        }
        clearRecoverySnapshots();
        if (ptyTerminalPanes != null) {
            for (PtyTerminalPane terminalPane : new ArrayList<>(ptyTerminalPanes.values())) {
                terminalPane.close();
            }
            ptyTerminalPanes.clear();
        }
        if (asyncJobService != null) {
            asyncJobService.shutdownNow();
        }
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
        if (editorState.mode != EditorMode.INSERT) {
            e.consume();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {}

    // Main method
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Texteditor(args));
    }
}
