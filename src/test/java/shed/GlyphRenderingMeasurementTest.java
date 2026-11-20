package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in measurements of the unchanged renderer and diagnostic fonts, not a proposed font replacement. */
@EnabledIfSystemProperty(named = "shed.test.glyph.measure", matches = "true")
class GlyphRenderingMeasurementTest {
    static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    static Map<String, Object> memory() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os = (com.sun.management.UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return Map.of("used_heap_bytes", heap.getUsed(), "committed_heap_bytes", heap.getCommitted(),
            "fds", os.getOpenFileDescriptorCount(), "threads", ManagementFactory.getThreadMXBean().getThreadCount());
    }

    static void paint(JTextArea area, JScrollPane scroll, BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setClip(0, 0, image.getWidth(), image.getHeight());
            Point position = scroll.getViewport().getViewPosition(); graphics.translate(-position.x, -position.y);
            EditorUiController.applyEditorTextRenderingHints(graphics); area.paint(graphics);
        } finally { graphics.dispose(); }
    }

    static Map<String, Object> samples(List<Double> values) {
        List<Double> warm = values.subList(1, values.size()); var sorted = new ArrayList<>(warm); sorted.sort(Double::compare);
        return Map.of("first_ms", values.getFirst(), "warm_samples_ms", warm,
            "warm_p50_ms", sorted.get((int) Math.ceil(warm.size() * .50) - 1),
            "warm_p95_ms", sorted.get((int) Math.ceil(warm.size() * .95) - 1), "max_ms", values.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
    }

    @Test void boundedFirstWarmGeometryAndLifecycleMeasurements() throws Exception {
        var rows = new ArrayList<Map<String, Object>>(); var before = memory();
        double loadBefore = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        long firstFont = System.nanoTime(); Font primary = edt(() -> BundledEditorFont.atSize(16));
        double firstFontMs = (System.nanoTime() - firstFont) / 1e6;
        Font logical = new Font(Font.DIALOG, Font.PLAIN, 16);
        for (String source : List.of("// ordinary source alpha beta gamma delta\n".repeat(100),
                GlyphRenderingQualificationTest.B0_SOURCE, "中文日本語\n".repeat(4))) {
            for (Font font : List.of(primary, logical)) {
                long setup = System.nanoTime();
                FileBuffer buffer = edt(() -> FileBuffer.createScratch("[glyph timing]", source));
                JTextArea area = edt(() -> {
                    JTextArea a = new JTextArea(); a.putClientProperty(SourcePlainView.SOURCE_PROPERTY, Boolean.TRUE);
                    a.setUI(new WorkbenchStyle.TextAreaUI()); a.setFont(font); a.setDocument(buffer.getDocument());
                    a.setBackground(new Color(0xFAFAFC)); a.setForeground(new Color(0x30343C));
                    a.setSize(600, 350); a.getCaret().setVisible(false); return a;
                });
                JScrollPane scroll = edt(() -> {
                    var s = new JScrollPane(area); s.setSize(620, 370); s.doLayout();
                    area.setSize(600, Math.max(350, area.getPreferredSize().height)); return s;
                });
                double layoutMs = (System.nanoTime() - setup) / 1e6;
                BufferedImage image = new BufferedImage(600, 350, BufferedImage.TYPE_INT_ARGB);
                var paintMs = new ArrayList<Double>(); var queuedMs = new ArrayList<Double>(); var scrollMs = new ArrayList<Double>();
                var fontMs = new ArrayList<Double>(); var themeMs = new ArrayList<Double>(); var allocations = new ArrayList<Long>();
                var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                for (int sample = 0; sample <= 50; sample++) {
                    edt(() -> {
                        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                        long started = System.nanoTime(); paint(area, scroll, image);
                        paintMs.add((System.nanoTime() - started) / 1e6);
                        allocations.add(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated); return null;
                    });
                    int index = sample; long queued = System.nanoTime();
                    edt(() -> {
                        int start = GraphemeBoundary.floor(source, index % source.length());
                        area.select(start, GraphemeBoundary.next(source, start)); paint(area, scroll, image); return null;
                    });
                    queuedMs.add((System.nanoTime() - queued) / 1e6);
                    queued = System.nanoTime();
                    edt(() -> {
                        int y = index % 2 == 0 ? 0 : Math.max(0, area.getHeight() - scroll.getViewport().getHeight());
                        scroll.getViewport().setViewPosition(new Point(0, y)); paint(area, scroll, image); return null;
                    });
                    scrollMs.add((System.nanoTime() - queued) / 1e6);
                    if (sample <= 10) {
                        long transition = System.nanoTime();
                        edt(() -> { area.setFont(font.deriveFont(24f)); paint(area, scroll, image);
                            area.setFont(font); paint(area, scroll, image); return null; });
                        fontMs.add((System.nanoTime() - transition) / 1e6);
                        transition = System.nanoTime();
                        edt(() -> { area.setBackground(new Color(0x282A36)); area.setForeground(new Color(0xF8F8F2)); paint(area, scroll, image);
                            area.setBackground(new Color(0xFAFAFC)); area.setForeground(new Color(0x30343C)); paint(area, scroll, image); return null; });
                        themeMs.add((System.nanoTime() - transition) / 1e6);
                    }
                }
                assertEquals(source, edt(buffer::getContent));
                rows.add(Map.of("utf16_length", source.length(), "font", font.getFontName(), "source", source,
                    "layout_and_component_setup_ms", layoutMs, "paint", samples(paintMs), "queued_caret_selection_paint", samples(queuedMs),
                    "scroll_and_paint", samples(scrollMs), "font_size_round_trip_and_paint", samples(fontMs),
                    "palette_round_trip_and_paint", samples(themeMs), "paint_allocated_bytes", allocations));
                edt(() -> { area.setDocument(new javax.swing.text.PlainDocument()); return null; });
            }
        }
        FileBuffer shared = edt(() -> FileBuffer.createScratch("[glyph retained owner]", GlyphRenderingQualificationTest.B0_SOURCE));
        int baselineListeners = edt(() -> shared.getDocument().getDocumentListeners().length);
        var references = new ArrayList<WeakReference<EditorPane>>();
        var create = new ArrayList<Double>(); var dispose = new ArrayList<Double>();
        for (int cycle = 0; cycle < 24; cycle++) {
            edt(() -> {
                long started = System.nanoTime();
                JTextArea a = new JTextArea(); a.setDocument(shared.getDocument()); a.setFont(primary); a.setSize(600, 500);
                var pane = new EditorPane(a, new LineNumberPanel(a), new JScrollPane(a), new SearchManager(a)); pane.setBuffer(shared);
                GlyphRenderingQualificationTest.image(a, 1); create.add((System.nanoTime() - started) / 1e6);
                started = System.nanoTime(); pane.disposeSourceView(); GlyphRenderingQualificationTest.image(a, 1);
                dispose.add((System.nanoTime() - started) / 1e6); references.add(new WeakReference<>(pane));
                assertEquals(baselineListeners, shared.getDocument().getDocumentListeners().length); return null;
            });
        }
        edt(() -> null); var after = memory(); var gc = new ArrayList<Map<String, Object>>();
        for (int attempt = 0; attempt < 3; attempt++) { System.gc(); gc.add(memory()); }
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("rows", rows); result.put("first_font_lookup_ms", firstFontMs); result.put("memory_before", before);
        result.put("memory_after_workloads", after); result.put("diagnostic_gc_only_after_latency", gc);
        result.put("closed_panes_reachable_after_diagnostic_gc", references.stream().filter(r -> r.get() != null).count());
        result.put("active_document_listeners_before_after", List.of(baselineListeners, edt(() -> shared.getDocument().getDocumentListeners().length)));
        result.put("pane_create_and_paint", samples(create)); result.put("pane_dispose_and_placeholder_paint", samples(dispose));
        result.put("system_load_before_after", List.of(loadBefore, ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()));
        result.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments()); result.put("jdk", System.getProperty("java.runtime.version"));
        result.put("boundary", "unchanged renderer; queued offscreen component paints and diagnostic font controls, not production before/after or native desktop acceptance");
        System.out.println("GLYPH_MEASUREMENTS " + MiniJson.stringify(result));
    }
}
