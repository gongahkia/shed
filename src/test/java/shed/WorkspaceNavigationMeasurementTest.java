package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** opt-in actual pane/surface, loopback and offscreen paint; no JFrame or native fixture. */
@EnabledIfSystemProperty(named="shed.test.status.measure",matches="true")
class WorkspaceNavigationMeasurementTest {
    @TempDir Path temporary;
    static final com.sun.management.ThreadMXBean ALLOCATION=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static final class Measured implements EditorNavigation.Host {
        final EditorNavigationTest.Fixture f;
        final Map<String,List<Double>> times=new LinkedHashMap<>();
        final Map<String,List<Long>> allocations=new LinkedHashMap<>();
        Measured(EditorNavigationTest.Fixture f){this.f=f;}
        <T> T measure(String key,java.util.function.Supplier<T> operation) {
            long start=System.nanoTime(),allocated=ALLOCATION.getCurrentThreadAllocatedBytes();
            try{return operation.get();}finally{times.computeIfAbsent(key,k->new ArrayList<>()).add((System.nanoTime()-start)/1e6);allocations.computeIfAbsent(key,k->new ArrayList<>()).add(ALLOCATION.getCurrentThreadAllocatedBytes()-allocated);}
        }
        public EditorMetadata.Context capture(){return f.capture();}
        public EditorNavigation.State state(){return measure("state",f::state);}
        public EditorNavigation.Prepared prepare(EditorNavigation.Command c){return measure("prepare",()->f.prepare(c));}
        public EditorNavigation.Applied apply(EditorNavigation.Prepared p,BooleanSupplier authorized){return measure(p.command().method(),()->f.apply(p,authorized));}
    }
    static String command(Map<String,Object> context,String method,int start,int end){return WorkspaceNavigationTransportTest.request(method,EditorNavigationTest.params(context,method,start,end));}
    static Map<String,Object> exchange(WorkspaceBridgeClient client,String request){
        try{return WorkspaceNavigationTransportTest.result(client.exchange(request));}catch(java.io.IOException e){throw new AssertionError(e);}
    }
    @Test void navigationLatencyEdtTypingAllocationAndDisposal() throws Exception {
        var output=new LinkedHashMap<String,Object>();
        assertEquals(0,WorkspaceStatusMeasurementTest.ownedThreads());
        output.put("disabled_memory",WorkspaceStatusMeasurementTest.memory());output.put("disabled_idle_cpu",WorkspaceStatusMeasurementTest.idleCpu());
        try(var f=new EditorNavigationTest.Fixture(temporary)) {
            EditorMetadataTest.edt(()->{f.editor.active.getBuffer().setContent("ordinary ASCII code\n".repeat(80));f.geometry();return null;});
            var measured=new Measured(f);
            var grant=EditorMetadata.navigation(f.editor.application,new EditorMetadata.Approval(f.editor.root,f.editor.roots.generation()),measured,f.interaction);
            try(var server=WorkspaceBridgeServer.metadata(f.editor.application,grant,temporary.resolve("private"),List.of(f.editor.root))) {
                output.put("enabled_memory",WorkspaceStatusMeasurementTest.memory());output.put("enabled_idle_cpu",WorkspaceStatusMeasurementTest.idleCpu());
                for(String method:List.of("editor/caret/set","editor/selection/set","editor/reveal")) {
                    var latencies=new ArrayList<Double>();var allocation=new ArrayList<Long>();
                    try(var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
                        for(int i=0;i<61;i++) {
                            var context=exchange(client,client.navigationContextRequest());String request=command(context,method,i%2==0?1:2,method.equals("editor/caret/set")?1:6);
                            long allocated=ALLOCATION.getTotalThreadAllocatedBytes(),start=System.nanoTime();
                            assertEquals(true,exchange(client,request).get("applied"));latencies.add((System.nanoTime()-start)/1e6);allocation.add(ALLOCATION.getTotalThreadAllocatedBytes()-allocated);
                        }
                    }
                    output.put(method,WorkspaceStatusMeasurementTest.samples(latencies));output.put(method+"_all_threads_allocated_bytes",allocation);
                }
                // a separate fresh ASCII document avoids the Unicode fixture's sticky Swing i18n flag
                String source="// ordinary code alpha beta gamma delta\n".repeat(30000).substring(0,1024*1024);
                Path file=Files.writeString(f.editor.root.resolve("typing-ascii.java"),source);
                var area=EditorMetadataTest.edt(()->{
                    f.editor.add(file);f.activate(f.editor.active);f.editor.active.getBuffer().setModified(true);
                    var a=f.editor.active.getTextArea();a.putClientProperty(SourcePlainView.SOURCE_PROPERTY,true);a.setUI(new WorkbenchStyle.TextAreaUI());a.setFont(BundledEditorFont.atSize(16));
                    a.setBackground(new Color(0xFAFAFC));a.setForeground(new Color(0x30343C));a.setSize(720,450);a.getCaret().setVisible(false);return a;
                });
                var image=new BufferedImage(720,450,BufferedImage.TYPE_INT_ARGB);
                var control=type(area,image,null,output);output.put("typing_control_1mib",WorkspaceStatusMeasurementTest.samples(control));
                assertTrue((double)WorkspaceStatusMeasurementTest.samples(control).get("warm_p95_ms")<=16,"Control typing p95 exceeds 16 ms");
                try(var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
                    var typing=type(area,image,client,output);output.put("typing_with_navigation_1mib",WorkspaceStatusMeasurementTest.samples(typing));
                    assertTrue((double)WorkspaceStatusMeasurementTest.samples(typing).get("warm_p95_ms")<=16,"Navigation traffic typing p95 exceeds 16 ms");
                }
                assertEquals(source,EditorMetadataTest.edt(()->f.editor.active.getBuffer().getContent()));image.flush();
                measured.times.forEach((key,value)->output.put("edt_"+key,WorkspaceStatusMeasurementTest.samples(value)));
                output.put("edt_allocated_bytes",measured.allocations);
                for(var entry:measured.times.entrySet()) assertTrue((double)WorkspaceStatusMeasurementTest.samples(entry.getValue()).get("warm_p95_ms")<=4,"EDT p95 exceeds 4 ms: "+entry.getKey());
            }
        }
        WorkspaceStatusTransportTest.await(()->WorkspaceStatusMeasurementTest.ownedThreads()==0);
        collect();output.put("settled_before_cycles",WorkspaceStatusMeasurementTest.memory());
        var weak=new ArrayList<WeakReference<Object>>();
        for(int i=0;i<24;i++)weak.addAll(cycle(Files.createDirectory(temporary.resolve("cycle-"+i))));
        WorkspaceStatusTransportTest.await(()->WorkspaceStatusMeasurementTest.ownedThreads()==0);
        collect();long retained=weak.stream().filter(w->w.get()!=null).count();
        output.put("cycles",24);output.put("tracked_objects",weak.size());output.put("retained_tracked_objects",retained);
        output.put("settled_after_cycles",WorkspaceStatusMeasurementTest.memory());output.put("remaining_named_attachment_platform_threads",WorkspaceStatusMeasurementTest.ownedThreads());
        assertEquals(0,retained);assertEquals(0,WorkspaceStatusMeasurementTest.ownedThreads());
        System.out.println("A22_MEASUREMENTS "+MiniJson.stringify(output));
    }
    static void collect() throws Exception {for(int i=0;i<3;i++){System.gc();EditorMetadataTest.edt(()->null);}}
    static List<WeakReference<Object>> cycle(Path parent) throws Exception {
        try(var f=new EditorNavigationTest.Fixture(parent);var server=WorkspaceNavigationTransportTest.server(f,parent.resolve("private"))) {
            var weak=List.<WeakReference<Object>>of(new WeakReference<>(f.editor.active),new WeakReference<>(f.editor.active.getTextArea()),new WeakReference<>(f.editor.active.getBuffer()),
                new WeakReference<>(server),new WeakReference<>(f.grant),new WeakReference<>(f.interaction),new WeakReference<>(f.grant.navigation));
            try(var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
                var c=exchange(client,client.navigationContextRequest());assertEquals(true,exchange(client,command(c,"editor/caret/set",1,1)).get("applied"));
            }
            return weak;
        }
    }
    static List<Double> type(javax.swing.JTextArea area,BufferedImage image,WorkspaceBridgeClient client,Map<String,Object> output) throws Exception {
        var values=new ArrayList<Double>();var barrier=new CyclicBarrier(2);var outcomes=new LinkedHashMap<String,Integer>();
        try(var worker=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var traffic=client==null?null:worker.submit(()->{
                for(int i=0;i<51;i++) {
                    var context=exchange(client,client.navigationContextRequest());
                    barrier.await(5,TimeUnit.SECONDS);
                    String response=client.exchange(command(context,i%2==0?"editor/caret/set":"editor/selection/set",1,6));
                    String outcome=WorkspaceStatusContractTest.object(response).containsKey("result")?"applied":WorkspaceStatusContractTest.code(response);
                    assertTrue(List.of("applied","CONTEXT_CHANGED","STALE_REVISION").contains(outcome),response);outcomes.merge(outcome,1,Integer::sum);
                    barrier.await(5,TimeUnit.SECONDS);
                }
                return true;
            });
            for(int i=0;i<51;i++) {
                if(client!=null)barrier.await(5,TimeUnit.SECONDS);
                long start=System.nanoTime();
                EditorMetadataTest.edt(()->{
                    area.insert("x",0);var graphics=image.createGraphics();
                    try{graphics.setClip(0,0,720,450);EditorUiController.applyEditorTextRenderingHints(graphics);area.paint(graphics);}finally{graphics.dispose();}return null;
                });
                values.add((System.nanoTime()-start)/1e6);EditorMetadataTest.edt(()->{area.getDocument().remove(0,1);return null;});
                if(client!=null)barrier.await(5,TimeUnit.SECONDS);
            }
            if(traffic!=null){assertTrue(traffic.get(5,TimeUnit.SECONDS));output.put("navigation_traffic_outcomes_during_human_edits",outcomes);}
        }
        return values;
    }
}
