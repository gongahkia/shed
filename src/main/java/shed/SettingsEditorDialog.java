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
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicHTML;
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
            return new DialogTheme(surface, config.getRaisedSurfaceColor(), config.getFloatingSurfaceColor(),
                config.getSubtleBorderColor(), foreground, config.getMutedForegroundColor(), config.getFocusColor(),
                config.getSelectionColor(), config.getSelectionTextColor());
        }
    }

    private final Texteditor editor;
    private final SettingsApplication application;
    private final JTextField search = new JTextField();
    private final JComboBox<String> categories = new JComboBox<>();
    private final JCheckBox modifiedOnly = new JCheckBox("Modified only");
    private final javax.swing.JTextArea feedback = new javax.swing.JTextArea("Changes save automatically. Text values apply on Enter or when you leave the field.", 2, 0);
    private final JLabel count = new JLabel();
    private final java.util.Map<String, String> drafts = new java.util.HashMap<>();
    private final java.util.Set<String> invalidKeys = new java.util.HashSet<>();
    private final java.util.Map<String, JButton> resetButtons = new java.util.HashMap<>();
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
        this.application = editor.settingsApplication;
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
        categories.addActionListener(event -> { if (!refreshing) { resetScroll(); refresh(); } });
        modifiedOnly.addActionListener(event -> { resetScroll(); refresh(); });
        populateCategories();
        refresh();
        applyTheme(this);
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(uiPixels(12), uiPixels(12), 0, uiPixels(12)));
        JPanel query = new JPanel(new BorderLayout(8, 0));
        JLabel searchLabel = new JLabel("Search settings"); searchLabel.setLabelFor(search);
        AccessibilitySupport.describe(search, "Search settings", "Filter settings by name, category, or description.");
        query.add(searchLabel, BorderLayout.WEST); query.add(search, BorderLayout.CENTER);
        panel.add(query, BorderLayout.NORTH);
        JPanel filters = new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEADING, 8, 0)));
        JLabel category = new JLabel("Category"); category.setLabelFor(categories);
        categories.setPreferredSize(new Dimension(uiPixels(220), categories.getPreferredSize().height));
        AccessibilitySupport.describe(categories, "Settings categories", "Choose a group of settings, or All to search every group.");
        modifiedOnly.setName("settings.modifiedOnly");
        AccessibilitySupport.describe(modifiedOnly, "Modified settings only", "Show saved values that differ from their defaults.");
        filters.add(category); filters.add(categories); filters.add(modifiedOnly); filters.add(count);
        panel.add(filters, BorderLayout.CENTER); return panel;
    }

    private JPanel content() {
        settingsScroll = new JScrollPane(settings);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder(uiPixels(8), uiPixels(12), uiPixels(8), uiPixels(12)));
        settingsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        settingsScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { updateDetailWidths(); }
        });
        JPanel panel = new JPanel(new BorderLayout()); panel.add(settingsScroll); return panel;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEADING, 6, 4)));
        JPanel actions = panel;
        JButton file = new JButton("Open TOML");
        file.addActionListener(event -> editor.openSettingsBuffer());
        actions.add(file);
        JButton snippets = new JButton("Open Snippets");
        snippets.addActionListener(event -> editor.openSnippetsBuffer());
        actions.add(snippets);
        JButton languageServices = new JButton("Language Services");
        languageServices.addActionListener(event -> editor.showLanguageServices());
        actions.add(languageServices);
        JButton reload = new JButton("Reload TOML");
        reload.addActionListener(event -> { editor.reloadConfigFromDisk(); populateCategories(); refresh(); });
        panel.add(reload);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        panel.add(close);
        feedback.setEditable(false); feedback.setLineWrap(true); feedback.setWrapStyleWord(true);
        feedback.setOpaque(false); feedback.setName("settings.feedback");
        AccessibilitySupport.describe(feedback, "Settings result", "Saved updates and validation errors. Invalid values remain available to correct.");
        JScrollPane message = new JScrollPane(feedback, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        message.setBorder(BorderFactory.createEmptyBorder());
        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.setBorder(BorderFactory.createEmptyBorder(0, uiPixels(12), uiPixels(8), uiPixels(12)));
        footer.add(message, BorderLayout.NORTH); footer.add(panel, BorderLayout.CENTER); return footer;
    }

    private void populateCategories() {
        Object selected = categories.getSelectedItem();
        boolean wasRefreshing = refreshing; refreshing = true;
        try {
            javax.swing.DefaultComboBoxModel<String> model = new javax.swing.DefaultComboBoxModel<>();
            model.addElement("All");
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (var descriptor : application.search("")) values.add(descriptor.category());
            values.stream().sorted().forEach(model::addElement);
            categories.setModel(model); categories.setSelectedItem(selected == null ? "All" : selected);
            if (categories.getSelectedIndex() < 0) categories.setSelectedIndex(0);
        } finally { refreshing = wasRefreshing; }
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
            resetButtons.clear();
            laidOutDetailWidth = -1;
            theme = DialogTheme.from(editor.configManager);
            String category = (String) categories.getSelectedItem();
            List<SettingsApplication.Setting> descriptors = application.search(search.getText());
            int row = 0;
            for (SettingsApplication.Setting descriptor : descriptors) {
                if (category != null && !"All".equals(category) && !category.equals(descriptor.category())) continue;
                if (modifiedOnly.isSelected() && descriptor.currentValue().equals(descriptor.defaultValue())) continue;
                addDescriptor(descriptor, row++);
            }
            count.setText(row + (row == 1 ? " setting" : " settings"));
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

    private void addDescriptor(SettingsApplication.Setting descriptor, int row) {
        JPanel panel = new JPanel(new BorderLayout(0, uiPixels(4)));
        panel.setOpaque(true);
        panel.setBackground(theme.surface());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, editor.configManager.getSubtleBorderColor()),
            BorderFactory.createEmptyBorder(uiPixels(12), uiPixels(12), uiPixels(12), uiPixels(12))));
        JPanel top = new JPanel(new BorderLayout(0, uiPixels(3)));
        top.setOpaque(false);
        JLabel name = new JLabel(settingTitle(descriptor.key()));
        name.setToolTipText(descriptor.description());
        JComponent input = control(descriptor);
        name.setLabelFor(input);
        AccessibilitySupport.describe(input, descriptor.key(), descriptor.description() + " Current value: " + descriptor.currentValue());
        input.putClientProperty("shed.setting.key", descriptor.key());
        if (invalidKeys.contains(descriptor.key())) input.putClientProperty("shed.invalid", true);
        JButton reset = new JButton("Reset"); reset.setName("settings.reset." + descriptor.key());
        reset.setEnabled(!descriptor.currentValue().equals(descriptor.defaultValue()) || drafts.containsKey(descriptor.key()));
        resetButtons.put(descriptor.key(), reset);
        reset.setToolTipText("Reset " + descriptor.key() + " to its default value");
        AccessibilitySupport.describe(reset, "Reset " + descriptor.key(), "Restore this setting to its default value through the normal settings operation.");
        reset.addActionListener(event -> persist(descriptor.key(), descriptor.defaultValue()));
        JPanel controls = new JPanel(new BorderLayout(uiPixels(8), 0)); controls.setOpaque(false);
        controls.add(input, BorderLayout.CENTER); controls.add(reset, BorderLayout.EAST);
        if (input instanceof JCheckBox box) box.setText(settingTitle(descriptor.key()));
        else top.add(name, BorderLayout.NORTH);
        top.add(controls, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        String defaultValue = descriptor.defaultValue();
        String shownDefault = defaultValue.length() > 70 ? defaultValue.substring(0, 67) + "…" : defaultValue;
        String detailText = descriptor.description() + " Default: " + defaultValue + ". " + descriptor.applyBehavior();
        JLabel detail = new JLabel("<html>" + escape(descriptor.key()) + "<br>" + escape(descriptor.description()) + "<br>Default: "
            + escape(shownDefault) + " · " + escape(descriptor.applyBehavior()) + "</html>");
        detail.setToolTipText(detailText);
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

    private JComponent control(SettingsApplication.Setting descriptor) {
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
        JTextField field = new JTextField(drafts.getOrDefault(descriptor.key(), descriptor.currentValue()));
        field.setColumns(22);
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() { drafts.put(descriptor.key(), field.getText()); }
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        });
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

    @Override public void dispose() {
        searchRefresh.stop();
        super.dispose();
    }

    private boolean persist(String key, String value) {
        if (refreshing) return false;
        String result = application.update(key, value);
        feedback.setText(key + ": " + result);
        feedback.getAccessibleContext().setAccessibleDescription(feedback.getText());
        if (result.startsWith("Error")) {
            invalidKeys.add(key);
            WorkbenchStyle.intent(feedback, "error");
            JComponent control = settingControl(settings, key);
            if (control != null) { control.putClientProperty("shed.invalid", true); control.repaint(); }
            if (resetButtons.get(key) != null) resetButtons.get(key).setEnabled(true);
            return false;
        }
        WorkbenchStyle.intent(feedback, result.contains("(self-healed ") ? "warning" : "success");
        drafts.remove(key);
        invalidKeys.remove(key);
        theme = DialogTheme.from(editor.configManager);
        applyTheme(this);
        SwingUtilities.invokeLater(() -> { if (isDisplayable()) refresh(); });
        return true;
    }

    private static String settingTitle(String key) {
        return switch (key) {
            case "theme" -> "Color theme";
            case "font.family" -> "Editor font";
            case "font.size" -> "Editor font size";
            case "ui.font.family" -> "Interface font";
            case "ui.font.size" -> "Interface font size";
            case "ui.zoom" -> "Interface scale";
            case "keymap.profile" -> "Keyboard profile";
            default -> {
                String words = key.replace('.', ' ').replace('_', ' ');
                yield words.isEmpty() ? key : Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
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

    private void applyTheme(Component component) {
        editor.editorUiController.applyUiTheme(component);
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
