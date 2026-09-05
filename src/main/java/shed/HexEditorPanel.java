package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import shed.api.CustomEditorDocument;

/** Bounded native hex view and single-byte editor for a binary custom document. */
final class HexEditorPanel extends JPanel {
    static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    static final int PAGE_BYTES = 4 * 1024;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final CustomEditorDocument document;
    private final JTextArea view = new JTextArea();
    private final JTextField offset = new JTextField("0x0", 12);
    private final JTextField value = new JTextField(4);
    private final JLabel summary = new JLabel();
    private final JLabel status = new JLabel(" ");
    private byte[] bytes = new byte[0];
    private int pageOffset;

    HexEditorPanel(CustomEditorDocument document) throws IOException {
        super(new BorderLayout(5, 5));
        if (document == null) throw new IOException("hex editor document is required");
        this.document = document;
        setBorder(BorderFactory.createEmptyBorder(6, 7, 7, 7));
        setFocusable(true);
        AccessibilitySupport.describe(this, "Hex editor", "Binary file editor showing hexadecimal bytes and ASCII text.");
        add(toolbar(), BorderLayout.NORTH);
        view.setEditable(false);
        view.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        view.getAccessibleContext().setAccessibleName("Hexadecimal bytes");
        add(new JScrollPane(view), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        document.onDidChange(change -> reloadLater("Saved byte change."));
        document.onDidExternalChange(change -> reloadLater(change.exists() ? "File changed on disk." : "File was removed on disk."));
        document.onDidDispose(() -> view.setEditable(false));
        reload("");
    }

    static boolean supports(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) return false;
            return CustomEditorController.isBinary(Files.readAllBytes(file));
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    static String renderPage(byte[] source, int requestedOffset, int pageBytes) {
        byte[] bytes = source == null ? new byte[0] : source;
        if (bytes.length == 0) return "Empty binary file\n";
        int start = Math.max(0, Math.min(bytes.length - 1, requestedOffset));
        start -= start % 16;
        int end = Math.min(bytes.length, start + Math.max(16, pageBytes));
        StringBuilder rendered = new StringBuilder(Math.max(80, (end - start) * 4));
        for (int base = start; base < end; base += 16) {
            appendHex(rendered, base, 8);
            rendered.append("  ");
            for (int index = 0; index < 16; index++) {
                if (index == 8) rendered.append(' ');
                int position = base + index;
                if (position < end) appendHex(rendered, Byte.toUnsignedInt(bytes[position]), 2);
                else rendered.append("  ");
                rendered.append(' ');
            }
            rendered.append(" | ");
            for (int index = 0; index < 16 && base + index < end; index++) {
                int character = Byte.toUnsignedInt(bytes[base + index]);
                rendered.append(character >= 0x20 && character <= 0x7E ? (char) character : '.');
            }
            rendered.append('\n');
        }
        return rendered.toString();
    }

    static int parseOffset(String input, int length) {
        if (length < 1) throw new IllegalArgumentException("binary file is empty");
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("offset is required");
        int radix = value.regionMatches(true, 0, "0x", 0, 2) ? 16 : 10;
        String digits = radix == 16 ? value.substring(2) : value;
        if (digits.isEmpty() || digits.startsWith("-")) throw new IllegalArgumentException("offset must be a non-negative decimal or 0x hexadecimal number");
        long parsed;
        try {
            parsed = Long.parseLong(digits, radix);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("offset must be a non-negative decimal or 0x hexadecimal number");
        }
        if (parsed < 0 || parsed >= length) throw new IllegalArgumentException("offset must be within the binary file");
        return (int) parsed;
    }

    static int parseByte(String input) {
        String value = input == null ? "" : input.trim();
        if (!value.matches("[0-9A-Fa-f]{2}")) throw new IllegalArgumentException("byte value must be exactly two hexadecimal digits");
        return Integer.parseInt(value, 16);
    }

    static byte[] replaceByte(byte[] source, int offset, int replacement) {
        if (source == null || offset < 0 || offset >= source.length) throw new IllegalArgumentException("offset must be within the binary file");
        if (replacement < 0 || replacement > 0xFF) throw new IllegalArgumentException("byte value must be between 00 and FF");
        byte[] result = source.clone();
        result[offset] = (byte) replacement;
        return result;
    }

    private JPanel toolbar() {
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEADING, 5, 0));
        tools.add(summary);
        tools.add(new JLabel("Go to"));
        tools.add(offset);
        tools.add(button("Go", this::goToOffset));
        tools.add(new JLabel("Set byte"));
        tools.add(value);
        tools.add(button("Write", this::writeByte));
        tools.add(button("Undo", this::undo));
        tools.add(button("Redo", this::redo));
        tools.add(button("Reload", () -> reload("Reloaded.")));
        return tools;
    }

    private void goToOffset() {
        try {
            int selected = parseOffset(offset.getText(), bytes.length);
            pageOffset = selected - selected % 16;
            offset.setText(formatOffset(selected));
            reload("Showing byte " + formatOffset(selected) + ".");
        } catch (IllegalArgumentException error) {
            status(error.getMessage());
        }
    }

    private void writeByte() {
        try {
            int selected = parseOffset(offset.getText(), bytes.length);
            int replacement = parseByte(value.getText());
            if (Byte.toUnsignedInt(bytes[selected]) == replacement) {
                status("Byte is already " + String.format(Locale.ROOT, "%02X", replacement) + ".");
                return;
            }
            document.write(replaceByte(bytes, selected, replacement));
            pageOffset = selected - selected % 16;
            reload("Saved byte " + formatOffset(selected) + ".");
        } catch (IOException | IllegalArgumentException error) {
            status("Hex write failed: " + concise(error));
        }
    }

    private void undo() {
        try {
            if (!document.canUndo()) {
                status("Hex undo is unavailable.");
                return;
            }
            document.undo();
            reload("Undid byte change.");
        } catch (IOException error) {
            status("Hex undo failed: " + concise(error));
        }
    }

    private void redo() {
        try {
            if (!document.canRedo()) {
                status("Hex redo is unavailable.");
                return;
            }
            document.redo();
            reload("Redid byte change.");
        } catch (IOException error) {
            status("Hex redo failed: " + concise(error));
        }
    }

    private void reloadLater(String message) {
        SwingUtilities.invokeLater(() -> reload(message));
    }

    private void reload(String message) {
        try {
            bytes = document.bytes();
            if (bytes.length > MAX_FILE_BYTES) {
                bytes = new byte[0];
                view.setText("Binary file exceeds the 8 MiB hex-editor limit.\n");
                summary.setText("Hex editor unavailable");
                status("File changed beyond the 8 MiB hex-editor limit.");
                return;
            }
            if (bytes.length > 0) pageOffset = Math.max(0, Math.min(pageOffset, bytes.length - 1) / 16 * 16);
            else pageOffset = 0;
            view.setText(renderPage(bytes, pageOffset, PAGE_BYTES));
            view.setCaretPosition(0);
            summary.setText(bytes.length + " bytes · page " + formatOffset(pageOffset));
            if (!message.isBlank()) status(message);
        } catch (IOException error) {
            bytes = new byte[0];
            view.setText("Could not read binary file: " + concise(error) + "\n");
            summary.setText("Hex editor read failed");
            status("Hex read failed: " + concise(error));
        }
    }

    private void status(String message) {
        status.setText(message == null || message.isBlank() ? " " : message);
    }

    private static JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static String formatOffset(int value) {
        return String.format(Locale.ROOT, "0x%08X", Math.max(0, value));
    }

    private static void appendHex(StringBuilder output, int value, int digits) {
        for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4) {
            output.append(HEX[(value >>> shift) & 0x0F]);
        }
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
