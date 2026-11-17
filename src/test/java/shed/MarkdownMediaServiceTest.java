package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.MarkdownMedia.*;
import static shed.MarkdownSemanticSnapshot.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownMediaServiceTest {
    @TempDir Path temp;
    private Source source(String text) { return new Source(new DocumentWorkspace.Id(1), 7, VersionedTextSnapshot.of(text)); }
    private MarkdownSemanticSnapshot semantic(Source source) { return new MarkdownSemanticParser().parse(source); }
    private Plan diagram(Source source) { var sem = semantic(source); return mermaid(sem, source, sem.codeBlocks().getFirst(), Options.preview(), 1); }
    private Plan formula(Source source) { var sem = semantic(source); return math(sem, source, sem.blocks().stream().filter(b -> b.kind() == BlockKind.PARAGRAPH).findFirst().orElseThrow(), Options.preview(), 1); }
    private Plan picture(Source source) { var sem = semantic(source); return image(sem, source, sem.links().getFirst(), Options.preview(), new Context(temp.resolve("note.md"), List.of(temp)), 1); }
    private void idle(MarkdownMediaService service) throws Exception { assertTrue(service.awaitIdle(30, TimeUnit.SECONDS), service.metrics().toString()); }
    private void png(Path file, int width, int height, int color) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, color); ImageIO.write(image, "png", file.toFile()); image.flush();
    }
    private static final class Gate implements MarkdownMediaService.Renderer {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
        final boolean failFirst;
        Gate() { this(false); }
        Gate(boolean failFirst) { this.failFirst = failFirst; }
        @Override public MarkdownMediaService.Prepared prepare(Request request, java.util.function.BooleanSupplier stop) {
            assertFalse(SwingUtilities.isEventDispatchThread()); return new MarkdownMediaService.Prepared(request.key(), request, null);
        }
        @Override public BufferedImage render(MarkdownMediaService.Prepared prepared, java.util.function.BooleanSupplier stop) throws Exception {
            int number = calls.incrementAndGet(); peak.accumulateAndGet(active.incrementAndGet(), Math::max); entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS)); // intentionally ignores cancellation, like noninterruptible rendering
                if (number == 1 && failFirst) throw new IllegalStateException("renderer failed");
                return new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
            } finally { active.decrementAndGet(); }
        }
    }
    private MarkdownMediaService fake(Gate renderer) { return new MarkdownMediaService(MarkdownMediaService.Budget.desktop(), renderer); }

    @Test void plansCaptureExactRevisionRangesAndOnlySmallPayloads() {
        var source = source("😀\n\n```mermaid\r\ngraph TD\r\nA-->B\r\n```\r\n"); var plan = diagram(source); var request = plan.request().orElseThrow();
        assertEquals(State.PENDING, plan.state()); assertEquals("graph TD\r\nA-->B\r\n", request.content());
        assertEquals(request.content(), request.contentRange().orElseThrow().text(source.text()));
        assertTrue(request.stamp().matches(source, 1)); assertFalse(request.stamp().matches(source, 2));
        assertFalse(request.stamp().matches(new Source(new DocumentWorkspace.Id(2), 7, source.text()), 1));
        assertFalse(request.stamp().matches(new Source(source.documentId(), 7, VersionedTextSnapshot.of(source.text().text())), 1));
        assertThrows(UnsupportedOperationException.class, () -> request.ranges().clear());
        assertNotEquals(hash("\ud800"), hash("\ud801"));
    }
    @Test void staleFailedCancelledIncompleteAndUnavailableRangesAreExplicit() {
        var source = source("![x](x.png)"); var semantic = semantic(source);
        var current = new Source(source.documentId(), 8, source.text());
        assertEquals(State.SUPERSEDED, image(semantic, current, semantic.links().getFirst(), Options.preview(), new Context(temp.resolve("x.md"), List.of(temp)), 1).state());
        for (var status : List.of(Status.FAILED, Status.CANCELLED, Status.UNSUPPORTED)) {
            var missing = MarkdownSemanticSnapshot.unavailable(source, status, "no coverage");
            assertTrue(image(missing, source, semantic.links().getFirst(), Options.preview(), new Context(temp.resolve("x.md"), List.of(temp)), 1).request().isEmpty());
        }
        var table = semantic(source("| image |\n| --- |\n| ![x](x.png) |"));
        assertEquals(State.UNSUPPORTED, image(table, table.source(), table.links().getFirst(), Options.preview(), new Context(temp.resolve("x.md"), List.of(temp)), 1).state());
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSourceRange(1, 2).validate(VersionedTextSnapshot.of("😀")));
    }
    @Test void unsupportedAndOverCapSourceDoesNotAcquireAMediaRequest() {
        var large = source("x".repeat(1024 * 1024 + 1)); var sem = semantic(large);
        assertEquals(Status.UNSUPPORTED, sem.status());
        assertEquals(State.UNSUPPORTED, math(sem, large, new Block(BlockKind.PARAGRAPH, new Location(List.of())), Options.preview(), 1).state());
        assertEquals(State.UNSUPPORTED, diagram(source("```mermaid\ngraph TD\nA-->B")).state());
        assertEquals(State.UNSUPPORTED, diagram(source("> ```mermaid\n> graph TD\n> A-->B\n> ```")).state());
        assertEquals(State.RESOURCE_LIMIT, diagram(source("```mermaid\n" + "A\n".repeat(33000) + "```\n")).state());
    }
    @Test void mathAdapterRequiresAWholeVerifiedStandaloneExpression() {
        for (String input : List.of("$x^2$", "$$x^2$$", "\\(x^2\\)", "\\[x^2\\]")) {
            var source = source(input); var plan = formula(source); assertEquals(State.PENDING, plan.state(), input);
            assertEquals("x^2", plan.request().orElseThrow().content());
        }
        for (String input : List.of("words $x$", "`$x$`", "\\$x$", "$x$ $y$", "$$\nx\n$$", "$ x $")) {
            assertEquals(State.UNSUPPORTED, formula(source(input)).state(), input);
        }
        assertTrue(semantic(source("```\n$x$\n```" )).blocks().stream().noneMatch(b -> b.kind() == BlockKind.PARAGRAPH));
    }
    @Test void duplicateInflightRequestsAcrossSplitsRenderOnlyOnce() throws Exception {
        var renderer = new Gate(); var source = source("$x$");
        try (var service = fake(renderer); var first = service.open(source, 1); var split = service.open(source, 1)) {
            var a = first.request(formula(source)); assertTrue(renderer.entered.await(10, TimeUnit.SECONDS));
            var b = split.request(formula(source)); assertEquals(State.PENDING, b.state()); renderer.release.countDown(); idle(service);
            assertEquals(1, renderer.calls.get()); assertEquals(1, service.metrics().coalesced());
            try (var x = a.take(source, 1); var y = b.take(source, 1)) {
                assertEquals(State.READY, x.state()); assertEquals(State.READY, y.state()); assertEquals(1, service.metrics().resident());
                assertTrue(x.lease().orElseThrow().isCurrent(source, 1)); assertEquals(4, y.lease().orElseThrow().width());
            }
            assertEquals(0, service.metrics().consumers());
        }
    }
    @Test void cancelledNoninterruptibleWorkKeepsItsPhysicalWorkerSlot() throws Exception {
        var renderer = new Gate(); var a = source("$x$"); var b = source("$y$");
        try (var service = fake(renderer); var session = service.open(a, 1)) {
            var old = session.request(formula(a)); assertTrue(renderer.entered.await(10, TimeUnit.SECONDS));
            session.update(b, 1); var latest = session.request(formula(b));
            assertEquals(State.SUPERSEDED, old.state()); assertEquals(1, service.metrics().running()); assertEquals(1, service.metrics().queued());
            renderer.release.countDown(); idle(service);
            assertEquals(1, renderer.peak.get()); assertEquals(State.READY, latest.state()); assertEquals(1, service.metrics().cached());
            latest.close();
        }
    }
    @Test void cancellingOneDuplicateDoesNotCancelTheOther() throws Exception {
        var renderer = new Gate(); var source = source("$x$");
        try (var service = fake(renderer); var session = service.open(source, 1)) {
            var a = session.request(formula(source)); assertTrue(renderer.entered.await(10, TimeUnit.SECONDS));
            var b = session.request(formula(source)); a.close(); renderer.release.countDown(); idle(service);
            assertEquals(State.CANCELLED, a.state()); assertEquals(State.READY, b.state()); b.close(); assertEquals(1, renderer.calls.get());
        }
    }
    @Test void repeatedIdenticalRequestAfterAbandonmentGetsFreshWork() throws Exception {
        var renderer = new Gate(); var source = source("$x$");
        try (var service = fake(renderer); var session = service.open(source, 1)) {
            var a = session.request(formula(source)); assertTrue(renderer.entered.await(10, TimeUnit.SECONDS)); a.close();
            var b = session.request(formula(source)); renderer.release.countDown(); idle(service);
            assertEquals(State.READY, b.state()); assertEquals(2, renderer.calls.get()); b.close();
        }
    }
    @Test void queueAndConsumerAdmissionAreBoundedAndNonblockingOnEdt() throws Exception {
        var renderer = new Gate(); var initial = source("$x$");
        var budget = new MarkdownMediaService.Budget(2, 4, 4, 4, 1024, 2048, 512);
        try (var service = new MarkdownMediaService(budget, renderer); var session = service.open(initial, 1)) {
            session.request(formula(initial)); assertTrue(renderer.entered.await(10, TimeUnit.SECONDS));
            var others = new ArrayList<MarkdownMediaService.Session>();
            for (String text : List.of("$a$", "$b$")) { var source = source(text); var other = service.open(source, 1); others.add(other); other.request(formula(source)); }
            var third = source("$c$"); var other = service.open(third, 1);
            var result = new java.util.concurrent.atomic.AtomicReference<MarkdownMediaService.Handle>();
            SwingUtilities.invokeAndWait(() -> result.set(other.request(formula(third))));
            assertEquals(State.RESOURCE_LIMIT, result.get().state()); assertEquals(2, service.metrics().queued());
            others.forEach(MarkdownMediaService.Session::close); other.close(); assertEquals(0, service.metrics().queued());
            renderer.release.countDown(); idle(service); assertEquals(1, renderer.calls.get());
        }
    }
    @Test void sourceChangesElsewhereAndUndoRevisionCannotConsumeOldResult() throws Exception {
        var renderer = new Gate(); renderer.release.countDown(); var source = source("$x$");
        try (var service = fake(renderer); var session = service.open(source, 1)) {
            var handle = session.request(formula(source)); idle(service);
            var revised = new Source(source.documentId(), 9, source.text());
            assertEquals(State.SUPERSEDED, handle.take(revised, 1).state()); assertEquals(0, service.metrics().consumers());
            var next = session.request(formula(source)); idle(service); assertEquals(State.SUPERSEDED, next.take(source, 2).state());
        }
    }
    @Test void documentSwitchCloseAndServiceShutdownReleaseHandlesAndLeases() throws Exception {
        var renderer = new Gate(); renderer.release.countDown(); var source = source("$x$");
        var service = fake(renderer); var session = service.open(source, 1);
        var handle = session.request(formula(source)); idle(service); var result = handle.take(source, 1); var lease = result.lease().orElseThrow();
        session.update(new Source(new DocumentWorkspace.Id(2), 7, source.text()), 1);
        assertFalse(lease.isCurrent(source, 1)); assertThrows(IllegalStateException.class, lease::width);
        service.close(); assertEquals(0, service.metrics().sessions()); assertEquals(0, service.metrics().residentBytes());
        assertEquals(State.CLOSED, session.request(formula(source)).state()); assertThrows(IllegalStateException.class, () -> service.open(source, 1));
    }
    @Test void shutdownDoesNotPretendToInterruptAStuckRenderer() throws Exception {
        var renderer = new Gate(); var source = source("$x$"); var service = fake(renderer); var session = service.open(source, 1);
        var handle = session.request(formula(source)); assertTrue(renderer.entered.await(10, TimeUnit.SECONDS));
        service.close(); assertEquals(1, service.metrics().running()); assertEquals(State.CLOSED, handle.state());
        renderer.release.countDown(); idle(service); assertEquals(0, service.metrics().running()); assertEquals(0, service.metrics().residentBytes());
    }
    @Test void cacheEvictionPreservesPinnedAssetsAndEnforcesResidentBudget() throws Exception {
        var renderer = new Gate(); renderer.release.countDown(); var source = source("$x$");
        var budget = new MarkdownMediaService.Budget(2, 8, 8, 1, 64, 64, 16);
        try (var service = new MarkdownMediaService(budget, renderer); var session = service.open(source, 1)) {
            var first = session.request(formula(source)); idle(service); var result = first.take(source, 1);
            var other = source("$y$"); var second = service.open(other, 1); var pending = second.request(formula(other)); idle(service);
            assertEquals(State.RESOURCE_LIMIT, pending.state()); assertEquals(64, service.metrics().residentBytes()); assertEquals(4, result.lease().orElseThrow().width());
            result.close(); var retry = second.request(formula(other)); idle(service); assertEquals(State.READY, retry.state());
            assertEquals(1, service.metrics().cached()); assertEquals(1, service.metrics().resident()); retry.close(); second.close();
        }
    }
    @Test void completedResultReuseDoesNotSkipRevisionValidation() throws Exception {
        var renderer = new Gate(); renderer.release.countDown(); var source = source("$x$");
        try (var service = fake(renderer); var session = service.open(source, 1)) {
            var first = session.request(formula(source)); idle(service); first.close();
            var hit = session.request(formula(source)); idle(service); assertEquals(1, renderer.calls.get()); assertEquals(1, service.metrics().hits());
            assertEquals(State.SUPERSEDED, hit.take(new Source(source.documentId(), 8, source.text()), 1).state());
        }
    }
    @Test void failureIsNotNegativelyCachedAndLaterValidWorkSucceeds() throws Exception {
        var renderer = new Gate(true); renderer.release.countDown(); var source = source("$x$");
        try (var service = fake(renderer); var session = service.open(source, 1)) {
            var failed = session.request(formula(source)); idle(service); assertEquals(State.FAILED, failed.state()); assertEquals(0, service.metrics().cached());
            var valid = session.request(formula(source)); idle(service); assertEquals(State.READY, valid.state()); valid.close(); assertEquals(2, renderer.calls.get());
        }
    }
    @Test void localImageDecodeReuseAndIndependentPaintOwnership() throws Exception {
        png(temp.resolve("image.png"), 12, 8, Color.RED.getRGB()); var source = source("![alt](image.png)");
        try (var service = new MarkdownMediaService(); var session = service.open(source, 1)) {
            var handle = session.request(picture(source)); idle(service);
            try (var result = handle.take(source, 1)) {
                assertEquals(State.READY, result.state(), result.detail()); var lease = result.lease().orElseThrow();
                assertEquals(12, lease.width()); assertEquals(8, lease.height());
                assertThrows(NullPointerException.class, () -> lease.paint(null, 0, 0));
                var canvas = new BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB); var g = canvas.createGraphics(); lease.paint(g, 0, 0); g.dispose();
                assertEquals(Color.RED.getRGB(), canvas.getRGB(0, 0));
                canvas.setRGB(0, 0, Color.BLUE.getRGB());
                var again = session.request(picture(source)); idle(service); again.close(); assertEquals(1, service.metrics().rendered()); assertEquals(1, service.metrics().hits());
            }
        }
    }
    @Test void imageContentDigestInvalidatesEvenPreservedMetadata() throws Exception {
        Path file = temp.resolve("x.bmp");
        var a = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB); ImageIO.write(a, "bmp", file.toFile()); var modified = Files.getLastModifiedTime(file); long size = Files.size(file);
        var source = source("![x](x.bmp)");
        try (var service = new MarkdownMediaService(); var session = service.open(source, 1)) {
            var first = session.request(picture(source)); idle(service); first.close();
            a.setRGB(0, 0, Color.RED.getRGB()); ImageIO.write(a, "bmp", file.toFile()); assertEquals(size, Files.size(file)); Files.setLastModifiedTime(file, modified);
            var next = session.request(picture(source)); idle(service); next.close(); assertEquals(2, service.metrics().rendered());
        }
    }
    @Test void missingRenamedUnsupportedRemoteAndOutsideImagesAreRejected() throws Exception {
        png(temp.resolve("valid.png"), 4, 4, 0); Files.move(temp.resolve("valid.png"), temp.resolve("renamed.png"));
        Files.writeString(temp.resolve("invalid.bin"), "not an image");
        try (var service = new MarkdownMediaService()) {
            for (String path : List.of("valid.png", "missing.png", "invalid.bin", "https://example.com/a.png", "file:///etc/passwd", "../outside.png")) {
                var source = source("![x](" + path + ")"); var session = service.open(source, 1); var handle = session.request(picture(source)); idle(service);
                assertEquals(State.UNSUPPORTED, handle.state(), path); session.close();
            }
            assertEquals(0, service.metrics().cached());
        }
    }
    @Test void decodedPixelAndCompressedInputLimitsApplyBeforeAllocation() throws Exception {
        png(temp.resolve("tall.png"), 2, 4097, 0);
        try (var file = new java.io.RandomAccessFile(temp.resolve("huge.png").toFile(), "rw")) { file.setLength(4L * 1024 * 1024 + 1); }
        try (var service = new MarkdownMediaService()) {
            for (String file : List.of("tall.png", "huge.png")) {
                var source = source("![x](" + file + ")"); var session = service.open(source, 1); var handle = session.request(picture(source)); idle(service);
                assertEquals(State.RESOURCE_LIMIT, handle.state(), file); session.close();
            }
        }
    }
    @Test void symlinkScopeAndRetargetingUseExistingProjectRules() throws Exception {
        Path outside = Files.createTempDirectory("shed-media-outside-");
        try {
            png(outside.resolve("x.png"), 4, 4, 0); Files.createSymbolicLink(temp.resolve("x.png"), outside.resolve("x.png"));
            var source = source("![x](x.png)");
            try (var service = new MarkdownMediaService(); var session = service.open(source, 1)) {
                var handle = session.request(picture(source)); idle(service); assertEquals(State.UNSUPPORTED, handle.state());
            }
        } finally { Files.deleteIfExists(outside.resolve("x.png")); Files.deleteIfExists(outside); }
    }
    @Test void safeLocalSvgIsBoundedAndActiveResourcesAreRejected() throws Exception {
        var source = source("![x](x.svg)");
        try (var service = new MarkdownMediaService(); var session = service.open(source, 1)) {
            Files.writeString(temp.resolve("x.svg"), "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='10'><rect width='20' height='10'/></svg>");
            var valid = session.request(picture(source)); idle(service); assertEquals(State.READY, valid.state()); valid.close();
            for (String body : List.of("<script>alert(1)</script>", "<image href='file:///etc/passwd'/>", "<foreignObject/>", "<rect onclick='x()'/>", "<use href='https://example.com/x.svg'/>") ) {
                Files.writeString(temp.resolve("x.svg"), "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='10'>" + body + "</svg>");
                var invalid = session.request(picture(source)); idle(service); assertEquals(State.UNSUPPORTED, invalid.state(), body);
            }
        }
    }
    @Test void mathOutputMatchesExistingRendererAndAppearanceInvalidatesCache() throws Exception {
        var source = source("$\\frac{x^2}{2}$"); var plan = formula(source);
        try (var service = new MarkdownMediaService(); var session = service.open(source, 1); var preview = new MarkdownPreviewAssets()) {
            var handle = session.request(plan); idle(service);
            try (var result = handle.take(source, 1)) {
                assertEquals(State.READY, result.state()); var lease = result.lease().orElseThrow();
                var expected = ImageIO.read(Path.of(java.net.URI.create(preview.renderMath("\\frac{x^2}{2}", false, Color.BLACK))).toFile());
                var actual = new BufferedImage(lease.width(), lease.height(), BufferedImage.TYPE_INT_ARGB); var g = actual.createGraphics(); lease.paint(g, 0, 0); g.dispose();
                assertEquals(expected.getWidth(), actual.getWidth()); assertEquals(expected.getHeight(), actual.getHeight());
                assertArrayEquals(expected.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()), actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
            }
            var sem = semantic(source); var paragraph = sem.blocks().stream().filter(b -> b.kind() == BlockKind.PARAGRAPH).findFirst().orElseThrow();
            var changed = math(sem, source, paragraph, new Options(Theme.DARK, Color.WHITE.getRGB(), 24, 2048, 2048), 2);
            session.update(source, 2); var next = session.request(changed); idle(service); assertEquals(State.READY, next.state()); next.close(); assertEquals(2, service.metrics().rendered());
        }
    }
    @Test void invalidAndStatefulMathCannotPoisonLaterRendering() throws Exception {
        try (var service = new MarkdownMediaService()) {
            for (String input : List.of("$\\frac{$", "$\\includegraphics{file:///etc/passwd}$", "$\\newcommand{\\x}{x}$", "$x^2$")) {
                var source = source(input); var session = service.open(source, 1); var handle = session.request(formula(source)); idle(service);
                assertEquals(input.equals("$x^2$") ? State.READY : input.startsWith("$\\frac") ? State.FAILED : State.UNSUPPORTED, handle.state(), input);
                session.close();
            }
        }
    }
    @Test void mermaidValidInvalidRecoveryReuseAndThemeExecuteOffEdt() throws Exception {
        try (var service = new MarkdownMediaService()) {
            for (String text : List.of("graph TD\nA-->B", "not mermaid language", "graph LR\nA-->B\nB-->C")) {
                var source = source("```mermaid\n" + text + "\n```"); var session = service.open(source, 1);
                var handle = session.request(diagram(source)); idle(service);
                assertEquals(text.startsWith("not ") ? State.FAILED : State.READY, handle.state(), text);
                if (handle.state() == State.READY) {
                    try (var result = handle.take(source, 1)) { assertTrue(result.lease().orElseThrow().width() > 10); }
                    var repeated = session.request(diagram(source)); idle(service); assertEquals(State.READY, repeated.state()); repeated.close();
                }
                session.close();
            }
            assertEquals(2, service.metrics().rendered()); assertEquals(2, service.metrics().hits()); assertEquals(1, service.metrics().failures());
        }
    }
    @Test void sourceDocumentHistoryAndFilesystemStayUnchanged() throws Exception {
        var buffer = new FileBuffer((String) null); var events = new AtomicInteger();
        var holder = new java.util.concurrent.atomic.AtomicReference<Source>();
        SwingUtilities.invokeAndWait(() -> {
            buffer.setContent("$x^2$"); buffer.setModified(false);
            buffer.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { events.incrementAndGet(); }
                public void removeUpdate(DocumentEvent e) { events.incrementAndGet(); }
                public void changedUpdate(DocumentEvent e) { events.incrementAndGet(); }
            });
            holder.set(Source.capture(new DocumentWorkspace.Id(1), buffer));
        });
        var source = holder.get(); String baseline = buffer.getSavedContent(); boolean undo = buffer.getUndoManager().canUndo();
        try (var service = new MarkdownMediaService(); var session = service.open(source, 1)) {
            var plan = formula(source); var handle = session.request(plan); idle(service); assertEquals(State.READY, handle.state()); handle.close();
        }
        SwingUtilities.invokeAndWait(() -> {
            assertSame(source.text(), buffer.textSnapshot()); assertEquals(source.revision(), buffer.documentRevision()); assertFalse(buffer.isModified());
            assertEquals(baseline, buffer.getSavedContent()); assertEquals(undo, buffer.getUndoManager().canUndo()); assertEquals(0, events.get());
        });
        try (var paths = Files.list(temp)) { assertEquals(0, paths.count()); }
    }
    @Test void constructionStartsNoWorkerAndWaitsAreForbiddenOnEdt() throws Exception {
        var renderer = new Gate();
        try (var service = fake(renderer)) {
            assertEquals(0, service.metrics().running()); assertEquals(0, service.metrics().sessions()); assertEquals(0, renderer.calls.get());
            SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, () -> service.awaitIdle(1, TimeUnit.MILLISECONDS)));
        }
    }
    @Test void themeChangeGetsANewMermaidAssetAndSupersedesTheOldLease() throws Exception {
        var source = source("```mermaid\ngraph TD\nA-->B\n```"); var semantic = semantic(source);
        try (var service = new MarkdownMediaService(); var session = service.open(source, 1)) {
            var handle = session.request(diagram(source)); idle(service); var old = handle.take(source, 1); var lease = old.lease().orElseThrow();
            String identity = lease.info().identity(); session.update(source, 2); assertFalse(lease.isCurrent(source, 1));
            var themed = mermaid(semantic, source, semantic.codeBlocks().getFirst(), new Options(Theme.DARK, Color.WHITE.getRGB(), 16, 2048, 2048), 2);
            var next = session.request(themed); idle(service);
            try (var result = next.take(source, 2)) {
                assertEquals(State.READY, result.state()); assertNotEquals(identity, result.lease().orElseThrow().info().identity());
                assertEquals(Theme.DARK, result.lease().orElseThrow().info().options().theme());
            }
            old.close(); assertEquals(2, service.metrics().rendered());
        }
    }
    @Test void localRasterSizingPreservesAspectRatioAndNeverUpscales() throws Exception {
        png(temp.resolve("x.png"), 20, 10, Color.RED.getRGB()); var source = source("![x](x.png)"); var sem = semantic(source);
        try (var service = new MarkdownMediaService(); var session = service.open(source, 1)) {
            var scaled = image(sem, source, sem.links().getFirst(), new Options(Theme.DEFAULT, Color.BLACK.getRGB(), 16, 10, 10), new Context(temp.resolve("x.md"), List.of(temp)), 1);
            var handle = session.request(scaled); idle(service);
            try (var result = handle.take(source, 1)) { assertEquals(10, result.lease().orElseThrow().width()); assertEquals(5, result.lease().orElseThrow().height()); }
            var natural = session.request(picture(source)); idle(service);
            try (var result = natural.take(source, 1)) { assertEquals(20, result.lease().orElseThrow().width()); assertEquals(10, result.lease().orElseThrow().height()); }
        }
    }
    @Test void deletedImageDuringDecodingCannotPublishAnAsset() throws Exception {
        png(temp.resolve("x.png"), 4, 4, 0); var source = source("![x](x.png)");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var delegate = new MarkdownMediaRenderer();
        var renderer = new MarkdownMediaService.Renderer() {
            public MarkdownMediaService.Prepared prepare(Request request, java.util.function.BooleanSupplier stop) throws Exception { return delegate.prepare(request, stop); }
            public BufferedImage render(MarkdownMediaService.Prepared prepared, java.util.function.BooleanSupplier stop) throws Exception {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return delegate.render(prepared, stop);
            }
            public void validate(MarkdownMediaService.Prepared prepared, java.util.function.BooleanSupplier stop) throws Exception { delegate.validate(prepared, stop); }
        };
        try (var service = new MarkdownMediaService(MarkdownMediaService.Budget.desktop(), renderer); var session = service.open(source, 1)) {
            var handle = session.request(picture(source)); assertTrue(entered.await(10, TimeUnit.SECONDS));
            Files.delete(temp.resolve("x.png")); release.countDown(); idle(service);
            assertEquals(State.UNSUPPORTED, handle.state()); assertEquals(0, service.metrics().residentBytes());
        }
    }
    @Test void consumerAndSessionBudgetsAlsoBoundCompletedCacheHits() throws Exception {
        var renderer = new Gate(); renderer.release.countDown(); var source = source("$x$");
        var budget = new MarkdownMediaService.Budget(2, 2, 1, 4, 1024, 2048, 512);
        try (var service = new MarkdownMediaService(budget, renderer); var session = service.open(source, 1)) {
            assertThrows(IllegalArgumentException.class, () -> service.open(source, 1));
            var first = session.request(formula(source)); idle(service); var second = session.request(formula(source)); idle(service);
            assertEquals(State.RESOURCE_LIMIT, session.request(formula(source)).state());
            first.close(); var third = session.request(formula(source)); idle(service); assertEquals(State.READY, third.state());
            assertEquals(2, service.metrics().consumers()); assertEquals(1, renderer.calls.get()); second.close(); third.close();
        }
    }
    @Test void aNewRevisionCanCancelAPendingTakeWithoutWaitingForRendering() throws Exception {
        var renderer = new Gate(); var source = source("$x$");
        try (var service = fake(renderer); var session = service.open(source, 1)) {
            var handle = session.request(formula(source)); assertTrue(renderer.entered.await(10, TimeUnit.SECONDS));
            assertEquals(State.SUPERSEDED, handle.take(new Source(source.documentId(), 8, source.text()), 1).state());
            assertEquals(0, service.metrics().consumers()); assertEquals(1, service.metrics().running()); renderer.release.countDown(); idle(service);
            assertEquals(0, service.metrics().residentBytes());
        }
    }
}
