package shed;

import java.awt.*;
import java.awt.event.*;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import static shed.MarkdownPerformanceProbe.*;

/** Explicitly invoked production focus/lifecycle qualification; never loaded by the editor. */
public final class ActivePanePerformanceProbe {
    private static Texteditor editor;
    private static boolean fixed;
    private static Path output;
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static boolean indicators() {
        boolean multiple = editor.editorPanes.stream().filter(p -> !p.isHiddenByFocusMode()).count() > 1;
        for (EditorPane pane : editor.editorPanes) {
            boolean active = pane == editor.getActivePane();
            if (!Boolean.valueOf(active).equals(pane.getScrollPane().getClientProperty("shed.activePane"))) return false;
            var border = pane.getScrollPane().getViewportBorder();
            if (multiple && (!(border instanceof javax.swing.border.MatteBorder matte)
                || !matte.getMatteColor().equals(active ? editor.configManager.getFocusColor() : editor.configManager.getSubtleBorderColor()))) return false;
            if (!multiple && border != null) return false;
        }
        return true;
    }

    private static void timed(String name, Runnable operation) throws Exception {
        out("PANE ", edt(() -> {
            long bytes = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()), start = System.nanoTime();
            operation.run(); long dispatched = System.nanoTime();
            editor.validate(); long validated = System.nanoTime();
            editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());
            return Map.of("operation", name, "dispatch_ms", (dispatched - start) / 1e6,
                "validate_ms", (validated - dispatched) / 1e6, "paint_ms", (System.nanoTime() - validated) / 1e6,
                "edt_allocated_bytes", THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes,
                "indicators_correct", indicators());
        }));
    }

    private static long[] metric() throws Exception {
        synchronized (editor.perfService) {
            var field = PerfService.class.getDeclaredField("metrics"); field.setAccessible(true);
            Object metric = ((Map<?, ?>) field.get(editor.perfService)).get("edit.input-to-paint");
            if (metric == null) return new long[]{0, 0};
            var count = metric.getClass().getDeclaredField("count"); count.setAccessible(true);
            var last = metric.getClass().getDeclaredField("lastNanos"); last.setAccessible(true);
            return new long[]{count.getLong(metric), last.getLong(metric)};
        }
    }

    private static void typing(String phase) throws Exception {
        for (int i = 0; i < 51; i++) {
            edt(() -> { editor.setMode(EditorMode.INSERT); editor.writingArea.setCaretPosition(16); return null; });
            long before = edt(() -> metric()[0]);
            edt(() -> {
                JTextArea area = editor.writingArea;
                var key = new KeyEvent(area, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_X, 'x');
                editor.keyPressed(key);
                if (!key.isConsumed()) area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(area, 0, "x"));
                return null;
            });
            await(() -> metric()[0] > before, 5);
            out("PANE ", Map.of("operation", phase + (i == 0 ? "_first_input" : "_warm_input"), "input_to_paint_ms", edt(() -> metric()[1] / 1e6)));
            edt(() -> { editor.getCurrentBuffer().getUndoManager().undo(); return null; });
        }
    }

    private static void memory(String phase) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out("PANE ", Map.of("operation", phase, "used_mib", heap.getUsed() / 1048576d,
            "committed_mib", heap.getCommitted() / 1048576d, "threads", ManagementFactory.getThreadMXBean().getThreadCount()));
    }

    private static void collect() throws Exception {
        for (int i = 0; i < 5; i++) { edt(() -> null); System.gc(); Thread.sleep(100); }
    }

    private static void capture(String name) throws Exception {
        edt(() -> {
            editor.validate();
            var component = editor.editorHostPanel;
            var image = new java.awt.image.BufferedImage(component.getWidth(), component.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try { component.paint(graphics); } finally { graphics.dispose(); }
            ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
            return null;
        });
    }

    private static void nativeFocus() throws Exception {
        EditorPane target = edt(() -> editor.editorPanes.getFirst());
        edt(() -> { editor.toFront(); editor.requestFocus(); return null; });
        Robot robot = new Robot();
        Point point = edt(() -> { Point p = target.getTextArea().getLocationOnScreen(); p.translate(20, 30); return p; });
        robot.mouseMove(point.x, point.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        await(() -> target.getTextArea().isFocusOwner() && editor.getActivePane() == target, 10);
        out("PANE ", Map.of("operation", "native_mouse_focus", "correct", edt(ActivePanePerformanceProbe::indicators)));
        SwingUtilities.invokeLater(editor::showCommandPalette);
        await(() -> Arrays.stream(editor.getOwnedWindows()).anyMatch(w -> w instanceof JDialog d && d.isShowing() && d.getTitle().equals("Command Palette")), 10);
        await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextField
            && SwingUtilities.getWindowAncestor(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()) != editor, 10);
        out("PANE ", Map.of("operation", "native_picker_focus", "correct", edt(() -> editor.getActivePane() == target && indicators()),
            "editor_window_focused", edt(editor::isFocused)));
        edt(() -> { for (Window w : editor.getOwnedWindows()) if (w instanceof JDialog d && d.getTitle().equals("Command Palette")) w.dispose(); editor.requestActivePaneFocus(); return null; });
        await(() -> target.getTextArea().isFocusOwner(), 10);
        out("PANE ", Map.of("operation", "native_focus_return", "correct", edt(ActivePanePerformanceProbe::indicators)));
    }

    private static void lifecycle(Path file) throws Exception {
        FileBuffer source = edt(editor::getCurrentBuffer);
        Object snapshot = edt(source::textSnapshot); long revision = edt(source::documentRevision);
        int initialListeners = edt(() -> ((javax.swing.text.AbstractDocument) source.getDocument()).getDocumentListeners().length);
        List<WeakReference<JTextArea>> closed = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            timed("cycle_create", () -> editor.splitWindow(true));
            closed.add(edt(() -> new WeakReference<>(editor.writingArea)));
            timed("cycle_close", () -> editor.closeActiveWindow());
        }
        collect(); memory("cycles_requested_gc");
        out("PANE ", edt(() -> Map.of("operation", "lifecycle", "closed_text_areas_reachable", closed.stream().filter(w -> w.get() != null).count(),
            "document_listeners_before", initialListeners,
            "document_listeners_after", ((javax.swing.text.AbstractDocument) source.getDocument()).getDocumentListeners().length,
            "listener_classes", Arrays.stream(((javax.swing.text.AbstractDocument) source.getDocument()).getDocumentListeners()).map(l -> l.getClass().getName()).toList())));
        if (snapshot != edt(source::textSnapshot) || revision != edt(source::documentRevision)) throw new AssertionError("Appearance changed source");
        typing("after");
        out("PANE ", Map.of("operation", "source_integrity", "exact", Files.readString(file).equals(edt(source::getContent))));
    }

    public static void main(String[] args) throws Exception {
        int exit = 1;
        try {
            Path file = Path.of(args[0]); fixed = Boolean.parseBoolean(args[1]); output = Path.of(args[2]);
            editor = edt(() -> new Texteditor(new String[]{file.toString()}));
            await(() -> editor.getCurrentBuffer() != null && file.toString().equals(editor.getCurrentBuffer().getFilePath()) && !editor.stagedStartupPending, 30);
            edt(() -> { editor.setSize(1100, 760); editor.configManager.set("keymap.profile", "plain"); editor.splitWindow(true); editor.validate(); return null; });
            nativeFocus(); typing("before"); collect(); memory("ready_requested_gc");
            capture("two-panes-light");
            timed("first_activation", () -> editor.cycleWindowFocus());
            for (int i = 0; i < 60; i++) timed("warm_activation", () -> editor.cycleWindowFocus());
            timed("third_pane", () -> editor.splitWindow(false));
            for (int i = 0; i < 30; i++) timed("three_pane_activation", () -> editor.cycleWindowFocus());
            timed("dark_theme", () -> editor.setThemeFromCommand("shed-dark")); capture("three-panes-dark");
            timed("light_theme", () -> editor.setThemeFromCommand("shed-light"));
            timed("focus_on", () -> editor.commandHandler.execute("focus on"));
            timed("focus_off", () -> editor.commandHandler.execute("focus off"));
            timed("close_active", () -> editor.closeActiveWindow());
            boolean activeClose = edt(ActivePanePerformanceProbe::indicators);
            timed("close_inactive", () -> editor.closePane(editor.editorPanes.getLast()));
            boolean inactiveClose = edt(ActivePanePerformanceProbe::indicators);
            if (fixed && (!activeClose || !inactiveClose)) throw new AssertionError("Closed pane indicator remained stale");
            // Preserve the baseline observation, then start both lifecycle controls with a consistent cue.
            edt(() -> { editor.editorUiController.refreshPaneFocus(); return null; });
            lifecycle(file);
            WeakReference<Texteditor> disposed = new WeakReference<>(editor);
            edt(() -> {
                for (var timer : new javax.swing.Timer[]{editor.messageResetTimer, editor.externalChangeTimer, editor.recoverySnapshotTimer, editor.diagnosticRefreshTimer}) if (timer != null) timer.stop();
                editor.fileWatcherService.stop(); editor.shutdownSyntaxUi(); editor.shutdownLspScheduling(); editor.disposeMarkdownPreviews();
                editor.shutdownRecoveryJournalScheduling(); editor.asyncJobService.shutdownNow(); editor.backupScheduler.close();
                editor.treeGitController.closeFinderIndexes(); editor.paletteController.close(); editor.testController.shutdown();
                editor.debugSessionController.shutdown(); editor.remoteWorkspaceController.closeAll(); editor.dispose(); return null;
            });
            editor = null; collect(); memory("disposed_requested_gc");
            out("PANE ", Map.of("operation", "disposed", "editor_reachable", disposed.get() != null));
            System.out.println("PANE_DONE"); exit = 0;
        } catch (Throwable error) { error.printStackTrace(); }
        finally { edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; }); System.exit(exit); }
    }
}
