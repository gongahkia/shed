package shed;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.alerts.AlertsExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.heading.anchor.IdGenerator;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import static shed.MarkdownSemanticSnapshot.*;
import shed.MarkdownSemanticSnapshot.Block;

/** On-demand, caller-thread adapter. Call from an existing worker, never an editor-event callback.
 * No workers, caches, renderer initialization or registrations are created by this foundation. */
final class MarkdownSemanticParser {
    // Deliberate initial full-parse envelope, NOT an editor/file size restriction.
    static final int MAX_CHARACTERS = 1024 * 1024;
    static final int MAX_LINE_CHARACTERS = 64 * 1024;
    static final int MAX_LINES = 50_000;
    static final int MAX_NODES = 100_000;
    static final int MAX_SPANS = 200_000;

    MarkdownSemanticSnapshot parse(Source source) { return parse(source, () -> false); }

    MarkdownSemanticSnapshot parse(Source source, BooleanSupplier cancelled) {
        Objects.requireNonNull(source); Objects.requireNonNull(cancelled);
        try {
            checkCancelled(cancelled);
            if (!source.completeSource()) return unavailable(source, Status.UNSUPPORTED, "Projected source is not complete document coverage");
            String unsupported = preflight(source.text(), cancelled);
            if (unsupported != null) return unavailable(source, Status.UNSUPPORTED, unsupported);
            // Construct only on demand; per-call extension state is never shared between workers.
            Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .maxOpenBlockParsers(128).extensions(List.of(AutolinkExtension.create(), FootnotesExtension.create(),
                    AlertsExtension.create(), StrikethroughExtension.create(), TablesExtension.create(),
                    TaskListItemsExtension.create())).build();
            Node root;
            try (Reader reader = new SnapshotReader(source.text(), cancelled)) {
                root = parser.parseReader(reader);
            }
            return new Projection(source, cancelled).project(root);
        } catch (CancellationException cancelledWork) {
            return unavailable(source, Status.CANCELLED, "Semantic request cancelled; no partial data published");
        } catch (LimitExceeded limit) {
            return unavailable(source, Status.UNSUPPORTED, limit.getMessage());
        } catch (IOException | RuntimeException error) {
            // Do not retain ASTs/throwables/source copies in failure results.
            return unavailable(source, Status.FAILED, "CommonMark analysis failed: " + error.getClass().getSimpleName());
        }
    }

    private static String preflight(VersionedTextSnapshot text, BooleanSupplier cancelled) {
        if (text.length() > MAX_CHARACTERS) return "Full semantics limited to " + MAX_CHARACTERS + " UTF-16 units";
        if (text.length() > 0 && text.charAt(0) == '\ufeff') return "Decode BOM metadata before semantic parsing";
        int column = 0, lines = 1;
        boolean previousCr = false;
        for (int start = 0; start < text.length(); start += 8192) {
            checkCancelled(cancelled);
            String chunk = text.substring(start, Math.min(8192, text.length() - start));
            for (int index = 0; index < chunk.length(); index++) {
                char c = chunk.charAt(index);
                if (c == '\n' || c == '\r') {
                    column = 0;
                    if (!(c == '\n' && previousCr) && ++lines > MAX_LINES) return "Semantic line-count limit exceeded";
                } else if (++column > MAX_LINE_CHARACTERS) return "Semantic line-length limit exceeded";
                previousCr = c == '\r';
            }
        }
        return null;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
    }

    private static final class SnapshotReader extends Reader {
        private final VersionedTextSnapshot source;
        private final BooleanSupplier cancelled;
        private int position;
        private boolean closed;
        SnapshotReader(VersionedTextSnapshot source, BooleanSupplier cancelled) {
            this.source = source; this.cancelled = cancelled;
        }
        @Override public int read(char[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (closed) throw new IOException("Reader closed");
            checkCancelled(cancelled);
            if (length == 0) return 0;
            if (position == source.length()) return -1;
            int count = Math.min(Math.min(length, 8192), source.length() - position);
            source.substring(position, count).getChars(0, count, target, offset);
            position += count;
            return count;
        }
        @Override public void close() { closed = true; }
    }

