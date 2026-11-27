package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JComboBox;
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
    private final JComboBox<String> scopeField;
    private final JTextField lhsField;
    private final JTextField rhsField;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private List<KeymapOverlay.Binding> visibleBindings;
    private boolean suppressRecordedTyped;
    private final JLabel feedback = new JLabel("Select a shortcut to edit it, or record a new shortcut.");
    private final javax.swing.JToggleButton record = new javax.swing.JToggleButton("Record shortcut");

    static void showFor(Texteditor editor) {
        new KeymapInspectorDialog(editor).setVisible(true);
    }

    private KeymapInspectorDialog(Texteditor editor) {
        super(editor, "Keyboard Shortcuts", false);
        this.editor = editor;
        this.searchField = new JTextField();
        AccessibilitySupport.describe(searchField, "Keymap search", "Search effective keybindings by scope, key, mapping, source, or status.");
        this.scopeField = new JComboBox<>(new String[] {"plain", "emacs", "vim", "global", "normal", "insert", "visual", "visual_line", "replace", "command", "search"});
        scopeField.setSelectedItem(editor.configManager.getKeymapProfile().configValue());
        AccessibilitySupport.describe(scopeField, "Keybinding scope", "Choose a keymap profile, global fallback, or a Vim mode.");
        this.lhsField = new JTextField();
        AccessibilitySupport.describe(lhsField, "Keybinding key", "Key or supported key token to override.");
        this.rhsField = new JTextField();
        AccessibilitySupport.describe(rhsField, "Keybinding mapping", "action:name, command:Ex command, nop, or a legacy Vim key sequence.");
        this.tableModel = new DefaultTableModel(new Object[] {"Scope", "Key", "Mapping", "Source", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        this.table = new JTable(tableModel);
        AccessibilitySupport.describe(table, "Effective keybindings", "Searchable active profile bindings and user overlays, including override and shadowing status.");
        this.visibleBindings = List.of();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 1080, 480);
        pack();
        setLocationRelativeTo(editor);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshTable(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshTable(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshTable(); }
        });
        table.setRowHeight(Math.max(table.getRowHeight(), UiZoom.scale(40, editor.configManager.getUiZoom())));
        lhsField.setName("shortcuts.key"); rhsField.setName("shortcuts.mapping"); record.setName("shortcuts.record");
        record.addActionListener(e -> { if(record.isSelected()){feedback.setText("Press a modified shortcut or function key in the Key field. Escape cancels.");lhsField.requestFocusInWindow();} });
        lhsField.addKeyListener(new java.awt.event.KeyAdapter(){@Override public void keyPressed(java.awt.event.KeyEvent e){if(!record.isSelected())return;e.consume();if(e.getKeyCode()==java.awt.event.KeyEvent.VK_ESCAPE){record.setSelected(false);feedback.setText("Recording cancelled.");return;}String captured=ShortcutEditing.capture(e);if(captured!=null){suppressRecordedTyped=true;lhsField.setText(captured);record.setSelected(false);showConflicts();}}@Override public void keyTyped(java.awt.event.KeyEvent e){if(record.isSelected()||suppressRecordedTyped)e.consume();}@Override public void keyReleased(java.awt.event.KeyEvent e){suppressRecordedTyped=false;}});
        table.addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2)editSelected();}});
        lhsField.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){showConflicts();}public void removeUpdate(DocumentEvent e){showConflicts();}public void changedUpdate(DocumentEvent e){showConflicts();}});
        scopeField.addActionListener(e->showConflicts());
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
        JPanel lower = new JPanel(new BorderLayout(0, 5));
        lower.add(binding, BorderLayout.NORTH);
        JComboBox<String> actions = new JComboBox<>(KeymapOverlay.actionNames().toArray(String[]::new));
        AccessibilitySupport.describe(actions, "Available editor actions", "Choose an action to populate the mapping field.");
        actions.addActionListener(event -> rhsField.setText("action:" + actions.getSelectedItem()));
        JPanel hint = new JPanel(new BorderLayout(8, 0));
        hint.add(new JLabel("One shortcut, e.g. <mod-s-k>. Mod means Cmd on macOS, Ctrl elsewhere."), BorderLayout.CENTER);
        var choices=new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING,6,0));choices.add(record);choices.add(actions);
        hint.add(choices, BorderLayout.EAST);
        lower.add(hint, BorderLayout.SOUTH);
        panel.add(lower, BorderLayout.SOUTH);
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
        var footer=new JPanel(new BorderLayout());footer.add(feedback,BorderLayout.NORTH);footer.add(panel);return footer;
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
            JOptionPane.showMessageDialog(this, "Select a binding to customize", "Keymap Inspector", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        KeymapOverlay.Binding binding = visibleBindings.get(row);
        if (binding.configKey() == null) {
            JOptionPane.showMessageDialog(this, "This is a built-in key sequence. Create a single shortcut using an action or command.", "Keymap Inspector", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        scopeField.setSelectedItem(binding.scope());
        lhsField.setText(binding.lhs());
        rhsField.setText(binding.mapping());
    }

    private void showConflicts() {
        var conflicts=ShortcutEditing.conflicts(editor.getEffectiveKeybindings(),String.valueOf(scopeField.getSelectedItem()),lhsField.getText());
        feedback.setText(conflicts.isEmpty()?"No matching shortcut in this scope or global fallback.":"Existing mapping: "+conflicts.stream().map(b->b.scope()+" · "+b.mapping()).distinct().collect(java.util.stream.Collectors.joining("; ")));
    }

    private void saveOverlay() {
        String scope=String.valueOf(scopeField.getSelectedItem()),key="keybind."+scope+"."+lhsField.getText();
        String invalid=KeymapOverlay.validate(key,rhsField.getText());
        if(invalid!=null){feedback.setText(invalid);return;}
        var conflicts=ShortcutEditing.conflicts(editor.getEffectiveKeybindings(),scope,lhsField.getText());
        if(!conflicts.isEmpty()&&JOptionPane.showConfirmDialog(this,"This shortcut already has a mapping in this scope or global fallback. Save this override?","Override shortcut",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        String result = editor.setKeybindingPersistent(String.valueOf(scopeField.getSelectedItem()), lhsField.getText(), rhsField.getText());
        if (result.startsWith("Error")) {
            JOptionPane.showMessageDialog(this, result, "Keymap Inspector", JOptionPane.ERROR_MESSAGE);
            return;
        }
        refreshTable();
        feedback.setText(result);
    }

    private void resetSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleBindings.size()) {
            JOptionPane.showMessageDialog(this, "Select a user overlay to reset", "Keymap Inspector", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        KeymapOverlay.Binding binding = visibleBindings.get(row);
        if (!binding.source().equals("User overlay")) {
            JOptionPane.showMessageDialog(this, "Select a user overlay to reset", "Keymap Inspector", JOptionPane.INFORMATION_MESSAGE);
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
