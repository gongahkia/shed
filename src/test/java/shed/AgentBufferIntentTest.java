package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import javax.swing.*;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** In-process authenticated fixtures and owned Swing paints; no shell, server or desktop launch. */
class AgentBufferIntentTest {
    @TempDir Path temporary;
    static AgentBufferIntent event(String kind,String status,int focus,AgentEditPresentation.Range...ranges){return new AgentBufferIntent(kind,status,"Local agent",List.of(ranges),focus);}
    static AgentEditPresentation.Range range(int a,int b){return new AgentEditPresentation.Range(a,b);}
    static Object wire(Object value){return MiniJson.parseStrict(MiniJson.stringify(value));}
    static Map<String,Object> params(Map<String,Object> context,AgentBufferIntent event){var p=EditorControlTest.params(context);p.put("intent",wire(event.json()));return p;}
    @Test void contractBoundsKindsAndOutcomesAreExplicit(){
        for(String kind:AgentBufferIntent.KINDS){var e=event(kind,kind.equals("verify")?"checking":"active",0,kind.equals("move")?new AgentEditPresentation.Range[]{range(0,2),range(4,4)}:new AgentEditPresentation.Range[]{range(0,2)});assertEquals(e,AgentBufferIntent.parse(wire(e.json())));assertFalse(e.label(0).isBlank());}
        assertThrows(WorkspaceApiException.class,()->event("execute","active",0,range(0,1)));
        assertThrows(WorkspaceApiException.class,()->event("search","passed",0,range(0,1)));
        assertThrows(WorkspaceApiException.class,()->event("verify","success",0,range(0,1)));
        assertThrows(WorkspaceApiException.class,()->event("move","active",0,range(0,1)));
        assertThrows(WorkspaceApiException.class,()->event("target","active",1,range(0,1)));
        assertThrows(WorkspaceApiException.class,()->new AgentBufferIntent("target","active","bad\nlabel",List.of(range(0,1)),0));
        assertThrows(WorkspaceApiException.class,()->new AgentBufferIntent("search","active","Agent",Collections.nCopies(33,range(0,1)),0));
        assertThrows(WorkspaceApiException.class,()->AgentBufferIntent.parse(Map.of()));
        var extra=new LinkedHashMap<>(event("target","active",0,range(0,1)).json());extra.put("text","forbidden");assertThrows(WorkspaceApiException.class,()->AgentBufferIntent.parse(extra));
        assertEquals("warning",event("move","active",0,range(0,1),range(3,3)).role(0));assertEquals("success",event("move","active",0,range(0,1),range(3,3)).role(1));
        assertTrue(event("verify","passed",0,range(0,1)).label(0).contains("reports check passed"));
        assertEquals("warning",event("move","source",0,range(0,2)).role(0));assertEquals("success",event("move","destination",0,range(4,4)).role(0));
    }
    @Test void offsetsRemainUtf16AndMustRespectGraphemeBoundaries(){
        String source="a😀e\u0301क्षz";
        for(int offset:List.of(2,4,6,7,source.length()+1))assertThrows(WorkspaceApiException.class,()->event("target","active",0,range(offset,offset)).validateSource(source));
        event("navigate","active",0,range(source.length(),source.length())).validateSource(source);
        assertThrows(WorkspaceApiException.class,()->event("target","active",0,range(0,1)).validateSource("a".repeat(65537)));
    }
    @Test void authenticatedIntentsPreserveDocumentCaretDirtyAndUndo()throws Exception{
        var seen=new ArrayList<AgentBufferIntent>();
        try(var f=new EditorControlTest.Fixture(temporary){public void intent(AgentBufferIntent event){assertTrue(SwingUtilities.isEventDispatchThread());seen.add(event);pane.agentPresentation().showIntent(event);}};
            var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            var before=EditorControlTest.edt(()->List.of(f.pane.getBuffer().getContent(),f.pane.getBuffer().documentRevision(),f.pane.getBuffer().isModified(),f.pane.getBuffer().getUndoManager().canUndo(),f.pane.getTextArea().getCaretPosition()));
            for(String kind:AgentBufferIntent.KINDS){var e=event(kind,kind.equals("verify")?"passed":"active",0,kind.equals("move")?new AgentEditPresentation.Range[]{range(0,5),range(12,12)}:new AgentEditPresentation.Range[]{range(0,5)});var r=EditorControlTest.result(EditorControlTest.exchange(c,"editor/agent/intent",params(EditorControlTest.context(c),e)));assertEquals("shown",r.get("outcome"));assertEquals(e,AgentBufferIntent.parse(r.get("intent")));}
            assertEquals(9,seen.size());assertEquals(before,EditorControlTest.edt(()->List.of(f.pane.getBuffer().getContent(),f.pane.getBuffer().documentRevision(),f.pane.getBuffer().isModified(),f.pane.getBuffer().getUndoManager().canUndo(),f.pane.getTextArea().getCaretPosition())));
            assertEquals(before.getFirst(),Files.readString(f.root.resolve("One.java")));
        }
    }
    @Test void staleContextInvalidRangesAndUnauthorizedIdentitiesNeverShowIntent()throws Exception{
        var seen=new ArrayList<AgentBufferIntent>();
        try(var f=new EditorControlTest.Fixture(temporary){public void intent(AgentBufferIntent e){seen.add(e);}};
            var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            var p=params(EditorControlTest.context(c),event("target","active",0,range(0,5)));
            EditorControlTest.edt(()->{f.pane.getBuffer().getDocument().insertString(0,"human ",null);return null;});
            assertEquals("STALE_REVISION",EditorControlTest.code(EditorControlTest.exchange(c,"editor/agent/intent",p)));
            p=params(EditorControlTest.context(c),event("target","active",0,range(0,500)));assertEquals("INVALID_OFFSET",EditorControlTest.code(EditorControlTest.exchange(c,"editor/agent/intent",p)));
            p=params(EditorControlTest.context(c),event("target","active",0,range(0,1)));p.put("instanceId",UUID.randomUUID().toString());assertEquals("UNAUTHORIZED",EditorControlTest.code(EditorControlTest.exchange(c,"editor/agent/intent",p)));
            p=params(EditorControlTest.context(c),event("target","active",0,range(0,1)));p.put("text","no source writes");assertEquals("INVALID_REQUEST",EditorControlTest.code(EditorControlTest.exchange(c,"editor/agent/intent",p)));assertTrue(seen.isEmpty());
            p=params(EditorControlTest.context(c),event("target","active",0,range(0,1)));EditorControlTest.edt(()->{f.pane.getTextArea().setCaretPosition(1);return null;});assertEquals("CONTEXT_CHANGED",EditorControlTest.code(EditorControlTest.exchange(c,"editor/agent/intent",p)));assertTrue(seen.isEmpty());
        }
    }
    static int cli(EditorControlTest.Fixture f,String...args){var command=new ArrayList<>(List.of("ctl","--connect",f.server.descriptor().toString()));command.addAll(List.of(args));return WorkspaceCli.run(command.toArray(String[]::new),InputStream.nullInputStream(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream());}
    @Test void cliIsVendorNeutralSupportsMultipleMatchesAndExplicitRevisionChecks()throws Exception{
        var seen=new ArrayList<AgentBufferIntent>();
        try(var f=new EditorControlTest.Fixture(temporary){public void intent(AgentBufferIntent e){seen.add(e);}}){
            for(String label:List.of("Codex","Claude Code","OpenCode","Any local script"))assertEquals(0,cli(f,"intent","Two.java","--kind","search","--ranges","0:5,6:9","--focus","1","--agent",label));
            assertEquals(List.of("Codex","Claude Code","OpenCode","Any local script"),seen.stream().map(AgentBufferIntent::agent).toList());assertEquals(1,seen.getFirst().focus());
            assertNotEquals(0,cli(f,"intent","--kind","target","--start","0","--end","5","--document","wrong","--revision","0"));assertEquals(4,seen.size());
            String id=EditorControlTest.edt(()->f.capture().documentId());
            assertEquals(0,cli(f,"intent","--kind","verify","--status","failed","--start","0","--end","5","--document",id,"--revision",Long.toString(EditorControlTest.edt(()->f.pane.getBuffer().documentRevision()))));
            assertEquals(0,cli(f,"intent","Two.java","--kind","move","--status","source","--start","0","--end","5"));
            assertEquals(0,cli(f,"intent","One.java","--kind","move","--status","destination","--start","0","--end","0"));assertEquals("One.java",EditorControlTest.edt(()->f.pane.getBuffer().getFile().getName()));
        }
    }
    @Test void malformedCliIntentsRejectBeforeFileOpening()throws Exception{
        try(var f=new EditorControlTest.Fixture(temporary)){
            for(String[] args:List.of(new String[]{"--kind","search","--ranges","0:1,2:3","--start","0"},new String[]{"--kind","unknown","--ranges","0:1"},new String[]{"--kind","target","--ranges","-1:5"},new String[]{"--kind","target","--ranges","1:0"},new String[]{"--kind","target","--ranges","0:1","--focus","4"},new String[]{"--kind","target","--ranges","0:1","--agent","bad\nlabel"},new String[]{"--kind","target","--ranges","0:1","--status","passed"},new String[]{"--kind","target","--ranges","0:1","--document","missing-revision"})){
                var all=new ArrayList<>(List.of("intent","Two.java"));all.addAll(List.of(args));assertNotEquals(0,cli(f,all.toArray(String[]::new)));assertEquals("One.java",EditorControlTest.edt(()->f.pane.getBuffer().getFile().getName()));
            }
            assertNotEquals(0,cli(f,"intent","../outside.java","--kind","target","--ranges","0:1"));
        }
    }
    @Test void disconnectedRevokedAndNavigationOnlyClientsCannotPresent()throws Exception{
        var seen=new ArrayList<AgentBufferIntent>();
        try(var f=new EditorControlTest.Fixture(temporary){public void intent(AgentBufferIntent e){seen.add(e);}};
            var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            var p=params(EditorControlTest.context(c),event("target","active",0,range(0,1)));
            assertThrows(WorkspaceApiException.class,()->f.grant.control.dispatch("editor/agent/intent",p,()->false));assertTrue(seen.isEmpty());
            var old=EditorMetadata.navigation(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),f,f.interaction);
            try(var server=WorkspaceBridgeServer.metadata(f.application,old,temporary.resolve("navigation"),List.of(f.root));var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())){
                assertEquals("UNSUPPORTED",EditorControlTest.code(EditorControlTest.exchange(client,"editor/agent/intent",p)));
            }
            f.grant.close();assertTrue(EditorControlTest.exchange(c,"editor/agent/intent",p).containsKey("error"));assertTrue(seen.isEmpty());
        }
    }
    @Test void outcomesUseDistinctColorsAndReducedMotionStillShowsMeaningAtEof()throws Exception{
        EditorControlTest.edt(()->{
            var area=new JTextArea("alpha beta\n");area.setFont(new Font("Monospaced",0,16));area.setSize(700,250);area.putClientProperty("shed.caretAnimation",false);
            try(var p=new AgentEditPresentation(area)){
                var colors=new HashSet<Color>();for(String status:List.of("checking","passed","failed")){p.showIntent(event("verify",status,0,range(0,5)));colors.add(p.agentColor());assertTrue(area.getClientProperty("shed.agentActivity").toString().contains("reports check "+status));}
                assertEquals(3,colors.size());
                p.showIntent(event("navigate","active",0,range(area.getDocument().getLength(),area.getDocument().getLength())));
                var baseline=new java.awt.image.BufferedImage(700,250,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=baseline.createGraphics();area.paint(g);g.dispose();
                assertEquals(1,area.getHighlighter().getHighlights().length);assertEquals("navigate",area.getClientProperty("shed.agentIntent"));
                area.setDocument(new javax.swing.text.PlainDocument());assertNull(area.getClientProperty("shed.agentIntent"));assertEquals(0,area.getHighlighter().getHighlights().length);
            }return null;
        });
    }
    @Test void presentationScrollsAndPaintsWithoutMovingHumanOrAccumulatingListeners()throws Exception{
        EditorControlTest.edt(()->{
            String source="source line\n".repeat(100);var area=new JTextArea(source){@Override public boolean isShowing(){return true;}};area.setFont(new Font("Monospaced",0,16));area.setSize(700,2200);area.select(2,6);var undo=new UndoManager();area.getDocument().addUndoableEditListener(undo);
            var scroll=new JScrollPane(area);scroll.setSize(700,250);scroll.doLayout();area.setSize(680,2200);int keys=area.getKeyListeners().length,mouse=scroll.getMouseWheelListeners().length;var doc=(javax.swing.text.AbstractDocument)area.getDocument();int listeners=doc.getDocumentListeners().length;
            try(var p=new AgentEditPresentation(area)){
                var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);var field=AgentEditPresentation.class.getDeclaredField("timer");field.setAccessible(true);
                for(int cycle=0;cycle<24;cycle++){
                    p.showIntent(event("search","active",1,range(0,6),range(900,906)));for(int t=0;t<6;t++)tick.invoke(p);assertTrue(scroll.getViewport().getViewPosition().y>0);assertEquals(2,area.getHighlighter().getHighlights().length);
                    var img=new java.awt.image.BufferedImage(700,250,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=img.createGraphics();scroll.paint(g);g.dispose();assertEquals(source,area.getText());assertEquals(2,area.getSelectionStart());assertEquals(6,area.getSelectionEnd());assertFalse(undo.canUndo());
                    for(int t=6;t<120;t++)tick.invoke(p);assertNull(area.getClientProperty("shed.agentIntent"));assertFalse(((javax.swing.Timer)field.get(p)).isRunning());assertEquals(keys,area.getKeyListeners().length);assertEquals(mouse,scroll.getMouseWheelListeners().length);
                }
                area.putClientProperty("shed.caretAnimation",false);p.showIntent(event("conflict","active",0,range(0,6)));assertFalse(((javax.swing.Timer)field.get(p)).isRunning());
                for(var listener:area.getKeyListeners())listener.keyPressed(new KeyEvent(area,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_RIGHT,KeyEvent.CHAR_UNDEFINED));assertNull(area.getClientProperty("shed.agentIntent"));
                p.showIntent(event("target","active",0,range(0,6)));area.append("human");assertNull(area.getClientProperty("shed.agentIntent"));
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            assertEquals(listeners,doc.getDocumentListeners().length);assertEquals(keys,area.getKeyListeners().length);assertEquals(mouse,scroll.getMouseWheelListeners().length);return null;
        });
    }
}
