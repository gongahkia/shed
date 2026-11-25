package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShedControlCliTest {
    @TempDir Path temporary;
    record Reply(int code,Map<String,Object> body) { }
    private Reply call(EditorControlTest.Fixture fixture,String...arguments){
        var args=new ArrayList<>(List.of("ctl","--connect",fixture.server.descriptor().toString()));args.addAll(List.of(arguments));
        var out=new ByteArrayOutputStream();
        int code=WorkspaceCli.run(args.toArray(String[]::new),InputStream.nullInputStream(),out,OutputStream.nullOutputStream());
        return new Reply(code,MiniJson.asObject(MiniJson.parseStrict(out.toString(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Test void ordinaryCommandsUseExplicitAttachmentAndCaptureCurrentContext() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary)){
            var first=call(f,"state");assertEquals(0,first.code(),first.body()::toString);
            var opened=call(f,"open","Two.java");assertEquals(0,opened.code(),opened.body()::toString);
            var read=call(f,"read");assertEquals(0,read.code());var state=MiniJson.asObject(read.body().get("result"));
            assertTrue(((String)state.get("content")).contains("class Two"));
            Path payload=temporary.resolve("chunk.txt");Files.writeString(payload,"class Replacement {}\n");
            var proposal=call(f,"replace","--document",(String)state.get("documentId"),"--revision",state.get("documentRevision").toString(),"--text-file",payload.toString());
            assertEquals(0,proposal.code(),proposal.body()::toString);assertEquals(true,MiniJson.asObject(proposal.body().get("result")).get("reviewRequired"));
            assertEquals(64,call(f,"exec","arbitrary").code());assertEquals(64,call(f,"state","--start","1").code());
        }
    }
    @Test void staleOrMissingIntentAndOversizedPayloadDoNotPropose() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary)){
            var state=MiniJson.asObject(call(f,"state").body().get("result"));
            Path payload=temporary.resolve("chunk.txt");Files.writeString(payload,"x".repeat(40000));
            assertNotEquals(0,call(f,"replace","--text-file",payload.toString()).code());
            assertNotEquals(0,call(f,"replace","--document",(String)state.get("documentId"),"--revision",state.get("documentRevision").toString(),"--text-file",payload.toString()).code());
            assertNull(f.reviewed);
        }
    }
    @Test void visibleReadOpensActualFileReturnsExactChunkAndReportsReadingWithoutEdits()throws Exception {
        var shown=new ArrayList<Object>();
        try(var f=new EditorControlTest.Fixture(temporary){
            public void reading(int start,int end,String agent){assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());shown.addAll(List.of(start,end,agent));pane.agentPresentation().showReading(start,end,agent);}
        }){
            String original=Files.readString(f.root.resolve("Two.java"));
            var reply=call(f,"read","Two.java","--offset","0","--limit","5","--agent","Local agent");
            assertEquals(0,reply.code(),reply.body()::toString);var result=MiniJson.asObject(reply.body().get("result"));
            assertEquals(original.substring(0,5),result.get("content"));assertEquals(List.of(0,5,"Local agent"),shown);
            EditorControlTest.edt(()->{assertEquals("Two.java",f.pane.getBuffer().getFile().getName());assertEquals(original,f.pane.getTextArea().getText());assertFalse(f.pane.getBuffer().isModified());assertFalse(f.pane.getBuffer().getUndoManager().canUndo());assertEquals("Agent reading",f.pane.getTextArea().getClientProperty("shed.agentActivity"));return null;});
            assertEquals(original,Files.readString(f.root.resolve("Two.java")));
            shown.clear();assertEquals(0,call(f,"read").code());assertTrue(shown.isEmpty(),"Plain reads must remain presentation-free");
        }
    }
    @Test void invalidVisibleReadOptionsFailBeforeOpeningAndUnsafePathsRemainRejected()throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary)){
            String document=(String)MiniJson.asObject(call(f,"state").body().get("result")).get("documentId");
            for(var args:List.of(new String[]{"read","Two.java","--limit","0"},new String[]{"read","Two.java","--offset","-1"},new String[]{"read","Two.java","--end","5"},new String[]{"read","Two.java","--agent","bad\nlabel"}))assertEquals(64,call(f,args).code());
            assertEquals(document,MiniJson.asObject(call(f,"state").body().get("result")).get("documentId"));
            assertNotEquals(0,call(f,"read","../outside.java").code());
            EditorControlTest.edt(()->{assertNull(f.pane.getTextArea().getClientProperty("shed.agentActivity"));return null;});
        }
    }
    @Test void explicitReadCanOpenFromUntitledWithoutExposingOrChangingItsDraft()throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary){
            public EditorMetadata.Context capture(){return EditorMetadata.captureForOpen(pane,documents,roots.active(),roots.all(),roots.generation());}
            public EditorControl.WatchRoot watchRoot(){return new EditorControl.WatchRoot(roots.active(),roots.generation());}
        }){
            var draft=new FileBuffer((String)null);draft.setContent("private unsaved draft",true);
            EditorControlTest.edt(()->{f.store.legacyBuffers().add(draft);f.pane.setBuffer(draft);f.pane.getTextArea().setDocument(draft.getDocument());return null;});
            assertNotEquals(0,call(f,"read").code(),"Plain observation must not expose an untitled draft");
            assertNotEquals(0,call(f,"read","../outside.java").code());
            EditorControlTest.edt(()->{assertSame(draft,f.pane.getBuffer());return null;});
            var reply=call(f,"read","Two.java","--limit","20");assertEquals(0,reply.code(),reply.body()::toString);
            var body=MiniJson.asObject(reply.body().get("result"));assertTrue(((String)body.get("content")).startsWith("class Two"));assertFalse(body.toString().contains("private unsaved draft"));
            EditorControlTest.edt(()->{assertEquals("private unsaved draft",draft.getContent());assertTrue(draft.isModified());assertTrue(f.store.legacyBuffers().contains(draft));return null;});
        }
    }
    @Test void initialOpenRejectsAHumanEditBeforeInstallation()throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary){
            public EditorMetadata.Context capture(){return EditorMetadata.captureForOpen(pane,documents,roots.active(),roots.all(),roots.generation());}
            public EditorControl.WatchRoot watchRoot(){return new EditorControl.WatchRoot(roots.active(),roots.generation());}
            public ProjectFileOpen.Request open(ProjectBoundFileAcquisition.RootPin root,String path,EditorNavigation.State expected,java.util.function.BooleanSupplier current,java.util.function.Consumer<ProjectDocumentInstall.Result> completed){
                try{pane.getBuffer().getDocument().insertString(0,"human edit",null);pane.getBuffer().setModified(true);}catch(javax.swing.text.BadLocationException impossible){throw new AssertionError(impossible);}
                return super.open(root,path,expected,current,completed);
            }
        }){
            var draft=new FileBuffer((String)null);
            EditorControlTest.edt(()->{f.store.legacyBuffers().add(draft);f.pane.setBuffer(draft);f.pane.getTextArea().setDocument(draft.getDocument());return null;});
            var reply=call(f,"read","Two.java");assertNotEquals(0,reply.code());
            EditorControlTest.edt(()->{assertSame(draft,f.pane.getBuffer());assertEquals("human edit",draft.getContent());assertTrue(draft.isModified());assertNull(f.pane.getTextArea().getClientProperty("shed.agentActivity"));return null;});
        }
    }
    @Test void visibleReadCannotRetargetWhenTheHumanSwitchesAfterOpening()throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary){
            public ProjectFileOpen.Request open(ProjectBoundFileAcquisition.RootPin root,String path,EditorNavigation.State expected,java.util.function.BooleanSupplier current,java.util.function.Consumer<ProjectDocumentInstall.Result> completed){
                var original=pane.getBuffer();
                return super.open(root,path,expected,current,result->{
                    if(result.outcome()==ProjectDocumentInstall.Outcome.APPLIED){pane.setBuffer(original);pane.getTextArea().setDocument(original.getDocument());}
                    completed.accept(result);
                });
            }
        }){
            var reply=call(f,"read","Two.java");assertNotEquals(0,reply.code());assertFalse(reply.body().containsKey("result"));
            EditorControlTest.edt(()->{assertEquals("One.java",f.pane.getBuffer().getFile().getName());assertNull(f.pane.getTextArea().getClientProperty("shed.agentActivity"));return null;});
        }
    }
    @Test void watchingIsVendorNeutralAndAgentNamesAreOnlyLabels() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary){
            public EditorControl.WatchRoot watchRoot(){return new EditorControl.WatchRoot(roots.active(),roots.generation());}
            public Map<String,Object> watch(String action,String label){return Map.of("kind","watch","agent",label);}
        }){
            var unnamed=call(f,"watch");assertEquals(0,unnamed.code(),unnamed.body()::toString);
            var observation=MiniJson.asObject(MiniJson.asObject(unnamed.body().get("result")).get("observation"));assertEquals("Agent",observation.get("agent"));
            for(String label:List.of("Codex","Claude Code","OpenCode","Local agent","Custom writer")){
                var reply=call(f,"watch","--agent",label);assertEquals(0,reply.code(),reply.body()::toString);
                observation=MiniJson.asObject(MiniJson.asObject(reply.body().get("result")).get("observation"));assertEquals(label,observation.get("agent"));
            }
        }
    }

}
