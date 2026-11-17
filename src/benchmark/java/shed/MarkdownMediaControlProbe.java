package shed;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static shed.MarkdownPerformanceProbe.*;

/** Matched renderer/memory control. The probe worker is diagnostic, not a shipped preview scheduler. */
final class MarkdownMediaControlProbe {
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static Object timed(String operation, Callable<Object> work) throws Exception {
        long began = System.nanoTime(), allocated = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
        Object value = work.call();
        out("MD_SAMPLE ", Map.of("operation", operation, "state", "OK", "wall_ms", (System.nanoTime() - began) / 1e6,
            "thread_allocated_bytes", Math.max(0, THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated), "thread", Thread.currentThread().getName()));
        return value;
    }
    private static void settle(String phase) throws Exception {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
        memory(phase);
        var ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String rss = new String(ps.getInputStream().readAllBytes()).strip(); ps.waitFor();
        out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Long.parseLong(rss) / 1024d));
    }
    static void run(Texteditor editor, int samples, int warmups) throws Exception {
        var buffer = edt(editor::getCurrentBuffer); var source = edt(buffer::textSnapshot);
        for (int i = -warmups; i < samples; i++) {
            uiText("media_inactive_typing", () -> typed('x'), i >= 0);
            uiText("media_inactive_undo", () -> buffer.getUndoManager().undo(), i >= 0);
        }
        settle("before_media_control");
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "media-control-only"));
        var assets = executor.submit(() -> new MarkdownPreviewAssets()).get();
        var entered = new CountDownLatch(1); var running = new AtomicBoolean();
        var first = executor.submit(() -> {
            entered.countDown(); running.set(true);
            try { return timed("legacy_mermaid_cold", () -> assets.renderMermaid("graph TD\nA-->B\n")); }
            finally { running.set(false); }
        });
        if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("Control renderer did not start");
        int overlap = 0;
        for (int i = 0; i < samples; i++) {
            if (running.get()) overlap++;
            uiText("media_active_typing", () -> typed('x'), true);
            uiText("media_active_undo", () -> buffer.getUndoManager().undo(), true);
        }
        first.get(45, TimeUnit.SECONDS);
        for (int i = 0; i < 8; i++) { final int n = i + 1; executor.submit(() -> timed("legacy_math", () -> assets.renderMath("x^" + n, false, java.awt.Color.BLACK))).get(); }
        for (int i = 0; i < samples; i++) executor.submit(() -> timed("legacy_mermaid_cached", () -> assets.renderMermaid("graph TD\nA-->B\n"))).get();
        byte[] image = Files.readAllBytes(Path.of(buffer.getFilePath()).getParent().resolve("swatch.png"));
        executor.submit(() -> timed("legacy_image_decode", () -> { var decoded = MarkdownPreviewAssets.decodeRemoteImageBytes(image); decoded.flush(); return null; })).get();
        for (int i = 0; i < Math.min(samples, 10); i++) { final int n = i;
            executor.submit(() -> timed("legacy_mermaid_warm", () -> assets.renderMermaid("graph LR\nA-->B" + n + "\n"))).get(45, TimeUnit.SECONDS);
        }
        executor.submit(() -> timed("legacy_mermaid_theme", () -> assets.renderMermaid("%%{init: {\"theme\":\"dark\"}}%%\ngraph TD\nA-->B\n"))).get(45, TimeUnit.SECONDS);
        settle("retained_media_control");
        executor.submit(assets::close).get(); executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new AssertionError("Control worker retained");
        settle("after_media_control_disposal");
        out("MD_META ", Map.of("operation", "media_control", "typing_overlap", overlap, "samples", samples, "source_unchanged", edt(() -> buffer.textSnapshot().text()).equals(source.text())));
    }
}
