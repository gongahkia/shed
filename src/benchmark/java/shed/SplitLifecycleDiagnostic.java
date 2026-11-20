package shed;

import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListenerProxy;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JTextArea;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in rooted-reference and Undo diagnostics; baseline failures are recorded, never suppressed in production. */
public final class SplitLifecycleDiagnostic {
    private static Texteditor editor;
    private static final List<WeakReference<JTextArea>> retired = new ArrayList<>();

    private static void state(String phase, FileBuffer buffer) throws Exception {
        out("SPLIT ", edt(() -> Map.of("operation", phase, "document_id", System.identityHashCode(buffer.getDocument()),
            "pane_id", System.identityHashCode(editor.getActivePane()), "history_id", System.identityHashCode(buffer.getUndoManager()),
            "revision", buffer.documentRevision(), "caret", editor.writingArea.getCaretPosition(),
            "document_text", buffer.getDocument().getText(0, buffer.getDocument().getLength()),
            "snapshot_text", buffer.textSnapshot().text(), "listeners", buffer.getDocument().getDocumentListeners().length)));
    }

    private static void references(FileBuffer buffer, int cycles) throws Exception {
        for (int i = 0; i < 5; i++) { edt(() -> null); System.gc(); Thread.sleep(100); }
        edt(() -> {
            int rooted = 0;
            for (var proxy : Toolkit.getDefaultToolkit().getAWTEventListeners()) {
                var listener = proxy instanceof AWTEventListenerProxy p ? p.getListener() : proxy;
                if (!listener.getClass().getName().startsWith("shed.")) continue;
                for (var field : listener.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    if (field.get(listener) == editor.editorUiController) rooted++;
                }
            }
            int retainedCaretEdges = 0;
            for (var listener : buffer.getDocument().getDocumentListeners()) {
                if (!listener.getClass().getName().equals("shed.BlockCaret$1")) continue;
                var outer = listener.getClass().getDeclaredField("this$0"); outer.setAccessible(true);
                Object caret = outer.get(listener);
                for (var view : retired) {
                    JTextArea area = view.get();
                    if (area != null && area.getCaret() == caret) retainedCaretEdges++;
                }
            }
            out("SPLIT ", Map.of("operation", "retention", "cycles", cycles,
                "toolkit_root_edges_to_editor_ui", rooted, "open_buffer_owned", editor.buffers.contains(buffer),
                "document_listeners", buffer.getDocument().getDocumentListeners().length,
                "retired_views_reachable", retired.stream().filter(r -> r.get() != null).count(),
                "document_to_retired_caret_edges", retainedCaretEdges));
            return null;
        });
    }

    private static void run(boolean corrected) throws Exception {
        FileBuffer buffer = edt(editor::getCurrentBuffer);
        String original = edt(buffer::getContent); Object history = buffer.getUndoManager();
        edt(() -> {
            buffer.getDocument().insertString(0, "draft ", null); buffer.getUndoManager().undo();
            editor.writingArea.setCaretPosition(1); editor.writingArea.moveCaretPosition(3);
            return null;
        });
        state("before_cycles", buffer); references(buffer, 0);
        for (int batch = 0; batch < 3; batch++) {
            for (int i = 0; i < 8; i++) edt(() -> {
                editor.splitWindow(true); EditorPane closing = editor.getActivePane();
                retired.add(new WeakReference<>(closing.getTextArea()));
                for (int j = 0; j < 8; j++) editor.cycleWindowFocus();
                editor.closeActiveWindow(); return null;
            });
            references(buffer, (batch + 1) * 8);
        }
        state("before_redo", buffer);
        int successful = 0;
        for (int i = 0; i < 51; i++) {
            final int sample = i;
            try {
                edt(() -> {
                    long start = System.nanoTime(); buffer.getUndoManager().redo();
                    out("SPLIT ", Map.of("operation", "redo", "sample", sample, "ms", (System.nanoTime() - start) / 1e6,
                        "revision", buffer.documentRevision(), "caret", editor.writingArea.getCaretPosition()));
                    if (!buffer.getContent().equals("draft " + original)) throw new AssertionError("Redo source mismatch");
                    start = System.nanoTime(); buffer.getUndoManager().undo();
                    out("SPLIT ", Map.of("operation", "undo", "sample", sample, "ms", (System.nanoTime() - start) / 1e6,
                        "revision", buffer.documentRevision(), "caret", editor.writingArea.getCaretPosition()));
                    if (!buffer.getContent().equals(original)) throw new AssertionError("Undo source mismatch");
                    if (history != buffer.getUndoManager()) throw new AssertionError("History ownership changed");
                    return null;
                });
                successful++;
            } catch (Throwable error) {
                error.printStackTrace(System.out); state("after_failure", buffer);
                out("SPLIT ", Map.of("operation", "undo_failure", "exception", error.toString()));
                if (corrected) throw error;
                break;
            }
        }
        out("SPLIT ", Map.of("operation", "completed_history_pairs", "count", successful));
        state("final_source", buffer);
    }

    public static void main(String[] args) throws Exception {
        int exit = 1;
        try {
            String path = Path.of(args[0]).toAbsolutePath().toString();
            editor = edt(() -> new Texteditor(new String[]{path}));
            await(() -> editor.getCurrentBuffer() != null && path.equals(editor.getCurrentBuffer().getFilePath()) && !editor.stagedStartupPending, 30);
            run(Boolean.parseBoolean(args[1])); System.out.println("SPLIT_DONE"); exit = 0;
        } catch (Throwable error) { error.printStackTrace(System.out); }
        finally {
            edt(() -> {
                if (editor != null) {
                    for (var timer : new javax.swing.Timer[]{editor.messageResetTimer, editor.externalChangeTimer, editor.recoverySnapshotTimer, editor.diagnosticRefreshTimer}) if (timer != null) timer.stop();
                    editor.fileWatcherService.stop(); editor.shutdownSyntaxUi(); editor.shutdownLspScheduling(); editor.shutdownRecoveryJournalScheduling();
                    editor.asyncJobService.shutdownNow(); editor.backupScheduler.close(); editor.treeGitController.closeFinderIndexes(); editor.paletteController.close();
                    editor.testController.shutdown(); editor.debugSessionController.shutdown(); editor.remoteWorkspaceController.closeAll();
                }
                for (Window window : Window.getWindows()) window.dispose(); return null;
            });
            System.exit(exit);
        }
    }
}
