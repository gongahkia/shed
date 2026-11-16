package shed;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable interpretation, not an editable document or a cache. No CommonMark/Swing objects escape. */
record MarkdownSemanticSnapshot(
        Source source, Status status, Coverage coverage, MarkdownSourceRange coveredRange,
        List<Block> blocks, List<Heading> headings, List<CodeBlock> codeBlocks,
        List<Inline> inlines, List<Link> links, List<ReferenceDefinition> references,
        List<TaskMarker> tasks, Set<Limitation> limitations, String detail) {

    enum Status { SUCCEEDED, UNSUPPORTED, CANCELLED, FAILED }
    enum Coverage { COMPLETE, NONE }
    enum BlockKind { HEADING, PARAGRAPH, FENCED_CODE, INDENTED_CODE, BULLET_LIST, ORDERED_LIST,
        LIST_ITEM, BLOCK_QUOTE, TABLE, TABLE_HEAD, TABLE_BODY, TABLE_ROW, TABLE_CELL,
        THEMATIC_BREAK, HTML, EXTENSION }
    enum InlineKind { EMPHASIS, STRONG_EMPHASIS, STRIKETHROUGH, CODE, HTML }
    enum LinkKind { LINK, IMAGE }
    enum Limitation {
        MATH_AND_WIKI_LINKS_DEFERRED, UNRESOLVED_REFERENCES_ARE_TEXT,
        LINK_FORM_AND_DESTINATION_RANGES_UNAVAILABLE, EXTENSION_DETAILS_NOT_PROJECTED,
        TABLE_INLINE_LOCATIONS_UNAVAILABLE, MISSING_OR_INVALID_PARSER_SPANS
    }

    /** IDs are workspace-scoped. Exact snapshot identity also prevents cross-workspace/reload reuse. */
    record Source(DocumentWorkspace.Id documentId, long revision, VersionedTextSnapshot text, boolean completeSource) {
        /** For independently captured, complete immutable inputs (tests/tools and future snapshot callers). */
        Source(DocumentWorkspace.Id documentId, long revision, VersionedTextSnapshot text) {
            this(documentId, revision, text, true);
        }
        Source {
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(text);
            if (revision < 0) throw new IllegalArgumentException("Negative document revision");
        }

        /** Capture with the ID resolved by the owning workspace, after its ordinary snapshot update. */
        static Source capture(DocumentWorkspace.Id documentId, FileBuffer buffer) {
            if (!javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Capture source on EDT");
            return new Source(documentId, buffer.documentRevision(), buffer.textSnapshot(), !buffer.isLargeFile());
        }

        boolean matches(Source current) {
            return current != null && documentId.equals(current.documentId)
                && revision == current.revision && text == current.text && completeSource == current.completeSource;
        }
    }

    /** Disjoint parser spans exclude newlines and may exclude quote/list prefixes.
     * The envelope is for navigation, NOT an exact content or replacement range. */
    record Location(List<MarkdownSourceRange> segments) {
        Location {
            segments = List.copyOf(segments);
            int previousEnd = -1;
            for (var range : segments) {
                if (range.start() < previousEnd) throw new IllegalArgumentException("Unordered source spans");
                previousEnd = range.end();
            }
        }
        Optional<MarkdownSourceRange> envelope() {
            return segments.isEmpty() ? Optional.empty() : Optional.of(new MarkdownSourceRange(
                segments.getFirst().start(), segments.getLast().end()));
        }
    }

    record Block(BlockKind kind, Location location) { }
    record Heading(int level, String text, String anchor, Location location,
                   Optional<MarkdownSourceRange> marker) { }
    /** Content stays in the shared source; no retained copy of potentially large code literals. */
    record CodeBlock(String info, String language, boolean mermaid, boolean closed, Location location,
                     Optional<MarkdownSourceRange> openingMarker, Optional<MarkdownSourceRange> closingMarker) { }
    record Inline(InlineKind kind, Location location, Optional<MarkdownSourceRange> openingMarker,
                  Optional<MarkdownSourceRange> closingMarker) { }
    /** Destinations are decoded parser values, UNTRUSTED and unresolved against the filesystem/network. */
    record Link(LinkKind kind, String destination, Optional<String> title, Location location) { }
    record ReferenceDefinition(String label, String destination, Optional<String> title, Location location) { }
    /** CommonMark's task marker has no source spans; checked state is reliable, location explicitly absent. */
    record TaskMarker(boolean checked, Location location) { }

    MarkdownSemanticSnapshot {
        Objects.requireNonNull(source); Objects.requireNonNull(status); Objects.requireNonNull(coverage);
        coveredRange.validate(source.text());
        blocks = List.copyOf(blocks); headings = List.copyOf(headings); codeBlocks = List.copyOf(codeBlocks);
        inlines = List.copyOf(inlines); links = List.copyOf(links); references = List.copyOf(references);
        tasks = List.copyOf(tasks); limitations = Set.copyOf(limitations); Objects.requireNonNull(detail);
        if ((status == Status.SUCCEEDED) != (coverage == Coverage.COMPLETE)
                || !coveredRange.equals(new MarkdownSourceRange(0, coverage == Coverage.COMPLETE ? source.text().length() : 0))) {
            throw new IllegalArgumentException("Inconsistent semantic coverage");
        }
        if (coverage == Coverage.COMPLETE && !source.completeSource()) throw new IllegalArgumentException("Projected source cannot be complete semantics");
        if (coverage == Coverage.NONE && !(blocks.isEmpty() && headings.isEmpty() && codeBlocks.isEmpty()
                && inlines.isEmpty() && links.isEmpty() && references.isEmpty() && tasks.isEmpty())) {
            throw new IllegalArgumentException("Failed/capped analysis cannot publish partial records");
        }
    }

    boolean isCurrent(Source current) { return source.matches(current); }

    void requireCurrent(Source current) {
        if (!isCurrent(current)) throw new IllegalStateException("Stale or foreign Markdown semantics");
    }

    static MarkdownSemanticSnapshot unavailable(Source source, Status status, String reason) {
        return new MarkdownSemanticSnapshot(source, status, Coverage.NONE, new MarkdownSourceRange(0, 0),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(), reason);
    }
}
