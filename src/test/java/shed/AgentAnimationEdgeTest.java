package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;

/** Bounded owned components and manually advanced timers; no native process or desktop. */
class AgentAnimationEdgeTest {
    static final String SOURCE="source line\n".repeat(100);
    static final class View implements AutoCloseable {
        final JTextArea area=new JTextArea(SOURCE){@Override public boolean isShowing(){return true;}@Override protected void paintComponent(Graphics g){super.paintComponent(g);AgentSourceHandoff.paint(this,g);AgentChangePlayback.paint(this,g);}};
        final JScrollPane scroll=new JScrollPane(area);
        final AgentEditPresentation presentation;
        View(){area.setFont(new Font("Monospaced",0,16));area.setBackground(Color.WHITE);area.setForeground(Color.BLACK);scroll.setSize(600,220);scroll.doLayout();area.setSize(1100,2200);presentation=new AgentEditPresentation(area);}
        AgentChangeTour tour(int start,int end,java.util.function.BooleanSupplier current){var range=new AgentEditPresentation.Range(start,end);return new AgentChangeTour(area,presentation,List.of(range),area.getText(),"Agent",current,()->{},()->{},List.of(new AgentChangePlayback.Edit(range,"")));}
        public void close(){presentation.close();}
    }
    static int[] listeners(View v){return new int[]{v.area.getKeyListeners().length,v.area.getMouseListeners().length,v.area.getFocusListeners().length,v.area.getPropertyChangeListeners().length,v.area.getHierarchyListeners().length,((AbstractDocument)v.area.getDocument()).getDocumentListeners().length,v.scroll.getMouseWheelListeners().length,v.scroll.getViewport().getChangeListeners().length,v.scroll.getVerticalScrollBar().getMouseListeners().length};}
    static boolean timer(Object owner,String name)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return ((javax.swing.Timer)f.get(owner)).isRunning();}
    static BufferedImage paint(JTextArea area,int scale){var visible=area.getVisibleRect();var image=new BufferedImage(visible.width*scale,visible.height*scale,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{g.scale(scale,scale);g.translate(-visible.x,-visible.y);g.clip(visible);area.paint(g);}finally{g.dispose();}return image;}
    @Test void staleTourCannotRetireTheReplacementTour()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View();var old=v.tour(300,310,()->true);var next=v.tour(600,610,()->true)){
            old.start();next.start();Object replay=v.area.getClientProperty(AgentChangePlayback.KEY);old.advance(System.nanoTime());
            assertFalse(old.running());assertTrue(next.running());assertSame(replay,v.area.getClientProperty(AgentChangePlayback.KEY));assertNotNull(v.area.getClientProperty("shed.agentTour"),"An obsolete timer must not remove the new tour's ownership marker");
            next.advance(System.nanoTime()+500_000_000L);assertNotNull(v.area.getClientProperty(AgentChangePlayback.KEY));assertEquals(SOURCE,v.area.getText());
        }return null;});
    }
    @Test void readSupersedesReplayAndLateFramesCannotMoveItsViewportOrClearIt()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View();var old=v.tour(900,910,()->true)){
            old.start();v.presentation.showReading(0,10,"Other agent");v.scroll.getViewport().setViewPosition(new Point(0,30));long version=v.presentation.version();
            old.advance(System.nanoTime()+2_000_000_000L);old.close();assertFalse(old.running());assertEquals(version,v.presentation.version());assertEquals("Agent reading",v.area.getClientProperty("shed.agentActivity"));assertEquals(30,v.scroll.getViewport().getViewPosition().y);assertNull(v.area.getClientProperty(AgentChangePlayback.KEY));
        }return null;});
    }
    @Test void editsAndBufferReplacementCancelWithoutExtraSourceOrUndoChanges()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View()){
            var undo=new UndoManager();v.area.getDocument().addUndoableEditListener(undo);v.area.select(2,6);
            try(var tour=v.tour(300,310,()->true)){tour.start();v.area.insert("human ",0);tour.advance(System.nanoTime()+2_000_000_000L);assertFalse(tour.running());assertEquals("human "+SOURCE,v.area.getText());undo.undo();assertEquals(SOURCE,v.area.getText());assertFalse(undo.canUndo());}
            try(var tour=v.tour(300,310,()->true)){tour.start();var original=v.area.getDocument();v.area.setDocument(new javax.swing.text.PlainDocument());v.area.append("other buffer");tour.advance(System.nanoTime()+2_000_000_000L);assertFalse(tour.running());assertEquals("other buffer",v.area.getText());assertEquals(SOURCE,original.getText(0,original.getLength()));assertNull(v.area.getClientProperty(AgentChangePlayback.KEY));}
        }return null;});
    }
    @Test void paneContextLossAndCloseRejectQueuedFramesWithoutTouchingTheSurvivor()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View()){
            var current=new AtomicBoolean(true);var survivor=new JTextArea("survivor");
            try(var tour=v.tour(300,310,current::get)){tour.start();current.set(false);v.scroll.getViewport().setViewPosition(new Point(0,20));tour.advance(System.nanoTime()+5_000_000_000L);assertFalse(tour.running());assertEquals(20,v.scroll.getViewport().getViewPosition().y);assertEquals("survivor",survivor.getText());}
            var pane=new EditorPane(v.area,null,v.scroll,new SearchManager(v.area));pane.setBuffer(FileBuffer.createScratch("fixture",SOURCE));v.area.setDocument(pane.getBuffer().getDocument());
            try(var tour=v.tour(300,310,()->!pane.isDisposed())){tour.start();pane.disposeSourceView();tour.advance(System.nanoTime()+5_000_000_000L);assertFalse(tour.running());assertNull(v.area.getClientProperty(AgentChangePlayback.KEY));assertFalse(v.area.isEditable());assertEquals("survivor",survivor.getText());}
        }return null;});
    }
    @Test void handoffRejectsStaleGeometryAndReleasesPaintOnThemeChanges()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View()){
            try(var frame=AgentSourceHandoff.capture(v.area)){v.area.setText("new source");frame.install();assertNotNull(v.area.getClientProperty("shed.agentSourceHandoff"));v.area.setBackground(new Color(0x202020));assertNull(v.area.getClientProperty("shed.agentSourceHandoff"),"An old light-theme image must not obscure a dark-theme editor");}
            try(var frame=AgentSourceHandoff.capture(v.area)){v.area.setText("newer source");frame.install();v.area.setForeground(Color.WHITE);assertNull(v.area.getClientProperty("shed.agentSourceHandoff"));}
            try(var frame=AgentSourceHandoff.capture(v.area)){v.scroll.setSize(450,160);v.scroll.doLayout();frame.install();assertNull(v.area.getClientProperty("shed.agentSourceHandoff"));}
        }return null;});
    }
    @Test void pendingCapturesRejectFontThemeAndWrapChangesBeforeInstallation()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View()){
            for(int change=0;change<4;change++)try(var frame=AgentSourceHandoff.capture(v.area)){
                if(change==0)v.area.setFont(v.area.getFont().deriveFont(18f));else if(change==1)v.area.setForeground(Color.RED);else if(change==2)v.area.setBackground(Color.GRAY);else v.area.setLineWrap(true);
                frame.install();assertNull(v.area.getClientProperty("shed.agentSourceHandoff"));assertFalse(timer(frame,"expiry"));
            }
        }return null;});
    }
    @Test void highlighterReplacementAndUiReinstallationRetireOnlyOwnedTags()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View()){
            var undo=new UndoManager();v.area.getDocument().addUndoableEditListener(undo);v.area.setCaret(new BlockCaret());v.area.select(2,6);v.presentation.showReading(12,20,"Codex");paint(v.area,1);
            var replacement=new javax.swing.text.DefaultHighlighter();v.area.setHighlighter(replacement);
            assertNull(v.area.getClientProperty("shed.agentActivity"));assertFalse(timer(v.presentation,"timer"));
            Object foreign=replacement.addHighlight(0,2,javax.swing.text.DefaultHighlighter.DefaultPainter);v.presentation.showApplied(12,20);v.presentation.clear();assertEquals(1,replacement.getHighlights().length);assertSame(foreign,replacement.getHighlights()[0]);
            v.area.setHighlighter(new javax.swing.plaf.basic.BasicTextUI.BasicHighlighter());v.presentation.showReading(12,20,"Codex");paint(v.area,1);v.area.updateUI();v.presentation.clear();assertFalse(timer(v.presentation,"timer"));assertEquals(SOURCE,v.area.getText());assertEquals(2,v.area.getSelectionStart());assertEquals(6,v.area.getSelectionEnd());assertFalse(undo.canUndo());v.area.setCaret(new javax.swing.text.DefaultCaret());
        }return null;});
    }
    @Test void geometryFontThemeHorizontalScrollAndScaledPaintingKeepRealCoordinates()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View()){
            String source="prefix "+"addition ".repeat(15)+"tail\n";v.area.setText(source);v.area.select(1,5);var undo=new UndoManager();v.area.getDocument().addUndoableEditListener(undo);
            try(var tour=v.tour(7,source.length()-5,()->true)){
                tour.start();long now=System.nanoTime();
                for(int size:new int[]{12,20,16}){v.area.setFont(new Font("Monospaced",0,size));v.scroll.setSize(400+size*5,130+size);v.scroll.doLayout();v.area.setSize(1800,700);v.scroll.getViewport().setViewPosition(new Point(100,0));v.area.setBackground(size==20?new Color(0x202020):Color.WHITE);v.area.setForeground(size==20?Color.WHITE:Color.BLACK);tour.advance(now+700_000_000L);assertEquals(100,v.scroll.getViewport().getViewPosition().x);assertTrue(v.scroll.getViewport().getViewPosition().y>=0);paint(v.area,1);paint(v.area,2);var point=v.area.modelToView2D(8);assertEquals(8,v.area.viewToModel2D(new java.awt.geom.Point2D.Double(point.getX()+.1,point.getCenterY())));}
                assertEquals(source,v.area.getText());assertEquals(1,v.area.getSelectionStart());assertEquals(5,v.area.getSelectionEnd());assertFalse(undo.canUndo());
            }
            v.area.setLineWrap(true);v.area.setSize(480,700);var expected=paint(v.area,1);try(var playback=new AgentChangePlayback(v.area,source,List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(7,15),"")),()->Color.GREEN)){
                playback.frame(0,.5);var actual=paint(v.area,1);assertArrayEquals(expected.getRGB(0,0,expected.getWidth(),expected.getHeight(),null,0,expected.getWidth()),actual.getRGB(0,0,actual.getWidth(),actual.getHeight(),null,0,actual.getWidth()),"Wrapped fallback must paint actual source");
            }
        }return null;});
    }
    @Test void emptyEofSizeAndComplexLayoutBoundariesRemainExplicit()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View()){
            v.area.setText("");try(var tour=v.tour(0,0,()->true)){tour.start();tour.advance(System.nanoTime()+2_000_000_000L);paint(v.area,2);assertEquals("",v.area.getText());}
            v.area.setText("x".repeat(65536));try(var tour=v.tour(65536,65536,()->true)){tour.start();tour.advance(System.nanoTime()+2_000_000_000L);assertEquals(65536,v.area.getDocument().getLength());}
            assertThrows(IllegalArgumentException.class,()->v.tour(65536,65537,()->true));v.area.append("x");assertThrows(IllegalArgumentException.class,()->v.tour(0,1,()->true));
            v.area.setText("x".repeat(EditorNavigation.MAX_BOUNDARY_UNITS));v.area.setLineWrap(true);assertTrue(v.presentation.available());v.area.append("x");assertFalse(v.presentation.available());try(var tour=v.tour(0,1,()->true)){tour.start();assertFalse(tour.running());}
            v.area.setLineWrap(false);v.area.getDocument().putProperty("i18n",true);assertFalse(v.presentation.available());
            v.area.setText("a😀e\u0301 אבג العربية क्ष");assertTrue(v.presentation.available());var expected=paint(v.area,1);try(var playback=new AgentChangePlayback(v.area,v.area.getText(),List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,v.area.getDocument().getLength()),"")),()->Color.GREEN)){playback.frame(0,.5);var actual=paint(v.area,1);assertArrayEquals(expected.getRGB(0,0,expected.getWidth(),expected.getHeight(),null,0,expected.getWidth()),actual.getRGB(0,0,actual.getWidth(),actual.getHeight(),null,0,actual.getWidth()));}
        }return null;});
    }
    @Test void repeatedReplayInterruptionAndImageExpiryRestoreAllOwnedResources()throws Exception{
        EditorControlTest.edt(()->{try(var v=new View()){
            int[] baseline=listeners(v);var expiry=AgentSourceHandoff.class.getDeclaredField("expiry");expiry.setAccessible(true);
            for(int cycle=0;cycle<100;cycle++){
                try(var tour=v.tour(12,20,()->true)){tour.start();tour.advance(System.nanoTime()+650_000_000L);v.scroll.dispatchEvent(new MouseWheelEvent(v.scroll,MouseWheelEvent.MOUSE_WHEEL,0,0,1,1,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,1));assertFalse(tour.running());tour.advance(System.nanoTime()+20_000_000_000L);}
                try(var frame=AgentSourceHandoff.capture(v.area)){frame.install();var clock=(javax.swing.Timer)expiry.get(frame);for(var listener:clock.getActionListeners())listener.actionPerformed(new ActionEvent(clock,0,"bounded expiry"));assertFalse(clock.isRunning());assertNull(v.area.getClientProperty("shed.agentSourceHandoff"));}
                assertNull(v.area.getClientProperty(AgentChangePlayback.KEY));assertNull(v.area.getClientProperty("shed.agentTour"));assertNull(v.area.getClientProperty("shed.agentTourOwner"));assertFalse(timer(v.presentation,"timer"));assertArrayEquals(baseline,listeners(v));assertEquals(SOURCE,v.area.getText());
            }
        }return null;});
    }
}
