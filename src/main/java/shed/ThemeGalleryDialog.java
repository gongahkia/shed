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
import java.awt.Window;
import java.io.IOException;
import java.lang.ref.WeakReference;
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
import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.JOptionPane;

/** Existing palette preview plus explicitly requested, data-only Open VSX discovery. */
final class ThemeGalleryDialog extends JDialog {
    private static WeakReference<ThemeGalleryDialog> livePreview = new WeakReference<>(null);
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
    private final JButton previewInApp = new JButton("Preview in App");
    private final OpenVsxThemeService registry;
    private final JTabbedPane sources = new JTabbedPane();
    private final JTextField query = new JTextField(18);
    private final JButton search = new JButton("Search");
    private final DefaultListModel<OpenVsxThemeService.Extension> results = new DefaultListModel<>();
    private final JList<OpenVsxThemeService.Extension> discoveries = new JList<>(results);
    private final JButton download = new JButton("Load Theme Previews");
    private final JButton install = new JButton("Install Package");
    private final JButton remove = new JButton("Remove Package");
    private final JButton license = new JButton("License");
    private final JComboBox<ImportedTheme> variants = new JComboBox<>();
    private final JTextArea details = new JTextArea(4, 32);
    private OpenVsxThemeService.Download loaded;
    private int jobId = -1;
    private int request;
    private boolean closed;
    private ConfigManager.ThemePreviewLease previewLease;

    static void showFor(Texteditor editor) {
        if (editor == null) return;
        for (Window window : editor.getOwnedWindows()) {
            if (window instanceof ThemeGalleryDialog gallery && gallery.isDisplayable()) {
                gallery.setVisible(true);
                gallery.toFront();
                return;
            }
        }
        new ThemeGalleryDialog(editor).setVisible(true);
    }

    static void cancelLivePreview() {
        ThemeGalleryDialog gallery = livePreview.get();
        if (gallery != null) gallery.cancelPreview();
    }

    private ThemeGalleryDialog(Texteditor editor) {
        this(editor, new OpenVsxThemeService());
    }

