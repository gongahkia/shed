package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

/** Native startup surface for the default, otherwise unconfigured landing page. */
final class ShedWelcomePanel extends JPanel {
    private static final int COMPACT_WIDTH = 960;
    private static final int DESIGN_WIDTH = 960;
    private static final int DESIGN_HEIGHT = 640;
    private static final double MINIMUM_SCALE = 1.10;
    private static final double MAXIMUM_SCALE = 1.65;
    private static final int ACTION_CONTENT_HEIGHT = 420;
    private static final BufferedImage LOGO = loadLogo();

    private record WelcomeAction(String id, String label, List<String> keys, Runnable run) { }

    private final Texteditor editor;
    private final JPanel brandPane;
    private final JPanel actionPane;
    private final Color surface;
    private final Color brandSurface;
    private final Color foreground;
    private final Color mutedForeground;
    private final Color accent;
    private final Color keyBackground;
    private JPanel brandContent;
    private JPanel actionContent;
    private GridBagConstraints actionContentConstraints;
    private ShedLogoMark logo;
    private JLabel title;
    private JLabel version;
    private JLabel descriptor;
    private JLabel heading;
    private JLabel detail;
    private final List<ScaledSpacer> scaledSpacers = new java.util.ArrayList<>();
    private final List<WelcomeButton> actionButtons = new java.util.ArrayList<>();
    private double appliedScale = -1.0;

