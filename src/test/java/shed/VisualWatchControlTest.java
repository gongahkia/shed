package shed;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class VisualWatchControlTest {
    @TempDir Path temporary;
    @Test void launcherResolvesClasspathBeforeChangingTheChildWorkingDirectory(){
        String separator=java.io.File.pathSeparator;
        String value=ShedVisualize.absoluteClassPath("target/shed.jar"+separator+"lib jars/*"+separator,temporary);
        String[] entries=value.split(java.util.regex.Pattern.quote(separator),-1);
        assertEquals(temporary.resolve("target/shed.jar").toAbsolutePath().toString(),entries[0]);
        assertEquals(temporary.resolve("lib jars/*").toAbsolutePath().toString(),entries[1]);assertEquals(temporary.toAbsolutePath().toString(),entries[2]);
    }
    @Test void watchIsAuthenticatedAndProjectScopedWithoutAnOpenDocument() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary){
            public EditorControl.WatchRoot watchRoot(){return new EditorControl.WatchRoot(roots.active(),roots.generation());}
            public Map<String,Object> watch(String action,String name){return Map.of("kind","watch","ready",true,"following",!action.equals("pause"));}
        };var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            EditorControlTest.edt(()->{f.pane.setBuffer(null);return null;});
            var d=WorkspaceStatusAttachment.Descriptor.read(f.server.descriptor());
            var p=new LinkedHashMap<String,Object>(Map.of("schemaVersion",1L,"instanceId",d.instanceId(),"windowId",d.windowId(),"projectId",d.projectId(),"action","state","agent","Codex"));
            var reply=EditorControlTest.result(EditorControlTest.exchange(c,"editor/watch",p));assertEquals("editor/watch",reply.get("operation"));assertFalse(reply.containsKey("documentId"));
            p.put("projectId","another-project");assertEquals("PROJECT_UNAUTHORIZED",EditorControlTest.code(EditorControlTest.exchange(c,"editor/watch",p)));
            p.put("projectId",d.projectId());p.put("instanceId",UUID.randomUUID().toString());assertEquals("UNAUTHORIZED",EditorControlTest.code(EditorControlTest.exchange(c,"editor/watch",p)));
        }
    }
}
