package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.event.MouseWheelEvent;
import java.util.*;
import javax.swing.*;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;

class AgentChangeTourTest {
    private static final class View implements AutoCloseable {
        final String source=java.util.stream.IntStream.range(0,200).mapToObj(i->"line "+i+"\n").collect(java.util.stream.Collectors.joining());
        final JTextArea area=new JTextArea(source){@Override public boolean isShowing(){return true;}};
        final JScrollPane scroll=new JScrollPane(area);
        final AgentEditPresentation presentation;
        final List<AgentEditPresentation.Range> ranges;
        View(){
            area.setFont(new java.awt.Font("Monospaced",0,16));scroll.setSize(500,200);scroll.doLayout();area.setSize(480,6000);
            presentation=new AgentEditPresentation(area);ranges=List.of(range(5),range(100),range(160));
        }
        AgentEditPresentation.Range range(int line){int start=source.indexOf("line "+line+"\n");return new AgentEditPresentation.Range(start,start+5);}
        public void close(){presentation.close();}
    }
    @Test void toursSeparatedBlocksWithoutMovingHumanSelectionOrEditingSource()throws Exception {
        EditorControlTest.edt(()->{
            try(var view=new View()){
                var undo=new UndoManager();view.area.getDocument().addUndoableEditListener(undo);view.area.select(2,6);
                int keys=view.area.getKeyListeners().length;var finished=new int[1];
                try(var tour=new AgentChangeTour(view.area,view.presentation,view.ranges,view.source,"Codex",()->true,()->fail("Unexpected pause"),()->finished[0]++)){
                    tour.start();long now=System.nanoTime();tour.advance(now+1_100_000_000L);tour.advance(now+1_450_000_000L);
                    assertTrue(view.scroll.getViewport().getViewPosition().y>1000);assertEquals(2,view.area.getClientProperty("shed.agentTour"));
                    tour.advance(now+2_200_000_000L);tour.advance(now+2_550_000_000L);assertEquals(3,view.area.getClientProperty("shed.agentTour"));
                    tour.advance(now+3_300_000_000L);assertFalse(tour.running());assertNull(view.area.getClientProperty("shed.agentTour"));
                }
                assertEquals(1,finished[0]);assertEquals(keys,view.area.getKeyListeners().length);assertEquals(view.source,view.area.getText());assertEquals(2,view.area.getSelectionStart());assertEquals(6,view.area.getSelectionEnd());assertFalse(undo.canUndo());
            }return null;
        });
    }
    @Test void manualScrollingCancelsAndCannotBeOverwrittenByLateFrames()throws Exception {
        EditorControlTest.edt(()->{
            try(var view=new View()){
                var paused=new int[1];try(var tour=new AgentChangeTour(view.area,view.presentation,view.ranges,view.source,"Codex",()->true,()->paused[0]++,()->{})){
                    tour.start();view.scroll.dispatchEvent(new MouseWheelEvent(view.scroll,MouseWheelEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));
                    var position=view.scroll.getViewport().getViewPosition();tour.advance(System.nanoTime()+2_000_000_000L);
                    assertEquals(position,view.scroll.getViewport().getViewPosition());assertFalse(tour.running());assertEquals(1,paused[0]);assertEquals(0,view.area.getHighlighter().getHighlights().length);
                }
            }return null;
        });
    }
    @Test void reducedMotionAndDocumentReplacementReleaseTheTour()throws Exception {
        EditorControlTest.edt(()->{
            try(var view=new View()){
                view.area.putClientProperty("shed.caretAnimation",false);
                try(var tour=new AgentChangeTour(view.area,view.presentation,view.ranges,view.source,"Codex",()->true,()->{},()->{})){tour.start();assertFalse(tour.running());assertNull(view.area.getClientProperty("shed.agentTour"));}
                view.area.putClientProperty("shed.caretAnimation",true);
                try(var tour=new AgentChangeTour(view.area,view.presentation,view.ranges,view.source,"Codex",()->true,()->{},()->{})){
                    tour.start();view.area.setDocument(new javax.swing.text.PlainDocument());tour.advance(System.nanoTime());assertFalse(tour.running());
                }
            }return null;
        });
    }
    @Test void finitePulsesAndPathMarksDoNotRetainRemovedRows()throws Exception {
        EditorControlTest.edt(()->{
            var showing=new boolean[]{true};var owner=new JPanel(){@Override public boolean isShowing(){return showing[0];}};
            try(var pulse=new ChangePulse(owner)){pulse.flash(Set.of("one"));assertTrue(pulse.running());for(int i=0;i<30;i++)pulse.advance();assertFalse(pulse.running());assertEquals(0,pulse.strength("one"));pulse.flash(Set.of("two"));showing[0]=false;pulse.advance();assertFalse(pulse.running());}
            var root=java.nio.file.Path.of("/synthetic");var changes=List.of(new ExternalChangeSnapshot.Change("R","new/a.txt","old/a.txt","a","b",List.of()));
            var marks=FileChangeMarks.from(root,changes);assertTrue(marks.get(root.resolve("old/a.txt")).removed());assertEquals("R",marks.get(root.resolve("new/a.txt")).code());assertTrue(FileChangeMarks.recent(root,List.of("new/a.txt")).contains(root.toString()));
            return null;
        });
    }
}
