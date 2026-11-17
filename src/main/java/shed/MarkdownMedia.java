package shed;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static shed.MarkdownSemanticSnapshot.*;

/** Immutable, read-only requests. No buffer, Swing component or complete source is retained. */
final class MarkdownMedia {
    private MarkdownMedia() { }
    enum Kind { IMAGE, MERMAID, MATH }
    enum State { READY, PENDING, CANCELLED, SUPERSEDED, UNSUPPORTED, FAILED, RESOURCE_LIMIT, CLOSED }
    enum Theme { DEFAULT, DARK, NEUTRAL }
    static final int MAX_CONTENT = 65_536;
    record Options(Theme theme, int foreground, float mathSize, int maxWidth, int maxHeight) {
        Options {
            Objects.requireNonNull(theme);
            if (!Float.isFinite(mathSize) || mathSize < 8 || mathSize > 64 || maxWidth < 1 || maxHeight < 1
                    || maxWidth > 2048 || maxHeight > 2048) throw new IllegalArgumentException("Media sizing limit");
        }
        static Options preview() { return new Options(Theme.DEFAULT, 0xff000000, 16, 2048, 2048); }
    }
    record Context(Path file, List<Path> roots) {
        Context {
            file = Objects.requireNonNull(file).toAbsolutePath().normalize();
            roots = List.copyOf(roots.stream().map(p -> p.toAbsolutePath().normalize()).toList());
            if (roots.isEmpty() || roots.size() > 16) throw new IllegalArgumentException("Media project scope required (at most 16 roots)");
        }
    }
    record Stamp(DocumentWorkspace.Id document, long revision, WeakReference<VersionedTextSnapshot> snapshot, long generation) {
        static Stamp of(Source source, long generation) {
            return new Stamp(source.documentId(), source.revision(), new WeakReference<>(source.text()), generation);
        }
        boolean matches(Source source, long currentGeneration) {
            return source != null && source.completeSource() && document.equals(source.documentId())
                && revision == source.revision() && snapshot.get() == source.text() && generation == currentGeneration;
        }
        boolean same(Stamp other) {
            var text = snapshot.get();
            return text != null && other != null && document.equals(other.document) && revision == other.revision
                && generation == other.generation && text == other.snapshot.get();
        }
    }
    record Request(Kind kind, Stamp stamp, List<MarkdownSourceRange> ranges, Optional<MarkdownSourceRange> contentRange,
                   String content, boolean display, Options options, Optional<Context> context, String digest) {
        Request {
            Objects.requireNonNull(kind); Objects.requireNonNull(stamp); Objects.requireNonNull(options);
            ranges = List.copyOf(ranges); Objects.requireNonNull(contentRange); Objects.requireNonNull(context);
            if (ranges.isEmpty() || ranges.size() > 64 || content.isBlank() || content.length() > MAX_CONTENT)
                throw new IllegalArgumentException("Media source admission limit");
            if (!digest.equals(hash(content))) throw new IllegalArgumentException("Media content digest mismatch");
            var text = stamp.snapshot.get();
            if (text == null) throw new IllegalArgumentException("Media snapshot released");
            new Location(ranges); ranges.forEach(r -> r.validate(text)); contentRange.ifPresent(r -> r.validate(text));
            if (kind == Kind.IMAGE && context.isEmpty()) throw new IllegalArgumentException("Image resolution context required");
        }
        String key() { return kind + "|" + digest + "|" + display + "|" + options + "|" + (kind == Kind.IMAGE ? context : ""); }
    }
    record Plan(State state, Optional<Request> request, String detail) {
        static Plan unavailable(State state, String detail) { return new Plan(state, Optional.empty(), detail); }
    }
    private static Plan eligible(MarkdownSemanticSnapshot semantic, Source current) {
        if (!semantic.isCurrent(current)) return Plan.unavailable(State.SUPERSEDED, "Stale or foreign semantic revision");
        return switch (semantic.status()) {
            case SUCCEEDED -> null;
            case CANCELLED -> Plan.unavailable(State.CANCELLED, semantic.detail());
            case FAILED -> Plan.unavailable(State.FAILED, semantic.detail());
            case UNSUPPORTED -> Plan.unavailable(State.UNSUPPORTED, semantic.detail());
        };
    }
    static Plan image(MarkdownSemanticSnapshot semantic, Source current, Link image, Options options, Context context, long generation) {
        Plan rejected = eligible(semantic, current); if (rejected != null) return rejected;
        if (!semantic.links().contains(image) || image.kind() != LinkKind.IMAGE || image.location().segments().isEmpty())
            return Plan.unavailable(State.UNSUPPORTED, "Verified image source location required");
        return plan(Kind.IMAGE, current, image.location().segments(), Optional.empty(), image.destination(), false, options, Optional.of(context), generation);
    }
    static Plan mermaid(MarkdownSemanticSnapshot semantic, Source current, CodeBlock fence, Options options, long generation) {
        Plan rejected = eligible(semantic, current); if (rejected != null) return rejected;
        if (!semantic.codeBlocks().contains(fence) || !fence.mermaid() || !fence.closed()
                || fence.openingMarker().isEmpty() || fence.closingMarker().isEmpty())
            return Plan.unavailable(State.UNSUPPORTED, "Closed verified Mermaid fence required");
        var text = current.text(); var opening = fence.openingMarker().orElseThrow(); var closing = fence.closingMarker().orElseThrow();
        int first = text.positionAt(opening.start()).line(), last = text.positionAt(closing.start()).line();
        // Container prefixes need a content-mapping adapter, not a navigation envelope.
        if (last <= first || !text.substring(text.lineStartOffset(first), opening.start() - text.lineStartOffset(first)).isBlank()
                || !text.substring(text.lineStartOffset(last), closing.start() - text.lineStartOffset(last)).isBlank())
            return Plan.unavailable(State.UNSUPPORTED, "Container Mermaid body mapping deferred");
        var body = new MarkdownSourceRange(text.lineStartOffset(first + 1), text.lineStartOffset(last));
        if (body.length() > MAX_CONTENT) return Plan.unavailable(State.RESOURCE_LIMIT, "Mermaid source exceeds 64 Ki UTF-16 units");
        return plan(Kind.MERMAID, current, fence.location().segments(), Optional.of(body), body.text(text), false, options, Optional.empty(), generation);
    }
    /** Narrow adapter: one complete single-line paragraph containing exactly one existing math form.
     * Inline/multiline detection remains unsupported; this is not a second Markdown scanner. */
    static Plan math(MarkdownSemanticSnapshot semantic, Source current, Block paragraph, Options options, long generation) {
        Plan rejected = eligible(semantic, current); if (rejected != null) return rejected;
        if (!semantic.blocks().contains(paragraph) || paragraph.kind() != BlockKind.PARAGRAPH || paragraph.location().segments().size() != 1)
            return Plan.unavailable(State.UNSUPPORTED, "Verified standalone math paragraph required");
        var range = paragraph.location().segments().getFirst();
        if (range.length() > 8192) return Plan.unavailable(State.RESOURCE_LIMIT, "Math source exceeds 8 Ki UTF-16 units");
        String raw = range.text(current.text()); String open, close; boolean display;
        if (raw.startsWith("$$") && raw.endsWith("$$")) { open = close = "$$"; display = true; }
        else if (raw.startsWith("\\[") && raw.endsWith("\\]")) { open = "\\["; close = "\\]"; display = true; }
        else if (raw.startsWith("\\(") && raw.endsWith("\\)")) { open = "\\("; close = "\\)"; display = false; }
        else if (raw.startsWith("$") && raw.endsWith("$")) { open = close = "$"; display = false; }
        else return Plan.unavailable(State.UNSUPPORTED, "Math source range is not verified");
        if (raw.length() <= open.length() + close.length()) return Plan.unavailable(State.UNSUPPORTED, "Empty math");
        var body = new MarkdownSourceRange(range.start() + open.length(), range.end() - close.length()); String formula = body.text(current.text());
        if (formula.indexOf('$') >= 0 || formula.isBlank() || !formula.equals(formula.strip()) || formula.contains("\\)") || formula.contains("\\]"))
            return Plan.unavailable(State.UNSUPPORTED, "Multiple or ambiguous math expressions deferred");
        return plan(Kind.MATH, current, List.of(range), Optional.of(body), formula, display, options, Optional.empty(), generation);
    }
    private static Plan plan(Kind kind, Source source, List<MarkdownSourceRange> ranges, Optional<MarkdownSourceRange> body,
                             String content, boolean display, Options options, Optional<Context> context, long generation) {
        if (ranges.size() > 64 || content.length() > MAX_CONTENT) return Plan.unavailable(State.RESOURCE_LIMIT, "Media source admission limit");
        if (content.isBlank()) return Plan.unavailable(State.UNSUPPORTED, "Empty media source");
        return new Plan(State.PENDING, Optional.of(new Request(kind, Stamp.of(source, generation), ranges, body, content, display, options, context, hash(content))), "Verified media request");
    }
    static String hash(String value) {
        byte[] bytes = new byte[value.length() * 2];
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); bytes[i * 2] = (byte) (c >>> 8); bytes[i * 2 + 1] = (byte) c; }
        return hash(bytes);
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
