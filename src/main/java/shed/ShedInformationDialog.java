package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** Read-only, structured information page for local reports and status views. */
final class ShedInformationDialog extends JDialog {
    private final Texteditor editor;
    private final String pageTitle;
    private final JEditorPane content = new JEditorPane();
    private String source;
    private String appearance;

    static void showFor(Texteditor editor, String title, String report) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showFor(editor, title, report));
            return;
        }
        for (Window window : editor.getOwnedWindows()) {
            if (window instanceof ShedInformationDialog dialog && dialog.isDisplayable() && dialog.pageTitle.equals(title)) {
                dialog.setReport(report);
                dialog.setVisible(true);
                dialog.toFront();
                return;
            }
        }
        new ShedInformationDialog(editor, title, report).setVisible(true);
    }

    private ShedInformationDialog(Texteditor editor, String title, String report) {
        super(editor, title, false);
        this.editor = editor;
        this.pageTitle = title;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout());
        add(header(), BorderLayout.NORTH);
        content.setContentType("text/html");
        content.setEditable(false);
        content.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        AccessibilitySupport.describe(content, title + " details", "Read-only local information for " + title + ".");
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
        add(scroll, BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 760, 570);
        setReport(report);
        pack();
        setLocationRelativeTo(editor);
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowGainedFocus(java.awt.event.WindowEvent event) {
                if (!appearanceKey().equals(appearance)) render();
            }
        });
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 28, 16, 28));
        JLabel heading = new JLabel(pageTitle);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 7f));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(new JLabel("Local information"), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 28, 18, 28));
        JButton copy = new JButton("Copy details");
        copy.addActionListener(event -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(source), null);
            copy.setText("Copied");
        });
        panel.add(copy, BorderLayout.WEST);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        panel.add(close, BorderLayout.EAST);
        getRootPane().setDefaultButton(close);
        return panel;
    }

    private void setReport(String report) {
        source = report == null ? "" : report;
        render();
    }

    private void render() {
        Color foreground = editor.configManager.getEditorForeground();
        Color background = editor.configManager.getNormalColor();
        Color border = editor.configManager.getSubtleBorderColor();
        String family = editor.configManager.getUiFontFamily();
        if (family == null || family.isBlank()) family = Font.SANS_SERIF;
        int size = UiZoom.scale(Math.max(12, editor.configManager.getUiFontSize()), editor.configManager.getUiZoom());
        String html = "<html><head><style type='text/css'>"
            + "body { font-family:'" + escape(family) + "'; font-size:" + size + "pt; color:" + hex(foreground)
            + "; background:" + hex(background) + "; margin:12px 16px 24px 16px; }"
            + "h2 { font-size:" + (size + 2) + "pt; margin-top:20px; margin-bottom:8px; }"
            + "p { margin:7px 0; } td { padding:5px 12px 5px 0; border-bottom:1px solid " + hex(border) + "; }"
            + "pre { font-family:monospace; font-size:" + size + "pt; }"
            + "</style></head><body>" + formatReport(source) + "</body></html>";
        content.setText(html);
        content.setCaretPosition(0);
        appearance = appearanceKey();
    }

    static String formatReport(String report) {
        if (report == null || report.isBlank()) return "<p>No information available.</p>";
        StringBuilder html = new StringBuilder();
        StringBuilder rows = new StringBuilder();
        StringBuilder block = new StringBuilder();
        for (String line : report.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushRows(html, rows);
                flushBlock(html, block);
            } else if (trimmed.matches("[=─-]{3,}")) {
                continue;
            } else if (!Character.isWhitespace(line.charAt(0)) && trimmed.endsWith(":") && trimmed.length() < 70) {
                flushRows(html, rows);
                flushBlock(html, block);
                html.append("<h2>").append(escape(trimmed.substring(0, trimmed.length() - 1))).append("</h2>");
            } else if (!Character.isWhitespace(line.charAt(0)) && trimmed.indexOf(':') > 0
                && trimmed.indexOf(':') < 42 && !trimmed.startsWith("http")) {
                flushBlock(html, block);
                int separator = trimmed.indexOf(':');
                rows.append("<tr><td><b>").append(escape(trimmed.substring(0, separator)))
                    .append("</b></td><td>").append(escape(trimmed.substring(separator + 1).trim())).append("</td></tr>");
            } else {
                flushRows(html, rows);
                if (block.length() > 0) block.append('\n');
                block.append(line);
            }
        }
        flushRows(html, rows);
        flushBlock(html, block);
        return html.toString();
    }

    private static void flushRows(StringBuilder html, StringBuilder rows) {
        if (rows.length() == 0) return;
        html.append("<table width='100%' cellspacing='0'>").append(rows).append("</table>");
        rows.setLength(0);
    }

    private static void flushBlock(StringBuilder html, StringBuilder block) {
        if (block.length() == 0) return;
        html.append("<pre>").append(escape(block.toString())).append("</pre>");
        block.setLength(0);
    }

    private String appearanceKey() {
        return editor.configManager.getThemeId() + ":" + editor.configManager.getUiZoom() + ":"
            + editor.configManager.getUiFontFamily() + ":" + editor.configManager.getUiFontSize();
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
