package shed;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EditorUiController {
    private final Texteditor editor;
    private final DefaultListModel<String> commandPathModel = new DefaultListModel<>();
    private JPopupMenu commandPathPopup;
    private JList<String> commandPathList;
    private boolean updatingCommandBar;

    EditorUiController(Texteditor editor) {
        this.editor = editor;
    }

    void initializeUI() {
        editor.setTitle("Shed " + editor.VERSION);
        editor.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        editor.setSize(screenSize.width / 2, screenSize.height);
        editor.setLayout(new BorderLayout(5, 5));
        editor.editorHostPanel = new JPanel(new BorderLayout());
        editor.editorHostPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { renderWindowLayout(); }
        });
        editor.undoManager = new BoundedUndoManager(UndoHistoryPolicy.defaults());
        editor.bufferDocumentListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { editor.handleDocumentChange(); }
            public void removeUpdate(DocumentEvent e) { editor.handleDocumentChange(); }
            public void changedUpdate(DocumentEvent e) { editor.handleDocumentChange(); }
        };

        EditorPane initialPane = createEditorPane(screenSize);
        editor.editorPanes.add(initialPane);
        editor.activePaneIndex = 0;
        bindActivePane(initialPane);
        editor.windowLayoutRoot = WindowLayoutNode.leaf(initialPane);
        renderWindowLayout();

        // Create footer
        editor.statusBar = new JLabel();
        editor.statusBar.setBackground(editor.configManager.getStatusBarBackground());
        editor.statusBar.setOpaque(true);
        editor.statusBar.setPreferredSize(new Dimension(screenSize.width / 2, 30));
        editor.statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        editor.statusBar.setForeground(editor.configManager.getStatusBarForeground());

        editor.commandBar = new JTextField();
        editor.commandBar.setBackground(editor.configManager.getCommandBarBackground());
        editor.commandBar.setOpaque(true);
        editor.commandBar.setPreferredSize(new Dimension(screenSize.width / 2, 28));
        editor.commandBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        editor.commandBar.setForeground(editor.configManager.getCommandBarForeground());
        editor.commandBar.setCaret(new BlockCaret());
        editor.commandBar.getCaret().setBlinkRate(500);
        editor.commandBar.setCaretColor(editor.configManager.getCaretColor());
        editor.commandBar.setEditable(false);
        editor.commandBar.setFocusable(false);
        initializeCommandPrompt();

        JPanel footerPanel = new JPanel(new GridLayout(2, 1));
        footerPanel.add(editor.statusBar);
        footerPanel.add(editor.commandBar);

        // Add components
        editor.add(editor.editorHostPanel, BorderLayout.CENTER);
        editor.add(footerPanel, BorderLayout.SOUTH);

        // Window close handler
        editor.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                editor.handleQuit(false);
            }
        });
    }

    private void initializeCommandPrompt() {
        commandPathList = new JList<>(commandPathModel);
        commandPathList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        commandPathList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 1) {
                    acceptCommandPathSuggestion();
                }
            }
        });
        commandPathPopup = new JPopupMenu();
        commandPathPopup.setBorder(BorderFactory.createLineBorder(editor.configManager.getSelectionColor()));
        commandPathPopup.add(new JScrollPane(commandPathList));
        editor.commandBar.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { commandPromptChanged(); }
            @Override public void removeUpdate(DocumentEvent event) { commandPromptChanged(); }
            @Override public void changedUpdate(DocumentEvent event) { commandPromptChanged(); }
        });
        editor.commandBar.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent event) {
                if (editor.editorState.mode == EditorMode.COMMAND && editor.inputController.handleCommandPromptKeyPressed(event)) {
                    event.consume();
                }
            }

            @Override public void keyTyped(java.awt.event.KeyEvent event) {
                if (editor.editorState.mode == EditorMode.COMMAND && (Character.isISOControl(event.getKeyChar())
                    || event.isControlDown() || event.isMetaDown() || event.isAltDown())) {
                    event.consume();
                }
            }
        });
    }

    private void commandPromptChanged() {
        if (updatingCommandBar || editor.editorState.mode != EditorMode.COMMAND) {
            return;
        }
        editor.editorState.commandBuffer = editor.commandBar.getText();
        editor.updateSubstitutePreview();
        updateCommandPathSuggestions();
    }

    void setCommandPromptText(String text) {
        if (editor.commandBar == null) {
            return;
        }
        String value = text == null ? "" : text;
        editor.editorState.commandBuffer = value;
        updatingCommandBar = true;
        try {
            if (!value.equals(editor.commandBar.getText())) {
                editor.commandBar.setText(value);
            }
            editor.commandBar.setCaretPosition(value.length());
        } finally {
            updatingCommandBar = false;
        }
        editor.updateSubstitutePreview();
        updateCommandPathSuggestions();
    }

    void configureCommandPrompt(EditorMode mode) {
        if (editor.commandBar == null) {
            return;
        }
        boolean active = mode == EditorMode.COMMAND;
        editor.commandBar.setEditable(active);
        editor.commandBar.setFocusable(active);
        if (!active) {
            dismissCommandPathSuggestions();
            if (editor.commandBar.isFocusOwner()) {
                SwingUtilities.invokeLater(this::requestActivePaneFocus);
            }
            return;
        }
        SwingUtilities.invokeLater(() -> {
            editor.commandBar.requestFocusInWindow();
            editor.commandBar.setCaretPosition(editor.commandBar.getDocument().getLength());
        });
    }

    boolean acceptCommandPathSuggestion() {
        if (commandPathPopup == null || !commandPathPopup.isVisible()) {
            return false;
        }
        String selection = commandPathList.getSelectedValue();
        if (selection == null && !commandPathModel.isEmpty()) {
            selection = commandPathModel.getElementAt(0);
        }
        if (selection == null) {
            return false;
        }
        setCommandPromptText(selection);
        dismissCommandPathSuggestions();
        editor.commandBar.requestFocusInWindow();
        return true;
    }

    private void updateCommandPathSuggestions() {
        if (editor.commandBar == null || !editor.commandBar.isFocusOwner()) {
            dismissCommandPathSuggestions();
            return;
        }
        List<String> suggestions = CommandPathCompletion.suggestions(editor.editorState.commandBuffer, commandPathBaseDirectory());
        if (suggestions.isEmpty()) {
            dismissCommandPathSuggestions();
            return;
        }
        commandPathModel.clear();
        suggestions.forEach(commandPathModel::addElement);
        commandPathList.setSelectedIndex(0);
        int rows = Math.min(8, commandPathModel.size());
        commandPathList.setVisibleRowCount(rows);
        commandPathPopup.setPopupSize(Math.max(260, editor.commandBar.getWidth()), rows * editor.commandBar.getFontMetrics(editor.commandBar.getFont()).getHeight() + 8);
        if (!commandPathPopup.isVisible()) {
            commandPathPopup.show(editor.commandBar, 0, editor.commandBar.getHeight());
        }
    }

    private File commandPathBaseDirectory() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer != null && buffer.hasFilePath()) {
            File parent = new File(buffer.getFilePath()).getParentFile();
            if (parent != null) {
                return parent;
            }
        }
        return editor.treeRoot != null ? editor.treeRoot : new File(".");
    }

    private void dismissCommandPathSuggestions() {
        if (commandPathPopup != null) {
            commandPathPopup.setVisible(false);
        }
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
                int rulerCol = editor.configManager.getRulerColumn();
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
        textArea.addKeyListener(editor);
        AccessibilitySupport.describe(textArea, "Editor", "Active source editor. Use keyboard navigation and configured keymap commands to edit the current buffer.");
        textArea.setFont(resolveEditorFont());
        textArea.setTabSize(editor.configManager.getTabSize());
        textArea.setCaret(new BlockCaret());
        textArea.getCaret().setBlinkRate(0);
        textArea.setCaretColor(editor.configManager.getCaretColor());
        textArea.setForeground(editor.configManager.getEditorForeground());
        textArea.setEditable(false);
        textArea.setSelectionColor(editor.configManager.getSelectionColor());
        textArea.setSelectedTextColor(editor.configManager.getSelectionTextColor());

        LineNumberPanel paneLineNumberPanel = new LineNumberPanel(textArea);
        paneLineNumberPanel.setMode(editor.lineNumberMode);
        paneLineNumberPanel.setHighlightCurrentLine(editor.configManager.getShowCurrentLine());

        JScrollPane paneScrollPane = new JScrollPane(textArea);
        paneScrollPane.setWheelScrollingEnabled(true);
        paneScrollPane.getVerticalScrollBar().setUnitIncrement(Math.max(16, textArea.getFontMetrics(textArea.getFont()).getHeight()));
        if (editor.lineNumberMode != LineNumberMode.NONE) {
            paneScrollPane.setRowHeaderView(paneLineNumberPanel);
        }
        SearchManager paneSearchManager = new SearchManager(textArea);
        final EditorPane[] paneRef = new EditorPane[1];
        textArea.addCaretListener(e -> {
            if (paneRef[0] != null && paneRef[0] != getActivePane()) {
                activateEditorPane(paneRef[0]);
            }
            editor.ensureCaretVisible(textArea);
            if (paneRef[0] != null) {
                editor.handleLargeFileCaret(paneRef[0]);
            }
            editor.updateCurrentLineHighlight();
            editor.updateMatchingBracketHighlight();
            if (editor.lineNumberPanel != null) {
                editor.lineNumberPanel.repaint();
            }
            editor.dismissCompletionPopup();
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

            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                String result = editor.openGitLogSelectionAtCaret();
                if (!result.isEmpty()) {
                    editor.showMessage(result);
                }
            }
        });

        EditorPane pane = new EditorPane(textArea, paneLineNumberPanel, paneScrollPane, paneSearchManager);
        paneRef[0] = pane;
        paneLineNumberPanel.setBreakpointToggleListener(line -> {
            if (paneRef[0] == null) return;
            activateEditorPane(paneRef[0]);
            if (editor.debugSessionController != null) editor.debugSessionController.toggleBreakpoint(paneRef[0].getBuffer(), line);
        });
        paneScrollPane.getVerticalScrollBar().addAdjustmentListener(event -> {
            if (!event.getValueIsAdjusting()) {
                editor.handleLargeFileScroll(pane);
            }
        });
        paneScrollPane.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                editor.handleLargeFileResize(pane);
            }
        });
        return pane;
    }


    void bindActivePane(EditorPane pane) {
        if (pane == null) {
            return;
        }
        editor.writingArea = pane.getTextArea();
        editor.lineNumberPanel = pane.getLineNumberPanel();
        editor.editorScrollPane = pane.getScrollPane();
        editor.searchManager = pane.getSearchManager();
    }


    EditorPane getActivePane() {
        if (editor.activePaneIndex < 0 || editor.activePaneIndex >= editor.editorPanes.size()) {
            return null;
        }
        return editor.editorPanes.get(editor.activePaneIndex);
    }


    void activateEditorPane(EditorPane pane) {
        int index = editor.editorPanes.indexOf(pane);
        if (index < 0 || index == editor.activePaneIndex) {
            return;
        }
        editor.detachActiveDocumentListener();
        editor.activePaneIndex = index;
        bindActivePane(pane);
        editor.attachActiveDocumentListener();
        editor.currentBufferIndex = pane.getBuffer() == null ? -1 : editor.buffers.indexOf(pane.getBuffer());
        editor.updateCurrentLineHighlight();
        editor.refreshLineNumberPanel();
        updateStatusBar();
    }


    void requestActivePaneFocus() {
        EditorPane pane = getActivePane();
        if (pane != null && pane.getTerminalPane() != null) {
            pane.getTerminalPane().requestFocusInWindow();
            return;
        }
        if (editor.writingArea != null) {
            editor.writingArea.requestFocusInWindow();
        }
    }


    void renderWindowLayout() {
        if (editor.renderedLayoutComponent != null) {
            editor.editorHostPanel.remove(editor.renderedLayoutComponent);
        }
        int width = Math.max(editor.editorHostPanel.getWidth(), editor.getWidth());
        int height = Math.max(editor.editorHostPanel.getHeight(), editor.getHeight());
        editor.renderedLayoutComponent = editor.windowLayoutRoot == null ? new JPanel()
            : editor.windowLayoutRoot.render(getActivePane(), width, height);
        editor.editorHostPanel.add(editor.renderedLayoutComponent, BorderLayout.CENTER);
        editor.updateZenModeLayout();
        editor.editorHostPanel.revalidate();
        editor.editorHostPanel.repaint();
    }


    Font resolveEditorFont() {
        int fontSize = editor.configManager.getFontSize();
        String configuredFamily = editor.configManager.getFontFamily();
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
        try (InputStream resource = EditorUiController.class.getClassLoader().getResourceAsStream("assets/hackregfont.ttf")) {
            Font hackFont = resource == null
                ? Font.createFont(Font.TRUETYPE_FONT, new File("assets/hackregfont.ttf"))
                : Font.createFont(Font.TRUETYPE_FONT, resource);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(hackFont);
            return hackFont.deriveFont((float) fontSize);
        } catch (Exception e) {
            return null;
        }
    }


    void setMode(EditorMode mode) {
        EditorMode oldMode = editor.editorState.mode;
        if ((oldMode == EditorMode.VISUAL || oldMode == EditorMode.VISUAL_LINE) && mode != EditorMode.VISUAL && mode != EditorMode.VISUAL_LINE) {
            editor.editorState.lastVisualStart = editor.writingArea.getSelectionStart();
            editor.editorState.lastVisualEnd = editor.writingArea.getSelectionEnd();
            editor.editorState.lastVisualMode = oldMode;
        }
        editor.editorState.mode = mode;
        FileBuffer buffer = editor.getCurrentBuffer();
        editor.writingArea.setEditable(mode.isEditable() && (buffer == null || !buffer.isLargeFile()));
        editor.writingArea.setBackground(getModeBackground(mode));
        configureCommandPrompt(mode);
        editor.updateZenModeLayout();
        if (mode != EditorMode.COMMAND) {
            editor.clearSubstitutePreview();
        }
        updateStatusBar();
        if (oldMode != mode) {
            editor.animateModeTransition(oldMode, mode);
            editor.firePluginEvent("ModeChange");
        }
    }


    Color getModeBackground(EditorMode mode) {
        switch (mode) {
            case INSERT:
                return editor.configManager.getInsertColor();
            case VISUAL:
            case VISUAL_LINE:
                return editor.configManager.getVisualColor();
            case REPLACE:
                return editor.configManager.getReplaceColor();
            case COMMAND:
            case SEARCH:
                return editor.configManager.getCommandColor();
            case NORMAL:
            default:
                return editor.configManager.getNormalColor();
        }
    }


    void updateStatusBar() {
        FileBuffer buffer = editor.getCurrentBuffer();
        StringBuilder status = new StringBuilder();

        if (buffer != null) {
            editor.pollLspNotifications(buffer);
            status.append(buffer.getDisplayName());
            if (buffer.isModified()) {
                status.append(" [+]");
            }
            status.append("  ");
        }

        try {
            int pos = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(pos);
            int col = pos - editor.writingArea.getLineStartOffset(line);
            status.append((line + 1)).append(":").append((col + 1)).append("  ");
        } catch (BadLocationException e) {
            status.append("1:1  ");
        }

        String breadcrumb = editor.findCurrentBreadcrumb();
        if (breadcrumb != null && !breadcrumb.isBlank()) {
            status.append(breadcrumb).append("  ");
        }

        EditorMode modeForStatus = editor.editorState.mode == null ? EditorMode.NORMAL : editor.editorState.mode;
        status.append(modeForStatus.getDisplayName()).append("  ");
        if (editor.dramaticUiEnabled && editor.isDramaticPerformanceThrottled()) {
            status.append("dramatic:throttled").append("  ");
        }

        if (buffer != null) {
            status.append(buffer.getFileType().getDisplayName()).append("  ");
            status.append(buffer.getEncoding()).append("/").append(buffer.getLineEndingLabel()).append("  ");
            appendLspStatus(status, buffer);
        }

        if (editor.gitBranch != null && !editor.gitBranch.isEmpty()) {
            status.append("git:").append(editor.gitBranch).append("  ");
        }

        int lineCount = editor.writingArea.getLineCount();
        status.append(lineCount).append(" line").append(lineCount != 1 ? "s" : "");
        if (buffer != null && buffer.isLargeFile() && buffer.isShowingPreviewOnly()) {
            status.append("  preview");
        }

        editor.statusBar.setText(status.toString());

        String inlinePeek = inlinePeekMessage(buffer);
        if ((editor.editorState.mode == EditorMode.COMMAND || editor.editorState.mode == EditorMode.SEARCH) && !editor.editorState.commandBuffer.isEmpty()) {
            setCommandBarDisplay(editor.editorState.commandBuffer);
        } else if (editor.lastMessage != null && !editor.lastMessage.isEmpty()) {
            setCommandBarDisplay(editor.lastMessage);
        } else if (inlinePeek != null) {
            setCommandBarDisplay(inlinePeek);
        } else {
            String blame = editor.getGitBlameForCurrentLine(buffer);
            setCommandBarDisplay(blame != null ? blame : "");
        }
        editor.applyDramaticFooterStyling();
    }

    private void setCommandBarDisplay(String text) {
        if (editor.commandBar == null) {
            return;
        }
        String value = text == null ? "" : text;
        if (!value.equals(editor.commandBar.getText())) {
            updatingCommandBar = true;
            try {
                editor.commandBar.setText(value);
            } finally {
                updatingCommandBar = false;
            }
        }
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
        if (!editor.isQuickfixBufferActive()) {
            return null;
        }
        int line = editor.getCurrentCaretLine() + 1;
        QuickfixService.Entry entry = editor.quickfixService.atLine(line);
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
        LspClient client = editor.existingLspClient(buffer);
        if (client == null) {
            return null;
        }
        List<LspClient.Diagnostic> diagnostics = client.getDiagnostics(editor.bufferUri(buffer));
        if (diagnostics == null || diagnostics.isEmpty()) {
            return null;
        }
        int caretLine = editor.getCurrentCaretLine();
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
        String severity = editor.diagnosticSeverityLabel(best.getSeverity()).toLowerCase(Locale.ROOT);
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
        LspClient client = editor.existingLspClient(buffer);
        if (client == null || !buffer.hasFilePath()) {
            return;
        }
        List<LspClient.Diagnostic> diagnosticEntries = client.getDiagnostics(editor.bufferUri(buffer));
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


    public void showMessage(String message) {
        editor.lastMessage = message == null ? "" : message;
        if (!editor.lastMessage.isEmpty()) {
            String normalized = editor.lastMessage.toLowerCase();
            if (normalized.startsWith("error") || normalized.startsWith("invalid") || normalized.contains(" failed")) {
                editor.playCue(CueType.ERROR);
            } else if (normalized.contains("opened") || normalized.contains("saved") || normalized.contains("loaded")) {
                editor.playCue(CueType.SUCCESS);
            }
        }
        if (editor.messageResetTimer != null) {
            editor.messageResetTimer.stop();
        }
        editor.messageResetTimer = new javax.swing.Timer(3000, e -> {
            editor.lastMessage = "";
            updateStatusBar();
        });
        editor.messageResetTimer.setRepeats(false);
        editor.messageResetTimer.start();
        updateStatusBar();
    }


    void paintColorPreviews(Graphics g, JTextArea area) {
        String text = area.getText();
        if (text.isEmpty()) return;
        FontMetrics fm = g.getFontMetrics(area.getFont());
        Rectangle clip = g.getClipBounds();
        java.util.regex.Matcher m = editor.HEX_COLOR_PATTERN.matcher(text);
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
        if (editor.editorState.mode != EditorMode.VISUAL_BLOCK || area != editor.writingArea) return;
        int[] bounds = editor.getVisualBlockBounds();
        if (bounds == null) return;
        int startLine = bounds[0], endLine = bounds[1], startCol = bounds[2], endCol = bounds[3];
        Graphics2D g2 = (Graphics2D) g;
        Color sel = editor.configManager.getSelectionColor();
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
        long started = System.nanoTime();
        try {
        if (area == null || editor.diagnosticRanges.isEmpty()) return;
        Graphics2D g2 = (Graphics2D) g;
        FontMetrics fm = g2.getFontMetrics(area.getFont());
        int ascent = fm.getAscent();
        int descent = fm.getDescent();
        int docLen = area.getDocument().getLength();
        for (int[] dr : editor.diagnosticRanges) {
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
        } finally {
            if (editor.perfService != null) {
                editor.perfService.recordDuration("diagnostic.paint", started, "ranges=" + editor.diagnosticRanges.size());
            }
        }
    }


    void refreshDiagnosticRanges() {
        long started = System.nanoTime();
        try {
        editor.diagnosticRanges.clear();
        EditorPane diagPane = getActivePane();
        if (diagPane != null && diagPane.getLineNumberPanel() != null) diagPane.getLineNumberPanel().updateDiagnosticMarkers(null);
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) { editor.writingArea.repaint(); return; }
        LspClient client = editor.lspClients.get(editor.bufferExtension(buffer));
        if (client == null || !client.isAlive()) { editor.writingArea.repaint(); return; }
        String uri = editor.bufferUri(buffer);
        List<LspClient.Diagnostic> diags = client.getDiagnostics(uri);
        if (diags == null || diags.isEmpty()) { editor.writingArea.repaint(); return; }
        try {
            for (LspClient.Diagnostic d : diags) {
                int line = d.getLine();
                if (line >= editor.writingArea.getLineCount()) continue;
                int lineStart = editor.writingArea.getLineStartOffset(line);
                int lineEnd = editor.writingArea.getLineEndOffset(line);
                int startOff = Math.min(lineStart + d.getCharacter(), lineEnd);
                int endOff = Math.min(startOff + 1, lineEnd); // at least 1 char wide
                // try to expand to end of token
                String text = editor.writingArea.getText();
                while (endOff < lineEnd && endOff < text.length() && !Character.isWhitespace(text.charAt(endOff))) endOff++;
                editor.diagnosticRanges.add(new int[]{startOff, endOff, d.getSeverity()});
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
        editor.writingArea.repaint();
        } finally {
            if (editor.perfService != null) {
                editor.perfService.recordDuration("diagnostic.refresh", started, "ranges=" + editor.diagnosticRanges.size());
            }
        }
    }


    void paintSyntaxForegroundOverlay(Graphics g, JTextArea area) {
        long started = System.nanoTime();
        try {
        if (area == null || area != editor.writingArea || editor.syntaxForegroundSpans.isEmpty()) {
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

        for (SyntaxSpan span : editor.syntaxForegroundSpans) {
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
        } finally {
            if (editor.perfService != null) {
                editor.perfService.recordDuration("syntax.paint", started, "spans=" + editor.syntaxForegroundSpans.size());
            }
        }
    }

}
