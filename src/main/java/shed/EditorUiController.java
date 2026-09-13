package shed;

import shed.api.LanguageProfile;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.Rectangle2D;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EditorUiController {
    private static final int STATUS_REFRESH_DEBOUNCE_MS = 33;
    private static final String BASE_UI_FONT_PROPERTY = "shed.base-ui-font";
    private static final String BASE_DIALOG_SIZE_PROPERTY = "shed.base-dialog-size";
    private static final String DIALOG_UI_READY_PROPERTY = "shed.dialog-ui-ready";
    private static final String DIALOG_UI_MANAGED_PROPERTY = "shed.dialog-ui-managed";
    private static final int SCREEN_EDGE_MARGIN = 24;
    private final Texteditor editor;
    private final Map<Object, Font> systemUiFonts;
    private final Map<?, ?> desktopTextRenderingHints;
    private final DefaultListModel<String> commandPathModel = new DefaultListModel<>();
    private final Map<JTextArea, FileTreeDropPlacement> explorerDropPreviews = new IdentityHashMap<>();
    private JPopupMenu commandPathPopup;
    private JList<String> commandPathList;
    private boolean updatingCommandBar;
    private boolean dialogUiScalingListenerInstalled;
    private Timer statusRefreshTimer;

    EditorUiController(Texteditor editor) {
        this.editor = editor;
        this.systemUiFonts = captureSystemUiFonts();
        this.desktopTextRenderingHints = desktopTextRenderingHints();
    }

    void initializeUI() {
        editor.setTitle("Shed " + editor.VERSION);
        editor.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setApplicationIcon();
        applyUiFont();
        installDialogUiScaling();
        installUiZoomShortcuts();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        editor.setSize(screenSize.width / 2, screenSize.height);
        editor.setLayout(new BorderLayout(5, 5));
        editor.editorHostPanel = new JPanel(new BorderLayout());
        editor.editorHostPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { renderWindowLayout(); }
        });
        editor.undoManager = new BoundedUndoManager(UndoHistoryPolicy.defaults());
        editor.bufferDocumentListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { editor.handleDocumentChange(e); }
            public void removeUpdate(DocumentEvent e) { editor.handleDocumentChange(e); }
            public void changedUpdate(DocumentEvent e) { editor.handleDocumentChange(e); }
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
        editor.statusBar.setForeground(editor.configManager.getStatusBarForeground());

        editor.commandBar = new JTextField();
        editor.commandBar.setBackground(editor.configManager.getCommandBarBackground());
        editor.commandBar.setOpaque(true);
        editor.commandBar.setForeground(editor.configManager.getCommandBarForeground());
        editor.commandBar.setCaret(new BlockCaret());
        editor.commandBar.getCaret().setBlinkRate(500);
        editor.commandBar.setCaretColor(editor.configManager.getCaretColor());
        editor.commandBar.setEditable(false);
        editor.commandBar.setFocusable(false);
        initializeCommandPrompt();

        editor.footerPanel = new JPanel(new BorderLayout());
        editor.footerPanel.add(editor.statusBar, BorderLayout.NORTH);
        editor.footerPanel.add(editor.commandBar, BorderLayout.SOUTH);
        applyFooterUiMetrics();

        editor.toolWindowHost = new ToolWindowHost(editor);
        editor.toolWindowHost.setVisible(false);
        editor.editorToolSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editor.editorHostPanel, editor.toolWindowHost);
        editor.editorToolSplit.setResizeWeight(1.0);
        editor.editorToolSplit.setDividerSize(0);
        editor.editorToolSplit.setBorder(BorderFactory.createEmptyBorder());
        // Add components
        editor.add(editor.editorToolSplit, BorderLayout.CENTER);
        editor.add(editor.footerPanel, BorderLayout.SOUTH);

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
        dismissCommandPathSuggestions();
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
        dismissCommandPathSuggestions();
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

    boolean showCommandPathSuggestions() {
        if (editor.commandBar == null || !editor.commandBar.isFocusOwner()) {
            dismissCommandPathSuggestions();
            return false;
        }
        List<String> suggestions = CommandPathCompletion.suggestions(editor.editorState.commandBuffer, commandPathBaseDirectory());
        if (suggestions.isEmpty()) {
            dismissCommandPathSuggestions();
            return false;
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
        return true;
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
                if (g instanceof Graphics2D graphics) {
                    applyDesktopTextRenderingHints(graphics, desktopTextRenderingHints);
                }
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
                editor.paintLspSemanticOverlay(g, this);
                editor.paintLimelightOverlay(g, this);
                paintDiagnosticOverlay(g, this);
                paintVisualBlockOverlay(g, this);
                editor.paintLspInlayHintOverlay(g, this);
                if (getLineWrap()) paintWrapIndicators(g, this);
                paintColorPreviews(g, this);
                paintExplorerFileDropPreview(g, this);
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
        final int[] previousCaretLine = {-1};
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
            if (paneRef[0] != null) {
                try {
                    int currentCaretLine = textArea.getLineOfOffset(textArea.getCaretPosition());
                    paneRef[0].getLineNumberPanel().repaintForCaretChange(previousCaretLine[0], currentCaretLine);
                    previousCaretLine[0] = currentCaretLine;
                } catch (BadLocationException ignored) {
                }
            }
            editor.dismissCompletionPopupForCaretMove();
            editor.refreshLimelight();
            requestStatusBarRefresh();
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
        installExplorerFileDropTarget(textArea, pane);
        paneLineNumberPanel.setBreakpointToggleListener(line -> {
            if (paneRef[0] == null) return;
            activateEditorPane(paneRef[0]);
            if (editor.debugSessionController != null) editor.debugSessionController.toggleBreakpoint(paneRef[0].getBuffer(), line);
        });
        paneScrollPane.getVerticalScrollBar().addAdjustmentListener(event -> {
            if (!event.getValueIsAdjusting()) {
                editor.handleLargeFileScroll(pane);
                editor.scheduleSyntaxHighlighting();
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


    private void installExplorerFileDropTarget(JTextArea textArea, EditorPane pane) {
        new DropTarget(textArea, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override public void dragEnter(DropTargetDragEvent event) {
                acceptExplorerFileDrag(event, textArea);
            }

            @Override public void dragOver(DropTargetDragEvent event) {
                acceptExplorerFileDrag(event, textArea);
            }

            @Override public void dragExit(DropTargetEvent event) {
                clearExplorerFileDropPreview(textArea);
            }

            @Override public void drop(DropTargetDropEvent event) {
                clearExplorerFileDropPreview(textArea);
                if (!event.isDataFlavorSupported(FileTreePanel.EXPLORER_FILE_FLAVOR)) {
                    event.rejectDrop();
                    return;
                }
                try {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable transferable = event.getTransferable();
                    Object value = transferable.getTransferData(FileTreePanel.EXPLORER_FILE_FLAVOR);
                    if (!(value instanceof File file) || !file.isFile()) {
                        event.dropComplete(false);
                        return;
                    }
                    FileTreeDropPlacement placement = FileTreeDropPlacement.forPoint(event.getLocation(), textArea.getSize());
                    String result = editor.openFileInSplit(file, pane, placement.orientation, placement.newPaneFirst);
                    editor.showMessage(result);
                    event.dropComplete(result.startsWith("Opened in split:"));
                } catch (Exception error) {
                    editor.showMessage("File drop failed: " + error.getMessage());
                    event.dropComplete(false);
                }
            }

            private void acceptExplorerFileDrag(DropTargetDragEvent event, JTextArea target) {
                if (event.isDataFlavorSupported(FileTreePanel.EXPLORER_FILE_FLAVOR)) {
                    event.acceptDrag(DnDConstants.ACTION_COPY);
                    showExplorerFileDropPreview(target, event.getLocation());
                } else {
                    event.rejectDrag();
                    clearExplorerFileDropPreview(target);
                }
            }
        }, true);
    }


    private void showExplorerFileDropPreview(JTextArea area, Point point) {
        FileTreeDropPlacement placement = FileTreeDropPlacement.forPoint(point, area.getSize());
        if (placement == explorerDropPreviews.put(area, placement)) return;
        area.repaint();
    }

    private void clearExplorerFileDropPreview(JTextArea area) {
        if (explorerDropPreviews.remove(area) != null) area.repaint();
    }

    private void paintExplorerFileDropPreview(Graphics graphics, JTextArea area) {
        FileTreeDropPlacement placement = explorerDropPreviews.get(area);
        if (placement == null || area.getWidth() <= 0 || area.getHeight() <= 0) return;
        int inset = UiZoom.scale(8, editor.configManager.getUiZoom());
        Rectangle region = placement.previewBounds(area.getSize());
        Rectangle preview = new Rectangle(region.x + inset, region.y + inset,
            Math.max(0, region.width - inset * 2), Math.max(0, region.height - inset * 2));
        if (preview.isEmpty()) return;
        Graphics2D overlay = (Graphics2D) graphics.create();
        try {
            Color accent = editor.configManager.getSelectionColor();
            overlay.setComposite(AlphaComposite.SrcOver.derive(0.30f));
            overlay.setColor(accent);
            overlay.fill(preview);
            overlay.setComposite(AlphaComposite.SrcOver);
            overlay.setColor(editor.configManager.getCaretColor());
            overlay.setStroke(new BasicStroke(Math.max(1f, UiZoom.scale(2, editor.configManager.getUiZoom()))));
            overlay.drawRect(preview.x, preview.y, Math.max(0, preview.width - 1), Math.max(0, preview.height - 1));
        } finally {
            overlay.dispose();
        }
    }


    static enum FileTreeDropPlacement {
        LEFT(WindowLayoutNode.Orientation.HORIZONTAL, true),
        RIGHT(WindowLayoutNode.Orientation.HORIZONTAL, false),
        TOP(WindowLayoutNode.Orientation.VERTICAL, true),
        BOTTOM(WindowLayoutNode.Orientation.VERTICAL, false);

        final WindowLayoutNode.Orientation orientation;
        final boolean newPaneFirst;

        FileTreeDropPlacement(WindowLayoutNode.Orientation orientation, boolean newPaneFirst) {
            this.orientation = orientation;
            this.newPaneFirst = newPaneFirst;
        }

        static FileTreeDropPlacement forPoint(Point point, Dimension size) {
            if (point == null || size == null || size.width <= 0 || size.height <= 0) {
                return RIGHT;
            }
            int horizontalEdge = Math.max(1, size.width / 4);
            if (point.x < horizontalEdge) return LEFT;
            if (point.x >= size.width - horizontalEdge) return RIGHT;
            return point.y < size.height / 2 ? TOP : BOTTOM;
        }

        Rectangle previewBounds(Dimension size) {
            int width = Math.max(0, size == null ? 0 : size.width);
            int height = Math.max(0, size == null ? 0 : size.height);
            return switch (this) {
                case LEFT -> new Rectangle(0, 0, width / 2, height);
                case RIGHT -> new Rectangle(width / 2, 0, width - width / 2, height);
                case TOP -> new Rectangle(0, 0, width, height / 2);
                case BOTTOM -> new Rectangle(0, height / 2, width, height - height / 2);
            };
        }
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
        if (index < 0 || pane.isHiddenByFocusMode() || index == editor.activePaneIndex) {
            return;
        }
        editor.detachActiveDocumentListener();
        editor.clearLspDecorations();
        editor.activePaneIndex = index;
        bindActivePane(pane);
        editor.writingArea.setTabSize(editor.effectiveTabSize(pane.getBuffer()));
        editor.attachActiveDocumentListener();
        editor.currentBufferIndex = pane.getBuffer() == null ? -1 : editor.buffers.indexOf(pane.getBuffer());
        editor.updateCurrentLineHighlight();
        editor.refreshLineNumberPanel();
        editor.refreshLspDecorations();
        updateStatusBar();
        editor.refreshLimelight();
    }


    void requestActivePaneFocus() {
        EditorPane pane = getActivePane();
        if (pane != null && pane.getTerminalPane() != null) {
            pane.getTerminalPane().requestFocusInWindow();
            return;
        }
        if (pane != null && pane.getCustomEditorComponent() != null) {
            pane.getCustomEditorComponent().requestFocusInWindow();
            return;
        }
        if (pane != null && pane.getMarkdownPreviewComponent() instanceof MarkdownPreviewPane preview) {
            preview.requestPreviewFocus();
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
        if (editor.renderedLayoutComponent == null) {
            editor.renderedLayoutComponent = new JPanel();
        }
        editor.editorHostPanel.add(editor.renderedLayoutComponent, BorderLayout.CENTER);
        editor.updateZenModeLayout();
        editor.editorHostPanel.revalidate();
        editor.editorHostPanel.repaint();
    }

    void showToolWindow() {
        if (editor.toolWindowHost == null || editor.editorToolSplit == null) return;
        editor.toolWindowHost.setVisible(true);
        editor.editorToolSplit.setDividerSize(6);
        int height = Math.max(editor.getHeight(), editor.editorToolSplit.getHeight());
        editor.editorToolSplit.setDividerLocation(Math.max(180, (int) (height * 0.62)));
        editor.toolWindowHost.refreshActive();
        editor.editorToolSplit.revalidate();
    }

    void hideToolWindow() {
        if (editor.toolWindowHost == null || editor.editorToolSplit == null) return;
        editor.toolWindowHost.setVisible(false);
        editor.editorToolSplit.setDividerSize(0);
        editor.editorToolSplit.setDividerLocation(1.0);
        editor.requestActivePaneFocus();
    }


    Font resolveEditorFont() {
        int fontSize = UiZoom.scale(editor.configManager.getFontSize(), editor.configManager.getUiZoom());
        String configuredFamily = editor.configManager.getFontFamily();
        Font configuredFont = resolveInstalledFont(configuredFamily, fontSize);
        return configuredFont != null ? configuredFont : new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
    }

    private static Map<?, ?> desktopTextRenderingHints() {
        try {
            Object value = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            return value instanceof Map<?, ?> hints ? Map.copyOf(hints) : Map.of();
        } catch (HeadlessException ignored) {
            return Map.of();
        }
    }

    static void applyDesktopTextRenderingHints(Graphics2D graphics, Map<?, ?> hints) {
        if (graphics != null && hints != null && !hints.isEmpty()) {
            graphics.addRenderingHints(hints);
        }
    }

    Font resolveUiFont() {
        Font systemFont = systemUiFonts.getOrDefault("Label.font", new Font(Font.DIALOG, Font.PLAIN, 13));
        return configuredUiFont(systemFont);
    }

    Font resolveUiFont(float unscaledSize) {
        Font systemFont = systemUiFonts.getOrDefault("Label.font", new Font(Font.DIALOG, Font.PLAIN, 13));
        Font configuredFamily = resolveInstalledFont(editor.configManager.getUiFontFamily(), Math.max(1, Math.round(unscaledSize)));
        String family = configuredFamily == null ? systemFont.getFamily() : configuredFamily.getFamily();
        return new Font(family, systemFont.getStyle(), UiZoom.scale(Math.round(unscaledSize), editor.configManager.getUiZoom()));
    }

    Font resolveUiFontAtSize(int size) {
        Font systemFont = systemUiFonts.getOrDefault("Label.font", new Font(Font.DIALOG, Font.PLAIN, 13));
        int resolvedSize = Math.max(1, size);
        Font configuredFamily = resolveInstalledFont(editor.configManager.getUiFontFamily(), resolvedSize);
        String family = configuredFamily == null ? systemFont.getFamily() : configuredFamily.getFamily();
        return new Font(family, systemFont.getStyle(), resolvedSize);
    }

    Font resolveTerminalFont() {
        int fontSize = UiZoom.scale(editor.configManager.getTerminalFontSize(), editor.configManager.getUiZoom());
        Font configuredFont = resolveInstalledFont(editor.configManager.getTerminalFontFamily(), fontSize);
        return configuredFont != null ? configuredFont : new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
    }

    void applyUiFont() {
        for (Map.Entry<Object, Font> entry : systemUiFonts.entrySet()) {
            UIManager.put(entry.getKey(), new FontUIResource(configuredUiFont(entry.getValue())));
        }
        SwingUtilities.updateComponentTreeUI(editor);
        applyFooterUiMetrics();
        for (Window window : Window.getWindows()) {
            if (window != editor && window.isDisplayable() && (!(window instanceof JDialog dialog)
                || !Boolean.TRUE.equals(dialog.getRootPane().getClientProperty(DIALOG_UI_MANAGED_PROPERTY)))) {
                applyUiFont(window);
                if (window instanceof JDialog dialog) applyDialogSize(dialog);
            }
        }
    }

    /** Applies the selected Shed palette to every standard Swing surface. */
    void applyUiTheme() {
        installUiThemeDefaults();
        applyUiTheme(editor);
        if (commandPathPopup != null) applyUiTheme(commandPathPopup);
        for (Window window : Window.getWindows()) {
            if (window != editor && window.isDisplayable()) applyUiTheme(window);
        }
    }

    void applyUiTheme(Component root) {
        if (root == null) return;
        SwingUtilities.updateComponentTreeUI(root);
        applyUiThemeRecursively(root, uiThemeColors());
        if (root instanceof Container container) {
            container.revalidate();
            container.repaint();
        }
    }

    private void installUiThemeDefaults() {
        UiThemeColors colors = uiThemeColors();
        putUiColor(colors.surface(),
            "control", "Panel.background", "Viewport.background", "ScrollPane.background", "SplitPane.background",
            "TabbedPane.background", "OptionPane.background", "FileChooser.background", "Dialog.background");
        putUiColor(colors.raised(),
            "Button.background", "ToggleButton.background", "ToolBar.background", "MenuBar.background", "Menu.background",
            "MenuItem.background", "CheckBoxMenuItem.background", "RadioButtonMenuItem.background", "PopupMenu.background",
            "ScrollBar.background", "ProgressBar.background", "TableHeader.background");
        putUiColor(colors.field(),
            "TextField.background", "PasswordField.background", "FormattedTextField.background", "TextArea.background",
            "TextPane.background", "EditorPane.background", "ComboBox.background", "List.background", "Tree.background", "Table.background");
        putUiColor(colors.foreground(),
            "Label.foreground", "Button.foreground", "ToggleButton.foreground", "CheckBox.foreground", "RadioButton.foreground",
            "TextField.foreground", "PasswordField.foreground", "FormattedTextField.foreground", "TextArea.foreground",
            "TextPane.foreground", "EditorPane.foreground", "ComboBox.foreground", "List.foreground", "Tree.foreground",
            "Table.foreground", "TableHeader.foreground", "MenuBar.foreground", "Menu.foreground", "MenuItem.foreground",
            "CheckBoxMenuItem.foreground", "RadioButtonMenuItem.foreground", "OptionPane.messageForeground", "ToolTip.foreground",
            "TitledBorder.titleColor");
        putUiColor(colors.selection(),
            "TextField.selectionBackground", "PasswordField.selectionBackground", "FormattedTextField.selectionBackground",
            "TextArea.selectionBackground", "TextPane.selectionBackground", "EditorPane.selectionBackground",
            "List.selectionBackground", "Tree.selectionBackground", "Table.selectionBackground", "ComboBox.selectionBackground",
            "Menu.selectionBackground", "MenuItem.selectionBackground", "CheckBoxMenuItem.selectionBackground",
            "RadioButtonMenuItem.selectionBackground");
        putUiColor(colors.selectionText(),
            "TextField.selectionForeground", "PasswordField.selectionForeground", "FormattedTextField.selectionForeground",
            "TextArea.selectionForeground", "TextPane.selectionForeground", "EditorPane.selectionForeground",
            "List.selectionForeground", "Tree.selectionForeground", "Table.selectionForeground", "ComboBox.selectionForeground",
            "Menu.selectionForeground", "MenuItem.selectionForeground", "CheckBoxMenuItem.selectionForeground",
            "RadioButtonMenuItem.selectionForeground");
        putUiColor(colors.accent(), "TextField.caretForeground", "TextArea.caretForeground", "TextPane.caretForeground", "EditorPane.caretForeground",
            "ProgressBar.foreground", "Focus.color");
        putUiColor(colors.border(), "Table.gridColor", "Separator.foreground", "controlShadow", "controlDkShadow");
        putUiColor(colors.muted(), "Label.disabledForeground", "Button.disabledText", "MenuItem.disabledForeground");
    }

    private UiThemeColors uiThemeColors() {
        Color surface = editor.configManager.getNormalColor();
        Color foreground = editor.configManager.getEditorForeground();
        return new UiThemeColors(surface, blend(surface, foreground, 0.07), editor.configManager.getCommandBarBackground(),
            blend(surface, foreground, 0.18), foreground, blend(foreground, surface, 0.42), editor.configManager.getCaretColor(),
            editor.configManager.getSelectionColor(), editor.configManager.getSelectionTextColor());
    }

    private void applyUiThemeRecursively(Component component, UiThemeColors colors) {
        if (component instanceof JPanel panel) {
            panel.setBackground(colors.surface());
            panel.setForeground(colors.foreground());
        }
        if (component instanceof JLabel label) label.setForeground(colors.foreground());
        if (component instanceof AbstractButton button) {
            button.setBackground(colors.raised());
            button.setForeground(colors.foreground());
        }
        if (component instanceof JTextComponent text) {
            text.setBackground(component instanceof JTextField ? colors.field() : colors.surface());
            text.setForeground(colors.foreground());
            text.setCaretColor(colors.accent());
            text.setSelectionColor(colors.selection());
            text.setSelectedTextColor(colors.selectionText());
        }
        if (component instanceof JComboBox<?> combo) {
            combo.setBackground(colors.field());
            combo.setForeground(colors.foreground());
        }
        if (component instanceof JList<?> list) {
            list.setBackground(colors.field());
            list.setForeground(colors.foreground());
            list.setSelectionBackground(colors.selection());
            list.setSelectionForeground(colors.selectionText());
        }
        if (component instanceof JTree tree) {
            tree.setBackground(colors.field());
            tree.setForeground(colors.foreground());
        }
        if (component instanceof JTable table) {
            table.setBackground(colors.field());
            table.setForeground(colors.foreground());
            table.setSelectionBackground(colors.selection());
            table.setSelectionForeground(colors.selectionText());
            table.setGridColor(colors.border());
            JTableHeader header = table.getTableHeader();
            if (header != null) {
                header.setBackground(colors.raised());
                header.setForeground(colors.foreground());
            }
        }
        if (component instanceof JScrollPane scroll) {
            scroll.setBackground(colors.surface());
            scroll.getViewport().setBackground(colors.surface());
            scroll.getHorizontalScrollBar().setBackground(colors.raised());
            scroll.getVerticalScrollBar().setBackground(colors.raised());
        }
        if (component instanceof JSplitPane split) split.setBackground(colors.surface());
        if (component instanceof JTabbedPane tabs) {
            tabs.setBackground(colors.surface());
            tabs.setForeground(colors.foreground());
        }
        if (component instanceof JProgressBar progress) {
            progress.setBackground(colors.raised());
            progress.setForeground(colors.accent());
        }
        if (component instanceof JComponent swing && swing.getBorder() instanceof javax.swing.border.TitledBorder border) {
            border.setTitleColor(colors.foreground());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) applyUiThemeRecursively(child, colors);
        }
    }

    private static void putUiColor(Color color, String... keys) {
        ColorUIResource resource = new ColorUIResource(color);
        for (String key : keys) UIManager.put(key, resource);
    }

    private static Color blend(Color first, Color second, double amount) {
        double ratio = Math.max(0.0, Math.min(1.0, amount));
        return new Color((int) Math.round(first.getRed() * (1.0 - ratio) + second.getRed() * ratio),
            (int) Math.round(first.getGreen() * (1.0 - ratio) + second.getGreen() * ratio),
            (int) Math.round(first.getBlue() * (1.0 - ratio) + second.getBlue() * ratio));
    }

    private record UiThemeColors(Color surface, Color raised, Color field, Color border, Color foreground, Color muted,
                                 Color accent, Color selection, Color selectionText) { }

    void prepareDialog(JDialog dialog, int unscaledWidth, int unscaledHeight) {
        if (dialog == null) return;
        dialog.getRootPane().putClientProperty(BASE_DIALOG_SIZE_PROPERTY, new Dimension(unscaledWidth, unscaledHeight));
        dialog.getContentPane().setPreferredSize(constrainDialogContentSize(dialog, unscaledWidth, unscaledHeight));
        applyUiTheme(dialog);
        applyUiFont(dialog);
        markDialogUiReady(dialog);
    }

    Dimension scaleUiDimension(int width, int height) {
        return new Dimension(UiZoom.scale(width, editor.configManager.getUiZoom()), UiZoom.scale(height, editor.configManager.getUiZoom()));
    }

    void markDialogUiReady(JDialog dialog) {
        if (dialog != null) dialog.getRootPane().putClientProperty(DIALOG_UI_READY_PROPERTY, Boolean.TRUE);
    }

    void markDialogUiFontsManaged(JDialog dialog) {
        if (dialog == null) return;
        markDialogUiReady(dialog);
        dialog.getRootPane().putClientProperty(DIALOG_UI_MANAGED_PROPERTY, Boolean.TRUE);
    }

    private void applyDialogSize(JDialog dialog) {
        Object stored = dialog.getRootPane().getClientProperty(BASE_DIALOG_SIZE_PROPERTY);
        if (!(stored instanceof Dimension base)) return;
        dialog.getContentPane().setPreferredSize(constrainDialogContentSize(dialog, base.width, base.height));
        dialog.pack();
    }

    Dimension fitPopupSize(Component anchor, Dimension requested) {
        if (requested == null) return new Dimension(1, 1);
        Rectangle usable = usableScreenBounds(anchor);
        return constrainSize(requested, new Dimension(usable.width, usable.height));
    }

    Point fitPopupLocation(Component anchor, Point requested, Dimension size) {
        Rectangle usable = usableScreenBounds(anchor);
        Dimension constrained = constrainSize(size, new Dimension(usable.width, usable.height));
        int maxX = usable.x + usable.width - constrained.width;
        int maxY = usable.y + usable.height - constrained.height;
        int x = Math.max(usable.x, Math.min(requested.x, maxX));
        int y = Math.max(usable.y, Math.min(requested.y, maxY));
        return new Point(x, y);
    }

    static Dimension constrainSize(Dimension requested, Dimension available) {
        int width = requested == null ? 1 : Math.max(1, requested.width);
        int height = requested == null ? 1 : Math.max(1, requested.height);
        int maximumWidth = available == null ? width : Math.max(1, available.width);
        int maximumHeight = available == null ? height : Math.max(1, available.height);
        return new Dimension(Math.min(width, maximumWidth), Math.min(height, maximumHeight));
    }

    private Dimension constrainDialogContentSize(JDialog dialog, int unscaledWidth, int unscaledHeight) {
        Dimension scaled = scaleUiDimension(unscaledWidth, unscaledHeight);
        Rectangle usable = usableScreenBounds(dialog);
        Insets insets = dialog.getInsets();
        Dimension contentBounds = new Dimension(
            Math.max(1, usable.width - insets.left - insets.right),
            Math.max(1, usable.height - insets.top - insets.bottom)
        );
        return constrainSize(scaled, contentBounds);
    }

    private Rectangle usableScreenBounds(Component component) {
        GraphicsConfiguration configuration = component == null ? null : component.getGraphicsConfiguration();
        if (configuration == null) configuration = editor.getGraphicsConfiguration();
        if (configuration == null || GraphicsEnvironment.isHeadless()) {
            return new Rectangle(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int x = screen.x + insets.left + SCREEN_EDGE_MARGIN;
        int y = screen.y + insets.top + SCREEN_EDGE_MARGIN;
        int width = Math.max(1, screen.width - insets.left - insets.right - SCREEN_EDGE_MARGIN * 2);
        int height = Math.max(1, screen.height - insets.top - insets.bottom - SCREEN_EDGE_MARGIN * 2);
        return new Rectangle(x, y, width, height);
    }

    void applyUiFont(Component root) {
        if (root == null) return;
        SwingUtilities.updateComponentTreeUI(root);
        applyUiFontRecursively(root);
        if (root instanceof Container container) {
            container.revalidate();
            container.repaint();
        }
    }

    private void applyUiFontRecursively(Component component) {
        Font font = component.getFont();
        if (font != null) {
            component.setFont(resolveUiFont(baseUiFont(component, font)));
        }
        if (component instanceof JTable table) {
            int minimumRowHeight = table.getFontMetrics(table.getFont()).getHeight() + UiZoom.scale(4, editor.configManager.getUiZoom());
            table.setRowHeight(minimumRowHeight);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyUiFontRecursively(child);
            }
        }
    }

    private Font baseUiFont(Component component, Font currentFont) {
        if (component instanceof JComponent swingComponent) {
            Object stored = swingComponent.getClientProperty(BASE_UI_FONT_PROPERTY);
            if (stored instanceof Font font) return font;
            Font base = matchingSystemUiFont(currentFont);
            swingComponent.putClientProperty(BASE_UI_FONT_PROPERTY, base);
            return base;
        }
        return matchingSystemUiFont(currentFont);
    }

    private Font matchingSystemUiFont(Font currentFont) {
        for (Font systemFont : systemUiFonts.values()) {
            if (systemFont.getStyle() == currentFont.getStyle()
                && systemFont.getFamily().equalsIgnoreCase(currentFont.getFamily())
                && UiZoom.scale(systemFont.getSize(), editor.configManager.getUiZoom()) == currentFont.getSize()) {
                return systemFont;
            }
        }
        return currentFont;
    }

    private Font resolveUiFont(Font unscaledFont) {
        Font configuredFamily = resolveInstalledFont(editor.configManager.getUiFontFamily(), Math.max(1, unscaledFont.getSize()));
        String family = configuredFamily == null ? unscaledFont.getFamily() : configuredFamily.getFamily();
        return new Font(family, unscaledFont.getStyle(), UiZoom.scale(unscaledFont.getSize(), editor.configManager.getUiZoom()));
    }

    private void applyFooterUiMetrics() {
        if (editor.statusBar == null || editor.commandBar == null) return;
        double zoom = editor.configManager.getUiZoom();
        int statusVerticalPadding = UiZoom.scale(5, zoom);
        int commandVerticalPadding = UiZoom.scale(4, zoom);
        Font font = resolveUiFont();
        editor.statusBar.setFont(font);
        editor.commandBar.setFont(font);
        editor.statusBar.setBorder(BorderFactory.createEmptyBorder(statusVerticalPadding, UiZoom.scale(10, zoom), statusVerticalPadding, UiZoom.scale(10, zoom)));
        editor.commandBar.setBorder(BorderFactory.createEmptyBorder(commandVerticalPadding, UiZoom.scale(10, zoom), commandVerticalPadding, UiZoom.scale(10, zoom)));
        int statusHeight = editor.statusBar.getFontMetrics(font).getHeight() + statusVerticalPadding * 2;
        int commandHeight = editor.commandBar.getFontMetrics(font).getHeight() + commandVerticalPadding * 2;
        editor.statusBar.setPreferredSize(new Dimension(0, statusHeight));
        editor.commandBar.setPreferredSize(new Dimension(0, commandHeight));
        if (editor.footerPanel != null) {
            editor.footerPanel.revalidate();
            editor.footerPanel.repaint();
        }
    }

    private void installDialogUiScaling() {
        if (dialogUiScalingListenerInstalled) return;
        dialogUiScalingListenerInstalled = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof WindowEvent windowEvent) || windowEvent.getID() != WindowEvent.WINDOW_OPENED
                || !(windowEvent.getWindow() instanceof JDialog dialog)) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (!Boolean.TRUE.equals(dialog.getRootPane().getClientProperty(DIALOG_UI_READY_PROPERTY))) {
                    applyUiFont(dialog);
                }
            });
        }, AWTEvent.WINDOW_EVENT_MASK);
    }

    private Map<Object, Font> captureSystemUiFonts() {
        Map<Object, Font> fonts = new HashMap<>();
        for (Map.Entry<Object, Object> entry : UIManager.getDefaults().entrySet()) {
            if (entry.getKey() instanceof String key && key.endsWith(".font") && entry.getValue() instanceof Font font) {
                fonts.put(entry.getKey(), font);
            }
        }
        return fonts;
    }

    private Font configuredUiFont(Font systemFont) {
        int requestedSize = editor.configManager.getUiFontSize();
        Font configuredFamily = resolveInstalledFont(editor.configManager.getUiFontFamily(), Math.max(1, requestedSize));
        String family = configuredFamily == null ? systemFont.getFamily() : configuredFamily.getFamily();
        int size = requestedSize == 0 ? systemFont.getSize() : requestedSize;
        return new Font(family, systemFont.getStyle(), UiZoom.scale(size, editor.configManager.getUiZoom()));
    }

    private void installUiZoomShortcuts() {
        bindUiZoomShortcut("shed.ui-zoom.in.ctrl.equals", KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK, 1);
        bindUiZoomShortcut("shed.ui-zoom.in.ctrl.plus", KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 1);
        bindUiZoomShortcut("shed.ui-zoom.in.ctrl.plus-key", KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK, 1);
        bindUiZoomShortcut("shed.ui-zoom.in.ctrl.numpad", KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK, 1);
        bindUiZoomShortcut("shed.ui-zoom.in.meta.equals", KeyEvent.VK_EQUALS, InputEvent.META_DOWN_MASK, 1);
        bindUiZoomShortcut("shed.ui-zoom.in.meta.plus", KeyEvent.VK_EQUALS, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 1);
        bindUiZoomShortcut("shed.ui-zoom.in.meta.plus-key", KeyEvent.VK_PLUS, InputEvent.META_DOWN_MASK, 1);
        bindUiZoomShortcut("shed.ui-zoom.in.meta.numpad", KeyEvent.VK_ADD, InputEvent.META_DOWN_MASK, 1);
        bindUiZoomShortcut("shed.ui-zoom.out.ctrl", KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK, -1);
        bindUiZoomShortcut("shed.ui-zoom.out.ctrl.numpad", KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK, -1);
        bindUiZoomShortcut("shed.ui-zoom.out.meta", KeyEvent.VK_MINUS, InputEvent.META_DOWN_MASK, -1);
        bindUiZoomShortcut("shed.ui-zoom.out.meta.numpad", KeyEvent.VK_SUBTRACT, InputEvent.META_DOWN_MASK, -1);
        bindUiZoomResetShortcut("shed.ui-zoom.reset.ctrl", KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK);
        bindUiZoomResetShortcut("shed.ui-zoom.reset.ctrl.numpad", KeyEvent.VK_NUMPAD0, InputEvent.CTRL_DOWN_MASK);
        bindUiZoomResetShortcut("shed.ui-zoom.reset.meta", KeyEvent.VK_0, InputEvent.META_DOWN_MASK);
        bindUiZoomResetShortcut("shed.ui-zoom.reset.meta.numpad", KeyEvent.VK_NUMPAD0, InputEvent.META_DOWN_MASK);
    }

    private void bindUiZoomShortcut(String id, int keyCode, int modifiers, int direction) {
        editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, modifiers), id);
        editor.getRootPane().getActionMap().put(id, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                editor.showMessage(editor.adjustUiZoom(direction));
            }
        });
    }

    private void bindUiZoomResetShortcut(String id, int keyCode, int modifiers) {
        editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, modifiers), id);
        editor.getRootPane().getActionMap().put(id, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                editor.showMessage(editor.resetUiZoom());
            }
        });
    }


    Font resolveInstalledFont(String family, int fontSize) {
        String installedFamily = FontFamilyCatalog.resolve(family);
        return installedFamily == null ? null : new Font(installedFamily, Font.PLAIN, fontSize);
    }


    private void setApplicationIcon() {
        try (InputStream resource = EditorUiController.class.getClassLoader().getResourceAsStream("assets/logo/shed.png")) {
            BufferedImage icon = resource == null ? null : ImageIO.read(resource);
            if (icon == null) return;
            editor.setIconImages(List.of(icon));
            if (GraphicsEnvironment.isHeadless() || !Taskbar.isTaskbarSupported()) return;
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.setIconImage(icon);
        } catch (java.io.IOException | SecurityException | UnsupportedOperationException ignored) {}
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
        long started = System.nanoTime();
        FileBuffer buffer = editor.getCurrentBuffer();
        StringBuilder status = new StringBuilder();
        boolean nativeWelcome = editor.paneBufferController != null && editor.paneBufferController.isNativeWelcomeBuffer(buffer);

        if (nativeWelcome) {
            status.append("Shed ").append(editor.VERSION).append("  Welcome");
        } else if (buffer != null) {
            editor.pollLspNotifications(buffer);
            status.append(buffer.getDisplayName());
            if (buffer.isModified()) {
                status.append(" [+]");
            }
            status.append("  ");
        }

        if (!nativeWelcome) {
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
            if (buffer != null) {
                status.append(languageDisplayName(buffer)).append("  ");
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
        }

        String statusText = status.toString();
        if (!statusText.equals(editor.statusBar.getText())) {
            editor.statusBar.setText(statusText);
        }

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
        if (editor.perfService != null) {
            editor.perfService.recordDuration("status.render", started, "lines=" + editor.writingArea.getLineCount());
        }
    }

    void requestStatusBarRefresh() {
        if (editor.statusBar == null) {
            return;
        }
        if (statusRefreshTimer == null) {
            statusRefreshTimer = new Timer(STATUS_REFRESH_DEBOUNCE_MS, event -> updateStatusBar());
            statusRefreshTimer.setRepeats(false);
        }
        statusRefreshTimer.restart();
    }

    private String languageDisplayName(FileBuffer buffer) {
        if (buffer == null) return "text";
        LanguageProfile profile = editor.languageProfileFor(buffer);
        return profile == null ? buffer.getFileType().getDisplayName() : profile.displayName();
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
        FontMetrics fm = g.getFontMetrics(area.getFont());
        Rectangle clip = g.getClipBounds();
        if (clip == null) return;
        int visibleStart = Math.max(0, area.viewToModel2D(new Point(clip.x, clip.y)));
        int visibleEnd = Math.min(area.getDocument().getLength(), area.viewToModel2D(new Point(clip.x + clip.width, clip.y + clip.height)) + 1);
        if (visibleEnd <= visibleStart) return;
        String text;
        try {
            text = area.getText(visibleStart, visibleEnd - visibleStart);
        } catch (BadLocationException ignored) {
            return;
        }
        java.util.regex.Matcher m = editor.HEX_COLOR_PATTERN.matcher(text);
        while (m.find()) {
            try {
                Rectangle2D r = area.modelToView2D(visibleStart + m.end());
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
        LspClient client = editor.existingLspClient(buffer);
        if (client == null || !client.isAlive()) { editor.writingArea.repaint(); return; }
        String uri = editor.bufferUri(buffer);
        List<LspClient.Diagnostic> diags = client.getDiagnostics(uri);
        if (diags == null || diags.isEmpty()) { editor.writingArea.repaint(); return; }
        VersionedTextSnapshot text = buffer.textSnapshot();
        for (LspClient.Diagnostic d : diags) {
            int line = d.getLine();
            if (line < 0 || line >= text.lineCount()) continue;
            int lineStart = text.lineStartOffset(line);
            int lineEnd = text.lineEndOffset(line);
            int startOff = Math.min(lineStart + Math.max(0, d.getCharacter()), lineEnd);
            int endOff = Math.min(startOff + 1, lineEnd); // at least 1 char wide
            while (endOff < lineEnd && !Character.isWhitespace(text.charAt(endOff))) endOff++;
            editor.diagnosticRanges.add(new int[]{startOff, endOff, d.getSeverity()});
        }
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
        paintForegroundSpans(g, area, editor.syntaxForegroundSpans, "syntax.paint");
    }

    void paintLspSemanticOverlay(Graphics g, JTextArea area) {
        paintForegroundSpans(g, area, editor.lspSemanticSpans, "lsp.semantic.paint");
    }

    private void paintForegroundSpans(Graphics g, JTextArea area, List<SyntaxSpan> spans, String metric) {
        long started = System.nanoTime();
        try {
        if (area == null || area != editor.writingArea || spans.isEmpty()) {
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

        int first = firstVisibleSpan(spans, visibleStart);
        for (int index = first; index < spans.size(); index++) {
            SyntaxSpan span = spans.get(index);
            if (span.start >= visibleEnd) break;
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
                editor.perfService.recordDuration(metric, started, "spans=" + spans.size());
            }
        }
    }

    private int firstVisibleSpan(List<SyntaxSpan> spans, int visibleStart) {
        int low = 0;
        int high = spans.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (spans.get(middle).end <= visibleStart) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    void paintLspInlayHintOverlay(Graphics graphics, JTextArea area) {
        if (area == null || area != editor.writingArea || editor.lspInlayHintOverlays.isEmpty()) return;
        Graphics2D g2 = (Graphics2D) graphics.create();
        Font hintFont = area.getFont().deriveFont(Math.max(10f, area.getFont().getSize2D() - 2f));
        g2.setFont(hintFont);
        FontMetrics metrics = g2.getFontMetrics(hintFont);
        Color foreground = editor.configManager.getEditorForeground();
        Color background = editor.configManager.getNormalColor();
        Color text = new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 150);
        Color fill = new Color(background.getRed(), background.getGreen(), background.getBlue(), 205);
        Rectangle clip = g2.getClipBounds();
        int length = area.getDocument().getLength();
        for (LspInlayHintOverlay hint : editor.lspInlayHintOverlays) {
            if (hint.offset() > length || hint.label().isBlank()) continue;
            try {
                Rectangle2D position = area.modelToView2D(hint.offset());
                if (position == null) continue;
                int x = (int) Math.round(position.getX()) + 2;
                int y = (int) Math.round(position.getY()) + metrics.getAscent();
                int width = Math.min(280, metrics.stringWidth(hint.label()) + 6);
                Rectangle bounds = new Rectangle(x, (int) Math.round(position.getY()), width, metrics.getHeight());
                if (clip != null && !clip.intersects(bounds)) continue;
                g2.setColor(fill);
                g2.fillRoundRect(x, bounds.y, width, bounds.height, 4, 4);
                g2.setColor(text);
                g2.drawString(hint.label(), x + 3, y);
            } catch (BadLocationException ignored) {
            }
        }
        g2.dispose();
    }

}
