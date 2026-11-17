package shed;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static shed.MarkdownMedia.*;
import static shed.MarkdownPerformanceProbe.*;
import static shed.MarkdownSemanticSnapshot.*;

/** Opt-in real service/renderer workload, independent of preview HTML layout. */
final class MarkdownMediaProbe {
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final class Measured implements MarkdownMediaService.Renderer {
        final MarkdownMediaRenderer delegate = new MarkdownMediaRenderer();
        final CountDownLatch firstMermaid = new CountDownLatch(1);
        final AtomicInteger mermaids = new AtomicInteger();
        private <T> T timed(String operation, Callable<T> work) throws Exception {
            long began = System.nanoTime(), cpu = THREADS.getCurrentThreadCpuTime(), allocated = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
            String state = "OK";
            try { return work.call(); }
            catch (Exception error) { state = error instanceof CancellationException ? "CANCELLED" : "FAILED"; throw error; }
            finally { out("MD_SAMPLE ", Map.of("operation", operation, "state", state, "wall_ms", (System.nanoTime() - began) / 1e6,
                "thread_cpu_ms", (THREADS.getCurrentThreadCpuTime() - cpu) / 1e6,
                "thread_allocated_bytes", Math.max(0, THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated), "thread", Thread.currentThread().getName())); }
        }
        public MarkdownMediaService.Prepared prepare(Request request, BooleanSupplier stop) throws Exception {
            return timed(request.kind().name().toLowerCase(Locale.ROOT) + "_prepare", () -> delegate.prepare(request, stop));
        }
        public BufferedImage render(MarkdownMediaService.Prepared prepared, BooleanSupplier stop) throws Exception {
            Kind kind = prepared.request().kind(); String operation = kind.name().toLowerCase(Locale.ROOT) + "_render";
            if (kind == Kind.MERMAID) { operation = mermaids.getAndIncrement() == 0 ? "mermaid_cold_render" : "mermaid_warm_render"; firstMermaid.countDown(); }
            return timed(operation, () -> delegate.render(prepared, stop));
        }
        public void validate(MarkdownMediaService.Prepared prepared, BooleanSupplier stop) throws Exception { delegate.validate(prepared, stop); }
    }
    private static Plan diagram(MarkdownSemanticSnapshot semantic, Options options, long generation) {
        return mermaid(semantic, semantic.source(), semantic.codeBlocks().getFirst(), options, generation);
    }
    private static MarkdownSemanticSnapshot parse(long id, long revision, String source) {
        var result = new MarkdownSemanticParser().parse(new Source(new DocumentWorkspace.Id(id), revision, VersionedTextSnapshot.of(source)));
        if (result.status() != Status.SUCCEEDED) throw new AssertionError(result.detail()); return result;
    }
    private static MarkdownMediaService.Handle submit(MarkdownMediaService.Session session, Plan plan, String operation) throws Exception {
        long began = System.nanoTime();
        var handle = edt(() -> { long start = System.nanoTime(); var h = session.request(plan);
            out("MD_SAMPLE ", Map.of("operation", operation, "state", "OK", "edt_ms", (System.nanoTime() - start) / 1e6)); return h; });
        submitted.put(handle, began); return handle;
    }
    private static final Map<MarkdownMediaService.Handle, Long> submitted = new IdentityHashMap<>();
    private static void consume(MarkdownMediaService.Handle handle, Source source, long generation, String operation, State expected) throws Exception {
        long began = submitted.remove(handle);
        try (var result = edt(() -> handle.take(source, generation))) {
            if (result.state() != expected) throw new AssertionError(operation + ": " + result.state() + " " + result.detail());
            out("MD_SAMPLE ", Map.of("operation", operation, "state", "OK", "request_to_result_ms", (System.nanoTime() - began) / 1e6,
                "queue_ms", result.queueNanos() / 1e6, "worker_ms", result.workerNanos() / 1e6));
            if (result.lease().isPresent()) {
                var lease = result.lease().orElseThrow();
                if (!lease.isCurrent(source, generation) || lease.width() < 1 || lease.height() < 1) throw new AssertionError("Invalid media lease");
            }
        }
    }
    private static void idle(MarkdownMediaService service) throws Exception { if (!service.awaitIdle(45, TimeUnit.SECONDS)) throw new AssertionError("Media service timeout"); }
    private static void settle(String phase) throws Exception {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
        memory(phase);
        var ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String rss = new String(ps.getInputStream().readAllBytes()).strip(); ps.waitFor();
        out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Long.parseLong(rss) / 1024d));
    }
    static void run(Texteditor editor, int samples, int warmups) throws Exception {
        for (int i = -warmups; i < samples; i++) {
            var buffer = edt(editor::getCurrentBuffer);
            uiText("media_inactive_typing", () -> typed('x'), i >= 0);
            uiText("media_inactive_undo", () -> buffer.getUndoManager().undo(), i >= 0);
        }
        settle("before_media_service");
        WeakReference<MarkdownMediaService> reference = workload(editor, samples);
        settle("after_media_disposal");
        long workers = Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().equals("shed-markdown-media")).count();
        out("MD_META ", Map.of("operation", "media_service_collected", "collected", reference.get() == null, "media_workers_alive", workers));
        if (reference.get() != null) throw new AssertionError("Disposed media service retained");
        if (workers != 0) throw new AssertionError("Disposed media worker retained");
    }
    private static WeakReference<MarkdownMediaService> workload(Texteditor editor, int samples) throws Exception {
        var buffer = edt(editor::getCurrentBuffer); var original = edt(buffer::textSnapshot); long revision = edt(buffer::documentRevision);
        Path sourceFile = Path.of(buffer.getFilePath());
        StringBuilder input = new StringBuilder("```mermaid\ngraph TD\nA-->B\n```\n\n![image](swatch.png)\n\n");
        for (int i = 0; i < 16; i++) input.append("$x^").append(i + 1).append("$\n\n");
        // Independently captured media revision: typing in another buffer must not cancel its work.
        var semantic = parse(9001, 1, input.toString()); var source = semantic.source(); var measured = new Measured();
        var service = new MarkdownMediaService(MarkdownMediaService.Budget.desktop(), measured); var session = service.open(source, 1);
        out("MD_META ", Map.of("operation", "media_lazy", "metrics", service.metrics().toString()));
        var cold = submit(session, diagram(semantic, Options.preview(), 1), "media_submit");
        if (!measured.firstMermaid.await(10, TimeUnit.SECONDS)) throw new AssertionError("Mermaid worker did not start");
        var duplicate = submit(session, diagram(semantic, Options.preview(), 1), "media_duplicate_submit");
        var paragraphs = semantic.blocks().stream().filter(b -> b.kind() == BlockKind.PARAGRAPH).skip(1).toList();
        var queued = new ArrayList<MarkdownMediaService.Handle>(); int limited = 0;
        for (var paragraph : paragraphs) {
            var handle = session.request(math(semantic, source, paragraph, Options.preview(), 1));
            if (handle.state() == State.RESOURCE_LIMIT) limited++; else queued.add(handle);
        }
        int overlap = 0;
        for (int i = 0; i < samples; i++) {
            if (service.metrics().running() != 0) overlap++;
            uiText("media_active_typing", () -> typed('x'), true);
            uiText("media_active_undo", () -> buffer.getUndoManager().undo(), true);
        }
        idle(service); consume(cold, source, 1, "media_cold_result", State.READY); consume(duplicate, source, 1, "media_duplicate_result", State.READY);
        for (var handle : queued) try (var result = handle.take(source, 1)) {
            if (result.state() != State.READY) throw new AssertionError("Queued math did not complete");
            out("MD_SAMPLE ", Map.of("operation", "media_queued_math_result", "state", "OK", "queue_ms", result.queueNanos() / 1e6, "worker_ms", result.workerNanos() / 1e6));
        }
        out("MD_META ", Map.of("operation", "media_typing_overlap", "samples", samples, "overlap", overlap, "queue_rejections", limited, "metrics", service.metrics().toString()));
        for (int i = 0; i < samples; i++) {
            var hit = submit(session, diagram(semantic, Options.preview(), 1), "media_cached_dispatch"); idle(service); consume(hit, source, 1, "media_cached_result", State.READY);
        }
        var image = image(semantic, source, semantic.links().getFirst(), Options.preview(), new Context(sourceFile, List.of(sourceFile.getParent())), 1);
        for (int i = 0; i < Math.min(samples, 20); i++) {
            var request = submit(session, image, "image_dispatch"); idle(service); consume(request, source, 1, i == 0 ? "image_first_result" : "image_cached_result", State.READY);
        }
        for (int i = 0; i < Math.min(samples, 20); i++) {
            var request = submit(session, math(semantic, source, paragraphs.getFirst(), Options.preview(), 1), "math_dispatch"); idle(service); consume(request, source, 1, "math_cached_result", State.READY);
        }
        for (int i = 0; i < Math.min(samples, 10); i++) {
            var changed = parse(9001, i + 2, "```mermaid\ngraph LR\nA-->B" + i + "\n```"); session.update(changed.source(), 1);
            var request = submit(session, diagram(changed, Options.preview(), 1), "mermaid_changed_dispatch"); idle(service); consume(request, changed.source(), 1, "mermaid_changed_result", State.READY);
        }
        session.update(source, 2);
        var themed = submit(session, diagram(semantic, new Options(Theme.DARK, 0xffffffff, 16, 2048, 2048), 2), "mermaid_theme_dispatch");
        idle(service); consume(themed, source, 2, "mermaid_theme_result", State.READY);
        var stale = submit(session, diagram(semantic, Options.preview(), 2), "media_stale_dispatch");
        session.update(source, 3); idle(service); consume(stale, source, 3, "media_stale_result", State.SUPERSEDED);
        settle("retained_media_service");
        out("MD_META ", Map.of("operation", "retained_media_metrics", "metrics", service.metrics().toString()));
        if (!edt(() -> buffer.textSnapshot().text()).equals(original.text()) || edt(buffer::documentRevision) - revision != samples * 2)
            throw new AssertionError("Unexpected source mutation during media rendering");
        out("MD_META ", Map.of("operation", "media_source_preservation", "unexpected_revision_delta", edt(buffer::documentRevision) - revision - samples * 2));
        session.close(); service.close(); idle(service);
        out("MD_META ", Map.of("operation", "media_closed", "metrics", service.metrics().toString()));
        if (service.metrics().residentBytes() != 0 || service.metrics().consumers() != 0 || service.metrics().sessions() != 0 || service.metrics().queued() != 0) throw new AssertionError("Media resources retained");
        return new WeakReference<>(service);
    }
}
