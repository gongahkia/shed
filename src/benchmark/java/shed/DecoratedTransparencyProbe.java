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

/** Isolated public-API gate; never used by production window construction. */
public final class DecoratedTransparencyProbe {
    private static JFrame frame;
    private static Source source;
    private static Surface surface;
    private static ConfigManager config;
    private static final class Source extends JTextArea {
        CountDownLatch pending;
        long paintedAt;
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            paintedAt = System.nanoTime();
            if (pending != null) pending.countDown();
        }
    }
    private static final class Surface extends JPanel {
        @Override protected void paintComponent(Graphics graphics) {
            graphics.setColor(config.getNormalColor()); graphics.fillRect(0, 0, getWidth(), getHeight());
            graphics.setColor(config.getEditorForeground()); graphics.fillRect(8, 4, 14, 14);
        }
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void emit(Map<String, Object> values) {
        Map<String, Object> row = new LinkedHashMap<>(values); row.put("epoch_ms", System.currentTimeMillis());
        System.out.println("DECORATED " + MiniJson.stringify(row)); System.out.flush();
    }
    private static boolean attempt(String phase) {
        boolean allAccepted = true;
        for (String kind : List.of("background_218", "background_0", "whole_window_opacity")) {
            Color before = frame.getBackground(); float opacity = frame.getOpacity();
            try {
                if (kind.equals("whole_window_opacity")) frame.setOpacity(.85f);
                else frame.setBackground(new Color(248, 248, 252, kind.equals("background_0") ? 0 : 218));
                emit(Map.of("operation", "attempt", "phase", phase, "kind", kind, "accepted", true));
            } catch (IllegalComponentStateException | UnsupportedOperationException rejected) {
                allAccepted = false;
                emit(Map.of("operation", "attempt", "phase", phase, "kind", kind, "accepted", false,
                    "exception", rejected.getClass().getName(), "message", rejected.getMessage(),
                    "state_unchanged", before.equals(frame.getBackground()) && opacity == frame.getOpacity()));
            } finally {
                frame.setBackground(before); frame.setOpacity(opacity);
            }
        }
        return allAccepted;
    }
    private static void memory(String phase) {
        var usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        emit(Map.of("operation", phase, "heap_used_mib", usage.getUsed() / 1048576d,
            "heap_committed_mib", usage.getCommitted() / 1048576d));
    }
    private static void paint(String operation, Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1); long start = System.nanoTime();
        double mutation = edt(() -> {
            source.pending = done; long began = System.nanoTime(); action.run();
            return (System.nanoTime() - began) / 1e6;
        });
        if (!done.await(3, TimeUnit.SECONDS)) throw new AssertionError("No source paint: " + operation);
        emit(Map.of("operation", operation, "edt_ms", mutation, "paint_ms", (source.paintedAt - start) / 1e6));
        edt(() -> { source.pending = null; return null; });
    }
    private static void image(Path file) throws Exception {
        BufferedImage image = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { surface.printAll(graphics); } finally { graphics.dispose(); }
        int background = image.getRGB(surface.getWidth() - 3, surface.getHeight() - 3), ink = image.getRGB(12, 10);
        emit(Map.of("operation", "opaque_pixels", "background_alpha", background >>> 24, "foreground_alpha", ink >>> 24));
        if ((background >>> 24) != 255 || (ink >>> 24) != 255) throw new AssertionError("Opaque restore contract");
        ImageIO.write(image, "png", file.toFile()); image.flush();
    }
    private static void contrast() {
        String theme = config.getThemeId();
        for (String id : List.of("shed-light", "shed-dark")) {
            config.setTheme(id);
            for (int alpha : List.of(255, 218, 128)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                Color base = config.getNormalColor();
                for (Color backdrop : List.of(Color.WHITE, Color.BLACK)) {
                    Color composite = new Color((base.getRed() * alpha + backdrop.getRed() * (255 - alpha)) / 255,
                        (base.getGreen() * alpha + backdrop.getGreen() * (255 - alpha)) / 255,
                        (base.getBlue() * alpha + backdrop.getBlue() * (255 - alpha)) / 255);
                    rows.add(Map.of("backdrop", backdrop.equals(Color.WHITE) ? "white" : "black",
                        "text", AccessibilitySupport.contrastRatio(config.getEditorForeground(), composite),
                        "muted", AccessibilitySupport.contrastRatio(config.getMutedForegroundColor(), composite),
                        "comment", AccessibilitySupport.contrastRatio(config.getSyntaxCommentColor(), composite)));
                }
                emit(Map.of("operation", "contrast_calculation_only", "theme", id, "alpha", alpha, "rows", rows));
            }
        }
        config.setTheme(theme);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !List.of("opaque", "attempt").contains(args[0])) throw new IllegalArgumentException("opaque|attempt fixture output");
        boolean attempted = args[0].equals("attempt"); Path output = Path.of(args[2]);
        int exit = 1;
        try {
            config = new ConfigManager(); var buffer = new FileBuffer(Path.of(args[1]).toFile());
            int listeners = buffer.getDocument().getDocumentListeners().length;
            long opened = System.nanoTime();
            edt(() -> {
                WorkbenchStyle.install(config);
                frame = new JFrame("Shed 8A.1 decorated public-API gate");
                frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                frame.setBackground(config.getNormalColor()); frame.setLayout(new BorderLayout());
                var device = frame.getGraphicsConfiguration().getDevice();
                emit(Map.of("operation", "capabilities", "per_pixel", device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT),
                    "uniform", device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT),
                    "scale", frame.getGraphicsConfiguration().getDefaultTransform().toString()));
                if (attempted) attempt("before_peer");
                surface = new Surface(); surface.setLayout(new BorderLayout(8, 8)); surface.setOpaque(false);
                JLabel title = new JLabel("       Shed — decorated feasibility only"); title.setForeground(config.getEditorForeground());
                surface.add(title, BorderLayout.NORTH);
                JList<String> sidebar = new JList<>(new String[]{"project", "note.md", "source.java"});
                sidebar.setBackground(config.getNormalColor()); sidebar.setForeground(config.getEditorForeground());
                sidebar.setPreferredSize(new Dimension(140, 200)); surface.add(sidebar, BorderLayout.WEST);
                source = new Source(); source.putClientProperty(SourcePlainView.SOURCE_PROPERTY, true);
                source.setUI(new WorkbenchStyle.TextAreaUI()); source.setFont(BundledEditorFont.atSize(16));
                source.setDocument(buffer.getDocument()); source.setBackground(config.getNormalColor()); source.setForeground(config.getEditorForeground());
                source.setCaretColor(config.getCaretColor()); source.setSelectionColor(config.getSelectionColor()); source.setSelectedTextColor(config.getSelectionTextColor());
                JTabbedPane tabs = new JTabbedPane(); tabs.addTab("note.md", new JScrollPane(source)); surface.add(tabs, BorderLayout.CENTER);
                surface.add(new JLabel("Opaque controls remain the fallback"), BorderLayout.SOUTH); frame.setContentPane(surface);
                frame.setSize(960, 600); frame.setLocationRelativeTo(null); frame.setVisible(true); return null;
            });
            paint("initial_paint", source::repaint);
            emit(Map.of("operation", "window_ready", "creation_to_paint_ms", (System.nanoTime() - opened) / 1e6,
                "native_decorations", !frame.isUndecorated(), "root_decoration_style", frame.getRootPane().getWindowDecorationStyle(),
                "font", source.getFont().toString(), "theme", config.getThemeId()));
            Thread.sleep(500); memory("settled_no_gc");
            edt(() -> {
                long revision = buffer.documentRevision(); source.select(2, 12); int dot = source.getCaret().getDot(), mark = source.getCaret().getMark();
                if (attempted) {
                    boolean accepted = attempt("displayable_peer");
                    emit(Map.of("operation", "verdict", "value", accepted ? "UNEXPECTED_ACCEPTANCE_REQUIRES_NEW_QUALIFICATION" : "NO_GO",
                        "reason", "Public JFrame translucency does not accept native decorations"));
                }
                if (revision != buffer.documentRevision() || dot != source.getCaret().getDot() || mark != source.getCaret().getMark()) throw new AssertionError("Presentation changed source/selection");
                if (frame.getBackground().getAlpha() != 255 || frame.getOpacity() != 1 || frame.isUndecorated()) throw new AssertionError("Opaque decorated restoration");
                emit(Map.of("operation", "restoration", "source_revision_unchanged", true, "selection_unchanged", true, "window_alpha", 255, "window_opacity", 1));
                source.setCaretPosition(0); return null;
            });
            // measurements below are opaque in both modes, never a transparent performance claim.
            for (int i = 0; i < 21; i++) {
                paint(i == 0 ? "first_opaque_insert" : "warm_opaque_insert", () -> source.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(source, 0, "x")));
                paint("opaque_undo", () -> buffer.getUndoManager().undo());
            }
            for (int i = 0; i < 4; i++) {
                final int step = i;
                paint("opaque_scroll", () -> { ((JViewport) source.getParent()).setViewPosition(new Point(0, step % 2 * 100)); source.repaint(); });
                paint("opaque_resize", () -> { frame.setSize(960 + step % 2 * 16, 600); source.repaint(); });
            }
            edt(() -> { image(output.resolve("opaque-components.png")); contrast(); return null; });
            if (!buffer.getDocument().getText(0, buffer.getDocument().getLength()).equals(Files.readString(Path.of(args[1])))) throw new AssertionError("Source mismatch after Undo");
            edt(() -> { source.setDocument(new PlainDocument()); frame.dispose(); return null; });
            if (buffer.getDocument().getDocumentListeners().length != listeners) throw new AssertionError("Source listeners retained");
            for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
            memory("disposed_requested_gc");
            emit(edt(() -> Map.of("operation", "disposal", "displayable_windows", Arrays.stream(Window.getWindows()).filter(Window::isDisplayable).count(), "source_listeners", listeners, "source_exact", true)));
            System.out.println("DECORATED_DONE"); exit = 0;
        } finally {
            edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; }); System.exit(exit);
        }
    }
}
