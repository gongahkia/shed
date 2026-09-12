package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.table.JTableHeader;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;

final class RecoveryWorkspaceDialog extends JDialog {
    private record DialogTheme(Color surface, Color raised, Color field, Color border, Color foreground, Color accent,
                               Color selection, Color selectionText) {
        static DialogTheme from(ConfigManager config) {
            Color surface = config.getNormalColor();
            Color foreground = config.getEditorForeground();
            return new DialogTheme(surface, blend(surface, foreground, 0.07), config.getCommandBarBackground(),
                blend(surface, foreground, 0.18), foreground, config.getCaretColor(), config.getSelectionColor(),
                config.getSelectionTextColor());
        }
    }

    private final Texteditor editor;
    private final DialogTheme theme;
    private final List<EntryView> entries;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextArea originalPreview;
    private final JTextArea recoveryPreview;
    private Result result;

    static Result showFor(Texteditor editor, RecoveryJournal.Journal journal) {
        RecoveryWorkspaceDialog dialog = new RecoveryWorkspaceDialog(editor, viewsFor(journal.entries()));
        dialog.setVisible(true);
        return dialog.result == null ? Result.defer() : dialog.result;
    }

    static List<EntryView> viewsFor(List<RecoveryJournal.Entry> entries) {
        List<EntryView> views = new ArrayList<>();
        if (entries == null) {
            return views;
        }
        for (RecoveryJournal.Entry entry : entries) {
            if (entry != null) {
                views.add(viewFor(entry));
            }
        }
        return List.copyOf(views);
    }

