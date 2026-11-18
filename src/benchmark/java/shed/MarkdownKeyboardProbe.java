package shed;

import java.lang.ref.WeakReference;
import java.util.Map;
import static shed.MarkdownPerformanceProbe.*;

/** Explicit paired production-input probe, compiled against each measured packaged JAR. */
final class MarkdownKeyboardProbe {
    private static Object field(Object owner, String name) throws Exception {
        var value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner);
    }
    private static boolean highlighted(Texteditor editor) throws Exception {
        return ((WeakReference<?>) field(editor.syntaxUiController, "installedMarkdownText")).get() == editor.getCurrentBuffer().textSnapshot();
    }
    private static void settle(String phase) throws Exception {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
        memory(phase);
        var child = new ProcessBuilder("/bin/ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String rss = new String(child.getInputStream().readAllBytes()).trim();
        if (child.waitFor() == 0 && !rss.isEmpty()) out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Double.parseDouble(rss) / 1024));
    }
    private static void position(Texteditor editor, int offset) throws Exception {
        long generation = edt(() -> (long) field(editor.syntaxUiController, "syntaxGeneration"));
        edt(() -> {
            editor.writingArea.setCaretPosition(offset);
            var bounds = editor.writingArea.modelToView2D(offset);
            if (bounds != null) editor.writingArea.scrollRectToVisible(bounds.getBounds());
            editor.applySyntaxHighlighting(); return null;
        });
        await(() -> highlighted(editor) && (long) field(editor.syntaxUiController, "syntaxGeneration") > generation
            && (int) field(editor.syntaxUiController, "syntaxJobId") == -1, 15);
    }
    static void run(Texteditor editor, int samples, int warmups) throws Exception {
        boolean modern;
        try { Class.forName("shed.MarkdownKeyboardContext"); modern = true; }
        catch (ClassNotFoundException legacy) { modern = false; }
        out("MD_META ", Map.of("operation", "keyboard_startup", "context_guard", modern,
            "semantic_initialized", edt(() -> field(editor.markdownController, "headingAnalysis") != null),
            "links_initialized", edt(() -> field(editor, "markdownLinks") != null)));
        settle("keyboard_before"); operations(editor, samples, warmups, modern); settle("keyboard_after_operations");
        edt(() -> { editor.closeActiveBufferWithoutExiting(true); return null; }); settle("keyboard_after_close");
    }
    private static void operations(Texteditor editor, int samples, int warmups, boolean modern) throws Exception {
        var buffer = edt(editor::getCurrentBuffer); String original = edt(() -> buffer.textSnapshot().text());
        int quote = original.lastIndexOf("> > nested quote") + "> > nested quote".length();
        int list = original.lastIndexOf("- unordered") + "- unordered".length();
        int fence = original.lastIndexOf("```java") + "```jav".length();
        int plain = original.lastIndexOf("alpha beta") + 10;
        if (quote < 16 || list < 11 || fence < 6 || plain < 10) throw new AssertionError("Mixed fixture required");
        position(editor, plain);
        for (int i = -warmups; i < samples; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(plain); return null; });
            uiText("keyboard_source_typing", () -> typed('x'), i >= 0);
            uiText("keyboard_source_typing_undo", () -> buffer.getUndoManager().undo(), i >= 0);
        }
        for (String operation : new String[]{"quote_enter", "list_enter", "plain_enter", "opening_completion"}) {
            boolean completion = operation.equals("opening_completion");
            int offset = completion ? fence : operation.equals("quote_enter") ? quote : operation.equals("list_enter") ? list : plain;
            if (completion) edt(() -> { editor.writingArea.replaceRange("", fence, fence + 1); return null; });
            String base = completion ? original.substring(0, fence) + original.substring(fence + 1) : original;
            String inserted = operation.equals("quote_enter") ? (modern ? "\n> > " : "\n> ") : operation.equals("list_enter") ? "\n- " : "\n";
            String expected = completion ? original : original.substring(0, offset) + inserted + original.substring(offset);
            position(editor, offset); edt(() -> { buffer.getUndoManager().discardAllEdits(); return null; });
            for (int i = -warmups; i < samples; i++) {
                edt(() -> { editor.writingArea.setCaretPosition(offset); return null; });
                uiText(operation, () -> { if (completion) editor.completeCodeFenceLanguage(); else typed('\n'); }, i >= 0);
                if (!expected.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError(operation + " produced incorrect source");
                if (i >= 0 && i < 5) {
                    long start = System.nanoTime(); await(() -> highlighted(editor), 15);
                    out("MD_SAMPLE ", Map.of("operation", operation + "_highlight", "state", "OK", "observed_ms", (System.nanoTime() - start) / 1e6,
                        "perf_report", edt(() -> editor.perfService.report())));
                }
                uiText(operation + "_undo", () -> buffer.getUndoManager().undo(), i >= 0);
                if (!base.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Undo failed");
                uiText(operation + "_redo", () -> buffer.getUndoManager().redo(), i >= 0);
                if (!expected.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Redo failed");
                edt(() -> { buffer.getUndoManager().undo(); buffer.setModified(false); return null; });
            }
            if (completion) edt(() -> { editor.writingArea.insert("a", fence); buffer.setModified(false); return null; });
        }
        int closing = original.lastIndexOf("```\n\nInline");
        if (closing < 0) throw new AssertionError("Closing fixture missing");
        position(editor, closing + 3);
        for (int i = -warmups; i < samples; i++) {
            boolean measured = i >= 0;
            edt(() -> {
                editor.writingArea.setCaretPosition(closing + 3); var source = buffer.textSnapshot();
                long start = System.nanoTime(); String message = editor.completeCodeFenceLanguage(); long elapsed = System.nanoTime() - start;
                boolean changed = buffer.textSnapshot() != source;
                if (modern && changed || !modern && !changed) throw new AssertionError("Incorrect closing-fence behavior");
                if (measured) out("MD_SAMPLE ", Map.of("operation", "closing_completion", "state", "OK", "wall_ms", elapsed / 1e6,
                    "mutated", changed, "message", message));
                if (changed) buffer.getUndoManager().undo(); buffer.setModified(false); return null;
            });
        }
        if (!original.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Probe did not restore source");
        if (edt(() -> field(editor.markdownController, "headingAnalysis") != null || field(editor, "markdownLinks") != null))
            throw new AssertionError("Keyboard operations initialized semantic/link services");
        out("MD_META ", Map.of("operation", "keyboard_integrity", "source_restored", true, "new_semantic_or_link_services", false));
    }
}
