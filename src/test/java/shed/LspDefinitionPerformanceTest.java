package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.LspDefinitionRequestsTest.edt;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.Element;
import javax.swing.text.View;
import javax.swing.text.DefaultEditorKit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in headless component measurements; not a visible-window or physical-input benchmark. */
@EnabledIfSystemProperty(named = "shed.test.lsp.measure", matches = "true")
class LspDefinitionPerformanceTest {
    private static String source(int size) {
        String line = "// ordinary source alpha beta gamma delta\n";
        return line.repeat(size / line.length() + 1).substring(0, size);
    }

    private static void configure(LspDefinitionRequestsTest.Harness h) throws Exception {
        edt(() -> {
            var area = h.pane.getTextArea();
            area.setUI(new BasicTextAreaUI() {
                @Override public View create(Element element) { return SourcePlainView.adapt(super.create(element)); }
            });
            var config = new ConfigManager();
            assertEquals("shed-light", config.getThemeId());
            area.setFont(BundledEditorFont.atSize(config.getFontSize()));
            assertNotNull(area.getFont());
            area.setBackground(config.getNormalColor()); area.setForeground(config.getEditorForeground());
            area.setSize(800, 500); area.getPreferredSize();
            return null;
        });
    }

    private static void paint(JTextArea area, BufferedImage image) {
        var graphics = image.createGraphics();
        try { area.paint(graphics); } finally { graphics.dispose(); }
    }

    private static Map<String,Object> memory() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os = (com.sun.management.UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return Map.of("used_heap_bytes", heap.getUsed(), "committed_heap_bytes", heap.getCommitted(),
            "threads", ManagementFactory.getThreadMXBean().getThreadCount(), "fds", os.getOpenFileDescriptorCount(),
            "native_children", ProcessHandle.current().descendants().count());
    }