    private static final class LimitExceeded extends RuntimeException {
        LimitExceeded(String message) { super(message); }
    }

    private static final class Projection {
        private final Source source;
        private final BooleanSupplier cancelled;
        private final List<Block> blocks = new ArrayList<>();
        private final List<MarkdownSemanticSnapshot.Heading> headings = new ArrayList<>();
        private final List<MarkdownSemanticSnapshot.CodeBlock> code = new ArrayList<>();
        private final List<Inline> inlines = new ArrayList<>();
        private final List<MarkdownSemanticSnapshot.Link> links = new ArrayList<>();
        private final List<ReferenceDefinition> references = new ArrayList<>();
        private final List<TaskMarker> tasks = new ArrayList<>();
        private final EnumSet<Limitation> limitations = EnumSet.of(Limitation.MATH_AND_WIKI_LINKS_DEFERRED,
            Limitation.UNRESOLVED_REFERENCES_ARE_TEXT, Limitation.LINK_FORM_AND_DESTINATION_RANGES_UNAVAILABLE);
        private final IdGenerator anchors = IdGenerator.builder().build();
        private int nodes, spans;

        Projection(Source source, BooleanSupplier cancelled) { this.source = source; this.cancelled = cancelled; }

        MarkdownSemanticSnapshot project(Node root) {
            // Iterative traversal: no additional recursive visitor/retained AST parent graph.
            for (Node node = root.getFirstChild(); node != null; node = next(node, root)) {
                checkCancelled(cancelled);
                if (++nodes > MAX_NODES) throw new LimitExceeded("Semantic node limit exceeded");
                // CommonMark's block cap stops opening blocks rather than reporting failure.
                // Never advertise that deliberately capped interpretation as complete semantics.
                if (node instanceof org.commonmark.node.Block) {
                    int depth = 0;
                    for (Node parent = node; parent != root; parent = parent.getParent()) {
                        if (++depth >= 127) throw new LimitExceeded("Semantic block-depth limit reached");
                    }
                }
                BlockKind kind = blockKind(node);
                InlineKind inline = inlineKind(node);
                if (kind == null && inline == null && !(node instanceof org.commonmark.node.Link)
                        && !(node instanceof Image) && !(node instanceof LinkReferenceDefinition)
                        && !(node instanceof TaskListItemMarker)) continue;
                Location location = location(node);
                if (kind != null) blocks.add(new Block(kind, location));
                if (node instanceof org.commonmark.node.Heading heading) {
                    String label = headingText(heading, true);
                    // Same public ID generator and Text/Code-only input as the existing heading-anchor extension.
                    String anchor = anchors.generateId(headingText(heading, false).trim().toLowerCase());
                    headings.add(new MarkdownSemanticSnapshot.Heading(heading.getLevel(), label, anchor, location,
                        headingMarker(heading, location)));
                } else if (node instanceof FencedCodeBlock fence) {
                    String info = fence.getInfo() == null ? "" : fence.getInfo();
                    String trimmed = info.strip();
                    String language = trimmed.isEmpty() ? "" : trimmed.split("\\s+", 2)[0];
                    boolean closed = fence.getClosingFenceLength() != null;
                    code.add(new MarkdownSemanticSnapshot.CodeBlock(info, language, language.equalsIgnoreCase("mermaid"),
                        closed, location, fenceMarker(location, fence.getFenceCharacter(), fence.getOpeningFenceLength(), false),
                        closed ? fenceMarker(location, fence.getFenceCharacter(), fence.getClosingFenceLength(), true) : Optional.empty()));
                } else if (node instanceof org.commonmark.node.Link link) {
                    links.add(new MarkdownSemanticSnapshot.Link(LinkKind.LINK, link.getDestination(), Optional.ofNullable(link.getTitle()), location));
                } else if (node instanceof Image image) {
                    links.add(new MarkdownSemanticSnapshot.Link(LinkKind.IMAGE, image.getDestination(), Optional.ofNullable(image.getTitle()), location));
                } else if (node instanceof LinkReferenceDefinition definition) {
                    references.add(new ReferenceDefinition(definition.getLabel(), definition.getDestination(),
                        Optional.ofNullable(definition.getTitle()), location));
                } else if (node instanceof TaskListItemMarker task) {
                    tasks.add(new TaskMarker(task.isChecked(), location));
                }
                if (inline != null) {
                    String delimiter = node instanceof Emphasis em ? em.getOpeningDelimiter()
                        : node instanceof StrongEmphasis strong ? strong.getOpeningDelimiter()
                        : node instanceof Strikethrough ? "~~" : null;
                    var markers = inlineMarkers(location, delimiter, node instanceof Code);
                    inlines.add(new Inline(inline, location, markers.get(0), markers.get(1)));
                }
            }
            return new MarkdownSemanticSnapshot(source, Status.SUCCEEDED, Coverage.COMPLETE,
                new MarkdownSourceRange(0, source.text().length()), blocks, headings, code, inlines, links, references,
                tasks, limitations, "Complete captured source parsed; feature/range limitations are explicit");
        }

