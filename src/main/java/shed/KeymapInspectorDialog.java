package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

final class KeymapInspectorDialog extends JDialog {
    private final Texteditor editor;
    private final JTextField searchField;
    private final JTextField scopeField;
    private final JTextField lhsField;
    private final JTextField rhsField;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private List<KeymapOverlay.Binding> visibleBindings;

    static void showFor(Texteditor editor) {
        new KeymapInspectorDialog(editor).setVisible(true);
    }

    private KeymapInspectorDialog(Texteditor editor) {
        super(editor, "Keymap Inspector", false);
        this.editor = editor;
        this.searchField = new JTextField();
        this.scopeField = new JTextField("normal");
        this.lhsField = new JTextField();
        this.rhsField = new JTextField();
        this.tableModel = new DefaultTableModel(new Object[] {"Scope", "Key", "Mapping", "Source", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        this.table = new JTable(tableModel);
        this.visibleBindings = List.of();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(1080, 480));
        pack();
        setLocationRelativeTo(editor);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshTable(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshTable(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshTable(); }
        });
        refreshTable();
    }

    private JPanel header() {
        JPanel search = new JPanel(new BorderLayout(8, 0));
        search.add(new JLabel("Search"), BorderLayout.WEST);
        search.add(searchField, BorderLayout.CENTER);
        JPanel binding = new JPanel(new java.awt.GridLayout(1, 7, 6, 0));
        binding.add(new JLabel("Scope"));
        binding.add(scopeField);
        binding.add(new JLabel("Key"));
        binding.add(lhsField);
        binding.add(new JLabel("Mapping"));
        binding.add(rhsField);
        JButton save = new JButton("Save Overlay");
        save.addActionListener(event -> saveOverlay());
        binding.add(save);
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        panel.add(search, BorderLayout.NORTH);
        panel.add(binding, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel actions() {
        JButton useSelected = new JButton("Edit Selected");
        useSelected.addActionListener(event -> editSelected());
        JButton reset = new JButton("Reset Selected");
        reset.addActionListener(event -> resetSelected());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refreshTable());
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(useSelected);
        panel.add(reset);
        panel.add(refresh);
        panel.add(close);
        return panel;
    }

    private void refreshTable() {
        String query = searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        List<KeymapOverlay.Binding> matches = new ArrayList<>();
        for (KeymapOverlay.Binding binding : editor.getEffectiveKeybindings()) {
            if (KeymapOverlay.matches(binding, query)) {
                matches.add(binding);
            }
        }
        visibleBindings = List.copyOf(matches);
        tableModel.setRowCount(0);
        for (KeymapOverlay.Binding binding : visibleBindings) {
            tableModel.addRow(new Object[] {binding.scope(), binding.lhs(), binding.mapping(), binding.source(), binding.status()});
        }
    }

    private void editSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleBindings.size()) {
            JOptionPane.showMessageDialog(this, "Select a user overlay to edit", "Keymap Inspector", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        KeymapOverlay.Binding binding = visibleBindings.get(row);
        if (binding.configKey() == null) {
            JOptionPane.showMessageDialog(this, "Profile bindings are fixed; create a user overlay instead", "Keymap Inspector", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        scopeField.setText(binding.scope());
        lhsField.setText(binding.lhs());
        rhsField.setText(binding.mapping());
    }

    private void saveOverlay() {
        String result = editor.setKeybindingPersistent(scopeField.getText(), lhsField.getText(), rhsField.getText());
        if (result.startsWith("Error")) {
            JOptionPane.showMessageDialog(this, result, "Keymap Inspector", JOptionPane.ERROR_MESSAGE);
            return;
        }
        refreshTable();
    }

    private void resetSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleBindings.size()) {
            JOptionPane.showMessageDialog(this, "Select a user overlay to reset", "Keymap Inspector", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        KeymapOverlay.Binding binding = visibleBindings.get(row);
        if (binding.configKey() == null) {
            JOptionPane.showMessageDialog(this, "Profile bindings have no resettable overlay", "Keymap Inspector", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String result = editor.resetKeybindingPersistent(binding.scope(), binding.lhs());
        if (result.startsWith("Error")) {
            JOptionPane.showMessageDialog(this, result, "Keymap Inspector", JOptionPane.ERROR_MESSAGE);
            return;
        }
        refreshTable();
    }
}
