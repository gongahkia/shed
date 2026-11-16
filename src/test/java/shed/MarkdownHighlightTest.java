package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MarkdownHighlightTest {
    private final GrammarHighlightService grammar = new GrammarHighlightService();

    private GrammarHighlightService.MarkdownResult compare(VersionedTextSnapshot text, int start, int end,
                                                          GrammarHighlightService.MarkdownCache cache) {
        var result = grammar.highlightMarkdown(text, start, end, cache, cache.bind(text), () -> false);
        assertNotNull(result);
        int first = text.lineStartOffset(text.positionAt(start).line());
        int last = text.lineEndOffset(text.positionAt(Math.max(start, end)).line());
        var expected = grammar.highlightSnapshot(text.text(), FileType.MARKDOWN).stream()
            .filter(t -> t.start() >= first && t.end() <= last).toList();
        assertEquals(expected, result.tokens());
        assertTrue(result.tokens().stream().allMatch(t -> t.start() >= first && t.end() <= last));
        assertTrue(cache.checkpointCount() <= GrammarHighlightService.MarkdownCache.MAX_CHECKPOINTS);
        return result;
    }

    @Test void matchesFullLexerAcrossFencesWhitespaceUnicodeAndExistingSyntax() {
        String block = "# Heading `code`\nSetext\n===\n**strong** *em* ~~strike~~ \\`escaped`\n"
            + "- [x] task\n1. ordered\n  > > quote\n[ref]: file.md\n![image](a.png) [link](#heading)\n"
            + "| a\\|b | c |\n| --- | --- |\n<div>raw</div>\n\n~~~python\n'''open\ntext α😀 é\nclose'''\n~~~~~\n"
            + "````java\n/* open\nstill comment\n*/ class Demo {}\n```\n```mermaid\nflowchart LR\n A --> B\n```\n"
            + "```math\nx^2\n```\n```java\n/* unclosed\n";
        var text = VersionedTextSnapshot.of(block);
        var cache = new GrammarHighlightService.MarkdownCache();
        for (int at = 0; at <= text.length(); at += 7) compare(text, at, Math.min(text.length(), at + 21), cache);
        compare(text, text.length(), text.length(), cache);
    }

    @Test void supportsEmptySingleLongLinesAndChunkBoundaryNewlines() {
        for (String value : List.of("", "x", "`α😀`", "# " + "x".repeat(40000),
                "x".repeat(16383) + "\n```java\nclass X {}\n```\n", "  \r\n`x`\r\n")) {
            var text = VersionedTextSnapshot.of(value);
            var cache = new GrammarHighlightService.MarkdownCache();
            compare(text, text.length() / 2, text.length(), cache);
            compare(text, 0, text.length(), cache);
        }
    }

    @Test void repeatedDeepRequestsAndOrdinaryEarlierEditsReuseState() {
        var text = VersionedTextSnapshot.of("# item `code`\nparagraph\n".repeat(10000));
        var cache = new GrammarHighlightService.MarkdownCache();
        int at = text.length() - 200;
        compare(text, at, text.length(), cache);
        var warm = compare(text, at, text.length(), cache);
        assertTrue(warm.scannedCharacters() < 1000);
        var edited = text.replace(2, 0, "ordinary 😀\nnew line\n");
        cache.changed(text, edited, 2, 0);
        var updated = compare(edited, edited.length() - 200, edited.length(), cache);
        assertTrue(updated.scannedCharacters() < 10000, "An unchanged suffix should reconverge after the first checkpoint");
        var removed = edited.replace(2, 21, "");
        cache.changed(edited, removed, 2, 21);
        compare(removed, removed.length() - 200, removed.length(), cache);
    }

    @Test void changedUnclosedFenceMustRescanItsAffectedSuffix() {
        var text = VersionedTextSnapshot.of("plain\n".repeat(3000));
        var cache = new GrammarHighlightService.MarkdownCache();
        compare(text, text.length() - 100, text.length(), cache);
        var fenced = text.replace(0, 0, "```java\n/* open\n");
        cache.changed(text, fenced, 0, 0);
        var changed = compare(fenced, fenced.length() - 100, fenced.length(), cache);
        assertTrue(changed.scannedCharacters() >= text.length());
        var closed = fenced.replace(fenced.length() / 2, 0, "\n*/\n```\n");
        cache.changed(fenced, closed, fenced.length() / 2, 0);
        compare(closed, closed.length() - 100, closed.length(), cache);
    }

    @Test void randomizedEditsUndoRedoAndViewportsAgreeWithFullScan() {
        var random = new Random(0x1D5EED);
        String[] pieces = {"# head `x`\n", "\n", "```java\n", "~~~python\n", "```\n", "~~~~\n",
            "/* open\n", "*/ class A {}\n", "'''\n", "- [ ] α😀\n", "text \\` *x*\n", "\t> quote\n", "x", "\r\n"};
        var text = VersionedTextSnapshot.of(String.join("", pieces).repeat(15));
        var cache = new GrammarHighlightService.MarkdownCache();
        for (int i = 0; i < 500; i++) {
            int offset = random.nextInt(text.length() + 1);
            int removed = Math.min(random.nextInt(24), text.length() - offset);
            String insertion = pieces[random.nextInt(pieces.length)];
            var next = text.replace(offset, removed, insertion);
            cache.changed(text, next, offset, removed);
            if (i % 9 == 0) { // Multiple changes without intervening analysis must not trust old suffix candidates.
                var again = next.replace(0, 0, "\n"); cache.changed(next, again, 0, 0); next = again;
            }
            int start = random.nextInt(next.length() + 1);
            compare(next, start, Math.min(next.length(), start + random.nextInt(400)), cache);
            if (i % 17 == 0) { // Exact undo/redo snapshots, including changed line counts.
                cache.bind(text); compare(text, 0, text.length(), cache);
                cache.bind(next); compare(next, 0, next.length(), cache);
            }
            text = next;
        }
    }

    @Test void cancelledOrStaleRequestsCannotPublishMetadata() {
        var text = VersionedTextSnapshot.of("# header\n".repeat(10000));
        var cache = new GrammarHighlightService.MarkdownCache();
        long generation = cache.bind(text);
        assertNull(grammar.highlightMarkdown(text, text.length() - 20, text.length(), cache, generation, () -> true));
        assertEquals(1, cache.checkpointCount());
        var calls = new AtomicInteger();
        assertNull(grammar.highlightMarkdown(text, text.length() - 20, text.length(), cache, generation,
            () -> calls.incrementAndGet() > 30));
        assertEquals(1, cache.checkpointCount());
        var edited = text.replace(0, 0, "```java\n");
        cache.changed(text, edited, 0, 0);
        assertNull(grammar.highlightMarkdown(text, 0, 100, cache, generation, () -> false));
        compare(edited, 0, 100, cache);
    }

    @Test void sparseMetadataRemainsBoundedAndUnknownRevisionsResetIt() {
        var text = VersionedTextSnapshot.of("# x\n".repeat(100000));
        var cache = new GrammarHighlightService.MarkdownCache();
        compare(text, text.length() - 10, text.length(), cache);
        var random = new Random(17);
        for (int i = 0; i < 1000; i++) {
            int start = random.nextInt(text.length());
            assertNotNull(grammar.highlightMarkdown(text, start, Math.min(start + 20, text.length()), cache, cache.bind(text), () -> false));
            assertTrue(cache.checkpointCount() <= GrammarHighlightService.MarkdownCache.MAX_CHECKPOINTS);
        }
        var replacement = VersionedTextSnapshot.of("```java\nclass Other {}\n");
        cache.bind(replacement); assertEquals(1, cache.checkpointCount());
        compare(replacement, 0, replacement.length(), cache);
    }

    @Test void editDuringWorkerScanRejectsPublicationWithoutHoldingTheCacheLock() throws Exception {
        var text = VersionedTextSnapshot.of("plain\n".repeat(3000));
        var cache = new GrammarHighlightService.MarkdownCache(); long generation = cache.bind(text);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> grammar.highlightMarkdown(text, text.length() - 20, text.length(), cache, generation, () -> {
                if (once.compareAndSet(false, true)) {
                    entered.countDown();
                    try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Worker gate timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return true; }
                }
                return false;
            }));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var edited = text.replace(0, 0, "~~~java\n");
            cache.changed(text, edited, 0, 0);
            release.countDown();
            assertNull(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, cache.checkpointCount());
            compare(edited, edited.length() - 20, edited.length(), cache);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void statelessViewportAlsoEmitsOnlyItsLineContext() {
        String text = "# before\n".repeat(100) + "```java\nclass Visible {}\n```\n# after\n";
        int start = text.indexOf("class Visible"); int end = start + 4;
        var tokens = grammar.highlightViewport(text, FileType.MARKDOWN, start, end);
        assertEquals(List.of(new GrammarHighlightService.Token(start, start + 5, GrammarHighlightService.Scope.KEYWORD),
            new GrammarHighlightService.Token(start + 6, start + 13, GrammarHighlightService.Scope.TYPE)), tokens);
    }
}