    @Test void boundedPairedInProcessQualification() throws Exception {
        var rows = new ArrayList<Map<String,Object>>();
        var memoryBefore = memory();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var allocated = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        try {
            for (boolean asynchronous : new boolean[]{false, true}) {
                try (var h = new LspDefinitionRequestsTest.Harness(source(4096))) {
                    long setup = System.nanoTime(); configure(h);
                    var image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB);
                    edt(() -> { paint(h.pane.getTextArea(), image); return null; });
                    rows.add(Map.of("operation", "fixture_setup_and_first_paint", "async", asynchronous, "ms", (System.nanoTime()-setup)/1e6));
                    for (int sample = 0; sample < 3; sample++) {
                        long[] timing = new long[7]; long[] allocation = new long[1];
                        AtomicLong responseSent = new AtomicLong();
                        CountDownLatch command = new CountDownLatch(1), input = new CountDownLatch(1), painted = new CountDownLatch(1);
                        edt(() -> {
                            h.onNavigation = () -> {
                                timing[5] = System.nanoTime(); paint(h.pane.getTextArea(), image); timing[6] = System.nanoTime(); painted.countDown();
                            }; return null;
                        });
                        SwingUtilities.invokeLater(() -> {
                            long bytes = allocated.getThreadAllocatedBytes(Thread.currentThread().threadId());
                            timing[0] = System.nanoTime();
                            if (asynchronous) {
                                try {
                                    var context = LspDefinitionRequests.Context.capture(h.buffer, h.pane, h.client, h.uri, h.version);
                                    h.requests.request(context, () -> true);
                                } catch (Exception error) { throw new AssertionError(error); }
                            } else {
                                var location = h.client.definition(h.uri, 0, 7);
                                assertNotNull(location); h.onNavigation.run();
                            }
                            timing[1] = System.nanoTime(); allocation[0] = allocated.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes;
                            command.countDown();
                        });
                        var request = h.peer.await("textDocument/definition");
                        timing[2] = System.nanoTime();
                        SwingUtilities.invokeLater(() -> {
                            timing[3] = System.nanoTime(); paint(h.pane.getTextArea(), image); timing[4] = System.nanoTime(); input.countDown();
                        });
                        scheduler.schedule(() -> {
                            responseSent.set(System.nanoTime()); h.peer.respond(request, InProcessLspFixture.location("file:///target.java"));
                        }, 200, TimeUnit.MILLISECONDS);
                        assertTrue(command.await(3, TimeUnit.SECONDS)); assertTrue(input.await(3, TimeUnit.SECONDS)); assertTrue(painted.await(3, TimeUnit.SECONDS)); h.idle();
                        rows.add(Map.of("operation", "definition", "async", asynchronous, "sample", sample,
                            "command_edt_ms", (timing[1]-timing[0])/1e6, "queued_input_ms", (timing[3]-timing[2])/1e6,
                            "queued_input_completed_component_paint_ms", (timing[4]-timing[2])/1e6,
                            "result_component_paint_ms", (timing[6]-timing[5])/1e6,
                            "response_to_component_paint_ms", (timing[6]-responseSent.get())/1e6, "command_edt_allocated_bytes", allocation[0]));
                    }
                }
            }
            for (int size : new int[]{4096, 102400, 1048576}) {
                try (var h = new LspDefinitionRequestsTest.Harness(source(size))) {
                    configure(h); var image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB);
                    edt(() -> { paint(h.pane.getTextArea(), image); return null; });
                    for (boolean pending : new boolean[]{false, true}) {
                        Map<String,Object> request = null;
                        if (pending) { h.request(); request = h.peer.await("textDocument/definition"); }
                        List<Double> samples = new ArrayList<>();
                        for (int n = 0; n < 51; n++) {
                            double ms = edt(() -> {
                                var area = h.pane.getTextArea(); area.setCaretPosition(7);
                                long start = System.nanoTime();
                                area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction)
                                    .actionPerformed(new java.awt.event.ActionEvent(area, 0, "x"));
                                paint(area, image); long end = System.nanoTime(); h.buffer.getUndoManager().undo();
                                return (end-start)/1e6;
                            });
                            samples.add(ms);
                        }
                        var warm = samples.subList(1,samples.size()).stream().sorted().toList();
                        assertTrue(warm.get(47) <= 16, "component typing p95 >16ms: " + size + " pending=" + pending);
                        rows.add(Map.of("operation", "typing_component_paint", "bytes", size, "definition_pending", pending,
                            "first_ms", samples.getFirst(), "warm_samples_ms", samples.subList(1,samples.size()), "warm_p95_ms", warm.get(47)));
                        if (request != null) { h.peer.respond(request, InProcessLspFixture.location("file:///stale.java")); h.idle(); }
                        edt(() -> { assertEquals(source(size), h.buffer.getContent()); return null; });
                    }
                }
            }
            for (int cycle = 0; cycle < 24; cycle++) {
                try (var h = new LspDefinitionRequestsTest.Harness(source(4096))) {
                    h.request(); var request = h.peer.await("textDocument/definition");
                    edt(() -> { h.requests.cancel(); return null; }); h.peer.await("$/cancelRequest");
                    h.peer.respond(request, InProcessLspFixture.location("file:///obsolete.java")); h.idle();
                    edt(() -> { assertTrue(h.navigated.isEmpty()); return null; });
                }
            }
        } finally { scheduler.shutdownNow(); }
        var beforeGc = memory();
        var diagnosticGc = new ArrayList<Map<String,Object>>();
        for (int attempt = 0; attempt < 3; attempt++) { System.gc(); diagnosticGc.add(memory()); }
        System.out.println("B1A_PERFORMANCE " + MiniJson.stringify(Map.of("rows", rows, "memory_before", memoryBefore,
            "memory_after_24_disposals", beforeGc, "diagnostic_gc_requests", diagnosticGc, "native_processes_launched", 0,
            "boundary", "headless forced component paint; not native visible workbench or physical input")));
    }
}
