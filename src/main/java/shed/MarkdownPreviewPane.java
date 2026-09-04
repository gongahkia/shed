package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyListener;
import java.awt.geom.Rectangle2D;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;

final class MarkdownPreviewPane extends JPanel {
    private static final int RENDER_DEBOUNCE_MS = 120;
    private static final int MAX_DEFERRED_SCROLL_SYNCS = 3;
    private final FileBuffer source;
    private final Supplier<Font> fontSupplier;
    private final Supplier<Color> backgroundSupplier;
    private final Supplier<Color> foregroundSupplier;
    private final Consumer<String> linkHandler;
    private final Runnable focusHandler;
    private final BooleanSupplier scrollSyncEnabled;
    private final EditorPane sourcePane;
    private final JLabel title;
    private final JEditorPane preview;
    private final JScrollPane scrollPane;
    private final MarkdownPreviewAssets assets;
    private final Timer renderTimer;
    private final DocumentListener sourceListener;
    private final CaretListener sourceCaretListener;
    private final java.awt.event.AdjustmentListener sourceScrollListener;
    private String html = "";
    private boolean disposed;
    private boolean syncQueued;
    private boolean syncToCaret;
    private int deferredScrollSyncs;

    MarkdownPreviewPane(FileBuffer source, Supplier<Font> fontSupplier, Supplier<Color> backgroundSupplier,
                        Supplier<Color> foregroundSupplier, Consumer<String> linkHandler, Runnable focusHandler,
                        BooleanSupplier scrollSyncEnabled, EditorPane sourcePane, KeyListener editorKeyListener) {
        super(new BorderLayout());
        this.source = source;
        this.fontSupplier = fontSupplier;
        this.backgroundSupplier = backgroundSupplier;
        this.foregroundSupplier = foregroundSupplier;
        this.linkHandler = linkHandler;
        this.focusHandler = focusHandler;
        this.scrollSyncEnabled = scrollSyncEnabled;
        this.sourcePane = sourcePane;
        assets = new MarkdownPreviewAssets();
        title = new JLabel("Markdown Preview — " + source.getDisplayName(), SwingConstants.LEADING);
        title.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        preview = new JEditorPane();
        preview.setContentType("text/html");
        preview.setEditable(false);
        preview.addKeyListener(editorKeyListener);
        preview.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        preview.getAccessibleContext().setAccessibleName("Markdown Preview");
        preview.addHyperlinkListener(event -> {
            if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            String href = event.getDescription();
            if (href != null && href.startsWith("#")) {
                preview.scrollToReference(href.substring(1));
            } else if (href != null) {
                linkHandler.accept(href);
            }
        });
        preview.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { focusHandler.run(); }
        });
        preview.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) { focusHandler.run(); }
        });
        scrollPane = new JScrollPane(preview);
        add(title, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        renderTimer = new Timer(RENDER_DEBOUNCE_MS, event -> renderNow());
        renderTimer.setRepeats(false);
        sourceListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { scheduleRender(); }
            @Override public void removeUpdate(DocumentEvent event) { scheduleRender(); }
            @Override public void changedUpdate(DocumentEvent event) { scheduleRender(); }
        };
        source.getDocument().addDocumentListener(sourceListener);
        sourceCaretListener = event -> queueSourceSync(true);
        sourceScrollListener = event -> queueSourceSync(false);
        sourcePane.getTextArea().addCaretListener(sourceCaretListener);
        sourcePane.getScrollPane().getVerticalScrollBar().addAdjustmentListener(sourceScrollListener);
        refreshAppearance();
        renderNow();
    }

    void refreshAppearance() {
        if (disposed) return;
        Font font = fontSupplier.get();
        Color background = backgroundSupplier.get();
        Color foreground = foregroundSupplier.get();
        title.setFont(font);
        title.setBackground(background);
        title.setForeground(foreground);
        title.setOpaque(true);
        preview.setFont(font);
        preview.setBackground(background);
        preview.setForeground(foreground);
        renderNow();
    }

    void refreshNow() {
        if (!disposed) renderNow();
    }

    String getHtml() {
        return html;
    }

    void requestPreviewFocus() {
        preview.requestFocusInWindow();
    }

    void disposePreview() {
        if (disposed) return;
        disposed = true;
        renderTimer.stop();
        source.getDocument().removeDocumentListener(sourceListener);
        sourcePane.getTextArea().removeCaretListener(sourceCaretListener);
        sourcePane.getScrollPane().getVerticalScrollBar().removeAdjustmentListener(sourceScrollListener);
        assets.close();
    }

    private void scheduleRender() {
        if (!disposed) renderTimer.restart();
    }

    private void renderNow() {
        if (disposed) return;
        int scrollPosition = scrollPane.getVerticalScrollBar().getValue();
        Font font = fontSupplier.get();
        Color background = backgroundSupplier.get();
        Color foreground = foregroundSupplier.get();
        title.setText("Markdown Preview — " + source.getDisplayName());
        html = MarkdownPreviewRenderer.render(source.getFullContent(), source.getDisplayName(), font, background, foreground, assets, source.getFile());
        preview.setText(html);
        if (isScrollSyncEnabled()) {
            queueSourceSync(false);
        } else {
            SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(scrollPosition));
        }
    }

    int getVerticalScrollPosition() {
        return scrollPane.getVerticalScrollBar().getValue();
    }

    private boolean isScrollSyncEnabled() {
        return scrollSyncEnabled != null && scrollSyncEnabled.getAsBoolean();
    }

    private void queueSourceSync(boolean caret) {
        if (disposed || !isScrollSyncEnabled()) return;
        if (!syncQueued) {
            syncQueued = true;
            syncToCaret = caret;
            SwingUtilities.invokeLater(this::runQueuedSourceSync);
        } else if (!caret) {
            syncToCaret = false;
        }
    }

    private void runQueuedSourceSync() {
        syncQueued = false;
        if (disposed || !isScrollSyncEnabled()) return;
        if (syncPreviewToSource(syncToCaret)) {
            deferredScrollSyncs = 0;
            return;
        }
        if (deferredScrollSyncs++ < MAX_DEFERRED_SCROLL_SYNCS) {
            queueSourceSync(syncToCaret);
        }
    }

    /**
     * The HTML view may not have its scroll range when a source event first
     * arrives, particularly while a newly created split is being laid out.
     * Returning false lets the bounded deferred path retry after Swing has had
     * a chance to size the viewport.
     */
    private boolean syncPreviewToSource(boolean caret) {
        if (disposed || !isScrollSyncEnabled()) return true;
        JScrollBar sourceBar = sourcePane.getScrollPane().getVerticalScrollBar();
        JScrollBar previewBar = scrollPane.getVerticalScrollBar();
        int sourceMaximum = Math.max(0, sourceBar.getMaximum() - sourceBar.getVisibleAmount());
        int previewMaximum = Math.max(0, previewBar.getMaximum() - previewBar.getVisibleAmount());
        if (previewMaximum == 0) {
            establishPreviewScrollRange();
            previewMaximum = Math.max(0, previewBar.getMaximum() - previewBar.getVisibleAmount());
        }
        if (previewMaximum == 0) {
            preview.revalidate();
            scrollPane.revalidate();
            return false;
        }
        int sourcePosition = caret ? caretVerticalPosition(sourceMaximum) : sourceBar.getValue();
        double progress = sourceMaximum == 0 ? caretDocumentProgress() : (double) sourcePosition / sourceMaximum;
        previewBar.setValue((int) Math.round(Math.max(0.0, Math.min(1.0, progress)) * previewMaximum));
        return true;
    }

    private void establishPreviewScrollRange() {
        Dimension preferred = preview.getPreferredSize();
        if (preferred.height <= 0) return;
        Dimension extent = scrollPane.getViewport().getExtentSize();
        int width = extent.width > 0 ? extent.width : Math.max(1, preferred.width);
        preview.setSize(width, Math.max(1, preferred.height));
        scrollPane.doLayout();
    }

    private int caretVerticalPosition(int sourceMaximum) {
        try {
            Rectangle2D bounds = sourcePane.getTextArea().modelToView2D(sourcePane.getTextArea().getCaretPosition());
            if (bounds != null) return Math.min(sourceMaximum, Math.max(0, (int) Math.round(bounds.getY())));
        } catch (javax.swing.text.BadLocationException ignored) {
        }
        return (int) Math.round(caretDocumentProgress() * sourceMaximum);
    }

    private double caretDocumentProgress() {
        int length = sourcePane.getTextArea().getDocument().getLength();
        return length == 0 ? 0.0 : (double) sourcePane.getTextArea().getCaretPosition() / length;
    }
}
