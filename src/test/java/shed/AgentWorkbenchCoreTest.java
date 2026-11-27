package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class AgentWorkbenchCoreTest {
    static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);}
    static class Fixture implements AgentUiSession.Host,AutoCloseable {
        final JTextArea area=new JTextArea(){@Override public boolean isShowing(){return true;}};
        final JScrollPane scroll=new JScrollPane(area);
        final JTextField query=new JTextField(){@Override public boolean isShowing(){return true;}};
        final JButton button=new JButton("Run"){ @Override public boolean isShowing(){return true;}};
        final JList<String> list=new JList<>(new String[]{"one","two","three"}){@Override public boolean isShowing(){return true;}};
        final JRootPane root=new JRootPane();
        final AgentUiPresentation presence=new AgentUiPresentation();
        final AgentUiSession session=new AgentUiSession(this,presence);
        final EnumSet<AgentUiPermission> permissions=EnumSet.of(AgentUiPermission.NAVIGATE);
        boolean live=true,motion;
        int clicks;
        Fixture(){root.setSize(640,400);root.getContentPane().setLayout(null);scroll.setBounds(0,0,450,350);root.getContentPane().add(scroll);query.setBounds(10,360,200,30);root.getContentPane().add(query);button.setBounds(220,360,80,30);root.getContentPane().add(button);list.setBounds(460,0,150,100);root.getContentPane().add(list);area.setText("x".repeat(300)+"\n"+"line\n".repeat(300));area.setSize(1500,6000);scroll.doLayout();root.doLayout();button.addActionListener(e->clicks++);}
        public List<AgentUiTarget> targets(){return List.of(new AgentUiTarget("editor","Editor",area,AgentUiPermission.NAVIGATE,null),new AgentUiTarget("query","Query",query,AgentUiPermission.NAVIGATE,null),new AgentUiTarget("run","Run",button,AgentUiPermission.TOOLS,null),new AgentUiTarget("list","Results",list,AgentUiPermission.NAVIGATE,null));}
        public boolean permitted(AgentUiPermission p){return permissions.contains(p);}
        public boolean current(){return live;}
        public boolean motion(){return motion;}
        public JRootPane root(){return root;}
        public void close(){session.close();}
    }
    static AgentUiRequest request(Fixture f,String kind,String target,Map<String,Object> args){long r=((Number)f.session.state(0).get("revision")).longValue();return new AgentUiRequest(kind,target,"Codex",r,args);}
    static Map<String,Object> run(Fixture f,String kind,String target,Map<String,Object> args)throws Exception{return edt(()->f.session.execute(request(f,kind,target,args),()->true)).get(5,TimeUnit.SECONDS);}
    static Map<String,Object> range(int a,int b){return Map.of("start",(long)a,"end",(long)b);}
    @Test void verticalAndHorizontalScrollingPreserveSourceAndSelection()throws Exception{
        var f=edt(Fixture::new);try{
            edt(()->{f.area.select(2,8);return null;});String source=edt(f.area::getText);
            run(f,"scroll","editor",Map.of("axis","vertical","unit","pages","amount",2L));
            edt(()->{assertTrue(f.scroll.getViewport().getViewPosition().y>0);assertEquals(2,f.area.getSelectionStart());assertEquals(8,f.area.getSelectionEnd());assertEquals(source,f.area.getText());return null;});
            run(f,"scroll","editor",Map.of("axis","horizontal","unit","pixels","amount",100L));
            edt(()->{assertTrue(f.scroll.getViewport().getViewPosition().x>0);return null;});
        }finally{edt(()->{f.close();return null;});}
    }
    @Test void scrollingClampsAtBothEndsAndSupportsNegativeDistance()throws Exception{
        edt(()->{var f=new Fixture();try(f){var down=new AgentUiScroll(f.scroll,AgentUiScroll.Axis.VERTICAL,AgentUiScroll.Unit.PAGES,10_000);down.frame(1);assertEquals(down.destination(),f.scroll.getVerticalScrollBar().getValue());var up=new AgentUiScroll(f.scroll,AgentUiScroll.Axis.VERTICAL,AgentUiScroll.Unit.PAGES,-10_000);up.frame(1);assertEquals(0,f.scroll.getVerticalScrollBar().getValue());}return null;});
    }
    @Test void wheelFramesAreMonotonicAndUseActualViewport()throws Exception{
        edt(()->{var f=new Fixture();try(f){var motion=new AgentUiScroll(f.scroll,AgentUiScroll.Axis.VERTICAL,AgentUiScroll.Unit.PIXELS,300);int last=-1;for(int i=0;i<=20;i++){motion.frame(i/20.0);int y=f.scroll.getVerticalScrollBar().getValue();assertTrue(y>=last);last=y;}assertEquals(300,last);}return null;});
    }
    @Test void setInsertDeleteSelectAndReadUseTheRealField()throws Exception{
        var f=edt(Fixture::new);try{
            run(f,"text","query",Map.of("mode","set","text","hello world","start",0L,"end",0L));
            run(f,"text","query",Map.of("mode","insert","text","Shed","start",6L,"end",11L));assertEquals("hello Shed",edt(f.query::getText));
            var selected=new LinkedHashMap<String,Object>(range(0,5));selected.put("mode","select");selected.put("text","");run(f,"text","query",selected);
            assertEquals("hello",edt(f.query::getSelectedText));
            var read=run(f,"read","query",range(0,5));assertEquals("hello",read.get("content"));
            var deleted=new LinkedHashMap<String,Object>(range(0,6));deleted.put("mode","delete");deleted.put("text","");run(f,"text","query",deleted);assertEquals("Shed",edt(f.query::getText));
        }finally{edt(()->{f.close();return null;});}
    }
    @Test void clipboardIsSessionPrivateAndUsesActualSelection()throws Exception{
        var f=edt(Fixture::new);try{
            edt(()->{f.query.setText("private");f.query.selectAll();return null;});run(f,"copy","query",Map.of());
            edt(()->{f.query.setText("");return null;});run(f,"paste","query",Map.of());assertEquals("private",edt(f.query::getText));
        }finally{edt(()->{f.close();return null;});}
    }
    @Test void toolPermissionIsCheckedBeforeButtonActivation()throws Exception{
        var f=edt(Fixture::new);try{
            assertThrows(ExecutionException.class,()->edt(()->f.session.execute(request(f,"click","run",Map.of()),()->true)));
            assertEquals(0,f.clicks);edt(()->{f.permissions.add(AgentUiPermission.TOOLS);return null;});run(f,"click","run",Map.of());edt(()->{assertEquals(1,f.clicks);return null;});
        }finally{edt(()->{f.close();return null;});}
    }
    @Test void selectAndKeyboardUseExistingListModelAndActions()throws Exception{
        var f=edt(Fixture::new);try{run(f,"select","list",Map.of("index",1L));assertEquals(1,edt(f.list::getSelectedIndex));run(f,"key","list",Map.of("key","DOWN"));assertEquals(2,edt(f.list::getSelectedIndex));}
        finally{edt(()->{f.close();return null;});}
    }
    @Test void arbitraryKeyBindingsCannotReadTheSystemClipboard()throws Exception{
        var f=edt(Fixture::new);try{assertThrows(ExecutionException.class,()->run(f,"key","query",Map.of("key","meta V")));assertEquals("",edt(f.query::getText));}
        finally{edt(()->{f.close();return null;});}
    }
    @Test void requestSchemaRejectsExtraFieldsUnknownKindsAndUnboundedDistances(){
        assertThrows(WorkspaceApiException.class,()->new AgentUiRequest("exec","editor","Agent",1,Map.of()));
        assertThrows(WorkspaceApiException.class,()->new AgentUiRequest("scroll","editor","Agent",1,Map.of("axis","vertical","unit","pixels","amount",Long.MIN_VALUE)));
        assertThrows(WorkspaceApiException.class,()->new AgentUiRequest("text","query","Agent",1,Map.of("mode","set","text","x".repeat(4097),"start",0L,"end",0L)));
        assertThrows(WorkspaceApiException.class,()->AgentUiRequest.parse(Map.of("schemaVersion",2L,"kind","hover","target","editor","agent","Agent","expectedUiRevision",1L,"args",Map.of(),"exec","no")));
    }
    @Test void staleRequestsAndClosedSessionsDoNotMutateFields()throws Exception{
        edt(()->{var f=new Fixture();try(f){var r=request(f,"hover","query",Map.of());f.button.setEnabled(false);f.session.state(0);assertThrows(WorkspaceApiException.class,()->f.session.execute(r,()->true));f.close();assertThrows(WorkspaceApiException.class,()->f.session.execute(request(f,"hover","query",Map.of()),()->true));}return null;});
    }
    @Test void cancelledFutureAndCloseRemoveTransientHighlightsAndTimers()throws Exception{
        var f=edt(Fixture::new);f.motion=true;
        var done=edt(()->f.session.execute(request(f,"read","editor",range(0,20)),()->true));
        edt(()->{f.close();assertFalse(f.session.busy());assertEquals(0,f.area.getHighlighter().getHighlights().length);assertFalse(f.presence.installed());return null;});
        assertThrows(ExecutionException.class,()->done.get(1,TimeUnit.SECONDS));
    }
    @Test void headlessCompletionDoesNotClaimDesktopPaint()throws Exception{
        var f=edt(Fixture::new);try{var reply=run(f,"hover","query",Map.of());assertEquals("ui-updated",reply.get("stage"));}finally{edt(()->{f.close();return null;});}
    }
}
