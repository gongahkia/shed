package shed;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import com.sun.jna.*;

/** Disposable background-alpha experiment. Never constructed by Shed's production workbench. */
public final class AppearanceTransparencyProbe {
    private static final List<Demo> windows = new ArrayList<>();
    private static ConfigManager config;
    private static int alpha;
    private static final class Signal {
        CountDownLatch next;
        long paintedAt;
        void painted() { paintedAt = System.nanoTime(); if (next != null) next.countDown(); }
    }
    private static final class Surface extends JPanel {
        final Signal paint = new Signal();
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setComposite(AlphaComposite.Src);
                Color bg = config.getNormalColor();
                copy.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), alpha));
                copy.fillRect(0, 0, getWidth(), getHeight());
                copy.setColor(config.getEditorForeground());
                copy.fillRect(12, 8, 26, 12);
            } finally { copy.dispose(); }
        }
        @Override protected void paintChildren(Graphics graphics) { super.paintChildren(graphics); paint.painted(); }
    }
    private static final class Source extends JTextArea {
        final Signal paint = new Signal();
        @Override protected void paintComponent(Graphics graphics) { super.paintComponent(graphics); paint.painted(); }
    }
    private record Demo(JFrame frame, Surface surface, List<Source> sources, JWindow backdrop) { }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action); SwingUtilities.invokeAndWait(result); return result.get();
    }
    private static void out(String prefix, Map<String, Object> value) {
        Map<String, Object> row = new LinkedHashMap<>(value); row.putIfAbsent("epoch_ms", System.currentTimeMillis());
        System.out.println(prefix + MiniJson.stringify(row)); System.out.flush();
    }
    private static void memory(String phase) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out("AP_META ", Map.of("operation", phase, "heap_used_mib", heap.getUsed() / 1048576d,
            "heap_committed_mib", heap.getCommitted() / 1048576d, "threads", ManagementFactory.getThreadMXBean().getThreadCount()));
    }
    private static void transition(int value) {
        alpha = value;
        for (Demo demo : windows) {
            demo.frame().setBackground(value == 255 ? config.getNormalColor() : new Color(0, 0, 0, 0));
            demo.frame().getRootPane().setOpaque(false); demo.frame().getLayeredPane().setOpaque(false);
            demo.surface().setOpaque(false); demo.surface().repaint();
        }
    }
    private static Demo create(FileBuffer buffer, int panes, int index, int count) {
        int width = count == 1 ? 1000 : 560, height = 610;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int x = screen.x + 30 + index * 575, y = screen.y + 28;
        JWindow backdrop = new JWindow();
        backdrop.setContentPane(new JPanel() {
            @Override protected void paintComponent(Graphics graphics) {
                for (int column = 0; column < getWidth(); column += 36) {
                    graphics.setColor(column % 72 == 0 ? new Color(0x397346) : new Color(0x854B52));
                    graphics.fillRect(column, 0, 36, getHeight());
                }
            }
        });
        backdrop.setBounds(x, y, width, height); backdrop.setVisible(true);
        JFrame frame = new JFrame("Shed 8A disposable alpha experiment " + index);
        frame.setUndecorated(true); frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Surface surface = new Surface(); surface.setOpaque(false); surface.setLayout(new BorderLayout(8, 8));
        JLabel title = new JLabel("       Shed — isolated appearance feasibility"); title.setForeground(config.getEditorForeground());
        surface.add(title, BorderLayout.NORTH);
        JList<String> sidebar = new JList<>(new String[]{"project", "  note.md", "  source.java"});
        sidebar.setOpaque(false); sidebar.setForeground(config.getEditorForeground()); sidebar.setBackground(config.getNormalColor());
        sidebar.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int row, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, row, selected, focus); setOpaque(selected); return this;
            }
        });
        sidebar.setPreferredSize(new Dimension(140, 200)); surface.add(sidebar, BorderLayout.WEST);
        List<Source> sources = new ArrayList<>(); JPanel editors = new JPanel(new GridLayout(1, panes, 8, 0)); editors.setOpaque(false);
        for (int i = 0; i < panes; i++) {
            Source area = new Source(); area.putClientProperty(SourcePlainView.SOURCE_PROPERTY, true);
            area.setUI(new WorkbenchStyle.TextAreaUI()); area.setFont(BundledEditorFont.atSize(16));
            area.setForeground(config.getEditorForeground()); area.setBackground(config.getNormalColor());
            area.setCaretColor(config.getCaretColor()); area.setSelectionColor(config.getSelectionColor()); area.setSelectedTextColor(config.getSelectionTextColor());
            area.setDocument(buffer.getDocument()); area.setOpaque(false);
            JScrollPane scroll = new JScrollPane(area); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
            editors.add(scroll); sources.add(area);
        }
        surface.add(editors, BorderLayout.CENTER);
        JLabel footer = new JLabel("Plain source • foreground paint is not globally faded"); footer.setForeground(config.getMutedForegroundColor());
        surface.add(footer, BorderLayout.SOUTH); frame.setContentPane(surface); frame.setBounds(x, y, width, height);
        frame.setBackground(alpha == 255 ? config.getNormalColor() : new Color(0, 0, 0, 0));
        frame.getRootPane().setOpaque(false); frame.getLayeredPane().setOpaque(false); surface.setOpaque(false);
        frame.setVisible(true); return new Demo(frame, surface, sources, backdrop);
    }
    private static void measure(String name, Signal signal, Runnable action, boolean painted) throws Exception {
        CountDownLatch done = new CountDownLatch(1); long began = System.nanoTime();
        double edtMs = edt(() -> {
            signal.next = done;
            long entered = System.nanoTime(); action.run(); return (System.nanoTime() - entered) / 1e6;
        });
        if (painted && !done.await(3, TimeUnit.SECONDS)) throw new AssertionError("No completed component paint for " + name);
        out("AP_SAMPLE ", Map.of("operation", name, "edt_ms", edtMs, "completed_paint_ms", painted ? (signal.paintedAt - began) / 1e6 : -1));
        edt(() -> { signal.next = null; return null; });
    }
    private static Map<String, Object> pixels(Surface root, Path file) throws Exception {
        BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { root.printAll(graphics); } finally { graphics.dispose(); }
        int background = image.getRGB(root.getWidth() - 4, root.getHeight() - 4), ink = image.getRGB(20, 12);
        ImageIO.write(image, "png", file.toFile()); image.flush();
        if ((background >>> 24) != alpha || (ink >>> 24) != 255) throw new AssertionError("Background/foreground alpha contract failed");
        return Map.of("background_alpha", background >>> 24, "solid_foreground_alpha", ink >>> 24, "foreground_rgb", ink & 0xffffff);
    }
    interface ScreenAccess extends Library { byte CGPreflightScreenCaptureAccess(); }
    private static void capture(Path output) throws Exception {
        boolean allowed = !System.getProperty("os.name").startsWith("Mac") || Native.load(
            "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", ScreenAccess.class).CGPreflightScreenCaptureAccess() != 0;
        out("AP_META ", Map.of("operation", "screen_capture_permission_preflight", "allowed", allowed, "requested_permission", false));
        if (allowed) {
            JFrame frame = windows.get(0).frame();
            try {
                OwnedWindowCapture.capture(frame.getTitle(), frame.getWidth(), frame.getHeight(), output.resolve("owned-window-surface.png"));
                out("AP_META ", Map.of("operation", "owned_window_capture", "available", true));
            } catch (IllegalStateException unavailable) {
                out("AP_META ", Map.of("operation", "owned_window_capture", "available", false, "reason", unavailable.toString()));
            }
        }
    }
    private static void contrast(int value) {
        Color base = config.getNormalColor(); Map<String, Object> rows = new LinkedHashMap<>();
        Map<String, Color> text = Map.of("text", config.getEditorForeground(), "muted", config.getMutedForegroundColor(), "comment", config.getSyntaxCommentColor());
        for (var entry : text.entrySet()) {
            List<Double> ratios = new ArrayList<>();
            for (Color behind : List.of(Color.BLACK, Color.WHITE)) {
                Color composite = new Color((base.getRed() * value + behind.getRed() * (255 - value)) / 255,
                    (base.getGreen() * value + behind.getGreen() * (255 - value)) / 255, (base.getBlue() * value + behind.getBlue() * (255 - value)) / 255);
                ratios.add(AccessibilitySupport.contrastRatio(entry.getValue(), composite));
            }
            rows.put(entry.getKey(), ratios);
        }
        out("AP_META ", Map.of("operation", "black_white_backdrop_contrast", "alpha", value, "ratios", rows));
    }
    private static void command(Path output, String phase, String... command) throws Exception {
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve(phase + ".txt").toFile()).start();
        boolean finished = child.waitFor(8, TimeUnit.SECONDS); if (!finished) { child.destroyForcibly(); child.waitFor(); }
        out("AP_META ", Map.of("operation", phase, "exit", child.exitValue(), "timeout", !finished));
    }
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]), output = Path.of(args[4]); alpha = Integer.parseInt(args[1]);
        int panes = Integer.parseInt(args[2]), count = Integer.parseInt(args[3]); boolean diagnostic = Boolean.parseBoolean(args[5]);
        int exit = 1;
        try {
            var buffer = new FileBuffer(file.toFile()); int listeners = buffer.getDocument().getDocumentListeners().length;
            config = new ConfigManager(); edt(() -> { WorkbenchStyle.install(config); return null; });
            var device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            out("AP_META ", Map.of("operation", "capabilities", "per_pixel", device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT),
                "uniform", device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT), "scale", device.getDefaultConfiguration().getDefaultTransform().toString()));
            edt(() -> {
                JFrame decorated = new JFrame();
                for (String operation : List.of("background", "opacity")) {
                    try { if (operation.equals("background")) decorated.setBackground(new Color(0, 0, 0, 0)); else decorated.setOpacity(.8f);
                        out("AP_META ", Map.of("operation", "decorated_" + operation, "result", "ACCEPTED"));
                    } catch (IllegalComponentStateException | UnsupportedOperationException expected) {
                        out("AP_META ", Map.of("operation", "decorated_" + operation, "result", expected.toString()));
                    }
                }
                decorated.dispose(); return null;
            });
            if (!device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) throw new UnsupportedOperationException("No per-pixel capability");
            long open = System.nanoTime();
            edt(() -> { for (int i = 0; i < count; i++) windows.add(create(buffer, panes, i, count)); return null; });
            Source area = windows.get(0).sources().get(0); Surface root = windows.get(0).surface();
            measure("initial_repaint", root.paint, root::repaint, true);
            out("AP_META ", Map.of("operation", "window_ready", "window_creation_to_paint_ms", (System.nanoTime() - open) / 1e6,
                "font", area.getFont().toString(), "theme", config.getThemeId(), "decorated", windows.get(0).frame().isUndecorated() == false));
            memory("ready_no_gc"); Thread.sleep(500); memory("fixed_500ms_settle_no_gc");
            if (diagnostic) { String pid = Long.toString(ProcessHandle.current().pid()); command(output, "ready_nmt", System.getProperty("java.home") + "/bin/jcmd", pid, "VM.native_memory", "summary", "scale=KB"); command(output, "ready_vmmap", "/usr/bin/vmmap", "-summary", pid); }
            for (int i = 0; i < 51; i++) {
                measure(i == 0 ? "first_insert" : "insert", area.paint, () -> { area.setCaretPosition(0); area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(area, 0, "x")); }, true);
                measure("undo", area.paint, () -> buffer.getUndoManager().undo(), true);
            }
            for (int i = 0; i < 10; i++) {
                final int step = i;
                measure("resize", root.paint, () -> { windows.get(0).frame().setSize((count == 1 ? 1000 : 560) + step % 2 * 16, 610 + step % 2 * 12); root.repaint(); }, true);
                measure("scroll", area.paint, () -> { var viewport = (JViewport) area.getParent(); viewport.setViewPosition(new Point(0, step % 2 * 100)); area.repaint(); }, true);
            }
            int requested = alpha;
            for (int i = 0; i < 5; i++) {
                measure("enable_disable", root.paint, () -> transition(alpha == 255 ? 218 : 255), true);
                measure("restore_requested", root.paint, () -> transition(requested), true);
            }
            edt(() -> {
                JPopupMenu popup = new JPopupMenu(); popup.add(new JMenuItem("Existing-style source action")); popup.show(area, 40, 40);
                out("AP_META ", Map.of("operation", "popup", "background_alpha", popup.getBackground().getAlpha())); popup.setVisible(false);
                out("AP_META ", Map.of("operation", "argb_contract", "pixels", pixels(root, output.resolve("component-argb.png"))));
                contrast(255); contrast(218); contrast(128); return null;
            });
            capture(output);
            if (!buffer.getDocument().getText(0, buffer.getDocument().getLength()).equals(Files.readString(file))) throw new AssertionError("Source changed");
            edt(() -> {
                for (Demo demo : windows) { for (Source source : demo.sources()) source.setDocument(new PlainDocument()); demo.frame().dispose(); demo.backdrop().dispose(); }
                windows.clear(); return null;
            });
            if (buffer.getDocument().getDocumentListeners().length != listeners) throw new AssertionError("Source listener retained");
            for (int i = 0; i < 3; i++) { edt(() -> null); System.gc(); Thread.sleep(80); }
            memory("disposed_requested_gc");
            out("AP_META ", edt(() -> Map.of("operation", "disposal", "displayable_windows", Arrays.stream(Window.getWindows()).filter(Window::isDisplayable).count(), "source_listeners", listeners, "source_exact", true)));
            if (diagnostic) { String pid = Long.toString(ProcessHandle.current().pid()); command(output, "disposed_nmt", System.getProperty("java.home") + "/bin/jcmd", pid, "VM.native_memory", "summary", "scale=KB"); command(output, "disposed_vmmap", "/usr/bin/vmmap", "-summary", pid); }
            System.out.println("AP_DONE"); exit = 0;
        } catch (Throwable failure) { failure.printStackTrace(); }
        finally { edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; }); System.exit(exit); }
    }
}
