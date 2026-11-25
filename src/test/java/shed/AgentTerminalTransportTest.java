package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Authenticated loopback and in-memory terminal requests; no shell or native terminal launch. */
class AgentTerminalTransportTest {
    @TempDir Path temporary;
    static Map<String,Object> params(EditorControlTest.Fixture f,String action){return new LinkedHashMap<>(Map.of("schemaVersion",1L,"instanceId",f.application.instanceId,"windowId",f.application.windowId,"projectId",f.grant.projectId,"action",action,"requestId","test-command","agent",action.equals("request")?"Codex":"","commands",action.equals("request")?"{}":""));}
    @Test void terminalRequestsUseProjectIdentityWithoutNeedingAnActiveSource()throws Exception{
        var seen=new ArrayList<String>();var permission=new java.util.concurrent.atomic.AtomicReference<BooleanSupplier>();
        try(var f=new EditorControlTest.Fixture(temporary){
            @Override public EditorNavigation.State state(){throw new AssertionError("Terminal requests must not require a source buffer");}
            @Override public EditorControl.WatchRoot watchRoot(){return new EditorControl.WatchRoot(root,roots.generation());}
            @Override public Map<String,Object> terminal(String action,String id,String agent,String body,BooleanSupplier authorized){assertTrue(SwingUtilities.isEventDispatchThread());assertTrue(authorized.getAsBoolean());permission.set(authorized);seen.add(action);return Map.of("requestId",id,"agent","Codex","state","awaiting-approval","result",Map.of(),"error","");}
        };var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            for(String action:List.of("request","status","cancel")){
                var response=EditorControlTest.exchange(client,"editor/terminal",params(f,action));assertNull(response.get("error"),response.toString());
            }
            assertEquals(List.of("request","status","cancel"),seen);f.grant.close();assertFalse(permission.get().getAsBoolean());
        }
    }
    @Test void normalSourceControlDoesNotAcquireTerminalExecutionAccess()throws Exception{
        try(var f=new EditorControlTest.Fixture(temporary);var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            assertEquals("UNSUPPORTED",EditorControlTest.code(EditorControlTest.exchange(client,"editor/terminal",params(f,"request"))));
            var request=params(f,"request");request.put("projectId","wrong");assertEquals("PROJECT_UNAUTHORIZED",EditorControlTest.code(EditorControlTest.exchange(client,"editor/terminal",request)));
            request=params(f,"request");request.put("instanceId","wrong");assertEquals("UNAUTHORIZED",EditorControlTest.code(EditorControlTest.exchange(client,"editor/terminal",request)));
            request=params(f,"cancel");request.put("commands","unexpected");assertEquals("INVALID_REQUEST",EditorControlTest.code(EditorControlTest.exchange(client,"editor/terminal",request)));
        }
    }
    @Test void cliForwardsBoundedCommandPlansAndStatusRequests()throws Exception{
        var seen=new ArrayList<String>();Path body=Files.writeString(temporary.resolve("commands.json"),"{\"reason\":\"test\",\"commands\":[]}");
        try(var f=new EditorControlTest.Fixture(temporary){
            @Override public Map<String,Object> terminal(String action,String id,String agent,String commands,BooleanSupplier authorized){seen.add(action);return Map.of("requestId",id,"agent","Codex","state","awaiting-approval","result",Map.of(),"error","");}
        }){
            assertEquals(0,AgentBufferIntentTest.cli(f,"terminal-run","--commands-file",body.toString(),"--request-id","test-command","--agent","Codex"));
            assertEquals(0,AgentBufferIntentTest.cli(f,"terminal-status","--request-id","test-command"));
            assertEquals(0,AgentBufferIntentTest.cli(f,"terminal-cancel","--request-id","test-command"));
            assertNotEquals(0,AgentBufferIntentTest.cli(f,"terminal-run","--commands-file",body.toString(),"--request-id","bad id"));assertEquals(3,seen.size());
        }
    }
    @Test void boundedTerminalResultsRemainStructuredAndFitTheControlReplyBudget(){
        var entries=new ArrayList<Map<String,Object>>();for(int i=0;i<4;i++)entries.add(Map.of("command",i+1,"exitCode",7,"output","😀".repeat(10000),"error",""));
        var result=AgentTerminalRequests.boundedResult(MiniJson.stringify(Map.of("approved",true,"results",entries)));
        assertTrue(MiniJson.stringify(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<20000);
        var bounded=MiniJson.asArray(MiniJson.asObject(result).get("results"));assertEquals(4,bounded.size());assertEquals(7L,MiniJson.asObject(bounded.getFirst()).get("exitCode"));assertTrue(MiniJson.asObject(bounded.getFirst()).get("output").toString().contains("truncated"));
    }
}
