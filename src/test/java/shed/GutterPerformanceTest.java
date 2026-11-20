package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.Element;
import javax.swing.text.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in, pinned before/after component probes. No window, native child or server is created. */
@EnabledIfSystemProperty(named = "shed.test.gutter.measure", matches = "true")
class GutterPerformanceTest {
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, Object> memory() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os = (com.sun.management.UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return Map.of("used_heap_bytes", heap.getUsed(), "committed_heap_bytes", heap.getCommitted(),
            "fds", os.getOpenFileDescriptorCount(), "threads", ManagementFactory.getThreadMXBean().getThreadCount());
    }

    private static JPanel gutter(Class<?> type, JTextArea area, ConfigManager config) throws Exception {
        var constructor = type.getDeclaredConstructor(JTextArea.class); constructor.setAccessible(true);
        JPanel panel = (JPanel) constructor.newInstance(area);
        var colors = type.getDeclaredMethod("setColors", Color.class, Color.class, Color.class); colors.setAccessible(true);
        colors.invoke(panel, config.getLineNumberBackground(), config.getLineNumberForeground(), config.getLineNumberActiveForeground());
        panel.setSize(70, 500);
        return panel;
    }

    private static void paint(JComponent component, BufferedImage image) {
        paintAt(component, image, new Point());
    }

    private static void paintAt(JComponent component, BufferedImage image, Point position) {
        Graphics2D g = image.createGraphics();
        try {
            g.setClip(0, 0, image.getWidth(), image.getHeight());
            g.translate(-position.x, -position.y);
            component.paint(g);
        } finally { g.dispose(); }
    }

    private static Map<String, Object> samples(List<Double> values) {
        return Map.of("first_ms", values.getFirst(), "warm_samples_ms", values.subList(1, values.size()),
            "warm_p95_ms", percentile(values.subList(1, values.size()), .95));
    }

    private static double percentile(List<Double> values, double fraction) {
        var sorted = new ArrayList<>(values); sorted.sort(Double::compare);
        return sorted.get((int) Math.ceil(sorted.size() * fraction) - 1);
    }

