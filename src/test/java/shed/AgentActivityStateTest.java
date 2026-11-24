package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentActivityStateTest {
    @TempDir Path temporary;
    @Test void progressIsBoundedCancellationIsStickyAndNamesAreDisplayOnly(){
        var a=new AgentActivityState();assertEquals("idle",a.snapshot().get("phase"));
        assertEquals("Codex · thinking · 1/3",a.update("one","Codex","thinking",1,3).label());
        assertThrows(WorkspaceApiException.class,()->a.update("two","Other","reading",0,0));
        a.cancel("one");assertThrows(WorkspaceApiException.class,()->a.update("one","Codex","proposing",2,3));
        assertEquals("Other",a.update("two","Other","complete",1,1).agent());a.close();assertEquals("idle",a.snapshot().get("phase"));
        assertThrows(WorkspaceApiException.class,()->a.update("bad","bad\nlabel","thinking",0,0));
        assertThrows(WorkspaceApiException.class,()->a.update("bad","Agent","thinking",4,3));
    }
    @Test void hiddenDecorationNeverRequestsTextGeometry() throws Exception {
        EditorControlTest.edt(()->{
            var area=new javax.swing.JTextArea("class Hidden {}"){ @Override public java.awt.geom.Rectangle2D modelToView2D(int offset){throw new AssertionError("Hidden view geometry must not be requested");} };
            try(var presentation=new AgentEditPresentation(area)){presentation.showPending(0,5);presentation.setAgentName("Codex");presentation.clear();}
            return null;
        });
    }
    @Test void authenticatedProgressAndCancellationDoNotEditTheSource() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary);var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            String original=EditorControlTest.edt(()->f.pane.getBuffer().getContent());
            var p=EditorControlTest.range(EditorControlTest.context(c),0,1);p.put("sessionId","session");p.put("agent","Codex");p.put("phase","thinking");p.put("completed",1L);p.put("total",3L);
            var reply=EditorControlTest.result(EditorControlTest.exchange(c,"editor/agent/progress",p));assertEquals("activity",reply.get("outcome"));
            var cancel=EditorControlTest.params(EditorControlTest.context(c));cancel.put("sessionId","session");
            EditorControlTest.result(EditorControlTest.exchange(c,"editor/agent/cancel",cancel));
            assertEquals("CANCELLED",EditorControlTest.code(EditorControlTest.exchange(c,"editor/agent/progress",p)));
            var edit=EditorControlTest.range(EditorControlTest.context(c),0,0);edit.put("sessionId","session");edit.put("text","agent ");edit.put("requestId","cancelled");
            assertEquals("CANCELLED",EditorControlTest.code(EditorControlTest.exchange(c,"editor/edits/propose",edit)));assertNull(f.reviewed);
            assertEquals(original,EditorControlTest.edt(()->f.pane.getBuffer().getContent()));
            assertFalse(EditorControlTest.edt(()->f.pane.getBuffer().isModified()));
        }
    }
}
