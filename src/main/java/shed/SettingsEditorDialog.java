package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Two-way typed settings editor; TOML remains canonical. */
final class SettingsEditorDialog extends JDialog {
    private final Texteditor editor;
    private final JTextField search = new JTextField();
    private final JList<String> categories = new JList<>();
    private final JPanel settings = new JPanel(new GridBagLayout());
    private boolean refreshing;

    static void showFor(Texteditor editor) { new SettingsEditorDialog(editor).setVisible(true); }

    private SettingsEditorDialog(Texteditor editor) {
        super(editor, "Settings", false);
        this.editor = editor;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(1040, 650));
        pack();
        setLocationRelativeTo(editor);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(DocumentEvent event) { refresh(); }
        });
        categories.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) refresh(); });
        populateCategories();
        refresh();
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(new JLabel("Search settings"), BorderLayout.WEST);
        panel.add(search, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton file = new JButton("Open TOML");
        file.addActionListener(event -> editor.openSettingsBuffer());
        actions.add(file);
        JButton snippets = new JButton("Open Snippets");
        snippets.addActionListener(event -> editor.openSnippetsBuffer());
        actions.add(snippets);
        JButton languageServices = new JButton("Language Services");
        languageServices.addActionListener(event -> editor.showLanguageServices());
        actions.add(languageServices);
        panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private JPanel content() {
        categories.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        AccessibilitySupport.describe(categories, "Settings categories", "Select a category of typed Shed settings.");
        JScrollPane left = new JScrollPane(categories);
        left.setBorder(BorderFactory.createTitledBorder("Categories"));
        JScrollPane right = new JScrollPane(settings);
        right.setBorder(BorderFactory.createTitledBorder("Settings"));
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.19);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JLabel note = new JLabel("Changes save immediately to ~/.shed/config.toml");
        note.setHorizontalAlignment(SwingConstants.LEFT);
        panel.add(note);
        JButton reload = new JButton("Reload TOML");
        reload.addActionListener(event -> { editor.reloadConfigFromDisk(); populateCategories(); refresh(); });
        panel.add(reload);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        panel.add(close);
        return panel;
    }

    private void populateCategories() {
        String selected = categories.getSelectedValue();
        javax.swing.DefaultListModel<String> model = new javax.swing.DefaultListModel<>();
        model.addElement("All");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (TypedSettings.Descriptor descriptor : editor.configManager.typedSettingDescriptors()) values.add(descriptor.category());
        for (String value : values) model.addElement(value);
        categories.setModel(model);
        categories.setSelectedValue(selected == null ? "All" : selected, true);
        if (categories.getSelectedIndex() < 0) categories.setSelectedIndex(0);
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        try {
            settings.removeAll();
            String category = categories.getSelectedValue();
            List<TypedSettings.Descriptor> descriptors = editor.configManager.searchTypedSettings(search.getText());
            int row = 0;
            for (TypedSettings.Descriptor descriptor : descriptors) {
                if (category != null && !"All".equals(category) && !category.equals(descriptor.category())) continue;
                addDescriptor(descriptor, row++);
            }
            GridBagConstraints fill = constraints(0, row);
            fill.weighty = 1;
            settings.add(new JPanel(), fill);
            settings.revalidate();
            settings.repaint();
        } finally {
            refreshing = false;
        }
    }

    private void addDescriptor(TypedSettings.Descriptor descriptor, int row) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, editor.configManager.getStatusBarBackground()),
            BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        GridBagConstraints left = constraints(0, 0);
        left.weightx = 0.56;
        JLabel name = new JLabel(descriptor.key());
        name.setToolTipText(descriptor.description());
        panel.add(name, left);
        GridBagConstraints right = constraints(1, 0);
        right.weightx = 0.44;
        panel.add(control(descriptor), right);
        GridBagConstraints detail = constraints(0, 1);
        detail.gridwidth = 2;
        detail.weightx = 1;
        JLabel text = new JLabel("<html><small>" + escape(descriptor.description()) + " &nbsp; Default: " + escape(descriptor.defaultValue())
            + " &nbsp; " + escape(descriptor.allowedValues()) + " &nbsp; " + escape(descriptor.applyBehavior()) + "</small></html>");
        panel.add(text, detail);
        GridBagConstraints target = constraints(0, row);
        target.gridwidth = 2;
        target.weightx = 1;
        target.fill = GridBagConstraints.HORIZONTAL;
        settings.add(panel, target);
    }

    private java.awt.Component control(TypedSettings.Descriptor descriptor) {
        if ("boolean".equals(descriptor.type())) {
            JCheckBox box = new JCheckBox();
            box.setSelected(Boolean.parseBoolean(descriptor.currentValue()));
            box.addActionListener(event -> persist(descriptor.key(), Boolean.toString(box.isSelected())));
            return box;
        }
        String[] choices = choices(descriptor.allowedValues());
        if (choices != null) {
            JComboBox<String> combo = new JComboBox<>(choices);
            combo.setEditable(false);
            combo.setSelectedItem(descriptor.currentValue());
            combo.addActionListener(event -> { if (!refreshing && combo.getSelectedItem() != null) persist(descriptor.key(), String.valueOf(combo.getSelectedItem())); });
            return combo;
        }
        JTextField field = new JTextField(descriptor.currentValue());
        field.addActionListener(event -> persist(descriptor.key(), field.getText()));
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { persist(descriptor.key(), field.getText()); }
        });
        return field;
    }

    private void persist(String key, String value) {
        if (refreshing) return;
        String result = editor.setConfigOptionPersistent(key, value);
        if (result.startsWith("Error")) {
            javax.swing.JOptionPane.showMessageDialog(this, result, "Settings", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
        refresh();
    }

    private static String[] choices(String allowed) {
        if (allowed == null || !allowed.contains(" | ")) return null;
        String[] values = allowed.split(" \\| ");
        for (String value : values) if (value.contains(" ")) return null;
        return values;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 2, 2, 2);
        return constraints;
    }

    private static String escape(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
