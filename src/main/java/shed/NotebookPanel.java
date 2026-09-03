package shed;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Swing notebook surface for Jupyter's JSON notebook format. */
final class NotebookPanel extends JPanel {
    private final NotebookDocument baseDocument;
    private final JPanel cellsPanel = new JPanel();
    private final List<CellEditor> cells = new ArrayList<>();
    private final Consumer<NotebookDocument> saveAction;
    private final Consumer<NotebookDocument> runAction;

    NotebookPanel(NotebookDocument document, Consumer<NotebookDocument> saveAction, Consumer<NotebookDocument> runAction) {
        super(new BorderLayout(0, 5));
        this.baseDocument = document == null ? NotebookDocument.empty() : document;
        this.saveAction = saveAction == null ? ignored -> { } : saveAction;
        this.runAction = runAction == null ? ignored -> { } : runAction;
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
        CellEditor editor = new CellEditor(cell, cells.size() + 1);
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

        private CellEditor(NotebookDocument.Cell cell, int index) {
            super(new BorderLayout(0, 4));
            this.original = cell;
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(java.awt.Color.GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
            add(new JLabel("Cell " + index + " · " + cell.type()), BorderLayout.NORTH);
            source = new JTextArea(cell.source(), "code".equals(cell.type()) ? 8 : 5, 80);
            source.setTabSize(4);
            add(new JScrollPane(source), BorderLayout.CENTER);
            String output = NotebookDocument.outputText(cell);
            if (!output.isBlank()) {
                JTextArea outputs = new JTextArea(output, Math.min(8, Math.max(2, output.lines().toArray().length)), 80);
                outputs.setEditable(false);
                outputs.setLineWrap(true);
                outputs.setWrapStyleWord(true);
                JPanel outputPanel = new JPanel(new BorderLayout(0, 3));
                outputPanel.add(new JLabel("Output"), BorderLayout.NORTH);
                outputPanel.add(new JScrollPane(outputs), BorderLayout.CENTER);
                add(outputPanel, BorderLayout.SOUTH);
            }
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        private NotebookDocument.Cell cell() {
            return new NotebookDocument.Cell(original.type(), source.getText(), original.fields());
        }
    }
}
