package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.EditorControlTest.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWorkbenchTransportTest {
    @TempDir Path temporary;
    static class Fixture extends EditorControlTest.Fixture {
        final AgentWorkbenchCoreTest.Fixture ui;
        Fixture(Path parent)throws Exception{super(parent);ui=edt(AgentWorkbenchCoreTest.Fixture::new);}
        public EditorControl.WatchRoot watchRoot(){return new EditorControl.WatchRoot(roots.active(),roots.generation());}
        public CompletableFuture<Map<String,Object>> workbench(String method,Map<String,Object> params,BooleanSupplier live,BooleanSupplier connection){
            if(method.endsWith("state"))return CompletableFuture.completedFuture(ui.session.state(((Long)params.get("offset")).intValue()));
            if(method.endsWith("inspect"))return CompletableFuture.completedFuture(ui.session.inspect((String)params.get("target"),((Long)params.get("offset")).intValue()));
            return ui.session.execute(AgentUiRequest.parse(params.get("request")),connection);
        }
        public void revokeUiActions(){ui.close();}
    }
    static Map<String,Object> params(Fixture fixture){return new LinkedHashMap<>(Map.of("schemaVersion",1L,"instanceId",fixture.server.statusApplication().instanceId,"windowId",fixture.server.statusApplication().windowId,"projectId",fixture.grant.projectId));}
    static Map<String,Object> observation(Map<String,Object> result){return MiniJson.asObject(result.get("observation"));}
    static Map<String,Object> state(Fixture f,WorkspaceBridgeClient c)throws Exception{var p=params(f);p.put("offset",0L);return result(exchange(c,"editor/ui/workbench-state",p));}
    static Map<String,Object> perform(Fixture f,WorkspaceBridgeClient c,String kind,String target,Map<String,Object> args)throws Exception{
        var before=observation(state(f,c));var p=params(f);p.put("request",Map.of("schemaVersion",2,"kind",kind,"target",target,"agent","Codex","expectedUiRevision",before.get("revision"),"args",args));return exchange(c,"editor/ui/workbench-perform",p);
    }
    @Test void authenticatedScrollAndPrivateTextWorkWithoutAnActiveSourceContext()throws Exception{
        try(var f=new Fixture(temporary);var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            edt(()->{var draft=new FileBuffer((String)null);f.pane.setBuffer(draft);f.pane.getTextArea().setDocument(draft.getDocument());return null;});
            assertEquals("workbench",observation(state(f,c)).get("kind"));
            var typed=result(perform(f,c,"text","query",Map.of("mode","set","text","needle","start",0L,"end",0L)));assertEquals("ui-updated",observation(typed).get("stage"));
            result(perform(f,c,"scroll","editor",Map.of("axis","vertical","unit","pages","amount",1L)));
            edt(()->{assertEquals("needle",f.ui.query.getText());assertTrue(f.ui.scroll.getViewport().getViewPosition().y>0);assertFalse(f.pane.getBuffer().isModified());return null;});
        }
    }
    @Test void permissionAndMalformedCommandsFailBeforeInvokingControls()throws Exception{
        try(var f=new Fixture(temporary);var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            assertEquals("PERMISSION_DENIED",code(perform(f,c,"click","run",Map.of())));
            assertEquals("INVALID_REQUEST",code(perform(f,c,"exec","query",Map.of())));
            assertEquals("INVALID_REQUEST",code(perform(f,c,"scroll","editor",Map.of("axis","vertical","unit","pixels","amount",Long.MIN_VALUE))));
            var p=params(f);p.put("offset",0L);p.put("source","private");assertEquals("INVALID_REQUEST",code(exchange(c,"editor/ui/workbench-state",p)));
            edt(()->{assertEquals(0,f.ui.clicks);return null;});
        }
    }
    @Test void navigationOnlyGrantDoesNotAcquireWorkbenchControl()throws Exception{
        try(var f=new Fixture(temporary)){
            var grant=EditorMetadata.navigation(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),f,f.interaction);
            try(var server=WorkspaceBridgeServer.metadata(f.application,grant,temporary.resolve("navigation"),List.of(f.root));var c=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())){
                for(String suffix:List.of("state","inspect","perform"))assertEquals("UNSUPPORTED",code(exchange(c,"editor/ui/workbench-"+suffix,Map.of())));
            }finally{grant.close();}
        }
    }
    @Test void cliProvidesSemanticCommandsAndFreshCheckedRevisions()throws Exception{
        try(var f=new Fixture(temporary)){
            var out=new ByteArrayOutputStream();
            assertEquals(0,cli(f,out,"","ui-targets"));assertEquals("workbench",observation(ShedControlCli.result(out.toString(java.nio.charset.StandardCharsets.UTF_8))).get("kind"));
            out.reset();assertEquals(0,cli(f,out,"hello","ui-type","query","--text-file","-"),out::toString);assertEquals("hello",edt(f.ui.query::getText));
            out.reset();assertEquals(0,cli(f,out,"","ui-scroll","editor","--amount","-10","--unit","pixels"),out::toString);
            out.reset();assertEquals(0,cli(f,out,"","ui-select","list","--index","1"),out::toString);assertEquals(1,edt(f.ui.list::getSelectedIndex));
            out.reset();assertEquals(0,cli(f,out,"","ui-inspect","list"),out::toString);
            out.reset();assertNotEquals(0,cli(f,out,"","ui-scroll","editor","--amount","10001"));
        }
    }
    static int cli(Fixture f,ByteArrayOutputStream out,String input,String...args){var argv=new ArrayList<>(List.of("ctl","--connect",f.server.descriptor().toString()));argv.addAll(List.of(args));return ShedControlCli.run(argv.toArray(String[]::new),new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)),out,OutputStream.nullOutputStream());}
    @Test void strictResponseSchemaRejectsInjectedFieldsAndImpossiblePaging()throws Exception{
        try(var f=new Fixture(temporary)){
            var state=edt(()->MiniJson.asObject(MiniJson.parseStrict(MiniJson.stringify(f.ui.session.state(0)))));assertTrue(AgentWorkbenchContract.accepts("editor/ui/workbench-state",state),state::toString);
            state.put("credentials","no");assertFalse(AgentWorkbenchContract.accepts("editor/ui/workbench-state",state));state.remove("credentials");state.put("nextOffset",999L);assertFalse(AgentWorkbenchContract.accepts("editor/ui/workbench-state",state));
        }
    }
    @Test void changingTheProjectRevokesWorkbenchAccess()throws Exception{
        try(var f=new Fixture(temporary);var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            result(stateReply(f,c));Path other=Files.createDirectory(temporary.resolve("other"));edt(()->{f.roots.add(other);f.roots.activate(other);return null;});
            var p=params(f);p.put("offset",0L);assertTrue(exchange(c,"editor/ui/workbench-state",p).containsKey("error"));
        }
    }
    private Map<String,Object> stateReply(Fixture f,WorkspaceBridgeClient c)throws Exception{var p=params(f);p.put("offset",0L);return exchange(c,"editor/ui/workbench-state",p);}
}
