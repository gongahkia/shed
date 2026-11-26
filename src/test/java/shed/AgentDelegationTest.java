package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure leases, real bridge, and in-memory Swing ownership; no child processes or desktop. */
class AgentDelegationTest {
    @TempDir Path temporary;
    @Test void explicitApprovalExpiryAndHandbackNeverReenableLegacyAccess() {
        var time=new AtomicLong(100);var s=new AgentDelegation(time::get);
        assertTrue(s.allowed(null));s.requireApproval();assertFalse(s.allowed(null));
        assertTrue(s.request("one","Any CLI","Fix a task"));assertFalse(s.allowed("one"));
        assertFalse(s.request("one","Any CLI","Fix a task"));
        assertThrows(WorkspaceApiException.class,()->s.request("one","Other","Changed"));
        assertThrows(WorkspaceApiException.class,()->s.request("two","Other","Task"));
        s.approve("one");assertFalse(s.allowed("one"));s.activate("one");assertTrue(s.allowed("one"));assertFalse(s.allowed("other"));assertFalse(s.allowed(null));
        time.addAndGet(480_001);assertFalse(s.allowed("one"));assertEquals("expired",s.status("one").get("state"));
        s.request("two","Any CLI","Next task");s.approve("two");s.activate("two");s.finish("two","Tests passed (agent-reported)");
        assertFalse(s.allowed("two"));assertFalse(s.allowed(null));assertEquals("completed",s.status("two").get("state"));assertThrows(WorkspaceApiException.class,()->s.request("one","Any CLI","Fix a task"));
    }
    @Test void rejectionRevocationAndMalformedRequestsFailClosed() {
        var time=new AtomicLong();var s=new AgentDelegation(time::get);
        assertThrows(WorkspaceApiException.class,()->s.request("../bad","Agent","Task"));
        assertThrows(WorkspaceApiException.class,()->s.request("ok","\u001b[bad","Task"));
        s.request("one","Agent","Task");s.revoke("rejected");assertFalse(s.allowed("one"));
        s.request("two","Agent","Task");time.addAndGet(60_000);assertEquals("expired",s.state());
        assertThrows(WorkspaceApiException.class,()->s.approve("two"));
        s.request("three","Agent","Task");s.approve("three");time.addAndGet(45_001);assertFalse(s.allowed("three"));assertEquals("expired",s.state());
    }
    @Test void ownershipRestoresMenuGlassEditableSourceSelectionAndUndo() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary)) {
            EditorControlTest.edt(()->{
                var root=new JRootPane();root.getContentPane().add(f.pane.getScrollPane());var bar=new JMenuBar();var enabled=new JMenu("File");var disabled=new JMenu("Disabled");disabled.setEnabled(false);bar.add(enabled);bar.add(disabled);root.setJMenuBar(bar);
                var old=root.getGlassPane();var area=f.pane.getTextArea();int listeners=area.getPropertyChangeListeners("editable").length;area.select(2,6);var before=f.pane.getBuffer().getContent();long revision=f.pane.getBuffer().documentRevision();
                try(var owner=new AgentEditorOwnership(root,()->List.of(f.pane),"Agent",()->{})) {
                    assertFalse(area.isEditable());assertFalse(enabled.isEnabled());assertTrue(root.getGlassPane().isVisible());owner.refresh();area.setEditable(true);assertFalse(area.isEditable());
                    assertEquals(before,area.getText());assertEquals(revision,f.pane.getBuffer().documentRevision());assertFalse(f.pane.getBuffer().isModified());assertFalse(f.pane.getBuffer().getUndoManager().canUndo());
                }
                assertEquals(listeners,area.getPropertyChangeListeners("editable").length);
                for(int i=0;i<24;i++)try(var owner=new AgentEditorOwnership(root,()->List.of(f.pane),"Agent",()->{})){owner.refresh();}
                assertEquals(listeners,area.getPropertyChangeListeners("editable").length);
                assertSame(old,root.getGlassPane());assertTrue(enabled.isEnabled());assertFalse(disabled.isEnabled());assertTrue(area.isEditable());assertEquals(2,area.getSelectionStart());assertEquals(6,area.getSelectionEnd());return null;
            });
        }
    }
    @Test void disposingAPaneDuringOwnershipCannotReenableItsRetiredSourceView() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary)) {
            EditorControlTest.edt(()->{
                var root=new JRootPane();root.getContentPane().add(f.pane.getScrollPane());var area=f.pane.getTextArea();int listeners=area.getPropertyChangeListeners("editable").length;
                try(var owner=new AgentEditorOwnership(root,()->List.of(f.pane),"Agent",()->{})){f.pane.disposeSourceView();owner.refresh();}
                assertFalse(area.isEditable());assertTrue(f.pane.isDisposed());assertEquals(listeners,area.getPropertyChangeListeners("editable").length);return null;
            });
        }
    }
    @Test void chatFocusAllowsApprovedNavigationOnlyWhileSourceOwnershipIsHeld() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary)) {
            EditorControlTest.edt(()->{
                var root=new JRootPane();var chat=new JPanel();var composer=new JTextArea();chat.add(composer);root.getContentPane().add(chat);var unrelated=new JButton("Other tool");
                var owner=new AgentEditorOwnership(root,()->List.of(f.pane),"Agent",()->{},chat);
                assertTrue(owner.allowsChatNavigationFrom(composer));assertTrue(owner.allowsChatNavigationFrom(root.getGlassPane()));
                assertFalse(owner.allowsChatNavigationFrom(unrelated));assertFalse(owner.allowsChatNavigationFrom(null));assertFalse(f.pane.getTextArea().isEditable());
                owner.close();assertFalse(owner.allowsChatNavigationFrom(composer));assertTrue(f.pane.getTextArea().isEditable());
                try(var legacy=new AgentEditorOwnership(root,()->List.of(f.pane),"Agent",()->{})){assertFalse(legacy.allowsChatNavigationFrom(composer));}
                return null;
            });
        }
    }
    static final class DelegatedFixture extends EditorControlTest.Fixture {
        final AgentDelegation lease=new AgentDelegation();
        volatile boolean revokeOnCapture;
        @Override public EditorMetadata.Context capture(){if(revokeOnCapture)lease.revoke("cancelled");return super.capture();}
        DelegatedFixture(Path p)throws Exception{super(p);lease.requireApproval();}
        public boolean delegationAllowed(String id){return lease!=null&&lease.allowed(id);}
        public Map<String,Object> delegation(String action,String id,String agent,String task){
            switch(action){case "request"->lease.request(id,agent,task);case "status"->{}case "finish"->lease.finish(id,task);default->throw WorkspaceApiException.invalidRequest("action");}return lease.status(id);
        }
    }
    @Test void revocationDuringQueuedContextObservationCannotReturnNewHumanContext() throws Exception {
        try(var f=new DelegatedFixture(temporary);var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())) {
            f.lease.request("one","Agent","Task");f.lease.approve("one");f.lease.activate("one");c.delegation("one");f.revokeOnCapture=true;
            assertEquals("AUTHORIZATION_REVOKED",EditorControlTest.code(MiniJson.asObject(MiniJson.parseStrict(c.exchange(c.navigationContextRequest())))));
        }
    }
    @Test void realAuthenticatedBridgeRequiresLiveSessionIncludingContextAndQueuedAuthorization() throws Exception {
        try(var f=new DelegatedFixture(temporary);var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            assertEquals("AUTHORIZATION_REVOKED",EditorControlTest.code(MiniJson.asObject(MiniJson.parseStrict(c.exchange(c.navigationContextRequest())))));
            var identity=WorkspaceStatusAttachment.Descriptor.read(f.server.descriptor());
            var params=new LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"instanceId",identity.instanceId(),"windowId",identity.windowId(),"projectId",identity.projectId(),"action","request","requestId","agent-one","agent","Codex","task","A bounded task"));
            var reply=EditorControlTest.result(EditorControlTest.exchange(c,"editor/delegation",params));assertEquals("awaiting-approval",MiniJson.asObject(reply.get("observation")).get("state"));
            f.lease.approve("agent-one");f.lease.activate("agent-one");c.delegation("wrong");assertEquals("AUTHORIZATION_REVOKED",EditorControlTest.code(MiniJson.asObject(MiniJson.parseStrict(c.exchange(c.navigationContextRequest())))));
            c.delegation("agent-one");var context=EditorControlTest.context(c);var read=EditorControlTest.params(context);read.put("offset",0L);read.put("limit",2048L);
            assertTrue(EditorControlTest.result(EditorControlTest.exchange(c,"editor/source/read",read)).get("content").toString().contains("class One"));
            var gateParams=new LinkedHashMap<String,Object>();gateParams.put("delegationId","agent-one");var queued=f.grant.control.authorizeDelegation(gateParams,()->true);assertTrue(queued.getAsBoolean());
            f.lease.revoke("cancelled");assertFalse(queued.getAsBoolean());assertEquals("AUTHORIZATION_REVOKED",EditorControlTest.code(EditorControlTest.exchange(c,"editor/source/read",read)));
            assertEquals("class One {\n  int value = 1;\n}\n",Files.readString(f.root.resolve("One.java")));
            params.put("action","status");params.put("agent","");params.put("task","");assertEquals("cancelled",MiniJson.asObject(EditorControlTest.result(EditorControlTest.exchange(c,"editor/delegation",params)).get("observation")).get("state"));
        }
    }
}