        private Location location(Node node) {
            // The table extension unescapes pipes before inline parsing; inline spans can address wrong characters.
            if (!(node instanceof TableCell)) {
                for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent instanceof TableCell) {
                        limitations.add(Limitation.TABLE_INLINE_LOCATIONS_UNAVAILABLE);
                        return new Location(List.of());
                    }
                }
            }
            List<MarkdownSourceRange> ranges = new ArrayList<>();
            try {
                for (SourceSpan span : node.getSourceSpans()) {
                    if (++spans > MAX_SPANS) throw new LimitExceeded("Semantic span limit exceeded");
                    ranges.add(new MarkdownSourceRange(span.getInputIndex(), Math.addExact(span.getInputIndex(), span.getLength()))
                        .validate(source.text()));
                }
                if (ranges.isEmpty()) limitations.add(Limitation.MISSING_OR_INVALID_PARSER_SPANS);
                return new Location(ranges);
            } catch (IllegalArgumentException | ArithmeticException invalidSpan) {
                limitations.add(Limitation.MISSING_OR_INVALID_PARSER_SPANS);
                return new Location(List.of());
            }
        }

        private Optional<MarkdownSourceRange> headingMarker(org.commonmark.node.Heading heading, Location location) {
            if (location.segments().isEmpty()) return Optional.empty();
            MarkdownSourceRange first = location.segments().getFirst();
            String raw = first.text(source.text());
            int start = 0;
            while (start < raw.length() && raw.charAt(start) == ' ') start++;
            if (start <= 3 && raw.startsWith("#".repeat(heading.getLevel()), start)) {
                int end = start + heading.getLevel();
                if (end == raw.length() || raw.charAt(end) == ' ' || raw.charAt(end) == '\t') {
                    return Optional.of(new MarkdownSourceRange(first.start() + start, first.start() + end));
                }
            }
            // Setext underline (not the entire heading envelope).
            MarkdownSourceRange last = location.segments().getLast();
            String underline = last.text(source.text()).strip();
            if (!underline.isEmpty() && underline.chars().allMatch(c -> c == (heading.getLevel() == 1 ? '=' : '-'))) {
                String original = last.text(source.text());
                int offset = original.indexOf(underline);
                return Optional.of(new MarkdownSourceRange(last.start() + offset, last.start() + offset + underline.length()));
            }
            return Optional.empty();
        }

        private Optional<MarkdownSourceRange> fenceMarker(Location location, String character, int count, boolean closing) {
            if (location.segments().isEmpty()) return Optional.empty();
            MarkdownSourceRange line = closing ? location.segments().getLast() : location.segments().getFirst();
            String raw = line.text(source.text());
            int start = 0;
            while (start < raw.length() && raw.charAt(start) == ' ') start++;
            if (start <= 3 && raw.startsWith(character.repeat(count), start)) {
                return Optional.of(new MarkdownSourceRange(line.start() + start, line.start() + start + count));
            }
            return Optional.empty();
        }

