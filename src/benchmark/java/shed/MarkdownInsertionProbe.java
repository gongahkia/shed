package shed;

import java.util.Map;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in same-probe controls against the previous packaged application. */
final class MarkdownInsertionProbe {
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void settled(String phase) throws Exception {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
        memory(phase);
        var process = new ProcessBuilder("/bin/ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String value = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() == 0 && !value.isEmpty()) out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Double.parseDouble(value) / 1024));
    }
    static void run(Texteditor editor, int samples, int warmups) throws Exception {
        boolean modern;
        try { Class.forName("shed.MarkdownService$Insertion"); modern = true; }
        catch (ClassNotFoundException legacy) { modern = false; }
        out("MD_META ", Map.of("operation", "insertion_startup", "selected_text_supported", modern,
            "analysis_initialized", edt(() -> field(editor.markdownController, "headingAnalysis") != null),
            "links_initialized", edt(() -> field(editor, "markdownLinks") != null)));
        settled("insertion_before");
        operations(editor, samples, warmups, modern);
        settled("insertion_after_operations");
        edt(() -> { editor.closeActiveBufferWithoutExiting(true); return null; });
        settled("insertion_after_close");
    }
    private static void operations(Texteditor editor, int samples, int warmups, boolean modern) throws Exception {
        var buffer = edt(editor::getCurrentBuffer);
        String original = edt(() -> buffer.textSnapshot().text());
        int at = original.indexOf("alpha beta");
        if (at < 0) throw new AssertionError("Mixed fixture required");
        for (int i = -warmups; i < samples; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(at); return null; });
            uiText("insertion_source_typing", () -> typed('x'), i >= 0);
            uiText("insertion_source_typing_undo", () -> buffer.getUndoManager().undo(), i >= 0);
        }
        for (boolean image : new boolean[]{false, true}) for (boolean selected : new boolean[]{false, true}) {
            String name = (image ? "image_" : "link_") + (selected ? "selected" : "empty");
            edt(() -> { buffer.getUndoManager().discardAllEdits(); return null; });
            for (int i = -warmups; i < samples; i++) {
                boolean reverse = i % 2 == 0;
                int dot = selected && !reverse ? at + 10 : at, mark = selected && reverse ? at + 10 : at;
                edt(() -> { editor.writingArea.setCaretPosition(mark); editor.writingArea.moveCaretPosition(dot); return null; });
                String replacement = image ? "![alt](path)" : "[text](url)";
                int start = dot, end = dot;
                if (selected && modern) { start = at; end = at + 10; replacement = image ? "![alpha beta](path)" : "[alpha beta](url)"; }
                String expected = original.substring(0, start) + replacement + original.substring(end);
                uiText(name + "_command", () -> editor.commandHandler.execute(image ? "markdown image insert" : "markdown link insert"), i >= 0);
                if (!expected.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Unexpected command source");
                if (selected && modern && !edt(() -> (image ? "path" : "url").equals(editor.writingArea.getSelectedText())))
                    throw new AssertionError("Destination not selected");
                uiText(name + "_undo", () -> buffer.getUndoManager().undo(), i >= 0);
                if (!original.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Undo source mismatch");
                uiText(name + "_redo", () -> buffer.getUndoManager().redo(), i >= 0);
                if (!expected.equals(edt(() -> buffer.textSnapshot().text()))) throw new AssertionError("Redo source mismatch");
                edt(() -> { buffer.getUndoManager().undo(); buffer.setModified(false); return null; });
            }
        }
        if (edt(() -> field(editor.markdownController, "headingAnalysis") != null || field(editor, "markdownLinks") != null))
            throw new AssertionError("Insertion or typing initialized semantic/link services");
        out("MD_META ", Map.of("operation", "insertion_integrity", "source_restored", original.equals(edt(() -> buffer.textSnapshot().text())),
            "new_semantic_or_link_services", false));
    }
}
