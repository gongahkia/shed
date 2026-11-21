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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** opt-in component/loopback workload; no window, native shell, screenshot or provider. */
@EnabledIfSystemProperty(named="shed.test.status.measure",matches="true")
class WorkspaceMetadataMeasurementTest {
    @TempDir Path temporary;
    private static final com.sun.management.ThreadMXBean ALLOCATION=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();

    @Test void latencyEdtTypingAllocationsAndTwentyFourDisposalCycles() throws Exception {
        var output=new LinkedHashMap<String,Object>();
        assertEquals(0,WorkspaceStatusMeasurementTest.ownedThreads());
        output.put("disabled_memory",WorkspaceStatusMeasurementTest.memory());
        var occupancy=new ArrayList<Double>();var captureAllocations=new ArrayList<Long>();
        try(var f=new EditorMetadataTest.Fixture(temporary)) {
            var metadata=new EditorMetadata(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),()->{
                long allocated=ALLOCATION.getCurrentThreadAllocatedBytes(),start=System.nanoTime();
                try{return f.capture();}finally{occupancy.add((System.nanoTime()-start)/1e6);captureAllocations.add(ALLOCATION.getCurrentThreadAllocatedBytes()-allocated);}
            });
            try(var server=WorkspaceBridgeServer.metadata(f.application,metadata,temporary.resolve("private"),List.of(f.root))) {
                output.put("enabled_memory",WorkspaceStatusMeasurementTest.memory());
                try(var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {
                    var latencies=new ArrayList<Double>();var serviceCaptures=new ArrayList<Double>();var clientAllocation=new ArrayList<Long>();var totalAllocation=new ArrayList<Long>();
                    String request=client.metadataRequest();
                    for(int i=0;i<101;i++) {
                        long clientBefore=ALLOCATION.getCurrentThreadAllocatedBytes(),totalBefore=ALLOCATION.getTotalThreadAllocatedBytes(),start=System.nanoTime();
                        var result=WorkspaceMetadataTransportTest.result(client.exchange(request));
                        latencies.add((System.nanoTime()-start)/1e6);clientAllocation.add(ALLOCATION.getCurrentThreadAllocatedBytes()-clientBefore);
                        serviceCaptures.add(metadata.lastEdtNanos()/1e6);
                        totalAllocation.add(ALLOCATION.getTotalThreadAllocatedBytes()-totalBefore);assertEquals(true,result.get("valid"));
                    }
                    output.put("metadata_round_trip",WorkspaceStatusMeasurementTest.samples(latencies));
                    output.put("service_final_edt_capture",WorkspaceStatusMeasurementTest.samples(serviceCaptures));
                    output.put("client_thread_allocated_bytes_per_round_trip",clientAllocation);
                    output.put("jvm_total_allocation_delta_per_round_trip_including_unrelated_work",totalAllocation);
                }
                output.put("edt_capture_small",WorkspaceStatusMeasurementTest.samples(List.copyOf(occupancy)));output.put("edt_capture_allocated_bytes",captureAllocations);
                assertTrue((double)WorkspaceStatusMeasurementTest.samples(occupancy).get("warm_p95_ms")<=1,"Metadata EDT p95 exceeded 1 ms");
                assertTrue(occupancy.stream().mapToDouble(Double::doubleValue).max().orElseThrow()<=4,"Metadata EDT maximum exceeded 4 ms");
                String source="// ordinary code alpha beta gamma delta\n".repeat(30000).substring(0,1024*1024);
                // a fresh ASCII document avoids retaining Swing's i18n flag from the Unicode contract fixture
                Path ascii=Files.writeString(f.root.resolve("typing-ascii.java"),source);
                var area=EditorMetadataTest.edt(()->{
                    f.add(ascii);var a=f.active.getTextArea();f.active.getBuffer().setModified(true);
                    a.putClientProperty(SourcePlainView.SOURCE_PROPERTY,true);a.setUI(new WorkbenchStyle.TextAreaUI());a.setFont(BundledEditorFont.atSize(16));
                    a.setBackground(new Color(0xFAFAFC));a.setForeground(new Color(0x30343C));a.setSize(720,450);a.getCaret().setVisible(false);return a;
                });
                var image=new BufferedImage(720,450,BufferedImage.TYPE_INT_ARGB);
                var control=type(area,image,null,output);output.put("typing_control_1mib",WorkspaceStatusMeasurementTest.samples(control));
                assertTrue((double)WorkspaceStatusMeasurementTest.samples(control).get("warm_p95_ms")<=16,"Ordinary control typing p95 exceeded 16 ms");
                int capturedBefore=occupancy.size();
                try(var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {
                    var typing=type(area,image,client,output);output.put("typing_with_metadata_1mib",WorkspaceStatusMeasurementTest.samples(typing));
                    assertTrue((double)WorkspaceStatusMeasurementTest.samples(typing).get("warm_p95_ms")<=16,"Ordinary component typing p95 exceeded 16 ms");
                }
                var largeCaptures=List.copyOf(occupancy.subList(capturedBefore,occupancy.size()));
                output.put("edt_capture_during_1mib_typing",WorkspaceStatusMeasurementTest.samples(largeCaptures));
                assertTrue((double)WorkspaceStatusMeasurementTest.samples(largeCaptures).get("warm_p95_ms")<=1,"Large-document metadata EDT p95 exceeded 1 ms");
                assertTrue(largeCaptures.stream().mapToDouble(Double::doubleValue).max().orElseThrow()<=4,"Large-document metadata EDT maximum exceeded 4 ms");
                assertEquals(source,EditorMetadataTest.edt(()->f.active.getBuffer().getContent()));image.flush();
            }
        }
        WorkspaceStatusTransportTest.await(()->WorkspaceStatusMeasurementTest.ownedThreads()==0);
        for(int i=0;i<3;i++){System.gc();EditorMetadataTest.edt(()->null);}
        output.put("settled_before_cycles",WorkspaceStatusMeasurementTest.memory());
        var weak=new ArrayList<WeakReference<Object>>();
        for(int i=0;i<24;i++)weak.addAll(cycle(Files.createDirectory(temporary.resolve("cycle-"+i))));
        WorkspaceStatusTransportTest.await(()->WorkspaceStatusMeasurementTest.ownedThreads()==0);
        for(int i=0;i<3;i++){System.gc();EditorMetadataTest.edt(()->null);}
        long retained=weak.stream().filter(w->w.get()!=null).count();
        output.put("cycles",24);output.put("tracked_objects",weak.size());output.put("retained_tracked_objects",retained);
        output.put("settled_after_cycles",WorkspaceStatusMeasurementTest.memory());output.put("remaining_named_attachment_platform_threads",WorkspaceStatusMeasurementTest.ownedThreads());
        assertEquals(0,retained);assertEquals(0,WorkspaceStatusMeasurementTest.ownedThreads());
        System.out.println("A21_MEASUREMENTS "+MiniJson.stringify(output));
    }

    private static List<WeakReference<Object>> cycle(Path parent) throws Exception {
        try(var f=new EditorMetadataTest.Fixture(parent);var server=WorkspaceMetadataTransportTest.server(f,parent.resolve("private"))) {
            var weak=List.<WeakReference<Object>>of(new WeakReference<>(f.active),new WeakReference<>(f.active.getTextArea()),new WeakReference<>(f.active.getBuffer()),new WeakReference<>(server),new WeakReference<>(f.metadata));
            try(var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {WorkspaceMetadataTransportTest.result(client.exchange(client.metadataRequest()));}
            return weak;
        }
    }

    private static List<Double> type(javax.swing.JTextArea area,BufferedImage image,WorkspaceBridgeClient client,Map<String,Object> output) throws Exception {
        var values=new ArrayList<Double>();var barrier=new CyclicBarrier(2);var outcomes=new LinkedHashMap<String,Integer>();
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var traffic=client==null?null:workers.submit(()->{
                for(int i=0;i<51;i++) {
                    barrier.await(5,TimeUnit.SECONDS);
                    String response=client.exchange(client.metadataRequest());
                    String outcome=WorkspaceStatusContractTest.object(response).containsKey("result")?"snapshot":WorkspaceStatusContractTest.code(response);
                    assertTrue(List.of("snapshot","CONTEXT_CHANGED").contains(outcome),response);outcomes.merge(outcome,1,Integer::sum);
                    barrier.await(5,TimeUnit.SECONDS);
                }
                return true;
            });
            for(int i=0;i<51;i++) {
                if(client!=null)barrier.await(5,TimeUnit.SECONDS);
                long start=System.nanoTime();
                EditorMetadataTest.edt(()->{
                    area.insert("x",0);var graphics=image.createGraphics();
                    try{graphics.setClip(0,0,720,450);EditorUiController.applyEditorTextRenderingHints(graphics);area.paint(graphics);}finally{graphics.dispose();}
                    return null;
                });
                values.add((System.nanoTime()-start)/1e6);EditorMetadataTest.edt(()->{area.getDocument().remove(0,1);return null;});
                if(client!=null)barrier.await(5,TimeUnit.SECONDS);
            }
            if(traffic!=null){assertTrue(traffic.get(5,TimeUnit.SECONDS));output.put("metadata_traffic_outcomes_during_human_edits",outcomes);}
        }
        return values;
    }
}
