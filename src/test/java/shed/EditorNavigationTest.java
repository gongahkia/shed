package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** actual pane/surface and interaction tracking; no JFrame or shell. */
class EditorNavigationTest {
    @TempDir Path temporary;
    static final class Fixture implements AutoCloseable, EditorNavigation.Host {
        final EditorMetadataTest.Fixture editor;
        final EditorInteraction interaction = new EditorInteraction();
        final EditorMetadata grant;
        boolean eligible = true;
        Runnable beforePrepare = () -> { }, beforeApply = () -> { }, duringApply = () -> { };
        Fixture(Path parent) throws Exception {
            editor = new EditorMetadataTest.Fixture(parent);
            EditorMetadataTest.edt(() -> { interaction.watch(editor.active); return null; });
            grant = EditorMetadata.navigation(editor.application,new EditorMetadata.Approval(editor.root,editor.roots.generation()),this,interaction);
        }
        public EditorMetadata.Context capture() { return editor.capture(); }
        public EditorNavigation.State state() { interaction.watch(editor.active);return new EditorNavigation.State(capture(),interaction.revision()); }
        public EditorNavigation.Prepared prepare(EditorNavigation.Command c) { beforePrepare.run();return EditorNavigation.prepare(editor.active,state(),c,eligible); }
        public EditorNavigation.Applied apply(EditorNavigation.Prepared p, BooleanSupplier authorized) {
            beforeApply.run();
            return EditorNavigation.apply(editor.active,p,state(),eligible,interaction,authorized,()->{duringApply.run();return state();});
        }
        Map<String,Object> context() { return grant.navigation.dispatch(EditorNavigation.CONTEXT,Map.of("schemaVersion",1L,"projectId",grant.projectId),"context",()->true); }
        Map<String,Object> run(String method,Map<String,Object> params) { return grant.navigation.dispatch(method,params,"n",()->true); }
        void activate(EditorPane pane) { editor.active=pane;interaction.watch(pane);interaction.changed(); }
        void geometry() {
            var area=editor.active.getTextArea();area.setUI(new WorkbenchStyle.TextAreaUI());area.setFont(BundledEditorFont.atSize(16));
            area.setSize(500,3000);var scroll=editor.active.getScrollPane();scroll.setSize(500,220);scroll.doLayout();scroll.getViewport().setExtentSize(new java.awt.Dimension(480,200));
            scroll.getViewport().setViewSize(new java.awt.Dimension(500,3000));
        }
        @Override public void close() throws Exception { grant.close(); EditorMetadataTest.edt(()->null);editor.close(); }
    }
    static Map<String,Object> params(Map<String,Object> context,String method,int start,int end) {
        var p=new LinkedHashMap<String,Object>();
        for(String key:List.of("schemaVersion","instanceId","windowId","projectId","paneId","documentId","documentRevision","contextRevision","caretOffset","selectionStart","selectionEnd"))p.put(key,context.get(key));
        if(method.equals("editor/caret/set"))p.put("offset",(long)start);else{p.put("start",(long)start);p.put("end",(long)end);}return p;
    }
    static Map<String,Object> wire(Map<String,Object> object) {return WorkspaceStatusContractTest.object(MiniJson.stringify(object));}
    static void fails(String code,Runnable operation){EditorMetadataTest.fails(code,operation);}
    static List<Object> source(Fixture f) throws Exception {
        return EditorMetadataTest.edt(()->{
            var b=f.editor.active.getBuffer();return List.of(b.getContent(),b.documentRevision(),b.isModified(),b.getUndoManager(),b.getUndoManager().canUndo(),b.getUndoManager().canRedo(),b.getSavedContent());
        });
    }