    static EntryView viewFor(RecoveryJournal.Entry entry) {
        if (entry.path() == null || entry.path().isBlank()) {
            return new EntryView(entry, "Scratch document", "No original file exists for this scratch document.");
        }
        try {
            Path path = Path.of(entry.path());
            if (!Files.isRegularFile(path)) {
                return new EntryView(entry, "Original file missing", "Original file is unavailable: " + entry.path());
            }
            return new EntryView(entry, "Current disk content", Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            return new EntryView(entry, "Original file unavailable", "Original file is unavailable: " + entry.path());
        }
    }

    private RecoveryWorkspaceDialog(Texteditor editor, List<EntryView> entries) {
        super(editor, "Crash Recovery Workspace", true);
        this.editor = editor;
        this.theme = DialogTheme.from(editor.configManager);
        this.entries = entries;
        this.tableModel = new DefaultTableModel(new Object[] {"Restore", "Document", "Original", "Recovery"}, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                return column == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };
        this.table = new JTable(tableModel);
        AccessibilitySupport.describe(table, "Recovery snapshots", "Recoverable documents. Toggle Restore before choosing Restore Selected.");
        this.originalPreview = previewArea();
        this.recoveryPreview = previewArea();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::defer);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                defer();
            }
        });
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 1080, 700);
        applyTheme();
        pack();
        setLocationRelativeTo(editor);
        populateTable();
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showSelectedPreview();
            }
        });
        if (!entries.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel("Review each recovery snapshot before restoring it. Defer leaves this journal unchanged."), BorderLayout.CENTER);
        return panel;
    }

    private JSplitPane content() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(titledBorder("Recoverable documents"));
        JScrollPane originalScroll = new JScrollPane(originalPreview);
        originalScroll.setBorder(titledBorder("Original/current content"));
        JScrollPane recoveryScroll = new JScrollPane(recoveryPreview);
        recoveryScroll.setBorder(titledBorder("Recovery snapshot"));
        JSplitPane previews = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, originalScroll, recoveryScroll);
        previews.setResizeWeight(0.5);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, previews);
        split.setResizeWeight(0.3);
        return split;
    }

    private JPanel actions() {
        JButton restore = new JButton("Restore Selected");
        restore.addActionListener(event -> restore());
        JButton discard = new JButton("Discard All");
        discard.addActionListener(event -> discard());
        JButton defer = new JButton("Defer");
        defer.addActionListener(event -> defer());
        JPanel panel = new JPanel();
        panel.add(restore);
        panel.add(discard);
        panel.add(defer);
        return panel;
    }

    private JTextArea previewArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(Font.decode(Font.MONOSPACED));
        area.setLineWrap(false);
        return area;
    }

    private void populateTable() {
        for (EntryView view : entries) {
            tableModel.addRow(new Object[] {Boolean.TRUE, view.entry().name(), view.originalState(), summarize(view.entry().content())});
        }
    }

    private String summarize(String content) {
        int length = content == null ? 0 : content.length();
        return length + " character" + (length == 1 ? "" : "s");
    }

    private void showSelectedPreview() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= entries.size()) {
            originalPreview.setText("");
            recoveryPreview.setText("");
            return;
        }
        EntryView view = entries.get(row);
        originalPreview.setText(view.originalContent());
        originalPreview.setCaretPosition(0);
        recoveryPreview.setText(view.entry().content());
        recoveryPreview.setCaretPosition(0);
    }

    private void restore() {
        List<RecoveryJournal.Entry> selected = new ArrayList<>();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(row, 0))) {
                selected.add(entries.get(row).entry());
            }
        }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select at least one snapshot to restore.", "Crash Recovery", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        result = Result.restore(selected);
        dispose();
    }

    private void discard() {
        int confirmation = JOptionPane.showConfirmDialog(this,
            "Discard all recovery snapshots? This cannot be undone.",
            "Discard Crash Recovery", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirmation == JOptionPane.YES_OPTION) {
            result = Result.discard();
            dispose();
        }
    }

    private void defer() {
        result = Result.defer();
        dispose();
    }

    private TitledBorder titledBorder(String title) {
        TitledBorder border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(theme.border()), title);
        border.setTitleColor(theme.foreground());
        return border;
    }

    private void applyTheme() {
        getContentPane().setBackground(theme.surface());
        applyTheme(getContentPane());
    }

    private void applyTheme(Component component) {
        if (component instanceof JPanel panel) {
            panel.setOpaque(true);
            panel.setBackground(theme.surface());
            panel.setForeground(theme.foreground());
        }
        if (component instanceof JLabel label) label.setForeground(theme.foreground());
        if (component instanceof JButton button) {
            button.setOpaque(true);
            button.setContentAreaFilled(true);
            button.setBackground(theme.raised());
            button.setForeground(theme.foreground());
            button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(theme.border()),
                BorderFactory.createEmptyBorder(UiZoom.scale(4, editor.configManager.getUiZoom()), UiZoom.scale(9, editor.configManager.getUiZoom()),
                    UiZoom.scale(4, editor.configManager.getUiZoom()), UiZoom.scale(9, editor.configManager.getUiZoom()))));
        }
        if (component instanceof JTextArea area) {
            area.setBackground(theme.surface());
            area.setForeground(theme.foreground());
            area.setCaretColor(theme.accent());
            area.setSelectionColor(theme.selection());
            area.setSelectedTextColor(theme.selectionText());
        }
        if (component instanceof JTable value) {
            value.setBackground(theme.field());
            value.setForeground(theme.foreground());
            value.setSelectionBackground(theme.selection());
            value.setSelectionForeground(theme.selectionText());
            value.setGridColor(theme.border());
            JTableHeader header = value.getTableHeader();
            header.setBackground(theme.raised());
            header.setForeground(theme.foreground());
        }
        if (component instanceof JScrollPane scroll) {
            scroll.setBackground(theme.surface());
            scroll.getViewport().setBackground(theme.surface());
            scroll.getHorizontalScrollBar().setBackground(theme.raised());
            scroll.getVerticalScrollBar().setBackground(theme.raised());
        }
        if (component instanceof JSplitPane split) {
            split.setBackground(theme.surface());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) applyTheme(child);
        }
        component.repaint();
    }

    private static Color blend(Color first, Color second, double amount) {
        double ratio = Math.max(0.0, Math.min(1.0, amount));
        return new Color((int) Math.round(first.getRed() * (1.0 - ratio) + second.getRed() * ratio),
            (int) Math.round(first.getGreen() * (1.0 - ratio) + second.getGreen() * ratio),
            (int) Math.round(first.getBlue() * (1.0 - ratio) + second.getBlue() * ratio));
    }

    record EntryView(RecoveryJournal.Entry entry, String originalState, String originalContent) {
    }

    enum Decision {
        RESTORE,
        DISCARD,
        DEFER
    }

    record Result(Decision decision, List<RecoveryJournal.Entry> entries) {
        static Result restore(List<RecoveryJournal.Entry> entries) {
            return new Result(Decision.RESTORE, List.copyOf(entries));
        }

        static Result discard() {
            return new Result(Decision.DISCARD, List.of());
        }

        static Result defer() {
            return new Result(Decision.DEFER, List.of());
        }
    }
}
