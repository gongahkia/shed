package shed;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in actual-workbench integration. No production instrumentation or extra worker. */
final class MarkdownConsumerProbe {
    private static final class QueueTiming extends EventQueue {
        final AtomicLong maximum = new AtomicLong();
        @Override protected void dispatchEvent(AWTEvent event) {
            long began = System.nanoTime();
            try { super.dispatchEvent(event); }
            finally { maximum.accumulateAndGet(System.nanoTime() - began, Math::max); }
        }
        void remove() { pop(); }
    }
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static MarkdownOutlineDialog dialog(Texteditor editor) {
        return Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof MarkdownOutlineDialog && w.isDisplayable())
            .map(w -> (MarkdownOutlineDialog) w).findFirst().orElse(null);
    }
    private static MarkdownHeadingAnalysis analysis(Texteditor editor) throws Exception {
        var dialog = dialog(editor);
        return dialog == null ? null : (MarkdownHeadingAnalysis) field(dialog, "analysis");
    }
    private static boolean ready(Texteditor editor) throws Exception {
        var result = analysis(editor);
        return result != null && editor.isMarkdownSourceCurrent(editor.getCurrentBuffer(), result.source());
    }
    private static void newRevision(Texteditor editor) throws Exception {
        var buffer = editor.getCurrentBuffer();
        buffer.getDocument().insertString(buffer.getDocument().getLength(), "x", null);
        buffer.getUndoManager().undo(); buffer.setModified(false);
    }

    static void run(Texteditor editor, int samples, int warmups) throws Exception {
        FileBuffer buffer = edt(editor::getCurrentBuffer);
        if (buffer.isLargeFile()) { out("MD_SAMPLE ", Map.of("operation", "semantic_outline", "state", "UNSUPPORTED", "reason", "Read-only projection retains legacy UI")); return; }
        String original = edt(() -> buffer.textSnapshot().text());
        out("MD_META ", Map.of("operation", "semantic_before_request", "service_initialized", edt(() -> field(editor.markdownController, "headingAnalysis") != null)));
        QueueTiming queue = new QueueTiming();
        edt(() -> { Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue); return null; });
        try {
            for (int i = -warmups; i < samples; i++) {
                edt(() -> { newRevision(editor); return null; });
                queue.maximum.set(0); long began = System.nanoTime();
                double dispatch = edt(() -> { long t = System.nanoTime(); editor.showOutline(); return (System.nanoTime() - t) / 1e6; });
                await(() -> ready(editor), 30);
                var result = edt(() -> analysis(editor));
                var row = row("semantic_outline"); row.put("state", i < 0 ? "WARMUP" : "OK");
                row.put("convergence_observed_ms", (System.nanoTime() - began) / 1e6);
                row.put("request_edt_ms", dispatch); row.put("worker_ms", result.workerNanos() / 1e6);
                row.put("largest_observed_edt_task_ms", queue.maximum.get() / 1e6); row.put("path", result.path().name());
                row.put("coverage", result.semantic().coverage().name()); row.put("headings", result.headings().size());
                row.put("reason", result.semantic().detail()); out("MD_SAMPLE ", row);
            }
            var service = edt(() -> editor.markdownController.headingAnalysis());
            long submitted = edt(() -> service.diagnostics().submitted());
            for (int i = -warmups; i < samples; i++) {
                var row = edt(() -> {
                    editor.writingArea.setCaretPosition(0); long began = System.nanoTime(); editor.navigateHeading(true);
                    var r = row("semantic_cached_navigation"); r.put("edt_ms", (System.nanoTime() - began) / 1e6); return r;
                });
                row.put("state", i < 0 ? "WARMUP" : "OK"); out("MD_SAMPLE ", row);
            }
            if (submitted != edt(() -> service.diagnostics().submitted())) throw new AssertionError("Unchanged revision reparsed despite live outline result");
            for (int i = -warmups; i < samples; i++) {
                edt(() -> { editor.writingArea.setCaretPosition(0); editor.showOutline(); return null; });
                boolean outstanding = edt(() -> service.diagnostics().running() != 0);
                uiText(outstanding ? "semantic_typing_outstanding_job" : "semantic_typing_idle", () -> typed('x'), i >= 0);
                uiText("semantic_typing_undo", () -> editor.getCurrentBuffer().getUndoManager().undo(), i >= 0);
            }
            edt(() -> { buffer.setModified(false); editor.showOutline(); return null; });
            await(() -> ready(editor), 30);
            MarkdownHeadingAnalysis retained = edt(() -> analysis(editor));
            if (retained.semantic().status() == MarkdownSemanticSnapshot.Status.SUCCEEDED) {
                var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                for (int i = -warmups; i < samples; i++) {
                    long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()), began = System.nanoTime();
                    long count = new MarkdownOccurrences(retained.semantic()).stream().count();
                    var row = row("semantic_occurrences_worker"); row.put("state", i < 0 ? "WARMUP" : "OK");
                    row.put("worker_ms", (System.nanoTime() - began) / 1e6); row.put("occurrences", count);
                    row.put("allocated_bytes", threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes); out("MD_SAMPLE ", row);
                }
            } else out("MD_SAMPLE ", Map.of("operation", "semantic_occurrences_worker", "state", "UNSUPPORTED", "coverage", retained.semantic().coverage().name()));
            edt(() -> { editor.splitWindow(true); editor.showOutline(); return null; });
            await(() -> ready(editor), 30);
            out("MD_META ", Map.of("operation", "semantic_split", "shared_document", edt(() -> editor.editorPanes.stream().allMatch(p -> p.getBuffer() != buffer || p.getTextArea().getDocument() == buffer.getDocument()))));
            FileBuffer other = FileBuffer.createScratch("consumer-switch.md", "# other\n");
            long began = System.nanoTime();
            edt(() -> { editor.buffers.add(other); editor.loadBufferIntoEditor(other); editor.showOutline(); return null; });
            await(() -> ready(editor), 30);
            out("MD_SAMPLE ", Map.of("operation", "semantic_buffer_switch", "state", "OK", "convergence_observed_ms", (System.nanoTime() - began) / 1e6));
            edt(() -> { editor.closeActiveBufferWithoutExiting(true); editor.loadBufferIntoEditor(buffer); return null; });
            if (!original.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Consumer benchmark changed source");
            out("MD_META ", Map.of("operation", "semantic_diagnostics", "submitted", edt(() -> service.diagnostics().submitted()),
                "reused", edt(() -> service.diagnostics().reused()), "stale_rejected", edt(() -> service.diagnostics().staleRejected()),
                "superseded", edt(() -> service.diagnostics().superseded()), "source_restored", true));
            System.gc(); memory("semantic_after_document_close_gc_observation");
            edt(() -> { var d = dialog(editor); if (d != null) d.dispose(); return null; });
            System.gc(); memory("semantic_after_outline_dispose_gc_observation");
        } finally { edt(() -> { queue.remove(); return null; }); }
    }
}
