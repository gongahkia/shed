package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.AgentWorkbenchCoreTest.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class AgentWorkbenchInteractionTest {
    @Test void standaloneTerminalScrollbarUsesItsExistingModelAndExplicitUnits()throws Exception{
        edt(()->{var bar=new JScrollBar(JScrollBar.VERTICAL,-100,24,-200,24);bar.setUnitIncrement(1);bar.setBlockIncrement(24);var motion=new AgentUiScroll(bar,AgentUiScroll.Unit.PAGES,2);motion.frame(.5);assertTrue(bar.getValue()>-100);motion.frame(1);assertEquals(-52,bar.getValue());assertEquals("scrollbar-model",motion.state().get("units"));assertEquals(-52,motion.state().get("y"));return null;});
    }
    @Test void pickerEnterInvokesTheActualFieldListenerWithoutSystemFocus()throws Exception{
        var f=edt(Fixture::new);int[] accepted={0};try{edt(()->{f.query.addActionListener(e->accepted[0]++);return null;});run(f,"key","query",Map.of("key","ENTER"));assertEquals(1,accepted[0]);}finally{edt(()->{f.close();return null;});}
    }
    @Test void agentPaletteExcludesOsDialogsQuitAndUserConfigurationFiles(){
        for(String command:List.of("open folder","qa!","settings file","snippet edit","agent api start control","wall"))assertFalse(AgentWorkbenchUi.paletteAllowed(command));
        for(String command:List.of("files","buffers","write","density compact","focus toggle"))assertTrue(AgentWorkbenchUi.paletteAllowed(command));
    }
    @Test void ambiguousAndPasswordTargetsAreNeverAdvertised()throws Exception{
        edt(()->{var root=new JPanel();root.add(new JTextField());root.add(new JTextField());var secret=new JPasswordField("private");secret.setName("password");root.add(secret);var button=new JButton("Refresh");button.setName("refresh");root.add(button);var targets=new ArrayList<AgentUiTarget>();AgentUiTargets.collect(root,"tool",AgentUiPermission.NAVIGATE,targets);assertEquals(List.of("tool/refresh"),targets.stream().map(AgentUiTarget::id).toList());return null;});
    }
    @Test void explicitPermissionTravelsWithTheActualControl()throws Exception{
        edt(()->{var panel=new JPanel();var button=new JButton("Replace");int[] clicked={0};AgentUiTargets.action(button,AgentUiPermission.EDIT,()->clicked[0]++);panel.add(button);var targets=new ArrayList<AgentUiTarget>();AgentUiTargets.collect(panel,"find",AgentUiPermission.NAVIGATE,targets);assertEquals(AgentUiPermission.EDIT,targets.getFirst().permission());targets.getFirst().action().run("");assertEquals(1,clicked[0]);return null;});
    }
    @Test void backgroundModelReplacementCancelsBeforeChoosingTheWrongResult()throws Exception{
        var f=edt(Fixture::new);f.motion=true;try{var done=edt(()->f.session.execute(request(f,"select","list",Map.of("index",1L)),()->true));edt(()->{f.list.setListData(new String[]{"other","wrong","result"});return null;});assertThrows(ExecutionException.class,()->done.get(2,TimeUnit.SECONDS));assertEquals(-1,edt(f.list::getSelectedIndex));}finally{edt(()->{f.close();return null;});}
    }
    @Test void sourceBoundariesRejectSplittingSurrogatesAndCombiningClusters()throws Exception{
        var f=edt(Fixture::new);try{edt(()->{f.query.setText("a👩‍💻e\u0301z");return null;});assertThrows(ExecutionException.class,()->run(f,"read","query",range(2,3)));assertThrows(ExecutionException.class,()->run(f,"read","query",range(6,7)));assertEquals("👩‍💻",run(f,"read","query",range(1,6)).get("content"));assertEquals("a👩‍💻e\u0301z",edt(f.query::getText));}finally{edt(()->{f.close();return null;});}
    }
    @Test void unboundedParagraphsFailWithoutMaterializingTheWholeDocument()throws Exception{
        var f=edt(Fixture::new);try{edt(()->{f.query.setText("a".repeat(65537));return null;});assertThrows(ExecutionException.class,()->run(f,"read","query",range(0,1)));assertEquals(65537,edt(()->f.query.getDocument().getLength()));}finally{edt(()->{f.close();return null;});}
    }
    @Test void sourceDragMovesOnlyRealSelectionAndPreservesText()throws Exception{
        var f=edt(Fixture::new);try{String before=edt(f.area::getText);run(f,"drag","editor",range(20,4));edt(()->{assertEquals(4,f.area.getSelectionStart());assertEquals(20,f.area.getSelectionEnd());assertEquals(before,f.area.getText());return null;});}finally{edt(()->{f.close();return null;});}
    }
    @Test void privateClipboardIsRetiredBetweenSessions()throws Exception{
        var f=edt(Fixture::new);edt(()->{f.query.setText("private");f.query.selectAll();return null;});run(f,"copy","query",Map.of());edt(()->{f.close();return null;});var next=edt(Fixture::new);try{run(next,"paste","query",Map.of());assertEquals("",edt(next.query::getText));}finally{edt(()->{next.close();return null;});}
    }
    @Test void sourceAndUiUseOnePresenceAndOldFollowCannotStealIt()throws Exception{
        edt(()->{var f=new Fixture();try(f){var p=f.presence;p.attach(f.root);assertSame(p,AgentUiPresentation.find(f.area));long old=p.beginFollow(f.area,new Point(20,20),"Codex",false);p.follow(old,new Point(20,20),true,"writing");var last=p.pointerPosition();p.show(f.button,"Claude Code",true);assertEquals(last,p.pointerPosition());assertFalse(p.follows(old));p.follow(old,new Point(500,500),true,"writing");assertEquals(last,p.pointerPosition());long current=p.beginFollow(f.area,new Point(30,30),"Codex",false);assertTrue(p.follows(current));assertEquals(1,f.root.getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER).length);p.endFollow(old);assertTrue(p.follows(current));p.endFollow(current);assertFalse(p.follows(current));}return null;});
    }
    @Test void splitAndScrollbarDragUseActualStartingPosition()throws Exception{
        class Controls extends Fixture {
            final JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JPanel(),new JPanel()){@Override public boolean isShowing(){return true;}};
            Controls(){root.getContentPane().add(split);split.setBounds(0,0,400,300);split.setDividerLocation(100);scroll.getVerticalScrollBar().setVisible(true);}
            public List<AgentUiTarget> targets(){var result=new ArrayList<>(super.targets());result.add(new AgentUiTarget("split","Divider",split,AgentUiPermission.NAVIGATE,null));return result;}
        }
        var f=edt(Controls::new);try{run(f,"drag","split",range(0,750));assertEquals(292,edt(f.split::getDividerLocation));}finally{edt(()->{f.close();return null;});}
    }
    @Test void reducedMotionStillInvokesTheSameRealButton()throws Exception{
        var f=edt(Fixture::new);try{edt(()->{f.permissions.add(AgentUiPermission.TOOLS);return null;});var result=run(f,"click","run",Map.of());assertEquals("requested",result.get("stage"));edt(()->{assertEquals(1,f.clicks);assertFalse(f.session.busy());return null;});}finally{edt(()->{f.close();return null;});}
    }
}