    @Test void caretSelectionAndRevealOperateOnCanonicalSurfaceWithoutSourceOrHistoryChanges() throws Exception {
        try(var f=new Fixture(temporary)) {
            EditorMetadataTest.edt(()->{
                var b=f.editor.active.getBuffer();b.getDocument().insertString(0,"human ",null);b.getDocument().insertString(0,"draft ",null);b.getUndoManager().undo();b.setModified(true);f.geometry();return null;
            });
            var before=source(f);byte[] disk=Files.readAllBytes(f.editor.root.resolve("private-name.java"));
            var context=wire(f.context());var moved=f.run("editor/caret/set",params(context,"editor/caret/set",1,1));
            assertEquals(1,moved.get("caretOffset"));assertEquals(true,moved.get("applied"));
            var selected=f.run("editor/selection/set",params(wire(moved),"editor/selection/set",0,3));
            assertEquals(0,selected.get("selectionStart"));assertEquals(3,selected.get("selectionEnd"));assertEquals(3,selected.get("caretOffset"));
            var revealed=f.run("editor/reveal",params(wire(selected),"editor/reveal",0,3));
            assertEquals(3,revealed.get("caretOffset"));assertEquals(0,revealed.get("selectionStart"));assertEquals("range",revealed.get("visibility"));
            assertEquals(before,source(f));assertArrayEquals(disk,Files.readAllBytes(f.editor.root.resolve("private-name.java")));
        }
    }

    @Test void boundariesRejectSurrogateCombiningCrlfEmojiAndGb9cInteriors() throws Exception {
        try(var f=new Fixture(temporary)) {
            String text="a😀é\r\nक्ष👩‍💻z";
            EditorMetadataTest.edt(()->{f.editor.active.getBuffer().setContent(text);return null;});
            var before=source(f);
            for(int position=0;position<=text.length();position++) {
                var p=params(wire(f.context()),"editor/caret/set",position,position);
                if(GraphemeBoundary.isBoundary(text,position))assertEquals(position,f.run("editor/caret/set",p).get("caretOffset"));
                else fails("INVALID_OFFSET",()->f.run("editor/caret/set",p));
            }
            assertEquals(before,source(f));
        }
    }

