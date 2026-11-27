package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class AgentUiActionsTest {
    private static <T> T edt(Callable<T> action) throws Exception {
        var result = new FutureTask<>(action); SwingUtilities.invokeAndWait(result); return result.get();
    }
    private static final class Surface implements AgentUiActions.Surface {
        final JTextField field = new JTextField() { @Override public boolean isShowing() { return true; } };
        final JButton button = new JButton() { @Override public boolean isShowing() { return true; } };
        final JRootPane root = new JRootPane();
        boolean visible = true, current = true, motion;
        String phase = "ready";
        long revision;
        String fingerprint = "";
        final List<String> invoked = new ArrayList<>();
        Surface() {
            root.setSize(640, 200); root.getContentPane().setLayout(null);
            field.setBounds(20, 20, 240, 30); button.setBounds(280, 20, 80, 30);
            root.getContentPane().add(field); root.getContentPane().add(button); root.doLayout();
        }
        public Map<String,Object> state() {
            String value = visible + field.getText() + field.getCaretPosition() + field.getSelectionStart();
            if (!value.equals(fingerprint)) { fingerprint=value; revision++; }
            return Map.of("visible",visible,"mode","find","revision",revision,"query",field.getText(),"phase",phase);
        }
        public Component target(String action) { return action.equals("find.query") || action.equals("find.open") ? field : button; }
        public void invoke(String action) { invoked.add(action); if(action.equals("find.open"))visible=true; if(action.equals("find.close"))visible=false; }
        public boolean current() { return current; }
        public boolean motion() { return motion; }
    }
    private static CompletableFuture<Map<String,Object>> start(AgentUiActions ui, Surface surface, String action, String text, java.util.function.BooleanSupplier authorized) throws Exception {
        return edt(() -> ui.invoke(new AgentUiActions.Command(action, ((Number)surface.state().get("revision")).longValue(), "Codex", text), authorized));
    }
    @Test void rejectsUnknownActionsAndInvalidText() {
        for (String action : List.of("save","terminal.run","find.replace","find.query.extra"))
            assertThrows(WorkspaceApiException.class,()->new AgentUiActions.Command(action,0,"Agent",""));
        for (String text : List.of("x\ny", "x\0y", "\ud800", "x".repeat(513)))
            assertThrows(WorkspaceApiException.class,()->new AgentUiActions.Command("find.query",0,"Agent",text));
        assertThrows(WorkspaceApiException.class,()->new AgentUiActions.Command("find.next",0,"Agent","source"));
        assertThrows(WorkspaceApiException.class,()->new AgentUiActions.Command("find.query",-1,"Agent",""));
    }
    @Test void acceptsExactComplexTextWithoutNormalization() {
        String text="cafe\u0301 👩‍💻 हिन्दी";
        assertEquals(text,new AgentUiActions.Command("find.query",0,"Agent",text).text());
    }
    @Test void catalogOnlyListsSupportedFindActions() throws Exception {
        edt(()->{var s=new Surface();try(var ui=new AgentUiActions(s)){
            assertEquals(AgentUiActions.IDS.size(),((List<?>)ui.catalog().get("actions")).size());
            assertFalse(ui.catalog().toString().contains("replace"));
        }return null;});
    }
    @Test void typesInActualFieldThenReleasesOverlayAndListener() throws Exception {
        var s=edt(Surface::new);var ui=edt(()->new AgentUiActions(s));
        try {
            var result=start(ui,s,"find.query","hello 👩‍💻",()->true).get(2,TimeUnit.SECONDS);
            assertEquals("hello 👩‍💻",result.get("query"));
            edt(()->{assertFalse(ui.active());assertEquals(1,s.root.getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER).length);return null;});
        } finally {edt(()->{ui.close();return null;});}
    }
    @Test void animatedTypingPreservesGraphemeBoundaries() throws Exception {
        var s=edt(Surface::new);s.motion=true;var ui=edt(()->new AgentUiActions(s));
        String value="a👩‍💻e\u0301क्‍ष";var seen=new ArrayList<String>();
        edt(()->{s.field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            public void insertUpdate(javax.swing.event.DocumentEvent e){seen.add(s.field.getText());}
            public void removeUpdate(javax.swing.event.DocumentEvent e){}
            public void changedUpdate(javax.swing.event.DocumentEvent e){}
        });return null;});
        try {start(ui,s,"find.query",value,()->true).get(6,TimeUnit.SECONDS);
            for(String prefix:seen){assertTrue(value.startsWith(prefix));assertTrue(GraphemeBoundary.isBoundary(value,prefix.length()));}
        } finally {edt(()->{ui.close();return null;});}
    }
    @Test void staleUiRevisionRejectsBeforeOpening() throws Exception {
        edt(()->{var s=new Surface();try(var ui=new AgentUiActions(s)){
            s.state();assertThrows(WorkspaceApiException.class,()->ui.invoke(new AgentUiActions.Command("find.open",0,"Agent",""),()->true));
            assertTrue(s.invoked.isEmpty());
        }return null;});
    }
    @Test void humanQueryChangeCancelsWithoutOverwritingIt() throws Exception {
        var s=edt(Surface::new);s.motion=true;var ui=edt(()->new AgentUiActions(s));
        try {var done=start(ui,s,"find.query","agent query",()->true);
            edt(()->{s.field.setText("human query");return null;});
            assertThrows(ExecutionException.class,()->done.get(2,TimeUnit.SECONDS));
            assertEquals("human query",edt(s.field::getText));
        }finally{edt(()->{ui.close();return null;});}
    }
    @Test void contextSwitchCancelsBeforeClick() throws Exception {
        var s=edt(Surface::new);s.motion=true;var ui=edt(()->new AgentUiActions(s));
        try {var done=start(ui,s,"find.next","",()->true);edt(()->{s.current=false;return null;});
            assertThrows(ExecutionException.class,()->done.get(2,TimeUnit.SECONDS));assertTrue(s.invoked.isEmpty());
        }finally{edt(()->{ui.close();return null;});}
    }
    @Test void revocationCancelsAndConcurrentActionIsRejected() throws Exception {
        var s=edt(Surface::new);s.motion=true;var ui=edt(()->new AgentUiActions(s));var allowed=new AtomicBoolean(true);
        try {var done=start(ui,s,"find.next","",allowed::get);
            edt(()->{assertThrows(WorkspaceApiException.class,()->ui.invoke(new AgentUiActions.Command("find.previous",s.revision,"Agent",""),()->true));return null;});
            allowed.set(false);assertThrows(ExecutionException.class,()->done.get(2,TimeUnit.SECONDS));
            edt(()->{assertFalse(ui.active());return null;});
        }finally{edt(()->{ui.close();return null;});}
    }
    @Test void disposalIsIdempotentAndCompletesPendingRequest() throws Exception {
        var s=edt(Surface::new);var ui=edt(()->new AgentUiActions(s));
        var done=start(ui,s,"find.next","",()->true);
        edt(()->{ui.close();ui.close();assertFalse(ui.active());return null;});
        assertThrows(ExecutionException.class,()->done.get(1,TimeUnit.SECONDS));
    }
    @Test void buttonsUseSurfaceActionAndHiddenFindRejectsText() throws Exception {
        var s=edt(Surface::new);var ui=edt(()->new AgentUiActions(s));
        try {start(ui,s,"find.close","",()->true).get(2,TimeUnit.SECONDS);
            assertEquals(List.of("find.close"),s.invoked);
            edt(()->{assertThrows(WorkspaceApiException.class,()->ui.invoke(new AgentUiActions.Command("find.query",s.revision,"Agent","x"),()->true));return null;});
        }finally{edt(()->{ui.close();return null;});}
    }
    @Test void presentationIsClickThroughAndClearsOnClose() throws Exception {
        edt(()->{var s=new Surface();try(var p=new AgentUiPresentation()){
            p.show(s.field,"Agent",true);assertTrue(p.installed());assertFalse(p.contains(25,25));
            var image=new java.awt.image.BufferedImage(640,200,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var g=image.createGraphics();p.frame(.7f,false);p.paint(g);g.dispose();
            boolean painted=false;for(int y=0;y<200;y++)for(int x=0;x<640;x++)painted|=image.getRGB(x,y)!=0;
            assertTrue(painted);image.flush();
            p.close();assertFalse(p.installed());
        }return null;});
    }
    @Test void pointerRetainsLocationAndMorphsWithoutCapturingMouse() throws Exception {
        edt(()->{var s=new Surface();try(var p=new AgentUiPresentation()){
            p.show(s.button,"Codex",true);p.frame(1,false);var last=p.pointerPosition();p.settle();
            p.show(s.field,"Claude Code",true);assertEquals(last,p.pointerPosition());
            p.frame(.1f,false);assertNotEquals(last,p.pointerPosition());
            p.frame(1,true);assertTrue(p.morph()>0&&p.morph()<1);
            for(int i=0;i<8;i++)p.frame(1,true);assertEquals(1,p.morph());
            for(int i=0;i<8;i++)p.frame(1,false);assertEquals(0,p.morph());
            assertFalse(p.contains(0,0));
        }return null;});
    }
    @Test void reducedMotionMovesDirectlyWithoutMorphFrames() throws Exception {
        edt(()->{var s=new Surface();try(var p=new AgentUiPresentation()){
            p.show(s.field,"Codex",false);p.frame(1,true);assertEquals(1,p.morph());p.frame(1,false);assertEquals(0,p.morph());
        }return null;});
    }
    @Test void cancelledClientFutureStopsWorkAndReleasesInputListener() throws Exception {
        var s=edt(Surface::new);s.motion=true;var ui=edt(()->new AgentUiActions(s));
        int listeners=edt(()->java.awt.Toolkit.getDefaultToolkit().getAWTEventListeners().length);
        try {
            var done=start(ui,s,"find.query","should not type",()->true);done.cancel(false);
            var barrier=new CompletableFuture<Void>();
            edt(()->{var timer=new javax.swing.Timer(50,e->barrier.complete(null));timer.setRepeats(false);timer.start();return null;});barrier.get(2,TimeUnit.SECONDS);
            edt(()->{assertFalse(ui.active());assertEquals("",s.field.getText());assertEquals(listeners,java.awt.Toolkit.getDefaultToolkit().getAWTEventListeners().length);return null;});
        }finally{edt(()->{ui.close();return null;});}
    }
    @Test void sequentialActionsRetainOnePointerAndNoInputListeners() throws Exception {
        var s=edt(Surface::new);var ui=edt(()->new AgentUiActions(s));
        int listeners=edt(()->java.awt.Toolkit.getDefaultToolkit().getAWTEventListeners().length);
        try {
            for(int i=0;i<8;i++)start(ui,s,"find.query","query"+i,()->true).get(2,TimeUnit.SECONDS);
            edt(()->{assertFalse(ui.active());assertEquals(1,s.root.getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER).length);
                assertEquals(listeners,java.awt.Toolkit.getDefaultToolkit().getAWTEventListeners().length);return null;});
        }finally{edt(()->{ui.close();assertEquals(0,s.root.getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER).length);return null;});}
    }

}
