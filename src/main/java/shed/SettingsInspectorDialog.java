package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.TableModelEvent;

final class SettingsInspectorDialog extends JDialog {
    private final Texteditor editor;
    private final DefaultListModel<String> categoryModel;
    private final JList<String> categories;
    private final DefaultTableModel tableModel;
    private boolean refreshing;

    static void showFor(Texteditor editor) {
        SettingsInspectorDialog dialog = new SettingsInspectorDialog(editor);
        dialog.setVisible(true);
    }

    private SettingsInspectorDialog(Texteditor editor) {
        super(editor, "Settings Inspector", false);
        this.editor = editor;
        this.categoryModel = new DefaultListModel<>();
        this.categories = new JList<>(categoryModel);
        this.tableModel = new DefaultTableModel(new Object[] {"Category", "Setting", "Current", "Default", "Type"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 2;
            }
        };
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(new JLabel("Changes save immediately to ~/.shed/config.toml", SwingConstants.CENTER), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(900, 540));
        pack();
        setLocationRelativeTo(editor);
        populateCategories();
        refreshTable();
        categories.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshTable();
            }
        });
        tableModel.addTableModelListener(event -> {
            if (!refreshing && event.getType() == TableModelEvent.UPDATE && event.getColumn() == 2) {
                persistRow(event.getFirstRow());
            }
        });
    }

    private JSplitPane content() {
        categories.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane categoryScroll = new JScrollPane(categories);
        categoryScroll.setBorder(BorderFactory.createTitledBorder("Categories"));
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Typed TOML Settings"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, categoryScroll, tableScroll);
        split.setResizeWeight(0.2);
        return split;
    }

    private JPanel actions() {
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refreshTable());
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(refresh);
        panel.add(close);
        return panel;
    }

    private void populateCategories() {
        String selected = categories.getSelectedValue();
        categoryModel.clear();
        categoryModel.addElement("All");
        LinkedHashSet<String> known = new LinkedHashSet<>();
        for (TypedSettings.Descriptor descriptor : editor.configManager.typedSettingDescriptors()) {
            known.add(descriptor.category());
        }
        for (String category : known) {
            categoryModel.addElement(category);
        }
        categories.setSelectedValue(selected == null ? "All" : selected, true);
        if (categories.getSelectedIndex() < 0) {
            categories.setSelectedIndex(0);
        }
    }

    private void refreshTable() {
        String selected = categories.getSelectedValue();
        List<TypedSettings.Descriptor> descriptors = editor.configManager.typedSettingDescriptors();
        refreshing = true;
        try {
            tableModel.setRowCount(0);
            for (TypedSettings.Descriptor descriptor : descriptors) {
                if (selected == null || "All".equals(selected) || selected.equals(descriptor.category())) {
                    tableModel.addRow(new Object[] {
                        descriptor.category(), descriptor.key(), descriptor.currentValue(), descriptor.defaultValue(), descriptor.type()
                    });
                }
            }
        } finally {
            refreshing = false;
        }
    }

    private void persistRow(int row) {
        if (row < 0 || row >= tableModel.getRowCount()) {
            return;
        }
        String key = String.valueOf(tableModel.getValueAt(row, 1));
        String value = String.valueOf(tableModel.getValueAt(row, 2));
        String result = editor.setConfigOptionPersistent(key, value);
        if (result.startsWith("Error")) {
            JOptionPane.showMessageDialog(this, result, "Settings Inspector", JOptionPane.ERROR_MESSAGE);
        }
        refreshTable();
    }
}
