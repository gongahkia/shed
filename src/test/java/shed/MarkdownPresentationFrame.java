package shed;

import java.awt.*;
import java.awt.font.*;
import java.lang.ref.WeakReference;
import java.text.AttributedString;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import static shed.MarkdownSemanticSnapshot.*;

/** 4A experiment only: immutable geometry, never a document or production renderer. */
final class MarkdownPresentationFrame {
    static final int MAX_ROWS = 32768, MAX_SPANS = 32768, MAX_LINE = 4096;
    enum Kind { HEADING, STRONG, EMPHASIS, CODE, LINK, MARKER }
    enum State { READY, UNSUPPORTED, CANCELLED, FAILED }
    record Style(MarkdownSourceRange range, Kind kind, int level) { }
    record Row(int start, int end, int line, float y, float height, float ascent, float width, MarkdownSourceRange object) { }
    record Settings(Font font, Color foreground, Color background, Color link, Color code, int width, boolean wrap,
                    boolean placeholder, FontRenderContext frc) {
        Settings {
            Objects.requireNonNull(font); Objects.requireNonNull(foreground); Objects.requireNonNull(background);
            Objects.requireNonNull(link); Objects.requireNonNull(code); Objects.requireNonNull(frc);
            if (width < 32 || width > 16384 || font.getSize2D() < 8 || font.getSize2D() > 64) throw new IllegalArgumentException("Prototype geometry admission");
        }
    }
    record Stamp(DocumentWorkspace.Id id, long revision, WeakReference<VersionedTextSnapshot> text, long generation) {
        boolean matches(Source source, long currentGeneration) {
            return source != null && source.completeSource() && id.equals(source.documentId()) && revision == source.revision()
                && text.get() == source.text() && generation == currentGeneration;
        }
    }
    final State state;
    final String detail;
    final Stamp stamp;
    final Settings settings;
    final List<Style> styles;
    final List<Row> rows, textRows;
    private final int[] prefixEnd;
    final float width, height;
    private MarkdownPresentationFrame(State state, String detail, Source source, long generation, Settings settings,
                                      List<Style> styles, List<Row> rows, float width, float height) {
        this.state = state; this.detail = detail; this.settings = settings;
        stamp = new Stamp(source.documentId(), source.revision(), new WeakReference<>(source.text()), generation);
        this.styles = List.copyOf(styles); this.rows = List.copyOf(rows);
        textRows = rows.stream().filter(row -> row.object() == null).toList(); this.width = width; this.height = height;
        prefixEnd = new int[styles.size()]; int end = 0;
        for (int i = 0; i < styles.size(); i++) prefixEnd[i] = end = Math.max(end, styles.get(i).range().end());
    }
    private static MarkdownPresentationFrame unavailable(State state, String detail, Source source, long generation, Settings settings) {
        return new MarkdownPresentationFrame(state, detail, source, generation, settings, List.of(), List.of(), 0, 0);
    }
    static MarkdownPresentationFrame prepare(MarkdownSemanticSnapshot semantic, Settings settings, long generation, BooleanSupplier stop) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Prepare presentation off EDT");
        Source source = semantic.source();
        if (semantic.status() != Status.SUCCEEDED) return unavailable(semantic.status() == Status.CANCELLED ? State.CANCELLED : semantic.status() == Status.FAILED ? State.FAILED : State.UNSUPPORTED, semantic.detail(), source, generation, settings);
        try {
            List<Style> styles = new ArrayList<>();
            for (var heading : semantic.headings()) {
                for (var range : heading.location().segments()) styles.add(new Style(range, Kind.HEADING, heading.level()));
                heading.marker().ifPresent(range -> styles.add(new Style(range, Kind.MARKER, 0)));
            }
            for (var inline : semantic.inlines()) {
                Kind kind = switch (inline.kind()) { case STRONG_EMPHASIS -> Kind.STRONG; case EMPHASIS -> Kind.EMPHASIS; case CODE -> Kind.CODE; default -> null; };
                if (kind == null) continue;
                for (var range : inline.location().segments()) styles.add(new Style(range, kind, 0));
                inline.openingMarker().ifPresent(range -> styles.add(new Style(range, Kind.MARKER, 0)));
                inline.closingMarker().ifPresent(range -> styles.add(new Style(range, Kind.MARKER, 0)));
            }
            for (var link : semantic.links()) if (link.kind() == LinkKind.LINK)
                for (var range : link.location().segments()) styles.add(new Style(range, Kind.LINK, 0));
            for (var fence : semantic.codeBlocks()) {
                for (var range : fence.location().segments()) styles.add(new Style(range, Kind.CODE, 0));
                fence.openingMarker().ifPresent(range -> styles.add(new Style(range, Kind.MARKER, 0)));
                fence.closingMarker().ifPresent(range -> styles.add(new Style(range, Kind.MARKER, 0)));
            }
            if (styles.size() > MAX_SPANS) throw new UnsupportedOperationException("Decoration budget exceeded");
            styles.sort(Comparator.comparingInt((Style style) -> style.range().start()).thenComparingInt(style -> style.kind().ordinal()));
            styles.forEach(style -> style.range().validate(source.text()));
            var index = new MarkdownPresentationFrame(State.READY, "Verified geometry", source, generation, settings, styles, List.of(), 0, 0);
            MarkdownSourceRange object = settings.placeholder() ? semantic.links().stream().filter(link -> link.kind() == LinkKind.IMAGE && link.location().segments().size() == 1)
                .map(link -> link.location().segments().getFirst()).findFirst().orElse(null) : null;
            List<Row> rows = new ArrayList<>(); float y = 0, maximum = 0;
            for (int line = 0; line < source.text().lineCount(); line++) {
                if (stop.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                int start = source.text().lineStartOffset(line), end = source.text().lineEndOffset(line);
                if (end - start > MAX_LINE) throw new UnsupportedOperationException("Long-line geometry deferred");
                String raw = source.text().substring(start, end - start);
                if (raw.indexOf('\t') >= 0 || raw.indexOf('\r') >= 0 || java.text.Bidi.requiresBidi(raw.toCharArray(), 0, raw.length()))
                    throw new UnsupportedOperationException("Tabs, raw CR and bidi use stock source geometry");
                AttributedString attributed = index.attributed(raw.isEmpty() ? " " : raw, start, false);
                var iterator = attributed.getIterator();
                var measurer = new LineBreakMeasurer(iterator, settings.frc());
                do {
                    int from = measurer.getPosition();
                    TextLayout layout = settings.wrap() ? measurer.nextLayout(settings.width()) : new TextLayout(iterator, settings.frc());
                    int to = settings.wrap() ? measurer.getPosition() : raw.length();
                    if (raw.isEmpty()) to = 0;
                    new MarkdownSourceRange(start + from, start + to).validate(source.text());
                    float height = (float) Math.ceil(layout.getAscent() + layout.getDescent() + layout.getLeading() + 3);
                    float width = raw.isEmpty() ? 0 : layout.getAdvance();
                    rows.add(new Row(start + from, start + to, line, y, height, layout.getAscent() + 1, width, null));
                    y += height; maximum = Math.max(maximum, width);
                    if (!settings.wrap() || raw.isEmpty()) break;
                } while (measurer.getPosition() < raw.length());
                if (object != null && object.start() >= start && object.end() <= end) {
                    rows.add(new Row(end, end, line, y, 44, 0, Math.min(240, settings.width()), object)); y += 44;
                }
                if (rows.size() > MAX_ROWS) throw new UnsupportedOperationException("Visual-row budget exceeded");
            }
            return new MarkdownPresentationFrame(State.READY, "Verified geometry; editing immediately reveals source", source, generation, settings, styles, rows, maximum + 2, y);
        } catch (java.util.concurrent.CancellationException cancelled) { return unavailable(State.CANCELLED, "Presentation cancelled", source, generation, settings); }
        catch (UnsupportedOperationException | IllegalArgumentException unsupported) { return unavailable(State.UNSUPPORTED, unsupported.getMessage(), source, generation, settings); }
    }
    List<Style> overlapping(int start, int end) {
        int lo = 0, hi = styles.size();
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (prefixEnd[mid] <= start) lo = mid + 1; else hi = mid; }
        List<Style> result = new ArrayList<>();
        for (int i = lo; i < styles.size() && styles.get(i).range().start() < end; i++) if (styles.get(i).range().end() > start) result.add(styles.get(i));
        result.sort(Comparator.comparingInt(style -> style.kind().ordinal())); return result;
    }
    AttributedString attributed(String text, int start, boolean active) {
        var value = new AttributedString(text);
        value.addAttribute(TextAttribute.FAMILY, settings.font().getFamily()); value.addAttribute(TextAttribute.SIZE, settings.font().getSize2D());
        value.addAttribute(TextAttribute.WEIGHT, settings.font().isBold() ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR);
        value.addAttribute(TextAttribute.POSTURE, settings.font().isItalic() ? TextAttribute.POSTURE_OBLIQUE : TextAttribute.POSTURE_REGULAR);
        value.addAttribute(TextAttribute.FOREGROUND, settings.foreground());
        for (var style : overlapping(start, start + text.length())) {
            int from = Math.max(start, style.range().start()) - start, to = Math.min(start + text.length(), style.range().end()) - start;
            if (from >= to) continue;
            switch (style.kind()) {
                case HEADING -> { value.addAttribute(TextAttribute.SIZE, settings.font().getSize2D() * (style.level() == 1 ? 1.6f : style.level() == 2 ? 1.35f : 1.15f), from, to); value.addAttribute(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD, from, to); }
                case STRONG -> value.addAttribute(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD, from, to);
                case EMPHASIS -> value.addAttribute(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE, from, to);
                case CODE -> { value.addAttribute(TextAttribute.FAMILY, Font.MONOSPACED, from, to); value.addAttribute(TextAttribute.BACKGROUND, settings.code(), from, to); }
                case LINK -> { value.addAttribute(TextAttribute.FOREGROUND, settings.link(), from, to); value.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, from, to); }
                case MARKER -> { if (!active) value.addAttribute(TextAttribute.FOREGROUND, blend(settings.foreground(), settings.background()), from, to); }
            }
        }
        return value;
    }
    private static Color blend(Color a, Color b) { return new Color((a.getRed() + b.getRed()) / 2, (a.getGreen() + b.getGreen()) / 2, (a.getBlue() + b.getBlue()) / 2); }
    int rowAtY(float y) {
        int lo = 0, hi = rows.size();
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (rows.get(mid).y() + rows.get(mid).height() <= y) lo = mid + 1; else hi = mid; }
        return Math.min(Math.max(0, lo), rows.size() - 1);
    }
    Row rowAtOffset(int offset, boolean backward) {
        int lo = 0, hi = textRows.size();
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (textRows.get(mid).start() <= offset) lo = mid + 1; else hi = mid; }
        int index = Math.max(0, lo - 1);
        if (backward && index > 0 && textRows.get(index).start() == offset && textRows.get(index - 1).end() == offset) index--;
        return textRows.get(index);
    }
    long metadataPayloadBytes() { return rows.size() * 40L + styles.size() * 16L + prefixEnd.length * 4L; }
}
