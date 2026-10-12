package shed;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Searchable Markdown heading outline that navigates to the source document. */
final class MarkdownOutlineDialog extends JDialog {
    record Heading(int line, int level, String title) {
        @Override public String toString() {
            return "  ".repeat(level - 1) + title + "   ·   " + line;
        }
    }

    private final Texteditor editor;
    private FileBuffer source;
    private final List<Heading> headings = new ArrayList<>();
    private final DefaultListModel<Heading> model = new DefaultListModel<>();
    private final JList<Heading> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final JLabel count = new JLabel();

    static void showFor(Texteditor editor, FileBuffer source, String[] lines) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showFor(editor, source, lines));
            return;
        }
        for (Window window : editor.getOwnedWindows()) {
            if (window instanceof MarkdownOutlineDialog dialog && dialog.isDisplayable()) {
                dialog.load(source, lines);
                dialog.setVisible(true);
                dialog.toFront();
                return;
            }
        }
        MarkdownOutlineDialog dialog = new MarkdownOutlineDialog(editor);
        dialog.load(source, lines);
        dialog.setVisible(true);
    }

    private MarkdownOutlineDialog(Texteditor editor) {
        super(editor, "Table of Contents", false);
        this.editor = editor;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout());
        add(header(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 560, 530);
        pack();
        setLocationRelativeTo(editor);
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(22, 24, 14, 24));
        JLabel title = new JLabel("Table of Contents");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 7f));
        panel.add(title, BorderLayout.NORTH);
        search.putClientProperty("JTextField.placeholderText", "Filter headings");
        AccessibilitySupport.describe(search, "Filter headings", "Search headings in the current Markdown document.");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter(); }
            @Override public void removeUpdate(DocumentEvent event) { filter(); }
            @Override public void changedUpdate(DocumentEvent event) { filter(); }
        });
        search.addActionListener(event -> goToSelection());
        panel.add(search, BorderLayout.SOUTH);
        return panel;
    }

    private JScrollPane body() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) goToSelection();
            }
        });
        AccessibilitySupport.describe(list, "Markdown headings", "Choose a heading to jump to its line.");
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 24));
        return scroll;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 24, 18, 24));
        panel.add(count, BorderLayout.WEST);
        JPanel buttons = new JPanel();
        JButton go = new JButton("Go to heading");
        go.addActionListener(event -> goToSelection());
        buttons.add(go);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        buttons.add(close);
        panel.add(buttons, BorderLayout.EAST);
        getRootPane().setDefaultButton(go);
        return panel;
    }

    private void load(FileBuffer buffer, String[] lines) {
        source = buffer;
        headings.clear();
        for (int index = 0; index < lines.length; index++) {
            int level = editor.markdownService.headingLevel(lines[index]);
            if (level > 0) headings.add(new Heading(index + 1, level, editor.markdownService.headingText(lines[index])));
        }
        search.setText("");
        filter();
    }

    private void filter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        for (Heading heading : headings) {
            if (heading.title().toLowerCase(Locale.ROOT).contains(query)) model.addElement(heading);
        }
        count.setText(model.size() + " heading" + (model.size() == 1 ? "" : "s"));
        if (!model.isEmpty()) list.setSelectedIndex(0);
    }

    private void goToSelection() {
        Heading heading = list.getSelectedValue();
        if (heading == null) return;
        if (editor.getCurrentBuffer() != source) editor.loadBufferIntoEditor(source);
        editor.gotoLine(heading.line());
        editor.toFront();
        editor.writingArea.requestFocusInWindow();
    }
}
