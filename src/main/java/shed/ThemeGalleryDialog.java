package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;

/** Visual selector for the bundled theme palettes. */
final class ThemeGalleryDialog extends JDialog {
    private static final List<Swatch> SWATCHES = List.of(
        new Swatch("Editor", ThemePreview::normal),
        new Swatch("Insert", ThemePreview::insert),
        new Swatch("Command", ThemePreview::command),
        new Swatch("Visual", ThemePreview::visual),
        new Swatch("Replace", ThemePreview::replace),
        new Swatch("Text", ThemePreview::foreground),
        new Swatch("Accent", ThemePreview::accent),
        new Swatch("String", ThemePreview::stringAccent)
    );

    private final Texteditor editor;
    private final DefaultListModel<ThemePreview> themeModel = new DefaultListModel<>();
    private final JList<ThemePreview> themes = new JList<>(themeModel);
    private final ThemeCodePreview codePreview = new ThemeCodePreview();
    private final ThemeSwatchGrid swatchGrid = new ThemeSwatchGrid();
    private final JLabel title = new JLabel();
    private final JLabel status = new JLabel();
    private final JButton applyAndSave = new JButton("Apply and Save");

    static void showFor(Texteditor editor) {
        if (editor == null) return;
        new ThemeGalleryDialog(editor).setVisible(true);
    }

    private ThemeGalleryDialog(Texteditor editor) {
        super(editor, "Theme Gallery", false);
        this.editor = editor;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 1080, 690);
        pack();
        setLocationRelativeTo(editor);
        loadThemes();
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(8, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        panel.add(title, BorderLayout.NORTH);
        panel.add(status, BorderLayout.SOUTH);
        return panel;
    }

