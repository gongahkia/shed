package shed;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import static shed.MarkdownPerformanceProbe.*;

/** Paired opt-in navigation/real-picker measurements. Compiles against the pre-3A.2 artifact too. */
final class MarkdownNavigationProbe {
    private static final List<WeakReference<Window>> dialogs = new ArrayList<>();
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static long[] metric(Texteditor editor, String name) throws Exception {
        synchronized (editor.perfService) {
            var map = (Map<?, ?>) field(editor.perfService, "metrics"); Object value = map.get(name);
            return value == null ? new long[]{0, 0} : new long[]{(long) field(value, "count"), (long) field(value, "lastNanos")};
        }
    }
    private static boolean idle(Texteditor editor, boolean integrated) throws Exception {
        if (!integrated) return true;
        var method = MarkdownController.class.getDeclaredMethod("linkDiagnostics"); method.setAccessible(true); Object value = method.invoke(editor.markdownController);
        for (String name : List.of("active", "worker", "queued")) {
            var accessor = value.getClass().getDeclaredMethod(name); accessor.setAccessible(true); if ((int) accessor.invoke(value) != 0) return false;
        }
        return true;
    }
    static void run(Texteditor editor, int samples, int warmups) throws Exception {
        boolean integrated;
        try { MarkdownController.class.getDeclaredMethod("showLinks", boolean.class); integrated = true; }
        catch (NoSuchMethodException before) { integrated = false; }
        FileBuffer source = edt(editor::getCurrentBuffer); Path path = Path.of(source.getFilePath()); Path root = path.getParent();
        int count = source.textSnapshot().length() >= 1024 * 1024 ? 1000 : source.textSnapshot().length() >= 100 * 1024 ? 100 : 20;
        Path notes = Files.createDirectories(root.resolve("notes"));
        for (int i = 0; i < count; i++) {
            Path note = i == count - 2 ? notes.resolve("a/ambiguous.md") : i == count - 1 ? notes.resolve("b/ambiguous.md") : notes.resolve("note-" + i + ".md");
            Files.createDirectories(note.getParent());
            Files.writeString(note, "# Note " + i + "\n\n## Linked Heading\n\n" + "prose ".repeat(30) + "\n[[input]]\n"
                + "[[note-0]] [[note-1]] [[note-2]] [[note-3]] [[note-4]] [[note-5]] [[note-6]]\n");
        }
        String prefix = "[plain](notes/note-0.md)\n[heading](notes/note-0.md#linked-heading)\n[[note-0#Linked Heading|Alias]]\n[[ambiguous#Linked Heading]]\n";
        String original = edt(() -> source.textSnapshot().text());
        edt(() -> { source.setContent(prefix + original.substring(prefix.length())); source.setModified(true); editor.writingArea.setCaretPosition(2); editor.workspaceRoots.add(root); return null; });
        var expected = edt(source::textSnapshot); long revision = edt(source::documentRevision);
        for (int i = -warmups; i < samples; i++) {
            uiText("navigation_typing_control", () -> typed('x'), i >= 0);
            uiText("navigation_typing_control_undo", () -> source.getUndoManager().undo(), i >= 0);
        }
        expected = edt(source::textSnapshot); revision = edt(source::documentRevision);
        settle(editor, "before_index");
        edt(() -> { editor.markdownLinks().request(root); return null; });
        await(() -> editor.markdownLinks().diagnostics().running() == 0 && editor.markdownLinks().diagnostics().pending() == 0, 30);
        settle(editor, "before_navigation");
        final boolean current = integrated;
        Path target = notes.resolve("note-0.md");
        for (int i = -Math.min(warmups, 3); i < Math.min(samples, 30); i++) {
            edt(() -> { if (editor.getCurrentBuffer() != source) editor.switchToBuffer(editor.buffers.indexOf(source)); editor.writingArea.setCaretPosition(2); return null; });
            long began = System.nanoTime();
            double dispatch = edt(() -> { long start = System.nanoTime(); editor.goToMarkdownLink(); return (System.nanoTime() - start) / 1e6; });
            await(() -> editor.getCurrentBuffer() != source && editor.getCurrentBuffer().getFilePath().endsWith("note-0.md"), 30);
            edt(() -> { editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect()); return null; });
            if (i >= 0) {
                out("MD_SAMPLE ", Map.of("operation", "follow_plain", "state", "OK", "edt_ms", dispatch, "navigation_to_forced_paint_ms", (System.nanoTime() - began) / 1e6));
                if (current) {
                    out("MD_SAMPLE ", Map.of("operation", "validation_worker", "state", "OK", "worker_ms", edt(() -> metric(editor, "markdown.links.validate")[1]) / 1e6));
                    out("MD_SAMPLE ", Map.of("operation", "source_identity_worker", "state", "OK", "worker_ms", edt(() -> metric(editor, "markdown.links.source")[1]) / 1e6));
                }
            }
        }
        settle(editor, "after_follow_navigation");
        for (int i = 0; i < Math.min(samples, 20); i++) {
            edt(() -> { editor.switchToBuffer(editor.buffers.indexOf(source)); editor.writingArea.setCaretPosition(2); return null; });
            picker(editor, current, "Markdown Outgoing Links", "markdown outgoing", false, null, i);
            picker(editor, current, "Markdown Outgoing Links", "markdown outgoing", true, null, i);
            if (current) {
                await(() -> editor.getCurrentBuffer() != source, 30);
                edt(() -> { editor.switchToBuffer(editor.buffers.indexOf(source)); editor.writingArea.setCaretPosition(prefix.indexOf("ambiguous") + 3); return null; });
                picker(editor, true, "Markdown Link Targets", "markdown link follow", false, null, i);
            }
            edt(() -> { if (editor.getCurrentBuffer() == source) editor.openFile(target.toFile()); return null; });
            picker(editor, current, "Markdown Backlinks", "markdown backlinks", false, null, i);
            if (current) {
                picker(editor, true, "Markdown Backlinks", "markdown backlinks", true, null, i);
                await(() -> idle(editor, true), 30);
            }
        }
        edt(() -> { if (editor.getCurrentBuffer() != source) editor.switchToBuffer(editor.buffers.indexOf(source)); editor.writingArea.setCaretPosition(2); return null; });
        settle(editor, "after_picker_cycles");
        out("MD_META ", Map.of("operation", "picker_collection", "opened", dialogs.size(), "still_referenced", dialogs.stream().filter(reference -> reference.get() != null).count()));
        // Index refresh is the unchanged 3A.1 worker; this phase observes actual typing while it is outstanding.
        edt(() -> { editor.markdownLinks().refresh(root); return null; }); int overlap = 0;
        for (int i = 0; i < samples; i++) {
            if (edt(() -> editor.markdownLinks().diagnostics().running() != 0)) overlap++;
            uiText("navigation_indexing_typing", () -> typed('x'), true);
            uiText("navigation_indexing_undo", () -> source.getUndoManager().undo(), true);
        }
        await(() -> editor.markdownLinks().diagnostics().running() == 0 && editor.markdownLinks().diagnostics().pending() == 0, 30);
        out("MD_META ", Map.of("operation", "navigation_workload", "integrated", current, "files", count + 1, "typing_overlap", overlap,
            "index_metrics", edt(() -> editor.markdownLinks().metrics(root).orElseThrow().toString())));
        long actualRevision = edt(source::documentRevision);
        out("MD_META ", Map.of("operation", "source_preservation", "navigation_revision_delta", actualRevision - revision - samples * 2));
        if (!edt(() -> source.textSnapshot().text()).equals(prefix + original.substring(prefix.length()))) throw new AssertionError("Navigation altered source");
        edt(() -> {
            for (FileBuffer buffer : List.copyOf(editor.buffers)) if (buffer != source) { editor.buffers.remove(buffer); editor.markdownSourceClosed(buffer); }
            editor.markdownLinks().closeScope(root); return null;
        });
        settle(editor, "after_project_close");
        out("MD_META ", Map.of("operation", "closed_navigation", "partitions", edt(() -> editor.markdownLinks().diagnostics().partitions()), "interaction_idle", edt(() -> idle(editor, current))));
    }
    private static void picker(Texteditor editor, boolean integrated, String title, String command, boolean accept,
                               String filter, int iteration) throws Exception {
        var done = new CountDownLatch(1); var failure = new AtomicReference<Throwable>(); 
        long began = System.nanoTime(); var dispatch = new AtomicReference<Double>(0d); var acceptedAt = new AtomicLong();
        edt(() -> {
            var driver = new javax.swing.Timer(5, event -> {
                for (Window window : editor.getOwnedWindows()) if (window instanceof JDialog dialog && dialog.isShowing() && dialog.getTitle().startsWith(title)) {
                    ((javax.swing.Timer) event.getSource()).stop(); dialogs.add(new WeakReference<>(dialog));
                    try {
                        dialog.getRootPane().paintImmediately(dialog.getRootPane().getVisibleRect());
                        double observed = (System.nanoTime() - began) / 1e6;
                        out("MD_SAMPLE ", Map.of("operation", (integrated ? "" : "control_") + command.replace(' ', '_') + (accept ? "_accept" : "_cancel"),
                            "state", "OK", "observed_to_picker_paint_ms", observed));
                        if (accept) {
                            acceptedAt.set(System.nanoTime());
                            JTextField field = descendants(dialog, JTextField.class).getFirst();
                            field.postActionEvent();
                        } else dialog.getRootPane().getActionMap().get("dismiss").actionPerformed(new ActionEvent(dialog, 0, "escape"));
                    } catch (Throwable error) { failure.set(error); dialog.dispose(); }
                    finally { done.countDown(); }
                    break;
                }
            }); driver.start();
            long started = System.nanoTime();
            if (integrated) editor.commandHandler.execute(command);
            else {
                var buffer = editor.getCurrentBuffer(); Path file = Path.of(buffer.getFilePath());
                var query = command.endsWith("backlinks") ? editor.markdownLinks().backlinks(file.getParent().getParent(), file) : editor.markdownLinks().outgoing(file.getParent(), file);
                var labels = new ArrayList<String>();
                for (var edge : query.edges()) {
                    if (labels.size() == 200) break;
                    labels.add(edge.occurrence().kind().name().toLowerCase(Locale.ROOT) + " · " + edge.occurrence().destination()
                        + " [" + edge.resolution().status().name().toLowerCase(Locale.ROOT) + "] " + edge.occurrence().source().file().getFileName()
                        + " @UTF-16 " + edge.occurrence().offset() + " [#" + (labels.size() + 1) + "]");
                }
                editor.showPaletteDialog(title, labels.isEmpty() ? List.of("No known links (control)") : labels);
            }
            dispatch.set((System.nanoTime() - started) / 1e6); return null;
        });
        if (!done.await(30, TimeUnit.SECONDS)) throw new AssertionError("Picker did not appear: " + title);
        if (failure.get() != null) throw new AssertionError(failure.get());
        if (integrated) await(() -> idle(editor, true), 30);
        if (integrated && accept) {
            edt(() -> { editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect()); return null; });
            out("MD_SAMPLE ", Map.of("operation", command.replace(' ', '_') + "_selection_navigation", "state", "OK",
                "selection_to_forced_paint_ms", (System.nanoTime() - acceptedAt.get()) / 1e6));
        }
        if (integrated && !command.endsWith("follow")) out("MD_SAMPLE ", Map.of("operation", command.replace(' ', '_') + "_population_edt", "state", "OK", "edt_ms", edt(() -> metric(editor, "markdown.links.rows")[1]) / 1e6));
    }
    private static <T extends Component> List<T> descendants(Container parent, Class<T> type) {
        var result = new ArrayList<T>();
        for (Component child : parent.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container container) result.addAll(descendants(container, type)); }
        return result;
    }
    private static void settle(Texteditor editor, String phase) throws Exception {
        await(() -> {
            for (Object owner : List.of(editor, editor.syntaxUiController, editor.inputController, editor.recoveryController, editor.lspController, editor.paneBufferController)) {
                for (var f : owner.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == javax.swing.Timer.class) {
                        var timer = (javax.swing.Timer) f.get(owner); if (timer != null && !timer.isRepeats() && timer.isRunning()) return false;
                    } else if (Map.class.isAssignableFrom(f.getType())) {
                        var map = (Map<?, ?>) f.get(owner); if (map != null) for (Object value : map.values())
                            if (value instanceof javax.swing.Timer timer && !timer.isRepeats() && timer.isRunning()) return false;
                    }
                }
            }
            return editor.asyncJobService.list().stream().noneMatch(job -> job.getStatus() == AsyncJobService.Status.RUNNING);
        }, 30);
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(60); }
        memory(phase);
        var process = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String rss = new String(process.getInputStream().readAllBytes()).strip(); process.waitFor();
        out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Long.parseLong(rss) / 1024d));
    }
}
