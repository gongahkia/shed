package shed;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;

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

    int draw(JTextArea area, Graphics graphics, int x, int y, int start, int end, TabExpander tabs)
        throws BadLocationException {
        List<SyntaxSpan> lexical = syntax.get(), meanings = semantic.get();
        Segment text = new Segment();
        area.getDocument().getText(start, end - start, text);
        var metrics = area.getFontMetrics(area.getFont());
        int run = text.offset, limit = run + text.count;
        for (int i = run; i <= limit; i++) {
            char character = i < limit ? text.array[i] : '\n';
            if (character != '\t' && character != '\n' && character != '\r') continue;
            if (i > run) {
                int offset = start + run - text.offset;
                AttributedString styled = new AttributedString(new String(text.array, run, i - run));
                styled.addAttribute(TextAttribute.FONT, area.getFont());
                styled.addAttribute(TextAttribute.FOREGROUND, area.getForeground());
                apply(styled, lexical, offset, offset + i - run);
                apply(styled, meanings, offset, offset + i - run);
                // shape each tab-delimited run once; color boundaries must not shift glyphs at HiDPI
                new TextLayout(styled.getIterator(), ((Graphics2D) graphics).getFontRenderContext()).draw((Graphics2D) graphics, x, y);
                x += metrics.charsWidth(text.array, run, i - run);
            }
            if (character == '\t') x = (int) tabs.nextTabStop(x, start + i - text.offset);
            run = i + 1;
        }
        return x;
    }

    private static void apply(AttributedString text, List<SyntaxSpan> spans, int start, int end) {
        for (int index = firstEndingAfter(spans, start); index < spans.size(); index++) {
            SyntaxSpan span = spans.get(index);
            if (span.start >= end) break;
            int from = Math.max(start, span.start), to = Math.min(end, span.end);
            if (to > from) text.addAttribute(TextAttribute.FOREGROUND, span.color, from - start, to - start);
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
