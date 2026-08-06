package shed;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
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
    private Set<Integer> addedLines = new HashSet<>();
    private Set<Integer> modifiedLines = new HashSet<>();
    private Set<Integer> deletedAfterLines = new HashSet<>();
    private Map<Integer, Integer> diagnosticSeverityByLine = new HashMap<>(); // line -> min severity (1=error, 2=warn, 3=info, 4=hint)
    private Map<Integer, Integer> coverageHitsByLine = new HashMap<>();
    private Set<Integer> gitAddedLines = new HashSet<>();
    private Set<Integer> gitModifiedLines = new HashSet<>();
    private Set<Integer> gitDeletedAfterLines = new HashSet<>();
    private Map<Integer, BreakpointStore.State> breakpointStateByLine = new HashMap<>();
    private IntConsumer breakpointToggleListener = ignored -> { };

    public LineNumberPanel(JTextArea textArea) {
        this.textArea = textArea;
        setPreferredSize(new Dimension(50, Integer.MAX_VALUE));
        setBackground(Color.decode("#161B22"));
        this.lineNumberColor = Color.decode("#8B949E");
        this.currentLineNumberColor = Color.decode("#FAF9F6");
        this.mode = LineNumberMode.ABSOLUTE;
        this.highlightCurrentLine = true;
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) {
                if (event.getX() > 12) return;
                try {
                    int offset = textArea.viewToModel2D(new Point(0, event.getY()));
                    breakpointToggleListener.accept(textArea.getLineOfOffset(offset));
                } catch (BadLocationException ignored) {
                }
            }
        });
    }
    public void updatePreferredWidth() {
        FontMetrics fm = getFontMetrics(getFont() != null ? getFont() : textArea.getFont());
        int digits = Math.max(3, String.valueOf(textArea.getLineCount()).length());
        int charW = fm.charWidth('0');
        int newWidth = 12 + (digits * charW) + 12; // margins + gutter markers
        if (getPreferredSize().width != newWidth) {
            setPreferredSize(new Dimension(newWidth, Integer.MAX_VALUE));
            revalidate();
        }
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
        updatePreferredWidth();
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

                // Diff gutter marker
                int markerX = 1;
                int markerW = 3;
                int lineH = fm.getHeight();
                if (addedLines.contains(i)) {
                    g.setColor(new Color(0x3FB950));
                    g.fillRect(markerX, p.y, markerW, lineH);
                } else if (modifiedLines.contains(i)) {
                    g.setColor(new Color(0x58A6FF));
                    g.fillRect(markerX, p.y, markerW, lineH);
                } else if (deletedAfterLines.contains(i)) {
                    g.setColor(new Color(0xF85149));
                    g.fillRect(markerX, p.y + lineH - 2, markerW + 2, 2);
                }
                // git gutter (right of diff gutter)
                int gitX = markerX + markerW + 1;
                if (gitAddedLines.contains(i)) {
                    g.setColor(new Color(0x3FB950));
                    g.fillRect(gitX, p.y, 2, lineH);
                } else if (gitModifiedLines.contains(i)) {
                    g.setColor(new Color(0x58A6FF));
                    g.fillRect(gitX, p.y, 2, lineH);
                } else if (gitDeletedAfterLines.contains(i)) {
                    g.setColor(new Color(0xF85149));
                    g.fillRect(gitX, p.y + lineH - 2, 4, 2);
                }
                Integer coverageHits = coverageHitsByLine.get(i);
                if (coverageHits != null) {
                    g.setColor(coverageHits > 0 ? new Color(0x3F, 0xB9, 0x50) : new Color(0xF8, 0x51, 0x49));
                    g.fillRect(markerX + markerW, p.y, 1, lineH);
                }
                BreakpointStore.State breakpointState = breakpointStateByLine.get(i);
                if (breakpointState != null) {
                    int dotSize = Math.max(7, lineH / 2);
                    int dotY = p.y + (lineH - dotSize) / 2;
                    Color breakpointColor = switch (breakpointState) {
                        case REJECTED -> new Color(0xF8, 0x51, 0x49);
                        case CHANGED -> new Color(0xFF, 0xCC, 0x00);
                        default -> new Color(0xFF, 0x55, 0x55);
                    };
                    g.setColor(breakpointColor);
                    if (breakpointState == BreakpointStore.State.REJECTED) g.drawOval(8, dotY, dotSize - 1, dotSize - 1);
                    else g.fillOval(8, dotY, dotSize, dotSize);
                }
                // diagnostic severity icon
                Integer severity = diagnosticSeverityByLine.get(i);
                if (severity != null) {
                    Color dc;
                    switch (severity) {
                        case 1: dc = new Color(0xFF, 0x44, 0x44); break;
                        case 2: dc = new Color(0xFF, 0xCC, 0x00); break;
                        case 3: dc = new Color(0x55, 0x99, 0xFF); break;
                        default: dc = new Color(0x99, 0x99, 0x99); break;
                    }
                    g.setColor(dc);
                    int dotSize = Math.max(4, lineH / 3);
                    int dotY = p.y + (lineH - dotSize) / 2;
                    g.fillOval(getWidth() - dotSize - 2, dotY, dotSize, dotSize);
                }
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    public void updateDiffMarkers(String savedContent, String currentContent) {
        updateDiffMarkers(diffMarkers(savedContent, currentContent));
    }

    static DiffMarkers diffMarkers(String savedContent, String currentContent) {
        Set<Integer> added = new HashSet<>();
        Set<Integer> modified = new HashSet<>();
        Set<Integer> deletedAfter = new HashSet<>();
        if (savedContent == null || currentContent == null) return new DiffMarkers(added, modified, deletedAfter);
        String[] savedLines = savedContent.split("\\n", -1);
        String[] currentLines = currentContent.split("\\n", -1);
        int maxLen = Math.max(savedLines.length, currentLines.length);
        for (int i = 0; i < maxLen; i++) {
            if (i >= savedLines.length) {
                added.add(i);
            } else if (i >= currentLines.length) {
                if (currentLines.length > 0) deletedAfter.add(currentLines.length - 1);
            } else if (!savedLines[i].equals(currentLines[i])) {
                modified.add(i);
            }
        }
        return new DiffMarkers(added, modified, deletedAfter);
    }

    public void updateDiffMarkers(DiffMarkers markers) {
        addedLines.clear();
        modifiedLines.clear();
        deletedAfterLines.clear();
        if (markers != null) {
            addedLines.addAll(markers.added());
            modifiedLines.addAll(markers.modified());
            deletedAfterLines.addAll(markers.deletedAfter());
        }
        repaint();
    }

    record DiffMarkers(Set<Integer> added, Set<Integer> modified, Set<Integer> deletedAfter) {
        DiffMarkers {
            added = Set.copyOf(added == null ? Set.of() : added);
            modified = Set.copyOf(modified == null ? Set.of() : modified);
            deletedAfter = Set.copyOf(deletedAfter == null ? Set.of() : deletedAfter);
        }
    }

    public void updateDiagnosticMarkers(Map<Integer, Integer> severityByLine) {
        diagnosticSeverityByLine.clear();
        if (severityByLine != null) diagnosticSeverityByLine.putAll(severityByLine);
        repaint();
    }

    public void updateCoverageMarkers(Map<Integer, Integer> hitsByLine) {
        coverageHitsByLine.clear();
        if (hitsByLine != null) coverageHitsByLine.putAll(hitsByLine);
        repaint();
    }

    public void updateGitDiffMarkers(Set<Integer> added, Set<Integer> modified, Set<Integer> deletedAfter) {
        gitAddedLines.clear();
        gitModifiedLines.clear();
        gitDeletedAfterLines.clear();
        if (added != null) gitAddedLines.addAll(added);
        if (modified != null) gitModifiedLines.addAll(modified);
        if (deletedAfter != null) gitDeletedAfterLines.addAll(deletedAfter);
        repaint();
    }

    public void updateBreakpointMarkers(Map<Integer, BreakpointStore.State> states) {
        breakpointStateByLine.clear();
        if (states != null) breakpointStateByLine.putAll(states);
        repaint();
    }

    public void setBreakpointToggleListener(IntConsumer listener) {
        breakpointToggleListener = listener == null ? ignored -> { } : listener;
    }

    public void repaintLines(int... lines) {
        if (lines == null || lines.length == 0) {
            return;
        }
        int lineHeight = Math.max(1, getFontMetrics(textArea.getFont()).getHeight());
        for (int line : lines) {
            if (line < 0 || line >= textArea.getLineCount()) {
                continue;
            }
            try {
                int y = textArea.modelToView2D(textArea.getLineStartOffset(line)).getBounds().y;
                repaint(0, y, getWidth(), lineHeight);
            } catch (BadLocationException ignored) {
            }
        }
    }

    public void repaintForCaretChange(int previousLine, int currentLine) {
        if (mode == LineNumberMode.RELATIVE || mode == LineNumberMode.RELATIVE_ABSOLUTE) {
            repaint();
            return;
        }
        repaintLines(previousLine, currentLine);
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
