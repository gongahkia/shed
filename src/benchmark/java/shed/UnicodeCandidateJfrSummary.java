package shed;

import java.nio.file.*;
import java.util.*;
import jdk.jfr.consumer.*;

/** Bounded streaming first-edit attribution. Never exports the recording to a large JSON event tree. */
public final class UnicodeCandidateJfrSummary {
    private static boolean truncated;
    private static void count(Map<String, Long> map, String key, long amount) {
        if (map.size() < 4096 || map.containsKey(key)) map.merge(key, amount, Long::sum);
        else truncated = true;
    }
    private static java.util.List<Map<String, Object>> top(Map<String, Long> map) {
        return map.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(12)
            .map(e -> Map.<String, Object>of("value", e.getValue(), "stack", e.getKey())).toList();
    }
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        if (Files.size(path) > 128L * 1024 * 1024) throw new IllegalArgumentException("Recording exceeds 128 MiB diagnostic bound");
        double begin = Double.parseDouble(args[1]), end = Double.parseDouble(args[2]);
        Map<String, Long> stacks = new HashMap<>(), allocations = new HashMap<>(), groups = new TreeMap<>();
        long samples = 0, allocatedSamples = 0;
        try (var recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                double at = event.getStartTime().getEpochSecond() * 1000d + event.getStartTime().getNano() / 1e6;
                if (at < begin || at > end) continue;
                String type = event.getEventType().getName();
                boolean allocation = type.equals("jdk.ObjectAllocationSample");
                if (!allocation && !type.equals("jdk.ExecutionSample") && !type.equals("jdk.NativeMethodSample")) continue;
                var thread = allocation ? event.getThread() : event.getThread("sampledThread");
                if (thread == null || !thread.getJavaName().startsWith("AWT-EventQueue")) continue;
                var trace = event.getStackTrace();
                if (trace == null) continue;
                var frames = new ArrayList<String>();
                for (var frame : trace.getFrames()) {
                    if (frames.size() == 48) break;
                    frames.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName());
                }
                String stack = String.join(";", frames);
                if (allocation) {
                    allocatedSamples++;
                    count(allocations, event.getClass("objectClass").getName() + " " + stack, event.getLong("weight"));
                } else {
                    samples++; count(stacks, stack, 1);
                    for (String needle : new String[]{"GapContent.getChars", "GlyphView.getBreakSpot", "SunLayoutEngine", "TextLayoutStrategy.sync",
                            "GlyphPainter2.getSpan", "ParagraphView.findEdgeSpan", "BasicTextUI$UpdateHandler.insertUpdate"}) {
                        if (stack.contains(needle)) groups.merge(needle, 1L, Long::sum);
                    }
                }
            }
        }
        System.out.println(MiniJson.stringify(Map.of("recording", path.toString(), "begin_epoch_ms", begin, "end_epoch_ms", end,
            "edt_samples", samples, "allocation_samples", allocatedSamples, "overlapping_stack_presence", groups,
            "top_stacks", top(stacks), "top_weighted_allocation_stacks", top(allocations), "distinct_stack_bound_reached", truncated)));
    }
}