    ShedWelcomePanel(Texteditor editor) {
        this.editor = editor;
        surface = editor.configManager.getNormalColor();
        brandSurface = shade(surface, 0.18);
        foreground = editor.configManager.getEditorForeground();
        mutedForeground = blend(foreground, surface, 0.44);
        accent = editor.configManager.getCaretColor();
        keyBackground = blend(editor.configManager.getCommandBarBackground(), Color.BLACK, 0.20);

        setLayout(null);
        setOpaque(true);
        setBackground(surface);
        setFocusable(true);
        setMinimumSize(new Dimension(320, 240));
        AccessibilitySupport.describe(this, "Shed welcome", "Shed startup screen with actions to open files, search, and access help.");

        brandPane = createBrandPane();
        actionPane = createActionPane();
        add(brandPane);
        add(actionPane);
        installKeyboardActions();
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        int height = getHeight();
        boolean wide = usesWideLayout(width, height);
        if (wide) {
            int brandWidth = Math.max(280, Math.min(width - 320, (int) Math.round(width * 0.38)));
            brandPane.setBounds(0, 0, brandWidth, height);
            actionPane.setBounds(brandWidth, 0, width - brandWidth, height);
        } else {
            int minimumActionHeight = scaled(ACTION_CONTENT_HEIGHT, contentScale(width, height));
            int brandHeight = Math.max(0, Math.min(Math.max(0, height - minimumActionHeight), (int) Math.round(height * 0.46)));
            brandPane.setBounds(0, 0, width, brandHeight);
            actionPane.setBounds(0, brandHeight, width, height - brandHeight);
        }
        applyResponsiveMetrics(contentScale(width, height));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(brandSurface);
            if (usesWideLayout(getWidth(), getHeight())) {
                int brandWidth = brandPane.getWidth();
                g.fillRect(0, 0, brandWidth, getHeight());
                g.setColor(blend(brandSurface, surface, 0.64));
                g.fillRect(brandWidth, 0, 1, getHeight());
            } else {
                int brandHeight = brandPane.getHeight();
                g.fillRect(0, 0, getWidth(), brandHeight);
                g.setColor(blend(brandSurface, surface, 0.64));
                g.fillRect(0, brandHeight, getWidth(), 1);
            }
        } finally {
            g.dispose();
        }
    }

    private JPanel createBrandPane() {
        JPanel pane = new JPanel(new GridBagLayout());
        pane.setOpaque(false);
        brandContent = new JPanel();
        brandContent.setOpaque(false);
        brandContent.setLayout(new BoxLayout(brandContent, BoxLayout.Y_AXIS));

        logo = new ShedLogoMark();
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        brandContent.add(logo);
        brandContent.add(verticalSpacer(20));

        title = label("Shed", Math.max(25, editor.configManager.getUiFontSize() + 27), foreground);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        brandContent.add(title);
        brandContent.add(verticalSpacer(7));

        version = label("Version " + editor.VERSION, Math.max(14, editor.configManager.getUiFontSize() + 9), mutedForeground);
        version.setAlignmentX(Component.CENTER_ALIGNMENT);
        brandContent.add(version);
        brandContent.add(verticalSpacer(8));

        descriptor = label("Local-first desktop editor", Math.max(12, editor.configManager.getUiFontSize() + 6), mutedForeground);
        descriptor.setAlignmentX(Component.CENTER_ALIGNMENT);
        brandContent.add(descriptor);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.CENTER;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        pane.add(brandContent, constraints);
        AccessibilitySupport.describe(pane, "Shed product information", "Shed version " + editor.VERSION + ".");
        return pane;
    }

    private JPanel createActionPane() {
        JPanel pane = new JPanel(new GridBagLayout());
        pane.setOpaque(false);
        actionContent = new JPanel();
        actionContent.setOpaque(false);
        actionContent.setLayout(new BoxLayout(actionContent, BoxLayout.Y_AXIS));

        heading = label("Start working", Math.max(18, editor.configManager.getUiFontSize() + 15), foreground);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionContent.add(heading);
        actionContent.add(verticalSpacer(8));
        detail = label("Every action below is available by keyboard.", Math.max(12, editor.configManager.getUiFontSize() + 5), mutedForeground);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionContent.add(detail);
        actionContent.add(verticalSpacer(24));

        for (WelcomeAction action : actions()) {
            JButton button = createActionButton(action);
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            actionContent.add(button);
            actionContent.add(verticalSpacer(8));
        }

        actionContentConstraints = new GridBagConstraints();
        actionContentConstraints.anchor = GridBagConstraints.CENTER;
        actionContentConstraints.fill = GridBagConstraints.HORIZONTAL;
        actionContentConstraints.weightx = 1.0;
        actionContentConstraints.weighty = 1.0;
        actionContentConstraints.insets = new Insets(24, 42, 24, 42);
        pane.add(actionContent, actionContentConstraints);
        return pane;
    }

    private List<WelcomeAction> actions() {
        return List.of(
            new WelcomeAction("palette", "Show Command Palette", List.of("Ctrl/Cmd", "Shift", "P"),
                () -> showResult(editor.showCommandPalette())),
            new WelcomeAction("open-file", "Open File", List.of("Ctrl/Cmd", "O"), editor::openFileChooser),
            new WelcomeAction("find-file", "Find File", List.of("Ctrl/Cmd", "P"),
                () -> showResult(editor.showFileFinder())),
            new WelcomeAction("buffers", "Switch Between Open Files", List.of("Ctrl/Cmd", "B"),
                () -> showResult(editor.showBufferFinder())),
            new WelcomeAction("help", "Open Help", List.of("F1"), () -> {
                editor.showHelp("");
                editor.showMessage("Showing help");
            })
        );
    }

    private JButton createActionButton(WelcomeAction action) {
        JButton button = new JButton();
        button.setLayout(new BorderLayout(18, 0));
        button.setText(null);
        button.setFocusPainted(true);
        button.setContentAreaFilled(true);
        button.setOpaque(true);
        button.setBackground(surface);
        button.setBorder(actionButtonBorder(1.0));
        button.setMaximumSize(new Dimension(620, 52));
        button.setPreferredSize(new Dimension(470, 52));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.putClientProperty("shed.welcome.action", action.id());
        AccessibilitySupport.describe(button, action.label(), action.label() + ". Shortcut: " + String.join(" plus ", action.keys()) + ".");

        JLabel label = label(action.label(), Math.max(13, editor.configManager.getUiFontSize() + 7), foreground);
        button.add(label, BorderLayout.WEST);
        List<JLabel> keyLabels = new java.util.ArrayList<>();
        button.add(keyCaps(action.keys(), keyLabels), BorderLayout.EAST);
        actionButtons.add(new WelcomeButton(button, label, keyLabels));
        button.addActionListener(event -> action.run().run());
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent event) {
                button.setOpaque(true);
                button.setBackground(blend(surface, accent, 0.12));
            }

            @Override public void mouseExited(java.awt.event.MouseEvent event) {
                button.setBackground(surface);
            }
        });
        return button;
    }

    private JPanel keyCaps(List<String> keys, List<JLabel> labels) {
        JPanel panel = new JPanel(new GridLayout(1, keys.size(), 4, 0));
        panel.setOpaque(false);
        for (String key : keys) {
            JLabel label = label(key, Math.max(11, editor.configManager.getUiFontSize() + 4), blend(foreground, accent, 0.35));
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setOpaque(true);
            label.setBackground(keyBackground);
            label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(blend(keyBackground, accent, 0.25)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
            ));
            panel.add(label);
            labels.add(label);
        }
        return panel;
    }

    private void installKeyboardActions() {
        bindShortcut("palette.ctrl", KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, () -> showResult(editor.showCommandPalette()));
        bindShortcut("palette.meta", KeyEvent.VK_P, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, () -> showResult(editor.showCommandPalette()));
        bindShortcut("open.ctrl", KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK, editor::openFileChooser);
        bindShortcut("open.meta", KeyEvent.VK_O, InputEvent.META_DOWN_MASK, editor::openFileChooser);
        bindShortcut("find.ctrl", KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK, () -> showResult(editor.showFileFinder()));
        bindShortcut("find.meta", KeyEvent.VK_P, InputEvent.META_DOWN_MASK, () -> showResult(editor.showFileFinder()));
        bindShortcut("buffers.ctrl", KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK, () -> showResult(editor.showBufferFinder()));
        bindShortcut("buffers.meta", KeyEvent.VK_B, InputEvent.META_DOWN_MASK, () -> showResult(editor.showBufferFinder()));
        bindShortcut("help", KeyEvent.VK_F1, 0, () -> {
            editor.showHelp("");
            editor.showMessage("Showing help");
        });
    }

    private void bindShortcut(String id, int keyCode, int modifiers, Runnable action) {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, modifiers), id);
        getActionMap().put(id, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private void showResult(String result) {
        if (result != null && !result.isBlank()) {
            editor.showMessage(result);
        }
    }

    private JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(editor.editorUiController.resolveUiFont().deriveFont(Font.PLAIN, size));
        return label;
    }

    private Component verticalSpacer(int pixels) {
        Box.Filler spacer = new Box.Filler(new Dimension(0, pixels), new Dimension(0, pixels), new Dimension(Short.MAX_VALUE, pixels));
        scaledSpacers.add(new ScaledSpacer(spacer, pixels));
        return spacer;
    }

    private void applyResponsiveMetrics(double scale) {
        if (Math.abs(scale - appliedScale) < 0.01) {
            return;
        }
        appliedScale = scale;
        logo.setScale(scale);
        setLabelFont(title, Math.max(25, editor.configManager.getUiFontSize() + 27), scale);
        setLabelFont(version, Math.max(14, editor.configManager.getUiFontSize() + 9), scale);
        setLabelFont(descriptor, Math.max(12, editor.configManager.getUiFontSize() + 6), scale);
        setLabelFont(heading, Math.max(18, editor.configManager.getUiFontSize() + 15), scale);
        setLabelFont(detail, Math.max(12, editor.configManager.getUiFontSize() + 5), scale);
        for (ScaledSpacer spacer : scaledSpacers) {
            int height = scaled(spacer.baseHeight(), scale);
            spacer.component().changeShape(new Dimension(0, height), new Dimension(0, height), new Dimension(Short.MAX_VALUE, height));
        }
        for (WelcomeButton action : actionButtons) {
            action.button().setBorder(actionButtonBorder(scale));
            action.button().setMinimumSize(new Dimension(scaled(280, scale), scaled(44, scale)));
            action.button().setPreferredSize(new Dimension(scaled(470, scale), scaled(52, scale)));
            action.button().setMaximumSize(new Dimension(scaled(620, scale), scaled(52, scale)));
            setLabelFont(action.label(), Math.max(13, editor.configManager.getUiFontSize() + 7), scale);
            for (JLabel keyLabel : action.keyLabels()) {
                setLabelFont(keyLabel, Math.max(11, editor.configManager.getUiFontSize() + 4), scale);
                keyLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(blend(keyBackground, accent, 0.25)),
                    BorderFactory.createEmptyBorder(scaled(3, scale), scaled(6, scale), scaled(3, scale), scaled(6, scale))
                ));
            }
        }
        int verticalPadding = scaled(24, scale);
        int horizontalPadding = scaled(42, scale);
        actionContentConstraints.insets = new Insets(verticalPadding, horizontalPadding, verticalPadding, horizontalPadding);
        brandContent.revalidate();
        actionContent.revalidate();
    }

    private javax.swing.border.Border actionButtonBorder(double scale) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(blend(surface, foreground, 0.13)),
            BorderFactory.createEmptyBorder(scaled(11, scale), scaled(13, scale), scaled(11, scale), scaled(13, scale))
        );
    }

    private void setLabelFont(JLabel label, int baseSize, double scale) {
        label.setFont(editor.editorUiController.resolveUiFont().deriveFont(Font.PLAIN, (float) scaled(baseSize, scale)));
    }

    static boolean usesWideLayout(int width, int height) {
        return width >= COMPACT_WIDTH || height < DESIGN_HEIGHT;
    }

    static double contentScale(int width, int height) {
        double scale = Math.min(Math.max(0, width) / (double) DESIGN_WIDTH, Math.max(0, height) / (double) DESIGN_HEIGHT);
        return Math.max(MINIMUM_SCALE, Math.min(MAXIMUM_SCALE, scale));
    }

    private static int scaled(int pixels, double scale) {
        return Math.max(1, (int) Math.round(pixels * scale));
    }

    private static BufferedImage loadLogo() {
        try (InputStream stream = ShedWelcomePanel.class.getClassLoader().getResourceAsStream("assets/logo/shed.png")) {
            return stream == null ? null : ImageIO.read(stream);
        } catch (IOException error) {
            return null;
        }
    }

    private static Color blend(Color first, Color second, double secondRatio) {
        double ratio = Math.max(0.0, Math.min(1.0, secondRatio));
        return new Color(
            (int) Math.round(first.getRed() * (1.0 - ratio) + second.getRed() * ratio),
            (int) Math.round(first.getGreen() * (1.0 - ratio) + second.getGreen() * ratio),
            (int) Math.round(first.getBlue() * (1.0 - ratio) + second.getBlue() * ratio)
        );
    }

    private static Color shade(Color color, double amount) {
        return blend(color, Color.BLACK, Math.max(0.0, Math.min(1.0, amount)));
    }

    private final class ShedLogoMark extends JComponent {
        ShedLogoMark() {
            setPreferredSize(new Dimension(230, 230));
            setMinimumSize(new Dimension(120, 120));
            AccessibilitySupport.describe(this, "Shed logo", "The Shed application logo.");
        }

        void setScale(double scale) {
            int preferred = scaled(230, scale);
            int minimum = scaled(120, scale);
            setPreferredSize(new Dimension(preferred, preferred));
            setMinimumSize(new Dimension(minimum, minimum));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int side = Math.max(1, Math.min(getWidth(), getHeight()) - 4);
                int x = (getWidth() - side) / 2;
                int y = (getHeight() - side) / 2;
                g.setColor(shade(brandSurface, 0.31));
                g.fillOval(x, y, side, side);
                g.setColor(blend(brandSurface, accent, 0.12));
                g.drawOval(x, y, side - 1, side - 1);
                if (LOGO == null) {
                    g.setFont(getFont().deriveFont(Font.BOLD, Math.max(28.0f, side * 0.28f)));
                    g.setColor(foreground);
                    String fallback = "S";
                    int baseline = y + (side - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent();
                    g.drawString(fallback, x + (side - g.getFontMetrics().stringWidth(fallback)) / 2, baseline);
                    return;
                }
                double scale = Math.min((side * 0.70) / LOGO.getWidth(), (side * 0.70) / LOGO.getHeight());
                int imageWidth = Math.max(1, (int) Math.round(LOGO.getWidth() * scale));
                int imageHeight = Math.max(1, (int) Math.round(LOGO.getHeight() * scale));
                g.drawImage(LOGO, x + (side - imageWidth) / 2, y + (side - imageHeight) / 2, imageWidth, imageHeight, null);
            } finally {
                g.dispose();
            }
        }
    }

    private record ScaledSpacer(Box.Filler component, int baseHeight) { }
    private record WelcomeButton(JButton button, JLabel label, List<JLabel> keyLabels) { }
}
