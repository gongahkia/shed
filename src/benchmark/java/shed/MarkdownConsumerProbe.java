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
        System.gc(); memory("semantic_before_first_analysis_gc_observation");
        QueueTiming queue = new QueueTiming();
        java.nio.file.Path switchedFile = null;
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
            System.gc(); memory("semantic_with_outline_gc_observation");
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
            // One EDT turn keeps the worker from installing intermediate revisions during the burst.
            long burstBegan = System.nanoTime();
            edt(() -> {
                for (int i = 0; i < 16; i++) {
                    long began = System.nanoTime(); newRevision(editor);
                    double editMillis = (System.nanoTime() - began) / 1e6;
                    began = System.nanoTime(); editor.showOutline();
                    out("MD_SAMPLE ", Map.of("operation", "semantic_rapid_edit", "state", "OK",
                        "edit_undo_edt_ms", editMillis, "request_edt_ms", (System.nanoTime() - began) / 1e6));
                }
                return null;
            });
            await(() -> ready(editor), 30);
            out("MD_SAMPLE ", Map.of("operation", "semantic_burst_convergence", "state", "OK",
                "convergence_observed_ms", (System.nanoTime() - burstBegan) / 1e6));
            edt(() -> { buffer.setModified(false); editor.showOutline(); return null; });
            await(() -> ready(editor), 30);
            MarkdownHeadingAnalysis retained = edt(() -> analysis(editor));
            if (retained.semantic().status() == MarkdownSemanticSnapshot.Status.SUCCEEDED) {
                var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                for (int i = -warmups; i < samples; i++) {
                    long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()), began = System.nanoTime();
                    long[] countAndChecksum = new long[2];
                    new MarkdownOccurrences(retained.semantic()).stream().forEach(occurrence -> {
                        countAndChecksum[0]++;
                        countAndChecksum[1] += occurrence.kind().ordinal() + occurrence.location().segments().getFirst().start()
                            + occurrence.destination().orElse("").hashCode();
                    });
                    var row = row("semantic_occurrences_worker"); row.put("state", i < 0 ? "WARMUP" : "OK");
                    row.put("worker_ms", (System.nanoTime() - began) / 1e6); row.put("occurrences", countAndChecksum[0]); row.put("checksum", countAndChecksum[1]);
                    row.put("allocated_bytes", threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes); out("MD_SAMPLE ", row);
                }
            } else out("MD_SAMPLE ", Map.of("operation", "semantic_occurrences_worker", "state", "UNSUPPORTED", "coverage", retained.semantic().coverage().name()));
            retained = null;
            edt(() -> {
                EditorPane first = editor.getActivePane(); int caret = first.getTextArea().getCaretPosition();
                editor.splitWindow(true); editor.writingArea.setCaretPosition(0);
                long began = System.nanoTime(); editor.navigateHeading(true);
                out("MD_SAMPLE ", Map.of("operation", "semantic_split_navigation", "state", "OK", "edt_ms", (System.nanoTime() - began) / 1e6));
                if (first.getTextArea().getCaretPosition() != caret) throw new AssertionError("Navigation changed other pane caret");
                editor.showOutline(); return null;
            });
            await(() -> ready(editor), 30);
            out("MD_META ", Map.of("operation", "semantic_split", "shared_document", edt(() -> editor.editorPanes.stream().allMatch(p -> p.getBuffer() != buffer || p.getTextArea().getDocument() == buffer.getDocument()))));
            switchedFile = java.nio.file.Files.createTempFile(java.nio.file.Path.of(buffer.getFilePath()).getParent(), "consumer-switch-", ".md");
            java.nio.file.Files.writeString(switchedFile, "# other\n");
            FileBuffer other = new FileBuffer(switchedFile.toFile(), editor.configManager);
            if (other.getFileType() != FileType.MARKDOWN) throw new AssertionError("Switch fixture is not Markdown");
            long began = System.nanoTime();
            edt(() -> { editor.buffers.add(other); editor.loadBufferIntoEditor(other); editor.showOutline(); return null; });
            await(() -> ready(editor), 30);
            out("MD_SAMPLE ", Map.of("operation", "semantic_buffer_switch", "state", "OK", "convergence_observed_ms", (System.nanoTime() - began) / 1e6));
            edt(() -> { editor.closeActiveBufferWithoutExiting(true); editor.loadBufferIntoEditor(buffer); return null; });
            if (!original.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Consumer benchmark changed source");
            out("MD_META ", Map.of("operation", "semantic_diagnostics", "submitted", edt(() -> service.diagnostics().submitted()),
                "reused", edt(() -> service.diagnostics().reused()), "stale_rejected", edt(() -> service.diagnostics().staleRejected()),
                "superseded", edt(() -> service.diagnostics().superseded()), "source_restored", true));
            edt(() -> { var d = dialog(editor); if (d != null) d.dispose(); return null; });
            System.gc(); memory("semantic_after_outline_dispose_gc_observation");
            edt(() -> { editor.closeActiveBufferWithoutExiting(true); return null; });
            if (edt(() -> editor.buffers.contains(buffer))) throw new AssertionError("Source buffer still open");
            System.gc(); memory("semantic_after_document_close_gc_observation");
        } finally {
            edt(() -> { queue.remove(); return null; });
            if (switchedFile != null) java.nio.file.Files.deleteIfExists(switchedFile);
        }
    }
}