    ThemeGalleryDialog(Texteditor editor, OpenVsxThemeService registry) {
        super(editor, "Theme Gallery", false);
        this.editor = editor;
        this.registry = registry;
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
        runJob("Loading installed themes…", token -> editor.configManager.importedThemeStore().load(), values -> {
            editor.configManager.replaceImportedThemes(values);
            loadThemes();
        });
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
        AccessibilitySupport.describe(themes, "Installed themes", "Built-in and imported themes. Select to preview, apply or remove an imported package.");
        JScrollPane list = new JScrollPane(themes);
        list.setPreferredSize(new Dimension(310, 500));
        sources.addTab("Installed", list);
        sources.addTab("Discover", discovery());
        sources.addChangeListener(event -> {
            cancelPreview();
            if (sources.getSelectedIndex() == 0) updateSelection();
            else showDiscovery();
        });

        JPanel preview = new JPanel(new BorderLayout(8, 8));
        preview.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        codePreview.setBorder(BorderFactory.createTitledBorder("Editor preview"));
        swatchGrid.setBorder(BorderFactory.createTitledBorder("Palette"));
        variants.setVisible(false);
        variants.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof ImportedTheme theme ? theme.name() : value, index, selected, focus);
            }
        });
        variants.addActionListener(event -> showDownloadedTheme());
        AccessibilitySupport.describe(variants, "Package theme variant", "Choose a downloaded palette to inspect before installing.");
        preview.add(variants, BorderLayout.NORTH);
        preview.add(codePreview, BorderLayout.CENTER);
        JPanel lower = new JPanel(new BorderLayout(8, 8));
        lower.add(swatchGrid, BorderLayout.NORTH);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        AccessibilitySupport.describe(details, "Theme source and compatibility", "Publisher, license, repository and color translation limitations.");
        lower.add(new JScrollPane(details), BorderLayout.CENTER);
        preview.add(lower, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sources, preview);
        split.setResizeWeight(0.31);
        split.setDividerLocation(310);
        return split;
    }

    private JPanel actions() {
        previewInApp.addActionListener(event -> {
            if (previewLease == null) previewSelectedTheme();
            else cancelPreview();
        });
        previewInApp.setToolTipText("Temporarily preview in this workbench without saving. Close or cancel to restore.");
        applyAndSave.addActionListener(event -> applyAndSaveSelection());
        applyAndSave.setToolTipText("Use this theme now and save it to ~/.shed/config.toml.");
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(previewInApp);
        panel.add(applyAndSave);
        install.addActionListener(event -> installPackage());
        remove.addActionListener(event -> removePackage());
        license.addActionListener(event -> showLicense());
        panel.add(install);
        panel.add(remove);
        panel.add(license);
        panel.add(close);
        return panel;
    }

    private void loadThemes() {
        themeModel.clear();
        for (ThemePreview preview : editor.configManager.getThemePreviews()) themeModel.addElement(preview);
        int active = indexOf(editor.configManager.getThemeId());
        themes.setSelectedIndex(active < 0 ? 0 : active);
        themes.ensureIndexIsVisible(themes.getSelectedIndex());
        themes.requestFocusInWindow();
        if (!editor.configManager.importedThemeFailure().isEmpty()) status.setText(editor.configManager.importedThemeFailure());
    }

    private int indexOf(String id) {
        for (int index = 0; index < themeModel.size(); index++) {
            if (themeModel.get(index).id().equals(id)) return index;
        }
        return -1;
    }

    private void updateSelection() {
        if (sources.getSelectedIndex() != 0) return;
        ThemePreview preview = themes.getSelectedValue();
        if (preview == null) return;
        boolean active = preview.id().equals(editor.configManager.getThemeId());
        title.setText(preview.displayName() + (active ? " — active" : ""));
        status.setText(preview.id() + "    :theme " + preview.id());
        applyAndSave.setEnabled(true);
        ImportedTheme imported = editor.configManager.importedTheme(preview.id());
        codePreview.setTheme(preview, imported);
        swatchGrid.setTheme(preview);
        variants.setVisible(false);
        install.setVisible(false);
        remove.setVisible(imported != null);
        remove.setEnabled(jobId < 0 && imported != null && !packageInUse(imported.packageId()));
        remove.setToolTipText("Remove this package after applying and saving a different theme.");
        license.setVisible(imported != null);
        details.setText(imported == null ? "Built into Shed. Available offline." : description(imported));
        details.setCaretPosition(0);
        previewInApp.setEnabled(true);
        if (previewLease != null) previewSelectedTheme();
    }

    void previewSelectedTheme() {
        if (sources.getSelectedIndex() != 0) return;
        ThemePreview selected = themes.getSelectedValue();
        if (selected == null) return;
        // Swing UI defaults are process-wide; independent multi-window previews need separate qualification.
        for (Window window : Window.getWindows()) {
            if (window instanceof Texteditor && window != editor && window.isDisplayable()) {
                status.setText("Workbench preview requires one Shed window; palette preview remains available.");
                return;
            }
        }
        String before = editor.configManager.getThemeId();
        if (previewLease == null) previewLease = editor.configManager.previewTheme(selected.id());
        else previewLease.select(selected.id());
        if (previewLease == null) return;
        livePreview = new WeakReference<>(this);
        previewInApp.setText("Cancel Preview");
        remove.setEnabled(false);
        status.setText("Previewing " + selected.displayName() + " — not saved");
        if (!before.equals(editor.configManager.getThemeId())) editor.applyThemeColors();
        themes.repaint();
    }

    void cancelPreview() {
        if (previewLease == null) return;
        String before = editor.configManager.getThemeId();
        previewLease.close();
        previewLease = null;
        if (livePreview.get() == this) livePreview.clear();
        previewInApp.setText("Preview in App");
        if (editor.isDisplayable() && !before.equals(editor.configManager.getThemeId())) editor.applyThemeColors();
        updateSelection();
    }

    @Override public void dispose() {
        closed = true;
        request++;
        if (jobId >= 0) editor.asyncJobService.cancel(jobId);
        jobId = -1;
        loaded = null;
        variants.removeAllItems();
        cancelPreview();
        super.dispose();
    }

    private JPanel discovery() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel searchBar = new JPanel(new BorderLayout(6, 0));
        searchBar.add(query, BorderLayout.CENTER);
        searchBar.add(search, BorderLayout.EAST);
        query.setToolTipText("Search color themes on Open VSX");
        AccessibilitySupport.describe(query, "Search Open VSX themes", "Enter a theme or publisher name, then press Enter.");
        query.addActionListener(event -> searchRegistry());
        search.addActionListener(event -> searchRegistry());
        panel.add(searchBar, BorderLayout.NORTH);
        discoveries.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        AccessibilitySupport.describe(discoveries, "Open VSX theme packages", "Choose a package and load its theme previews.");
        discoveries.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            loaded = null;
            variants.removeAllItems();
            showDiscovery();
        });
        panel.add(new JScrollPane(discoveries), BorderLayout.CENTER);
        download.addActionListener(event -> downloadPackage());
        panel.add(download, BorderLayout.SOUTH);
        return panel;
    }

    private void searchRegistry() {
        String text = query.getText();
        loaded = null;
        results.clear();
        variants.removeAllItems();
        runJob("Searching Open VSX…", token -> registry.search(text, token), entries -> {
            for (var entry : entries) results.addElement(entry);
            status.setText(entries.isEmpty() ? "No themes found. Try another name." : entries.size() + " packages · select one to load previews");
            showDiscovery();
        });
    }

    private void downloadPackage() {
        OpenVsxThemeService.Extension selected = discoveries.getSelectedValue();
        if (selected == null) return;
        runJob("Loading theme previews…", token -> registry.download(selected, token), value -> {
            if (!selected.equals(discoveries.getSelectedValue())) return;
            loaded = value;
            variants.removeAllItems();
            for (ImportedTheme theme : value.themes()) variants.addItem(theme);
            showDiscovery();
            status.setText(value.themes().size() + " theme variants · downloaded for inspection, not installed");
        });
    }

    private void showDiscovery() {
        if (sources.getSelectedIndex() != 1) return;
        previewInApp.setEnabled(false);
        applyAndSave.setEnabled(false);
        install.setVisible(true);
        install.setEnabled(jobId < 0 && loaded != null);
        install.setText(loaded != null && editor.configManager.importedTheme(loaded.themes().getFirst().id()) != null ? "Update Package" : "Install Package");
        remove.setVisible(false);
        license.setVisible(loaded != null);
        download.setEnabled(jobId < 0 && discoveries.getSelectedValue() != null);
        variants.setVisible(loaded != null);
        OpenVsxThemeService.Extension selected = discoveries.getSelectedValue();
        title.setText(selected == null ? "Discover themes on Open VSX" : selected.displayName());
        details.setText(selected == null ? "Search Open VSX to find community color themes. Network access starts only when you search."
            : selected.id() + " · " + selected.version() + "\n" + selected.description() + "\nLoad previews to inspect the license and adapted colors.");
        details.setCaretPosition(0);
        if (loaded == null) {
            codePreview.setTheme(null);
            swatchGrid.setTheme(null);
        } else showDownloadedTheme();
    }

    private void showDownloadedTheme() {
        ImportedTheme theme = (ImportedTheme) variants.getSelectedItem();
        if (sources.getSelectedIndex() != 1 || theme == null || loaded == null) return;
        codePreview.setTheme(theme.preview(), theme);
        swatchGrid.setTheme(theme.preview());
        details.setText(description(theme));
        details.setCaretPosition(0);
    }

    private static String description(ImportedTheme theme) {
        return theme.packageId() + " · " + theme.version() + " · License: " + theme.license() + "\n"
            + theme.repository() + "\n" + String.join("\n", theme.limitations());
    }

    private boolean packageInUse(String id) {
        if (editor.configManager.usesImportedPackage(id)) return true;
        for (Window window : Window.getWindows()) {
            if (window instanceof Texteditor other && other.isDisplayable() && other.configManager.usesImportedPackage(id)) return true;
        }
        return false;
    }

    private boolean canManage(String id) {
        for (Window window : Window.getWindows()) if (window instanceof Texteditor && window != editor && window.isDisplayable()) {
            status.setText("Install or remove themes with one Shed window open.");
            return false;
        }
        if (packageInUse(id)) {
            status.setText("Apply and save a different theme before updating or removing this package.");
            return false;
        }
        return true;
    }

    private void installPackage() {
        if (loaded == null) return;
        List<ImportedTheme> batch = loaded.themes();
        String id = batch.getFirst().packageId();
        if (!canManage(id)) return;
        ImportedTheme selected = (ImportedTheme) variants.getSelectedItem();
        runJob("Installing theme palettes…", token -> {
            OpenVsxThemeService.checkCancelled(token);
            return editor.configManager.importedThemeStore().install(batch);
        }, values -> {
            editor.configManager.replaceImportedThemes(values);
            sources.setSelectedIndex(0);
            loadThemes();
            if (selected != null) {
                themes.setSelectedIndex(indexOf(selected.id()));
                themes.ensureIndexIsVisible(themes.getSelectedIndex());
            }
            status.setText("Installed " + id + " · preview or apply a theme · available offline");
        });
    }

    private void removePackage() {
        ThemePreview selected = themes.getSelectedValue();
        ImportedTheme imported = selected == null ? null : editor.configManager.importedTheme(selected.id());
        if (imported == null || !canManage(imported.packageId())) return;
        if (JOptionPane.showConfirmDialog(this, "Remove all installed palettes from " + imported.packageId() + "?",
            "Remove Theme Package", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        runJob("Removing theme package…", token -> {
            OpenVsxThemeService.checkCancelled(token);
            return editor.configManager.importedThemeStore().remove(imported.packageId());
        }, values -> {
            editor.configManager.replaceImportedThemes(values);
            loadThemes();
            status.setText("Theme package removed");
        });
    }

    private void showLicense() {
        ImportedTheme theme = sources.getSelectedIndex() == 1 ? (ImportedTheme) variants.getSelectedItem()
            : themes.getSelectedValue() == null ? null : editor.configManager.importedTheme(themes.getSelectedValue().id());
        if (theme == null) return;
        JTextArea text = new JTextArea(theme.license() + "\n\n" + (theme.licenseText().isEmpty()
            ? "No conventional LICENSE file supplied. Review the package repository: " + theme.repository() : theme.licenseText()), 20, 65);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "Theme License", JOptionPane.PLAIN_MESSAGE);
    }

    private <T> void runJob(String message, AsyncJobService.JobTask<T> task, java.util.function.Consumer<T> accept) {
        if (closed || jobId >= 0) return;
        int expected = ++request;
        status.setText(message);
        search.setEnabled(false);
        download.setEnabled(false);
        install.setEnabled(false);
        remove.setEnabled(false);
        query.setEnabled(false);
        discoveries.setEnabled(false);
        themes.setEnabled(false);
        sources.setEnabled(false);
        previewInApp.setEnabled(false);
        applyAndSave.setEnabled(false);
        jobId = editor.asyncJobService.submit("Theme Gallery", task, (job, value, error) -> {
            if (closed || expected != request) return;
            jobId = -1;
            search.setEnabled(true);
            query.setEnabled(true);
            discoveries.setEnabled(true);
            themes.setEnabled(true);
            sources.setEnabled(true);
            if (sources.getSelectedIndex() == 0) updateSelection(); else showDiscovery();
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || value == null) {
                status.setText("Theme operation failed. " + (error instanceof IOException ? error.getMessage() : "Try again or use an installed theme."));
            } else accept.accept(value);
        });
    }

    private void applyAndSaveSelection() {
        applySelectedTheme();
    }

    private void applySelectedTheme() {
        if (sources.getSelectedIndex() != 0 || jobId >= 0) return;
        ThemePreview preview = themes.getSelectedValue();
        if (preview == null) return;
        ConfigManager.ThemePreviewLease lease = previewLease;
        if (lease == null) lease = editor.configManager.previewTheme(preview.id());
        if (lease == null) {
            status.setText("Theme preview is no longer available");
            return;
        }
        try {
            int count = lease.acceptAndPersist();
            previewLease = null;
            if (livePreview.get() == this) livePreview.clear();
            previewInApp.setText("Preview in App");
            editor.applyThemeColors();
            remove.setEnabled(false);
            status.setText("Theme set to " + preview.id() + "    Saved config (" + count + " keys)");
        } catch (IOException error) {
            if (previewLease == null) lease.close();
            status.setText("Error saving config: " + error.getMessage());
            return;
        }
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
            String active = preview.id().equals(editor.configManager.getThemeId()) ? "  · Current" : "";
            label.setText(preview.displayName() + active);
            label.setToolTipText(preview.id());
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
        private ImportedTheme imported;

        private ThemeCodePreview() { setPreferredSize(new Dimension(610, 360)); }
        private void setTheme(ThemePreview theme) { setTheme(theme, null); }
        private void setTheme(ThemePreview theme, ImportedTheme imported) { this.theme = theme; this.imported = imported; repaint(); }
        private Color syntax(String role, Color fallback) {
            return imported != null && imported.colors().containsKey(role) ? imported.color(role) : fallback;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (theme == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                var insets = getInsets();
                int width = Math.max(0, getWidth() - insets.left - insets.right);
                int height = Math.max(0, getHeight() - insets.top - insets.bottom);
                g.translate(insets.left, insets.top);
                g.clipRect(0, 0, width, height);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(theme.normal());
                g.fillRect(0, 0, width, height);
                int gutter = Math.min(WorkbenchStyle.px(62), Math.max(WorkbenchStyle.px(46), width / 11));
                g.setColor(syntax("ui.linenumber.background", theme.command()));
                g.fillRect(0, 0, gutter, height);
                Font font = new Font(Font.MONOSPACED, Font.PLAIN, getFont().getSize());
                g.setFont(font);
                FontMetrics metrics = g.getFontMetrics();
                int line = metrics.getHeight() + WorkbenchStyle.px(7);
                int firstBaseline = WorkbenchStyle.px(8) + metrics.getAscent();
                Color keyword = syntax("ui.syntax.keyword", theme.accent()), string = syntax("ui.syntax.string", theme.stringAccent());
                drawLine(g, metrics, gutter, firstBaseline, "1", "public ", keyword, "class ", imported == null ? theme.stringAccent() : keyword, "ThemePreview {", theme.foreground());
                drawLine(g, metrics, gutter, firstBaseline + line, "2", "    ", theme.foreground(), "String ", syntax("ui.syntax.type", theme.stringAccent()), "name = ", theme.foreground(), "\"" + theme.displayName() + "\";", string);
                g.setColor(syntax("ui.selection", theme.visual()));
                g.fillRect(gutter, firstBaseline + line * 2 - metrics.getAscent(), width - gutter, line);
                drawLine(g, metrics, gutter, firstBaseline + line * 2, "3", "    ", theme.foreground(), "int ", imported == null ? theme.stringAccent() : keyword, "themes = ", theme.foreground(), "32", syntax("ui.syntax.number", theme.accent()), ";", theme.foreground());
                drawLine(g, metrics, gutter, firstBaseline + line * 3, "4", "    ", theme.foreground(), "// selected theme preview", syntax("ui.syntax.comment", blend(theme.foreground(), theme.normal(), 0.48)));
                drawLine(g, metrics, gutter, firstBaseline + line * 4, "5", "}", theme.foreground());
                int footerHeight = Math.max(WorkbenchStyle.px(30), line);
                g.setColor(theme.insert());
                g.fillRect(gutter, height - footerHeight, width - gutter, footerHeight);
                g.setColor(readable(theme.insert()));
                g.drawString("INSERT  Preview palette — apply to use", gutter + WorkbenchStyle.px(12),
                    height - footerHeight + (footerHeight - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally {
                g.dispose();
            }
        }

        private void drawLine(Graphics2D g, FontMetrics metrics, int gutter, int baseline, String number, Object... segments) {
            g.setColor(syntax("ui.linenumber.foreground", blend(theme.foreground(), theme.normal(), 0.45)));
            int numberWidth = metrics.stringWidth(number);
            g.drawString(number, gutter - numberWidth - WorkbenchStyle.px(10), baseline);
            int x = gutter + WorkbenchStyle.px(12);
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

        private void setTheme(ThemePreview theme) { this.theme = theme; repaint(); }

        @Override public Dimension getPreferredSize() {
            var insets = getInsets();
            int textHeight = getFontMetrics(getFont().deriveFont(Font.BOLD)).getHeight()
                + getFontMetrics(getFont().deriveFont(Font.PLAIN)).getHeight();
            int rowsHeight = 2 * (textHeight + WorkbenchStyle.px(26));
            return new Dimension(WorkbenchStyle.px(610), Math.max(WorkbenchStyle.px(180), rowsHeight + insets.top + insets.bottom));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (theme == null) return;
            int columns = 4;
            int rows = 2;
            var insets = getInsets();
            int contentWidth = Math.max(0, getWidth() - insets.left - insets.right);
            int contentHeight = Math.max(0, getHeight() - insets.top - insets.bottom);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(insets.left, insets.top);
                g.clipRect(0, 0, contentWidth, contentHeight);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font headingFont = getFont().deriveFont(Font.BOLD);
                Font valueFont = getFont().deriveFont(Font.PLAIN);
                FontMetrics headingMetrics = g.getFontMetrics(headingFont);
                FontMetrics valueMetrics = g.getFontMetrics(valueFont);
                int padding = WorkbenchStyle.px(10);
                for (int index = 0; index < SWATCHES.size(); index++) {
                    int column = index % columns, row = index / columns;
                    int x = column * contentWidth / columns, y = row * contentHeight / rows;
                    int width = (column + 1) * contentWidth / columns - x;
                    int height = (row + 1) * contentHeight / rows - y;
                    Color color = SWATCHES.get(index).color().apply(theme);
                    Color text = readable(color);
                    g.setColor(color);
                    g.fillRect(x, y, width, height);
                    g.setColor(text);
                    g.setFont(headingFont);
                    g.drawString(SWATCHES.get(index).label(), x + padding, y + padding + headingMetrics.getAscent());
                    g.setFont(valueFont);
                    g.drawString(hex(color), x + padding,
                        y + padding + headingMetrics.getHeight() + WorkbenchStyle.px(6) + valueMetrics.getAscent());
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
