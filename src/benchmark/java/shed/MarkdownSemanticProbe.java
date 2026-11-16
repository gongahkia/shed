package shed;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static shed.MarkdownSemanticSnapshot.*;

/** Opt-in, headless caller-thread measurements. Not keyboard, EDT or highlighting measurements. */
final class MarkdownSemanticProbe {
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final java.lang.management.MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        int samples = Integer.parseInt(args[1]), warmups = Integer.parseInt(args[2]);
        if (Files.size(input) > 10 * 1024 * 1024 || samples < 1 || samples > 100 || warmups < 0 || warmups > 10)
            throw new IllegalArgumentException("Bounded semantic workload required");
        long before = allocated(), began = System.nanoTime();
        var source = new Source(new DocumentWorkspace.Id(1), 0, VersionedTextSnapshot.of(Files.readString(input)));
        emit(Map.of("operation", "source_acquisition", "state", "OK", "wall_ms", millis(began),
            "allocated_bytes", allocated() - before, "characters", source.text().length()));
        var parser = new MarkdownSemanticParser();
        System.gc();
        long sourceHeld = MEMORY.getHeapMemoryUsage().getUsed();
        MarkdownSemanticSnapshot result = null;
        for (int sample = -warmups; sample < samples; sample++) {
            before = allocated(); began = System.nanoTime();
            result = parser.parse(source);
            double duration = millis(began);
            long bytes = allocated() - before;
            var row = new LinkedHashMap<String, Object>();
            row.put("operation", "semantic_parse"); row.put("sample", sample);
            row.put("state", result.status() != Status.SUCCEEDED ? result.status().name() : sample < 0 ? "WARMUP" : "OK");
            row.put("wall_ms", duration); row.put("allocated_bytes", bytes); row.put("status", result.status().name());
            row.put("coverage", result.coverage().name()); row.put("detail", result.detail());
            row.put("records", result.blocks().size() + result.headings().size() + result.codeBlocks().size()
                + result.inlines().size() + result.links().size() + result.references().size() + result.tasks().size());
            row.put("blocks", result.blocks().size()); row.put("headings", result.headings().size());
            row.put("links", result.links().size()); row.put("fences", result.codeBlocks().size());
            row.put("heap_used_bytes", MEMORY.getHeapMemoryUsage().getUsed());
            emit(row);
            if (result.status() != Status.SUCCEEDED) break; // Unsupported is NOT a near-zero successful parse.
            if (!result.isCurrent(source) || result.coveredRange().length() != source.text().length()) throw new AssertionError("Wrong identity/coverage");
            for (var block : result.blocks()) for (var range : block.location().segments()) range.validate(source.text());
        }
        System.gc();
        long resultHeld = MEMORY.getHeapMemoryUsage().getUsed();
        // Keep the intended live set reachable through the observation (not a heap-retention proof).
        java.lang.ref.Reference.reachabilityFence(result); java.lang.ref.Reference.reachabilityFence(source);
        result = null;
        System.gc();
        long released = MEMORY.getHeapMemoryUsage().getUsed();
        java.lang.ref.Reference.reachabilityFence(source);
        long gcCount = 0, gcMillis = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, collector.getCollectionCount()); gcMillis += Math.max(0, collector.getCollectionTime());
        }
        emit(Map.of("operation", "memory_observation", "state", "OBSERVED", "source_held_heap_bytes", sourceHeld,
            "result_and_source_held_heap_bytes", resultHeld, "result_released_source_held_heap_bytes", released,
            "committed_heap_bytes", MEMORY.getHeapMemoryUsage().getCommitted(), "gc_count", gcCount, "gc_millis", gcMillis));
        System.out.println("SEMANTIC_PASS");
    }

    private static long allocated() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static double millis(long began) { return (System.nanoTime() - began) / 1e6; }
    private static void emit(Map<String, Object> row) { System.out.println("SEMANTIC " + MiniJson.stringify(row)); }
}
