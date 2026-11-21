package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** opt-in component/loopback measurements, not native desktop or whole-process RSS qualification. */
@EnabledIfSystemProperty(named = "shed.test.status.measure", matches = "true")
class WorkspaceStatusMeasurementTest {
    @TempDir Path temporary;
    static <T> T edt(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call); SwingUtilities.invokeLater(task); return task.get(10, TimeUnit.SECONDS);
    }
    static long ownedThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() &&
            (t.getName().startsWith("shed-workspace-") || t.getName().startsWith("shed-status-client-"))).count();
    }
    static Map<String, Object> memory() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(); var result = new LinkedHashMap<String, Object>();
        result.put("used_heap_bytes", heap.getUsed()); result.put("committed_heap_bytes", heap.getCommitted());
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount()); result.put("attachment_owned_threads", ownedThreads());
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) result.put("fds", unix.getOpenFileDescriptorCount());
        result.put("rss", "unmeasured"); return result;
    }
    static Map<String, Object> samples(List<Double> values) {
        var warm = new ArrayList<>(values.subList(1, values.size())); warm.sort(Double::compareTo);
        return Map.of("first_ms", values.getFirst(), "samples_ms", values, "warm_count", warm.size(),
            "warm_p50_ms", warm.get((int) Math.ceil(warm.size() * .50) - 1),
            "warm_p95_ms", warm.get((int) Math.ceil(warm.size() * .95) - 1),
            "warm_p99_observed_ms", warm.get((int) Math.ceil(warm.size() * .99) - 1), "max_ms", values.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
    }
    static Map<String, Object> idleCpu() throws Exception {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var threads = ManagementFactory.getThreadMXBean(); var named = new java.util.HashMap<Long, Long>();
        for (var thread : Thread.getAllStackTraces().keySet()) if (thread.getName().startsWith("shed-workspace-") || thread.getName().startsWith("shed-status-client-"))
            named.put(thread.threadId(), threads.getThreadCpuTime(thread.threadId()));
        long compilation = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
        long cpu = os.getProcessCpuTime(), start = System.nanoTime();
        new java.util.concurrent.CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
        long ownedCpu = 0;
        for (var entry : named.entrySet()) {
            long after = threads.getThreadCpuTime(entry.getKey());
            if (after >= 0 && entry.getValue() >= 0) ownedCpu += Math.max(0, after - entry.getValue());
        }
        return Map.of("wall_ns", System.nanoTime() - start, "whole_jvm_cpu_ns", os.getProcessCpuTime() - cpu,
            "attachment_platform_thread_cpu_ns", ownedCpu, "compiler_time_delta_ms", ManagementFactory.getCompilationMXBean().getTotalCompilationTime() - compilation);
    }
    static List<WeakReference<Object>> cycle(Path directory, List<Double> create, List<Double> attach, List<Double> close) throws Exception {
        var app = new ApplicationStatus(true); long start = System.nanoTime();
        var server = WorkspaceBridgeServer.status(app, directory, List.of()); create.add((System.nanoTime() - start) / 1e6);
        try {
            start = System.nanoTime();
            try (var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
                attach.add((System.nanoTime() - start) / 1e6); WorkspaceStatusTransportTest.exchange(client);
            }
        } finally { start = System.nanoTime(); server.close(); close.add((System.nanoTime() - start) / 1e6); }
        return List.of(new WeakReference<>(app), new WeakReference<>(server));
    }

    @Test void boundedLatencyTypingAndRetention() throws Exception {
        var result = new LinkedHashMap<String, Object>();
        assertEquals(0, ownedThreads());
        result.put("before_first_attachment", memory()); result.put("disabled_fixture_idle_cpu", idleCpu());
        long start = System.nanoTime();
        try (var server = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("first"), List.of())) {
            result.put("first_model_factory_and_publication_ms", (System.nanoTime() - start) / 1e6);
            start = System.nanoTime();
            try (var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
                result.put("first_client_file_validation_connect_auth_ms", (System.nanoTime() - start) / 1e6);
                var requests = new ArrayList<Double>();
                for (int i = 0; i < 81; i++) {
                    start = System.nanoTime(); assertTrue(WorkspaceStatusContractTest.object(WorkspaceStatusTransportTest.exchange(client)).containsKey("result"));
                    requests.add((System.nanoTime() - start) / 1e6);
                }
                result.put("status_round_trip", samples(requests));
            }
            result.put("enabled_idle_cpu", idleCpu()); result.put("enabled_idle_memory", memory());
            String source = "// ordinary code alpha beta gamma delta\n".repeat(30000).substring(0, 1024 * 1024);
            var buffer = edt(() -> FileBuffer.createScratch("[A1 typing fixture]", source));
            var area = edt(() -> {
                JTextArea a = new JTextArea(); a.putClientProperty(SourcePlainView.SOURCE_PROPERTY, true);
                a.setUI(new WorkbenchStyle.TextAreaUI()); a.setFont(BundledEditorFont.atSize(16)); a.setDocument(buffer.getDocument());
                a.setBackground(new Color(0xFAFAFC)); a.setForeground(new Color(0x30343C)); a.setSize(720, 450); a.getCaret().setVisible(false); return a;
            });
            var image = new BufferedImage(720, 450, BufferedImage.TYPE_INT_ARGB);
            result.put("typing_control_1mib", samples(type(area, image, null)));
            try (var client = WorkspaceBridgeClient.status(server.descriptor(), server.credential())) {
                var typing = type(area, image, client); result.put("typing_with_status_1mib", samples(typing));
                assertTrue((double) samples(typing).get("warm_p95_ms") <= 16, "Supported component typing p95 exceeded 16 ms");
            }
            assertEquals(source, edt(buffer::getContent));
            edt(() -> { area.setDocument(new javax.swing.text.PlainDocument()); return null; }); image.flush();
        }
        WorkspaceStatusTransportTest.await(() -> ownedThreads() == 0);
        System.gc(); result.put("settled_before_cycles", memory());
        var create = new ArrayList<Double>(); var attach = new ArrayList<Double>(); var close = new ArrayList<Double>();
        var weak = new ArrayList<WeakReference<Object>>();
        for (int i = 0; i < 24; i++) weak.addAll(cycle(temporary.resolve("cycles"), create, attach, close));
        WorkspaceStatusTransportTest.await(() -> ownedThreads() == 0);
        for (int i = 0; i < 3; i++) { System.gc(); edt(() -> null); }
        long retained = weak.stream().filter(ref -> ref.get() != null).count();
        result.put("cycle_count", 24); result.put("factory_publication", samples(create)); result.put("client_attach_including_auth", samples(attach));
        result.put("server_close_including_owned_file_cleanup", samples(close)); result.put("retained_cycle_objects", retained);
        result.put("settled_after_cycles", memory());
        assertEquals(0, retained); assertEquals(0, ownedThreads());
        var enabledCpu = new ArrayList<Map<String, Object>>();
        try (var quiet = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("settled"), List.of())) {
            System.gc(); result.put("warm_enabled_settled_memory", memory());
            for (int i = 0; i < 3; i++) {
                var sample = idleCpu(); enabledCpu.add(sample);
                assertTrue((long) sample.get("attachment_platform_thread_cpu_ns") <= 2_000_000, "Idle attachment used unexpected thread CPU");
            }
        }
        WorkspaceStatusTransportTest.await(() -> ownedThreads() == 0);
        System.gc(); result.put("warm_disabled_settled_memory", memory());
        var disabledCpu = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 3; i++) disabledCpu.add(idleCpu());
        result.put("warm_enabled_idle_cpu", enabledCpu); result.put("warm_disabled_idle_cpu", disabledCpu);
        System.out.println("A1_MEASUREMENTS " + MiniJson.stringify(result));
    }

    private static List<Double> type(JTextArea area, BufferedImage image, WorkspaceBridgeClient client) throws Exception {
        var values = new ArrayList<Double>(); var barrier = new CyclicBarrier(2);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = client == null ? null : workers.submit(() -> {
                for (int i = 0; i < 51; i++) {
                    barrier.await(5, TimeUnit.SECONDS); WorkspaceStatusTransportTest.exchange(client); barrier.await(5, TimeUnit.SECONDS);
                }
                return true;
            });
            for (int i = 0; i < 51; i++) {
                if (client != null) barrier.await(5, TimeUnit.SECONDS);
                long start = System.nanoTime();
                edt(() -> {
                    area.insert("x", 0); var graphics = image.createGraphics();
                    try { graphics.setClip(0, 0, 720, 450); EditorUiController.applyEditorTextRenderingHints(graphics); area.paint(graphics); }
                    finally { graphics.dispose(); }
                    return null;
                });
                values.add((System.nanoTime() - start) / 1e6);
                edt(() -> { area.getDocument().remove(0, 1); return null; });
                if (client != null) barrier.await(5, TimeUnit.SECONDS);
            }
            if (pending != null) assertTrue(pending.get(5, TimeUnit.SECONDS));
        }
        return values;
    }
}
