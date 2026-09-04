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
    private static final int COMPACT_WIDTH = 760;
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
        if (width >= COMPACT_WIDTH) {
            int brandWidth = Math.max(280, Math.min(width - 320, (int) Math.round(width * 0.38)));
            brandPane.setBounds(0, 0, brandWidth, height);
            actionPane.setBounds(brandWidth, 0, width - brandWidth, height);
            return;
        }
        int brandHeight = Math.max(0, Math.min(Math.max(0, height - 180), (int) Math.round(height * 0.46)));
        brandPane.setBounds(0, 0, width, brandHeight);
        actionPane.setBounds(0, brandHeight, width, height - brandHeight);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(brandSurface);
            if (getWidth() >= COMPACT_WIDTH) {
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
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        ShedLogoMark logo = new ShedLogoMark();
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(logo);
        content.add(Box.createVerticalStrut(20));

        JLabel title = label("Shed", Math.max(25, editor.configManager.getUiFontSize() + 27), foreground);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(7));

        JLabel version = label("Version " + editor.VERSION, Math.max(14, editor.configManager.getUiFontSize() + 9), mutedForeground);
        version.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(version);
        content.add(Box.createVerticalStrut(8));

        JLabel descriptor = label("Local-first desktop editor", Math.max(12, editor.configManager.getUiFontSize() + 6), mutedForeground);
        descriptor.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(descriptor);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.CENTER;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        pane.add(content, constraints);
        AccessibilitySupport.describe(pane, "Shed product information", "Shed version " + editor.VERSION + ".");
        return pane;
    }

    private JPanel createActionPane() {
        JPanel pane = new JPanel(new GridBagLayout());
        pane.setOpaque(false);
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel heading = label("Start working", Math.max(18, editor.configManager.getUiFontSize() + 15), foreground);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);
        content.add(Box.createVerticalStrut(8));
        JLabel detail = label("Every action below is available by keyboard.", Math.max(12, editor.configManager.getUiFontSize() + 5), mutedForeground);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(detail);
        content.add(Box.createVerticalStrut(24));

        for (WelcomeAction action : actions()) {
            JButton button = createActionButton(action);
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(button);
            content.add(Box.createVerticalStrut(8));
        }

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.CENTER;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        constraints.insets = new Insets(24, 42, 24, 42);
        pane.add(content, constraints);
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
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(blend(surface, foreground, 0.13)),
            BorderFactory.createEmptyBorder(11, 13, 11, 13)
        ));
        button.setMaximumSize(new Dimension(620, 52));
        button.setPreferredSize(new Dimension(470, 52));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.putClientProperty("shed.welcome.action", action.id());
        AccessibilitySupport.describe(button, action.label(), action.label() + ". Shortcut: " + String.join(" plus ", action.keys()) + ".");

        JLabel label = label(action.label(), Math.max(13, editor.configManager.getUiFontSize() + 7), foreground);
        button.add(label, BorderLayout.WEST);
        button.add(keyCaps(action.keys()), BorderLayout.EAST);
        button.addActionListener(event -> action.run().run());
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent event) {
                button.setOpaque(true);
                button.setBackground(blend(surface, accent, 0.12));
            }

            @Override public void mouseExited(java.awt.event.MouseEvent event) {
                button.setOpaque(false);
                button.setBackground(surface);
            }
        });
        return button;
    }

    private JPanel keyCaps(List<String> keys) {
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
}
