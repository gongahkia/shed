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
    JTextField commandBar;
    LineNumberPanel lineNumberPanel;
    JScrollPane editorScrollPane;
    JPanel editorHostPanel;
    JSplitPane editorToolSplit;
    ToolWindowHost toolWindowHost;

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
    GrammarHighlightService grammarHighlightService;
    AsyncJobService asyncJobService;
    BackupScheduler backupScheduler;
    UpdateController updateController;
    QuickfixService quickfixService;
    ProblemsService problemsService;
    PluginManager pluginManager;
    ExtensionRegistry extensionRegistry;
    ExtensionManager extensionManager;
    ScmController scmController;
    CustomEditorController customEditorController;
    NotebookController notebookController;
    DevContainerController devContainerController;
    WorkspaceToolController workspaceToolController;
    RemoteWorkspaceController remoteWorkspaceController;
    TreeGitController treeGitController;
    GitHubCapabilityController gitHubCapabilityController;
    LspController lspController;
    DebugSessionController debugSessionController;
    JobQuickfixController jobQuickfixController;
    ProblemsController problemsController;
    TestController testController;
    FormatOnSaveController formatOnSaveController;
    FormatterController formatterController;
    PeekView peekView;
    TerminalController terminalController;
    MarkdownController markdownController;
    PaneBufferController paneBufferController;
    SessionConfigController sessionConfigController;
    FocusModeController focusModeController;
    SyntaxUiController syntaxUiController;
    EditActionController editActionController;
    InputController inputController;
    PaletteController paletteController;
    EditorUiController editorUiController;
    WorkspaceController workspaceController;
    RecoveryController recoveryController;
    SearchReplaceController searchReplaceController;
    PerfService perfService;
    ApplicationErrorReporter errorReporter;

    // Buffer management
    List<FileBuffer> buffers;
    int currentBufferIndex;
    List<EditorPane> editorPanes;
    int activePaneIndex;
    WindowLayoutNode windowLayoutRoot;
    Component renderedLayoutComponent;
    WorkbenchPlacementState workbenchPlacementState;

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
    boolean goyoModeEnabled;
    boolean limelightEnabled;
    JPanel footerPanel;
    String lastInsertedText;
    Timer externalChangeTimer;
    Timer recoverySnapshotTimer;
    boolean reloadPromptActive;
    List<Object> syntaxHighlightTags;
    List<SyntaxSpan> syntaxForegroundSpans;
    List<SyntaxSpan> lspSemanticSpans;
    List<LspInlayHintOverlay> lspInlayHintOverlays;
    Color syntaxKeywordColor;
    Color syntaxStringColor;
    Color syntaxCommentColor;
    Color syntaxNumberColor;
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
    final List<MultiSelection> extraSelections = new ArrayList<>();
    final List<Object> extraSelectionHighlightTags = new ArrayList<>();
    Map<LspServerKey, LspClient> lspClients;
    Map<String, Integer> lspDocumentVersions;
    Map<LspServerKey, String> lspErrors;
    List<LspClient.TextEdit> pendingLspRenameEdits;
    String pendingLspRenameTarget;
    LspClient.CodeAction pendingLspCodeAction;
    EditorPane treePane;
    FileBuffer treeBuffer;
    File treeRoot;
    Map<FileBuffer, List<String>> treeLineTargets;
    FileBuffer quickfixBuffer;
    int keymapReplayDepth;
    List<RegisterContent> yankRing;
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
    boolean whichKeyHintsEnabled;

    // Constants
    static final String VERSION = "2.0";
    static final Pattern QUICKFIX_PATTERN = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:(.*)$");
    static final Pattern HEX_COLOR_VALUE_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{3})?$");
    static final String WORKSPACE_PROFILE_PREFIX = "workspace-";

    // Constructor
    public Texteditor(String[] args) {
        this(args, new ApplicationErrorReporter());
    }

    Texteditor(String[] args, ApplicationErrorReporter errorReporter) {
        this.errorReporter = errorReporter == null ? new ApplicationErrorReporter() : errorReporter;
        // Initialize managers
        configManager = new ConfigManager();
        extensionRegistry = new ExtensionRegistry();
        scmController = new ScmController(this, extensionRegistry);
        customEditorController = new CustomEditorController(this);
        notebookController = new NotebookController(this);
        devContainerController = new DevContainerController(this);
        workspaceToolController = new WorkspaceToolController(this, extensionRegistry);
        remoteWorkspaceController = new RemoteWorkspaceController(this);
        helpService = new HelpService();
        gitService = new GitService();
        treeService = new TreeService();
        treeGitController = new TreeGitController(this);
        workspaceController = new WorkspaceController(this);
        lspService = new LspService();
        lspController = new LspController(this);
        debugSessionController = new DebugSessionController(this);
        syntaxHighlightService = new SyntaxHighlightService();
        grammarHighlightService = new GrammarHighlightService();
        asyncJobService = new AsyncJobService(200, this.errorReporter);
        backupScheduler = new BackupScheduler();
        gitHubCapabilityController = new GitHubCapabilityController(this);
        updateController = new UpdateController(this);
        quickfixService = new QuickfixService();
        problemsService = new ProblemsService();
        jobQuickfixController = new JobQuickfixController(this);
        problemsController = new ProblemsController(this, problemsService);
        testController = new TestController(this, new TestService(extensionRegistry));
        formatOnSaveController = new FormatOnSaveController(this);
        formatterController = new FormatterController(this);
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
        goyoModeEnabled = false;
        limelightEnabled = false;
        lastInsertedText = "";
        reloadPromptActive = false;
        recoverySnapshotTimer = null;
        syntaxHighlightTags = new ArrayList<>();
        syntaxForegroundSpans = new ArrayList<>();
        lspSemanticSpans = new ArrayList<>();
        lspInlayHintOverlays = new ArrayList<>();
        syntaxKeywordColor = configManager.getSyntaxKeywordColor();
        syntaxStringColor = configManager.getSyntaxStringColor();
        syntaxCommentColor = configManager.getSyntaxCommentColor();
        syntaxNumberColor = configManager.getSyntaxNumberColor();
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
        ptyTerminalPanes = new HashMap<>();
        workbenchPlacementState = new WorkbenchPlacementState();
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
        snippetService.loadFromConfig(configManager);
        bracketColorService = new BracketColorService();
        markdownController = new MarkdownController(this);
        paneBufferController = new PaneBufferController(this);
        peekView = new PeekView(this);
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
        whichKeyHintsEnabled = true;
        focusModeController = new FocusModeController(this);
        loadRecentFiles();
        loadTrustedProjectRoots();
        lastMessage = "";

        syntaxUiController = new SyntaxUiController(this);
        editActionController = new EditActionController(this);
        inputController = new InputController(this);
        paletteController = new PaletteController(this);
        editorUiController = new EditorUiController(this);
        recoveryController = new RecoveryController(this);
        searchReplaceController = new SearchReplaceController(this);
        perfService = new PerfService();

        // Initialize UI
        initializeUI();
        // Set initial mode before any status rendering hooks
        setMode(configManager.getKeymapProfile().usesVimModeHandling() ? EditorMode.NORMAL : EditorMode.INSERT);
        applyThemeColors();

        // Initialize managers that depend on UI
        clipboardManager = new ClipboardManager();
        registerManager = new RegisterManager();
        commandHandler = new CommandHandler(this);
        pluginManager = new PluginManager(configManager, this);
        extensionManager = new ExtensionManager(configManager, extensionRegistry);
        extensionManager.loadInstalled();
        if (toolWindowHost != null) {
            toolWindowHost.refreshExtensionViews();
        }

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
        if (configManager.hasConfigLoadFailure()) {
            showScratchBuffer("[config recovery]", configManager.getConfigLoadReport());
        }
        updateController.startOnLaunch();

        externalChangeTimer = new Timer(2000, e -> checkForExternalChanges());
        externalChangeTimer.start();
        startRecoverySnapshotTimer();
        promptRecoveryRestoreIfAvailable();
        fileWatcherService.start();
    }

    // Initialize UI components
    void initializeUI() {
        editorUiController.initializeUI();
    }

    String handleExtensionCommand(String argument) {
        if (extensionManager == null) {
            return "Extension host is unavailable";
        }
        String result = extensionManager.handle(argument);
        if (toolWindowHost != null) {
            toolWindowHost.refreshExtensionViews();
        }
        if (result != null && (argument == null || argument.isBlank() || "list".equalsIgnoreCase(argument.trim()) || "status".equalsIgnoreCase(argument.trim()))) {
            showScratchBuffer("[extensions]", result);
            return "Showing extensions";
        }
        return result;
    }

    String executeExtensionCommand(String id, String arguments) {
        return extensionManager == null ? null : extensionManager.executeCommand(id, arguments);
    }

    List<String> extensionCommandIds() {
        return extensionManager == null ? List.of() : extensionManager.commandIds();
    }

    String handleExtensionViewCommand(String argument) {
        return toolWindowHost == null ? "Tool window host is unavailable" : toolWindowHost.showExtensionView(argument);
    }

    EditorPane createEditorPane(Dimension screenSize) {
        return editorUiController.createEditorPane(screenSize);
    }

    void bindActivePane(EditorPane pane) {
        editorUiController.bindActivePane(pane);
    }

    EditorPane getActivePane() {
        return editorUiController.getActivePane();
    }

    void activateEditorPane(EditorPane pane) {
        editorUiController.activateEditorPane(pane);
    }

    void requestActivePaneFocus() {
        editorUiController.requestActivePaneFocus();
    }

    void renderWindowLayout() {
        editorUiController.renderWindowLayout();
    }

    void showToolWindow() {
        editorUiController.showToolWindow();
    }

    void hideToolWindow() {
        editorUiController.hideToolWindow();
    }

    void showToolWindow(ToolWindowHost.Tab tab) {
        if (toolWindowHost != null) toolWindowHost.showTab(tab);
    }

    Font resolveEditorFont() {
        return editorUiController.resolveEditorFont();
    }

    Font resolveUiFont() {
        return editorUiController.resolveUiFont();
    }

    Font resolveTerminalFont() {
        return editorUiController.resolveTerminalFont();
    }

    void applyUiFont() {
        editorUiController.applyUiFont();
    }

    Font resolveInstalledFont(String family, int fontSize) {
        return editorUiController.resolveInstalledFont(family, fontSize);
    }

    // Key event handling
    @Override
    public void keyPressed(KeyEvent e) {
        inputController.keyPressed(e);
    }

    boolean applyConfiguredKeybinding(KeyEvent e) {
        return inputController.applyConfiguredKeybinding(e);
    }

    String modeKey(EditorMode mode) {
        return inputController.modeKey(mode);
    }

    String keySpecFromEvent(KeyEvent e) {
        return inputController.keySpecFromEvent(e);
    }

    String ctrlTarget(int keyCode, char keyChar) {
        return inputController.ctrlTarget(keyCode, keyChar);
    }

    List<String> parseKeySequence(String mapping) {
        return inputController.parseKeySequence(mapping);
    }

    KeyEvent keyEventFromToken(String token) {
        return inputController.keyEventFromToken(token);
    }

    KeyStrokeSpec keyStrokeSpec(String token) {
        return inputController.keyStrokeSpec(token);
    }

    // Normal mode key handling
    void handleNormalMode(KeyEvent e) {
        inputController.handleNormalMode(e);
    }

    boolean supportsCountPrefix(KeyEvent e) {
        return inputController.supportsCountPrefix(e);
    }

    void setPendingKeyWithHint(char pendingKey) {
        inputController.setPendingKeyWithHint(pendingKey);
    }

    void showWhichKeyHint(char pendingKey) {
        inputController.showWhichKeyHint(pendingKey);
    }

    String whichKeyHintText(char pendingKey) {
        return inputController.whichKeyHintText(pendingKey);
    }

    // Handle pending multi-key commands
    void handlePendingKey(char c, int code) {
        inputController.handlePendingKey(c, code);
    }

    // Insert mode key handling
    void handleInsertMode(KeyEvent e) {
        inputController.handleInsertMode(e);
    }

    // Visual mode key handling
    void handleVisualMode(KeyEvent e) {
        inputController.handleVisualMode(e);
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
        inputController.handleReplaceMode(e);
    }

    // Command mode key handling
    void handleCommandMode(KeyEvent e) {
        inputController.handleCommandMode(e);
    }

    void openCommandHistorySearch() {
        inputController.openCommandHistorySearch();
    }

    void handleSearchMode(KeyEvent e) {
        inputController.handleSearchMode(e);
    }

    void incrementalSearchPreview() {
        inputController.incrementalSearchPreview();
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
        inputController.browseCommandHistory(direction);
    }

    void addCommandHistory(String entry) {
        inputController.addCommandHistory(entry);
    }

    String completeCommand(String input) {
        return inputController.completeCommand(input);
    }

    String completePath(String prefix, String partialPath) {
        return inputController.completePath(prefix, partialPath);
    }

    void updateCurrentLineHighlight() {
        syntaxUiController.updateCurrentLineHighlight();
    }

    void invalidateCurrentLineHighlight() {
        syntaxUiController.invalidateCurrentLineHighlight();
    }

    String getGitBlameForCurrentLine(FileBuffer buffer) {
        return syntaxUiController.getGitBlameForCurrentLine(buffer);
    }

    void invalidateGitBlame(FileBuffer buffer) {
        syntaxUiController.invalidateGitBlame(buffer);
    }

    void clearGitBlameCache() {
        syntaxUiController.clearGitBlameCache();
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

    void scheduleSyntaxHighlighting() {
        syntaxUiController.scheduleSyntaxHighlighting();
    }

    void scheduleSymbolRefresh() {
        syntaxUiController.scheduleSymbolRefresh();
    }

    void shutdownSyntaxUi() {
        syntaxUiController.shutdown();
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
    javax.swing.JList<LspClient.CompletionItem> completionList;
    javax.swing.DefaultListModel<LspClient.CompletionItem> completionModel;
    javax.swing.JLabel completionSource;
    javax.swing.JTextArea completionDocumentation;
    String completionPrefix;
    javax.swing.JWindow signatureHelpPopup;
    javax.swing.JTextArea signatureHelpText;
    void showInlineCompletion() {
        inputController.showInlineCompletion();
    }
    void dismissCompletionPopup() {
        inputController.dismissCompletionPopup();
    }
    void dismissCompletionPopupForCaretMove() {
        inputController.dismissCompletionPopupForCaretMove();
    }
    boolean isCompletionPopupVisible() {
        return inputController.isCompletionPopupVisible();
    }
    void completionPopupNavigate(int direction) {
        inputController.completionPopupNavigate(direction);
    }
    void completionPopupAccept() {
        inputController.completionPopupAccept();
    }
    List<String> gatherCompletions(String prefix) {
        return inputController.gatherCompletions(prefix);
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
    void refreshExtraSelectionHighlights() {
        editActionController.refreshExtraSelectionHighlights();
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
        recoveryController.checkForExternalChanges();
    }

    void promptExternalConflictForModifiedBuffer(FileBuffer buffer) {
        recoveryController.promptExternalConflictForModifiedBuffer(buffer);
    }

    void showExternalConflictPreview(FileBuffer buffer) {
        recoveryController.showExternalConflictPreview(buffer);
    }

    void startRecoverySnapshotTimer() {
        recoveryController.startRecoverySnapshotTimer();
    }

    void persistRecoverySnapshotsSafely() {
        recoveryController.persistRecoverySnapshotsSafely();
    }

    void scheduleRecoverySnapshotCapture() {
        recoveryController.scheduleRecoverySnapshotCapture();
    }

    void flushScheduledRecoverySnapshotCapture() {
        recoveryController.flushScheduledRecoverySnapshotCapture();
    }

    void persistRecoverySnapshots() throws IOException {
        recoveryController.persistRecoverySnapshots();
    }

    void clearRecoverySnapshots() {
        recoveryController.clearRecoverySnapshots();
    }

    void shutdownRecoveryJournalScheduling() {
        recoveryController.shutdownRecoveryJournalScheduling();
    }

    void promptRecoveryRestoreIfAvailable() {
        recoveryController.promptRecoveryRestoreIfAvailable();
    }

    String handleMarkdownPreview(String args) {
        return markdownController.handlePreviewCommand(args);
    }

    String openMarkdownPreview() {
        return markdownController.openPreview();
    }

    void closeMarkdownPreviewForSource(EditorPane sourcePane) {
        markdownController.closePreviewForSource(sourcePane);
    }

    boolean hasMarkdownPreviewForSource(EditorPane sourcePane) {
        return markdownController.hasPreviewForSource(sourcePane);
    }

    void detachMarkdownPreview(EditorPane pane) {
        markdownController.detachPreview(pane);
    }

    void disposeMarkdownPreviews() {
        markdownController.disposePreviews();
    }

    void refreshMarkdownPreviews() {
        markdownController.refreshPreviews();
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
        return inputController.expandNativeSnippetAtCursor();
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
        recoveryController.registerFileWatch(buffer);
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
        commands.add("drop"); commands.add("task"); commands.add("test"); commands.add("coverage"); commands.add("cov"); commands.add("help"); commands.add("wc"); commands.add("recent");
        commands.add("d"); commands.add("delete"); commands.add("files"); commands.add("folder"); commands.add("projectreplace");
        commands.add("tree"); commands.add("git"); commands.add("grep"); commands.add("copen");
        commands.add("cclose"); commands.add("cnext"); commands.add("cprev"); commands.add("cc");
        commands.add("lsp"); commands.add("peek"); commands.add("format"); commands.add("formatter"); commands.add("debug"); commands.add("dap"); commands.add("definition"); commands.add("typedefinition"); commands.add("hover"); commands.add("references");
        commands.add("diagnostics"); commands.add("diag"); commands.add("problems"); commands.add("dnext"); commands.add("dprev"); commands.add("symbols"); commands.add("sym");
        commands.add("registers"); commands.add("yankring"); commands.add("marks"); commands.add("zen"); commands.add("goyo"); commands.add("limelight"); commands.add("normal");
        commands.add("reload"); commands.add("source"); commands.add("clean"); commands.add("shedclean");
        commands.add("noh"); commands.add("split");
        commands.add("vsplit"); commands.add("close"); commands.add("themes");
        // New markdown commands
        commands.add("toc"); commands.add("outline"); commands.add("toggle");
        commands.add("markdown"); commands.add("md"); commands.add("markdownpreview"); commands.add("mdpreview");
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

    String handleTerminalCommand(String argument) {
        return terminalController.handle(argument);
    }

    File resolveTerminalStartDirectory() {
        return terminalController.resolveTerminalStartDirectory();
    }

    void closeTerminalSession(FileBuffer buffer) {
        terminalController.closeTerminalSession(buffer);
    }

    void closeAllTerminalSessions() {
        terminalController.closeAllTerminalSessions();
    }

    List<Map<String, Object>> serializeTerminalSessionMetadata() {
        return terminalController.serializeSessionMetadata();
    }

    String restoreTerminalSessionMetadata(Object value) {
        return terminalController.restoreSessionMetadata(value).summary();
    }

    void closeExitedTerminal(FileBuffer buffer) {
        terminalController.closeExitedTerminal(buffer);
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
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "ui".equalsIgnoreCase(trimmed)) {
            showToolWindow(ToolWindowHost.Tab.TASKS);
            return "Tasks panel opened";
        }
        if (trimmed.equalsIgnoreCase("text")) {
            return jobQuickfixController.handleTaskCommand("list");
        }
        if (trimmed.regionMatches(true, 0, "text ", 0, 5)) {
            return jobQuickfixController.handleTaskCommand(trimmed.substring(5).trim());
        }
        return jobQuickfixController.handleTaskCommand(argument);
    }

    public String handleTestCommand(String argument) {
        return testController.handle(argument);
    }

    public String handleCoverageCommand(String argument) {
        return testController.handleCoverage(argument);
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

    public String showPerfReport() {
        return showPerfReport("");
    }

    public String showPerfReport(String argument) {
        String action = argument == null ? "" : argument.trim().toLowerCase(java.util.Locale.ROOT);
        if (action.isEmpty() || "status".equals(action)) {
            PerfService service = perfService == null ? new PerfService() : perfService;
            showScratchBuffer("[perf]", LocalPerformanceDiagnostics.overview(
                new DiagnosticLog(errorReporter.getLogPath()), service, sessionConfigController.canBenchmarkWorkspaceIndex()
            ));
            return "Showing local performance diagnostics";
        }
        if ("diagnostics".equals(action) || "log".equals(action)) {
            showScratchBuffer("[diagnostics]", LocalPerformanceDiagnostics.diagnostics(new DiagnosticLog(errorReporter.getLogPath())));
            return "Showing local diagnostic log";
        }
        if ("benchmark".equals(action)) {
            return handleWorkspaceProfileCommand("index benchmark");
        }
        return "Usage: :perf [status|diagnostics|benchmark]";
    }

    public String showLargeFileStatus() {
        showScratchBuffer("[large file]", LargeFileMode.report(getCurrentBuffer()));
        return "Showing large-file status";
    }

    public String showBuildInfo() {
        showScratchBuffer("[build]", BuildInfo.current().render());
        return "Showing build information";
    }

    public String showConfigLoadStatus() {
        showScratchBuffer("[config status]", configManager.getConfigLoadReport());
        return "Showing config status";
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

    boolean isInteractiveGitBufferActive() {
        return treeGitController.isInteractiveGitBufferActive();
    }

    String openInteractiveGitSelection() {
        return treeGitController.openInteractiveGitSelection();
    }

    String openGitLogSelectionAtCaret() {
        return treeGitController.openGitLogSelectionAtCaret();
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

    String handleScmCommand(String argument) {
        return scmController.handle(argument);
    }

    String handleCustomEditorCommand(String argument) {
        return customEditorController.handle(argument);
    }

    String handleNotebookCommand(String argument) {
        return notebookController.handle(argument);
    }

    String handleContainerCommand(String argument) {
        return devContainerController.handle(argument);
    }

    String handleWorkspaceIntegrationCommand(String argument) {
        return workspaceToolController.handle(argument);
    }

    boolean showCustomEditorIfAvailable(EditorPane pane, FileBuffer buffer) {
        return notebookController.showIfAvailable(pane, buffer) || customEditorController.showIfAvailable(pane, buffer);
    }

    String handleRemoteWorkspaceCommand(String argument) {
        return remoteWorkspaceController.handle(argument);
    }

    public String handleGitHubCommand(String argument) {
        return gitHubCapabilityController.handle(argument);
    }

    public String handleUpdateCommand(String argument) {
        return updateController.handle(argument);
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
        return paletteController.showCommandPalette();
    }

    public String showBufferFinder() {
        return paletteController.showBufferFinder();
    }

    public String showGrepFinder(String pattern) {
        return paletteController.showGrepFinder(pattern);
    }

    public String handleProjectReplace(String argument) {
        return paletteController.handleProjectReplace(argument);
    }

    WorkspaceReplaceCoordinator workspaceReplaceCoordinator() { return paletteController.workspaceReplaceCoordinator(); }

    public String showSymbols(String argument) {
        return lspController.showSymbols(argument);
    }

    public String showWorkspaceSymbols(String argument) {
        return lspController.showWorkspaceSymbols(argument);
    }

    String showHeuristicSymbols(String argument) {
        return paletteController.showHeuristicSymbols(argument);
    }

    String showLspSymbols(List<LspClient.NavigationSymbol> symbols, String query, boolean workspace) {
        return paletteController.showLspSymbols(symbols, query, workspace);
    }

    String openLspSymbol(LspClient.NavigationSymbol symbol) {
        return lspController.openLspSymbol(symbol);
    }

    public String handleProblemsCommand(String argument) {
        return problemsController.handleCommand(argument);
    }

    String formatSymbolCandidate(SymbolService.Symbol symbol) {
        return paletteController.formatSymbolCandidate(symbol);
    }

    String describeSymbolCandidate( String selection, Map<String, SymbolService.Symbol> candidateMap, List<SymbolService.Symbol> allSymbols ) {
        return paletteController.describeSymbolCandidate(selection, candidateMap, allSymbols);
    }

    void collectFiles(File directory, List<String> results) {
        paletteController.collectFiles(directory, results);
    }

    List<String> grepFiles(String pattern) {
        return paletteController.grepFiles(pattern);
    }

    void grepFilesRecursive(File directory, String pattern, List<String> results) {
        paletteController.grepFilesRecursive(directory, pattern, results);
    }

    String describeCommandPaletteCandidate(String selection) {
        return paletteController.describeCommandPaletteCandidate(selection);
    }

    String describeGrepCandidate(String selection) {
        return paletteController.describeGrepCandidate(selection);
    }

    String showPaletteDialog(String title, List<String> candidates) {
        return paletteController.showPaletteDialog(title, candidates);
    }

    String showPaletteDialog(String title, List<String> candidates, PalettePreviewProvider previewProvider) {
        return paletteController.showPaletteDialog(title, candidates, previewProvider);
    }

    boolean shouldSkipHiddenPath(File file) {
        return paletteController.shouldSkipHiddenPath(file);
    }

    public String showRegisters() {
        return paletteController.showRegisters();
    }

    public String showMarks() {
        return paletteController.showMarks();
    }

    String trimForRegisterDisplay(String value) {
        return paletteController.trimForRegisterDisplay(value);
    }

    String describeOffset(int offset) {
        return paletteController.describeOffset(offset);
    }

    MinimapPanel activeMinimapPanel;
    public String toggleMinimap() {
        return focusModeController.toggleMinimap();
    }

    public String toggleZenMode() {
        return focusModeController.toggleZenMode();
    }

    public String toggleGoyoMode() {
        return focusModeController.toggleGoyoMode();
    }

    public String toggleLimelight() {
        return focusModeController.toggleLimelight();
    }

    void updateZenModeLayout() {
        focusModeController.updateZenModeLayout();
    }

    void refreshLimelight() {
        focusModeController.refreshLimelight();
    }

    void paintLimelightOverlay(Graphics graphics, JTextArea area) {
        focusModeController.paintLimelightOverlay(graphics, area);
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

    public String formatCurrentBuffer() {
        return formatterController.formatCurrent();
    }

    public String showFormatterPolicy() {
        return formatterController.showPolicyDialog();
    }

    public String handleDebugCommand(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "ui".equalsIgnoreCase(trimmed)) {
            showToolWindow(ToolWindowHost.Tab.DEBUG);
            return "Debug panel opened";
        }
        if (trimmed.equalsIgnoreCase("text")) {
            return debugSessionController.handle("status");
        }
        if (trimmed.regionMatches(true, 0, "text ", 0, 5)) {
            return debugSessionController.handle(trimmed.substring(5).trim());
        }
        return debugSessionController.handle(argument);
    }

    public String lspStatus() {
        return lspController.lspStatus();
    }

    public String lspRestart(String ext) {
        return lspController.lspRestart(ext);
    }

    void showLanguageServices() {
        lspController.handleLspCommand("manage");
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

    public String lspGoToTypeDefinition() {
        return lspController.lspGoToTypeDefinition();
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

    void syncLspChange(FileBuffer buffer, int offset, int removedLength, String insertedText) {
        lspController.syncLspChange(buffer, offset, removedLength, insertedText);
    }

    void syncLspChange(FileBuffer buffer, FileBuffer.DocumentTextChange textChange) {
        lspController.syncLspChange(buffer, textChange);
    }

    void flushLspChange(FileBuffer buffer) {
        lspController.flushPendingLspChange(buffer);
    }

    String lspCompletionUnavailableReason(FileBuffer buffer) {
        return lspController.completionUnavailableReason(buffer);
    }

    void shutdownLspScheduling() {
        lspController.shutdown();
    }

    void scheduleDiagnosticRefresh() {
        lspController.scheduleDiagnosticRefresh();
    }

    void refreshLspDecorations() {
        lspController.refreshLspDecorations();
    }

    public void notifyCurrentBufferSaved() {
        notifyBufferSaved(getCurrentBuffer());
    }

    public void notifyBufferSaved(FileBuffer buffer) {
        lspController.notifyBufferSaved(buffer);
        if (buffer == getCurrentBuffer()) markdownController.refreshPreviewForBuffer(buffer);
        persistRecoverySnapshotsSafely();
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
        editorUiController.setMode(mode);
    }

    Color getModeBackground(EditorMode mode) {
        return editorUiController.getModeBackground(mode);
    }

    // Status bar update
    void updateStatusBar() {
        editorUiController.updateStatusBar();
    }

    void requestStatusBarRefresh() {
        editorUiController.requestStatusBarRefresh();
    }

    String inlinePeekMessage(FileBuffer buffer) {
        return editorUiController.inlinePeekMessage(buffer);
    }

    String quickfixInlinePeek() {
        return editorUiController.quickfixInlinePeek();
    }

    String diagnosticInlinePeek(FileBuffer buffer) {
        return editorUiController.diagnosticInlinePeek(buffer);
    }

    String safePreviewText(String text, int maxLength) {
        return editorUiController.safePreviewText(text, maxLength);
    }

    void appendLspStatus(StringBuilder status, FileBuffer buffer) {
        editorUiController.appendLspStatus(status, buffer);
    }

    void handleDocumentChange(DocumentEvent event) {
        paneBufferController.handleDocumentChange(event);
    }

    void scheduleCompletionAfterDocumentChange(DocumentEvent event) {
        inputController.onDocumentChanged(event);
    }

    void scheduleOpenBufferCompletionIndex() {
        inputController.scheduleOpenBufferWordIndex();
    }

    void scheduleIdleBackup(FileBuffer buffer) {
        paneBufferController.scheduleIdleBackup(buffer);
    }

    void backupBeforeSave(FileBuffer buffer) {
        paneBufferController.backupBeforeSave(buffer);
    }

    void flushPendingBackups() {
        paneBufferController.flushPendingBackups();
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
        testController.updateCoverageGutter(buffer);
    }

    void loadBufferIntoPane(EditorPane pane, FileBuffer buffer, int caretPosition) {
        paneBufferController.loadBufferIntoPane(pane, buffer, caretPosition);
    }

    void handleLargeFileScroll(EditorPane pane) {
        paneBufferController.handleLargeFileScroll(pane);
    }

    void handleLargeFileCaret(EditorPane pane) {
        paneBufferController.handleLargeFileCaret(pane);
    }

    void handleLargeFileResize(EditorPane pane) {
        paneBufferController.handleLargeFileResize(pane);
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

    void scheduleDiffGutter(FileBuffer buffer) {
        paneBufferController.scheduleDiffGutter(buffer);
    }

    // Show message in status bar
    public void showMessage(String message) {
        editorUiController.showMessage(message);
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
        return searchReplaceController.search(pattern);
    }

    public String searchBackward(String pattern) {
        return searchReplaceController.searchBackward(pattern);
    }

    public String substitute(String pattern, String replacement, boolean wholeBuffer, boolean replaceAll) {
        return searchReplaceController.substitute(pattern, replacement, wholeBuffer, replaceAll);
    }

    String substituteCurrentLine(String pattern, String replacement, boolean replaceAll) {
        return searchReplaceController.substituteCurrentLine(pattern, replacement, replaceAll);
    }

    ReplacementResult replaceLiteral(String text, String pattern, String replacement, boolean replaceAll) {
        return searchReplaceController.replaceLiteral(text, pattern, replacement, replaceAll);
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

    public String resetConfigOptionPersistent(String key) {
        return sessionConfigController.resetConfigOptionPersistent(key);
    }

    String setKeybindingPersistent(String scope, String lhs, String mapping) {
        return sessionConfigController.setKeybindingPersistent(scope, lhs, mapping);
    }

    String resetKeybindingPersistent(String scope, String lhs) {
        return sessionConfigController.resetKeybindingPersistent(scope, lhs);
    }

    List<KeymapOverlay.Binding> getEffectiveKeybindings() {
        return sessionConfigController.getEffectiveKeybindings();
    }

    String showKeymapInspector() {
        return sessionConfigController.showKeymapInspector();
    }

    String showEffectiveKeybindings(String query) {
        return sessionConfigController.showEffectiveKeybindings(query);
    }

    boolean isThemeRelatedConfigKey(String key) {
        return sessionConfigController.isThemeRelatedConfigKey(key);
    }

    public String saveConfigToDisk() {
        return sessionConfigController.saveConfigToDisk();
    }

    public String materializeDefaultConfig(boolean overwrite) {
        return sessionConfigController.materializeDefaultConfig(overwrite);
    }

    public String showSettingsInspector() {
        return sessionConfigController.showSettingsInspector();
    }

    public String showTypedSettingsReference() {
        return sessionConfigController.showTypedSettingsReference();
    }

    public String reloadConfigFromDisk() {
        return sessionConfigController.reloadConfigFromDisk();
    }

    String reloadConfigIfChanged() {
        return sessionConfigController.reloadConfigIfChanged();
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

    public String openSnippetsBuffer() {
        return sessionConfigController.openSnippetsBuffer();
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
        return formatOnSaveController.requestAll(false, false);
    }

    public String writeAllAndQuit(boolean force) {
        return formatOnSaveController.requestAll(true, force);
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
        editorUiController.paintColorPreviews(g, area);
    }
    void paintWrapIndicators(Graphics g, JTextArea area) {
        editorUiController.paintWrapIndicators(g, area);
    }

    void paintVisualBlockOverlay(Graphics g, JTextArea area) {
        editorUiController.paintVisualBlockOverlay(g, area);
    }

    void paintDiagnosticOverlay(Graphics g, JTextArea area) {
        editorUiController.paintDiagnosticOverlay(g, area);
    }

    void refreshDiagnosticRanges() {
        editorUiController.refreshDiagnosticRanges();
    }

    void paintSyntaxForegroundOverlay(Graphics g, JTextArea area) {
        editorUiController.paintSyntaxForegroundOverlay(g, area);
    }

    void paintLspSemanticOverlay(Graphics g, JTextArea area) {
        editorUiController.paintLspSemanticOverlay(g, area);
    }

    void paintLspInlayHintOverlay(Graphics g, JTextArea area) {
        editorUiController.paintLspInlayHintOverlay(g, area);
    }

    public void closeEditor() {
        if (closingDown) {
            return;
        }
        closingDown = true;
        disposeMarkdownPreviews();
        if (recoverySnapshotTimer != null) {
            recoverySnapshotTimer.stop();
        }
        flushScheduledRecoverySnapshotCapture();
        shutdownRecoveryJournalScheduling();
        shutdownSyntaxUi();
        shutdownLspScheduling();
        flushPendingBackups();
        if (backupScheduler != null) {
            backupScheduler.close();
        }
        if (ptyTerminalPanes != null) {
            for (PtyTerminalPane terminalPane : new ArrayList<>(ptyTerminalPanes.values())) {
                terminalPane.close();
            }
            ptyTerminalPanes.clear();
        }
        if (asyncJobService != null) {
            asyncJobService.shutdownNow();
        }
        if (debugSessionController != null) {
            debugSessionController.shutdown();
        }
        if (remoteWorkspaceController != null) {
            remoteWorkspaceController.closeAll();
        }
        if (extensionManager != null) {
            extensionManager.close();
        }
        dispose();
        System.exit(0);
    }

    public void rememberExCommand(String command) {
        registerManager.updateLastCommand(command);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        inputController.keyTyped(e);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        inputController.keyReleased(e);
    }

    // Main method
    public static void main(String[] args) {
        ApplicationErrorReporter reporter = new ApplicationErrorReporter();
        reporter.install();
        SwingUtilities.invokeLater(() -> new Texteditor(args, reporter));
    }
}
