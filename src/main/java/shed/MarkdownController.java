package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;

final class MarkdownController {
    private final Texteditor editor;
    private final Map<FileBuffer, PreviewBinding> previews = new IdentityHashMap<>();
    private MarkdownAnalysisService headingAnalysis;
    private final Object headingEditOwner = new Object();
    private PendingHeadingEdit pendingHeadingEdit;
    private record PendingHeadingEdit(FileBuffer buffer, EditorPane pane, MarkdownSemanticSnapshot.Source source,
                                      int dot, int mark, EditorMode mode, KeymapProfile profile, long keyPress,
                                      javax.swing.event.CaretListener caretListener, java.awt.event.FocusListener focusListener) { }

    void cancelHeadingEdit() {
        if (pendingHeadingEdit != null) {
            pendingHeadingEdit.pane().getTextArea().removeCaretListener(pendingHeadingEdit.caretListener());
            pendingHeadingEdit.pane().getTextArea().removeFocusListener(pendingHeadingEdit.focusListener());
            pendingHeadingEdit = null;
            cancelHeadings(headingEditOwner);
        }
    }

    private boolean headingEditCurrent(PendingHeadingEdit request) {
        return pendingHeadingEdit == request && sourceWritable(request.pane(), request.buffer())
            && editor.isMarkdownSourceCurrent(request.buffer(), request.source())
            // A submitted Vim Ex command normally leaves its prompt before an asynchronous result arrives.
            && (editor.editorState.mode == request.mode()
                || request.mode() == EditorMode.COMMAND && editor.editorState.mode == EditorMode.NORMAL)
            && editor.configManager.getKeymapProfile() == request.profile()
            && editor.inputController.lastKeyPressNanos() == request.keyPress()
            && editor.writingArea.getCaret().getDot() == request.dot() && editor.writingArea.getCaret().getMark() == request.mark();
    }
    private record Motion(boolean forward, int level) { }
    private static final class PendingNavigation {
        final FileBuffer buffer;
        final EditorPane pane;
        final java.lang.ref.WeakReference<VersionedTextSnapshot> text;
        final int dot, mark;
        final List<Motion> motions = new ArrayList<>();
        PendingNavigation(FileBuffer buffer, EditorPane pane) {
            this.buffer = buffer; this.pane = pane; text = new java.lang.ref.WeakReference<>(buffer.textSnapshot());
            dot = pane.getTextArea().getCaret().getDot(); mark = pane.getTextArea().getCaret().getMark();
        }
        boolean matches(FileBuffer buffer, EditorPane pane) {
            return this.buffer == buffer && this.pane == pane && text.get() == buffer.textSnapshot()
                && dot == pane.getTextArea().getCaret().getDot() && mark == pane.getTextArea().getCaret().getMark();
        }
    }
    private PendingNavigation pendingNavigation;

    MarkdownAnalysisService headingAnalysis() {
        if (headingAnalysis == null) headingAnalysis = new MarkdownAnalysisService(editor.asyncJobService);
        return headingAnalysis;
    }

    java.util.Optional<MarkdownSemanticSnapshot> availableSemantic(MarkdownSemanticSnapshot.Source source) {
        return headingAnalysis == null ? java.util.Optional.empty() : headingAnalysis.available(source);
    }

