package shed;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in packaged-editor density controls, including a frozen pre-density JAR. */
public final class DensityPerformanceProbe {
    private static Texteditor editor;
    private static boolean supported;
    private static Path output;
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
        List<T> values = new ArrayList<>();
        if (type.isInstance(root)) values.add(type.cast(root));
        if (root instanceof Container parent) for (Component child : parent.getComponents()) values.addAll(descendants(child, type));
        return values;
    }
    private static Map<String, Object> component(Component component) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bounds", List.of(component.getX(), component.getY(), component.getWidth(), component.getHeight()));
        row.put("preferred", List.of(component.getPreferredSize().width, component.getPreferredSize().height));
        row.put("font", String.valueOf(component.getFont()));
        if (component instanceof JComponent c) {
            Insets i = c.getInsets(); row.put("insets", List.of(i.top, i.left, i.bottom, i.right));
        }
        if (component instanceof JList<?> list) row.put("cell", List.of(list.getFixedCellWidth(), list.getFixedCellHeight()));
        if (component instanceof JTree tree) row.put("row_height", tree.getRowHeight());
        return row;
    }
    private static void capture(String phase) throws Exception {
        edt(() -> {
            editor.statusBar.setText("Density geometry control"); editor.validate();
            Map<String, Object> geometry = new LinkedHashMap<>();
            geometry.put("header", component(editor.workbenchHeader));
            geometry.put("editor_strip", component(editor.openEditorsBar));
            geometry.put("tabs", component(descendants(editor.openEditorsBar, JList.class).getFirst()));
            geometry.put("status", component(editor.statusBar)); geometry.put("command", component(editor.commandBar));
            geometry.put("source_font", editor.writingArea.getFont().toString());
            geometry.put("source_tab_size", editor.writingArea.getTabSize());
            var viewport = editor.getActivePane().getScrollPane().getViewport(); geometry.put("source_viewport", component(viewport));
            var trees = descendants(editor.getRootPane(), JTree.class);
            if (!trees.isEmpty()) geometry.put("tree", component(trees.getFirst()));
            var tabs = descendants(editor.toolWindowHost, JTabbedPane.class).getFirst();
            geometry.put("tool_tabs", component(tabs));
            geometry.put("tool_tab_bounds", tabs.getTabCount() == 0 ? "none" : String.valueOf(tabs.getBoundsAt(0)));
            for (var entry : Map.of("header", (JComponent) editor.workbenchHeader, "editor_strip", editor.openEditorsBar,
                "status", editor.statusBar).entrySet()) {
                JComponent c = entry.getValue();
                if (c.getWidth() <= 0 || c.getHeight() <= 0) continue;
                var image = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics(); c.paint(graphics); graphics.dispose();
                Path path = output.resolve(phase + "-" + entry.getKey() + ".png"); ImageIO.write(image, "png", path.toFile());
                geometry.put(entry.getKey() + "_png_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
            out("DENSITY ", Map.of("operation", "geometry", "phase", phase, "values", geometry));
            return null;
        });
    }
    private static void timed(String operation, MarkdownPerformanceProbe.Work work) throws Exception {
        out("DENSITY ", edt(() -> {
            long allocated = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()), began = System.nanoTime();
            work.run(); long applied = System.nanoTime(); editor.validate(); long layout = System.nanoTime();
            editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect()); long finished = System.nanoTime();
            return Map.of("operation", operation, "dispatch_ms", (applied - began) / 1e6, "validate_ms", (layout - applied) / 1e6,
                "forced_root_paint_ms", (finished - layout) / 1e6, "edt_total_ms", (finished - began) / 1e6,
                "edt_allocated_bytes", THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated);
        }));
        edt(() -> null);
    }
    private static void density(String value) {
        if (!supported) return;
        String result = editor.commandHandler.execute("density " + value);
        if (!result.equals("Density: " + value)) throw new AssertionError(result);
    }
    private static void typing(String phase) throws Exception {
        var buffer = edt(editor::getCurrentBuffer);
        for (int i = 0; i < 51; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(16); return null; });
            long before = edt(() -> metric()[0]);
            double dispatch = edt(() -> {
                long start = System.nanoTime(); JTextArea area = editor.writingArea;
                KeyEvent key = new KeyEvent(area, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_X, 'x');
                editor.keyPressed(key);
                if (!key.isConsumed()) area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(area, 0, "x"));
                return (System.nanoTime() - start) / 1e6;
            });
            await(() -> metric()[0] > before, 5);
            out("DENSITY ", Map.of("operation", phase + (i == 0 ? "_first_input" : "_warm_input"), "dispatch_ms", dispatch,
                "input_to_paint_ms", edt(() -> metric()[1] / 1e6)));
            edt(() -> { buffer.getUndoManager().undo(); return null; });
        }
    }
    private static void heap(String phase) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out("DENSITY ", Map.of("operation", phase, "used_mib", heap.getUsed() / 1048576d, "committed_mib", heap.getCommitted() / 1048576d,
            "threads", ManagementFactory.getThreadMXBean().getThreadCount()));
    }
    private static void picker(String phase) throws Exception {
        SwingUtilities.invokeLater(() -> editor.showCommandPalette());
        await(() -> Arrays.stream(editor.getOwnedWindows()).anyMatch(w -> w instanceof JDialog d && d.isShowing() && d.getTitle().equals("Command Palette")), 15);
        edt(() -> {
            JDialog dialog = Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof JDialog d && d.isShowing() && d.getTitle().equals("Command Palette"))
                .map(JDialog.class::cast).findFirst().orElseThrow();
            try {
                JList<?> list = descendants(dialog, JList.class).getFirst(); JTextField field = descendants(dialog, JTextField.class).getFirst();
                long start = System.nanoTime(); list.setSelectedIndex(Math.min(4, list.getModel().getSize() - 1));
                dialog.getRootPane().paintImmediately(dialog.getRootPane().getVisibleRect());
                out("DENSITY ", Map.of("operation", "picker", "phase", phase, "forced_interaction_paint_ms", (System.nanoTime() - start) / 1e6,
                    "list_height", list.getFixedCellHeight(), "filter_height", field.getPreferredSize().height, "font", list.getFont().toString()));
            } finally { dialog.dispose(); }
            return null;
        });
        edt(() -> null);
    }
    private static void workload(boolean split) throws Exception {
        var buffer = edt(editor::getCurrentBuffer);
        String original = edt(buffer::getContent);
        Object ui = edt(() -> editor.writingArea.getUI()), view = edt(() -> editor.writingArea.getUI().getRootView(editor.writingArea));
        Font font = edt(() -> editor.writingArea.getFont());
        capture("default_initial"); heap("ready_no_gc");
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
        heap("ready_requested_gc");
        for (String phase : List.of("default", "compact", "comfortable", "default_restored")) {
            var snapshot = edt(buffer::textSnapshot); long revision = edt(buffer::documentRevision);
            timed("switch_" + phase, () -> density(phase.equals("default_restored") ? "default" : phase));
            edt(() -> {
                if (snapshot != buffer.textSnapshot() || revision != buffer.documentRevision()) throw new AssertionError("Density changed source");
                if (ui != editor.writingArea.getUI() || view != editor.writingArea.getUI().getRootView(editor.writingArea)) throw new AssertionError("Density rebuilt source view");
                if (!font.equals(editor.writingArea.getFont())) throw new AssertionError("Density changed source font");
                return null;
            });
            typing(phase);
            for (int i = 0; i < 4; i++) {
                final int index = i;
                timed(phase + "_scroll", () -> editor.getActivePane().getScrollPane().getViewport().setViewPosition(new Point(0, index % 2 * 80)));
            }
            if (!split) picker(phase);
            capture(phase);
        }
        for (int i = 0; i < 12; i++) {
            timed("repeat_compact", () -> density("compact")); timed("repeat_comfortable", () -> density("comfortable"));
            timed("repeat_default", () -> density("default"));
        }
        for (int i = 0; i < 4; i++) {
            final int index = i;
            timed("resize", () -> editor.setSize(1100 + index % 2 * 120, 760));
        }
        timed("restore_window", () -> editor.setSize(1100, 760));
        if (original.length() <= 102400) {
            timed("focus_on", () -> editor.commandHandler.execute("focus on"));
            timed("density_in_focus", () -> density("comfortable"));
            timed("focus_off", () -> editor.commandHandler.execute("focus off"));
            timed("default_after_focus", () -> density("default"));
        }
        if (original.length() <= 4096) {
            ThemeGalleryDialog gallery = edt(() -> {
                ThemeGalleryDialog.showFor(editor);
                return Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof ThemeGalleryDialog && w.isDisplayable())
                    .map(ThemeGalleryDialog.class::cast).findFirst().orElseThrow();
            });
            timed("theme_preview", () -> {
                for (var list : descendants(gallery, JList.class)) for (int i = 0; i < list.getModel().getSize(); i++)
                    if (list.getModel().getElementAt(i) instanceof ThemePreview theme && theme.id().equals("dracula")) list.setSelectedIndex(i);
                gallery.previewSelectedTheme();
            });
            timed("density_in_preview", () -> density("comfortable"));
            timed("theme_cancel", gallery::cancelPreview);
            edt(() -> { gallery.dispose(); return null; });
            timed("default_after_preview", () -> density("default"));
        }
        if (!original.equals(edt(buffer::getContent))) throw new AssertionError("Source differs after Undo/switches");
        out("DENSITY ", Map.of("operation", "source_integrity", "exact", true));
    }
    public static void main(String[] args) throws Exception {
        int exit = 1;
        try {
            Path file = Path.of(args[0]); supported = Boolean.parseBoolean(args[1]);
            boolean wrap = Boolean.parseBoolean(args[2]), split = Boolean.parseBoolean(args[3]);
            output = Path.of(args[4]); int uiFontSize = Integer.parseInt(args[5]);
            long begin = System.nanoTime(); editor = edt(() -> new Texteditor(new String[]{file.toString()}));
            await(() -> editor.getCurrentBuffer() != null && file.toString().equals(editor.getCurrentBuffer().getFilePath()) && !editor.stagedStartupPending, 30);
            out("DENSITY ", Map.of("operation", "launch_to_loaded_editor", "ms", (System.nanoTime() - begin) / 1e6));
            edt(() -> {
                editor.setSize(1100, 760);
                if (uiFontSize > 0) { editor.configManager.set("ui.font.size", String.valueOf(uiFontSize)); editor.sessionConfigController.applyRuntimeConfigFromSettings(); }
                editor.writingArea.setLineWrap(wrap); editor.writingArea.setWrapStyleWord(wrap);
                if (split) { editor.splitWindow(true); editor.writingArea.setLineWrap(wrap); editor.writingArea.setWrapStyleWord(wrap); }
                editor.validate(); editor.setMode(EditorMode.INSERT);
                if (editor.messageResetTimer != null) editor.messageResetTimer.stop();
                editor.requestActivePaneFocus(); return null;
            });
            await(() -> editor.writingArea.isShowing() && editor.writingArea.getWidth() > 0, 10);
            Thread.sleep(500); workload(split);
            for (int i = 0; i < 5; i++) { edt(() -> null); System.gc(); Thread.sleep(100); }
            heap("after_cycles_requested_gc");
            WeakReference<Texteditor> closed = new WeakReference<>(editor);
            edt(() -> {
                for (var timer : new javax.swing.Timer[]{editor.messageResetTimer, editor.externalChangeTimer, editor.recoverySnapshotTimer, editor.diagnosticRefreshTimer}) if (timer != null) timer.stop();
                editor.fileWatcherService.stop(); editor.shutdownSyntaxUi(); editor.shutdownLspScheduling(); editor.disposeMarkdownPreviews();
                editor.asyncJobService.shutdownNow(); editor.backupScheduler.close(); editor.dispose(); return null;
            });
            editor = null;
            for (int i = 0; i < 5; i++) { edt(() -> null); System.gc(); Thread.sleep(100); }
            heap("disposed_requested_gc");
            out("DENSITY ", Map.of("operation", "lifecycle", "editor_reachable", closed.get() != null));
            System.out.println("DENSITY_DONE"); exit = 0;
        } catch (Throwable error) { error.printStackTrace(); }
        finally { edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; }); System.exit(exit); }
    }
}
