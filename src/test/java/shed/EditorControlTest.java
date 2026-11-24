package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Authenticated loopback plus real canonical documents; no shell, native window or language server. */
class EditorControlTest {
    @TempDir Path temporary;
    static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get(5,TimeUnit.SECONDS);
    }
    static class Fixture implements EditorControl.Host, AutoCloseable {
        final Path root, storage;
        final ApplicationStatus application = new ApplicationStatus(true);
        final WorkspaceRoots roots = new WorkspaceRoots();
        final LegacyDocumentStore store = new LegacyDocumentStore(null);
        final DocumentRequests<EditorPane> requests = new DocumentRequests<>();
        final AsyncJobService jobs = new AsyncJobService(20);
        final EditorInteraction interaction = new EditorInteraction();
        final EditorWorkspaceDocuments documents;
        final EditorPane pane;
        final WorkspaceQueryService queries;
        final WorkspaceProposalService proposals;
        final EditorMetadata grant;
        final WorkspaceBridgeServer server;
        String reviewed;
        boolean closing;
        Fixture(Path parent) throws Exception {
            root=Files.createDirectory(parent.resolve("project")).toRealPath(); storage=parent.resolve("sessions");
            Files.writeString(root.resolve("One.java"),"class One {\n  int value = 1;\n}\n");
            Files.writeString(root.resolve("Two.java"),"class Two {\n  String value = \"😀\";\n}\n");
            Files.writeString(root.resolve("private.env"),"synthetic secret");
            documents=new EditorWorkspaceDocuments(()->List.copyOf(store.legacyBuffers()),roots::all,roots::active,root);
            pane=edt(()->{
                roots.add(root); var b=store.load(root.resolve("One.java"));store.legacyBuffers().add(b);
                var a=new JTextArea();a.setFont(BundledEditorFont.atSize(16));a.setDocument(b.getDocument());a.setSize(700,500);
                var p=new EditorPane(a,null,new JScrollPane(a),new SearchManager(a));p.setBuffer(b);interaction.watch(p);return p;
            });
            queries=new WorkspaceQueryService(root,new AgentPolicy(Set.of(AgentPolicy.Capability.READ,AgentPolicy.Capability.PROPOSE),
                List.of("**/README.md"),List.of("**/*.env","**/*.env.*"),true),documents);
            proposals=new WorkspaceProposalService(queries,documents,storage.resolve("agent-workspace").resolve(queries.workspaceId()),"editor-control");
            grant=EditorMetadata.control(application,new EditorMetadata.Approval(root,roots.generation()),this,interaction,queries,proposals);
            server=WorkspaceBridgeServer.metadata(application,grant,parent.resolve("attachment"),List.of(root));
        }
        public EditorMetadata.Context capture(){return EditorMetadata.capture(pane,documents,roots.active(),roots.all(),roots.generation());}
        public EditorNavigation.State state(){return new EditorNavigation.State(capture(),interaction.revision());}
        public EditorNavigation.Prepared prepare(EditorNavigation.Command command){return EditorNavigation.prepare(pane,state(),command,!closing);}
        public EditorNavigation.Applied apply(EditorNavigation.Prepared prepared,BooleanSupplier current){
            return EditorNavigation.apply(pane,prepared,state(),!closing,interaction,current,this::state);
        }
        public ProjectFileOpen.Request open(ProjectBoundFileAcquisition.RootPin root,String name,EditorNavigation.State expected,
                BooleanSupplier current,Consumer<ProjectDocumentInstall.Result> completed){
            return ProjectFileOpen.start(jobs,store,requests,pane,root,name,
                prepared->!closing&&current.getAsBoolean()&&ProjectDocumentInstall.matches(prepared,pane,expected,state()),
                prepared->{var b=PaneBufferController.registerLoadedBuffer(store,prepared.buffer().getFile(),prepared.buffer(),ignored->{});
                    pane.setBuffer(b);pane.getTextArea().setDocument(b.getDocument());},completed);
        }
        public void activity(int start,int end){pane.agentPresentation().showPending(start,end);}
        public void review(String id){reviewed=id;}
        public void clearActivity(){pane.clearAgentPresentation();}
        WorkspaceProposalService human(){
            var q=new WorkspaceQueryService(root,new AgentPolicy(Set.of(AgentPolicy.Capability.READ,AgentPolicy.Capability.PROPOSE,AgentPolicy.Capability.APPLY),
                List.of("**/README.md"),List.of(),true),documents);
            return new WorkspaceProposalService(q,documents,storage.resolve("agent-workspace").resolve(q.workspaceId()),"human");
        }
        @Override public void close() throws Exception {
            grant.close();server.close();jobs.shutdownNow();
            edt(()->{closing=true;interaction.close();pane.disposeSourceView();documents.close();store.legacyBuffers().clear();return null;});
            assertFalse(Files.exists(server.descriptor()));assertFalse(Files.exists(server.credential()));
        }
    }
    static Map<String,Object> params(Map<String,Object> context){
        var p=new LinkedHashMap<String,Object>();
        for(String k:List.of("schemaVersion","instanceId","windowId","projectId","paneId","documentId","documentRevision","contextRevision","caretOffset","selectionStart","selectionEnd"))p.put(k,context.get(k));
        return p;
    }
    static String request(String method,Map<String,Object> p){return MiniJson.stringify(Map.of("jsonrpc","2.0","id","test","method",method,"params",p));}
    static Map<String,Object> exchange(WorkspaceBridgeClient c,String method,Map<String,Object> p) throws Exception {
        return MiniJson.asObject(MiniJson.parseStrict(c.exchange(request(method,p))));
    }
    static Map<String,Object> result(Map<String,Object> response){assertFalse(response.containsKey("error"),response::toString);return MiniJson.asObject(response.get("result"));}
    static String code(Map<String,Object> response){return (String)MiniJson.asObject(MiniJson.asObject(response.get("error")).get("data")).get("code");}
    static Map<String,Object> context(WorkspaceBridgeClient c) throws Exception {return MiniJson.asObject(MiniJson.asObject(MiniJson.parseStrict(c.exchange(c.navigationContextRequest()))).get("result"));}
    static Map<String,Object> range(Map<String,Object> ctx,int a,int b){var p=params(ctx);p.put("start",(long)a);p.put("end",(long)b);return p;}

    @Test void authenticatedOpenReadAndReviewedEditUsesActualCanonicalBufferAndAtomicUndo() throws Exception {
        try(var f=new Fixture(temporary);var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            var initial=context(client);assertTrue(((List<?>)initial.get("capabilities")).contains(EditorControl.SCOPE));
            var p=params(initial);p.put("path","Two.java");
            var opened=result(exchange(client,"editor/file/open",p));assertEquals("applied",opened.get("outcome"));
            edt(()->{assertEquals("Two.java",f.pane.getBuffer().getFile().getName());assertEquals(2,f.store.legacyBuffers().size());return null;});
            var read=params(context(client));read.put("offset",0L);read.put("limit",2048L);
            var source=result(exchange(client,"editor/source/read",read));String original=(String)source.get("content");assertTrue(original.contains("😀"));
            var edit=range(context(client),0,original.length());String updated=original.replace("class Two", "class RenamedTwo").replace("value =", "greeting =");
            edit.put("text",updated);edit.put("requestId","change-one");
            var proposed=result(exchange(client,"editor/edits/propose",edit));String id=(String)proposed.get("proposalId");
            assertEquals(true,proposed.get("reviewRequired"));assertEquals(id,f.reviewed);
            edt(()->{assertEquals(original,f.pane.getTextArea().getText());assertFalse(f.pane.getBuffer().isModified());return null;});
            var preview=f.human().preview(id,0);assertFalse(preview.hunks().isEmpty());assertEquals(updated,preview.proposed());
            var applied=f.human().apply(id,((Number)preview.proposal().get("revision")).intValue());assertEquals("APPLIED",applied.get("status"));
            edt(()->{var b=f.pane.getBuffer();assertEquals(updated,b.getContent());assertTrue(b.isModified());
                b.getUndoManager().undo();assertEquals(original,b.getContent());b.getUndoManager().redo();assertEquals(updated,b.getContent());return null;});
            assertEquals(original,Files.readString(f.root.resolve("Two.java")));
        }
    }
    @Test void staleRevisionAndHumanEditsNeverBecomeAnImplicitOverwrite() throws Exception {
        try(var f=new Fixture(temporary);var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            var old=context(client);edt(()->{f.pane.getBuffer().getDocument().insertString(0,"human ",null);return null;});
            var edit=range(old,0,0);edit.put("text","agent ");edit.put("requestId","stale");
            assertEquals("STALE_REVISION",code(exchange(client,"editor/edits/propose",edit)));
            var current=context(client);edit=range(current,0,0);edit.put("text","agent ");edit.put("requestId","valid");
            String id=(String)result(exchange(client,"editor/edits/propose",edit)).get("proposalId");
            edt(()->{f.pane.getBuffer().getDocument().insertString(0,"newer ",null);return null;});
            String human=edt(()->f.pane.getBuffer().getContent());var preview=f.human().preview(id,0);
            assertThrows(WorkspaceApiException.class,()->f.human().apply(id,((Number)preview.proposal().get("revision")).intValue()));
            assertEquals(human,edt(()->f.pane.getBuffer().getContent()));
        }
    }
    @Test void targetPathsAndIdentitiesAndUnimplementedCapabilitiesFailClosed() throws Exception {
        try(var f=new Fixture(temporary);var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            for(String name:List.of("../outside.java","/absolute","missing.java","private.env")){
                var p=params(context(client));p.put("path",name);assertTrue(exchange(client,"editor/file/open",p).containsKey("error"));
            }
            var wrong=range(context(client),0,1);wrong.put("instanceId",UUID.randomUUID().toString());
            assertEquals("UNAUTHORIZED",code(exchange(client,"editor/agent/activity",wrong)));
            for(String method:List.of("editor/edits/apply","proposals/apply","terminal/execute","editor/capture","files/save"))
                assertEquals("UNSUPPORTED",code(exchange(client,method,Map.of())));
            edt(()->{assertEquals(1,f.store.legacyBuffers().size());assertFalse(f.pane.getBuffer().isModified());return null;});
        }
    }
    @Test void activityHasSeparateCursorWithoutSourceCaretOrHistoryChanges() throws Exception {
        try(var f=new Fixture(temporary);var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            var before=edt(()->List.of(f.pane.getBuffer().getContent(),f.pane.getBuffer().documentRevision(),f.pane.getTextArea().getCaret(),f.pane.getTextArea().getCaretColor()));
            result(exchange(client,"editor/agent/activity",range(context(client),0,5)));
            edt(()->{assertEquals(before,List.of(f.pane.getBuffer().getContent(),f.pane.getBuffer().documentRevision(),f.pane.getTextArea().getCaret(),f.pane.getTextArea().getCaretColor()));
                assertEquals("Agent preparing change",f.pane.getTextArea().getClientProperty("shed.agentActivity"));assertFalse(f.pane.getBuffer().getUndoManager().canUndo());return null;});
        }
    }
    @Test void utf16BoundariesPayloadBoundsAndClosedGrantsRejectBeforeEditing() throws Exception {
        try(var f=new Fixture(temporary);var client=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            edt(()->{f.pane.getBuffer().setContent("a😀éक्षz");return null;});
            for(int start:List.of(2,4,6,7)){
                var edit=range(context(client),start,start);edit.put("text","x");edit.put("requestId","invalid-"+start);
                assertEquals("INVALID_OFFSET",code(exchange(client,"editor/edits/propose",edit)));
            }
            var large=range(context(client),0,0);large.put("text","x".repeat(EditorControl.MAX_REPLACEMENT+1));large.put("requestId","oversize");
            assertEquals("INVALID_REQUEST",code(exchange(client,"editor/edits/propose",large)));
            assertThrows(WorkspaceApiException.class,()->client.exchange("x".repeat(32769)));
            var last=context(client);f.grant.close();assertTrue(exchange(client,"editor/agent/activity",range(last,0,0)).containsKey("error"));
        }
    }
    @Test void navigationOnlyGrantCannotBeUpgradedByControlClientOrMethodName() throws Exception {
        try(var f=new Fixture(temporary)){
            var old=EditorMetadata.navigation(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),f,f.interaction);
            try(var server=WorkspaceBridgeServer.metadata(f.application,old,temporary.resolve("old"),List.of(f.root))){
                assertThrows(WorkspaceApiException.class,()->WorkspaceBridgeClient.control(server.descriptor(),server.credential()));
                try(var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())){
                    assertEquals("UNSUPPORTED",code(exchange(client,"editor/source/read",Map.of())));
                    assertEquals("UNSUPPORTED",code(exchange(client,"editor/edits/propose",Map.of())));
                }
            }
        }
    }
}
