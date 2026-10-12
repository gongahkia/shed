package shed;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;

/** A small, theme-aware About window shared by the menu, palette, and commands. */
final class ShedAboutDialog extends JDialog {
    private final Texteditor editor;
    private final BuildInfo buildInfo;

    static void showFor(Texteditor editor) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showFor(editor));
            return;
        }
        for (Window window : editor.getOwnedWindows()) {
            if (window instanceof ShedAboutDialog dialog && dialog.isDisplayable()) {
                dialog.setVisible(true);
                dialog.toFront();
                return;
            }
        }
        new ShedAboutDialog(editor).setVisible(true);
    }

    private ShedAboutDialog(Texteditor editor) {
        super(editor, "About Shed", false);
        this.editor = editor;
        buildInfo = BuildInfo.current();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout());
        add(page(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 560, 420);
        pack();
        setLocationRelativeTo(editor);
    }

    private JPanel page() {
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(BorderFactory.createEmptyBorder(26, 30, 12, 30));
        page.add(identity());
        page.add(Box.createVerticalStrut(22));
        page.add(new JSeparator());
        page.add(Box.createVerticalStrut(18));
        page.add(details());
        page.add(Box.createVerticalGlue());
        return page;
    }

    private JPanel identity() {
        JPanel identity = new JPanel(new BorderLayout(20, 0));
        JLabel icon = logo();
        if (icon != null) identity.add(icon, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Shed");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 13f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(title);
        text.add(Box.createVerticalStrut(3));
        String version = buildInfo.version() == null ? editor.VERSION : buildInfo.version();
        JLabel versionLabel = new JLabel("Version " + version);
        versionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(versionLabel);
        text.add(Box.createVerticalStrut(12));
        JLabel summary = new JLabel("A local desktop text and code editor.");
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(summary);
        identity.add(text, BorderLayout.CENTER);
        AccessibilitySupport.describe(identity, "About Shed", "Shed version " + version + ", a local desktop text and code editor.");
        identity.setAlignmentX(Component.LEFT_ALIGNMENT);
        return identity;
    }

    private JLabel logo() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("assets/logo/shed.png")) {
            if (input == null) return null;
            BufferedImage image = ImageIO.read(input);
            if (image == null) return null;
            int height = 82;
            int width = Math.round(height * image.getWidth() / (float) image.getHeight());
            JLabel label = new JLabel(new ImageIcon(image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH)));
            label.setPreferredSize(new Dimension(width, height));
            AccessibilitySupport.describe(label, "Shed logo", "Shed application logo.");
            return label;
        } catch (IOException ignored) {
            return null;
        }
    }

    private JPanel details() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel heading = new JLabel("BUILD & RUNTIME");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 10, 0);
        panel.add(heading, constraints);

        int row = 1;
        row = addDetail(panel, row, "Commit", buildInfo.commit());
        row = addDetail(panel, row, "Built with Java", buildInfo.buildJava());
        row = addDetail(panel, row, "Running on Java", buildInfo.runtimeJavaDescription());
        addDetail(panel, row, "Operating system", buildInfo.operatingSystemDescription());
        return panel;
    }

    private static int addDetail(JPanel panel, int row, String name, String value) {
        if (value == null) return row;
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.anchor = GridBagConstraints.NORTHWEST;
        label.insets = new Insets(0, 0, 9, 24);
        panel.add(new JLabel(name), label);
        GridBagConstraints content = new GridBagConstraints();
        content.gridx = 1;
        content.gridy = row;
        content.weightx = 1;
        content.fill = GridBagConstraints.HORIZONTAL;
        content.anchor = GridBagConstraints.NORTHWEST;
        content.insets = new Insets(0, 0, 9, 0);
        panel.add(new JLabel(value), content);
        return row + 1;
    }

    private JPanel actions() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(12, 30, 22, 30));
        JButton copy = new JButton("Copy support info");
        copy.setToolTipText("Copy version, build, and local runtime details.");
        copy.addActionListener(event -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(buildInfo.render()), null);
            copy.setText("Copied");
        });
        footer.add(copy, BorderLayout.WEST);
        JPanel right = new JPanel();
        JButton help = new JButton("Help");
        help.addActionListener(event -> editor.showHelp(""));
        right.add(help);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        right.add(close);
        footer.add(right, BorderLayout.EAST);
        getRootPane().setDefaultButton(close);
        return footer;
    }
}
