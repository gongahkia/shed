package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.EditorControlTest.*;
import java.awt.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real authenticated bridge and canonical source; only the Find surface is a component double. */
class AgentUiControlTest {
    @TempDir Path temporary;
    static class Fixture extends EditorControlTest.Fixture {
        final JTextField field=new JTextField(){@Override public boolean isShowing(){return true;}};
        final JButton button=new JButton(){@Override public boolean isShowing(){return true;}};
        final JRootPane rootPane=new JRootPane();
        AgentUiActions ui;
        boolean visible=true;
        long revision;
        String fingerprint="";
        Fixture(Path path)throws Exception {
            super(path);
            edt(()->{
                rootPane.setSize(640,200);rootPane.getContentPane().add(field);rootPane.getContentPane().add(button);rootPane.doLayout();
                ui=new AgentUiActions(new AgentUiActions.Surface(){
                    public Map<String,Object> state(){
                        String now=visible+field.getText()+field.getCaretPosition();if(!now.equals(fingerprint)){fingerprint=now;revision++;}
                        var m=new LinkedHashMap<String,Object>();m.put("kind","ui");m.put("schemaVersion",1);m.put("revision",revision);
                        m.put("visible",visible);m.put("mode","find");m.put("query",field.getText());m.put("caret",field.getCaretPosition());m.put("start",field.getSelectionStart());m.put("end",field.getSelectionEnd());
                        m.put("case",true);m.put("regex",false);m.put("word",false);m.put("phase","idle");m.put("matches",0);m.put("selected",-1);m.put("truncated",false);return m;
                    }
                    public Component target(String action){return action.equals("find.query")||action.equals("find.open")?field:button;}
                    public void invoke(String action){if(action.equals("find.close"))visible=false;if(action.equals("find.open"))visible=true;}
                    public boolean current(){return !closing;}
                    public boolean motion(){return false;}
                });return null;
            });
        }
        public CompletableFuture<Map<String,Object>> uiAction(String method,Map<String,Object> params,BooleanSupplier current){
            assertTrue(SwingUtilities.isEventDispatchThread());
            return method.endsWith("actions")?CompletableFuture.completedFuture(ui.catalog()):method.endsWith("state")?CompletableFuture.completedFuture(ui.state()):ui.invoke(AgentUiContract.command(params),current);
        }
        public void revokeUiActions(){ui.close();}
    }
    private Map<String,Object> invoke(WorkspaceBridgeClient client,String action,long revision,String text)throws Exception {
        var p=params(context(client));p.put("action",action);p.put("expectedUiRevision",revision);p.put("agent","Codex");p.put("text",text);return exchange(client,"editor/ui/invoke",p);
    }
    @Test void authenticatedActionsAndTextUseRealFieldWithoutTouchingSourceOrUndo()throws Exception {
        try(var f=new Fixture(temporary);var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            String source=edt(()->f.pane.getBuffer().getContent());long revision=edt(()->f.pane.getBuffer().documentRevision());
            var catalog=result(exchange(client,"editor/ui/actions",params(context(client))));assertEquals("ui-actions",MiniJson.asObject(catalog.get("observation")).get("kind"));
            var state=MiniJson.asObject(result(exchange(client,"editor/ui/state",params(context(client)))).get("observation"));
            var changed=MiniJson.asObject(result(invoke(client,"find.query",(Long)state.get("revision"),"value")).get("observation"));
            assertEquals("value",changed.get("query"));
            assertEquals("CONTEXT_CHANGED",code(invoke(client,"find.close",(Long)state.get("revision"),"")));
            result(invoke(client,"find.close",(Long)changed.get("revision"),""));
            edt(()->{assertEquals(source,f.pane.getBuffer().getContent());assertEquals(revision,f.pane.getBuffer().documentRevision());assertFalse(f.pane.getBuffer().isModified());assertFalse(f.pane.getBuffer().getUndoManager().canUndo());assertFalse(f.ui.active());return null;});
        }
    }
    @Test void unknownActionsExtraFieldsAndInvalidRevisionFailClosed()throws Exception {
        try(var f=new Fixture(temporary);var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            assertEquals("INVALID_REQUEST",code(invoke(client,"file.save",0,"")));
            var p=params(context(client));p.put("source","secret");assertEquals("INVALID_REQUEST",code(exchange(client,"editor/ui/state",p)));
            assertEquals("INVALID_REQUEST",code(invoke(client,"find.query",0,"x\ny")));
            assertEquals("INVALID_REQUEST",code(invoke(client,"find.query",-1,"x")));
            edt(()->{assertEquals("",f.field.getText());return null;});
        }
    }
    @Test void navigationGrantCannotInvokeOrObserveUi()throws Exception {
        try(var f=new Fixture(temporary)){
            var grant=EditorMetadata.navigation(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),f,f.interaction);
            try(var server=WorkspaceBridgeServer.metadata(f.application,grant,temporary.resolve("navigation"),List.of(f.root));var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())){
                for(String method:List.of("editor/ui/actions","editor/ui/state","editor/ui/invoke"))assertEquals("UNSUPPORTED",code(exchange(client,method,Map.of())));
            }finally{grant.close();}
        }
    }
    @Test void cliRequiresExplicitUiRevisionAndReadsTextFromStdin()throws Exception {
        try(var f=new Fixture(temporary)){
            var out=new ByteArrayOutputStream();String[] args={"ctl","--connect",f.server.descriptor().toString(),"ui-state"};
            assertEquals(0,ShedControlCli.run(args,InputStream.nullInputStream(),out,OutputStream.nullOutputStream()));
            var response=MiniJson.asObject(MiniJson.parseStrict(out.toString(java.nio.charset.StandardCharsets.UTF_8)));
            var state=MiniJson.asObject(MiniJson.asObject(response.get("result")).get("observation"));
            args=new String[]{"ctl","--connect",f.server.descriptor().toString(),"ui-text","--ui-revision",state.get("revision").toString(),"--text-file","-","--agent","Any agent"};out.reset();
            assertEquals(0,ShedControlCli.run(args,new ByteArrayInputStream("needle".getBytes(java.nio.charset.StandardCharsets.UTF_8)),out,OutputStream.nullOutputStream()),out::toString);
            assertEquals("needle",edt(f.field::getText));
            args=new String[]{"ctl","--connect",f.server.descriptor().toString(),"ui-action","find.close"};out.reset();
            assertEquals(64,ShedControlCli.run(args,InputStream.nullInputStream(),out,OutputStream.nullOutputStream()));
        }
    }
    @Test void responseValidatorRejectsExtraDataAndImpossibleGeometry()throws Exception {
        try(var f=new Fixture(temporary)){
            var state=edt(()->MiniJson.asObject(MiniJson.parseStrict(MiniJson.stringify(f.ui.state()))));
            assertTrue(AgentUiContract.accepts(state,false));
            state.put("source","not allowed");assertFalse(AgentUiContract.accepts(state,false));state.remove("source");
            state.put("end",10L);assertFalse(AgentUiContract.accepts(state,false));
            var catalog=edt(()->MiniJson.asObject(MiniJson.parseStrict(MiniJson.stringify(f.ui.catalog()))));assertTrue(AgentUiContract.accepts(catalog,true));
            catalog.put("actions",List.of(Map.of("id","terminal.run","kind","control","enabled",true)));assertFalse(AgentUiContract.accepts(catalog,true));
        }
    }
}
