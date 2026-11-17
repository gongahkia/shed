package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class MarkdownConsumersTest {
    static MarkdownSemanticSnapshot.Source source(String text) {
        return new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(1), 0, VersionedTextSnapshot.of(text));
    }
    static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(15, TimeUnit.SECONDS), "Completion not delivered"); }

    @Test void semanticHeadingsAreOrderedAndNavigateLevelsWithoutFencedExamples() {
        var input = source("# first\n```\n# fake\n```\nTitle *formatted* 😀\n---\n#\n## repeated\n## repeated\n\\# escaped\n~~~\n# hidden");
        var result = MarkdownHeadingAnalysis.analyze(input, () -> false);
        assertEquals(MarkdownHeadingAnalysis.Path.SEMANTIC, result.path());
        assertEquals(List.of("first", "Title formatted 😀", "", "repeated", "repeated"), result.headings().stream().map(MarkdownHeadingAnalysis.Heading::title).toList());
        assertEquals(5, result.target(true, 1, 0).orElseThrow().line());
        assertEquals(1, result.target(false, 5, 0).orElseThrow().line());
        assertEquals(2, result.target(true, 1, 2).orElseThrow().level());
        assertTrue(result.target(false, 1, 0).isEmpty());
        for (var heading : result.headings()) assertEquals(heading.line() - 1, input.text().positionAt(heading.offset()).line());
        assertEquals(MarkdownHeadingAnalysis.Path.SEMANTIC, MarkdownHeadingAnalysis.analyze(source("```\n# only fake\n```"), () -> false).path());
        assertTrue(MarkdownHeadingAnalysis.analyze(source("ordinary"), () -> false).headings().isEmpty());
    }

    @Test void limitsFailuresCancellationAndAdmissionHaveExplicitProvenance() {
        var big = source("p".repeat(MarkdownSemanticParser.MAX_CHARACTERS + 1) + "\n# preserved");
        var result = MarkdownHeadingAnalysis.analyze(big, () -> false);
        assertEquals(MarkdownHeadingAnalysis.Path.LEGACY_UNSUPPORTED, result.path());
        assertEquals("preserved", result.headings().getFirst().title());
        assertEquals(MarkdownSemanticSnapshot.Coverage.NONE, result.semantic().coverage());
        var small = source("# legacy\n```\n# legacy limitation\n```");
        var failed = MarkdownHeadingAnalysis.from(MarkdownSemanticSnapshot.unavailable(small, MarkdownSemanticSnapshot.Status.FAILED, "test"), () -> false, System.nanoTime());
        assertEquals(MarkdownHeadingAnalysis.Path.LEGACY_FAILED, failed.path()); assertEquals(2, failed.headings().size());
        assertEquals(MarkdownHeadingAnalysis.Path.CANCELLED, MarkdownHeadingAnalysis.analyze(small, () -> true).path());
        var dense = MarkdownHeadingAnalysis.analyze(source(("prose\n").repeat(45000) + ("*[]|\n").repeat(9000) + "# final"), () -> false);
        assertEquals(MarkdownHeadingAnalysis.Path.LEGACY_UNSUPPORTED, dense.path());
        assertTrue(dense.semantic().detail().contains("admission"));
        assertEquals("final", dense.headings().getFirst().title());
    }

    @Test void occurrenceViewsExposeVerifiedReferencesFragmentsImagesFencesAndNoFakeCodeLinks() {
        var input = source("[plain](../a.md#part) [ref][r] [r][] [r] [missing][absent]\n![alt](a.png) [escaped](a\\(b)\n\n[r]: target.md#ref\n\n```mermaid\nA --> B\n```\n~~~java\n[not a link](fake)\n~~~\n");
        var semantic = new MarkdownSemanticParser().parse(input);
        var view = new MarkdownOccurrences(semantic);
        var all = view.stream().toList();
        assertEquals(MarkdownSemanticSnapshot.Status.SUCCEEDED, view.status());
        assertEquals(1, all.stream().filter(o -> o.kind() == MarkdownOccurrences.Kind.IMAGE).count());
        assertEquals(1, all.stream().filter(o -> o.kind() == MarkdownOccurrences.Kind.MERMAID).count());
        assertEquals(1, all.stream().filter(o -> o.kind() == MarkdownOccurrences.Kind.CODE_FENCE).count());
        assertEquals(1, all.stream().filter(o -> o.kind() == MarkdownOccurrences.Kind.REFERENCE_DEFINITION).count());
        assertEquals("part", all.getFirst().fragment().orElseThrow());
        assertEquals("../a.md#part", all.getFirst().rawDestination().orElseThrow());
        assertEquals("plain", all.getFirst().label().orElseThrow());
        assertEquals(3, all.stream().filter(o -> o.referenceStatus() == MarkdownOccurrences.ReferenceStatus.RESOLVED).count());
        assertTrue(all.stream().noneMatch(o -> o.destination().orElse("").equals("fake")));
        assertTrue(all.stream().noneMatch(o -> o.referenceIdentifier().orElse("").equals("absent")));
        assertFalse(view.mathSupported()); assertFalse(view.unresolvedReferenceUsesSupported());
        for (var occurrence : all) {
            assertSame(input, occurrence.source());
            for (var range : occurrence.location().segments()) range.validate(input.text());
            occurrence.exactRange().ifPresent(r -> assertFalse(r.text(input.text()).isEmpty()));
        }
        var mermaid = all.stream().filter(o -> o.kind() == MarkdownOccurrences.Kind.MERMAID).findFirst().orElseThrow();
        assertEquals("mermaid", mermaid.language().orElseThrow());
        assertTrue(mermaid.exactRange().isEmpty()); assertTrue(mermaid.openingMarker().isPresent());
        assertFalse(view.isCurrent(new MarkdownSemanticSnapshot.Source(input.documentId(), 1, input.text())));
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
    }

    @Test void inaccurateTableInlineLocationsAndUnsupportedAnalysisNeverBecomeOccurrences() {
        var table = new MarkdownOccurrences(new MarkdownSemanticParser().parse(source("| a\\|b [l](x) | d |\n|---|---|\n")));
        assertEquals(1, table.unavailableLinkLocations()); assertEquals(0, table.stream().count());
        assertFalse(table.sourceLocationsComplete());
        assertTrue(table.limitations().contains(MarkdownSemanticSnapshot.Limitation.TABLE_INLINE_LOCATIONS_UNAVAILABLE));
        var unsupported = new MarkdownOccurrences(MarkdownSemanticSnapshot.unavailable(source("[a](b)"), MarkdownSemanticSnapshot.Status.UNSUPPORTED, "test"));
        assertEquals(0, unsupported.stream().count()); assertEquals(MarkdownSemanticSnapshot.Coverage.NONE, unsupported.coverage());
    }

    @Test void repeatedRequestsAndSplitConsumersShareOneWorkerAndCachedRevision() throws Exception {
        var jobs = new AsyncJobService(Executors.newSingleThreadExecutor(), 10, null);
        CountDownLatch started = new CountDownLatch(1), gate = new CountDownLatch(1), done = new CountDownLatch(2);
        AtomicInteger parses = new AtomicInteger(); AtomicReference<MarkdownHeadingAnalysis> retained = new AtomicReference<>();
        var service = new MarkdownAnalysisService(jobs, (s, cancelled) -> {
            assertFalse(SwingUtilities.isEventDispatchThread()); parses.incrementAndGet(); started.countDown();
            try { assertTrue(gate.await(15, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return MarkdownHeadingAnalysis.analyze(s, cancelled);
        });
        try {
            var s = source("# heading");
            edt(() -> { for (int i = 0; i < 2; i++) service.request(new Object(), s, () -> true, r -> {
                assertTrue(SwingUtilities.isEventDispatchThread()); retained.set(r); done.countDown();
            }); });
            await(started); assertEquals(1, parses.get()); gate.countDown(); await(done);
            edt(() -> service.request(new Object(), s, () -> true, r -> assertSame(retained.get(), r)));
            assertEquals(1, parses.get()); edt(() -> assertEquals(2, service.diagnostics().reused()));
        } finally { edt(service::close); jobs.shutdownNow(); }
    }

    @Test void staleSupersededAndDisposedRequestsCannotInstallAndQueueRemainsBounded() throws Exception {
        var jobs = new AsyncJobService(Executors.newSingleThreadExecutor(), 10, null);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(), obsolete = new AtomicInteger(); AtomicBoolean current = new AtomicBoolean(true);
        var service = new MarkdownAnalysisService(jobs, (s, cancelled) -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                // Deliberately model a dependency which cannot stop immediately on interrupt.
                boolean released = false;
                while (!released) try { released = release.await(15, TimeUnit.SECONDS); if (!released) throw new AssertionError("Gate timeout"); }
                    catch (InterruptedException ignored) { }
            }
            return MarkdownHeadingAnalysis.analyze(s, () -> false);
        });
        Object owner = new Object();
        AtomicInteger newest = new AtomicInteger(-1);
        try {
            edt(() -> service.request(new Object(), source("# old"), current::get, r -> obsolete.incrementAndGet())); await(started);
            current.set(false);
            edt(() -> {
                for (int i = 0; i < 20; i++) {
                    int n = i;
                    newest.set(i);
                    service.request(owner, source("# new " + i), () -> n == newest.get(), r -> {
                        assertEquals("new 19", r.headings().getFirst().title()); done.countDown();
                    });
                }
                assertEquals(1, service.diagnostics().running()); assertTrue(service.diagnostics().pending() <= 1);
            });
            release.countDown(); await(done); assertEquals(0, obsolete.get()); assertEquals(2, calls.get());
            edt(() -> { assertTrue(service.diagnostics().staleRejected() > 0); service.close();
                service.request(owner, source("# disposed"), () -> true, r -> fail("Closed service delivered"));
                assertEquals(0, service.diagnostics().subscribers()); });
        } finally { release.countDown(); edt(service::close); jobs.shutdownNow(); }
    }

    @Test void cancelledQueuedWorkAndWorkerFailurePreserveDistinctOutcomes() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch blocker = new CountDownLatch(1), occupied = new CountDownLatch(1);
        executor.submit(() -> { occupied.countDown(); try { blocker.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } });
        await(occupied);
        var jobs = new AsyncJobService(executor, 10, null);
        var service = new MarkdownAnalysisService(jobs, (s, stop) -> { throw new IllegalStateException("injected"); });
        Object owner = new Object(); CountDownLatch failed = new CountDownLatch(1);
        try {
            edt(() -> { service.request(owner, source("# cancelled"), () -> true, r -> fail("Cancelled queued request installed")); service.cancel(owner); });
            edt(() -> service.request(owner, source("# fallback"), () -> true, r -> {
                assertEquals(MarkdownHeadingAnalysis.Path.LEGACY_FAILED, r.path()); failed.countDown();
            }));
            blocker.countDown(); await(failed);
        } finally { blocker.countDown(); edt(service::close); jobs.shutdownNow(); }
    }

    @Test void subscriberBudgetAndDocumentClosureReleaseAllOutstandingConsumers() throws Exception {
        var jobs = new AsyncJobService(Executors.newSingleThreadExecutor(), 10, null);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger(), rejected = new AtomicInteger();
        var service = new MarkdownAnalysisService(jobs, (s, stop) -> {
            started.countDown();
            try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return MarkdownHeadingAnalysis.analyze(s, stop);
        });
        try {
            var s = source("# bounded");
            edt(() -> {
                for (int i = 0; i < 8; i++) service.request(new Object(), s, () -> true, r -> delivered.incrementAndGet());
                service.request(new Object(), s, () -> true, r -> { assertEquals(MarkdownHeadingAnalysis.Path.CANCELLED, r.path()); rejected.incrementAndGet(); });
                assertEquals(8, service.diagnostics().subscribers());
            });
            await(started);
            edt(() -> { service.documentClosed(s.documentId()); assertEquals(0, service.diagnostics().subscribers()); });
            release.countDown();
            edt(service::close);
            assertEquals(0, delivered.get()); assertEquals(1, rejected.get());
        } finally { release.countDown(); edt(service::close); jobs.shutdownNow(); }
    }

    @Test void streamedLegacyTraversalMatchesExistingRulesAcrossChunksEofAndUnicode() {
        String text = "prose".repeat(1700) + "\n# first 😀\n\n" + "x".repeat(8190) + "\n## across chunk\n"
            + "   # indented\n#\n# \n```\n# legacy fenced\n```\n" + "\n## repeated\r\n".repeat(100) + "### EOF e\u0301";
        var s = source(text);
        var result = MarkdownHeadingAnalysis.from(MarkdownSemanticSnapshot.unavailable(s,
            MarkdownSemanticSnapshot.Status.UNSUPPORTED, "force existing legacy rules"), () -> false, System.nanoTime());
        var expected = new java.util.ArrayList<MarkdownHeadingAnalysis.Heading>();
        var legacy = new MarkdownService(); int offset = 0, number = 1;
        for (String line : text.split("\n", -1)) {
            int level = legacy.headingLevel(line);
            if (level > 0) expected.add(new MarkdownHeadingAnalysis.Heading(offset, number, level, legacy.headingText(line)));
            number++; offset += line.length() + 1;
        }
        assertEquals(expected, result.headings());
    }
}