        private List<Optional<MarkdownSourceRange>> inlineMarkers(Location location, String delimiter, boolean codeSpan) {
            var none = List.<Optional<MarkdownSourceRange>>of(Optional.empty(), Optional.empty());
            if (location.segments().isEmpty()) return none;
            var first = location.segments().getFirst(); var last = location.segments().getLast();
            if (codeSpan) {
                int count = 0;
                while (count < first.length() && source.text().charAt(first.start() + count) == '`') count++;
                delimiter = "`".repeat(count);
            }
            if (delimiter == null || delimiter.isEmpty() || first.length() < delimiter.length() || last.length() < delimiter.length()) return none;
            var opening = new MarkdownSourceRange(first.start(), first.start() + delimiter.length());
            var closing = new MarkdownSourceRange(last.end() - delimiter.length(), last.end());
            if (opening.end() > closing.start() || !opening.text(source.text()).equals(delimiter)
                    || !closing.text(source.text()).equals(delimiter)) return none;
            return List.of(Optional.of(opening), Optional.of(closing));
        }

        private String headingText(Node heading, boolean includeBreaks) {
            StringBuilder result = new StringBuilder();
            for (Node node = heading.getFirstChild(); node != null; node = next(node, heading)) {
                if (node instanceof Text text) result.append(text.getLiteral());
                else if (node instanceof Code codeSpan) result.append(codeSpan.getLiteral());
                else if (includeBreaks && (node instanceof SoftLineBreak || node instanceof HardLineBreak)) result.append(' ');
            }
            return result.toString();
        }

        private BlockKind blockKind(Node node) {
            if (node instanceof org.commonmark.node.Heading) return BlockKind.HEADING;
            if (node instanceof Paragraph) return BlockKind.PARAGRAPH;
            if (node instanceof FencedCodeBlock) return BlockKind.FENCED_CODE;
            if (node instanceof IndentedCodeBlock) return BlockKind.INDENTED_CODE;
            if (node instanceof BulletList) return BlockKind.BULLET_LIST;
            if (node instanceof OrderedList) return BlockKind.ORDERED_LIST;
            if (node instanceof ListItem) return BlockKind.LIST_ITEM;
            if (node instanceof BlockQuote) return BlockKind.BLOCK_QUOTE;
            if (node instanceof TableBlock) return BlockKind.TABLE;
            if (node instanceof TableHead) return BlockKind.TABLE_HEAD;
            if (node instanceof TableBody) return BlockKind.TABLE_BODY;
            if (node instanceof TableRow) return BlockKind.TABLE_ROW;
            if (node instanceof TableCell) return BlockKind.TABLE_CELL;
            if (node instanceof ThematicBreak) return BlockKind.THEMATIC_BREAK;
            if (node instanceof HtmlBlock) return BlockKind.HTML;
            if (node instanceof CustomBlock) {
                limitations.add(Limitation.EXTENSION_DETAILS_NOT_PROJECTED);
                return BlockKind.EXTENSION;
            }
            if (node instanceof CustomNode && !(node instanceof Strikethrough) && !(node instanceof TaskListItemMarker)) {
                limitations.add(Limitation.EXTENSION_DETAILS_NOT_PROJECTED);
            }
            return null;
        }

        private static InlineKind inlineKind(Node node) {
            if (node instanceof Emphasis) return InlineKind.EMPHASIS;
            if (node instanceof StrongEmphasis) return InlineKind.STRONG_EMPHASIS;
            if (node instanceof Strikethrough) return InlineKind.STRIKETHROUGH;
            if (node instanceof Code) return InlineKind.CODE;
            if (node instanceof HtmlInline) return InlineKind.HTML;
            return null;
        }

        private static Node next(Node node, Node root) {
            if (node.getFirstChild() != null) return node.getFirstChild();
            while (node != root && node.getNext() == null) node = node.getParent();
            return node == root ? null : node.getNext();
        }
    }
}
