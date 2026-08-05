package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyListener;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;

final class MarkdownPreviewPane extends JPanel {
    private static final int RENDER_DEBOUNCE_MS = 120;
    private final FileBuffer source;
    private final Supplier<Font> fontSupplier;
    private final Supplier<Color> backgroundSupplier;
    private final Supplier<Color> foregroundSupplier;
    private final Consumer<String> linkHandler;
    private final Runnable focusHandler;
    private final JLabel title;
    private final JEditorPane preview;
    private final JScrollPane scrollPane;
    private final MarkdownPreviewAssets assets;
    private final Timer renderTimer;
    private final DocumentListener sourceListener;
    private String html = "";
    private boolean disposed;

    MarkdownPreviewPane(FileBuffer source, Supplier<Font> fontSupplier, Supplier<Color> backgroundSupplier,
                        Supplier<Color> foregroundSupplier, Consumer<String> linkHandler, Runnable focusHandler,
                        KeyListener editorKeyListener) {
        super(new BorderLayout());
        this.source = source;
        this.fontSupplier = fontSupplier;
        this.backgroundSupplier = backgroundSupplier;
        this.foregroundSupplier = foregroundSupplier;
        this.linkHandler = linkHandler;
        this.focusHandler = focusHandler;
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
        SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(scrollPosition));
    }
}
