package shed;

import java.awt.Color;
import java.awt.Graphics;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;

/** Foreground runs painted by the source view, before any caret or decoration. EDT-owned. */
final class SourceTextForeground {
    private static final String PROPERTY = "shed.sourceForeground";
    private final Supplier<List<SyntaxSpan>> syntax, semantic;

    private SourceTextForeground(Supplier<List<SyntaxSpan>> syntax, Supplier<List<SyntaxSpan>> semantic) {
        this.syntax = syntax;
        this.semantic = semantic;
    }

    static void install(JTextArea area, Supplier<List<SyntaxSpan>> syntax, Supplier<List<SyntaxSpan>> semantic) {
        area.putClientProperty(PROPERTY, new SourceTextForeground(syntax, semantic));
    }

    static SourceTextForeground forArea(java.awt.Container container) {
        return container instanceof JTextArea area && area.isEnabled()
            && area.getClientProperty(PROPERTY) instanceof SourceTextForeground foreground ? foreground : null;
    }

    @SuppressWarnings("deprecation") // retain PlainView's integer layout and tab stops
    int draw(JTextArea area, Graphics graphics, int x, int y, int start, int end, TabExpander tabs)
        throws BadLocationException {
        List<SyntaxSpan> lexical = syntax.get(), meanings = semantic.get();
        int lex = firstEndingAfter(lexical, start), sem = firstEndingAfter(meanings, start);
        Segment text = new Segment();
        Color previous = graphics.getColor();
        try {
            while (start < end) {
                while (lex < lexical.size() && lexical.get(lex).end <= start) lex++;
                while (sem < meanings.size() && meanings.get(sem).end <= start) sem++;
                int limit = end;
                Color color = area.getForeground();
                if (lex < lexical.size()) {
                    SyntaxSpan span = lexical.get(lex);
                    limit = Math.min(limit, span.start > start ? span.start : span.end);
                    if (span.start <= start) color = span.color;
                }
                if (sem < meanings.size()) {
                    SyntaxSpan span = meanings.get(sem);
                    limit = Math.min(limit, span.start > start ? span.start : span.end);
                    if (span.start <= start) color = span.color;
                }
                // semantic tokens replace lexical colors; neither is drawn over existing glyphs
                graphics.setColor(color);
                area.getDocument().getText(start, limit - start, text);
                x = Utilities.drawTabbedText(text, x, y, graphics, tabs, start);
                start = limit;
            }
            return x;
        } finally {
            graphics.setColor(previous);
        }
    }

    private static int firstEndingAfter(List<SyntaxSpan> spans, int position) {
        int low = 0, high = spans.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (spans.get(middle).end <= position) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
