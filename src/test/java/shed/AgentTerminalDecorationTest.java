package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class AgentTerminalDecorationTest {
    static BufferedImage paint(JComponent component){var image=new BufferedImage(300,100,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();component.paint(g);g.dispose();return image;}
    @Test void glowPaintsOverOpaqueTerminalChildrenWithoutChangingLayoutAndCleansUp()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var terminal=new JPanel(new BorderLayout()){@Override protected void paintChildren(Graphics g){super.paintChildren(g);AgentTerminalDecoration.paintGlow(this,g);}};
            var child=new JPanel();child.setBackground(Color.WHITE);terminal.add(child);terminal.setSize(300,100);terminal.doLayout();
            var bounds=child.getBounds();var status=new JLabel("Normal status");var icon=UIManager.getIcon("OptionPane.informationIcon");status.setIcon(icon);
            var baseline=paint(terminal);var decoration=new AgentTerminalDecoration(terminal,child,"Codex");decoration.phase("typing");var active=paint(terminal);
            assertNotEquals(baseline.getRGB(0,0),active.getRGB(0,0));assertEquals(Color.WHITE.getRGB(),active.getRGB(150,50));assertEquals(bounds,child.getBounds());
            assertEquals(Boolean.TRUE,child.getClientProperty("shed.agentControl"));assertSame(icon,status.getIcon());
            decoration.phase("running");assertNotEquals(active.getRGB(0,0),paint(terminal).getRGB(0,0),"Glow color follows the current action");
            decoration.close();decoration.close();assertNull(child.getClientProperty("shed.agentControl"));assertSame(icon,status.getIcon());
            assertArrayEquals(baseline.getRGB(0,0,300,100,null,0,300),paint(terminal).getRGB(0,0,300,100,null,0,300));
        });
    }
    @Test void providerBadgeFollowsTheTerminalCursorWithoutChangingTextOrCoveringExistingOutput()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var settings=new PtyTerminalPane.ShedTerminalSettingsProvider(null,new Font("Monospaced",0,16));
            var style=new com.jediterm.terminal.model.StyleState();style.setDefaultStyle(settings.getDefaultStyle());
            var buffer=new com.jediterm.terminal.model.TerminalTextBuffer(80,24,style);
            var panel=new AnimatedTerminalPanel(settings,buffer,style){@Override public boolean isShowing(){return true;}@Override public boolean isFocusOwner(){return false;}};
            panel.init(new JScrollBar());panel.setSize(panel.getPreferredSize());panel.putClientProperty("shed.caretAnimation",false);panel.setCursorShape(com.jediterm.terminal.CursorShape.STEADY_BLOCK);
            var owner=new JPanel();owner.setSize(panel.getSize());
            try{
                buffer.writeString(0,1,new com.jediterm.terminal.model.CharBuffer("check"));panel.setCursor(5,1);String text=buffer.getScreenLines();
                var baseline=paintPanel(panel);var cursor=panel.agentCursorBounds();var footprint=new Rectangle(cursor);footprint.grow(1,1);
                for(String provider:new String[]{"Codex","Claude Code","OpenCode","Local agent"})try(var decoration=new AgentTerminalDecoration(owner,panel,provider)){
                    decoration.phase("typing");var active=paintPanel(panel);int changed=0;
                    for(int y=0;y<active.getHeight();y++)for(int x=0;x<active.getWidth();x++)if(active.getRGB(x,y)!=baseline.getRGB(x,y)){
                        if(footprint.contains(x,y))continue;
                        assertTrue(x>cursor.getMaxX(),"Badge must stay to the right of terminal input");assertTrue(y>=cursor.y&&y<cursor.getMaxY(),"Badge must remain one terminal row tall");changed++;
                    }
                    assertTrue(changed>0,"Provider badge is missing for "+provider);assertEquals(text,buffer.getScreenLines());
                    int x=cursor.x+cursor.width/2,y=cursor.y+cursor.height/2;
                    assertEquals(tinted(panel.getBackground(),AgentTerminalDecoration.cursorColor(panel)),active.getRGB(x,y),"Typing cursor shares the green action color, including with reduced motion and without focus");
                    int green=active.getRGB(x,y);
                    decoration.phase("running");assertEquals(tinted(panel.getBackground(),AgentTerminalDecoration.cursorColor(panel)),paintPanel(panel).getRGB(x,y));
                    assertNotEquals(green,paintPanel(panel).getRGB(x,y),"Running cursor must change to amber");
                    decoration.phase("waiting for prompt");assertEquals(tinted(panel.getBackground(),AgentTerminalDecoration.cursorColor(panel)),paintPanel(panel).getRGB(x,y));
                }
                assertNull(AgentTerminalDecoration.cursorColor(panel));
                assertArrayEquals(baseline.getRGB(0,0,baseline.getWidth(),baseline.getHeight(),null,0,baseline.getWidth()),paintPanel(panel).getRGB(0,0,baseline.getWidth(),baseline.getHeight(),null,0,baseline.getWidth()),"Human cursor appearance is restored after agent release");
                panel.setCursor(15,2);var movedBaseline=paintPanel(panel);
                try(var decoration=new AgentTerminalDecoration(owner,panel,"Claude Code")){
                    decoration.phase("typing");var moved=paintPanel(panel);var caret=panel.agentCursorBounds();int changed=0;
                    for(int y=caret.y;y<caret.getMaxY();y++)for(int x=(int)caret.getMaxX()+6;x<moved.getWidth();x++)if(moved.getRGB(x,y)!=movedBaseline.getRGB(x,y))changed++;
                    assertTrue(changed>0);assertEquals(text,buffer.getScreenLines());
                }
                panel.setCursor(5,1);buffer.writeString(6,1,new com.jediterm.terminal.model.CharBuffer("occupied"));var occupied=paintPanel(panel);
                try(var decoration=new AgentTerminalDecoration(owner,panel,"Codex")){
                    decoration.phase("typing");var active=paintPanel(panel);
                    var caret=panel.agentCursorBounds();caret.grow(1,1);
                    for(int y=0;y<active.getHeight();y++)for(int x=0;x<active.getWidth();x++)if(!caret.contains(x,y))assertEquals(occupied.getRGB(x,y),active.getRGB(x,y),"Badge must not cover existing output beside the cursor");
                }
            }finally{panel.dispose();}
        });
    }
    private static int tinted(Color background,Color color){
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{g.setColor(background);g.fillRect(0,0,1,1);g.setComposite(AlphaComposite.SrcOver.derive(.75f));g.setColor(color);g.fillRect(0,0,1,1);}finally{g.dispose();}return image.getRGB(0,0);
    }
    static final class EdgePanel extends AnimatedTerminalPanel {
        com.jediterm.terminal.model.TerminalSelection selection;
        EdgePanel(com.jediterm.terminal.ui.settings.SettingsProvider settings,com.jediterm.terminal.model.TerminalTextBuffer buffer,com.jediterm.terminal.model.StyleState style){super(settings,buffer,style);}
        @Override public boolean isShowing(){return true;}
        @Override public boolean isFocusOwner(){return false;}
        @Override public com.jediterm.terminal.model.TerminalSelection getSelection(){return selection;}
        void history(int origin){myClientScrollOrigin=origin;}
        void ime(String text){var iterator=text==null?null:new java.text.AttributedString(text).getIterator();processInputMethodEvent(new java.awt.event.InputMethodEvent(this,java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,iterator,0,null,null));}
    }
    static EdgePanel edgePanel(com.jediterm.terminal.model.TerminalTextBuffer buffer,com.jediterm.terminal.model.StyleState style){var settings=new PtyTerminalPane.ShedTerminalSettingsProvider(null,new Font("Monospaced",0,16));style.setDefaultStyle(settings.getDefaultStyle());var panel=new EdgePanel(settings,buffer,style);panel.init(new JScrollBar());panel.setSize(panel.getPreferredSize());panel.putClientProperty("shed.caretAnimation",false);panel.setCursorShape(com.jediterm.terminal.CursorShape.STEADY_BLOCK);panel.setCursor(5,1);return panel;}
    @Test void badgesAndTintYieldToSelectionScrollbackImeAndAlternateScreen()throws Exception{
        EditorControlTest.edt(()->{
            var style=new com.jediterm.terminal.model.StyleState();var buffer=new com.jediterm.terminal.model.TerminalTextBuffer(80,24,style);var panel=edgePanel(buffer,style);var owner=new JPanel();
            try(var decoration=new AgentTerminalDecoration(owner,panel,"Codex")){
                decoration.phase("typing");assertFalse(panel.agentCursorBounds().isEmpty());
                panel.selection=new com.jediterm.terminal.model.TerminalSelection(new com.jediterm.core.compatibility.Point(0,0));assertTrue(panel.agentCursorBounds().isEmpty());paintPanel(panel);panel.selection=null;
                panel.history(-1);assertTrue(panel.agentCursorBounds().isEmpty(),"Any scrollback must hide the agent marker, even when the screen cursor would still fit");paintPanel(panel);panel.history(0);
                buffer.useAlternateBuffer(true);assertTrue(panel.agentCursorBounds().isEmpty());paintPanel(panel);buffer.useAlternateBuffer(false);
                panel.ime("拼");assertTrue(panel.agentCursorBounds().isEmpty());paintPanel(panel);panel.ime(null);assertFalse(panel.agentCursorBounds().isEmpty());
                panel.setCursorVisible(false);assertTrue(panel.agentCursorBounds().isEmpty());panel.setCursorVisible(true);assertFalse(panel.agentCursorBounds().isEmpty());
            }finally{panel.dispose();}
            return null;
        });
    }
    @Test void obsoleteDecorationCannotReleaseANewerOwnerOrRestoreAClosedBadge()throws Exception{
        EditorControlTest.edt(()->{
            var owner=new JPanel();var input=new JPanel();owner.setSize(300,100);input.setSize(300,100);
            var old=new AgentTerminalDecoration(owner,input,"Codex");var next=new AgentTerminalDecoration(owner,input,"Claude Code");
            old.close();assertEquals(Boolean.TRUE,input.getClientProperty("shed.agentControl"));assertTrue(AgentTerminalDecoration.active(input));next.close();assertFalse(AgentTerminalDecoration.active(input));assertNull(input.getClientProperty("shed.agentTerminalBadge"));assertNull(owner.getClientProperty("shed.agentTerminalGlow"));assertNull(input.getClientProperty("shed.agentControl"));return null;
        });
    }
    @Test void rightEdgeAndRepeatedScaledPaintingStayBoundedAndRestoreHumanAppearance()throws Exception{
        EditorControlTest.edt(()->{
            var style=new com.jediterm.terminal.model.StyleState();var buffer=new com.jediterm.terminal.model.TerminalTextBuffer(80,24,style);var panel=edgePanel(buffer,style);var owner=new JPanel();String source=buffer.getScreenLines();
            try{
                for(int cycle=0;cycle<100;cycle++){
                    panel.setCursor(cycle%2==0?79:5,1);var before=paintPanel(panel);
                    try(var decoration=new AgentTerminalDecoration(owner,panel,cycle%2==0?"Codex":"Local agent")){
                        decoration.phase(cycle%2==0?"typing":"running");var image=new BufferedImage(panel.getWidth()*2,panel.getHeight()*2,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{g.scale(2,2);panel.paint(g);}finally{g.dispose();}
                        assertEquals(source,buffer.getScreenLines());assertEquals(panel.getPreferredSize(),panel.getSize());
                    }
                    assertArrayEquals(before.getRGB(0,0,before.getWidth(),before.getHeight(),null,0,before.getWidth()),paintPanel(panel).getRGB(0,0,before.getWidth(),before.getHeight(),null,0,before.getWidth()));assertNull(panel.getClientProperty("shed.agentTerminalBadge"));assertNull(owner.getClientProperty("shed.agentTerminalGlow"));
                }
            }finally{panel.dispose();}assertTrue(panel.agentCursorBounds().isEmpty());return null;
        });
    }
    private static BufferedImage paintPanel(AnimatedTerminalPanel panel){
        var image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{panel.paintComponent(g);}finally{g.dispose();}return image;
    }
}
