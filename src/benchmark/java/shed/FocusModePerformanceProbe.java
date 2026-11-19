package shed;

import java.awt.*;
import java.awt.event.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.Map;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in production-editor measurements, compatible with the pre-8D JAR. */
public final class FocusModePerformanceProbe {
    private static Texteditor editor;
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
    private static void memory(String name) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out("FOCUS ", Map.of("operation", name, "used_mib", heap.getUsed() / 1048576d, "committed_mib", heap.getCommitted() / 1048576d));
    }
    private static void toggle(String name) throws Exception {
        var values = edt(() -> {
            long began = System.nanoTime(); editor.toggleGoyoMode(); long dispatch = System.nanoTime();
            editor.validate(); long layout = System.nanoTime();
            editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());
            return Map.<String, Object>of("operation", name, "dispatch_ms", (dispatch - began) / 1e6,
                "validate_ms", (layout - dispatch) / 1e6, "forced_root_paint_ms", (System.nanoTime() - layout) / 1e6);
        });
        out("FOCUS ", values); edt(() -> null);
    }
    private static boolean edit(String name, Runnable work) throws Exception {
        long before = edt(() -> metric()[0]);
        double dispatch = edt(() -> { long start = System.nanoTime(); work.run(); return (System.nanoTime() - start) / 1e6; });
        try { await(() -> metric()[0] > before, 3); }
        catch (java.util.concurrent.TimeoutException missing) {
            out("FOCUS ", Map.of("operation", name, "state", "PAINT_UNAVAILABLE", "dispatch_ms", dispatch)); return false;
        }
        out("FOCUS ", Map.of("operation", name, "dispatch_ms", dispatch, "input_to_paint_ms", edt(() -> metric()[1] / 1e6)));
        return true;
    }
    private static void type() {
        JTextArea area = editor.writingArea;
        KeyEvent key = new KeyEvent(area, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_X, 'x');
        editor.keyPressed(key);
        if (!key.isConsumed()) area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(area, 0, "x"));
    }
    private static String documentDigest() throws Exception {
        return edt(() -> {
            var digest = MessageDigest.getInstance("SHA-256"); var document = editor.writingArea.getDocument();
            for (int i = 0; i < document.getLength(); i += 8192)
                digest.update(document.getText(i, Math.min(8192, document.getLength() - i)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        });
    }
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]); boolean wrap = Boolean.parseBoolean(args[1]), split = Boolean.parseBoolean(args[2]);
        int exit = 1;
        try {
            editor = edt(() -> new Texteditor(new String[]{file.toString()}));
            await(() -> editor.getCurrentBuffer() != null && file.toString().equals(editor.getCurrentBuffer().getFilePath()) && !editor.stagedStartupPending, 30);
            edt(() -> {
                editor.configManager.set("zen.mode.width", "60");
                editor.writingArea.setLineWrap(wrap); editor.writingArea.setWrapStyleWord(wrap);
                if (split) { editor.splitWindow(true); editor.writingArea.setLineWrap(wrap); editor.writingArea.setWrapStyleWord(wrap); }
                editor.validate(); editor.setMode(EditorMode.INSERT);
                if (editor.messageResetTimer != null) editor.messageResetTimer.stop();
                return null;
            });
            await(() -> editor.writingArea.isShowing() && editor.writingArea.getWidth() > 0, 10);
            Thread.sleep(500); String digest = documentDigest(); memory("ready_no_gc");
            for (String phase : new String[]{"normal", "focus"}) {
                if (phase.equals("focus")) { toggle("focus_first_enable"); Thread.sleep(150); }
                var buffer = edt(editor::getCurrentBuffer);
                for (int i = 0; i < 51; i++) {
                    edt(() -> { editor.writingArea.setCaretPosition(16); return null; });
                    boolean painted = edit(phase + (i == 0 ? "_first_insert" : "_warm_insert"), FocusModePerformanceProbe::type);
                    out("FOCUS ", edt(() -> {
                        long start = System.nanoTime(); buffer.getUndoManager().undo(); long mutation = System.nanoTime();
                        editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());
                        return Map.of("operation", phase + "_undo_forced_paint", "dispatch_ms", (mutation - start) / 1e6,
                            "forced_paint_ms", (System.nanoTime() - mutation) / 1e6);
                    }));
                    if (!painted) break;
                }
                for (int i = 0; i < 6; i++) {
                    final int index = i;
                    out("FOCUS ", edt(() -> {
                        long start = System.nanoTime(); var viewport = editor.getActivePane().getScrollPane().getViewport();
                        viewport.setViewPosition(new Point(0, index % 2 * 100)); editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());
                        return Map.of("operation", phase + "_scroll_forced_paint", "ms", (System.nanoTime() - start) / 1e6);
                    }));
                }
                memory(phase + "_no_gc");
            }
            toggle("focus_first_disable");
            for (int i = 0; i < 12; i++) { toggle("repeat_enable"); toggle("repeat_disable"); }
            for (int i = 0; i < 4; i++) {
                final int index = i; toggle("resize_enable");
                out("FOCUS ", edt(() -> { long start = System.nanoTime(); editor.setSize(1100 + index % 2 * 80, 760); editor.validate();
                    editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());
                    return Map.of("operation", "resize_forced_paint", "ms", (System.nanoTime() - start) / 1e6); }));
                toggle("resize_disable");
            }
            if (!digest.equals(documentDigest())) throw new AssertionError("Source changed after toggles/Undo");
            for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
            memory("after_cycles_requested_gc");
            out("FOCUS ", edt(() -> Map.of("operation", "source_integrity", "exact", true, "panes", editor.editorPanes.size(), "goyo", editor.goyoModeEnabled)));
            edt(() -> {
                for (var timer : new javax.swing.Timer[]{editor.messageResetTimer, editor.externalChangeTimer, editor.recoverySnapshotTimer, editor.diagnosticRefreshTimer})
                    if (timer != null) timer.stop();
                editor.fileWatcherService.stop(); editor.shutdownSyntaxUi(); editor.shutdownLspScheduling();
                editor.asyncJobService.shutdownNow(); if (editor.backupScheduler != null) editor.backupScheduler.close();
                editor.dispose(); return null;
            });
            editor = null;
            for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
            memory("disposed_requested_gc"); System.out.println("FOCUS_DONE");
            exit = 0;
        } finally { edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; }); System.exit(exit); }
    }
}
