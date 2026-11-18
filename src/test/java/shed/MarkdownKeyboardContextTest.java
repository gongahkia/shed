package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownKeyboardContextTest {
    private final GrammarHighlightService grammar = new GrammarHighlightService();
    private final MarkdownService service = new MarkdownService();
    private VersionedTextSnapshot text(String source) { return FileBuffer.createScratch("context.md", source).textSnapshot(); }
    private MarkdownKeyboardContext.State before(String prefix) {
        var text = text(prefix); return MarkdownKeyboardContext.before(text, 0, MarkdownKeyboardContext.TEXT, text.length());
    }

    @Test void quotesPreserveDepthWhitespaceAndExistingListTaskRules() {
        for (String[] row : new String[][] {{"> text", "> "}, {"> > text", "> > "}, {"> > > text", "> > > "},
            {">>>text", ">>> "}, {"  > > é😀é", "  > > "}, {">   > text", ">   > "},
            {"> - item", "> - "}, {"> > 2. item", "> > 3. "}, {"> - [x] task", "> - [ ] "},
            {">   - nested", ">   - "}, {"- item", "- "}, {"2. item", "3. "}, {"- [ ] task", "- [ ] "}})
            assertEquals(row[1], service.listContinuation(row[0]), row[0]);
    }

    @Test void emptyQuotesExitAndAmbiguousIndentationDoesNotInventAPrefix() {
        for (String line : List.of(">", "> ", "> > ", ">>>", "   > >   ")) {
            assertTrue(service.isEmptyListItem(line), line); assertNull(service.listContinuation(line), line);
        }
        for (String line : List.of("    > code", "\t> code", ">\tcode", ">     code", "\\> escaped", ">".repeat(33) + " text"))
            assertNull(service.listContinuation(line), line);
        assertFalse(service.isEmptyListItem("    > "));
        assertFalse(service.isEmptyListItem("> \u2003")); assertEquals("> ", service.listContinuation("> \u2003"));
    }

    @Test void exactFenceLengthsAndClosingSuffixesAreDifferentFromHighlightColours() {
        for (String marker : List.of("```", "````", "~~~", "~~~~~")) {
            assertFalse(before(marker + "java\n").text());
            assertTrue(before(marker + "java\nbody\n" + marker + "\n").text());
            assertFalse(before(marker + "java\n" + marker + "python\n").text());
            assertFalse(before(marker + "java\n" + marker + "\u00a0\n").text());
            assertFalse(before(marker + "java\n" + marker.substring(1) + "\n").text());
            assertTrue(before(marker + "java\n" + marker + marker.charAt(0) + "  \n").text());
        }
        assertTrue(before("\\```\n").text()); assertTrue(before("    ```\n").text());
        assertTrue(before("```bad`info\n").text());
        assertTrue(before("```a\nbody\n```\n~~~b\nbody\n~~~\n").text());
    }

    @Test void containerAndHtmlFencesRemainExplicitlyUnknown() {
        for (String source : List.of("> ```\n", "- ```\n", "1) ~~~\n", "<script>\n```\n", "- item\n  ```\n", "- item\n    ~~~\n"))
            assertSame(MarkdownKeyboardContext.UNKNOWN, before(source), source);
        assertTrue(before("- item\n> > paragraph\n").text());
        assertFalse(before("- item\n```\n").text());
    }

    @Test void readLineAndDistanceAdmissionAreBounded() {
        assertSame(MarkdownKeyboardContext.UNKNOWN, before("x\n".repeat(129)));
        assertTrue(before("x\n".repeat(128)).text());
        assertSame(MarkdownKeyboardContext.UNKNOWN, before("x".repeat(4097) + "\n"));
        assertSame(MarkdownKeyboardContext.UNKNOWN, before(("x".repeat(4095) + "\n").repeat(5)));
        assertTrue(before(("x".repeat(4095) + "\n").repeat(4)).text());
    }

    @Test void cacheResultsRequireExactSnapshotAndInvalidationPreservesOnlyTrustedPrefixes() {
        var cache = new GrammarHighlightService.MarkdownCache(); var source = text("```java\n> > example\n```\n> > body\n");
        long generation = cache.bind(source);
        assertNotNull(grammar.highlightMarkdown(source, 0, source.length(), cache, generation, () -> false));
        assertFalse(cache.keyboardContext(source, 1, source.lineStartOffset(1)).text());
        assertTrue(cache.keyboardContext(source, 3, source.lineStartOffset(3)).text());
        var changed = source.replace(0, 3, "ordinary"); cache.changed(source, changed, 0, 3);
        assertSame(MarkdownKeyboardContext.UNKNOWN, cache.keyboardContext(source, 3, source.lineStartOffset(3)));
        assertTrue(cache.keyboardContext(changed, 1, changed.lineStartOffset(1)).text());
        assertNull(grammar.highlightMarkdown(source, 0, source.length(), cache, generation, () -> false));
        assertNotNull(grammar.highlightMarkdown(changed, 0, changed.length(), cache, cache.bind(changed), () -> false));
        assertFalse(cache.keyboardContext(changed, 3, changed.lineStartOffset(3)).text());
    }

    @Test void sparseCheckpointQueriesAtDepthDoNotScanFromDocumentStart() {
        String source = ("body\n").repeat(4000) + "````java\n```\n> > example\n````\n> > body\n";
        var text = text(source); var cache = new GrammarHighlightService.MarkdownCache();
        grammar.highlightMarkdown(text, text.lineStartOffset(3990), text.length(), cache, cache.bind(text), () -> false);
        assertFalse(cache.keyboardContext(text, 4002, text.lineStartOffset(4002)).text());
        assertTrue(cache.keyboardContext(text, 4004, text.lineStartOffset(4004)).text());
        var cold = new GrammarHighlightService.MarkdownCache(); cold.bind(text);
        assertSame(MarkdownKeyboardContext.UNKNOWN, cold.keyboardContext(text, 4002, text.lineStartOffset(4002)));
        assertTrue(cache.checkpointCount() <= GrammarHighlightService.MarkdownCache.MAX_CHECKPOINTS);
    }
    @Test void aTextDecisionNeverAuthorizesContinuationInsideParserVerifiedFences() {
        var parser = new MarkdownSemanticParser();
        for (String opening : List.of("```", "````java", "~~~text", "  `````", "> ```", "- ```", "1) ~~~", "    ```", "\\```", "-\n  ```", "+\n  ```", "*\n  ```", "1.\n   ```", "1)\n   ```"))
            for (String middle : List.of("body", "```", "~~~~", "```python", "```\u00a0", "    ```", "   ~~~")) {
                String source = opening + "\n" + middle + "\n> > target\n";
                int target = source.indexOf("> > target");
                var text = text(source);
                var context = MarkdownKeyboardContext.before(text, 0, MarkdownKeyboardContext.TEXT, target);
                var semantic = parser.parse(new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(1), 1, text));
                assertEquals(MarkdownSemanticSnapshot.Coverage.COMPLETE, semantic.coverage());
                if (context.text()) assertFalse(semantic.codeBlocks().stream().anyMatch(block -> block.location().envelope()
                    .map(range -> range.start() <= target && target < range.end()).orElse(false)), source);
            }
    }

}
