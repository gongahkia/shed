package shed;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in paired production command probe; reflection keeps the legacy control compatible. */
final class MarkdownSubtreeProbe {
    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static MarkdownHeadingAnalysis analyze(Texteditor editor) throws Exception {
        var result = new AtomicReference<MarkdownHeadingAnalysis>();
        edt(() -> { editor.requestMarkdownHeadings(new Object(), editor.getCurrentBuffer(), () -> true, result::set); return null; });
        await(() -> result.get() != null, 30); return result.get();
    }
    private static boolean highlighted(Texteditor editor) throws Exception {
        return ((WeakReference<?>) field(editor.syntaxUiController, "installedMarkdownText")).get() == editor.getCurrentBuffer().textSnapshot();
    }
    private static void settled(String phase) throws Exception {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
        memory(phase);
        var p = new ProcessBuilder("/bin/ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String value = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() == 0 && !value.isEmpty()) out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Double.parseDouble(value) / 1024));
    }
    private static String expected(String original, MarkdownSemanticSnapshot semantic) {
        int level = semantic.headings().getFirst().level(); var markers = new ArrayList<MarkdownSourceRange>();
        for (var heading : semantic.headings()) {
            if (!markers.isEmpty() && heading.level() <= level) break;
            markers.add(heading.marker().orElseThrow());
        }
        var result = new StringBuilder(original);
        for (int i = markers.size() - 1; i >= 0; i--) result.insert(markers.get(i).end(), '#');
        return result.toString();
    }
    static void run(Texteditor editor, int samples, int warmups) throws Exception {
        out("MD_META ", Map.of("operation", "subtree_startup", "analysis_initialized", edt(() -> field(editor.markdownController, "headingAnalysis") != null)));
        settled("subtree_before");
        var released = operations(editor, samples, warmups);
        settled("subtree_after_operations");
        out("MD_META ", Map.of("operation", "subtree_plan_lifetime", "plan_collected", released.get() == null));
        edt(() -> { editor.closeActiveBufferWithoutExiting(true); return null; });
        settled("subtree_after_close");
    }
    private static WeakReference<Object> operations(Texteditor editor, int samples, int warmups) throws Exception {
        var buffer = edt(editor::getCurrentBuffer); String original = edt(() -> buffer.textSnapshot().text());
        long initial = edt(() -> editor.markdownController.headingAnalysis().diagnostics().submitted());
        for (int i = -warmups; i < samples; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
            uiText("subtree_source_typing", () -> typed('x'), i >= 0);
            uiText("subtree_source_typing_undo", () -> buffer.getUndoManager().undo(), i >= 0);
        }
        if (edt(() -> editor.markdownController.headingAnalysis().diagnostics().submitted()) != initial)
            throw new AssertionError("Ordinary typing submitted semantic work");
        edt(() -> { buffer.setModified(false); return null; });
        Method planner = null;
        try { planner = Class.forName("shed.MarkdownSubtreeEditPlan").getDeclaredMethod("plan", MarkdownSemanticSnapshot.class, int.class, int.class, boolean.class); planner.setAccessible(true); }
        catch (ClassNotFoundException legacy) { out("MD_META ", Map.of("operation", "subtree_plan", "state", "LEGACY_CONTROL")); }
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        WeakReference<Object> released = new WeakReference<>(null);
        for (int i = -warmups; i < samples; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
            long beforeAnalysis = edt(() -> editor.markdownController.headingAnalysis().diagnostics().submitted());
            var analysis = analyze(editor); String expected = expected(original, analysis.semantic());
            long submitted = edt(() -> editor.markdownController.headingAnalysis().diagnostics().submitted());
            if (i >= 0 && submitted > beforeAnalysis)
                out("MD_SAMPLE ", Map.of("operation", "subtree_semantic_worker", "state", "OK", "worker_ms", analysis.workerNanos() / 1e6));
            Object proposal = null;
            if (planner != null) {
                long before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()), start = System.nanoTime();
                var result = planner.invoke(null, analysis.semantic(), 0, 0, true);
                double elapsed = (System.nanoTime() - start) / 1e6;
                var edit = result.getClass().getDeclaredMethod("edit"); edit.setAccessible(true);
                proposal = ((Optional<?>) edit.invoke(result)).orElse(null);
                if (i >= 0) out("MD_SAMPLE ", Map.of("operation", "subtree_pure_plan", "state", "OK", "worker_ms", elapsed,
                    "allocated_bytes", allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before));
                if (proposal == null) {
                    var source = edt(buffer::textSnapshot); int entries = edt(() -> ((BoundedUndoManager) buffer.getUndoManager()).retainedEditCount());
                    boolean measured = i >= 0;
                    edt(() -> {
                        long startEdt = System.nanoTime(); String message = editor.markdownSubtreeShift(true);
                        if (buffer.textSnapshot() != source || entries != ((BoundedUndoManager) buffer.getUndoManager()).retainedEditCount())
                            throw new AssertionError("Rejected operation mutated source/history");
                        if (measured) out("MD_SAMPLE ", Map.of("operation", "subtree_limit_rejection", "state", "OK", "edt_ms", (System.nanoTime() - startEdt) / 1e6, "message", message));
                        return null;
                    });
                    continue;
                }
                released = new WeakReference<>(proposal);
            }
            final String[] message = {""};
            uiText("subtree_current_edit", () -> message[0] = editor.markdownSubtreeShift(true), i >= 0);
            await(() -> buffer.textSnapshot().text().equals(expected), 30);
            if (i >= 0 && i < 5) {
                long start = System.nanoTime(); await(() -> highlighted(editor), 10);
                out("MD_SAMPLE ", Map.of("operation", "subtree_highlight_convergence", "state", "OK", "observed_ms", (System.nanoTime() - start) / 1e6));
            }
            uiText("subtree_undo", () -> buffer.getUndoManager().undo(), i >= 0);
            uiText("subtree_redo", () -> buffer.getUndoManager().redo(), i >= 0);
            edt(() -> { buffer.getUndoManager().undo(); buffer.setModified(false); return null; });
            if (proposal != null) {
                Object stale = proposal; boolean measured = i >= 0;
                edt(() -> {
                    var apply = MarkdownEdit.class.getDeclaredMethod("applyPlan", stale.getClass(), MarkdownSemanticSnapshot.Source.class,
                        FileBuffer.class, javax.swing.JTextArea.class, EditorSurface.class, java.util.function.BooleanSupplier.class);
                    apply.setAccessible(true);
                    var source = MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer);
                    long start = System.nanoTime(); var failure = (String) apply.invoke(null, stale, source, buffer, editor.writingArea,
                        editor.getActivePane().surface(), (java.util.function.BooleanSupplier) () -> true);
                    if (failure.isEmpty() || source.text() != buffer.textSnapshot()) throw new AssertionError("Stale plan was accepted");
                    if (measured) out("MD_SAMPLE ", Map.of("operation", "subtree_stale_rejection", "state", "OK", "edt_ms", (System.nanoTime() - start) / 1e6));
                    return null;
                });
            }
            if (edt(() -> editor.markdownController.headingAnalysis().diagnostics().submitted()) != submitted)
                throw new AssertionError("Current-result command parsed again");
        }
        // Isolate checked application from planning; the active fixture supplies its existing surface.
        if (planner != null) for (int i = 0; i < 5; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
            var analysis = analyze(editor);
            var result = planner.invoke(null, analysis.semantic(), 0, 0, true);
            var getEdit = result.getClass().getDeclaredMethod("edit"); getEdit.setAccessible(true);
            Object proposal = ((Optional<?>) getEdit.invoke(result)).orElse(null);
            if (proposal == null) break;
            var apply = MarkdownEdit.class.getDeclaredMethod("applyPlan", proposal.getClass(), MarkdownSemanticSnapshot.Source.class,
                FileBuffer.class, javax.swing.JTextArea.class, EditorSurface.class, java.util.function.BooleanSupplier.class);
            apply.setAccessible(true);
            uiText("subtree_checked_apply", () -> {
                var source = MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer);
                String failure = (String) apply.invoke(null, proposal, source, buffer, editor.writingArea,
                    editor.getActivePane().surface(), (java.util.function.BooleanSupplier) () -> true);
                if (!failure.isEmpty()) throw new AssertionError(failure);
            }, true);
            if (!expected(original, analysis.semantic()).equals(edt(() -> buffer.textSnapshot().text())))
                throw new AssertionError("Checked application changed non-marker source");
            edt(() -> { buffer.getUndoManager().undo(); buffer.setModified(false); return null; });
        }
        // Separately capture dispatch and asynchronous completion with the existing weak reuse cleared.
        if (!original.contains("capped subtree")) for (int i = 0; i < 5; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
            var analysis = analyze(editor); String expected = expected(original, analysis.semantic());
            edt(() -> { editor.markdownController.headingAnalysis().clear(); return null; });
            long began = System.nanoTime();
            edt(() -> {
                long start = System.nanoTime(); String message = editor.markdownSubtreeShift(true);
                out("MD_SAMPLE ", Map.of("operation", "subtree_cold_dispatch", "state", "OK", "edt_ms", (System.nanoTime() - start) / 1e6, "message", message)); return null;
            });
            await(() -> buffer.textSnapshot().text().equals(expected), 30);
            out("MD_SAMPLE ", Map.of("operation", "subtree_cold_completion", "state", "OK", "observed_ms", (System.nanoTime() - began) / 1e6));
            edt(() -> { buffer.getUndoManager().undo(); buffer.setModified(false); return null; });
        }
        if (!original.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Probe changed source");
        out("MD_META ", Map.of("operation", "subtree_integrity", "source_restored", true, "ordinary_typing_parse_requests", 0));
        return released;
    }
}
