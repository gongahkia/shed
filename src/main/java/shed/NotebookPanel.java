package shed;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Color;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JEditorPane;
import javax.swing.ImageIcon;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Swing notebook surface for Jupyter's JSON notebook format. */
final class NotebookPanel extends JPanel {
    private static final int MAX_IMAGE_DIMENSION = 4_096;
    private static final long MAX_IMAGE_PIXELS = 16L * 1024L * 1024L;
    private static final int MAX_DISPLAY_WIDTH = 1_000;
    private static final int MAX_DISPLAY_HEIGHT = 700;

    private final NotebookDocument baseDocument;
    private final JPanel cellsPanel = new JPanel();
    private final List<CellEditor> cells = new ArrayList<>();
    private final Consumer<NotebookDocument> saveAction;
    private final Consumer<NotebookDocument> runAction;
    private final BiConsumer<NotebookDocument, Integer> runThroughAction;
    private final File sourceFile;

    NotebookPanel(NotebookDocument document, Consumer<NotebookDocument> saveAction, Consumer<NotebookDocument> runAction,
        BiConsumer<NotebookDocument, Integer> runThroughAction, File sourceFile) {
        super(new BorderLayout(0, 5));
        this.baseDocument = document == null ? NotebookDocument.empty() : document;
        this.saveAction = saveAction == null ? ignored -> { } : saveAction;
        this.runAction = runAction == null ? ignored -> { } : runAction;
        this.runThroughAction = runThroughAction == null ? (ignored, index) -> { } : runThroughAction;
        this.sourceFile = sourceFile;
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING, 5, 0));
        JButton save = new JButton("Save");
        save.addActionListener(event -> this.saveAction.accept(document()));
        JButton runAll = new JButton("Run all");
        runAll.addActionListener(event -> this.runAction.accept(document()));
        JButton addCode = new JButton("+ Code");
        addCode.addActionListener(event -> add(new NotebookDocument.Cell("code", "", java.util.Map.of())));
        JButton addMarkdown = new JButton("+ Markdown");
        addMarkdown.addActionListener(event -> add(new NotebookDocument.Cell("markdown", "", java.util.Map.of())));
        toolbar.add(save);
        toolbar.add(runAll);
        toolbar.add(addCode);
        toolbar.add(addMarkdown);
        toolbar.add(new JLabel("Execution uses an installed Jupyter CLI and is explicit."));
        add(toolbar, BorderLayout.NORTH);

        cellsPanel.setLayout(new BoxLayout(cellsPanel, BoxLayout.Y_AXIS));
        for (NotebookDocument.Cell cell : baseDocument.cells()) add(cell);
        JScrollPane scroll = new JScrollPane(cellsPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private void add(NotebookDocument.Cell cell) {
        int index = cells.size() + 1;
        CellEditor editor = new CellEditor(cell, index, () -> runThroughAction.accept(document(), index), sourceFile);
        cells.add(editor);
        cellsPanel.add(editor);
        cellsPanel.add(Box.createVerticalStrut(8));
        cellsPanel.revalidate();
        cellsPanel.repaint();
    }

    private NotebookDocument document() {
        List<NotebookDocument.Cell> updated = new ArrayList<>();
        for (CellEditor editor : cells) updated.add(editor.cell());
        return baseDocument.withCells(updated);
    }

    private static final class CellEditor extends JPanel {
        private final NotebookDocument.Cell original;
        private final JTextArea source;

        private CellEditor(NotebookDocument.Cell cell, int index, Runnable runThrough, File sourceFile) {
            super(new BorderLayout(0, 4));
            this.original = cell;
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(java.awt.Color.GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEADING, 5, 0));
            header.add(new JLabel("Cell " + index + " · " + cell.type()));
            JButton run = new JButton("Run to here");
            run.addActionListener(event -> runThrough.run());
            header.add(run);
            add(header, BorderLayout.NORTH);
            source = new JTextArea(cell.source(), "code".equals(cell.type()) ? 8 : 5, 80);
            source.setTabSize(4);
            if ("markdown".equals(cell.type())) {
                CardLayout cards = new CardLayout();
                javax.swing.JPanel content = new javax.swing.JPanel(cards);
                content.add(new JScrollPane(source), "source");
                JEditorPane preview = new JEditorPane();
                preview.setContentType("text/html");
                preview.setEditable(false);
                preview.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
                preview.getAccessibleContext().setAccessibleName("Notebook Markdown Preview");
                content.add(new JScrollPane(preview), "preview");
                JToggleButton render = new JToggleButton("Preview");
                render.addActionListener(event -> {
                    if (render.isSelected()) {
                        preview.setText(markdownPreview(source.getText(), sourceFile));
                        cards.show(content, "preview");
                        render.setText("Edit");
                    } else {
                        cards.show(content, "source");
                        render.setText("Preview");
                    }
                });
                source.getDocument().addDocumentListener(new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent event) { refresh(); }
                    @Override public void removeUpdate(DocumentEvent event) { refresh(); }
                    @Override public void changedUpdate(DocumentEvent event) { refresh(); }
                    private void refresh() {
                        if (render.isSelected()) preview.setText(markdownPreview(source.getText(), sourceFile));
                    }
                });
                header.add(render);
                add(content, BorderLayout.CENTER);
            } else {
                add(new JScrollPane(source), BorderLayout.CENTER);
            }
            String output = NotebookDocument.outputText(cell);
            List<NotebookDocument.ImageOutput> images = NotebookDocument.imageOutputs(cell);
            if (!output.isBlank() || !images.isEmpty()) {
                JPanel outputPanel = new JPanel(new BorderLayout(0, 3));
                outputPanel.add(new JLabel("Output"), BorderLayout.NORTH);
                JPanel contents = new JPanel();
                contents.setLayout(new BoxLayout(contents, BoxLayout.Y_AXIS));
                if (!output.isBlank()) {
                    JTextArea outputs = new JTextArea(output, Math.min(8, Math.max(2, output.lines().toArray().length)), 80);
                    outputs.setEditable(false);
                    outputs.setLineWrap(true);
                    outputs.setWrapStyleWord(true);
                    contents.add(new JScrollPane(outputs));
                }
                for (NotebookDocument.ImageOutput image : images) {
                    try {
                        BufferedImage decoded = decodeImage(image);
                        if (decoded != null) {
                            JLabel display = new JLabel(new ImageIcon(decoded));
                            display.setAlignmentX(Component.LEFT_ALIGNMENT);
                            contents.add(Box.createVerticalStrut(5));
                            contents.add(display);
                        }
                    } catch (IOException ignored) {
                        // Invalid or oversized image data remains omitted from the active surface.
                    }
                }
                outputPanel.add(contents, BorderLayout.CENTER);
                add(outputPanel, BorderLayout.SOUTH);
            }
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        private NotebookDocument.Cell cell() {
            return new NotebookDocument.Cell(original.type(), source.getText(), original.fields());
        }

        private static String markdownPreview(String markdown, File sourceFile) {
            return MarkdownPreviewRenderer.renderBasic(markdown, "Notebook Markdown", new Font(Font.MONOSPACED, Font.PLAIN, 13),
                Color.WHITE, Color.BLACK, sourceFile);
        }
    }

    static BufferedImage decodeImage(NotebookDocument.ImageOutput output) throws IOException {
        if (output == null || (!"image/png".equals(output.mimeType()) && !"image/jpeg".equals(output.mimeType()))) return null;
        byte[] bytes = output.bytes();
        if (bytes.length == 0) return null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                    || (long) width * height > MAX_IMAGE_PIXELS) return null;
                return scaleForDisplay(reader.read(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scaleForDisplay(BufferedImage image) {
        if (image == null || (image.getWidth() <= MAX_DISPLAY_WIDTH && image.getHeight() <= MAX_DISPLAY_HEIGHT)) return image;
        double scale = Math.min((double) MAX_DISPLAY_WIDTH / image.getWidth(), (double) MAX_DISPLAY_HEIGHT / image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }
}