    void requestHeadings(Object owner, FileBuffer buffer, java.util.function.BooleanSupplier current,
                         java.util.function.Consumer<MarkdownHeadingAnalysis> completion) {
        var source = MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer);
        headingAnalysis().request(owner, source, () -> editor.isMarkdownSourceCurrent(buffer, source) && current.getAsBoolean(), completion);
    }

    void cancelHeadings(Object owner) { if (headingAnalysis != null) headingAnalysis.cancel(owner); }

    void headingSourceClosed(FileBuffer buffer) {
        if (linkRequest != null && linkRequest.buffer() == buffer) cancelLinkNavigation();
        if (pendingHeadingEdit != null && pendingHeadingEdit.buffer() == buffer) cancelHeadingEdit();
        if (pendingNavigation != null && pendingNavigation.buffer == buffer) pendingNavigation = null;
        if (headingAnalysis != null) headingAnalysis.documentClosed(editor.documentStore.id(buffer));
        for (Window window : editor.getOwnedWindows()) if (window instanceof MarkdownOutlineDialog dialog) dialog.sourceClosed(buffer);
    }

    String showSemanticSymbols(String argument) {
        FileBuffer buffer = editor.getCurrentBuffer();
        EditorPane pane = editor.getActivePane();
        pendingNavigation = null;
        String[] message = {"Loading document symbols"};
        requestHeadings(pane, buffer, () -> pane == editor.getActivePane() && pane.getBuffer() == buffer
            && editor.editorPanes.contains(pane), result -> {
                if (result.path() == MarkdownHeadingAnalysis.Path.FAILED || result.path() == MarkdownHeadingAnalysis.Path.CANCELLED)
                    message[0] = "Markdown headings unavailable; request again";
                else message[0] = editor.showMarkdownSymbolResults(buffer, pane, result, argument);
                editor.showMessage(message[0]);
            });
        return message[0];
    }

    MarkdownController(Texteditor editor) {
        this.editor = editor;
    }

    String handlePreviewCommand(String args) {
        String operation = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "preview", "open" -> openPreview();
            case "close" -> closePreview();
            case "refresh" -> refreshPreview();
            default -> "Usage: :markdown preview|close|refresh";
        };
    }

    String openPreview() {
        EditorPane sourcePane = editor.getActivePane();
        FileBuffer buffer = editor.getCurrentBuffer();
        if (sourcePane == null || buffer == null || buffer.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        if (sourcePane.isMarkdownPreview()) return "Markdown preview already open";
        if (buffer.isLargeFile()) return "Markdown preview unavailable for a large file";
        PreviewBinding existing = previews.get(buffer);
        if (existing != null && editor.editorPanes.contains(existing.previewPane)) {
            editor.activateEditorPane(existing.sourcePane);
            editor.requestActivePaneFocus();
            return "Markdown preview already open";
        }
        if (existing != null) releasePreview(existing);

        EditorPane previewPane = editor.createEditorPane(editor.getSize());
        editor.editorPanes.add(previewPane);
        if (editor.windowLayoutRoot == null) editor.windowLayoutRoot = WindowLayoutNode.leaf(sourcePane);
        if (!editor.windowLayoutRoot.splitLeaf(sourcePane, previewPane, WindowLayoutNode.Orientation.HORIZONTAL, false, 0.5)) {
            editor.editorPanes.remove(previewPane);
            return "Markdown preview could not create a split";
        }
        editor.loadBufferIntoPane(previewPane, buffer, 0);
        MarkdownPreviewPane preview = new MarkdownPreviewPane(buffer, editor::resolveUiFont,
            editor.configManager::getNormalColor, editor.configManager::getEditorForeground,
            href -> editor.showMessage(openPreviewLink(buffer, href)),
            () -> editor.activateEditorPane(previewPane), editor.configManager::getMarkdownPreviewScrollSync,
            editor.configManager::getMarkdownPreviewRemoteImages, sourcePane, editor);
        previewPane.setMarkdownPreviewComponent(preview);
        previews.put(buffer, new PreviewBinding(sourcePane, previewPane, preview));
        editor.renderWindowLayout();
        sourcePane.getTextArea().requestFocusInWindow();
        return "Markdown preview opened";
    }

    void closePreviewForSource(EditorPane sourcePane) {
        PreviewBinding binding = previewForSource(sourcePane);
        if (binding != null && editor.editorPanes.contains(binding.previewPane)) editor.closePane(binding.previewPane);
    }

    boolean hasPreviewForSource(EditorPane sourcePane) {
        return previewForSource(sourcePane) != null;
    }

    void detachPreview(EditorPane pane) {
        if (linkRequest != null && linkRequest.pane() == pane) cancelLinkNavigation();
        if (pendingHeadingEdit != null && pendingHeadingEdit.pane() == pane) cancelHeadingEdit();
        if (pendingNavigation != null && pendingNavigation.pane == pane) pendingNavigation = null;
        cancelHeadings(pane);
        Iterator<Map.Entry<FileBuffer, PreviewBinding>> iterator = previews.entrySet().iterator();
        while (iterator.hasNext()) {
            PreviewBinding binding = iterator.next().getValue();
            if (binding.previewPane == pane || binding.sourcePane == pane) {
                binding.preview.disposePreview();
                iterator.remove();
            }
        }
    }

    void disposePreviews() {
        cancelLinkNavigation();
        cancelHeadingEdit();
        pendingNavigation = null;
        if (headingAnalysis != null) {
            if (editor.closingDown) { headingAnalysis.close(); headingAnalysis = null; }
            else headingAnalysis.clear();
        }
        for (Window window : editor.getOwnedWindows()) if (window instanceof MarkdownOutlineDialog dialog) dialog.dispose();
        for (PreviewBinding binding : previews.values()) binding.preview.disposePreview();
        previews.clear();
    }

    void refreshPreviews() {
        for (PreviewBinding binding : previews.values()) binding.preview.refreshAppearance();
    }

    void refreshPreviewForBuffer(FileBuffer buffer) {
        PreviewBinding binding = previews.get(buffer);
        if (binding != null) binding.preview.refreshNow();
    }

    private String closePreview() {
        PreviewBinding binding = previews.get(editor.getCurrentBuffer());
        if (binding == null || !editor.editorPanes.contains(binding.previewPane)) return "No Markdown preview is open";
        return editor.closePane(binding.previewPane).equals("Window closed") ? "Markdown preview closed" : "Markdown preview could not close";
    }

    private String refreshPreview() {
        PreviewBinding binding = previews.get(editor.getCurrentBuffer());
        if (binding == null || !editor.editorPanes.contains(binding.previewPane)) return "No Markdown preview is open";
        binding.preview.refreshNow();
        return "Markdown preview refreshed";
    }

    private PreviewBinding previewForSource(EditorPane sourcePane) {
        for (PreviewBinding binding : previews.values()) {
            if (binding.sourcePane == sourcePane) return binding;
        }
        return null;
    }

    private void releasePreview(PreviewBinding binding) {
        binding.preview.disposePreview();
        previews.values().removeIf(candidate -> candidate == binding);
    }

    private String openPreviewLink(FileBuffer source, String href) {
        try {
            URI uri = new URI(href);
            String scheme = uri.getScheme();
            if (scheme == null) {
                String path = href.split("#", 2)[0];
                if (path.isEmpty()) return "";
                activatePreviewSource(source);
                File parent = source.getFile() == null ? new File(".") : source.getFile().getParentFile();
                File target = new File(parent == null ? new File(".") : parent, path);
                if (!target.isFile()) return "Linked file not found: " + path;
                editor.openFileAsync(target);
                return "Opening: " + target.getName();
            }
            if (scheme.equalsIgnoreCase("file")) {
                activatePreviewSource(source);
                File target = new File(uri);
                if (!target.isFile()) return "Linked file not found: " + target.getPath();
                editor.openFileAsync(target);
                return "Opening: " + target.getName();
            }
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("mailto")) {
                return "Unsupported Markdown link";
            }
            if (!Desktop.isDesktopSupported()) return "Desktop links are unavailable";
            Desktop.getDesktop().browse(uri);
            return "Opened: " + href;
        } catch (IOException | URISyntaxException error) {
            return "Markdown link failed: " + error.getMessage();
        }
    }

    private void activatePreviewSource(FileBuffer source) {
        PreviewBinding binding = previews.get(source);
        if (binding != null && editor.editorPanes.contains(binding.sourcePane)) editor.activateEditorPane(binding.sourcePane);
    }

    private record PreviewBinding(EditorPane sourcePane, EditorPane previewPane, MarkdownPreviewPane preview) {}


    String[] getCurrentLines() {
        return editor.writingArea.getText().split("\n", -1);
    }


    // Folding must remain presentation-only. Until that view exists, never edit
    // the shared source document (including through direct helper calls).
    private static final String FOLDING_UNAVAILABLE =
        "Markdown folding is temporarily unavailable";

    String toggleFoldAtCursor() {
        return FOLDING_UNAVAILABLE;
    }

    String foldHeading(int headingLine, MarkdownService.FoldRange range, String[] lines) {
        return FOLDING_UNAVAILABLE;
    }

    String unfoldHeading(int headingLine, String[] lines) {
        return FOLDING_UNAVAILABLE;
    }

    String foldAll() {
        return FOLDING_UNAVAILABLE;
    }

    String unfoldAll() {
        return FOLDING_UNAVAILABLE;
    }

    String globalFoldCycle() {
        return FOLDING_UNAVAILABLE;
    }


    String navigateHeading(boolean forward) {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "";
        if (!buf.isLargeFile()) return navigateSemanticHeading(forward, 0);
        String[] lines = getCurrentLines();
        int currentLine = editor.getCurrentCaretLine();
        int target = forward ? editor.markdownService.nextHeading(lines, currentLine) : editor.markdownService.prevHeading(lines, currentLine);
        if (target < 0) {
            return forward ? "No next heading" : "No previous heading";
        }
        editor.recordJumpPosition();
        return editor.gotoLine(target + 1);
    }


    String navigateHeadingAtLevel(boolean forward, int level) {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "";
        if (!buf.isLargeFile()) return navigateSemanticHeading(forward, level);
        String[] lines = getCurrentLines();
        int currentLine = editor.getCurrentCaretLine();
        int target = forward ? editor.markdownService.nextHeadingAtLevel(lines, currentLine, level) : editor.markdownService.prevHeadingAtLevel(lines, currentLine, level);
        if (target < 0) {
            return "No " + (forward ? "next" : "previous") + " h" + level + " heading";
        }
        editor.recordJumpPosition();
        return editor.gotoLine(target + 1);
    }


    public String showTableOfContents() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        MarkdownOutlineDialog.showFor(editor, buf, buf.isLargeFile() ? getCurrentLines() : null);
        return "Table of contents opened";
    }


    public String showOutline() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null || buf.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        MarkdownOutlineDialog.showFor(editor, buf, buf.isLargeFile() ? getCurrentLines() : null);
        return "Outline opened";
    }

    private String navigateSemanticHeading(boolean forward, int level) {
        FileBuffer buffer = editor.getCurrentBuffer();
        EditorPane pane = editor.getActivePane();
        if (pendingNavigation != null && pendingNavigation.matches(buffer, pane)) {
            if (pendingNavigation.motions.size() >= 64) return "Heading navigation busy; request again";
            pendingNavigation.motions.add(new Motion(forward, level));
            return "Loading headings";
        }
        PendingNavigation navigation = new PendingNavigation(buffer, pane);
        navigation.motions.add(new Motion(forward, level)); pendingNavigation = navigation;
        String[] message = {"Loading headings"};
        requestHeadings(pane, buffer, () -> pendingNavigation == navigation && navigation.matches(buffer, pane)
            && pane == editor.getActivePane() && pane.getBuffer() == buffer
            && editor.editorPanes.contains(pane) && pane.getTextArea().getDocument() == buffer.getDocument(), result -> {
                pendingNavigation = null;
                if (result.path() == MarkdownHeadingAnalysis.Path.CANCELLED || result.path() == MarkdownHeadingAnalysis.Path.FAILED) {
                    message[0] = "Markdown headings unavailable; request again"; editor.showMessage(message[0]); return;
                }
                for (Motion motion : navigation.motions) {
                    var target = result.target(motion.forward(), editor.getCurrentCaretLine() + 1, motion.level());
                    message[0] = target.isEmpty() ? "No " + (motion.forward() ? "next" : "previous")
                        + (motion.level() == 0 ? " heading" : " h" + motion.level() + " heading")
                        : editor.gotoMarkdownHeading(buffer, result.source(), target.get());
                }
                editor.showMessage(message[0]);
            });
        return message[0];
    }


    private String mutationUnavailable() {
        EditorPane pane = editor.getActivePane();
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null) return "No buffer";
        if (buffer.getFileType() != FileType.MARKDOWN) return "Not a markdown file";
        return sourceWritable(pane, buffer) ? null : "Markdown edit unavailable: read-only or unsupported surface";
    }

    private boolean sourceWritable(EditorPane pane, FileBuffer buffer) {
        return !editor.closingDown && editor.isDisplayable() && pane != null && pane == editor.getActivePane() && buffer == editor.getCurrentBuffer()
            && pane.getBuffer() == buffer && !buffer.isLargeFile() && !buffer.isShowingPreviewOnly()
            && !pane.isMarkdownPreview() && pane.getAlternateComponent() == null && pane.getTerminalPane() == null
            && pane.getTextArea() == editor.writingArea && editor.writingArea.getDocument() == buffer.getDocument()
            && editor.writingArea.isEnabled() && !editor.suppressDocumentEvents
            // Vim disables ordinary typing in normal/visual/command modes, not source commands.
            && (pane.surface().editable() || editor.configManager.getKeymapProfile() == KeymapProfile.VIM
                && !editor.editorState.mode.isEditable());
    }

    private String replace(int start, int end, String text, int caret, String success) throws BadLocationException {
        EditorPane pane = editor.getActivePane();
        FileBuffer buffer = editor.getCurrentBuffer();
        MarkdownEdit edit = new MarkdownEdit(buffer, editor.writingArea, pane.surface(),
            () -> sourceWritable(pane, buffer), start, end);
        String failure = edit.apply(text, caret);
        return failure.isEmpty() ? success : failure;
    }

    private record SourceLine(int start, int end, String text) { }

    /** Excludes only the actual terminator; the final line need not have one. */
    private SourceLine sourceLine(int line) throws BadLocationException {
        int start = editor.writingArea.getLineStartOffset(line);
        int end = Math.min(editor.writingArea.getLineEndOffset(line), editor.writingArea.getDocument().getLength());
        if (end > start && editor.writingArea.getText(end - 1, 1).equals("\n")) end--;
        return new SourceLine(start, end, editor.writingArea.getText(start, end - start));
    }

    private SourceLine keyboardLine(int line) throws BadLocationException {
        int start = editor.writingArea.getLineStartOffset(line);
        int end = Math.min(editor.writingArea.getLineEndOffset(line), editor.writingArea.getDocument().getLength());
        return end - start > MarkdownKeyboardContext.MAX_LINE ? null : sourceLine(line);
    }

    String markdownHeadingShift(boolean demote) {
        return headingShift(demote, false);
    }

    String markdownSubtreeShift(boolean demote) {
        return headingShift(demote, true);
    }

    private String headingShift(boolean demote, boolean subtree) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        if (pendingHeadingEdit != null && headingEditCurrent(pendingHeadingEdit)) return "Heading edit pending; request again";
        cancelHeadingEdit();
        try {
            FileBuffer buffer = editor.getCurrentBuffer(); EditorPane pane = editor.getActivePane();
            var source = MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer);
            if (source.text().length() > MarkdownSemanticParser.MAX_CHARACTERS)
                return "Markdown heading edit unavailable: semantic size limit";
            SourceLine line = subtree ? null : sourceLine(editor.getCurrentCaretLine());
            int dot = editor.writingArea.getCaret().getDot(), mark = editor.writingArea.getCaret().getMark();
            javax.swing.event.CaretListener listener = event -> cancelHeadingEdit();
            var focusListener = new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent event) { cancelHeadingEdit(); }
            };
            var request = new PendingHeadingEdit(buffer, pane, source, dot, mark, editor.editorState.mode,
                editor.configManager.getKeymapProfile(), editor.inputController.lastKeyPressNanos(), listener, focusListener);
            pendingHeadingEdit = request; pane.getTextArea().addCaretListener(listener); pane.getTextArea().addFocusListener(focusListener);
            String[] message = {"Loading heading edit"};
            headingAnalysis().request(headingEditOwner, source, () -> {
                if (headingEditCurrent(request)) return true;
                // Detach only local state here: the coordinator may be pruning its subscriber map.
                if (pendingHeadingEdit == request) {
                    pane.getTextArea().removeCaretListener(listener); pendingHeadingEdit = null;
                    pane.getTextArea().removeFocusListener(focusListener);
                }
                return false;
            }, result -> {
                if (!headingEditCurrent(request)) return;
                pane.getTextArea().removeCaretListener(listener); pane.getTextArea().removeFocusListener(focusListener); pendingHeadingEdit = null;
                var planned = subtree ? null : MarkdownHeadingEditPlan.plan(result.semantic(), dot, mark, demote);
                var tree = subtree ? MarkdownSubtreeEditPlan.plan(result.semantic(), dot, mark, demote) : null;
                if (subtree ? tree.edit().isPresent() : planned.edit().isPresent()) {
                    Map<EditorPane, EditorSurface.Viewport> otherViews = new IdentityHashMap<>();
                    for (EditorPane other : editor.editorPanes) if (other != pane && other.getBuffer() == buffer
                        && !other.isMarkdownPreview() && other.getAlternateComponent() == null && other.getTerminalPane() == null)
                        otherViews.put(other, other.surface().viewport());
                    var current = MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer);
                    String failure = subtree
                        ? MarkdownEdit.applyPlan(tree.edit().orElseThrow(), current, buffer, pane.getTextArea(), pane.surface(), () -> sourceWritable(pane, buffer))
                        : MarkdownEdit.applyPlan(planned.edit().orElseThrow(), current, buffer, pane.getTextArea(), pane.surface(), () -> sourceWritable(pane, buffer));
                    if (failure.isEmpty()) {
                        editor.activateEditorPane(pane);
                        otherViews.forEach((other, viewport) -> {
                            // A bounded splice can temporarily remove lines; refresh the restored extent before clamping.
                            if (subtree) other.getScrollPane().getViewport().doLayout();
                            other.surface().scrollTo(viewport);
                        });
                    }
                    message[0] = failure.isEmpty() ? (subtree ? tree.message() : planned.message()) : failure;
                } else if (!subtree && planned.status() == MarkdownHeadingEditPlan.Status.NOT_HEADING && !line.text().contains("#")) {
                    // Preserve ordinary-line indentation without reinterpreting semantic no-match as a heading.
                    String changed;
                    if (demote) changed = (editor.effectiveExpandTab() ? " ".repeat(editor.writingArea.getTabSize()) : "\t") + line.text();
                    else {
                        int count = 0;
                        while (count < line.text().length() && count < editor.writingArea.getTabSize()
                            && Character.isWhitespace(line.text().charAt(count))) count++;
                        changed = line.text().substring(count);
                    }
                    try { message[0] = replace(line.start(), line.end(), changed, -1, "Line updated"); }
                    catch (BadLocationException invalid) { message[0] = "Markdown edit unavailable: invalid line"; }
                } else message[0] = subtree ? tree.message() : planned.message();
                editor.showMessage(message[0]);
            });
            return message[0];
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid line"; }
    }

    boolean isOnTableLine() {
        try { return editor.markdownService.isTableRow(sourceLine(editor.getCurrentCaretLine()).text()); }
        catch (BadLocationException error) { return false; }
    }

    private record TableRange(int start, int end, int currentRow, String[] lines) { }

    private TableRange currentTable() throws BadLocationException {
        int current = editor.getCurrentCaretLine(), first = current, last = current;
        if (!editor.markdownService.isTableRow(sourceLine(current).text())) throw new IllegalArgumentException("Not inside a table");
        int currentStart = sourceLine(current).start();
        while (first > 0 && editor.markdownService.isTableRow(sourceLine(first - 1).text())) {
            first--;
            if (currentStart - sourceLine(first).start() > MarkdownService.MAX_TABLE_CHARS) throw new IllegalArgumentException("Table too large to edit safely");
        }
        int start = sourceLine(first).start();
        while (last + 1 < editor.writingArea.getLineCount() && editor.markdownService.isTableRow(sourceLine(last + 1).text())) {
            last++;
            if (sourceLine(last).end() - start > MarkdownService.MAX_TABLE_CHARS) throw new IllegalArgumentException("Table too large to edit safely");
        }
        int end = sourceLine(last).end();
        if (end - start > MarkdownService.MAX_TABLE_CHARS) throw new IllegalArgumentException("Table too large to edit safely");
        String[] lines = editor.writingArea.getText(start, end - start).split("\n", -1);
        editor.markdownService.validateEditableTable(lines, 0, lines.length - 1);
        return new TableRange(start, end, current - first, lines);
    }

    String markdownTableNextCell(boolean reverse) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            TableRange table = currentTable();
            SourceLine line = sourceLine(editor.getCurrentCaretLine());
            int columnOffset = editor.writingArea.getCaretPosition() - line.start();
            if (reverse) {
                editor.writingArea.setCaretPosition(line.start() + editor.markdownService.prevCellOffset(line.text(), columnOffset));
                return "";
            }
            int next = editor.markdownService.nextCellOffset(line.text(), columnOffset + 1);
            if (next > columnOffset + 1 && next < line.text().length() - 1) {
                editor.writingArea.setCaretPosition(line.start() + next);
                return "";
            }
            if (table.currentRow() == table.lines().length - 1) {
                String[] cells = editor.markdownService.parseCells(line.text());
                int[] widths = new int[cells.length];
                for (int c = 0; c < cells.length; c++) widths[c] = Math.max(3, cells[c].length());
                String row = editor.markdownService.newTableRow(cells.length, widths);
                return replace(line.end(), line.end(), "\n" + row,
                    line.end() + 1 + editor.markdownService.nextCellOffset(row, 0), "");
            }
            int nextRow = table.currentRow() + 1;
            if (editor.markdownService.isTableSeparator(table.lines()[nextRow])) nextRow++;
            if (nextRow < table.lines().length) {
                int nextStart = line.end() + 1;
                for (int i = table.currentRow() + 1; i < nextRow; i++) nextStart += table.lines()[i].length() + 1;
                editor.writingArea.setCaretPosition(nextStart + editor.markdownService.nextCellOffset(table.lines()[nextRow], 0));
            }
            return "";
        } catch (BadLocationException error) { return "Table navigation unavailable"; }
        catch (IllegalArgumentException error) { return error.getMessage(); }
    }

    public String alignMarkdownTable() { return transformTable("align", ""); }
    public String sortMarkdownTable(String args) { return transformTable("sort", args); }
    public String insertTableColumn(String args) { return transformTable("insert", args); }
    public String deleteTableColumn(String args) { return transformTable("delete", args); }

    private String transformTable(String operation, String args) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            TableRange table = currentTable();
            String[] lines = table.lines();
            int last = lines.length - 1;
            int columns = editor.markdownService.parseCells(lines[0]).length;
            SourceLine line = sourceLine(editor.getCurrentCaretLine());
            int column = editor.markdownService.cellColumn(line.text(), editor.writingArea.getCaretPosition() - line.start());
            String result, success;
            switch (operation) {
                case "align" -> { result = editor.markdownService.alignTable(lines, 0, last); success = "Table aligned"; }
                case "sort" -> {
                    String[] parts = args == null || args.isBlank() ? new String[]{"1"} : args.trim().split("\\s+");
                    column = Integer.parseInt(parts[0]) - 1;
                    if (column < 0 || column >= columns || parts.length > 2
                        || parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))
                        return "Invalid table sort column or direction";
                    result = editor.markdownService.sortTable(lines, 0, last, column, parts.length < 2 || !parts[1].equalsIgnoreCase("desc"));
                    success = "Table sorted by column " + (column + 1);
                }
                case "insert" -> { result = editor.markdownService.insertColumn(lines, 0, last, column); success = "Column inserted"; }
                default -> {
                    if (args != null && !args.isBlank()) column = Integer.parseInt(args.trim()) - 1;
                    if (column < 0 || column >= columns || columns == 1) return "Invalid table column deletion";
                    result = editor.markdownService.deleteColumn(lines, 0, last, column); success = "Column deleted";
                }
            }
            if (result.length() > MarkdownService.MAX_TABLE_CHARS) return "Formatted table too large to edit safely";
            return replace(table.start(), table.end(), result, -1, success);
        } catch (BadLocationException error) { return "Table edit unavailable: invalid range"; }
        catch (NumberFormatException error) { return "Invalid table column"; }
        catch (IllegalArgumentException error) { return error.getMessage(); }
    }

    public String insertTableTemplate(String args) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        int cols = 3, rows = 2;
        try {
            if (args != null && !args.isBlank()) {
                String[] parts = args.trim().split("[xX]", 3);
                if (parts.length > 2) return "Invalid table dimensions";
                cols = Integer.parseInt(parts[0].trim());
                if (parts.length == 2) rows = Integer.parseInt(parts[1].trim());
            }
            return insertTemplate(editor.markdownService.createTableTemplate(cols, rows), "Table inserted (" + cols + "x" + rows + ")");
        } catch (IllegalArgumentException error) { return "Invalid table dimensions or size"; }
    }

    public String toggleCheckbox() {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            SourceLine line = sourceLine(editor.getCurrentCaretLine());
            if (!editor.markdownService.isCheckbox(line.text())) return "Not a checkbox line";
            String toggled = editor.markdownService.toggleCheckbox(line.text());
            return replace(line.start(), line.end(), toggled, -1, toggled.contains("[x]") ? "Checked" : "Unchecked");
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid line"; }
    }

    String handleMarkdownEnter() {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            int start = editor.writingArea.getSelectionStart(), end = editor.writingArea.getSelectionEnd();
            new MarkdownSourceRange(start, end).validate(editor.getCurrentBuffer().textSnapshot());
            int lineIndex = editor.writingArea.getLineOfOffset(start);
            SourceLine line = keyboardLine(lineIndex);
            if (line == null) {
                if (editor.configManager.getKeymapProfile() != KeymapProfile.VIM) return null;
                String result = replace(start, end, "\n", start + 1, "");
                if (result.isEmpty()) recordContinuation("\n");
                return result;
            }
            var context = editor.syntaxUiController.markdownKeyboardContext(editor.getCurrentBuffer(), lineIndex, line.start());
            boolean supported = context.text() && MarkdownKeyboardContext.advance(context, line.text()) != MarkdownKeyboardContext.UNKNOWN;
            // Continue the prefix before the selection/caret, never a different active editor later.
            String before = editor.writingArea.getText(line.start(), start - line.start());
            if (supported && editor.markdownService.isEmptyListItem(before) && before.equals(line.text()) && start == end) {
                int rangeEnd = line.end() < editor.writingArea.getDocument().getLength() ? line.end() + 1 : line.end();
                String result = replace(line.start(), rangeEnd, "\n", line.start() + 1, "");
                if (result.isEmpty()) recordContinuation("\n");
                return result;
            }
            String continuation = supported ? editor.markdownService.listContinuation(before) : null;
            if (continuation == null) {
                if (editor.configManager.getKeymapProfile() != KeymapProfile.VIM) return null;
                int indent = 0;
                while (indent < before.length() && (before.charAt(indent) == ' ' || before.charAt(indent) == '\t')) indent++;
                continuation = editor.configManager.getAutoIndent() ? before.substring(0, indent) : "";
            }
            String inserted = "\n" + continuation;
            String result = replace(start, end, inserted, start + inserted.length(), "");
            if (result.isEmpty()) recordContinuation(inserted);
            return result;
        } catch (BadLocationException error) { return "Markdown continuation unavailable"; }
        catch (IllegalArgumentException error) { return error.getMessage(); }
    }

    private void recordContinuation(String inserted) {
        if (editor.configManager.getKeymapProfile() == KeymapProfile.VIM) editor.lastInsertedText += inserted;
    }

    private String insertTemplate(String template, String success) {
        try {
            int start = editor.writingArea.getCaretPosition();
            return replace(start, start, template, start + template.length(), success);
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid range"; }
    }

    public String insertLink() {
        return insertReference(false);
    }

    public String insertImage() {
        return insertReference(true);
    }

    private String insertReference(boolean image) {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        if (editor.writingArea.getSelectionStart() == editor.writingArea.getSelectionEnd())
            return insertTemplate(image ? editor.markdownService.insertImageTemplate() : editor.markdownService.insertLinkTemplate(),
                image ? "Image template inserted" : "Link template inserted");
        EditorPane pane = editor.getActivePane(); FileBuffer buffer = editor.getCurrentBuffer();
        int start = pane.getTextArea().getSelectionStart(), end = pane.getTextArea().getSelectionEnd();
        if (end - start > MarkdownService.MAX_INSERTION_LABEL) return "Markdown insertion unavailable: selection exceeds 4096 source units";
        var source = MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer);
        try {
            new MarkdownSourceRange(start, end).validate(source.text());
            char previous = start == 0 ? 0 : source.text().charAt(start - 1);
            if (previous == '\\' || previous == '[' || !image && previous == '!'
                || end < source.text().length() && source.text().charAt(end) == ']')
                return "Markdown insertion unavailable: ambiguous adjacent delimiter";
            var insertion = editor.markdownService.insertion(pane.getTextArea().getText(start, end - start), image);
            MarkdownEdit edit = new MarkdownEdit(buffer, pane.getTextArea(), pane.surface(),
                () -> sourceWritable(pane, buffer) && editor.isMarkdownSourceCurrent(buffer, source), start, end);
            var host = new java.lang.ref.WeakReference<>(editor);
            var owner = new java.lang.ref.WeakReference<>(pane);
            // History may outlive a pane; its selection guard captures only weak UI references.
            java.util.function.BooleanSupplier restore = () -> {
                var current = host.get(); var target = owner.get();
                return current != null && target != null && !current.closingDown
                    && current.editorPanes.contains(target) && current.getActivePane() == target
                    && !target.isMarkdownPreview() && target.getAlternateComponent() == null && target.getTerminalPane() == null;
            };
            Map<EditorPane, EditorSurface.Viewport> otherViews = new IdentityHashMap<>();
            for (EditorPane other : editor.editorPanes) if (other != pane && other.getBuffer() == buffer
                && !other.isMarkdownPreview() && other.getAlternateComponent() == null && other.getTerminalPane() == null)
                otherViews.put(other, other.surface().viewport());
            String failure = edit.applySelection(insertion.source(), new EditorSurface.Selection(
                start + insertion.destinationEnd(), start + insertion.destinationStart()), restore);
            if (failure.isEmpty()) {
                editor.activateEditorPane(pane);
                otherViews.forEach((other, viewport) -> other.surface().scrollTo(viewport));
            }
            return failure.isEmpty() ? (image ? "Image inserted" : "Link inserted") : failure;
        } catch (BadLocationException | IllegalArgumentException invalid) {
            return "Markdown insertion unavailable: " + invalid.getMessage();
        }
    }


    private static final class LinkRequest {
        final java.lang.ref.WeakReference<FileBuffer> buffer;
        final java.lang.ref.WeakReference<EditorPane> pane;
        final DocumentWorkspace.Id document;
        final long revision;
        final java.lang.ref.WeakReference<VersionedTextSnapshot> text;
        final int dot, mark;
        final long keyPress;
        final List<java.nio.file.Path> roots;
        java.nio.file.Path root;
        final javax.swing.event.CaretListener caret;
        final java.awt.event.FocusListener focus;
        final KeymapProfile profile;
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        int job = -1;
        boolean committed, resolving;
        java.nio.file.Path file;
        LinkRequest(FileBuffer buffer, EditorPane pane, DocumentWorkspace.Id document, long keyPress,
                    List<java.nio.file.Path> roots, java.nio.file.Path root, KeymapProfile profile, java.util.function.BooleanSupplier picking, Runnable cancel) {
            this.buffer = new java.lang.ref.WeakReference<>(buffer); this.pane = new java.lang.ref.WeakReference<>(pane); this.document = document; revision = buffer.documentRevision();
            text = new java.lang.ref.WeakReference<>(buffer.textSnapshot()); this.keyPress = keyPress;
            dot = pane.getTextArea().getCaret().getDot(); mark = pane.getTextArea().getCaret().getMark();
            this.roots = List.copyOf(roots); this.root = root; this.profile = profile; caret = event -> cancel.run();
            focus = new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent event) { if (!picking.getAsBoolean()) cancel.run(); }
            };
        }
        FileBuffer buffer() { return buffer.get(); }
        EditorPane pane() { return pane.get(); }
    }
    private LinkRequest linkRequest;
    record LinkDiagnostics(long staleRejected, int active, int worker, int queued) { }
    LinkDiagnostics linkDiagnostics() { return new LinkDiagnostics(linkStaleRejected, linkRequest == null ? 0 : 1, linkWorkerRunning ? 1 : 0, linkPending == null ? 0 : 1); }
    private long linkStaleRejected;
    private boolean linkWorkerRunning;
    private boolean linkPickerOpen;
    private Runnable linkPending;
    // Narrow interaction seams keep the normal picker and Desktop policy as defaults.
    java.util.function.BiFunction<String, List<String>, String> linkPicker;
    java.util.function.Function<URI, String> linkBrowser = uri -> {
        try {
            if (!Desktop.isDesktopSupported()) return "Desktop links are unavailable";
            Desktop.getDesktop().browse(uri); return "Opened: " + uri;
        } catch (Exception error) { return "Error opening URL: " + error.getMessage(); }
    };

    void cancelLinkNavigation() {
        LinkRequest request = linkRequest; if (request == null) return;
        linkRequest = null; linkPending = null; request.stop.set(true);
        if (request.pane() != null) {
            request.pane().getTextArea().removeCaretListener(request.caret); request.pane().getTextArea().removeFocusListener(request.focus);
        }
        cancelHeadings(request);
        if (request.root != null) editor.markdownLinks().onChanged(() -> { });
    }
    private void cancelLink(LinkRequest request) { if (linkRequest == request) cancelLinkNavigation(); }
    private boolean linkCurrent(LinkRequest request) {
        return linkRequest == request && !request.stop.get() && !editor.closingDown && editor.isDisplayable()
            && editor.getActivePane() == request.pane() && editor.editorPanes.contains(request.pane())
            && editor.getCurrentBuffer() == request.buffer() && editor.buffers.contains(request.buffer())
            && request.buffer().getFileType() == FileType.MARKDOWN && !request.buffer().isLargeFile()
            && editor.writingArea.getDocument() == request.buffer().getDocument()
            && !request.pane().isMarkdownPreview() && request.pane().getAlternateComponent() == null && request.pane().getTerminalPane() == null
            && editor.documentStore.id(request.buffer()).equals(request.document) && request.buffer().documentRevision() == request.revision
            && request.text.get() == request.buffer().textSnapshot() && request.pane().getTextArea().getCaret().getDot() == request.dot
            && request.pane().getTextArea().getCaret().getMark() == request.mark
            && editor.inputController.lastKeyPressNanos() == request.keyPress && editor.workspaceRoots.all().equals(request.roots)
            && editor.configManager.getKeymapProfile() == request.profile;
    }
    private java.nio.file.Path linkRoot(FileBuffer buffer) {
        if (!buffer.hasFilePath()) return null;
        var path = java.nio.file.Path.of(buffer.getFilePath()).toAbsolutePath().normalize();
        // Canonical containment is checked again on the worker. Do not stat project roots on EDT.
        return editor.workspaceRoots.all().stream().filter(path::startsWith).max(java.util.Comparator.comparingInt(java.nio.file.Path::getNameCount)).orElse(null);
    }
    String showLinks(boolean incoming) { return beginLinks(incoming ? 2 : 1); }

    private String beginLinks(int operation) {
        if (linkPickerOpen) return "Finish the current Markdown picker first";
        cancelLinkNavigation();
        FileBuffer buffer = editor.getCurrentBuffer(); EditorPane pane = editor.getActivePane();
        if (buffer == null || pane == null || buffer.getFileType() != FileType.MARKDOWN) return "Not a Markdown file";
        if (pane.isMarkdownPreview() || pane.getAlternateComponent() != null || pane.getTerminalPane() != null || buffer.isLargeFile())
            return "Markdown source navigation unavailable on this surface";
        var root = linkRoot(buffer);
        if (root == null && operation != 0 && editor.workspaceRoots.all().isEmpty()) return "Open the owning project folder to inspect Markdown links";
        var request = new LinkRequest(buffer, pane, editor.documentStore.id(buffer), editor.inputController.lastKeyPressNanos(), editor.workspaceRoots.all(), root, editor.configManager.getKeymapProfile(), () -> linkPickerOpen,
            this::cancelLinkNavigation);
        linkRequest = request; pane.getTextArea().addCaretListener(request.caret); pane.getTextArea().addFocusListener(request.focus);
        if (root == null) {
            if (!request.roots.isEmpty()) resolveLinkRoot(request, operation);
            else SwingUtilities.invokeLater(() -> { if (linkCurrent(request)) externalWithoutProject(request); });
            return "Checking Markdown link scope";
        }
        return startLinkIndex(request, operation);
    }
    private String startLinkIndex(LinkRequest request, int operation) {
        var root = request.root; var buffer = request.buffer(); var service = editor.markdownLinks();
        service.onChanged(() -> linkIndexReady(request, operation));
        if (!service.request(root)) { cancelLinkNavigation(); return "Markdown project index admission limit"; }
        var query = operation == 2 ? service.backlinks(root, java.nio.file.Path.of(buffer.getFilePath())) : service.outgoing(root, java.nio.file.Path.of(buffer.getFilePath()));
        if (query.state() != MarkdownLink.State.PENDING) {
            service.refresh(root);
            query = operation == 2 ? service.backlinks(root, java.nio.file.Path.of(buffer.getFilePath())) : service.outgoing(root, java.nio.file.Path.of(buffer.getFilePath()));
        }
        return query.state() == MarkdownLink.State.PENDING ? "Markdown link index is building; results are not complete" : "Checking Markdown links";
    }
    private void resolveLinkRoot(LinkRequest request, int operation) {
        if (!linkCurrent(request)) return;
        if (linkWorkerRunning) { linkPending = () -> resolveLinkRoot(request, operation); return; }
        linkWorkerRunning = true; var path = java.nio.file.Path.of(request.buffer().getFilePath());
        editor.asyncJobService.submit("Resolve Markdown project scope", token -> WorkspaceRootResolver.configuredRoot(path, request.roots), (job, root, error) -> {
            finishLinkWork();
            if (!linkCurrent(request)) { cancelLink(request); return; }
            if (error != null) { editor.showMessage("Markdown project scope unavailable"); cancelLink(request); return; }
            if (root == null) {
                if (operation == 0) externalWithoutProject(request);
                else { editor.showMessage("Open the owning project folder to inspect Markdown links"); cancelLink(request); }
                return;
            }
            request.root = root; editor.showMessage(startLinkIndex(request, operation));
        });
    }
    private void linkIndexReady(LinkRequest request, int operation) {
        if (!linkCurrent(request)) { linkStaleRejected++; cancelLink(request); return; }
        var service = editor.markdownLinks();
        if (service.view(request.root).isEmpty()) {
            var state = service.outgoing(request.root, java.nio.file.Path.of(request.buffer().getFilePath()));
            if (state.state() != MarkdownLink.State.PENDING) { editor.showMessage(MarkdownLinkNavigation.feedback(state, operation == 2)); cancelLink(request); }
            return;
        }
        if (request.file == null) { resolveLinkSource(request, operation); return; }
        if (!request.file.startsWith(service.view(request.root).orElseThrow().root())) {
            editor.showMessage("Markdown project identity changed; request again"); cancelLink(request); return;
        }
        var query = operation == 2 ? service.backlinks(request.root, request.file) : service.outgoing(request.root, request.file);
        if (query.state() == MarkdownLink.State.PENDING) return;
        service.onChanged(() -> { });
        if (query.state() != MarkdownLink.State.COMPLETE && query.state() != MarkdownLink.State.PARTIAL) {
            editor.showMessage(MarkdownLinkNavigation.feedback(query, operation == 2)); cancelLink(request); return;
        }
        long generation = service.generation(request.root);
        if (operation == 0) {
            var edge = MarkdownLinkNavigation.at(query, request.dot);
            if (edge.isEmpty()) {
                editor.showMessage(query.state() == MarkdownLink.State.COMPLETE ? "No verified Markdown link at caret" : "No verified link at caret; Markdown coverage is partial");
                cancelLink(request); return;
            }
            followEdge(request, generation, edge.orElseThrow(), null, false); return;
        }
        if (query.edges().isEmpty()) { editor.showMessage(MarkdownLinkNavigation.feedback(query, operation == 2)); cancelLink(request); return; }
        long rowsStarted = System.nanoTime();
        var rows = MarkdownLinkNavigation.rows(service.view(request.root).orElseThrow().root(), query, operation == 2);
        Map<String, MarkdownLink.Edge> edges = new LinkedHashMap<>(); for (var row : rows.rows()) edges.put(row.label(), row.edge());
        editor.perfService.recordDuration("markdown.links.rows", rowsStarted, "rows=" + rows.rows().size());
        String title = operation == 2 ? "Markdown Backlinks" : "Markdown Outgoing Links";
        if (query.state() == MarkdownLink.State.PARTIAL) title += " (partial)";
        if (rows.limited()) title += " (first 200)";
        String selected;
        try { selected = pickLink(title, List.copyOf(edges.keySet())); }
        finally { if (!linkCurrent(request)) cancelLink(request); }
        if (selected == null) { cancelLink(request); editor.showMessage("Markdown link selection cancelled"); return; }
        if (!linkCurrent(request) || service.generation(request.root) != generation || service.view(request.root).isEmpty()) {
            linkStaleRejected++; cancelLink(request); editor.showMessage("Markdown links changed; request again"); return;
        }
        MarkdownLink.Edge edge = edges.get(selected);
        if (edge == null) { cancelLink(request); return; }
        followEdge(request, generation, edge, null, operation == 2);
    }
    private void resolveLinkSource(LinkRequest request, int operation) {
        if (request.resolving) return;
        if (linkWorkerRunning) { linkPending = () -> linkIndexReady(request, operation); return; }
        request.resolving = true; linkWorkerRunning = true;
        var path = java.nio.file.Path.of(request.buffer().getFilePath());
        var index = editor.markdownLinks().view(request.root).orElseThrow(); long generation = editor.markdownLinks().generation(request.root);
        var open = new MarkdownLinkNavigation.Open(path, MarkdownSemanticSnapshot.Source.capture(request.document, request.buffer()), request.buffer().isModified());
        editor.asyncJobService.submit("Resolve Markdown source identity", token -> {
            long started = System.nanoTime();
            try {
            var real = path.toRealPath(); var owner = WorkspaceRootResolver.configuredRoot(real, request.roots);
            if (token.isCancelled() || request.stop.get()) throw new java.util.concurrent.CancellationException();
            if (owner == null || !WorkspaceRootResolver.normalizedIdentity(owner).equals(WorkspaceRootResolver.normalizedIdentity(request.root)))
                throw new IllegalArgumentException("Markdown source is outside the owning project");
            if (!real.startsWith(index.root())) throw new IllegalArgumentException("Markdown project identity changed");
            if (operation != 2) {
                var facts = index.effective().get(real);
                if (facts == null) throw new IllegalArgumentException("Current Markdown file is not covered by the project index");
                if (open.dirty() && facts.origin().document().isEmpty()) throw new IllegalArgumentException("Current unsaved Markdown revision is not indexed");
                if (facts.links().isEmpty() && facts.state() != MarkdownLink.State.COMPLETE)
                    throw new IllegalArgumentException("Current Markdown source is " + facts.state().name().toLowerCase(Locale.ROOT) + ": " + facts.detail());
                MarkdownLinkNavigation.verifyOrigin(index.root(), request.roots, facts.origin(), List.of(open), () -> token.isCancelled() || request.stop.get());
            }
            return real;
            } finally { editor.perfService.recordDuration("markdown.links.source", started, ""); }
        }, (job, identity, error) -> {
            request.resolving = false; finishLinkWork();
            if (!linkCurrent(request) || editor.markdownLinks().generation(request.root) != generation) {
                linkStaleRejected++; cancelLink(request); return;
            }
            if (error != null) { editor.showMessage("Markdown source unavailable: " + error.getMessage()); cancelLink(request); return; }
            request.file = identity; linkIndexReady(request, operation);
        });
    }

    private String pickLink(String title, List<String> rows) {
        linkPickerOpen = true;
        try {
            String selected = linkPicker == null ? editor.showPaletteDialog(title, rows) : linkPicker.apply(title, rows);
            if (selected != null && linkRequest != null) {
                linkRequest.pane().getTextArea().removeFocusListener(linkRequest.focus);
            }
            return selected;
        } catch (RuntimeException | Error failure) {
            cancelLinkNavigation(); throw failure;
        } finally { linkPickerOpen = false; }
    }
    private void followEdge(LinkRequest request, long generation, MarkdownLink.Edge edge, java.nio.file.Path selected, boolean backlink) {
        var service = editor.markdownLinks();
        if (!linkCurrent(request) || generation != service.generation(request.root) || service.view(request.root).isEmpty()) {
            linkStaleRejected++; cancelLink(request); editor.showMessage("Markdown links changed; request again"); return;
        }
        if (!backlink && edge.resolution().status() == MarkdownLink.Status.AMBIGUOUS && selected == null) {
            Map<String, java.nio.file.Path> choices = new LinkedHashMap<>();
            int index = 0;
            for (var candidate : edge.resolution().candidates()) {
                if (choices.size() == MarkdownLinkNavigation.MAX_ROWS) break;
                choices.put("File: " + MarkdownLinkNavigation.text(service.view(request.root).orElseThrow().root().relativize(candidate).toString(), 240) + "  [#" + (++index) + "]", candidate);
            }
            String choice = pickLink("Markdown Link Targets" + (edge.resolution().candidates().size() > choices.size() ? " (first 200)" : ""), List.copyOf(choices.keySet()));
            if (choice == null) { cancelLink(request); editor.showMessage("Markdown link selection cancelled"); return; }
            selected = choices.get(choice); if (selected == null) { cancelLink(request); return; }
        }
        if (!linkCurrent(request) || service.generation(request.root) != generation || service.view(request.root).isEmpty()) {
            linkStaleRejected++; cancelLink(request); editor.showMessage("Markdown links changed; request again"); return;
        }
        if (request.job >= 0) return;
        if (linkWorkerRunning) {
            var choice = selected;
            linkPending = () -> followEdge(request, generation, edge, choice, backlink); return;
        }
        var index = service.view(request.root).orElseThrow();
        List<MarkdownLinkNavigation.Open> captured;
        try { captured = captureLinkSources(); }
        catch (IllegalArgumentException limit) { editor.showMessage(limit.getMessage()); cancelLink(request); return; }
        linkWorkerRunning = true;
        var candidate = selected;
        request.job = editor.asyncJobService.submit("Validate Markdown navigation", token -> {
            long validationStarted = System.nanoTime();
            try {
                if (!backlink && edge.resolution().status() == MarkdownLink.Status.EXTERNAL) {
                    MarkdownLinkNavigation.verifySource(index, request.roots, edge, captured, () -> token.isCancelled() || request.stop.get()); return null;
                }
                return MarkdownLinkNavigation.prepare(index, request.roots, edge, candidate, backlink, captured, () -> token.isCancelled() || request.stop.get());
            } finally { editor.perfService.recordDuration("markdown.links.validate", validationStarted, ""); }
        }, (job, target, error) -> {
            finishLinkValidation(request, generation, edge, target, error, backlink);
        });
    }
    private List<MarkdownLinkNavigation.Open> captureLinkSources() {
        var open = new ArrayList<MarkdownLinkNavigation.Open>();
        for (FileBuffer buffer : editor.buffers) if (buffer.hasFilePath()) {
            if (open.size() == 64) throw new IllegalArgumentException("Markdown navigation open-buffer admission limit");
            open.add(new MarkdownLinkNavigation.Open(java.nio.file.Path.of(buffer.getFilePath()),
                MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer), buffer.isModified()));
        }
        return List.copyOf(open);
    }
    private void finishLinkValidation(LinkRequest request, long generation, MarkdownLink.Edge edge,
                                      MarkdownLinkNavigation.Target target, Exception error, boolean backlink) {
        if (!linkCurrent(request) || editor.markdownLinks().generation(request.root) != generation || editor.markdownLinks().view(request.root).isEmpty()) {
            linkStaleRejected++; cancelLink(request); finishLinkWork(); return;
        }
        if (error != null) { editor.showMessage("Markdown navigation unavailable: " + error.getMessage()); cancelLink(request); finishLinkWork(); return; }
        if (target == null) {
            editor.showMessage(openExternalLink(edge.occurrence().destination())); cancelLink(request); finishLinkWork(); return;
        }
        editor.openMarkdownTarget(target, () -> linkCurrent(request) && editor.markdownLinks().generation(request.root) == generation
            && editor.markdownLinks().view(request.root).isPresent(), () -> {
                request.committed = true; request.pane().getTextArea().removeCaretListener(request.caret);
                request.pane().getTextArea().removeFocusListener(request.focus); linkRequest = null;
            }, failure -> {
                finishLinkWork();
                if (failure != null) { if (linkRequest == request) { editor.showMessage(failure.getMessage()); cancelLink(request); } return; }
                if (!request.committed || editor.getActivePane() != request.pane()) return;
                FileBuffer buffer = editor.getCurrentBuffer();
                if (buffer == null || editor.writingArea.getDocument() != buffer.getDocument()) return;
                if (target.offset() >= 0 && !buffer.isLargeFile()) {
                    try { new MarkdownSourceRange(target.offset(), target.offset()).validate(buffer.textSnapshot()); editor.writingArea.setCaretPosition(target.offset()); }
                    catch (IllegalArgumentException stale) { editor.showMessage("Markdown target position unavailable"); return; }
                }
                request.pane().surface().focus(); editor.showMessage(backlink ? "Opened Markdown reference" : "Opened Markdown link");
            });
    }
    private void finishLinkWork() {
        linkWorkerRunning = false;
        Runnable next = linkPending; linkPending = null;
        if (next != null && !editor.closingDown) SwingUtilities.invokeLater(next);
    }
    private String openExternalLink(String destination) {
        try {
            var uri = new URI(destination);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return "Unsupported external Markdown link scheme";
            return linkBrowser.apply(uri);
        } catch (URISyntaxException invalid) { return "Invalid external Markdown link"; }
    }
    private void externalWithoutProject(LinkRequest request) {
        requestHeadings(request, request.buffer(), () -> linkCurrent(request), result -> {
            if (!linkCurrent(request)) return;
            if (result.semantic().status() != MarkdownSemanticSnapshot.Status.SUCCEEDED) {
                editor.showMessage("Markdown links unavailable: " + result.semantic().status().name().toLowerCase(Locale.ROOT)); cancelLink(request); return;
            }
            boolean wiki = result.semantic().wikiLinks().stream().anyMatch(link -> request.dot >= link.range().start() && request.dot < link.range().end());
            var links = result.semantic().links().stream().filter(link -> link.location().segments().stream()
                .anyMatch(range -> request.dot >= range.start() && request.dot < range.end())).toList();
            var hyperlinks = links.stream().filter(link -> link.kind() == MarkdownSemanticSnapshot.LinkKind.LINK).toList();
            if (!hyperlinks.isEmpty()) links = hyperlinks;
            String destination = !wiki && links.size() == 1 ? links.getFirst().destination() : "";
            String message = destination.regionMatches(true, 0, "http://", 0, 7) || destination.regionMatches(true, 0, "https://", 0, 8)
                ? openExternalLink(destination) : "Open the owning project folder to navigate Markdown files";
            cancelLink(request); editor.showMessage(message);
        });
    }

    public String goToMarkdownLink() { return beginLinks(0); }


    public String setConcealLevel(int level) {
        editor.concealLevel = Math.max(0, Math.min(2, level));
        editor.applySyntaxHighlighting();
        return "Conceal level: " + editor.concealLevel;
    }


    boolean isOnCodeFenceLine() {
        try {
            int line = editor.getCurrentCaretLine();
            int start = editor.writingArea.getLineStartOffset(line);
            int length = Math.min(16, Math.min(editor.writingArea.getLineEndOffset(line), editor.writingArea.getDocument().getLength()) - start);
            return editor.writingArea.getText(start, length).stripLeading().startsWith("```");
        } catch (BadLocationException error) { return false; }
    }

    String completeCodeFenceLanguage() {
        String unavailable = mutationUnavailable();
        if (unavailable != null) return unavailable;
        try {
            int index = editor.getCurrentCaretLine();
            SourceLine line = keyboardLine(index);
            if (line == null || editor.writingArea.getSelectionStart() != editor.writingArea.getSelectionEnd())
                return "Fence completion unavailable: unsupported line or selection";
            var context = editor.syntaxUiController.markdownKeyboardContext(editor.getCurrentBuffer(), index, line.start());
            var fence = MarkdownKeyboardContext.fence(line.text());
            if (!context.text() || fence == null || fence.marker() != '`' || fence.info().indexOf('`') >= 0
                || MarkdownKeyboardContext.advance(context, line.text()) == MarkdownKeyboardContext.UNKNOWN)
                return "Fence completion unavailable: not a verified opening backtick fence";
            String[] matches = editor.markdownService.filterCodeFenceLanguages(fence.info().trim());
            if (matches.length == 0) return "No matching language";
            int languageStart = fence.markerEnd();
            while (languageStart < line.text().length() && (line.text().charAt(languageStart) == ' ' || line.text().charAt(languageStart) == '\t')) languageStart++;
            return replace(line.start() + languageStart, line.end(), matches[0], -1, "Language: " + matches[0]);
        } catch (BadLocationException error) { return "Markdown edit unavailable: invalid line"; }
    }


    String expandSnippetAtCursor() {
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf == null) return "No buffer";
        FileType ft = buf.getFileType();
        if (ft == FileType.MARKDOWN) {
            String unavailable = mutationUnavailable();
            if (unavailable != null) return unavailable;
        }
        int pos = editor.writingArea.getCaretPosition();
        String text = editor.writingArea.getText();

        // Find the word before cursor
        int wordStart = pos;
        while (wordStart > 0 && !Character.isWhitespace(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        if (wordStart == pos) return "No trigger word";
        String trigger = text.substring(wordStart, pos);
        SnippetService.Snippet snippet = editor.snippetService.findExact(ft, editor.snippetLanguageId(buf), trigger);
        if (snippet == null) return "No snippet: " + trigger;
        String expanded = editor.snippetService.expand(snippet);
        int cursorOffset = editor.snippetService.cursorOffset(snippet);
        if (ft == FileType.MARKDOWN) {
            try {
                return replace(wordStart, pos, expanded, wordStart + (cursorOffset >= 0 ? cursorOffset : expanded.length()), "Expanded: " + trigger);
            } catch (BadLocationException error) { return "Markdown edit unavailable: invalid snippet range"; }
        }
        editor.suppressDocumentEvents = true;
        editor.writingArea.replaceRange(expanded, wordStart, pos);
        editor.suppressDocumentEvents = false;
        if (cursorOffset >= 0) {
            editor.writingArea.setCaretPosition(Math.min(wordStart + cursorOffset, editor.writingArea.getText().length()));
        }
        editor.markModified();
        return "Expanded: " + trigger;
    }


    public String listSnippets() {
        FileBuffer buf = editor.getCurrentBuffer();
        FileType ft = buf != null ? buf.getFileType() : null;
        String listing = editor.snippetService.listSnippets(ft, editor.snippetLanguageId(buf));
        FileBuffer snippetBuf = FileBuffer.createScratch("[Snippets]", listing);
        editor.buffers.add(snippetBuf);
        editor.loadBufferIntoEditor(snippetBuf);
        return "Showing snippets";
    }


    public String toggleBracketColors() {
        editor.bracketColorEnabled = !editor.bracketColorEnabled;
        applyBracketHighlighting();
        return editor.bracketColorEnabled ? "Bracket colors enabled" : "Bracket colors disabled";
    }


    void applyBracketHighlighting() {
        clearBracketHighlighting();
        if (!editor.bracketColorEnabled) return;
        String text = editor.writingArea.getText();
        if (text.isEmpty()) return;
        List<BracketColorService.ColoredBracket> brackets = editor.bracketColorService.computeBracketColors(text);
        Highlighter highlighter = editor.writingArea.getHighlighter();
        for (BracketColorService.ColoredBracket bracket : brackets) {
            try {
                Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(bracket.color());
                editor.bracketHighlightTags.add(highlighter.addHighlight(bracket.offset, bracket.offset + 1, painter));
            } catch (BadLocationException ignored) {}
        }
    }


    void clearBracketHighlighting() {
        Highlighter highlighter = editor.writingArea.getHighlighter();
        for (Object tag : editor.bracketHighlightTags) {
            highlighter.removeHighlight(tag);
        }
        editor.bracketHighlightTags.clear();
    }

}
