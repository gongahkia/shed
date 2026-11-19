package shed;

import java.awt.*;
import java.awt.event.*;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in paired gallery measurements; reflection keeps the probe compatible with the pre-8E JAR. */
public final class ThemePreviewPerformanceProbe {
    private static Texteditor editor;
    private static ThemeGalleryDialog gallery;
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static long[] metric() throws Exception {
        synchronized (editor.perfService) {
            var field = PerfService.class.getDeclaredField("metrics"); field.setAccessible(true);
            Object entry = ((Map<?, ?>) field.get(editor.perfService)).get("edit.input-to-paint");
            if (entry == null) return new long[]{0, 0};
            var count = entry.getClass().getDeclaredField("count"); count.setAccessible(true);
            var last = entry.getClass().getDeclaredField("lastNanos"); last.setAccessible(true);
            return new long[]{count.getLong(entry), last.getLong(entry)};
        }
    }

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof Container container) for (Component child : container.getComponents()) result.addAll(descendants(child, type));
        return result;
    }

    private static ThemeGalleryDialog openGallery() {
        ThemeGalleryDialog.showFor(editor);
        return Arrays.stream(editor.getOwnedWindows()).filter(ThemeGalleryDialog.class::isInstance)
            .map(ThemeGalleryDialog.class::cast).filter(Window::isDisplayable).findFirst().orElseThrow();
    }

    private static void select(String id) {
        for (var list : descendants(gallery, JList.class)) for (int i = 0; i < list.getModel().getSize(); i++) {
            if (list.getModel().getElementAt(i) instanceof ThemePreview theme && theme.id().equals(id)) { list.setSelectedIndex(i); return; }
        }
        throw new AssertionError("Unknown gallery theme " + id);
    }

    private static void preview(String theme) throws Exception {
        select(theme);
        var method = ThemeGalleryDialog.class.getDeclaredMethod("previewSelectedTheme"); method.setAccessible(true); method.invoke(gallery);
    }

    private static void timed(String name, MarkdownPerformanceProbe.Work work) throws Exception {
        out("THEME ", edt(() -> {
            long allocated = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()), start = System.nanoTime();
            work.run(); long applied = System.nanoTime(); editor.validate(); long layout = System.nanoTime();
            editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());
            if (gallery != null && gallery.isShowing()) gallery.getRootPane().paintImmediately(gallery.getRootPane().getVisibleRect());
            long finish = System.nanoTime();
            return Map.of("operation", name, "apply_ms", (applied - start) / 1e6, "validate_ms", (layout - applied) / 1e6,
                "forced_paint_ms", (finish - layout) / 1e6, "edt_total_ms", (finish - start) / 1e6,
                "edt_allocated_bytes", THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated);
        }));
        edt(() -> null);
    }

    private static void typing(String phase) throws Exception {
        var buffer = edt(editor::getCurrentBuffer);
        for (int i = 0; i < 51; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(16); return null; });
            long count = edt(() -> metric()[0]);
            double dispatch = edt(() -> {
                long start = System.nanoTime(); var area = editor.writingArea;
                KeyEvent event = new KeyEvent(area, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_X, 'x');
                editor.keyPressed(event);
                if (!event.isConsumed()) area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(area, 0, "x"));
                return (System.nanoTime() - start) / 1e6;
            });
            await(() -> metric()[0] > count, 5);
            out("THEME ", Map.of("operation", phase + (i == 0 ? "_first_input" : "_warm_input"), "dispatch_ms", dispatch,
                "input_to_paint_ms", edt(() -> metric()[1] / 1e6)));
            edt(() -> { buffer.getUndoManager().undo(); return null; });
        }
    }

    private static void heap(String phase) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out("THEME ", Map.of("operation", phase, "used_mib", heap.getUsed() / 1048576d, "committed_mib", heap.getCommitted() / 1048576d,
            "threads", ManagementFactory.getThreadMXBean().getThreadCount()));
    }

    public static void main(String[] args) throws Exception {
        int exit = 1;
        try {
            Path file = Path.of(args[0]); boolean live = Boolean.parseBoolean(args[1]), split = Boolean.parseBoolean(args[2]), html = Boolean.parseBoolean(args[3]);
            long start = System.nanoTime();
            editor = edt(() -> new Texteditor(new String[]{file.toString()}));
            await(() -> editor.getCurrentBuffer() != null && file.toString().equals(editor.getCurrentBuffer().getFilePath()) && !editor.stagedStartupPending, 30);
            out("THEME ", Map.of("operation", "launch_to_loaded_editor", "ms", (System.nanoTime() - start) / 1e6));
            edt(() -> {
                if (split) { editor.splitWindow(true); editor.commandHandler.execute("focus on"); }
                if (html) editor.openMarkdownPreview();
                editor.validate(); editor.setMode(EditorMode.INSERT); return null;
            });
            Thread.sleep(750);
            heap("ready_no_gc");
            for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
            heap("ready_requested_gc");
            typing("before");
            gallery = edt(ThemePreviewPerformanceProbe::openGallery);
            var buffer = edt(editor::getCurrentBuffer); var snapshot = edt(buffer::textSnapshot);
            long revision = edt(buffer::documentRevision); boolean dirty = edt(buffer::isModified);
            int listeners = edt(() -> buffer.getDocument().getDocumentListeners().length);
            for (int i = 0; i < 10; i++) {
                final int cycle = i;
                timed(i == 0 ? "first_switch" : "repeat_switch", () -> {
                    if (live) preview("dracula"); else editor.setThemeFromCommand("dracula");
                });
                timed(i == 0 ? "first_cancel" : "repeat_cancel", () -> {
                    if (live) { var method = ThemeGalleryDialog.class.getDeclaredMethod("cancelPreview"); method.setAccessible(true); method.invoke(gallery); }
                    else editor.setThemeFromCommand("shed-light");
                });
                if (cycle < 3) {
                    timed("rapid_preview_a", () -> { if (live) preview("nib-dark"); else editor.setThemeFromCommand("nib-dark"); });
                    timed("rapid_preview_b", () -> { if (live) select("catppuccin-latte"); else editor.setThemeFromCommand("catppuccin-latte"); });
                    timed("rapid_cancel", () -> {
                        if (live) { var method = ThemeGalleryDialog.class.getDeclaredMethod("cancelPreview"); method.setAccessible(true); method.invoke(gallery); }
                        else editor.setThemeFromCommand("shed-light");
                    });
                }
            }
            edt(() -> {
                if (snapshot != buffer.textSnapshot() || revision != buffer.documentRevision() || dirty != buffer.isModified()) throw new AssertionError("Theme switch changed source");
                if (listeners != buffer.getDocument().getDocumentListeners().length) throw new AssertionError("Document listener growth");
                return null;
            });
            {
                timed("preview_before_accept", () -> {
                    if (live) preview("dracula");
                    else { select("dracula"); editor.setThemeFromCommand("dracula"); }
                });
                timed("accept_and_save", () -> {
                    var method = ThemeGalleryDialog.class.getDeclaredMethod("applySelectedTheme"); method.setAccessible(true); method.invoke(gallery);
                });
                if (!edt(() -> "dracula".equals(editor.configManager.get("theme")))) throw new AssertionError("Acceptance did not commit");
                timed("restore_manual", () -> editor.setThemeFromCommand("shed-light"));
            }
            List<WeakReference<ThemeGalleryDialog>> closed = new ArrayList<>();
            edt(() -> { gallery.dispose(); closed.add(new WeakReference<>(gallery)); gallery = null; return null; });
            for (int i = 0; i < 8; i++) timed("gallery_open_close", () -> {
                var next = openGallery(); next.dispose(); closed.add(new WeakReference<>(next));
            });
            typing("after");
            heap("cycles_no_gc");
            for (int i = 0; i < 5; i++) { edt(() -> null); System.gc(); Thread.sleep(100); }
            heap("cycles_requested_gc");
            out("THEME ", Map.of("operation", "lifecycle", "closed_galleries", closed.size(), "reachable_closed_galleries", closed.stream().filter(ref -> ref.get() != null).count(),
                "source_unchanged_by_themes", true));
            edt(() -> {
                for (var timer : new javax.swing.Timer[]{editor.messageResetTimer, editor.externalChangeTimer, editor.recoverySnapshotTimer, editor.diagnosticRefreshTimer}) if (timer != null) timer.stop();
                editor.fileWatcherService.stop(); editor.shutdownSyntaxUi(); editor.shutdownLspScheduling(); editor.disposeMarkdownPreviews();
                editor.asyncJobService.shutdownNow(); editor.backupScheduler.close(); editor.dispose(); return null;
            });
            editor = null;
            for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(100); }
            heap("disposed_requested_gc"); System.out.println("THEME_DONE"); exit = 0;
        } catch (Throwable failure) { failure.printStackTrace(); }
        finally { edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; }); System.exit(exit); }
    }
}
