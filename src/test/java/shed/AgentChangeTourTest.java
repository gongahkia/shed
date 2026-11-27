package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.event.MouseWheelEvent;
import java.util.*;
import javax.swing.*;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;

class AgentChangeTourTest {
    @Test void chunkInsertionHasNoThinTrackingCaretAndRetainsTheExactSource()throws Exception {
        EditorControlTest.edt(()->{
            String source="block line\n".repeat(20);var area=playbackArea(source);var undo=new UndoManager();area.getDocument().addUndoableEditListener(undo);
            try(var playback=new AgentChangePlayback(area,source,List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,source.length()),"")),()->java.awt.Color.GREEN)){
                int offset=playback.frame(0,.5);assertTrue(playback.badgeOnly());assertEquals("chunks",area.getClientProperty("shed.agentAnimation"));assertEquals(0,area.getClientProperty("shed.agentCursorCount"));var caret=area.modelToView2D(offset);var rendered=image(area);assertEquals(java.awt.Color.WHITE.getRGB(),rendered.getRGB((int)caret.getX(),(int)caret.getMaxY()-2),"A chunk must not paint a tracking I-beam or thin caret");assertEquals(source,area.getText());assertFalse(undo.canUndo());
            }return null;
        });
    }
    @Test void inlineDeletionKeepsAPulsingHighlightBehindEveryRemainingWord()throws Exception {
        EditorControlTest.edt(()->{
            for(var background:List.of(java.awt.Color.WHITE,new java.awt.Color(0x202020))){
                String source="String visitor;\nprint(visitor);\n";var area=playbackArea(source);area.setBackground(background);
                var edits=AgentChangePlayback.refineAll(source,new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,source.length()),"String name;\nprint(name);\n"));
                int oldWidth=area.getFontMetrics(area.getFont()).stringWidth("name");
                try(var playback=new AgentChangePlayback(area,source,edits,()->java.awt.Color.GREEN)){
                    playback.frames(0,2,0);var initial=image(area);
                    playback.frames(0,2,.1);var pulsed=image(area);
                    playback.frames(0,2,.3);var shrinking=image(area);
                    for(var edit:edits){
                        var anchor=area.modelToView2D(edit.range().start());int x=(int)anchor.getX(),y=(int)anchor.getMaxY()-2;
                        assertNotEquals(background.getRGB(),initial.getRGB(x+oldWidth/2,y),"Removed word must have a visible highlight behind its glyphs");
                        assertNotEquals(initial.getRGB(x+oldWidth/2,y),pulsed.getRGB(x+oldWidth/2,y),"Deletion highlight must pulse before erasure");
                        assertNotEquals(background.getRGB(),shrinking.getRGB(x+2,y));
                        assertEquals(background.getRGB(),shrinking.getRGB(x+oldWidth-2,y),"Highlight must disappear with the removed glyphs");
                        assertEquals(background.getRGB(),initial.getRGB(x-2,y),"Highlight must stay within the word");
                    }
                    assertEquals(source,area.getText());assertEquals(0,area.getCaretPosition());
                }
            }
            return null;
        });
    }
    @Test void laterEditsKeepTheirOldGlyphsUntilTheirReplayStarts()throws Exception {
        EditorControlTest.edt(()->{
            String source="String visitor;\nprint(visitor);\n";var area=playbackArea(source);
            var edits=AgentChangePlayback.refineAll(source,new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,source.length()),"String name;\nprint(name);\n"));
            int height=area.getFontMetrics(area.getFont()).getHeight();
            try(var playback=new AgentChangePlayback(area,source,edits,()->java.awt.Color.GREEN)){
                playback.frame(0,.9);assertTrue(ink(image(area),height,height*2,true)>0,"A later edit must retain its old glyphs rather than reveal completed text early");
                playback.frame(1,1);assertEquals(0,ink(image(area),height,height*2,true));assertEquals(source,area.getText());
            }
            return null;
        });
    }
    @Test void ordinaryBatchReplacementsEraseAndWriteAtMultipleCursorsWithoutEditingSource()throws Exception {
        EditorControlTest.edt(()->{
            String before="String name;\nprint(name);\n",source="String visitor;\nprint(visitor);\n";var area=playbackArea(source);
            var edits=AgentChangePlayback.refineAll(source,new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,source.length()),before));
            assertEquals(2,edits.size());assertTrue(edits.stream().allMatch(e->e.removed().equals("name")));
            var undo=new UndoManager();area.getDocument().addUndoableEditListener(undo);area.select(1,4);
            var scroll=new JScrollPane(area);scroll.setSize(600,240);scroll.doLayout();area.setSize(580,500);
            try(var presentation=new AgentEditPresentation(area);var tour=new AgentChangeTour(area,presentation,edits.stream().map(AgentChangePlayback.Edit::range).toList(),source,"Codex",()->true,()->{},()->{},edits)){
                tour.start();long now=System.nanoTime();assertEquals(2,area.getClientProperty("shed.agentCursorCount"));assertEquals("deleting",area.getClientProperty("shed.agentAnimation"));
                int height=area.getFontMetrics(area.getFont()).getHeight();var deletion=image(area);
                assertTrue(ink(deletion,0,height,true)>0);assertTrue(ink(deletion,height,height*2,true)>0,"Both old words must be erased together");
                tour.advance(now+1_300_000_000L);assertEquals("typing",area.getClientProperty("shed.agentAnimation"));assertEquals(2,area.getClientProperty("shed.agentCursorCount"));
                var writing=image(area);int first=0,second=0;var color=presentation.agentColor();
                for(int y=0;y<height*2;y++)for(int x=0;x<350;x++)if(writing.getRGB(x,y)==color.getRGB()){if(y<height)first++;else second++;}
                assertTrue(first>0&&second>0,"Both writing cursors must be visible");
                assertEquals(source,area.getText());assertEquals(1,area.getSelectionStart());assertEquals(4,area.getSelectionEnd());assertFalse(undo.canUndo());
                scroll.dispatchEvent(new MouseWheelEvent(scroll,MouseWheelEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));
                assertFalse(tour.running());assertNull(area.getClientProperty("shed.agentCursorCount"));assertNull(area.getClientProperty(AgentChangePlayback.KEY));
            }
            return null;
        });
    }
    @Test void inlineReplayKeepsTheExistingPrefixAndMovesTheTailAsOnlyNewCharactersAppear()throws Exception {
        EditorControlTest.edt(()->{
            String source="greetVisitor(arg)\n";var area=playbackArea(source);
            var edit=AgentChangePlayback.refine(source,new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,source.length()),"greet(arg)\n"));
            assertEquals(new AgentEditPresentation.Range(5,12),edit.range());assertEquals("",edit.removed());
            int cell=area.getFontMetrics(area.getFont()).charWidth('x'),height=area.getFontMetrics(area.getFont()).getHeight();
            try(var playback=new AgentChangePlayback(area,source,List.of(edit),()->java.awt.Color.GREEN)){
                for(double progress:new double[]{0,.5,1}){
                    int revealed=playback.frame(0,progress);var actual=image(area);
                    var expected=image(playbackArea(source.substring(0,revealed)+source.substring(edit.range().end())));
                    for(int y=0;y<height;y++)for(int x=0;x<20*cell;x++){
                        if(x>=revealed*cell&&x<revealed*cell+3)continue;
                        assertEquals(expected.getRGB(x,y),actual.getRGB(x,y),"Inline replay must keep surrounding text together at progress "+progress);
                    }
                }
                assertEquals(source,area.getText());assertEquals(0,area.getCaretPosition());
            }
            String unicode="a😀e\u0301z";
            var safe=AgentChangePlayback.refine(unicode,new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,unicode.length()),"a😀ez"));
            assertTrue(GraphemeBoundary.isBoundary(unicode,safe.range().start()));assertTrue(GraphemeBoundary.isBoundary(unicode,safe.range().end()));
            return null;
        });
    }
    private static JTextArea playbackArea(String source){
        var area=new JTextArea(source){@Override public boolean isShowing(){return true;}@Override protected void paintComponent(java.awt.Graphics g){super.paintComponent(g);AgentChangePlayback.paint(this,g);}};
        area.setFont(new java.awt.Font("Monospaced",0,16));area.setBackground(java.awt.Color.WHITE);area.setForeground(java.awt.Color.BLACK);area.setCaretColor(java.awt.Color.BLUE);area.setSize(600,240);return area;
    }
    private static java.awt.image.BufferedImage image(JTextArea area){var image=new java.awt.image.BufferedImage(600,240,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{area.paint(g);}finally{g.dispose();}return image;}
    private static int ink(java.awt.image.BufferedImage image,int top,int bottom,boolean red){int found=0;for(int y=top;y<bottom;y++)for(int x=5;x<350;x++){var c=new java.awt.Color(image.getRGB(x,y));if(red?c.getRed()>c.getGreen()*1.5&&c.getRed()>c.getBlue()*1.3:c.getRed()<90&&c.getGreen()<90&&c.getBlue()<90)found++;}return found;}
    @Test void smallChangesRevealCharactersWithoutChangingTheDocument()throws Exception {
        EditorControlTest.edt(()->{
            var area=playbackArea("alpha beta\n");var undo=new UndoManager();area.getDocument().addUndoableEditListener(undo);var events=new int[1];
            area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){events[0]++;}public void removeUpdate(javax.swing.event.DocumentEvent e){events[0]++;}public void changedUpdate(javax.swing.event.DocumentEvent e){events[0]++;}});
            var range=new AgentEditPresentation.Range(0,10);
            try(var playback=new AgentChangePlayback(area,area.getText(),List.of(new AgentChangePlayback.Edit(range,"")),()->java.awt.Color.GREEN)){
                assertEquals(0,playback.frame(0,0));int empty=ink(image(area),0,20,false);
                assertEquals(5,playback.frame(0,.5));int half=ink(image(area),0,20,false);
                assertEquals(10,playback.frame(0,1));int full=ink(image(area),0,20,false);
                assertTrue(empty<half&&half<full,"Glyphs must appear progressively");assertEquals("typing",area.getClientProperty("shed.agentAnimation"));assertEquals("left-to-right",area.getClientProperty("shed.agentDirection"));
                assertEquals("alpha beta\n",area.getText());assertEquals(0,area.getCaretPosition());assertEquals(0,events[0]);assertFalse(undo.canUndo());
            }
            assertNull(area.getClientProperty(AgentChangePlayback.KEY));return null;
        });
    }
    @Test void deletionPreviewErasesRightToLeftAndLargeWritesUseWholeLines()throws Exception {
        EditorControlTest.edt(()->{
            var area=playbackArea("remaining\n");int height=area.getFontMetrics(area.getFont()).getHeight();
            try(var playback=new AgentChangePlayback(area,area.getText(),List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,0),"old first line\nold second line\n")),()->java.awt.Color.GREEN)){
                playback.frame(0,0);int before=ink(image(area),height,height*3,true);assertTrue(before>0);
                playback.frame(0,.7);int during=ink(image(area),height,height*3,true);assertTrue(during<before,"Removed glyphs must be erased");assertEquals("right-to-left",area.getClientProperty("shed.agentDirection"));
                playback.frame(0,1);assertEquals(0,ink(image(area),height,height*3,true));assertEquals("remaining\n",area.getText());
            }
            String source="long replacement line\n".repeat(20);area.setText(source);
            try(var playback=new AgentChangePlayback(area,source,List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,source.length()),"")),()->java.awt.Color.GREEN)){
                int revealed=playback.frame(0,.43);assertTrue(revealed>0&&revealed<source.length());assertEquals('\n',source.charAt(revealed-1));assertEquals("chunks",area.getClientProperty("shed.agentAnimation"));
            }
            area.setText("");
            try(var playback=new AgentChangePlayback(area,"",List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,0),"removed whole document\n")),()->java.awt.Color.GREEN)){
                playback.frame(0,0);assertTrue(ink(image(area),0,height,true)>0);assertEquals(0,ink(image(area),height,height*2,true),"Deletion preview must not reserve a caption row");playback.frame(0,1);assertEquals("",area.getText());assertEquals(0,area.getDocument().getLength());
            }
            return null;
        });
    }
    @Test void playbackOffsetsRespectGraphemesAndInterruptionsRevealActualSource()throws Exception {
        EditorControlTest.edt(()->{
            String source="a😀e\u0301z";var area=playbackArea(source);
            try(var playback=new AgentChangePlayback(area,source,List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,source.length()),"")),()->java.awt.Color.GREEN)){
                for(int i=0;i<=20;i++)assertTrue(GraphemeBoundary.isBoundary(source,playback.frame(0,i/20.0)));assertEquals(source,area.getText());
            }
            // exercise the boundary guard independently of the JDK complex-layout flag
            area.getDocument().putProperty("i18n",false);var unchanged=image(area);
            try(var playback=new AgentChangePlayback(area,source,List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(2,3),"")),()->java.awt.Color.GREEN)){
                playback.frame(0,.5);assertArrayEquals(unchanged.getRGB(0,0,600,240,null,0,600),image(area).getRGB(0,0,600,240,null,0,600),"Partial-cluster ranges must not mask glyphs");
            }
            area.setText("alpha beta\n");var scroll=new JScrollPane(area);scroll.setSize(600,240);scroll.doLayout();area.setSize(580,500);
            try(var presentation=new AgentEditPresentation(area);var tour=new AgentChangeTour(area,presentation,List.of(new AgentEditPresentation.Range(0,5)),area.getText(),"Agent",()->true,()->{},()->{},List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,5),"")))){
                tour.start();assertNotNull(area.getClientProperty(AgentChangePlayback.KEY));
                scroll.dispatchEvent(new MouseWheelEvent(scroll,MouseWheelEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));
                assertNull(area.getClientProperty(AgentChangePlayback.KEY));assertFalse(tour.running());tour.advance(System.nanoTime()+9_000_000_000L);assertNull(area.getClientProperty(AgentChangePlayback.KEY));assertEquals("alpha beta\n",area.getText());
            }
            area.putClientProperty("shed.caretAnimation",false);
            try(var presentation=new AgentEditPresentation(area);var tour=new AgentChangeTour(area,presentation,List.of(new AgentEditPresentation.Range(0,5)),area.getText(),"Agent",()->true,()->{},()->{},List.of(new AgentChangePlayback.Edit(new AgentEditPresentation.Range(0,5),"")))){
                tour.start();assertFalse(tour.running());assertNull(area.getClientProperty(AgentChangePlayback.KEY));assertEquals("alpha beta\n",area.getText());
            }
            return null;
        });
    }
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
    @Test void completedReplayHoldsBrieflyAndHumanInputStillCancelsImmediately()throws Exception {
        EditorControlTest.edt(()->{
            var area=playbackArea("alpha beta\n");var scroll=new JScrollPane(area);scroll.setSize(600,240);scroll.doLayout();area.setSize(580,500);
            var range=new AgentEditPresentation.Range(0,5);var edits=List.of(new AgentChangePlayback.Edit(range,""));
            for(boolean cancel:new boolean[]{false,true})try(var presentation=new AgentEditPresentation(area);var tour=new AgentChangeTour(area,presentation,List.of(range),area.getText(),"Codex",()->true,()->{},()->{},edits)){
                tour.start();long now=System.nanoTime();tour.advance(now+1_050_000_000L);
                assertTrue(tour.running(),"Completed writing must remain visible during its finishing hold");
                assertNotNull(area.getClientProperty(AgentChangePlayback.KEY));
                assertEquals("alpha beta\n",area.getText());
                if(cancel){scroll.dispatchEvent(new MouseWheelEvent(scroll,MouseWheelEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));assertFalse(tour.running());assertNull(area.getClientProperty(AgentChangePlayback.KEY));}
                tour.advance(now+1_500_000_000L);assertFalse(tour.running());assertNull(area.getClientProperty(AgentChangePlayback.KEY));
            }
            return null;
        });
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
    @Test void appendedBlockRevealsEofAndStaysVisibleAfterTourWithoutMovingHumanCaret()throws Exception {
        EditorControlTest.edt(()->{
            String before="// demonstration line\n".repeat(240);
            String appended="\npublic class Demo {\n"+"    public static void placeholder() {}\n".repeat(5)+"}\n";
            String source=before+appended;
            var area=new JTextArea(source){@Override public boolean isShowing(){return true;}};
            area.setFont(new java.awt.Font("Monospaced",0,16));
            var scroll=new JScrollPane(area);scroll.setSize(500,200);scroll.doLayout();area.setSize(area.getPreferredSize());
            var undo=new UndoManager();area.getDocument().addUndoableEditListener(undo);area.select(2,6);
            scroll.getViewport().setViewPosition(new java.awt.Point(0,0));
            try(var presentation=new AgentEditPresentation(area);
                    var tour=new AgentChangeTour(area,presentation,List.of(new AgentEditPresentation.Range(before.length(),source.length())),source,"Agent",()->true,()->fail("Unexpected pause"),()->{})){
                tour.start();long now=System.nanoTime();tour.advance(now+350_000_000L);
                assertTrue(scroll.getViewport().getViewPosition().y>1000);
                tour.advance(now+1_100_000_000L);assertFalse(tour.running());
                var eof=area.modelToView2D(source.length());var visible=scroll.getViewport().getViewRect();
                assertNotNull(eof);assertTrue(visible.contains(eof.getX(),eof.getY()),"EOF must remain visible after the appended block finishes");
                var settled=scroll.getViewport().getViewPosition();tour.advance(now+3_000_000_000L);
                assertEquals(settled,scroll.getViewport().getViewPosition());
            }
            assertEquals(source,area.getText());assertEquals(2,area.getSelectionStart());assertEquals(6,area.getSelectionEnd());assertFalse(undo.canUndo());
            return null;
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
