package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.MarkdownLink.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownLinkInfrastructureTest {
    @TempDir Path directory;
    Path file(String name, String text) throws Exception {
        Path path = directory.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, text); return path.toRealPath();
    }
    MarkdownSemanticSnapshot parse(String text) {
        return new MarkdownSemanticParser().parse(new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(10), 3, VersionedTextSnapshot.of(text)));
    }
    MarkdownLinkIndex.Snapshot build(MarkdownLinkIndex.Snapshot prior, MarkdownLinkIndex.Overlay... overlays) throws Exception {
        return MarkdownLinkIndex.build(directory, List.of(directory), prior, List.of(overlays), Set.of(), () -> false);
    }
    MarkdownLinkIndex.Overlay overlay(Path path, long id, long revision, String text) {
        return new MarkdownLinkIndex.Overlay(path, new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(id), revision, VersionedTextSnapshot.of(text)), Optional.empty());
    }
    @Test void wikiFormsHaveExactUnicodeRangesAndFields() {
        String source = "😀 [[note]] [[folder/note]] [[note.md]] [[note#Heading]] [[note|Display text]] [[目录/note#标题|别名]]";
        var result = parse(source); assertEquals(6, result.wikiLinks().size());
        for (var wiki : result.wikiLinks()) assertEquals("[[" + wiki.content().text(result.source().text()) + "]]", wiki.range().text(result.source().text()));
        var last = result.wikiLinks().getLast(); assertEquals("目录/note", last.path()); assertEquals("标题", last.fragment().orElseThrow()); assertEquals("别名", last.alias().orElseThrow());
    }
    @Test void escapedCodeDestinationsImagesHtmlAndFencesAreInactive() {
        var result = parse("\\[[escaped]] `[[inline]]` [x](a/[[dest]]) ![a [[alt]]](i.png) [a [[label]]](x.md)\n\n```\n[[fence]]\n```\n\n<div>[[html]]</div>\n\n[[real]]");
        assertEquals(List.of("real"), result.wikiLinks().stream().map(MarkdownSemanticSnapshot.WikiLink::path).toList());
    }
    @Test void malformedAndFormattingSpansStayUnavailable() {
        var result = parse("[[]] [[broken] [[a|]] [[a|b|c]] [[*formatted*]]\n\n| a | b |\n|---|---|\n| [[table]] | x |");
        assertTrue(result.wikiLinks().isEmpty()); assertTrue(result.unavailableWikiLocations() > 0);
        assertFalse(new MarkdownOccurrences(result).sourceLocationsComplete());
    }
    @Test void commonMarkReferenceCollisionDoesNotDuplicateWiki() {
        var result = parse("[[note]]\n\n[note]: x.md\n");
        var all = new MarkdownOccurrences(result).stream().toList();
        assertEquals(1, all.stream().filter(o -> o.kind() == MarkdownOccurrences.Kind.WIKI_LINK).count());
        assertEquals(0, all.stream().filter(o -> o.kind() == MarkdownOccurrences.Kind.LINK).count());
    }
    @Test void ordinaryReferenceImageExternalAndDuplicateEdges() throws Exception {
        Path target = file("target.md", "# One\n"); Path image = file("image.png", "fake");
        Path source = file("source.md", "[a](target.md#one) [b][r] ![i](image.png) [e](https://example.test) [a](target.md)\n\n[r]: target.md\n");
        var index = build(null); assertEquals(State.COMPLETE, index.state());
        assertEquals(6, index.outgoing(source).edges().size()); assertEquals(3, index.backlinks(target).edges().size());
        assertEquals(1, index.backlinks(image).edges().size());
        assertTrue(index.outgoing(source).edges().stream().anyMatch(e -> e.resolution().status() == Status.EXTERNAL));
        assertEquals(0, index.metrics().reused()); assertEquals(2, index.metrics().parsed());
    }
    @Test void uniqueAmbiguousAndQualifiedWikiResolution() throws Exception {
        Path a = file("one/note.md", "# Heading"); Path source = file("source.md", "[[note]] [[one/note#Heading|Alias]]");
        var first = build(null); assertEquals(2, first.backlinks(a).edges().size());
        file("two/note.md", "# Heading"); var next = build(first);
        assertEquals(Status.AMBIGUOUS, next.outgoing(source).edges().getFirst().resolution().status());
        assertEquals(2, next.outgoing(source).edges().getFirst().resolution().candidates().size());
        assertEquals(1, next.backlinks(a).edges().size()); assertEquals(2, next.metrics().reused());
    }
    @Test void ordinaryPathsRelativeToSourceAndWikiPathsRelativeToRoot() throws Exception {
        Path root = file("target.md", ""); Path nested = file("folder/target.md", "");
        Path source = file("folder/source.md", "[local](target.md) [parent](../target.md) [[folder/target]] [[./target]]");
        var index = build(null); assertEquals(3, index.backlinks(nested).edges().size()); assertEquals(1, index.backlinks(root).edges().size());
        assertEquals(Status.RESOLVED, index.outgoing(source).edges().getLast().resolution().status());
    }
    @Test void duplicateHeadingTextIsAmbiguousButExactAnchorsAreDistinct() throws Exception {
        Path target = file("note.md", "# Duplicate\n# Duplicate\n"); Path source = file("source.md", "[[note#Duplicate]] [second](note.md#duplicate-1) [missing](note.md#none)");
        var edges = build(null).outgoing(source).edges();
        assertEquals(Status.AMBIGUOUS_HEADING, edges.get(0).resolution().status());
        assertEquals(Status.RESOLVED, edges.get(1).resolution().status()); assertEquals(12, edges.get(1).resolution().heading().orElseThrow().offset());
        assertEquals(Status.MISSING_HEADING, edges.get(2).resolution().status()); assertEquals(target, edges.get(1).resolution().target().orElseThrow());
    }
    @Test void missingOutOfScopeUnsafeAndQueryDestinationsStayExplicit() throws Exception {
        Path source = file("source.md", "[[missing]] [out](../elsewhere.md) [bad](javascript:alert) [query](a.md?q=1) [file](file:///tmp/a)");
        assertEquals(List.of(Status.MISSING, Status.OUT_OF_SCOPE, Status.UNSUPPORTED, Status.UNSUPPORTED, Status.UNSUPPORTED),
            build(null).outgoing(source).edges().stream().map(e -> e.resolution().status()).toList());
    }
    @Test void percentEncodedOrdinaryAndLiteralWikiUnicodePathsResolve() throws Exception {
        Path target = file("你好 +100%.md", "# 标题"); Path source = file("source.md", "[x](%E4%BD%A0%E5%A5%BD%20+100%25.md#%E6%A0%87%E9%A2%98) [[你好 +100%.md#标题]]");
        var index = build(null); assertEquals(2, index.backlinks(target).edges().size()); assertEquals(2, index.outgoing(source).edges().size());
    }
    @Test void nativeCaseSensitivityIsRespected() throws Exception {
        Path target = file("Note.md", ""); Path source = file("source.md", "[[note]] [x](note.md)");
        boolean insensitive = Files.exists(directory.resolve("note.md"));
        for (var edge : build(null).outgoing(source).edges()) assertEquals(insensitive ? Status.RESOLVED : Status.MISSING, edge.resolution().status());
        assertTrue(Files.exists(target));
    }
    @Test void symlinkTargetsCannotEscapeAndDiscoveryDoesNotFollowLinks() throws Exception {
        Path target = file("note.md", ""); Path outside = Files.createTempFile("shed-link-outside", ".md");
        try {
            Files.createSymbolicLink(directory.resolve("alias.md"), target); Files.createSymbolicLink(directory.resolve("escape.md"), outside);
            Path source = file("source.md", "[a](alias.md) [b](escape.md)"); var index = build(null);
            assertEquals(2, index.effective().size()); assertEquals(1, index.backlinks(target).edges().size());
            assertEquals(Status.OUT_OF_SCOPE, index.outgoing(source).edges().getLast().resolution().status());
        } finally { Files.deleteIfExists(outside); }
    }
    @Test void hardlinkAliasesAreReportedRatherThanIndexedTwice() throws Exception {
        Path target = file("a.md", "[[b]]"); Files.createLink(directory.resolve("alias.md"), target); file("b.md", "");
        var index = build(null); assertEquals(State.PARTIAL, index.state()); assertEquals(State.CONFLICT, index.effective().get(target).state());
        Files.delete(directory.resolve("alias.md")); assertEquals(State.COMPLETE, build(index).state());
    }
    @Test void nestedProjectPartitionsDoNotCrossResolve() throws Exception {
        Path target = file("nested/target.md", ""); Path source = file("source.md", "[[target]] [x](nested/target.md)");
        var index = MarkdownLinkIndex.build(directory, List.of(directory, target.getParent()), null, List.of(), Set.of(), () -> false);
        assertEquals(1, index.effective().size()); assertEquals(Status.OUT_OF_SCOPE, index.outgoing(source).edges().getLast().resolution().status());
    }
    @Test void unsavedOverlaySaveAndDiscardReconcileWithoutDuplicates() throws Exception {
        Path a = file("a.md", "[[b]]"); Path b = file("b.md", ""); Path c = file("c.md", "");
        var disk = build(null); var dirty = build(disk, overlay(a, 1, 5, "[[c]]"));
        assertTrue(dirty.backlinks(b).edges().isEmpty()); assertEquals(1, dirty.backlinks(c).edges().size());
        assertEquals(1, dirty.backlinks(c).edges().getFirst().occurrence().source().document().orElseThrow().value());
        var discarded = build(dirty); assertEquals(1, discarded.backlinks(b).edges().size()); assertTrue(discarded.backlinks(c).edges().isEmpty());
        Files.writeString(a, "[[c]]"); var saved = build(dirty); assertEquals(1, saved.backlinks(c).edges().size());
        assertTrue(saved.backlinks(c).edges().getFirst().occurrence().source().document().isEmpty());
    }
    @Test void splitIdentityDeduplicatesAndConflictingBuffersDoNotChooseArbitrarily() throws Exception {
        Path a = file("a.md", ""); Path b = file("b.md", ""); var overlay = overlay(a, 1, 1, "[[b]]");
        assertEquals(1, build(null, overlay, overlay).backlinks(b).edges().size());
        var second = overlay(a, 2, 1, "[[b]]"); var conflict = build(null, overlay, second);
        assertEquals(State.CONFLICT, conflict.effective().get(a).state()); assertTrue(conflict.backlinks(b).edges().isEmpty());
        assertEquals(1, build(conflict, second).backlinks(b).edges().size());
    }
    @Test void reusedRevisionAvoidsParsingAndRenameDeleteAdditionRebuildEdges() throws Exception {
        Path a = file("a.md", "[[b]]"); Path b = file("b.md", "[[a]]"); var first = build(null); var warm = build(first);
        assertEquals(0, warm.metrics().parsed()); assertEquals(2, warm.metrics().reused());
        Files.move(b, directory.resolve("renamed.md")); var renamed = build(warm);
        assertTrue(renamed.backlinks(b).edges().isEmpty()); assertEquals(Status.MISSING, renamed.outgoing(a).edges().getFirst().resolution().status());
        Files.delete(a); assertEquals(1, build(renamed).effective().size());
    }
    @Test void capAndUnavailableTableSpansCannotClaimCompleteEmptyBacklinks() throws Exception {
        Path large = file("large.md", "x".repeat(MarkdownSemanticParser.MAX_CHARACTERS + 1));
        Path source = file("source.md", "| a | b |\n|---|---|\n| x\\|y | [x](large.md) |");
        var index = build(null); assertEquals(State.PARTIAL, index.state()); assertEquals(2, index.incomplete().size());
        assertEquals(State.UNSUPPORTED, index.effective().get(large).state()); assertTrue(index.backlinks(large).edges().isEmpty());
        assertEquals(State.PARTIAL, index.backlinks(large).state()); assertTrue(index.outgoing(source).edges().isEmpty());
    }
    @Test void cancelledPassNeverPublishesPartialSuccessAndEdtBuildIsRejected() throws Exception {
        file("a.md", "[[b]]"); assertThrows(CancellationException.class, () -> MarkdownLinkIndex.build(directory, List.of(directory), null, List.of(), Set.of(), () -> true));
        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, () -> MarkdownLinkIndex.build(directory, List.of(directory), null, List.of(), Set.of(), () -> false)));
    }
    @Test void unsupportedSemanticStatesProduceNoFabricatedFacts() {
        for (var status : List.of(MarkdownSemanticSnapshot.Status.UNSUPPORTED, MarkdownSemanticSnapshot.Status.FAILED, MarkdownSemanticSnapshot.Status.CANCELLED)) {
            var source = parse("").source(); var facts = MarkdownLink.project(new Origin(directory.resolve("a.md"), Optional.empty(), "x"), MarkdownSemanticSnapshot.unavailable(source, status, "test"));
            assertTrue(facts.links().isEmpty()); assertNotEquals(State.COMPLETE, facts.state());
        }
    }
    @Test void boundedDiscoveryAndRetentionReportPartial() throws Exception {
        for (int i = 0; i < 1026; i++) file("file" + i + ".md", "");
        var index = build(null); assertEquals(1024, index.effective().size()); assertEquals(State.PARTIAL, index.state());
    }
    @Test void dirtySnapshotsAndSourceBytesAreUnchangedByIndexing() throws Exception {
        Path path = file("a.md", "\ufeff# 😀\r\n[[b]]\r\n"); byte[] before = Files.readAllBytes(path);
        var source = overlay(path, 1, 9, "[[a]]"); var text = source.source().text(); build(null, source);
        assertArrayEquals(before, Files.readAllBytes(path)); assertSame(text, source.source().text()); assertEquals(9, source.source().revision());
        assertEquals(List.of(path), Files.list(directory.toRealPath()).toList());
    }
    @Test void facadeIsLazyCoalescesUnchangedRequestsAndDisposes() throws Exception {
        Path a = file("a.md", "[[a]]"); var service = new AtomicReference<MarkdownProjectLinks>(); CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            var links = new MarkdownProjectLinks(() -> List.of(directory), List::of); service.set(links);
            assertEquals(0, links.diagnostics().submitted()); links.onChanged(done::countDown);
            assertTrue(links.request(directory)); for (int i = 0; i < 20; i++) links.request(directory);
        });
        assertTrue(done.await(20, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> {
            var links = service.get(); assertEquals(State.COMPLETE, links.backlinks(directory, a).state()); assertEquals(1, links.backlinks(directory, a).edges().size());
            assertEquals(1, links.diagnostics().submitted()); links.close(); assertEquals(State.CLOSED, links.backlinks(directory, a).state());
            assertEquals(0, links.diagnostics().partitions());
        });
    }
    @Test void facadeRejectsOutOfOrderWorkAndRemovesPendingEdgesImmediately() throws Exception {
        Path a = file("a.md", "[[a]]"); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); var sources = new AtomicReference<List<MarkdownLinkIndex.Overlay>>(List.of(overlay(a, 1, 1, "[[a]]")));
        var service = new AtomicReference<MarkdownProjectLinks>();
        SwingUtilities.invokeAndWait(() -> {
            var links = new MarkdownProjectLinks(() -> List.of(directory), sources::get, (root, roots, previous, overlays, force, stop) -> {
                if (calls.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS)); }
                return MarkdownLinkIndex.build(root, roots, previous, overlays, force, () -> false);
            }); service.set(links); links.onChanged(() -> { if (links.diagnostics().running() == 0 && links.diagnostics().pending() == 0) done.countDown(); }); links.request(directory);
        });
        assertTrue(entered.await(20, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> {
            sources.set(List.of(overlay(a, 1, 2, "no link"))); service.get().documentsChanged(a, false);
            assertEquals(State.PENDING, service.get().backlinks(directory, a).state()); assertTrue(service.get().backlinks(directory, a).edges().isEmpty());
        }); release.countDown(); assertTrue(done.await(20, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { var links = service.get(); assertTrue(links.backlinks(directory, a).edges().isEmpty()); assertEquals(State.COMPLETE, links.backlinks(directory, a).state()); assertEquals(1, links.diagnostics().staleRejected()); links.close(); });
    }
    @Test void snapshotIdentityPreventsSameRevisionReuseAfterReplacement() throws Exception {
        Path a = file("a.md", ""); Path b = file("b.md", "");
        var old = build(null, overlay(a, 1, 3, "[[b]]")); var next = build(old, overlay(a, 1, 3, "no links"));
        assertTrue(next.backlinks(b).edges().isEmpty()); assertEquals(1, next.metrics().parsed());
    }
    @Test void currentOverlayAndAvailableSemanticAreReusedWithoutAnotherParse() throws Exception {
        Path a = file("a.md", ""); var overlay = overlay(a, 1, 3, "[[a]]");
        var semantic = new MarkdownSemanticParser().parse(overlay.source());
        var available = new MarkdownLinkIndex.Overlay(a, overlay.source(), Optional.of(semantic));
        var first = build(null, available); assertEquals(1, first.metrics().parsed()); assertEquals(1, first.metrics().reused());
        var second = build(first, available); assertEquals(0, second.metrics().parsed()); assertEquals(1, second.backlinks(a).edges().size());
        var cancelled = MarkdownSemanticSnapshot.unavailable(overlay.source(), MarkdownSemanticSnapshot.Status.CANCELLED, "injected");
        var interrupted = build(null, new MarkdownLinkIndex.Overlay(a, overlay.source(), Optional.of(cancelled)));
        assertEquals(1, build(interrupted, available).backlinks(a).edges().size());
        var stale = new MarkdownLinkIndex.Overlay(a, overlay(a, 1, 4, "empty").source(), Optional.of(semantic));
        assertTrue(build(second, stale).backlinks(a).edges().isEmpty());
    }
    @Test void eligible100KiBAnd1MiBSourcesRemainComplete() throws Exception {
        String line = "prose ".repeat(170) + "\n";
        for (int size : List.of(100 * 1024, 1024 * 1024)) {
            String text = "# Heading\n[[target]]\n" + line.repeat(size / line.length());
            file("source" + size + ".md", text.substring(0, Math.min(text.length(), size)));
        }
        Path target = file("target.md", ""); var index = build(null);
        assertEquals(State.COMPLETE, index.state()); assertEquals(2, index.backlinks(target).edges().size());
    }
    @Test void structuralAndRecordBudgetsKeepPartialCoverageExplicit() throws Exception {
        Path target = file("target.md", ""); Path source = file("source.md", "[[target]] ".repeat(2100));
        var index = build(null); assertEquals(State.PARTIAL, index.state()); assertEquals(2048, index.backlinks(target).edges().size());
        assertEquals(List.of(source), index.incomplete());
        file("dense.md", ("[x](x) ".repeat(100) + "\n").repeat(500));
        var dense = build(index); assertEquals(State.UNSUPPORTED, dense.effective().get(directory.resolve("dense.md").toRealPath()).state());
    }
    @Test void nativeRootAliasAndExplicitDiskInvalidationAreHandled() throws Exception {
        Path source = file("a.md", "[[b]]"); Path target = file("b.md", ""); var first = build(null);
        assertEquals(1, first.outgoing(directory.resolve("a.md")).edges().size());
        var modified = Files.getLastModifiedTime(source); Files.writeString(source, "empty "); Files.setLastModifiedTime(source, modified);
        var refreshed = MarkdownLinkIndex.build(directory, List.of(directory), first, List.of(), Set.of(source), () -> false);
        assertTrue(refreshed.backlinks(target).edges().isEmpty()); assertEquals(1, refreshed.metrics().parsed());
    }
    @Test void invalidUtf8PercentEncodingIsUnsupported() throws Exception {
        Path source = file("a.md", "[bad](%FF.md) [bad](%XY.md)");
        for (var edge : build(null).outgoing(source).edges()) assertEquals(Status.UNSUPPORTED, edge.resolution().status());
    }
    @Test void realBufferRevisionUndoDirtyAndEventsArePreserved() throws Exception {
        Path path = file("a.md", "# heading\n[[a]]\n"); var captured = new AtomicReference<MarkdownLinkIndex.Overlay>(); var buffer = new AtomicReference<FileBuffer>();
        var events = new AtomicInteger(); long[] revision = new long[1]; int[] undo = new int[1]; String[] saved = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                var value = new FileBuffer(path.toFile()); buffer.set(value); value.getDocument().insertString(value.getDocument().getLength(), "draft", null); value.setModified(true);
                value.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) { events.incrementAndGet(); }
                    public void removeUpdate(javax.swing.event.DocumentEvent e) { events.incrementAndGet(); }
                    public void changedUpdate(javax.swing.event.DocumentEvent e) { events.incrementAndGet(); }
                });
                revision[0] = value.documentRevision(); undo[0] = ((BoundedUndoManager) value.getUndoManager()).retainedEditCount(); saved[0] = value.getSavedContent();
                captured.set(new MarkdownLinkIndex.Overlay(path, MarkdownSemanticSnapshot.Source.capture(new DocumentWorkspace.Id(1), value), Optional.empty()));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        var index = build(null, captured.get()); assertEquals(1, index.backlinks(path).edges().size());
        SwingUtilities.invokeAndWait(() -> {
            var value = buffer.get(); assertEquals(0, events.get()); assertEquals(revision[0], value.documentRevision()); assertTrue(value.isModified());
            assertEquals(undo[0], ((BoundedUndoManager) value.getUndoManager()).retainedEditCount()); assertEquals(saved[0], value.getSavedContent());
            assertSame(captured.get().source().text(), value.textSnapshot());
        });
    }
    @Test void facadeCancellationScopeReopenAndFailureAreExplicit() throws Exception {
        Path a = file("a.md", "[[a]]"); var service = new AtomicReference<MarkdownProjectLinks>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            var links = new MarkdownProjectLinks(() -> List.of(directory), List::of, (root, roots, old, sources, force, stop) -> {
                if (calls.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS)); }
                return MarkdownLinkIndex.build(root, roots, old, sources, force, stop);
            }); service.set(links); links.onChanged(() -> { if (links.diagnostics().running() == 0 && links.diagnostics().pending() == 0) done.countDown(); }); links.request(directory);
        });
        assertTrue(entered.await(20, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> {
            var links = service.get(); links.cancel(directory); assertEquals(State.CANCELLED, links.backlinks(directory, a).state());
            links.closeScope(directory); assertEquals(0, links.diagnostics().partitions()); assertTrue(links.request(directory));
        }); release.countDown(); assertTrue(done.await(20, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { assertEquals(1, service.get().backlinks(directory, a).edges().size()); service.get().close(); });
        CountDownLatch failed = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            var links = new MarkdownProjectLinks(() -> List.of(directory), List::of, (root, roots, old, sources, force, stop) -> { throw new IllegalStateException("injected"); });
            service.set(links); links.onChanged(failed::countDown); links.request(directory);
        }); assertTrue(failed.await(20, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> {
            assertEquals(State.FAILED, service.get().backlinks(directory, a).state()); assertTrue(service.get().backlinks(directory, a).edges().isEmpty()); service.get().close();
        });
    }
    @Test void facadeLimitsPartitionsAndPendingWorkWithoutEagerScan() throws Exception {
        var roots = new ArrayList<Path>(); for (int i = 0; i < 5; i++) roots.add(Files.createDirectories(directory.resolve("root" + i)));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); var service = new AtomicReference<MarkdownProjectLinks>();
        SwingUtilities.invokeAndWait(() -> {
            var links = new MarkdownProjectLinks(() -> roots, List::of, (root, configured, old, sources, force, stop) -> {
                entered.countDown(); release.await(20, TimeUnit.SECONDS); return MarkdownLinkIndex.build(root, configured, old, sources, force, stop);
            }); service.set(links); assertEquals(0, links.diagnostics().submitted());
            for (int i = 0; i < 4; i++) assertTrue(links.request(roots.get(i))); assertFalse(links.request(roots.get(4)));
            assertEquals(1, links.diagnostics().running()); assertEquals(3, links.diagnostics().pending()); assertEquals(1, links.diagnostics().submitted());
        }); assertTrue(entered.await(20, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> service.get().close()); release.countDown();
    }
    @Test void opaqueInlineHtmlAndDeferredMathDoNotInventWikiLocations() {
        for (String input : List.of("<span>x</span> [[note]]", "$[[note]]$", "$$\n[[note]]\n$$")) {
            var semantic = parse(input); assertTrue(semantic.wikiLinks().isEmpty()); assertTrue(semantic.unavailableWikiLocations() > 0);
            assertFalse(new MarkdownOccurrences(semantic).sourceLocationsComplete());
        }
    }
    @Test void missingHeadingStillRetainsVerifiedFileBacklink() throws Exception {
        Path target = file("note.md", "# Title"); file("source.md", "[x](note.md#absent)");
        var edges = build(null).backlinks(target).edges(); assertEquals(1, edges.size()); assertEquals(Status.MISSING_HEADING, edges.getFirst().resolution().status());
    }
    @Test void incrementalEdgesReuseOnlyUnchangedCatalogTargets() throws Exception {
        Path target = file("note.md", "# One"); Path source = file("source.md", "[x](note.md#one)");
        var first = build(null); var warm = build(first); assertEquals(1, warm.metrics().reusedEdges());
        assertSame(first.outgoing(source).edges().getFirst(), warm.outgoing(source).edges().getFirst());
        Files.writeString(target, "# Changed"); var changed = build(warm);
        assertEquals(Status.MISSING_HEADING, changed.outgoing(source).edges().getFirst().resolution().status()); assertEquals(0, changed.metrics().reusedEdges());
    }
    @Test void symlinkRetargetingCannotReuseAnOldEdge() throws Exception {
        Path first = file("first.md", ""); Path second = file("second.md", "");
        Path alias = directory.resolve("alias.md"); Files.createSymbolicLink(alias, first);
        Path source = file("source.md", "[x](alias.md)"); var index = build(null);
        Files.delete(alias); Files.createSymbolicLink(alias, second); var next = build(index);
        assertEquals(second, next.outgoing(source).edges().getFirst().resolution().target().orElseThrow()); assertEquals(0, next.metrics().reusedEdges());
    }
    @Test void previewMathAnchorDifferencesStayExplicitlyUnsupported() throws Exception {
        Path target = file("note.md", "# $x$\n# Title"); file("source.md", "[x](note.md#title)");
        var index = build(null); assertEquals(State.PARTIAL, index.state());
        assertEquals(Status.HEADING_UNSUPPORTED, index.backlinks(target).edges().getFirst().resolution().status());
    }
}
