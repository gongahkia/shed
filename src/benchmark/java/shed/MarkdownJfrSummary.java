package shed;

import java.nio.file.Path;
import java.util.*;
import jdk.jfr.consumer.*;

/** Streaming summary of explicit benchmark recordings; sample counts are not CPU percentages or retained bytes. */
public final class MarkdownJfrSummary {
    public static void main(String[] args) throws Exception {
        Map<String,Long> stacks=new HashMap<>(), allocations=new HashMap<>(), groups=new TreeMap<>();
        List<Double> pauses=new ArrayList<>();long samples=0,allocationSamples=0;
        try(RecordingFile recording=new RecordingFile(Path.of(args[0]))) {
            while(recording.hasMoreEvents()) {
                RecordedEvent e=recording.readEvent();String type=e.getEventType().getName();
                if(type.equals("jdk.GCPhasePause"))pauses.add(e.getDuration().toNanos()/1e6);
                if(type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample")) {
                    samples++;RecordedThread thread=e.getThread("sampledThread");
                    String name=thread==null?"unknown":thread.getJavaName();String category=name.startsWith("AWT-EventQueue")?"EDT":"other";
                    var trace=e.getStackTrace();if(trace==null)continue;
                    List<String> frames=new ArrayList<>();for(RecordedFrame f:trace.getFrames())frames.add(f.getMethod().getType().getName()+"."+f.getMethod().getName());
                    String joined=String.join(";",frames);stacks.merge(category+" "+joined,1L,Long::sum);
                    for(String needle:List.of("PlainView","WrappedPlainView","GapContent","VersionedTextSnapshot","reconcileDocumentText","GrammarHighlightService","highlightSnapshot","MarkdownPreviewRenderer","MarkdownPreviewAssets","HTMLEditorKit","HTMLDocument","FlowView","ParagraphView","SunLayoutEngine","FontDesignMetrics","UndoManager.undo","DefaultDocumentEvent.undo"))
                        if(joined.contains(needle))groups.merge(category+" "+needle,1L,Long::sum);
                }
                if(type.equals("jdk.ObjectAllocationSample")) {
                    allocationSamples++;String name=e.getClass("objectClass").getName();
                    long weight=e.hasField("weight")?e.getLong("weight"):1L;allocations.merge(name,weight,Long::sum);
                }
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("recording",args[0]);result.put("execution_or_native_samples",samples);
        result.put("overlapping_stack_presence_counts",groups);result.put("top_stacks",top(stacks,18));
        result.put("allocation_samples",allocationSamples);result.put("top_weighted_allocation_samples_not_retained",top(allocations,15));
        result.put("gc_pause_ms",pauses);result.put("gc_pause_total_ms",pauses.stream().mapToDouble(Double::doubleValue).sum());
        System.out.println(MiniJson.stringify(result));
    }
    private static List<Map<String,Object>> top(Map<String,Long> values,int limit) {
        return values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(limit)
            .map(e -> Map.<String,Object>of("key",e.getKey(),"value",e.getValue())).toList();
    }
}
