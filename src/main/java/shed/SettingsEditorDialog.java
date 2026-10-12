package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.text.View;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Two-way typed settings editor; TOML remains canonical. */
final class SettingsEditorDialog extends JDialog {
    private record DialogTheme(Color surface, Color raised, Color field, Color border, Color foreground, Color muted,
                               Color accent, Color selection, Color selectionText) {
        static DialogTheme from(ConfigManager config) {
            Color surface = config.getNormalColor();
            Color foreground = config.getEditorForeground();
            return new DialogTheme(surface, blend(surface, foreground, 0.07), config.getCommandBarBackground(),
                blend(surface, foreground, 0.18), foreground,
                AccessibilitySupport.readableText(blend(foreground, surface, 0.42), surface), config.getCaretColor(),
                config.getSelectionColor(), config.getSelectionTextColor());
        }
    }

    private final Texteditor editor;
    private final JTextField search = new JTextField();
    private final JList<String> categories = new JList<>();
    private final JPanel settings = new SettingsList();
    private JScrollPane settingsScroll;
    private final Timer searchRefresh = new Timer(120, event -> { resetScroll(); refresh(); });
    private int laidOutDetailWidth = -1;
    private DialogTheme theme;
    private boolean refreshing;

    static void showFor(Texteditor editor) { new SettingsEditorDialog(editor).setVisible(true); }