    @Test void invalidNumericOffsetsAndUnavailableBoundaryContextAreRejectedWithoutClamping() throws Exception {
        try(var f=new Fixture(temporary)) {
            var context=wire(f.context());
            for(Object bad:List.of(-1L,Long.MAX_VALUE,new java.math.BigDecimal("1.0"),"1",12L)) {
                var p=params(context,"editor/caret/set",0,0);p.put("offset",bad);fails("INVALID_OFFSET",()->f.run("editor/caret/set",p));
            }
            fails("INVALID_OFFSET",()->f.run("editor/selection/set",params(context,"editor/selection/set",3,1)));
            EditorMetadataTest.edt(()->{f.editor.active.getBuffer().setContent("é".repeat(10000));return null;});
            fails("BOUNDARY_UNAVAILABLE",()->f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",2,2)));
            Path ascii=Files.writeString(f.editor.root.resolve("long-line.java"),"a".repeat(20000));
            EditorMetadataTest.edt(()->{f.editor.add(ascii);f.activate(f.editor.active);return null;});
            for(int offset:List.of(0,1,20000))fails("BOUNDARY_UNAVAILABLE",()->f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",offset,offset)));
        }
    }

    @Test void emptyAndActualEofAreValidButImpliedEofIsNot() throws Exception {
        try(var f=new Fixture(temporary)) {
            EditorMetadataTest.edt(()->{f.editor.active.getBuffer().setContent("");return null;});
            var p=params(wire(f.context()),"editor/caret/set",0,0);assertEquals(0,f.run("editor/caret/set",p).get("caretOffset"));
            fails("INVALID_OFFSET",()->f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",1,1)));
            EditorMetadataTest.edt(()->{f.editor.active.getBuffer().setContent("body\n");return null;});
            assertEquals(5,f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",5,5)).get("caretOffset"));
            fails("INVALID_OFFSET",()->f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",6,6)));
        }
    }

    @Test void awayAndBackCaretPaneAndBufferTransitionsInvalidateOldCommands() throws Exception {
        try(var f=new Fixture(temporary)) {
            var context=wire(f.context());var original=f.editor.active;
            EditorMetadataTest.edt(()->{original.surface().select(1,1);original.surface().select(0,0);return null;});
            fails("CONTEXT_CHANGED",()->f.run("editor/caret/set",params(context,"editor/caret/set",1,1)));
            var next=wire(f.context());
            EditorMetadataTest.edt(()->{var other=f.editor.split(original.getBuffer());f.activate(other);f.activate(original);return null;});
            fails("CONTEXT_CHANGED",()->f.run("editor/caret/set",params(next,"editor/caret/set",1,1)));
            var latest=wire(f.context());Path path=Files.writeString(f.editor.root.resolve("other"),"other");
            EditorMetadataTest.edt(()->{
                var other=new FileBuffer(path.toFile());var saved=original.getBuffer();
                original.setBuffer(other);original.getTextArea().setDocument(other.getDocument());
                original.setBuffer(saved);original.getTextArea().setDocument(saved.getDocument());return null;
            });
            fails("CONTEXT_CHANGED",()->f.run("editor/caret/set",params(latest,"editor/caret/set",1,1)));
        }
    }

    @Test void sameLengthEditClosedPaneRootChangeAndShutdownFailClosed() throws Exception {
        try(var f=new Fixture(temporary)) {
            var context=wire(f.context());
            EditorMetadataTest.edt(()->{var doc=f.editor.active.getBuffer().getDocument();doc.remove(0,1);doc.insertString(0,"b",null);return null;});
            fails("STALE_REVISION",()->f.run("editor/caret/set",params(context,"editor/caret/set",1,1)));
            var current=wire(f.context());EditorMetadataTest.edt(()->{f.editor.active.disposeSourceView();return null;});
            fails("PANE_UNAVAILABLE",()->f.run("editor/caret/set",params(current,"editor/caret/set",1,1)));
        }
        var parent=Files.createDirectory(temporary.resolve("root-test"));
        try(var f=new Fixture(parent)) {
            var c=wire(f.context());EditorMetadataTest.edt(()->{f.editor.roots.remove(f.editor.root);return null;});
            fails("AUTHORIZATION_REVOKED",()->f.run("editor/caret/set",params(c,"editor/caret/set",1,1)));
        }
    }

    @Test void humanSelectionDuringPreflightIsPreserved() throws Exception {
        try(var f=new Fixture(temporary)) {
            var context=wire(f.context());f.beforeApply=()->f.editor.active.surface().select(5,3);
            fails("CONTEXT_CHANGED",()->f.run("editor/caret/set",params(context,"editor/caret/set",1,1)));
            assertEquals(new EditorSurface.Selection(5,3),EditorMetadataTest.edt(()->f.editor.active.surface().selection()));
        }
    }

    @Test void cancelledQueueCannotApplyLaterAndPendingRequestsRemainBounded() throws Exception {
        try(var f=new Fixture(temporary)) {
            var p=params(wire(f.context()),"editor/caret/set",1,1);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            javax.swing.SwingUtilities.invokeLater(()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            try {
                fails("TIMEOUT",()->f.run("editor/caret/set",p));
                for(int i=0;i<20;i++)fails("BUSY",()->f.run("editor/caret/set",p));
            }finally{release.countDown();}
            EditorMetadataTest.edt(()->null);assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
            assertEquals(1,f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",1,1)).get("caretOffset"));
        }
    }

    @Test void expiredOrRevokedConnectionAtCommitDoesNotMoveTheCaret() throws Exception {
        try(var f=new Fixture(temporary)) {
            var p=params(wire(f.context()),"editor/caret/set",1,1);var calls=new AtomicInteger();
            fails("SESSION_INVALID",()->f.grant.navigation.dispatch("editor/caret/set",p,"n",()->calls.incrementAndGet()<2));
            assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
            f.beforeApply=()->f.grant.close();
            fails("AUTHORIZATION_REVOKED",()->f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",1,1)));
        }
    }

    @Test void unsupportedModesRejectWithoutChangingTheirSourceHistoryOrSelection() throws Exception {
        try(var f=new Fixture(temporary)) {
            var before=source(f);
            for(int attempt=0;attempt<3;attempt++) {
                f.eligible=false;var c=wire(f.context());
                fails("UNSUPPORTED_MODE",()->f.run("editor/selection/set",params(c,"editor/selection/set",0,1)));
                assertEquals(before,source(f));assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
                f.eligible=true;f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",1,1));
                f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",0,0));
            }
        }
    }

    @Test void revealRequiresGeometryAndPreservesSelectionForLargeRanges() throws Exception {
        try(var f=new Fixture(temporary)) {
            var c=wire(f.context());fails("GEOMETRY_UNAVAILABLE",()->f.run("editor/reveal",params(c,"editor/reveal",0,1)));
            EditorMetadataTest.edt(()->{f.editor.active.getBuffer().setContent("line text\n".repeat(100));f.geometry();return null;});
            var result=f.run("editor/reveal",params(wire(f.context()),"editor/reveal",500,800));
            assertEquals("start",result.get("visibility"));assertEquals(0,result.get("caretOffset"));
            assertTrue(EditorMetadataTest.edt(()->f.editor.active.getScrollPane().getViewport().getViewPosition().y)>0);
        }
    }
    @Test void runningCommandWithLostDeadlineReportsUnknownAndConsumesItsContext() throws Exception {
        try(var f=new Fixture(temporary);var worker=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var command=params(wire(f.context()),"editor/caret/set",1,1);
            CountDownLatch applied=new CountDownLatch(1),release=new CountDownLatch(1);var reads=new AtomicInteger();
            f.duringApply=()->{if(reads.incrementAndGet()==2){applied.countDown();try{assertTrue(release.await(4,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}};
            var result=worker.submit(()->assertThrows(WorkspaceApiException.class,()->f.run("editor/caret/set",command)).code());
            try {
                assertTrue(applied.await(1,TimeUnit.SECONDS));
                assertEquals(WorkspaceApiException.Code.OUTCOME_UNKNOWN,result.get(2,TimeUnit.SECONDS));
                fails("BUSY",()->f.run("editor/caret/set",command));
            }finally{release.countDown();}
            EditorMetadataTest.edt(()->null);
            var current=wire(f.context());assertEquals(1L,current.get("caretOffset"));
            fails("CONTEXT_CHANGED",()->f.run("editor/caret/set",command));
        }
    }

    @Test void sharedSplitNavigationChangesOnlyTheApprovedActiveView() throws Exception {
        try(var f=new Fixture(temporary)) {
            var first=f.editor.active;var buffer=first.getBuffer();
            var second=EditorMetadataTest.edt(()->{var split=f.editor.split(buffer);f.activate(split);return split;});
            var before=source(f);var focus=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            f.run("editor/selection/set",params(wire(f.context()),"editor/selection/set",1,3));
            assertEquals(0,EditorMetadataTest.edt(()->first.getTextArea().getCaretPosition()));
            assertEquals(new EditorSurface.Selection(3,1),EditorMetadataTest.edt(()->second.surface().selection()));
            assertSame(focus,java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());assertEquals(before,source(f));
            EditorMetadataTest.edt(()->{first.disposeSourceView();f.editor.panes.remove(first);return null;});
            f.run("editor/caret/set",params(wire(f.context()),"editor/caret/set",1,1));assertEquals(before,source(f));
        }
    }

    @Test void closeDocumentPaneOrApplicationDuringPreparationRejectsBeforeApplication() throws Exception {
        for(String action:List.of("document","pane","shutdown")) {
            try(var f=new Fixture(Files.createDirectory(temporary.resolve(action)))) {
                var command=params(wire(f.context()),"editor/caret/set",1,1);
                f.beforeApply=()->{switch(action){case "document"->f.editor.buffers.clear();case "pane"->f.editor.active.disposeSourceView();default->f.editor.closing=true;}};
                fails(switch(action){case "document"->"CONTEXT_CHANGED";case "pane"->"PANE_UNAVAILABLE";default->"APP_SHUTTING_DOWN";},()->f.run("editor/caret/set",command));
                assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
            }
        }
    }

    @Test void trackingCapacityRejectsObservationWithoutInterruptingHumanPaneCreation() throws Exception {
        try(var f=new Fixture(temporary)) {
            EditorMetadataTest.edt(()->{
                var buffer=f.editor.active.getBuffer();
                for(int i=1;i<EditorMetadata.MAX_PANES;i++)assertTrue(f.interaction.watch(f.editor.split(buffer)));
                var excess=f.editor.split(buffer);assertFalse(f.interaction.watch(excess));
                assertEquals(0,excess.getTextArea().getCaretListeners().length);
                f.interaction.close();
                for(var pane:f.editor.panes)assertEquals(0,pane.getTextArea().getCaretListeners().length);
                return null;
            });
        }
    }

}
