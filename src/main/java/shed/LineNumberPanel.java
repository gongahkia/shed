package shed;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;

class LineNumberPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JTextArea textArea;
    private LineNumberMode mode;
    private boolean highlightCurrentLine;
    private Color lineNumberColor;
    private Color currentLineNumberColor;

    public LineNumberPanel(JTextArea textArea) {
        this.textArea = textArea;
        setPreferredSize(new Dimension(40, Integer.MAX_VALUE));
        setBackground(Color.decode("#161B22"));
        this.lineNumberColor = Color.decode("#8B949E");
        this.currentLineNumberColor = Color.decode("#FAF9F6");
        this.mode = LineNumberMode.ABSOLUTE;
        this.highlightCurrentLine = true;
    }

    public void setMode(LineNumberMode mode) {
        this.mode = mode == null ? LineNumberMode.ABSOLUTE : mode;
    }

    public void setHighlightCurrentLine(boolean highlightCurrentLine) {
        this.highlightCurrentLine = highlightCurrentLine;
    }

    public void setColors(Color background, Color lineNumberColor, Color currentLineNumberColor) {
        if (background != null) {
            setBackground(background);
        }
        if (lineNumberColor != null) {
            this.lineNumberColor = lineNumberColor;
        }
        if (currentLineNumberColor != null) {
            this.currentLineNumberColor = currentLineNumberColor;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        g.setColor(lineNumberColor);
        g.setFont(textArea.getFont());

        FontMetrics fm = g.getFontMetrics();
        int lineCount = textArea.getLineCount();
        int currentLine = 0;
        try {
            currentLine = textArea.getLineOfOffset(textArea.getCaretPosition());
        } catch (BadLocationException ignored) {
        }

        try {
            Rectangle visible = textArea.getVisibleRect();
            int startLine = textArea.getLineOfOffset(textArea.viewToModel2D(new Point(0, visible.y)));
            int endLine = textArea.getLineOfOffset(textArea.viewToModel2D(new Point(0, visible.y + visible.height)));

            for (int i = startLine; i <= endLine && i < lineCount; i++) {
                int lineStart = textArea.getLineStartOffset(i);
                Point p = textArea.modelToView2D(lineStart).getBounds().getLocation();
                int y = p.y + fm.getAscent();

                String lineNum = formatLineNumber(i, currentLine);
                if (highlightCurrentLine && i == currentLine) {
                    g.setColor(currentLineNumberColor);
                } else {
                    g.setColor(lineNumberColor);
                }
                int x = getWidth() - fm.stringWidth(lineNum) - 5;
                g.drawString(lineNum, x, y);
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private String formatLineNumber(int line, int currentLine) {
        switch (mode) {
            case NONE:
                return "";
            case RELATIVE:
                return line == currentLine ? "0" : String.valueOf(Math.abs(line - currentLine));
            case RELATIVE_ABSOLUTE:
                return line == currentLine ? String.valueOf(line + 1) : String.valueOf(Math.abs(line - currentLine));
            case ABSOLUTE:
            default:
                return String.valueOf(line + 1);
        }
    }
}
