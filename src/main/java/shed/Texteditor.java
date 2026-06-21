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
    SessionConfigController sessionConfigController;
    DramaticUiController dramaticUiController;
    SyntaxUiController syntaxUiController;
    EditActionController editActionController;

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
        sessionConfigController = new SessionConfigController(this);
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
        dramaticUiController = new DramaticUiController(this);
        refreshDramaticSettings();
        loadRecentFiles();
        loadTrustedProjectRoots();
        lastMessage = "";

        syntaxUiController = new SyntaxUiController(this);
        editActionController = new EditActionController(this);

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
        editActionController.normalizeVisualLineCaretForMotion();
    }

    void applyVisualLineOperator(char operator) {
        editActionController.applyVisualLineOperator(operator);
    }

    void joinVisualSelection() {
        editActionController.joinVisualSelection();
    }

    void surroundVisualSelection(char surroundChar) {
        editActionController.surroundVisualSelection(surroundChar);
    }

    void toggleCommentSelection() {
        editActionController.toggleCommentSelection();
    }

    void toggleCommentLineRange(int startLine, int endLine) {
        editActionController.toggleCommentLineRange(startLine, endLine);
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
        editActionController.moveUp();
    }

    void moveDown() {
        editActionController.moveDown();
    }

    void moveLeft() {
        editActionController.moveLeft();
    }

    void moveRight() {
        editActionController.moveRight();
    }

    void moveWordForward() {
        editActionController.moveWordForward();
    }

    void moveWordBackward() {
        editActionController.moveWordBackward();
    }

    void moveWordEnd() {
        editActionController.moveWordEnd();
    }

    void moveWordForwardBig() {
        editActionController.moveWordForwardBig();
    }

    void moveWordBackwardBig() {
        editActionController.moveWordBackwardBig();
    }

    void moveWordEndBig() {
        editActionController.moveWordEndBig();
    }

    void moveWordEndBackward() {
        editActionController.moveWordEndBackward();
    }

    void moveWordEndBackwardBig() {
        editActionController.moveWordEndBackwardBig();
    }

    void moveWordEndBackwardInternal(boolean bigWord) {
        editActionController.moveWordEndBackwardInternal(bigWord);
    }

    boolean isMotionWordChar(char c, boolean bigWord) {
        return editActionController.isMotionWordChar(c, bigWord);
    }

    void moveLineStart() {
        editActionController.moveLineStart();
    }

    void moveLineEnd() {
        editActionController.moveLineEnd();
    }

    void moveLineFirstNonBlank() {
        editActionController.moveLineFirstNonBlank();
    }

    void moveLineLastNonBlank() {
        editActionController.moveLineLastNonBlank();
    }

    void moveFileStart() {
        editActionController.moveFileStart();
    }

    void moveFileEnd() {
        editActionController.moveFileEnd();
    }

    void moveParagraphForward() {
        editActionController.moveParagraphForward();
    }

    void moveParagraphBackward() {
        editActionController.moveParagraphBackward();
    }

    void moveSentenceForward() {
        editActionController.moveSentenceForward();
    }

    void moveSentenceBackward() {
        editActionController.moveSentenceBackward();
    }

    void moveMatchingBracket() {
        editActionController.moveMatchingBracket();
    }

    void moveToFilePercent(int percent) {
        editActionController.moveToFilePercent(percent);
    }

    void moveToScreenPosition(char position) {
        editActionController.moveToScreenPosition(position);
    }

    void scrollCurrentLineTo(char anchor) {
        editActionController.scrollCurrentLineTo(anchor);
    }

    String lineText(int line) throws BadLocationException {
        return editActionController.lineText(line);
    }

    void scrollHalfPageDown() {
        editActionController.scrollHalfPageDown();
    }

    void scrollHalfPageUp() {
        editActionController.scrollHalfPageUp();
    }

    void scrollFullPageDown() {
        editActionController.scrollFullPageDown();
    }

    void scrollFullPageUp() {
        editActionController.scrollFullPageUp();
    }

    void scrollLineDown() {
        editActionController.scrollLineDown();
    }

    void scrollLineUp() {
        editActionController.scrollLineUp();
    }

    String showFileInfo() {
        return editActionController.showFileInfo();
    }

    String goToFileUnderCursor() {
        return editActionController.goToFileUnderCursor();
    }

    String openBrowserUrl() {
        return editActionController.openBrowserUrl();
    }

    void selectCurrentLine() {
        editActionController.selectCurrentLine();
    }

    void selectLineRange(int anchorPosition, int currentPosition) {
        editActionController.selectLineRange(anchorPosition, currentPosition);
    }

    void ensureCaretVisible(JTextArea area) {
        editActionController.ensureCaretVisible(area);
    }

    boolean isPrintableKey(KeyEvent e) {
        return editActionController.isPrintableKey(e);
    }

    String searchWordUnderCursor(boolean forward) {
        return editActionController.searchWordUnderCursor(forward);
    }

    boolean isWordCharacter(char c) {
        return editActionController.isWordCharacter(c);
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
        syntaxUiController.updateCurrentLineHighlight();
    }

    String getGitBlameForCurrentLine(FileBuffer buffer) {
        return syntaxUiController.getGitBlameForCurrentLine(buffer);
    }

    String findCurrentBreadcrumb() {
        return syntaxUiController.findCurrentBreadcrumb();
    }

    String findCurrentScopeHeuristic() {
        return syntaxUiController.findCurrentScopeHeuristic();
    }

    void updateMatchingBracketHighlight() {
        syntaxUiController.updateMatchingBracketHighlight();
    }

    boolean isBracketChar(char c) {
        return syntaxUiController.isBracketChar(c);
    }

    int findMatchingBracketPos(String text, int pos, char bracket) {
        return syntaxUiController.findMatchingBracketPos(text, pos, bracket);
    }

    void refreshLineNumberPanel() {
        syntaxUiController.refreshLineNumberPanel();
    }

    void applyThemeColors() {
        syntaxUiController.applyThemeColors();
    }

    void applySyntaxHighlighting() {
        syntaxUiController.applySyntaxHighlighting();
    }

    void clearSyntaxHighlighting() {
        syntaxUiController.clearSyntaxHighlighting();
    }

    String[] syntaxKeywordsFor(FileType fileType) {
        return syntaxUiController.syntaxKeywordsFor(fileType);
    }

    void highlightJavaAnnotations(String text, boolean[] masked) {
        syntaxUiController.highlightJavaAnnotations(text, masked);
    }

    void highlightKeywords(String text, String[] keywords, boolean[] masked) {
        syntaxUiController.highlightKeywords(text, keywords, masked);
    }

    boolean isKeywordMatch(String text, int start, String keyword, boolean[] masked) {
        return syntaxUiController.isKeywordMatch(text, start, keyword, masked);
    }

    void highlightComments(String text, FileType fileType, boolean[] masked) {
        syntaxUiController.highlightComments(text, fileType, masked);
    }

    void highlightStrings(String text, FileType fileType, boolean[] masked) {
        syntaxUiController.highlightStrings(text, fileType, masked);
    }

    void highlightTrailingWhitespace(Highlighter highlighter, String text) {
        syntaxUiController.highlightTrailingWhitespace(highlighter, text);
    }

    void highlightScopeRules(String text, FileType fileType, boolean[] masked) {
        syntaxUiController.highlightScopeRules(text, fileType, masked);
    }

    void highlightNumbers(String text, boolean[] masked) {
        syntaxUiController.highlightNumbers(text, masked);
    }

    void addSyntaxHighlight(int start, int end, Color color, boolean[] masked) {
        syntaxUiController.addSyntaxHighlight(start, end, color, masked);
    }

    boolean isMasked(boolean[] masked, int start, int end) {
        return syntaxUiController.isMasked(masked, start, end);
    }

    void markMasked(boolean[] masked, int start, int end) {
        syntaxUiController.markMasked(masked, start, end);
    }

    boolean matchesAt(String text, int index, String token) {
        return syntaxUiController.matchesAt(text, index, token);
    }

    boolean isIdentifierChar(char c) {
        return syntaxUiController.isIdentifierChar(c);
    }

    boolean isStringDelimiter(FileType fileType, char c) {
        return syntaxUiController.isStringDelimiter(fileType, c);
    }

    String[] lineCommentPrefixesFor(FileType fileType) {
        return syntaxUiController.lineCommentPrefixesFor(fileType);
    }

    String[][] blockCommentPairsFor(FileType fileType) {
        return syntaxUiController.blockCommentPairsFor(fileType);
    }

    void updateSubstitutePreview() {
        syntaxUiController.updateSubstitutePreview();
    }

    void clearSubstitutePreview() {
        syntaxUiController.clearSubstitutePreview();
    }

    SubstitutePreview parseSubstitutePreview(String command) {
        return syntaxUiController.parseSubstitutePreview(command);
    }

    int findRangeCommandStart(String command) {
        return syntaxUiController.findRangeCommandStart(command);
    }

    int getCurrentCaretLine() {
        return syntaxUiController.getCurrentCaretLine();
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
        editActionController.addCursorAtNextMatch();
    }

    String formatParagraph() {
        return editActionController.formatParagraph();
    }
    void moveDisplayLineDown() {
        editActionController.moveDisplayLineDown();
    }
    void moveDisplayLineUp() {
        editActionController.moveDisplayLineUp();
    }
    void enterVisualBlockMode() {
        editActionController.enterVisualBlockMode();
    }

    int[] getVisualBlockBounds() {
        return editActionController.getVisualBlockBounds();
    }

    void deleteVisualBlock() {
        editActionController.deleteVisualBlock();
    }

    void yankVisualBlock() {
        editActionController.yankVisualBlock();
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
        editActionController.applyMultiCursorInsert(c);
    }

    void applyMultiCursorBackspace() {
        editActionController.applyMultiCursorBackspace();
    }
    void applyMultiCursorDelete() {
        editActionController.applyMultiCursorDelete();
    }
    void addCursorAbove() {
        editActionController.addCursorAbove();
    }
    void addCursorBelow() {
        editActionController.addCursorBelow();
    }
    void clearExtraCursors() {
        editActionController.clearExtraCursors();
    }

    void deleteWordBackwardInsert() {
        editActionController.deleteWordBackwardInsert();
    }

    void deleteToLineStartInsert() {
        editActionController.deleteToLineStartInsert();
    }

    String currentLineIndentation() {
        return editActionController.currentLineIndentation();
    }

    void moveLineIndentStart() {
        editActionController.moveLineIndentStart();
    }

    void openLineBelow() {
        editActionController.openLineBelow();
    }

    void openLineAbove() {
        editActionController.openLineAbove();
    }

    void joinCurrentLine(boolean withSpace) {
        editActionController.joinCurrentLine(withSpace);
    }

    String applyLineOperator(char operator) {
        return editActionController.applyLineOperator(operator);
    }

    int leadingWhitespace(String text) {
        return editActionController.leadingWhitespace(text);
    }

    String indentationForLine(int line) {
        return editActionController.indentationForLine(line);
    }

    String findCharacter(char type, char target) {
        return editActionController.findCharacter(type, target);
    }

    String repeatFind(boolean reverse) {
        return editActionController.repeatFind(reverse);
    }

    void recordJumpPosition() {
        editActionController.recordJumpPosition();
    }

    void jumpBack() {
        editActionController.jumpBack();
    }

    void jumpForward() {
        editActionController.jumpForward();
    }

    void recordChangePosition() {
        editActionController.recordChangePosition();
    }

    void changePrev() {
        editActionController.changePrev();
    }

    void changeNext() {
        editActionController.changeNext();
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
        return dramaticUiController.toggleMinimap();
    }

    public String toggleZenMode() {
        return dramaticUiController.toggleZenMode();
    }

    void updateZenModeLayout() {
        dramaticUiController.updateZenModeLayout();
    }

    Color fadedMarginColor(Color base) {
        return dramaticUiController.fadedMarginColor(base);
    }

    Color blendColor(Color base, Color overlay, double ratio) {
        return dramaticUiController.blendColor(base, overlay, ratio);
    }

    void refreshDramaticSettings() {
        dramaticUiController.refreshDramaticSettings();
    }

    boolean dramaticMotionAllowed() {
        return dramaticUiController.dramaticMotionAllowed();
    }

    boolean isDramaticPerformanceThrottled() {
        return dramaticUiController.isDramaticPerformanceThrottled();
    }

    double cachedProcessCpuLoad() {
        return dramaticUiController.cachedProcessCpuLoad();
    }

    double readProcessCpuLoad() {
        return dramaticUiController.readProcessCpuLoad();
    }

    int animationDelayForSteps(int steps) {
        return dramaticUiController.animationDelayForSteps(steps);
    }

    double easeOut(double t) {
        return dramaticUiController.easeOut(t);
    }

    void applyDramaticFooterStyling() {
        dramaticUiController.applyDramaticFooterStyling();
    }

    void animateModeTransition(EditorMode fromMode, EditorMode toMode) {
        dramaticUiController.animateModeTransition(fromMode, toMode);
    }

    void clearFeedbackPulse() {
        dramaticUiController.clearFeedbackPulse();
    }

    void pulseCaretLine(Color color) {
        dramaticUiController.pulseCaretLine(color);
    }

    void animateEditorHostTint(Color tint) {
        dramaticUiController.animateEditorHostTint(tint);
    }

    void animateSplitForPane(EditorPane pane, double startRatio, double targetRatio) {
        dramaticUiController.animateSplitForPane(pane, startRatio, targetRatio);
    }

    void animateMinimapWidth(MinimapPanel panel, int fromWidth, int toWidth, Runnable onFinish) {
        dramaticUiController.animateMinimapWidth(panel, fromWidth, toWidth, onFinish);
    }

    void clearPaneJumpFlash() {
        dramaticUiController.clearPaneJumpFlash();
    }

    void flashPaneJump(EditorPane pane) {
        dramaticUiController.flashPaneJump(pane);
    }

    void playCue(CueType cueType) {
        dramaticUiController.playCue(cueType);
    }

    int[] cuePattern(CueType cueType) {
        return dramaticUiController.cuePattern(cueType);
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
        editActionController.insertLastText();
    }

    int consumePendingCount() {
        return editActionController.consumePendingCount();
    }

    void repeatAction(int count, Runnable action) {
        editActionController.repeatAction(count, action);
    }

    Character consumePendingRegister() {
        return editActionController.consumePendingRegister();
    }

    void storeYank(Character register, String text, boolean lineWise) {
        editActionController.storeYank(register, text, lineWise);
    }

    void storeDelete(Character register, String text, boolean lineWise) {
        editActionController.storeDelete(register, text, lineWise);
    }

    void addToYankRing(RegisterContent content) {
        editActionController.addToYankRing(content);
    }

    public String showYankRingPicker() {
        return editActionController.showYankRingPicker();
    }

    String pasteFromRegister(boolean before) {
        return editActionController.pasteFromRegister(before);
    }

    String playMacro(Character register) {
        return editActionController.playMacro(register);
    }

    String yankToEndOfLine() {
        return editActionController.yankToEndOfLine();
    }

    String replaceCharacter(char replacement) {
        return editActionController.replaceCharacter(replacement);
    }

    String applyMotionOperator(char operator, String motion) {
        return editActionController.applyMotionOperator(operator, motion);
    }

    String applyTextObjectOperator(char operator, char modifier, char objectKey) {
        return editActionController.applyTextObjectOperator(operator, modifier, objectKey);
    }

    String applyResolvedRange(char operator, MotionRange range, String label) {
        return editActionController.applyResolvedRange(operator, range, label);
    }

    MotionRange resolveMotionRange(String motion) {
        return editActionController.resolveMotionRange(motion);
    }

    MotionRange resolveTextObjectRange(char modifier, char objectKey) {
        return editActionController.resolveTextObjectRange(modifier, objectKey);
    }

    String handleSurroundPending(char c) {
        return editActionController.handleSurroundPending(c);
    }

    boolean isTextObjectKey(char c) {
        return editActionController.isTextObjectKey(c);
    }

    String surroundChange(char oldChar, char newChar) {
        return editActionController.surroundChange(oldChar, newChar);
    }

    String surroundDelete(char target) {
        return editActionController.surroundDelete(target);
    }

    String surroundAdd(char targetObject, char surroundChar) {
        return editActionController.surroundAdd(targetObject, surroundChar);
    }

    MotionRange resolveSurroundRange(char surround) {
        return editActionController.resolveSurroundRange(surround);
    }

    SurroundPair surroundPair(char surround) {
        return editActionController.surroundPair(surround);
    }

    MotionRange resolveWordObject(boolean around, boolean bigWord) {
        return editActionController.resolveWordObject(around, bigWord);
    }

    MotionRange resolveParagraphObject(boolean around) {
        return editActionController.resolveParagraphObject(around);
    }

    MotionRange resolveSentenceObject(boolean around) {
        return editActionController.resolveSentenceObject(around);
    }

    MotionRange resolveQuoteObject(boolean around, char quote) {
        return editActionController.resolveQuoteObject(around, quote);
    }

    MotionRange resolveBracketObject(boolean around, char open, char close) {
        return editActionController.resolveBracketObject(around, open, close);
    }

    int previewMotionTarget(String motion) {
        return editActionController.previewMotionTarget(motion);
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
        sessionConfigController.toggleLineNumbers(enabled);
    }

    public void setLineNumberMode(LineNumberMode mode) {
        sessionConfigController.setLineNumberMode(mode);
    }

    public String setLineNumberMode(String value) {
        return sessionConfigController.setLineNumberMode(value);
    }

    public void setHighlightSearch(boolean enabled) {
        sessionConfigController.setHighlightSearch(enabled);
    }

    public void setAutoIndent(boolean enabled) {
        sessionConfigController.setAutoIndent(enabled);
    }

    public void setWrap(boolean enabled) {
        sessionConfigController.setWrap(enabled);
    }

    public void setExpandTab(boolean enabled) {
        sessionConfigController.setExpandTab(enabled);
    }

    public void setShowCurrentLine(boolean enabled) {
        sessionConfigController.setShowCurrentLine(enabled);
    }

    public String getCurrentThemeName() {
        return sessionConfigController.getCurrentThemeName();
    }

    public List<String> getThemeIdsForPlugins() {
        return sessionConfigController.getThemeIdsForPlugins();
    }

    public Map<String, String> getActiveThemePaletteHex() {
        return sessionConfigController.getActiveThemePaletteHex();
    }

    public String resolveCommandAlias(String command) {
        return sessionConfigController.resolveCommandAlias(command);
    }

    public String setThemeFromCommand(String value) {
        return sessionConfigController.setThemeFromCommand(value);
    }

    public String applyThemeFromPlugin(String value, boolean persist) {
        return sessionConfigController.applyThemeFromPlugin(value, persist);
    }

    public String applyPaletteOverridesFromPlugin(Map<String, String> overrides, boolean persist) {
        return sessionConfigController.applyPaletteOverridesFromPlugin(overrides, persist);
    }

    String mapPaletteAliasToConfigKey(String rawKey) {
        return sessionConfigController.mapPaletteAliasToConfigKey(rawKey);
    }

    String colorToHex(Color color) {
        return sessionConfigController.colorToHex(color);
    }

    public String setConfigOption(String key, String value) {
        return sessionConfigController.setConfigOption(key, value);
    }

    public String setConfigOptionPersistent(String key, String value) {
        return sessionConfigController.setConfigOptionPersistent(key, value);
    }

    boolean isThemeRelatedConfigKey(String key) {
        return sessionConfigController.isThemeRelatedConfigKey(key);
    }

    public String saveConfigToDisk() {
        return sessionConfigController.saveConfigToDisk();
    }

    public String applyTheaterPreset(String presetArgument) {
        return sessionConfigController.applyTheaterPreset(presetArgument);
    }

    public String reloadConfigFromDisk() {
        return sessionConfigController.reloadConfigFromDisk();
    }

    public String reloadConfigIfSettingsBuffer(FileBuffer buffer) {
        return sessionConfigController.reloadConfigIfSettingsBuffer(buffer);
    }

    public String reloadConfigIfSettingsBuffer(FileBuffer buffer, String previousContent, String updatedContent) {
        return sessionConfigController.reloadConfigIfSettingsBuffer(buffer, previousContent, updatedContent);
    }

    boolean isSettingsFile(File file) {
        return sessionConfigController.isSettingsFile(file);
    }

    boolean didConfigKeyChange(String previousContent, String updatedContent, String key) {
        return sessionConfigController.didConfigKeyChange(previousContent, updatedContent, key);
    }

    String extractConfigValue(String content, String key) {
        return sessionConfigController.extractConfigValue(content, key);
    }

    void applyRuntimeConfigFromSettings() {
        sessionConfigController.applyRuntimeConfigFromSettings();
    }

    public String showThemes() {
        return sessionConfigController.showThemes();
    }

    public String openSettingsBuffer() {
        return sessionConfigController.openSettingsBuffer();
    }

    public String openCommandLogBuffer() {
        return sessionConfigController.openCommandLogBuffer();
    }

    public String cleanShedDataFiles() {
        return sessionConfigController.cleanShedDataFiles();
    }

    public String handleSessionCommand(String argument) {
        return sessionConfigController.handleSessionCommand(argument);
    }

    public String handleWorkspaceProfileCommand(String argument) {
        return sessionConfigController.handleWorkspaceProfileCommand(argument);
    }

    String saveSession(String nameArgument) {
        return sessionConfigController.saveSession(nameArgument);
    }

    String loadSession(String nameArgument, boolean force) {
        return sessionConfigController.loadSession(nameArgument, force);
    }

    boolean restoreSessionV2(Map<String, Object> payload) {
        return sessionConfigController.restoreSessionV2(payload);
    }

    boolean restoreLegacySession(Map<String, Object> payload) {
        return sessionConfigController.restoreLegacySession(payload);
    }

    Map<String, Object> captureSessionUiSettings() {
        return sessionConfigController.captureSessionUiSettings();
    }

    void applySessionUiSettings(Map<String, Object> settings) {
        sessionConfigController.applySessionUiSettings(settings);
    }

    List<Map<String, Object>> serializeSessionBuffers(Map<FileBuffer, String> bufferIds) {
        return sessionConfigController.serializeSessionBuffers(bufferIds);
    }

    List<Map<String, Object>> serializeSessionPanes(Map<FileBuffer, String> bufferIds) {
        return sessionConfigController.serializeSessionPanes(bufferIds);
    }

    List<FileBuffer> deserializeSessionBuffers(Object bufferObject, Map<String, FileBuffer> idToBuffer) {
        return sessionConfigController.deserializeSessionBuffers(bufferObject, idToBuffer);
    }

    Map<String, Object> serializeWindowLayout(WindowLayoutNode node) {
        return sessionConfigController.serializeWindowLayout(node);
    }

    WindowLayoutNode deserializeWindowLayout(Map<String, Object> layout, List<EditorPane> panes) {
        return sessionConfigController.deserializeWindowLayout(layout, panes);
    }

    void resetEditorPanesForSession(int paneCount, Map<String, Object> layoutObject) {
        sessionConfigController.resetEditorPanesForSession(paneCount, layoutObject);
    }

    WindowLayoutNode defaultLayoutForPanes(List<EditorPane> panes) {
        return sessionConfigController.defaultLayoutForPanes(panes);
    }

    List<String> extractSessionFilePaths(Object filesObject) {
        return sessionConfigController.extractSessionFilePaths(filesObject);
    }

    String listSessions() {
        return sessionConfigController.listSessions();
    }

    String listWorkspaceProfiles() {
        return sessionConfigController.listWorkspaceProfiles();
    }

    String defaultWorkspaceProfileName() {
        return sessionConfigController.defaultWorkspaceProfileName();
    }

    File resolveSessionFile(String nameArgument) {
        return sessionConfigController.resolveSessionFile(nameArgument);
    }

    String sanitizeSessionName(String rawName) {
        return sessionConfigController.sanitizeSessionName(rawName);
    }

    boolean hasUnsavedChangesInAnyBuffer() {
        return sessionConfigController.hasUnsavedChangesInAnyBuffer();
    }

    void ensureSettingsFileSeeded(File settingsFile) throws IOException {
        sessionConfigController.ensureSettingsFileSeeded(settingsFile);
    }

    public String setTabSizeFromCommand(String value) {
        return sessionConfigController.setTabSizeFromCommand(value);
    }

    // Go to line
    public String gotoLine(int lineNum) {
        return sessionConfigController.gotoLine(lineNum);
    }

    // Help system
    public void showHelp(String topic) {
        sessionConfigController.showHelp(topic);
    }

    String getHelpText(String topic) {
        return sessionConfigController.getHelpText(topic);
    }

    // Recent files management
    void addToRecentFiles(String filepath) {
        sessionConfigController.addToRecentFiles(filepath);
    }

    public String showRecentFiles() {
        return sessionConfigController.showRecentFiles();
    }

    void showBufferListDialog(String list) {
        sessionConfigController.showBufferListDialog(list);
    }

    // Quit handling
    void handleQuit(boolean force) {
        sessionConfigController.handleQuit(force);
    }

    boolean hasUnsavedChanges(FileBuffer buffer) {
        return sessionConfigController.hasUnsavedChanges(buffer);
    }

    int confirmDiscardChanges(String prompt) {
        return sessionConfigController.confirmDiscardChanges(prompt);
    }

    public String requestQuit(boolean force) {
        return sessionConfigController.requestQuit(force);
    }

    public ConfigManager getConfigManager() {
        return sessionConfigController.getConfigManager();
    }

    public PluginManager getPluginManager() {
        return sessionConfigController.getPluginManager();
    }

    void firePluginEvent(String event) {
        sessionConfigController.firePluginEvent(event);
    }

    public String reloadPlugins() {
        return sessionConfigController.reloadPlugins();
    }

    public String showPluginList() {
        return sessionConfigController.showPluginList();
    }

    public String showPluginPackages() {
        return sessionConfigController.showPluginPackages();
    }

    public String enablePlugin(String name) {
        return sessionConfigController.enablePlugin(name);
    }

    public String disablePlugin(String name) {
        return sessionConfigController.disablePlugin(name);
    }

    public String showPluginInfo(String name) {
        return sessionConfigController.showPluginInfo(name);
    }

    public String showPluginPath() {
        return sessionConfigController.showPluginPath();
    }

    public String createAndOpenPlugin(String name) {
        return sessionConfigController.createAndOpenPlugin(name);
    }

    public String installPluginPackage(String args) {
        return sessionConfigController.installPluginPackage(args);
    }

    public String updatePluginPackage(String args) {
        return sessionConfigController.updatePluginPackage(args);
    }

    public String removePluginPackage(String name) {
        return sessionConfigController.removePluginPackage(name);
    }

    public String pinPluginPackage(String name) {
        return sessionConfigController.pinPluginPackage(name);
    }

    public String unpinPluginPackage(String name) {
        return sessionConfigController.unpinPluginPackage(name);
    }

    public String executeCommand(String cmd) {
        return sessionConfigController.executeCommand(cmd);
    }

    public String getModeName() {
        return sessionConfigController.getModeName();
    }

    public String runUserCommand(String name, String shellCmd) {
        return sessionConfigController.runUserCommand(name, shellCmd);
    }

    public String showUndoHistory() {
        return sessionConfigController.showUndoHistory();
    }

    public String clearSearchHighlights() {
        return sessionConfigController.clearSearchHighlights();
    }

    public String writeAll() {
        return sessionConfigController.writeAll();
    }

    public String quitAll(boolean force) {
        return sessionConfigController.quitAll(force);
    }

    public void showScratchBuffer(String title, String content) {
        sessionConfigController.showScratchBuffer(title, content);
    }

    void openScratchBuffer(String title, String content, boolean returnable) {
        sessionConfigController.openScratchBuffer(title, content, returnable);
    }

    boolean closeReturnableScratchBuffer() {
        return sessionConfigController.closeReturnableScratchBuffer();
    }

    void loadRecentFiles() {
        sessionConfigController.loadRecentFiles();
    }

    void saveRecentFiles() {
        sessionConfigController.saveRecentFiles();
    }

    void loadTrustedProjectRoots() {
        sessionConfigController.loadTrustedProjectRoots();
    }

    void saveTrustedProjectRoots() {
        sessionConfigController.saveTrustedProjectRoots();
    }

    boolean ensureProjectTrustForFile(File file) {
        return sessionConfigController.ensureProjectTrustForFile(file);
    }

    File detectProjectTrustRoot(File file) {
        return sessionConfigController.detectProjectTrustRoot(file);
    }

    boolean hasProjectLocalExecutionSurface(File projectRoot) {
        return sessionConfigController.hasProjectLocalExecutionSurface(projectRoot);
    }

    void appendCommandLog(String entry) {
        sessionConfigController.appendCommandLog(entry);
    }

    void ensureStoreDirectory(File store) throws IOException {
        sessionConfigController.ensureStoreDirectory(store);
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