    private JSplitPane content() {
        themes.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        themes.setCellRenderer(new ThemeCellRenderer());
        themes.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateSelection();
        });
        themes.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) applyAndSaveSelection();
            }
        });
        themes.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "apply-theme");
        themes.getActionMap().put("apply-theme", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { applyAndSaveSelection(); }
        });
        AccessibilitySupport.describe(themes, "Built-in themes", "Select a theme to preview its colors, then apply and save it.");
        JScrollPane list = new JScrollPane(themes);
        list.setBorder(BorderFactory.createTitledBorder("Built-in themes"));
        list.setPreferredSize(new Dimension(310, 500));

        JPanel preview = new JPanel(new BorderLayout(8, 8));
        preview.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        codePreview.setBorder(BorderFactory.createTitledBorder("Editor preview"));
        swatchGrid.setBorder(BorderFactory.createTitledBorder("Palette"));
        preview.add(codePreview, BorderLayout.CENTER);
        preview.add(swatchGrid, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, preview);
        split.setResizeWeight(0.31);
        split.setDividerLocation(310);
        return split;
    }

    private JPanel actions() {
        applyAndSave.addActionListener(event -> applyAndSaveSelection());
        applyAndSave.setToolTipText("Use this theme now and save it to ~/.shed/config.toml.");
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(applyAndSave);
        panel.add(close);
        return panel;
    }

    private void loadThemes() {
        for (ThemePreview preview : editor.configManager.getThemePreviews()) themeModel.addElement(preview);
        int active = indexOf(editor.configManager.getThemeId());
        themes.setSelectedIndex(active < 0 ? 0 : active);
        themes.requestFocusInWindow();
    }

    private int indexOf(String id) {
        for (int index = 0; index < themeModel.size(); index++) {
            if (themeModel.get(index).id().equals(id)) return index;
        }
        return -1;
    }

    private void updateSelection() {
        ThemePreview preview = themes.getSelectedValue();
        if (preview == null) return;
        boolean active = preview.id().equals(editor.configManager.getThemeId());
        title.setText(preview.displayName() + (active ? " — active" : ""));
        status.setText(preview.id() + "    :theme " + preview.id());
        applyAndSave.setEnabled(true);
        codePreview.setTheme(preview);
        swatchGrid.setTheme(preview);
    }

    private void applyAndSaveSelection() {
        applySelectedTheme();
    }

    private void applySelectedTheme() {
        ThemePreview preview = themes.getSelectedValue();
        if (preview == null) return;
        String result = editor.setThemeFromCommand(preview.id());
        if (!result.startsWith("Theme set to ")) {
            status.setText(result);
            return;
        }
        editor.editorUiController.applyUiTheme(this);
        status.setText(result + "    " + editor.saveConfigToDisk());
        title.setText(preview.displayName() + " — active");
        applyAndSave.setEnabled(true);
        themes.repaint();
        codePreview.repaint();
        swatchGrid.repaint();
    }

    private static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color readable(Color background) {
        double luminance = (0.2126 * background.getRed() + 0.7152 * background.getGreen() + 0.0722 * background.getBlue()) / 255.0;
        return luminance > 0.55 ? Color.BLACK : Color.WHITE;
    }

    private record Swatch(String label, java.util.function.Function<ThemePreview, Color> color) { }

    private final class ThemeCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            if (!(value instanceof ThemePreview preview)) return label;
            String active = preview.id().equals(editor.configManager.getThemeId()) ? "  • active" : "";
            label.setText(preview.displayName() + "  " + preview.id() + active);
            label.setIcon(new PaletteIcon(preview));
            label.setIconTextGap(9);
            label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            return label;
        }
    }

    private static final class PaletteIcon implements javax.swing.Icon {
        private final ThemePreview theme;

        private PaletteIcon(ThemePreview theme) { this.theme = theme; }
        @Override public int getIconWidth() { return 56; }
        @Override public int getIconHeight() { return 17; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            int width = getIconWidth() / SWATCHES.size();
            for (int index = 0; index < SWATCHES.size(); index++) {
                graphics.setColor(SWATCHES.get(index).color().apply(theme));
                graphics.fillRect(x + index * width, y, width + 1, getIconHeight());
            }
            graphics.setColor(component.getForeground());
            graphics.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
        }
    }

    private static final class ThemeCodePreview extends JPanel {
        private ThemePreview theme;

        private ThemeCodePreview() { setPreferredSize(new Dimension(610, 360)); }
        private void setTheme(ThemePreview theme) { this.theme = theme; repaint(); }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (theme == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(theme.normal());
                g.fillRect(0, 0, getWidth(), getHeight());
                int gutter = Math.min(62, Math.max(46, getWidth() / 11));
                g.setColor(theme.command());
                g.fillRect(0, 0, gutter, getHeight());
                Font font = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(13, getFont().getSize()));
                g.setFont(font);
                FontMetrics metrics = g.getFontMetrics();
                int line = metrics.getHeight() + 7;
                drawLine(g, metrics, gutter, line, "1", "public ", theme.accent(), "class ", theme.stringAccent(), "ThemePreview {", theme.foreground());
                drawLine(g, metrics, gutter, line * 2, "2", "    ", theme.foreground(), "String ", theme.stringAccent(), "name = ", theme.foreground(), "\"" + theme.displayName() + "\";", theme.stringAccent());
                g.setColor(theme.visual());
                g.fillRect(gutter, line * 2 + 5, getWidth() - gutter, line + 3);
                drawLine(g, metrics, gutter, line * 3, "3", "    ", theme.foreground(), "int ", theme.stringAccent(), "themes = ", theme.foreground(), "32", theme.accent(), ";", theme.foreground());
                drawLine(g, metrics, gutter, line * 4, "4", "    ", theme.foreground(), "// selected theme preview", blend(theme.foreground(), theme.normal(), 0.48));
                drawLine(g, metrics, gutter, line * 5, "5", "}", theme.foreground());
                g.setColor(theme.insert());
                g.fillRect(gutter, getHeight() - Math.max(30, line), getWidth() - gutter, Math.max(30, line));
                g.setColor(readable(theme.insert()));
                g.drawString("INSERT  Preview palette — apply to use", gutter + 12, getHeight() - 11);
            } finally {
                g.dispose();
            }
        }

        private void drawLine(Graphics2D g, FontMetrics metrics, int gutter, int baseline, String number, Object... segments) {
            g.setColor(blend(theme.foreground(), theme.normal(), 0.45));
            int numberWidth = metrics.stringWidth(number);
            g.drawString(number, gutter - numberWidth - 10, baseline);
            int x = gutter + 12;
            for (int index = 0; index < segments.length; index += 2) {
                String text = (String) segments[index];
                g.setColor((Color) segments[index + 1]);
                g.drawString(text, x, baseline);
                x += metrics.stringWidth(text);
            }
        }

        private static Color blend(Color first, Color second, double ratio) {
            double amount = Math.max(0.0, Math.min(1.0, ratio));
            return new Color((int) Math.round(first.getRed() * (1.0 - amount) + second.getRed() * amount),
                (int) Math.round(first.getGreen() * (1.0 - amount) + second.getGreen() * amount),
                (int) Math.round(first.getBlue() * (1.0 - amount) + second.getBlue() * amount));
        }
    }

    private static final class ThemeSwatchGrid extends JPanel {
        private ThemePreview theme;

        private ThemeSwatchGrid() { setPreferredSize(new Dimension(610, 180)); }
        private void setTheme(ThemePreview theme) { this.theme = theme; repaint(); }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (theme == null) return;
            int columns = 4;
            int rows = 2;
            int width = Math.max(1, getWidth() / columns);
            int height = Math.max(1, getHeight() / rows);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setFont(getFont().deriveFont(Font.BOLD));
                for (int index = 0; index < SWATCHES.size(); index++) {
                    int x = (index % columns) * width;
                    int y = (index / columns) * height;
                    Color color = SWATCHES.get(index).color().apply(theme);
                    Color text = readable(color);
                    g.setColor(color);
                    g.fillRect(x, y, width, height);
                    g.setColor(text);
                    g.drawString(SWATCHES.get(index).label(), x + 10, y + 24);
                    g.setFont(getFont().deriveFont(Font.PLAIN));
                    g.drawString(hex(color), x + 10, y + 46);
                    g.setFont(getFont().deriveFont(Font.BOLD));
                    g.setColor(blend(text, color, 0.50));
                    g.drawRect(x, y, width - 1, height - 1);
                }
            } finally {
                g.dispose();
            }
        }

        private static Color blend(Color first, Color second, double ratio) {
            double amount = Math.max(0.0, Math.min(1.0, ratio));
            return new Color((int) Math.round(first.getRed() * (1.0 - amount) + second.getRed() * amount),
                (int) Math.round(first.getGreen() * (1.0 - amount) + second.getGreen() * amount),
                (int) Math.round(first.getBlue() * (1.0 - amount) + second.getBlue() * amount));
        }
    }
}
