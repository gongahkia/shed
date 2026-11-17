package shed;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in paired workbench measurements; compiles against the pre-2C artifact too. */
final class MarkdownEditingProbe {
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static MarkdownHeadingAnalysis analyze(Texteditor editor) throws Exception {
        AtomicReference<MarkdownHeadingAnalysis> result = new AtomicReference<>();
        edt(() -> { editor.requestMarkdownHeadings(new Object(), editor.getCurrentBuffer(), () -> true, result::set); return null; });
        await(() -> result.get() != null, 30);
        return result.get();
    }
    private static void changedRevision(Texteditor editor) throws Exception {
        var b = editor.getCurrentBuffer();
        b.getDocument().insertString(b.getDocument().getLength(), "x", null); b.getUndoManager().undo(); b.setModified(false);
    }
    private static boolean highlighted(Texteditor editor) throws Exception {
        var installed = (WeakReference<?>) field(editor.syntaxUiController, "installedMarkdownText");
        return installed.get() == editor.getCurrentBuffer().textSnapshot();
    }
    private static void observe(String phase) throws Exception {
        memory(phase);
        var child = new ProcessBuilder("/bin/ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IllegalStateException("RSS observation timed out"); }
        out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Long.parseLong(new String(child.getInputStream().readAllBytes()).trim()) / 1024d));
    }
    private static void settled(Texteditor editor, String phase) throws Exception {
        await(() -> editor.asyncJobService.list().stream().noneMatch(j -> j.getStatus() == AsyncJobService.Status.RUNNING), 30);
        edt(() -> null); observe(phase + "_no_forced_gc");
        System.gc(); edt(() -> null); observe(phase + "_after_gc");
        if (ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:NativeMemoryTracking=summary")) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
                Long.toString(ProcessHandle.current().pid()), "VM.native_memory", "summary").redirectErrorStream(true).start();
            if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IllegalStateException("NMT observation timed out"); }
            out("MD_META ", Map.of("operation", phase + "_nmt", "text", new String(child.getInputStream().readAllBytes())));
            if (Files.isExecutable(Path.of("/usr/bin/vmmap"))) {
                var map = new ProcessBuilder("/usr/bin/vmmap", "-summary", Long.toString(ProcessHandle.current().pid())).redirectErrorStream(true).start();
                if (!map.waitFor(5, TimeUnit.SECONDS)) map.destroyForcibly();
                out("MD_META ", Map.of("operation", phase + "_vmmap", "state", map.isAlive() ? "UNAVAILABLE" : "EXIT_" + map.exitValue(),
                    "text", new String(map.getInputStream().readAllBytes())));
            }
        }
    }
    static void run(Texteditor editor, int samples, int warmups, String mode) throws Exception {
        out("MD_META ", Map.of("operation", "edit_startup", "service_initialized", edt(() -> field(editor.markdownController, "headingAnalysis") != null)));
        if (mode.startsWith("semantic-memory")) { memoryRun(editor, samples, mode.equals("semantic-memory")); return; }
        FileBuffer buffer = edt(editor::getCurrentBuffer);
        String original = edt(() -> buffer.textSnapshot().text());
        boolean eligible = original.length() <= MarkdownSemanticParser.MAX_CHARACTERS;
        for (int i = -warmups; i < samples; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
            uiText("paired_typing", () -> typed('x'), i >= 0);
            uiText("paired_typing_undo", () -> buffer.getUndoManager().undo(), i >= 0);
        }
        edt(() -> { buffer.setModified(false); return null; });
        Method planner = null;
        try { planner = Class.forName("shed.MarkdownHeadingEditPlan").getDeclaredMethod("plan", MarkdownSemanticSnapshot.class, int.class, int.class, boolean.class); planner.setAccessible(true); }
        catch (ClassNotFoundException missing) { out("MD_META ", Map.of("operation", "edit_plans", "state", "UNAVAILABLE: pre-2C artifact")); }
        for (int i = -warmups; i < samples; i++) {
            MarkdownHeadingAnalysis retained = eligible ? analyze(editor) : null;
            long submitted = edt(() -> editor.markdownController.headingAnalysis().diagnostics().submitted());
            Object proposal = null;
            if (planner != null && retained != null) {
                long began = System.nanoTime(); Object planned = planner.invoke(null, retained.semantic(), 0, 0, true);
                double millis = (System.nanoTime() - began) / 1e6;
                var getEdit = planned.getClass().getDeclaredMethod("edit"); getEdit.setAccessible(true);
                proposal = ((Optional<?>) getEdit.invoke(planned)).orElseThrow();
                if (i >= 0) out("MD_SAMPLE ", Map.of("operation", "pure_heading_plan", "state", "OK", "worker_ms", millis));
            }
            final String[] message = {""};
            var beforeCommand = edt(buffer::textSnapshot);
            if (eligible) uiText("paired_heading_edit", () -> message[0] = editor.markdownHeadingShift(true), i >= 0);
            else edt(() -> {
                long began = System.nanoTime(); message[0] = editor.markdownHeadingShift(true);
                out("MD_SAMPLE ", Map.of("operation", "unsupported_heading_request", "state", "OK", "edt_ms", (System.nanoTime() - began) / 1e6)); return null;
            });
            if (eligible) {
                await(() -> editor.writingArea.getText(0, Math.min(3, editor.writingArea.getDocument().getLength())).startsWith("##"), 30);
                if (i >= 0 && i < 10) {
                    long began = System.nanoTime(); await(() -> highlighted(editor), 10);
                    out("MD_SAMPLE ", Map.of("operation", "heading_highlight_convergence", "state", "OK", "observed_ms", (System.nanoTime() - began) / 1e6));
                }
                uiText("paired_heading_undo", () -> buffer.getUndoManager().undo(), i >= 0);
                uiText("paired_heading_redo", () -> buffer.getUndoManager().redo(), i >= 0);
                edt(() -> { buffer.getUndoManager().undo(); buffer.setModified(false); return null; });
                if (proposal != null) {
                    Object stale = proposal; boolean measured = i >= 0;
                    edt(() -> {
                        var apply = MarkdownEdit.class.getDeclaredMethod("applyPlan", stale.getClass(), MarkdownSemanticSnapshot.Source.class,
                            FileBuffer.class, javax.swing.JTextArea.class, EditorSurface.class, java.util.function.BooleanSupplier.class);
                        apply.setAccessible(true);
                        var source = MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer);
                        int entries = ((BoundedUndoManager) buffer.getUndoManager()).retainedEditCount();
                        long began = System.nanoTime();
                        String failure = (String) apply.invoke(null, stale, source, buffer, editor.writingArea, editor.getActivePane().surface(), (java.util.function.BooleanSupplier) () -> true);
                        double millis = (System.nanoTime() - began) / 1e6;
                        if (failure.isEmpty() || buffer.textSnapshot() != source.text() || buffer.documentRevision() != source.revision()
                            || entries != ((BoundedUndoManager) buffer.getUndoManager()).retainedEditCount()) throw new AssertionError("Stale proposal mutated source/history");
                        if (measured) out("MD_SAMPLE ", Map.of("operation", "stale_plan_rejection", "state", "OK", "edt_ms", millis));
                        return null;
                    });
                }
            } else {
                boolean changed = edt(() -> buffer.textSnapshot() != beforeCommand);
                out("MD_META ", Map.of("operation", "unsupported_heading", "message", message[0], "source_changed", changed));
                if (changed) edt(() -> { buffer.getUndoManager().undo(); buffer.setModified(false); return null; });
            }
            long delta = edt(() -> editor.markdownController.headingAnalysis().diagnostics().submitted()) - submitted;
            if (eligible && delta != 0) throw new AssertionError("Current-result edit reparsed source");
            out("MD_META ", Map.of("operation", "edit_parse_delta", "submitted", delta));
            retained = null;
        }
        if (eligible) for (int i = 0; i < Math.min(10, samples); i++) {
            long began = System.nanoTime();
            edt(() -> {
                changedRevision(editor); editor.writingArea.setCaretPosition(0);
                long t = System.nanoTime(); String message = editor.markdownHeadingShift(true);
                out("MD_SAMPLE ", Map.of("operation", "cold_heading_request", "state", "OK", "edt_ms", (System.nanoTime() - t) / 1e6, "message", message));
                return null;
            });
            await(() -> editor.writingArea.getText(0, 3).equals("## "), 30);
            out("MD_SAMPLE ", Map.of("operation", "cold_heading_completion", "state", "OK", "observed_ms", (System.nanoTime() - began) / 1e6));
            edt(() -> { buffer.getUndoManager().undo(); buffer.setModified(false); return null; });
        }
        if (!original.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Paired probe changed source");
        settled(editor, "after_heading_operations");
    }
    private static void memoryRun(Texteditor editor, int samples, boolean semantic) throws Exception {
        FileBuffer buffer = edt(editor::getCurrentBuffer);
        settled(editor, "settled_before_semantics");
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var allocations = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
        if (semantic) edt(() -> {
            var service = new MarkdownAnalysisService(editor.asyncJobService, (source, cancelled) -> {
                long id = Thread.currentThread().threadId(), before = threads.getThreadAllocatedBytes(id), began = System.nanoTime();
                var result = MarkdownHeadingAnalysis.analyze(source, cancelled);
                allocations.add(Map.of("operation", "semantic_allocation", "state", "OK", "allocated_bytes", threads.getThreadAllocatedBytes(id) - before,
                    "worker_ms", (System.nanoTime() - began) / 1e6, "path", result.path().name()));
                return result;
            });
            Field f = editor.markdownController.getClass().getDeclaredField("headingAnalysis"); f.setAccessible(true); f.set(editor.markdownController, service);
            return null;
        });
        for (int i = 0; i < samples; i++) {
            edt(() -> { changedRevision(editor); if (semantic) editor.showOutline(); return null; });
            if (semantic) await(() -> {
                for (var w : editor.getOwnedWindows()) if (w instanceof MarkdownOutlineDialog && w.isDisplayable()) {
                    var a = (MarkdownHeadingAnalysis) field(w, "analysis");
                    return a != null && editor.isMarkdownSourceCurrent(buffer, a.source());
                }
                return false;
            }, 30);
            if (i == 0) settled(editor, "after_first_analysis_or_control");
        }
        settled(editor, "after_repeated_analysis_or_control");
        WeakReference<?> released = edt(() -> {
            for (var w : editor.getOwnedWindows()) if (w instanceof MarkdownOutlineDialog && w.isDisplayable()) return new WeakReference<>(field(w, "analysis"));
            return new WeakReference<>(null);
        });
        FileBuffer other = edt(() -> FileBuffer.createPlainScratch("memory control", "other"));
        edt(() -> { editor.buffers.add(other); editor.loadBufferIntoEditor(other); return null; });
        settled(editor, "after_buffer_switch");
        edt(() -> {
            for (var w : editor.getOwnedWindows()) if (w instanceof MarkdownOutlineDialog) w.dispose();
            editor.loadBufferIntoEditor(buffer); editor.closeActiveBufferWithoutExiting(true); return null;
        });
        settled(editor, "after_outline_and_source_close");
        out("MD_META ", Map.of("operation", "semantic_release", "collected", released.get() == null,
            "source_still_open", edt(() -> editor.buffers.contains(buffer)), "analyses", allocations.size()));
        allocations.forEach(r -> out("MD_SAMPLE ", r));
    }
}