    private SettingsEditorDialog(Texteditor editor) {
        super(editor, "Settings", false);
        this.editor = editor;
        theme = DialogTheme.from(editor.configManager);
        settings.addPropertyChangeListener("font", event -> {
            laidOutDetailWidth = -1;
            SwingUtilities.invokeLater(this::updateDetailWidths);
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 1040, 650);
        pack();
        setLocationRelativeTo(editor);
        searchRefresh.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { searchRefresh.restart(); }
            @Override public void removeUpdate(DocumentEvent event) { searchRefresh.restart(); }
            @Override public void changedUpdate(DocumentEvent event) { searchRefresh.restart(); }
        });
        categories.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) { resetScroll(); refresh(); } });
        populateCategories();
        refresh();
        applyTheme(this);
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(uiPixels(8), uiPixels(8), 0, uiPixels(8)));
        JLabel searchLabel = new JLabel("Search settings");
        searchLabel.setLabelFor(search);
        AccessibilitySupport.describe(search, "Search settings", "Filter settings by name, category, or description.");
        panel.add(searchLabel, BorderLayout.WEST);
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
        left.setBorder(titledBorder("Categories"));
        left.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        left.setPreferredSize(new Dimension(uiPixels(180), uiPixels(400)));
        JScrollPane right = new JScrollPane(settings);
        settingsScroll = right;
        right.setBorder(titledBorder("Settings"));
        right.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        right.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { updateDetailWidths(); }
        });
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.0);
        split.setDividerLocation(uiPixels(180));
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
        int scrollY = settingsScroll == null ? 0 : settingsScroll.getViewport().getViewPosition().y;
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        String focusedKey = focused instanceof JComponent component
            ? (String) component.getClientProperty("shed.setting.key") : null;
        refreshing = true;
        try {
            settings.removeAll();
            laidOutDetailWidth = -1;
            theme = DialogTheme.from(editor.configManager);
            String category = categories.getSelectedValue();
            List<TypedSettings.Descriptor> descriptors = editor.configManager.searchTypedSettings(search.getText());
            int row = 0;
            for (TypedSettings.Descriptor descriptor : descriptors) {
                if (category != null && !"All".equals(category) && !category.equals(descriptor.category())) continue;
                addDescriptor(descriptor, row++);
            }
            if (row == 0) {
                JLabel empty = new JLabel("No settings match this search.");
                GridBagConstraints emptyCell = constraints(0, row++);
                emptyCell.weightx = 1;
                settings.add(empty, emptyCell);
            }
            GridBagConstraints fill = constraints(0, row);
            fill.weighty = 1;
            JPanel filler = new JPanel();
            filler.setOpaque(true);
            filler.setBackground(theme.surface());
            settings.add(filler, fill);
            applyTheme(settings);
            editor.editorUiController.applyUiFont(settings);
            settings.revalidate();
            settings.repaint();
            if (settingsScroll != null) SwingUtilities.invokeLater(() -> {
                updateDetailWidths();
                int max = Math.max(0, settings.getPreferredSize().height - settingsScroll.getViewport().getHeight());
                settingsScroll.getViewport().setViewPosition(new java.awt.Point(0, Math.min(scrollY, max)));
                if (focusedKey != null) {
                    JComponent control = settingControl(settings, focusedKey);
                    if (control != null) control.requestFocusInWindow();
                }
            });
        } finally {
            refreshing = false;
        }
    }

    private void resetScroll() {
        if (settingsScroll != null) settingsScroll.getViewport().setViewPosition(new java.awt.Point(0, 0));
    }

    private static JComponent settingControl(Component parent, String key) {
        if (parent instanceof JComponent component && key.equals(component.getClientProperty("shed.setting.key"))) return component;
        if (parent instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                JComponent found = settingControl(child, key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void updateDetailWidths() {
        if (settingsScroll == null) return;
        int width = settingsScroll.getViewport().getWidth() - uiPixels(28);
        if (width <= 0 || width == laidOutDetailWidth) return;
        laidOutDetailWidth = width;
        for (Component row : settings.getComponents()) {
            if (!(row instanceof JPanel panel)) continue;
            for (Component child : panel.getComponents()) {
                if (!(child instanceof JLabel detail)
                    || !Boolean.TRUE.equals(detail.getClientProperty("shed.setting.description"))) continue;
                detail.setPreferredSize(null);
                View html = (View) detail.getClientProperty(BasicHTML.propertyKey);
                if (html == null) continue;
                html.setSize(width, 0);
                int height = (int) Math.ceil(html.getPreferredSpan(View.Y_AXIS));
                detail.setPreferredSize(new Dimension(width, Math.max(height + 2,
                    detail.getFontMetrics(detail.getFont()).getHeight())));
            }
        }
        settings.revalidate();
        settings.repaint();
    }

    private void addDescriptor(TypedSettings.Descriptor descriptor, int row) {
        JPanel panel = new JPanel(new BorderLayout(0, uiPixels(4)));
        panel.setOpaque(true);
        panel.setBackground(theme.surface());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, editor.configManager.getStatusBarBackground()),
            BorderFactory.createEmptyBorder(uiPixels(7), uiPixels(9), uiPixels(7), uiPixels(9))));
        JPanel top = new JPanel(new BorderLayout(0, uiPixels(3)));
        top.setOpaque(false);
        JLabel name = new JLabel(descriptor.key());
        name.setToolTipText(descriptor.description());
        JComponent input = control(descriptor);
        name.setLabelFor(input);
        AccessibilitySupport.describe(input, descriptor.key(), descriptor.description() + " Current value: " + descriptor.currentValue());
        input.putClientProperty("shed.setting.key", descriptor.key());
        top.add(name, BorderLayout.NORTH);
        top.add(input, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        String detailText = descriptor.description() + "  Default: " + descriptor.defaultValue()
            + "  " + descriptor.allowedValues() + "  " + descriptor.applyBehavior();
        JLabel detail = new JLabel("<html>" + escape(detailText) + "</html>");
        detail.putClientProperty("shed.setting.description", Boolean.TRUE);
        AccessibilitySupport.describe(detail, descriptor.key() + " description", detailText);
        detail.setVerticalAlignment(SwingConstants.TOP);
        detail.setOpaque(false);
        panel.add(detail, BorderLayout.CENTER);
        GridBagConstraints target = constraints(0, row);
        target.weightx = 1;
        target.fill = GridBagConstraints.HORIZONTAL;
        settings.add(panel, target);
    }

    private JComponent control(TypedSettings.Descriptor descriptor) {
        if ("boolean".equals(descriptor.type())) {
            JCheckBox box = new JCheckBox();
            box.setSelected(Boolean.parseBoolean(descriptor.currentValue()));
            box.addActionListener(event -> persist(descriptor.key(), Boolean.toString(box.isSelected())));
            return box;
        }
        String[] choices = choices(descriptor.allowedValues());
        if (choices != null) {
            JComboBox<String> combo = new JComboBox<>(choices);
            combo.setPreferredSize(new Dimension(uiPixels(220), combo.getPreferredSize().height));
            combo.setEditable(false);
            combo.setRenderer((list, value, index, selected, focused) -> {
                JLabel item = new JLabel(value == null ? "" : value);
                item.setOpaque(true);
                item.setBorder(BorderFactory.createEmptyBorder(uiPixels(2), uiPixels(7), uiPixels(2), uiPixels(7)));
                item.setBackground(selected ? theme.selection() : theme.field());
                item.setForeground(selected ? theme.selectionText() : theme.foreground());
                return item;
            });
            combo.setSelectedItem(descriptor.currentValue());
            combo.addActionListener(event -> { if (!refreshing && combo.getSelectedItem() != null) persist(descriptor.key(), String.valueOf(combo.getSelectedItem())); });
            return combo;
        }
        JTextField field = new JTextField(descriptor.currentValue());
        field.setColumns(22);
        final String[] savedValue = { descriptor.currentValue() };
        Runnable save = () -> {
            String value = field.getText();
            if (value.equals(savedValue[0])) return;
            if (persist(descriptor.key(), value)) savedValue[0] = value;
        };
        field.addActionListener(event -> save.run());
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { save.run(); }
        });
        return field;
    }

    private boolean persist(String key, String value) {
        if (refreshing) return false;
        String result = editor.setConfigOptionPersistent(key, value);
        if (result.startsWith("Error")) {
            javax.swing.JOptionPane.showMessageDialog(this, result, "Settings", javax.swing.JOptionPane.ERROR_MESSAGE);
            refresh();
            return false;
        } else if (result.contains("(self-healed ")) {
            javax.swing.JOptionPane.showMessageDialog(this, result, "Settings", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            refresh();
        }
        theme = DialogTheme.from(editor.configManager);
        applyTheme(this);
        return true;
    }

    private static String[] choices(String allowed) {
        if (allowed == null || !allowed.contains(" | ")) return null;
        String[] values = allowed.split(" \\| ");
        for (String value : values) if (value.contains(" ")) return null;
        return values;
    }

    private GridBagConstraints constraints(int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(uiPixels(2), uiPixels(2), uiPixels(2), uiPixels(2));
        return constraints;
    }

    private int uiPixels(int value) {
        return UiZoom.scale(value, editor.configManager.getUiZoom());
    }

    private TitledBorder titledBorder(String title) {
        TitledBorder border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(theme.border()), title);
        border.setTitleColor(theme.foreground());
        return border;
    }

    private void applyTheme(Component component) {
        if (component == null) return;
        if (component instanceof JPanel panel) {
            panel.setOpaque(true);
            panel.setBackground(theme.surface());
            panel.setForeground(theme.foreground());
        }
        if (component instanceof JLabel label) label.setForeground(
            Boolean.TRUE.equals(label.getClientProperty("shed.setting.description")) ? theme.muted() : theme.foreground());
        if (component instanceof JButton button) {
            boolean comboArrow = Boolean.TRUE.equals(button.getClientProperty("shed.setting.combo.arrow"));
            if (comboArrow) button.setUI(new BasicButtonUI());
            button.setOpaque(true);
            button.setContentAreaFilled(true);
            button.setBackground(theme.raised());
            button.setForeground(theme.foreground());
            button.setBorder(comboArrow ? BorderFactory.createLineBorder(theme.border())
                : BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(theme.border()),
                    BorderFactory.createEmptyBorder(uiPixels(4), uiPixels(9), uiPixels(4), uiPixels(9))));
        }
        if (component instanceof JTextField field) {
            field.setOpaque(true);
            field.setBackground(theme.field());
            field.setForeground(theme.foreground());
            field.setCaretColor(theme.accent());
            field.setSelectionColor(theme.selection());
            field.setSelectedTextColor(theme.selectionText());
            field.setBorder(BorderFactory.createLineBorder(theme.border()));
        }
        if (component instanceof JCheckBox box) {
            box.setOpaque(true);
            box.setBackground(theme.surface());
            box.setForeground(theme.foreground());
        }
        if (component instanceof JComboBox<?> combo) {
            combo.setUI(new BasicComboBoxUI() {
                @Override protected JButton createArrowButton() {
                    JButton arrow = new JButton() {
                        @Override protected void paintComponent(java.awt.Graphics graphics) {
                            super.paintComponent(graphics);
                            java.awt.Graphics2D brush = (java.awt.Graphics2D) graphics.create();
                            try {
                                brush.setColor(getForeground());
                                int x = getWidth() / 2;
                                int y = getHeight() / 2;
                                brush.drawLine(x - 4, y - 2, x, y + 2);
                                brush.drawLine(x, y + 2, x + 4, y - 2);
                            } finally {
                                brush.dispose();
                            }
                        }
                    };
                    arrow.putClientProperty("shed.setting.combo.arrow", Boolean.TRUE);
                    arrow.setFocusable(false);
                    arrow.setPreferredSize(new Dimension(uiPixels(24), uiPixels(24)));
                    return arrow;
                }
            });
            combo.setOpaque(true);
            combo.setBackground(theme.field());
            combo.setForeground(theme.foreground());
            combo.setBorder(BorderFactory.createLineBorder(theme.border()));
        }
        if (component instanceof JList<?> list) {
            list.setBackground(theme.raised());
            list.setForeground(theme.foreground());
            list.setSelectionBackground(theme.selection());
            list.setSelectionForeground(theme.selectionText());
        }
        if (component instanceof JScrollPane scroll) {
            scroll.setBackground(theme.surface());
            scroll.getViewport().setBackground(theme.surface());
            scroll.getHorizontalScrollBar().setBackground(theme.raised());
            scroll.getVerticalScrollBar().setBackground(theme.raised());
        }
        if (component instanceof JSplitPane split) split.setBackground(theme.surface());
        if (component instanceof JComponent swing && swing.getBorder() instanceof TitledBorder border) {
            border.setTitleColor(theme.foreground());
        }
        if (component instanceof java.awt.Container container) {
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

    private static String escape(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\n", "<br>");
    }

    private static final class SettingsList extends JPanel implements Scrollable {
        private SettingsList() { super(new GridBagLayout()); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 32; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(32, visible.height - 32);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
