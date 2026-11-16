package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.MarkdownSemanticSnapshot.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownSemanticParserTest {
    @TempDir Path temp;
    private final MarkdownSemanticParser parser = new MarkdownSemanticParser();

    private Source source(String value) {
        return new Source(new DocumentWorkspace.Id(1), 7, VersionedTextSnapshot.of(value));
    }
    private MarkdownSemanticSnapshot parse(String value) {
        var result = parser.parse(source(value));
        assertEquals(Status.SUCCEEDED, result.status(), result.detail());
        assertEquals(Coverage.COMPLETE, result.coverage());
        assertEquals(new MarkdownSourceRange(0, value.length()), result.coveredRange());
        return result;
    }
    private String raw(MarkdownSemanticSnapshot result, Location location) {
        return location.envelope().orElseThrow().text(result.source().text());
    }

    @Test void headingsFormattingSetextEmptyDuplicatesAndAnchorsMatchCommonMarkRenderer() {
        String input = "# Hé😀 *em* **strong** `code` ![alt](x)\n## two ##\nTitle\n=====\n\n#\n# duplicate\n# duplicate\n~~~\n# hidden\n~~~";
        var result = parse(input);
        assertEquals(List.of(1, 2, 1, 1, 1, 1), result.headings().stream().map(Heading::level).toList());
        assertEquals("Hé😀 em strong code alt", result.headings().getFirst().text());
        assertEquals("#", result.headings().getFirst().marker().orElseThrow().text(result.source().text()));
        assertEquals("=====", result.headings().get(2).marker().orElseThrow().text(result.source().text()));
        assertEquals("", result.headings().get(3).text());
        var extension = List.of(HeadingAnchorExtension.create());
        String html = HtmlRenderer.builder().extensions(extension).build().render(Parser.builder().build().parse(input));
        for (var heading : result.headings()) assertTrue(html.contains("id=\"" + heading.anchor() + "\""), html);
        assertNotEquals(result.headings().get(4).anchor(), result.headings().get(5).anchor());
        assertFalse(result.headings().stream().anyMatch(h -> h.text().equals("hidden")));
    }

    @Test void fencesPreserveMarkersLanguageNestedPrefixesAndUnclosedState() {
        for (String marker : List.of("```", "~~~~", "`````")) {
            var result = parse("> " + marker + "mermaid extra\n> A --> B\n> # hidden\n> " + marker + "\n");
            var fence = result.codeBlocks().getFirst();
            assertTrue(fence.closed()); assertTrue(fence.mermaid()); assertEquals("mermaid", fence.language());
            assertEquals("mermaid extra", fence.info()); assertTrue(result.headings().isEmpty());
            assertEquals(marker, fence.openingMarker().orElseThrow().text(result.source().text()));
            assertEquals(marker, fence.closingMarker().orElseThrow().text(result.source().text()));
            assertEquals("A --> B", fence.location().segments().get(1).text(result.source().text()));
        }
        var open = parse("```java\n\tα < & >\n").codeBlocks().getFirst();
        assertFalse(open.closed()); assertTrue(open.closingMarker().isEmpty()); assertEquals("java", open.language());
        assertEquals(1, parse("~~~\n~~~").codeBlocks().size());
        assertEquals(1, parse("```\n\n\n```\n").codeBlocks().size());
        assertEquals(1, parse("````\n```\n# hidden\n````").codeBlocks().size());
    }

    @Test void multilineHeadingLabelsAndLongUnicodeParagraphsHaveExactSourceRanges() {
        var result = parse("first *line*\nsecond `line`\n---\n\n" + "x".repeat(60_000) + " **עברית😀**");
        var heading = result.headings().getFirst();
        assertEquals("first line second line", heading.text());
        assertEquals("first-linesecond-line", heading.anchor()); // Existing extension ignores line breaks for IDs.
        assertEquals("first *line*\nsecond `line`\n---", raw(result, heading.location()));
        assertEquals("**עברית😀**", raw(result, result.inlines().getLast().location()));
    }

    @Test void inlineAndReferenceLinksImagesEscapesAndUnresolvedTextAreHonest() {
        var result = parse("[a *b*](../x\\(y.md#part \"title\") ![alt](img.png) [ref][r] [r][] [r] [missing][no]\n\n[r]: <target.md> 'R'\n");
        assertEquals(5, result.links().size());
        assertEquals("../x(y.md#part", result.links().getFirst().destination());
        assertEquals("title", result.links().getFirst().title().orElseThrow());
        assertEquals(LinkKind.IMAGE, result.links().get(1).kind());
        assertEquals("![alt](img.png)", raw(result, result.links().get(1).location()));
        assertEquals(3, result.links().stream().filter(l -> l.destination().equals("target.md")).count());
        assertEquals("[ref][r]", raw(result, result.links().get(2).location()));
        assertEquals("r", result.references().getFirst().label());
        assertEquals("R", result.references().getFirst().title().orElseThrow());
        assertTrue(result.limitations().contains(Limitation.UNRESOLVED_REFERENCES_ARE_TEXT));
        var autolinks = parse("<https://example.test> https://example.test\n");
        assertEquals(2, autolinks.links().size());
        assertEquals("<https://example.test>", raw(autolinks, autolinks.links().getFirst().location()));
    }

    @Test void supportedInlineMarkersAreExactAndUnknownMarkersStayAbsent() {
        var result = parse("*em* **strong** ~~strike~~ ``a ` b`` \\*escaped*\n\n<div>html</div>\n");
        assertEquals(List.of(InlineKind.EMPHASIS, InlineKind.STRONG_EMPHASIS, InlineKind.STRIKETHROUGH, InlineKind.CODE),
            result.inlines().stream().map(Inline::kind).toList());
        for (var inline : result.inlines()) {
            var opening = inline.openingMarker().orElseThrow(); var closing = inline.closingMarker().orElseThrow();
            assertEquals(opening.text(result.source().text()), closing.text(result.source().text()));
            assertTrue(opening.end() <= closing.start());
        }
        assertTrue(result.blocks().stream().anyMatch(b -> b.kind() == BlockKind.HTML));
    }

    @Test void listsTasksQuotesTablesAndEscapedPipesDoNotFabricateInlineRanges() {
        var result = parse("- [x] task\n  - nested\n\n1. ordered\n\n> quote\n> next\n\n| a\\|b | *c* [l](x) |\n|---|---|\n| e | f |\n");
        for (BlockKind kind : List.of(BlockKind.BULLET_LIST, BlockKind.ORDERED_LIST, BlockKind.LIST_ITEM,
                BlockKind.BLOCK_QUOTE, BlockKind.TABLE, BlockKind.TABLE_CELL)) {
            assertTrue(result.blocks().stream().anyMatch(b -> b.kind() == kind), kind.name());
        }
        assertTrue(result.tasks().getFirst().checked()); assertTrue(result.tasks().getFirst().location().segments().isEmpty());
        var cell = result.blocks().stream().filter(b -> b.kind() == BlockKind.TABLE_CELL).findFirst().orElseThrow();
        assertEquals(" a\\|b ", raw(result, cell.location()));
        assertTrue(result.inlines().getFirst().location().segments().isEmpty());
        assertTrue(result.links().getFirst().location().envelope().isEmpty());
        assertEquals("x", result.links().getFirst().destination());
        assertTrue(result.limitations().contains(Limitation.TABLE_INLINE_LOCATIONS_UNAVAILABLE));
        var quote = result.blocks().stream().filter(b -> b.kind() == BlockKind.PARAGRAPH && b.location().segments().size() == 2)
            .findFirst().orElseThrow();
        assertEquals(List.of("quote", "next"), quote.location().segments().stream().map(r -> r.text(result.source().text())).toList());
        assertTrue(raw(result, quote.location()).contains("\n> ")); // Envelope is NOT content-only.
    }

    @Test void offsetsCoverLfCrLfMixedTabsEmojiCombiningMarksAndNoEofNewline() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            String input = "\tprose" + newline + "# 😀e\u0301" + newline + "[x](é.md)";
            var result = parse(input);
            assertEquals("# 😀e\u0301", raw(result, result.headings().getFirst().location()));
            assertEquals(input.indexOf('#'), result.headings().getFirst().location().envelope().orElseThrow().start());
            assertEquals(input.length(), result.links().getFirst().location().envelope().orElseThrow().end());
        }
        var mixed = parse("# a\r\n> **😀**\n\n[b](x)\r# last");
        assertEquals(2, mixed.headings().size());
        assertEquals("**😀**", raw(mixed, mixed.inlines().getFirst().location()));
        assertTrue(parse("").blocks().isEmpty());
        assertEquals("single", parse("single").blocks().getFirst().location().segments().getFirst().text(VersionedTextSnapshot.of("single")));
    }

    @Test void rangesValidateUtf16BoundariesPositionsAndEmptyEofWithoutClamping() {
        var text = VersionedTextSnapshot.of("\t😀e\u0301\nlast");
        assertEquals(new MarkdownSourceRange(1, 1), MarkdownSourceRange.at(text, 0, 1));
        assertEquals("😀", new MarkdownSourceRange(1, 3).text(text));
        assertEquals("", new MarkdownSourceRange(text.length(), text.length()).text(text));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSourceRange(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSourceRange(3, 2));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSourceRange(1, text.length() + 1).validate(text));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSourceRange(2, 3).validate(text));
        assertThrows(IllegalArgumentException.class, () -> MarkdownSourceRange.at(text, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> MarkdownSourceRange.at(text, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> MarkdownSourceRange.at(text, 2, 0));
    }

    @Test void oldSnapshotsNeverBecomeCurrentForAnotherRevisionOrDocumentEvenWithIdenticalText() {
        var original = source("# heading");
        var result = parser.parse(original);
        assertTrue(result.isCurrent(original));
        assertTrue(result.isCurrent(new Source(original.documentId(), original.revision(), original.text()))); // Shared splits.
        for (var stale : List.of(new Source(original.documentId(), 8, original.text()),
                new Source(new DocumentWorkspace.Id(2), 7, original.text()), source("# heading"),
                new Source(original.documentId(), 7, original.text().replace(0, 1, "#")))) {
            assertFalse(result.isCurrent(stale)); assertThrows(IllegalStateException.class, () -> result.requireCurrent(stale));
        }
        assertEquals("# heading", result.source().text().text());
        assertThrows(UnsupportedOperationException.class, () -> result.headings().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.headings().getFirst().location().segments().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.limitations().clear());
    }

    @Test void cancellationLimitsAndUnsupportedSyntaxNeverPublishPretendPartialSuccess() {
        assertEquals(Status.CANCELLED, parser.parse(source("# a"), () -> true).status());
        var projected = source("# projection");
        assertEquals(Status.UNSUPPORTED, parser.parse(new Source(projected.documentId(), projected.revision(), projected.text(), false)).status());
        for (String value : List.of("x".repeat(MarkdownSemanticParser.MAX_CHARACTERS + 1),
                "x".repeat(MarkdownSemanticParser.MAX_LINE_CHARACTERS + 1), "\n".repeat(MarkdownSemanticParser.MAX_LINES), "\ufeff# a")) {
            var result = parser.parse(source(value));
            assertEquals(Status.UNSUPPORTED, result.status(), result.detail());
            assertEquals(Coverage.NONE, result.coverage()); assertEquals(new MarkdownSourceRange(0, 0), result.coveredRange());
            assertTrue(result.blocks().isEmpty()); assertTrue(result.headings().isEmpty());
        }
        AtomicInteger checks = new AtomicInteger();
        assertEquals(Status.CANCELLED, parser.parse(source(("# heading\nparagraph\n\n").repeat(1000)), () -> checks.incrementAndGet() > 10).status());
        assertTrue(parse("$math$ [[wiki]]\n").limitations().contains(Limitation.MATH_AND_WIKI_LINKS_DEFERRED));
        var nested = parser.parse(source("> ".repeat(140) + "text"));
        assertEquals(Status.UNSUPPORTED, nested.status()); assertTrue(nested.headings().isEmpty());
    }

    @Test void boundedRandomizedDifferentialPreservesHeadingAndLinkSpansAfterEdits() {
        var random = new Random(0x2A5EED);
        String[] pieces = {"# A *b* 😀\n", "setext\n---\n", "[x](../a.md#b)\n", "[x][r]\n\n[r]: target\n",
            "> ## quoted\n> paragraph\n", "- item **bold**\n  - nested\n", "```java\n# hidden [x](y)\n```\n", "e\u0301 `x` \\*literal*\n"};
        var reference = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
        for (int run = 0; run < 100; run++) {
            StringBuilder input = new StringBuilder();
            for (int i = 0; i < 20; i++) input.append(pieces[random.nextInt(pieces.length)]).append('\n');
            var captured = source(input.toString());
            for (int edit = 0; edit < 4; edit++) {
                var result = parser.parse(captured); assertEquals(Status.SUCCEEDED, result.status(), result.detail());
                List<Node> nodes = new ArrayList<>();
                reference.parse(captured.text().text()).accept(new org.commonmark.node.AbstractVisitor() {
                    @Override public void visit(org.commonmark.node.Heading n) { nodes.add(n); visitChildren(n); }
                    @Override public void visit(org.commonmark.node.Link n) { nodes.add(n); visitChildren(n); }
                });
                var expectedHeadings = nodes.stream().filter(n -> n instanceof org.commonmark.node.Heading).toList();
                var expectedLinks = nodes.stream().filter(n -> n instanceof org.commonmark.node.Link).toList();
                assertEquals(expectedHeadings.size(), result.headings().size()); assertEquals(expectedLinks.size(), result.links().size());
                for (int i = 0; i < expectedHeadings.size(); i++) assertSpans(expectedHeadings.get(i), result.headings().get(i).location());
                for (int i = 0; i < expectedLinks.size(); i++) {
                    assertSpans(expectedLinks.get(i), result.links().get(i).location());
                    assertEquals(((org.commonmark.node.Link) expectedLinks.get(i)).getDestination(), result.links().get(i).destination());
                }
                // Change a line boundary/delimiter before a consumer could reuse the old result.
                int offset = captured.text().lineStartOffset(random.nextInt(captured.text().lineCount()));
                var changed = captured.text().replace(offset, 0, edit % 2 == 0 ? "~~~\n" : "# edit\n");
                captured = new Source(captured.documentId(), captured.revision() + 1, changed);
                assertFalse(result.isCurrent(captured));
            }
        }
    }

    private void assertSpans(Node expected, Location actual) {
        assertEquals(expected.getSourceSpans().stream().map(s -> new MarkdownSourceRange(s.getInputIndex(), s.getInputIndex() + s.getLength())).toList(), actual.segments());
    }

    @Test void parallelRequestsHaveNoSharedParserStateOrDuplicateHeadingIds() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<MarkdownSemanticSnapshot>>();
            for (int i = 0; i < 16; i++) tasks.add(() -> parser.parse(source("# same\n# same\n[ref][r]\n\n[r]: x")));
            for (var future : workers.invokeAll(tasks)) {
                var result = future.get(); assertEquals(Status.SUCCEEDED, result.status());
                assertEquals(List.of("same", "same-1"), result.headings().stream().map(Heading::anchor).toList());
                assertEquals("x", result.links().getFirst().destination());
            }
        }
    }

    @Test void parsingCleanAndDirtyBuffersPreservesSourceHistoryEventsSplitsCodecAndSavedBytes() throws Exception {
        for (boolean dirty : new boolean[]{false, true}) {
            Path path = temp.resolve("source-" + dirty + ".md");
            byte[] bytes = "\ufeff# 😀\r\nparagraph\n[x](a)".getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            var buffer = new FileBuffer(path.toFile());
            AtomicInteger events = new AtomicInteger();
            Source[] captured = new Source[1];
            JTextArea[] panes = new JTextArea[2];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    panes[0] = new JTextArea(); panes[1] = new JTextArea();
                    for (var pane : panes) { pane.setDocument(buffer.getDocument()); pane.setCaretPosition(3); }
                    buffer.getDocument().addDocumentListener(new DocumentListener() {
                        public void insertUpdate(DocumentEvent e) { changed(e); }
                        public void removeUpdate(DocumentEvent e) { changed(e); }
                        public void changedUpdate(DocumentEvent e) { changed(e); }
                        private void changed(DocumentEvent e) { events.incrementAndGet(); buffer.applyDocumentChange(e); buffer.setModified(true); }
                    });
                    if (dirty) buffer.getDocument().insertString(buffer.getDocument().getLength(), "!", null);
                    var store = new LegacyDocumentStore(null);
                    captured[0] = Source.capture(store.id(buffer), buffer);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            String text = buffer.textSnapshot().text(), saved = buffer.getSavedContent();
            var undo = (BoundedUndoManager) buffer.getUndoManager();
            int count = undo.retainedEditCount(), beforeEvents = events.get();
            long retained = undo.retainedBytes();
            var result = parser.parse(captured[0]); // Background-safe snapshot access, no Swing access.
            assertEquals(Status.SUCCEEDED, result.status());
            SwingUtilities.invokeAndWait(() -> {
                assertSame(captured[0].text(), buffer.textSnapshot()); assertEquals(captured[0].revision(), buffer.documentRevision());
                assertEquals(text, panes[0].getText()); assertEquals(text, panes[1].getText());
                assertEquals(3, panes[0].getCaretPosition()); assertEquals(3, panes[1].getCaretPosition());
                assertEquals(saved, buffer.getSavedContent()); assertEquals(dirty, buffer.isModified());
                assertEquals(beforeEvents, events.get()); assertEquals(count, undo.retainedEditCount()); assertEquals(retained, undo.retainedBytes());
                assertEquals(dirty, undo.canUndo());
                if (dirty) { undo.undo(); assertEquals(saved, panes[0].getText()); undo.redo(); assertEquals(text, panes[1].getText()); }
            });
            assertArrayEquals(bytes, Files.readAllBytes(path));
            assertFalse(result.source().text().text().startsWith("\ufeff"));
            if (!dirty) { buffer.save(); assertArrayEquals(bytes, Files.readAllBytes(path)); }
        }
    }
}
