package shed;

import javax.swing.JTextArea;

/** Refresh read-only tool output without moving a reader back to the beginning on every update. */
final class ToolOutput {
    private ToolOutput() { }

    static void update(JTextArea area, String value, boolean newSelection) {
        String text = value == null ? "" : value;
        if (text.equals(area.getClientProperty("shed.output.snapshot"))) {
            if (newSelection) area.setCaretPosition(0);
            return;
        }
        int dot = area.getCaret().getDot(), mark = area.getCaret().getMark();
        boolean following = dot == mark && dot > 0 && dot == area.getDocument().getLength();
        area.putClientProperty("shed.output.snapshot", text);
        area.setText(text);
        if (newSelection) area.setCaretPosition(0);
        else if (following) area.setCaretPosition(area.getDocument().getLength());
        else {
            area.getCaret().setDot(Math.min(mark, area.getDocument().getLength()));
            area.getCaret().moveDot(Math.min(dot, area.getDocument().getLength()));
        }
    }
}
