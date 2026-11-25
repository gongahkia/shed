package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Font;
import java.awt.Point;
import javax.swing.*;
import org.junit.jupiter.api.Test;

/** Owned headless components only; deferred caret work must not recenter an agent's view. */
class AgentViewportHandoffTest {
    @Test void completedSourceStaysAuthoritativeWhileTheOldPaintWaitsForReplayAndHumanInputCancelsIt()throws Exception {
        EditorControlTest.edt(()->{
            var area=new JTextArea("greet(arg)"){
                @Override public boolean isShowing(){return true;}
                @Override protected void paintComponent(java.awt.Graphics g){super.paintComponent(g);AgentSourceHandoff.paint(this,g);}
            };
            area.setFont(new Font("Monospaced",0,16));area.setSize(600,100);
            var before=SwingComponentCapture.capture(area,0,0,600,100,60_000);
            try(var handoff=AgentSourceHandoff.capture(area)){
                assertNotNull(handoff);area.setText("greetVisitor(arg)");handoff.install();
                var held=SwingComponentCapture.capture(area,0,0,600,100,60_000);
                assertEquals("greetVisitor(arg)",area.getText());assertArrayEquals(before.getRGB(0,0,600,100,null,0,600),held.getRGB(0,0,600,100,null,0,600));
                for(var listener:area.getKeyListeners())listener.keyPressed(new java.awt.event.KeyEvent(area,java.awt.event.KeyEvent.KEY_PRESSED,0,0,java.awt.event.KeyEvent.VK_RIGHT,java.awt.event.KeyEvent.CHAR_UNDEFINED));
                assertNull(area.getClientProperty("shed.agentSourceHandoff"));
            }
            try(var handoff=AgentSourceHandoff.capture(area)){
                area.setText("next source");handoff.install();assertNotNull(area.getClientProperty("shed.agentSourceHandoff"));
                area.append(" changed again");assertNull(area.getClientProperty("shed.agentSourceHandoff"));
            }
            try(var handoff=AgentSourceHandoff.capture(area);var presentation=new AgentEditPresentation(area)){
                area.setText("another source");handoff.install();presentation.showReading(0,5,"Codex");
                assertNull(area.getClientProperty("shed.agentSourceHandoff"));
            }
            return null;
        });
    }
    private static final class View {
        final JTextArea area = new JTextArea("source line\n".repeat(400));
        final JScrollPane scroll = new JScrollPane(area);
        final BlockCaret caret = new BlockCaret();
        View() {
            area.setFont(new Font("Monospaced", 0, 16));area.setCaret(caret);
            scroll.setSize(600, 220);scroll.doLayout();area.setSize(580, 8000);
        }
    }

    @Test void internalCaretUpdatesKeepTheViewportAndHumanNavigationResumesAfterward()throws Exception {
        var view=EditorControlTest.edt(View::new);
        EditorControlTest.edt(()->{
            view.scroll.getViewport().setViewPosition(new Point(0,4000));
            var release=view.caret.holdAutomaticScrolling();
            try{view.area.setCaretPosition(1);}finally{release.run();}
            return null;
        });
        EditorControlTest.edt(()->{
            assertEquals(4000,view.scroll.getViewport().getViewPosition().y,"Deferred caret work must leave the agent viewport alone");
            view.area.setCaretPosition(2);return null;
        });
        EditorControlTest.edt(()->{assertTrue(view.scroll.getViewport().getViewPosition().y<100,"Human caret navigation must reveal its destination normally");view.area.setCaret(new javax.swing.text.DefaultCaret());return null;});
    }

    @Test void reloadRestorationKeepsSelectionDirectionAndViewportThroughNestedHolds()throws Exception {
        var view=EditorControlTest.edt(View::new);
        EditorControlTest.edt(()->{view.area.setCaretPosition(8);view.area.moveCaretPosition(2);return null;});
        EditorControlTest.edt(()->{
            view.scroll.getViewport().setViewPosition(new Point(0,4000));
            var before=view.area.getText();var state=BufferViewState.capture(view.area,view.scroll);
            var outer=view.caret.holdAutomaticScrolling();var inner=view.caret.holdAutomaticScrolling();
            try{
                view.area.setText("prefix\n"+before);
                state.restoreSelectionAfterChanges(view.area,before,view.area.getText());
                state.restoreViewport(new SwingEditorSurface(view.area,view.scroll,()->null));
            }finally{inner.run();outer.run();outer.run();}
            return null;
        });
        EditorControlTest.edt(()->{
            assertEquals(9,view.caret.getDot());assertEquals(15,view.caret.getMark());assertEquals(4000,view.scroll.getViewport().getViewPosition().y);
            view.area.setCaretPosition(0);return null;
        });
        EditorControlTest.edt(()->{assertTrue(view.scroll.getViewport().getViewPosition().y<100);view.area.setCaret(new javax.swing.text.DefaultCaret());return null;});
    }
}