    @Test void pairedGeometryLatencyAndDisposal() throws Exception {
        Path jar = Path.of(System.getProperty("shed.test.gutter.baseline")).toRealPath();
        // Fail before class loading if the control is not the preserved B1A application artifact.
        assertEquals("2a1767672c3a08dcc3062be06fef462db338adccd649d8df83f6a1fe7e4a29d9", digest(Files.readAllBytes(jar)));
        var result = new LinkedHashMap<String, Object>();
        result.put("memory_before", memory());
        var rows = new ArrayList<Map<String, Object>>();
        var screenshots = new ArrayList<Map<String, Object>>();
        long initialReproduction = System.nanoTime();
        // Keep baseline package-private dependencies in one isolated package/class-loader namespace.
        try (URLClassLoader before = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("shed.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        }) {
            Class<?> baseline = before.loadClass("shed.LineNumberPanel");
            var config = edt(ConfigManager::new);
            edt(() -> {
                GutterRenderingTest.MappedArea area = new GutterRenderingTest.MappedArea(GutterRenderingTest.MIXED);
                JPanel control = gutter(baseline, area, config), candidate = gutter(LineNumberPanel.class, area, config);
                BufferedImage expected = new BufferedImage(70, 500, BufferedImage.TYPE_INT_ARGB);
                BufferedImage failed = new BufferedImage(70, 500, BufferedImage.TYPE_INT_ARGB);
                BufferedImage actual = new BufferedImage(70, 500, BufferedImage.TYPE_INT_ARGB);
                paint(control, expected); area.bottom++;
                String oldError = GutterRenderingTest.withoutPaintErrors(() -> paint(control, failed));
                String newError = GutterRenderingTest.withoutPaintErrors(() -> paint(candidate, actual));
                assertTrue(oldError.contains("BadLocationException")); assertEquals("", newError);
                GutterRenderingTest.assertPixelsEqual(expected, actual);
                assertFalse(java.util.Arrays.equals(failed.getRGB(0, 0, 70, 500, null, 0, 70), actual.getRGB(0, 0, 70, 500, null, 0, 70)));
                result.put("reproduced_implied_end", Map.of("length", 430, "mapped_offset", 431,
                    "baseline_error", oldError, "candidate_error", newError, "candidate_equals_valid_eof_pixels", true));
                for (var entry : Map.of("before-failed-implied-end", failed, "after-implied-end", actual).entrySet()) {
                    ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(entry.getValue(), "png", png);
                    screenshots.add(Map.of("case", entry.getKey(), "png_base64", Base64.getEncoder().encodeToString(png.toByteArray())));
                }
                return null;
            });
            result.put("control_load_and_first_reproduction_ms", (System.nanoTime() - initialReproduction) / 1e6);
            long firstSetup = System.nanoTime();
            edt(() -> { BundledEditorFont.atSize(16); return null; });
            result.put("font_first_use_ms", (System.nanoTime() - firstSetup) / 1e6);
            for (String theme : List.of("shed-light", "dracula")) {
                edt(() -> { config.setTheme(theme); return null; });
                for (String text : List.of("one\ntwo\n", GutterRenderingTest.MIXED)) {
                    edt(() -> {
                        JTextArea area = new JTextArea(text); area.setFont(BundledEditorFont.atSize(16)); area.setSize(800, 500);
                        JPanel control = gutter(baseline, area, config), candidate = gutter(LineNumberPanel.class, area, config);
                        BufferedImage expected = new BufferedImage(70, 500, BufferedImage.TYPE_INT_ARGB);
                        BufferedImage actual = new BufferedImage(70, 500, BufferedImage.TYPE_INT_ARGB);
                        paint(control, expected); paint(candidate, actual);
                        GutterRenderingTest.assertPixelsEqual(expected, actual);
                        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(actual, "png", png);
                        screenshots.add(Map.of("theme", theme, "source_sha256", digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            "pixel_equivalent", true, "png_base64", Base64.getEncoder().encodeToString(png.toByteArray())));
                        return null;
                    });
                }
            }
            edt(() -> { config.setTheme("shed-light"); return null; });
            for (int size : new int[]{4096, 102400, 1048576}) {
                String line = "// ordinary source alpha beta gamma delta\n";
                String text = line.repeat(size / line.length() + 1).substring(0, size);
                for (Class<?> type : List.of(baseline, LineNumberPanel.class)) {
                    boolean candidate = type == LineNumberPanel.class;
                    long setup = System.nanoTime();
                    FileBuffer buffer = edt(() -> FileBuffer.createScratch("[gutter-control]", text));
                    JTextArea area = edt(() -> {
                        JTextArea a = new JTextArea();
                        a.setUI(new BasicTextAreaUI() {
                            @Override public View create(Element element) { return SourcePlainView.adapt(super.create(element)); }
                        });
                        a.setDocument(buffer.getDocument()); a.setFont(BundledEditorFont.atSize(16));
                        a.setForeground(config.getEditorForeground()); a.setBackground(config.getNormalColor());
                        a.setSize(800, 500); a.getPreferredSize(); return a;
                    });
                    JPanel panel = edt(() -> gutter(type, area, config));
                    JScrollPane scroll = edt(() -> {
                        var pane = new JScrollPane(area); pane.setRowHeaderView(panel); pane.setSize(900, 500); pane.doLayout();
                        area.setSize(800, Math.max(500, area.getPreferredSize().height));
                        panel.setSize(70, area.getHeight()); return pane;
                    });
                    BufferedImage sourceImage = new BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB);
                    BufferedImage gutterImage = new BufferedImage(70, 500, BufferedImage.TYPE_INT_ARGB);
                    edt(() -> { paint(area, sourceImage); paint(panel, gutterImage); return null; });
                    double setupMs = (System.nanoTime() - setup) / 1e6;
                    var paintMs = new ArrayList<Double>(); var typingMs = new ArrayList<Double>(); var scrollMs = new ArrayList<Double>();
                    var allocations = new ArrayList<Long>();
                    var allocationBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                    for (int sample = 0; sample <= 50; sample++) {
                        long begin = System.nanoTime();
                        edt(() -> { area.insert("x", 0);
                            paintAt(area, sourceImage, scroll.getViewport().getViewPosition());
                            paintAt(panel, gutterImage, scroll.getRowHeader().getViewPosition()); return null; });
                        typingMs.add((System.nanoTime() - begin) / 1e6);
                        edt(() -> { buffer.getUndoManager().undo(); return null; });
                        edt(() -> {
                            long allocated = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                            long started = System.nanoTime(); paintAt(panel, gutterImage, scroll.getRowHeader().getViewPosition());
                            paintMs.add((System.nanoTime() - started) / 1e6);
                            allocations.add(allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated);
                            return null;
                        });
                        begin = System.nanoTime();
                        final int row = sample % 2 == 0 ? 1 : 4;
                        edt(() -> { scroll.getViewport().setViewPosition(new Point(0, row * area.getFontMetrics(area.getFont()).getHeight()));
                            assertEquals(scroll.getViewport().getViewPosition().y, scroll.getRowHeader().getViewPosition().y);
                            paintAt(area, sourceImage, scroll.getViewport().getViewPosition());
                            paintAt(panel, gutterImage, scroll.getRowHeader().getViewPosition()); return null; });
                        scrollMs.add((System.nanoTime() - begin) / 1e6);
                    }
                    assertEquals(text, edt(area::getText));
                    rows.add(Map.of("candidate", candidate, "source_bytes", size, "source_sha256", digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "fixture_setup_and_first_paint_ms", setupMs, "gutter_paint", samples(paintMs),
                        "queued_insert_and_source_gutter_paint", samples(typingMs), "scroll_request_and_component_paint", samples(scrollMs),
                        "gutter_paint_allocated_bytes", allocations));
                    edt(() -> { area.setDocument(new javax.swing.text.PlainDocument()); return null; });
                }
            }
        }
        result.put("memory_after_measurements", memory());
        List<WeakReference<EditorPane>> references = new ArrayList<>();
        FileBuffer active = edt(() -> FileBuffer.createScratch("[surviving-owner]", GutterRenderingTest.MIXED));
        int baselineListeners = edt(() -> active.getDocument().getDocumentListeners().length);
        var creationMs = new ArrayList<Double>(); var disposalMs = new ArrayList<Double>();
        for (int cycle = 0; cycle < 24; cycle++) {
            edt(() -> {
                long started = System.nanoTime();
                JTextArea area = new JTextArea(); area.setDocument(active.getDocument()); area.setSize(219, 441);
                var panel = GutterRenderingTest.gutter(area);
                EditorPane pane = new EditorPane(area, panel, new JScrollPane(area), new SearchManager(area));
                pane.setBuffer(active); GutterRenderingTest.paint(panel);
                creationMs.add((System.nanoTime() - started) / 1e6); started = System.nanoTime();
                pane.disposeSourceView(); disposalMs.add((System.nanoTime() - started) / 1e6);
                GutterRenderingTest.paint(panel);
                assertEquals(baselineListeners, active.getDocument().getDocumentListeners().length);
                references.add(new WeakReference<>(pane)); return null;
            });
        }
        edt(() -> null);
        var gc = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 3; i++) { System.gc(); gc.add(memory()); }
        result.put("diagnostic_gc_only_after_latency", gc);
        result.put("closed_panes_remaining_after_diagnostic_gc", references.stream().filter(r -> r.get() != null).count());
        result.put("pane_creation_and_first_gutter_paint", samples(creationMs));
        result.put("pane_disposal", samples(disposalMs));
        result.put("active_document_listeners_before_after", List.of(baselineListeners, edt(() -> active.getDocument().getDocumentListeners().length)));
        assertEquals(GutterRenderingTest.MIXED, edt(active::getContent));
        result.put("rows", rows); result.put("visuals", screenshots);
        result.put("baseline_jar_sha256", digest(Files.readAllBytes(jar)));
        try (var input = LineNumberPanel.class.getResourceAsStream("LineNumberPanel.class")) {
            result.put("candidate_class_sha256", digest(input.readAllBytes()));
        }
        result.put("jdk", System.getProperty("java.runtime.version")); result.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("boundary", "queued insert/scroll request to completed offscreen source and gutter paints; not native visible workbench, hardware input or physical scrolling");
        System.out.println("B1B1_PERFORMANCE " + MiniJson.stringify(result));
    }
}
