package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.*;
import javax.swing.text.PlainDocument;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;

class AgentEditPresentationTest {
    @Test void navigationAndTargetsShareAnExaminingLabelAndADownwardPulseDistinctFromReadingAndSearch()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            for(var background:new java.awt.Color[]{java.awt.Color.WHITE,new java.awt.Color(0x202020)})for(String kind:java.util.List.of("navigate","target")){
                var area=new JTextArea(" ".repeat(18)){@Override public boolean isShowing(){return true;}};
                area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,0,48));area.setSize(600,100);area.setBackground(background);area.select(2,6);
                try(var presentation=new AgentEditPresentation(area)){
                    var range=new AgentEditPresentation.Range(0,18);
                    presentation.showReading(0,18,"Codex");var reading=presentation.agentColor();
                    presentation.showIntent(new AgentBufferIntent("search","active","Codex",java.util.List.of(range),0));var search=presentation.agentColor();
                    presentation.showIntent(new AgentBufferIntent("navigate","active","Codex",java.util.List.of(range),0));var examiningColor=presentation.agentColor();
                    presentation.showIntent(new AgentBufferIntent(kind,"active","Codex",java.util.List.of(range),0));assertEquals(examiningColor,presentation.agentColor());
                    assertEquals("Codex examining",area.getClientProperty("shed.agentActivity"));assertEquals("Codex",textField(presentation,"agentProvider"));
                    assertEquals("top-to-bottom",area.getClientProperty("shed.agentDirection"));assertNotEquals(reading,presentation.agentColor());assertNotEquals(search,presentation.agentColor());
                    assertTrue(AccessibilitySupport.meetsTextContrast(presentation.agentColor(),background));
                    var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);
                    for(int i=0;i<5;i++)tick.invoke(presentation);var upper=paint(area);
                    for(int i=0;i<20;i++)tick.invoke(presentation);var lower=paint(area);
                    int height=area.getFontMetrics(area.getFont()).getHeight();
                    assertTrue(strongestTintRow(lower,background,height)>strongestTintRow(upper,background,height),"Navigation band must travel downward");
                    assertEquals(" ".repeat(18),area.getText());assertEquals(2,area.getSelectionStart());assertEquals(6,area.getSelectionEnd());
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            }
        });
    }
    @Test void searchUsesAnUpwardBluePulseAndAnActionLabelWithoutChangingSource()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            for(var background:new java.awt.Color[]{java.awt.Color.WHITE,new java.awt.Color(0x202020)}){
                var area=new JTextArea(" ".repeat(18)){@Override public boolean isShowing(){return true;}};
                area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,0,48));area.setSize(600,100);area.setBackground(background);area.select(2,6);
                var undo=new UndoManager();area.getDocument().addUndoableEditListener(undo);
                try(var presentation=new AgentEditPresentation(area)){
                    presentation.showReading(0,18,"Codex");var readingColor=presentation.agentColor();
                    presentation.showIntent(new AgentBufferIntent("search","active","Codex",java.util.List.of(new AgentEditPresentation.Range(0,18)),0));
                    assertEquals("Codex searching",area.getClientProperty("shed.agentActivity"));assertEquals("bottom-to-top",area.getClientProperty("shed.agentDirection"));
                    assertNotEquals(readingColor,presentation.agentColor());assertTrue(AccessibilitySupport.meetsTextContrast(presentation.agentColor(),background));
                    var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);
                    for(int i=0;i<5;i++)tick.invoke(presentation);var lower=paint(area);
                    for(int i=0;i<20;i++)tick.invoke(presentation);var upper=paint(area);
                    int height=area.getFontMetrics(area.getFont()).getHeight();
                    assertTrue(strongestTintRow(upper,background,height)<strongestTintRow(lower,background,height),"Search band must travel upward");
                    area.putClientProperty("shed.caretAnimation",false);
                    presentation.showIntent(new AgentBufferIntent("search","active","Codex",java.util.List.of(new AgentEditPresentation.Range(0,18)),0));
                    assertArrayEquals(paint(area).getRGB(0,0,600,100,null,0,600),paint(area).getRGB(0,0,600,100,null,0,600));
                    assertEquals(" ".repeat(18),area.getText());assertEquals(2,area.getSelectionStart());assertEquals(6,area.getSelectionEnd());assertFalse(undo.canUndo());
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            }
        });
    }
    private static int strongestTintRow(java.awt.image.BufferedImage image,java.awt.Color background,int height){
        int row=-1,strongest=-1;
        for(int y=2;y<height-2;y++){
            var pixel=new java.awt.Color(image.getRGB(20,y));int distance=Math.abs(pixel.getRed()-background.getRed())+Math.abs(pixel.getGreen()-background.getGreen())+Math.abs(pixel.getBlue()-background.getBlue());
            if(distance>strongest){strongest=distance;row=y;}
        }
        return row;
    }
    @Test void readingHasDistinctPulseAndMarkerAndExpiresWithoutChangingHumanState()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var area=new JTextArea("class Example {}\nsecond line\n"){@Override public boolean isShowing(){return true;}};
            area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,0,16));area.setSize(600,100);area.select(2,6);
            var undo=new UndoManager();area.getDocument().addUndoableEditListener(undo);
            try(var presentation=new AgentEditPresentation(area)){
                presentation.showApplied(0,5);var editing=paint(area);var editingColor=presentation.agentColor();
                presentation.showReading(0,5,"Agent");var reading=paint(area);
                assertEquals("Agent reading",area.getClientProperty("shed.agentActivity"));assertEquals("top-to-bottom",area.getClientProperty("shed.agentDirection"));assertNotEquals(editingColor,presentation.agentColor());
                int changed=0;for(int y=0;y<100;y++)for(int x=0;x<600;x++)if(editing.getRGB(x,y)!=reading.getRGB(x,y))changed++;
                assertTrue(changed>0,"Reading must look different from an applied edit");
                var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);
                tick.invoke(presentation);assertFalse(java.util.Arrays.equals(reading.getRGB(0,0,600,100,null,0,600),paint(area).getRGB(0,0,600,100,null,0,600)),"Reading pulse did not advance");
                for(int i=1;i<120;i++)tick.invoke(presentation);
                var field=AgentEditPresentation.class.getDeclaredField("timer");field.setAccessible(true);assertFalse(((Timer)field.get(presentation)).isRunning());assertNull(area.getClientProperty("shed.agentActivity"));
                area.putClientProperty("shed.caretAnimation",false);presentation.showReading(0,5,"Agent");assertFalse(((Timer)field.get(presentation)).isRunning());
                assertEquals("class Example {}\nsecond line\n",area.getText());assertEquals(2,area.getSelectionStart());assertEquals(6,area.getSelectionEnd());assertFalse(undo.canUndo());
                area.append("edit");assertNull(area.getClientProperty("shed.agentActivity"));
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
    }
    @Test void providerBadgeAndSpinnerScaleWithoutTouchingSourceOrSelection()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            for(int size:new int[]{16,24})for(var background:new java.awt.Color[]{java.awt.Color.WHITE,new java.awt.Color(0x202020)}){
                var area=new JTextArea("x\n"){@Override public boolean isShowing(){return true;}};
                area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,0,size));area.setSize(600,100);area.setBackground(background);area.select(0,1);
                var history=new UndoManager();area.getDocument().addUndoableEditListener(history);
                try(var presentation=new AgentEditPresentation(area)){
                    presentation.showReading(0,1,"OpenCode");
                    int height=area.getFontMetrics(area.getFont()).getHeight(),gap=6;
                    int x=(int)area.modelToView2D(1).getX()+12;
                    var first=paint(area);int logoPixels=0;
                    for(int y=0;y<height;y++)for(int column=x;column<x+height;column++)if(first.getRGB(column,y)!=background.getRGB())logoPixels++;
                    assertTrue(logoPixels>0,"Provider logo is missing");
                    int spinnerX=x+height+gap+area.getFontMetrics(area.getFont()).stringWidth("OpenCode reading")+gap;
                    var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);tick.invoke(presentation);
                    var second=paint(area);int spinnerChanges=0;
                    for(int y=0;y<height;y++)for(int column=spinnerX;column<spinnerX+height;column++)if(first.getRGB(column,y)!=second.getRGB(column,y))spinnerChanges++;
                    assertTrue(spinnerChanges>0,"Circular spinner did not rotate");
                    for(int y=height;y<100;y++)for(int column=x;column<spinnerX+height;column++)assertEquals(background.getRGB(),second.getRGB(column,y),"Badge escaped its text line");
                    assertEquals("x\n",area.getText());assertEquals(0,area.getSelectionStart());assertEquals(1,area.getSelectionEnd());assertFalse(history.canUndo());
                    presentation.showProgress(0,1,"Claude Code","thinking");assertEquals("Claude Code thinking",textField(presentation,"agentName"));assertEquals("Claude Code",textField(presentation,"agentProvider"));
                    presentation.clear();assertEquals("Agent",textField(presentation,"agentProvider"));
                }catch(ReflectiveOperationException|javax.swing.text.BadLocationException failure){throw new AssertionError(failure);}
            }
        });
    }
    @Test void reducedMotionKeepsSpinnerStaticAndNarrowLabelsDoNotCoverSource()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var area=new JTextArea("source\n"){@Override public boolean isShowing(){return true;}};
            area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,0,16));area.setSize(600,100);area.putClientProperty("shed.caretAnimation",false);
            try(var presentation=new AgentEditPresentation(area)){
                presentation.showReading(0,6,"Codex");var first=paint(area);
                var spinnerField=AgentEditPresentation.class.getDeclaredField("spinner");spinnerField.setAccessible(true);
                var spinner=(org.jdesktop.swingx.painter.BusyPainter)spinnerField.get(presentation);
                assertEquals(0,spinner.getFrame());
                assertArrayEquals(first.getRGB(0,0,600,100,null,0,600),paint(area).getRGB(0,0,600,100,null,0,600));
                area.setSize(65,100);var narrow=paint(area);
                presentation.showReading(0,6,"Unknown agent");var unknown=paint(area);
                assertArrayEquals(narrow.getRGB(0,0,65,100,null,0,65),unknown.getRGB(0,0,65,100,null,0,65),"Badge overlapped source in a narrow pane");
                assertEquals("source\n",area.getText());
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
    }
    @Test void activeIntentsAnimateTheBadgeAndOutcomesAndConflictsStayStatic()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var area=new JTextArea("alpha beta\n"){@Override public boolean isShowing(){return true;}};
            area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,0,16));area.setSize(600,100);
            var history=new UndoManager();area.getDocument().addUndoableEditListener(history);area.select(2,4);
            try(var presentation=new AgentEditPresentation(area)){
                var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);
                int badgeX=(int)area.modelToView2D(10).getX()+12;
                for(String kind:AgentBufferIntent.KINDS){
                    if(kind.equals("conflict"))continue;
                    var ranges=java.util.List.of(new AgentEditPresentation.Range(0,5));
                    presentation.showIntent(new AgentBufferIntent(kind,kind.equals("verify")?"checking":"active","Codex",ranges,0));
                    var before=paint(area);tick.invoke(presentation);var after=paint(area);
                    assertTrue(changedPixels(before,after,badgeX)>0,"Active badge did not animate for "+kind);
                    assertEquals("Codex",textField(presentation,"agentProvider"));
                }
                for(String status:new String[]{"passed","failed"}){
                    presentation.showIntent(new AgentBufferIntent("verify",status,"Codex",java.util.List.of(new AgentEditPresentation.Range(0,5)),0));
                    var before=paint(area);tick.invoke(presentation);assertEquals(0,changedPixels(before,paint(area),badgeX),"Completed check badge still spins");
                }
                presentation.showIntent(new AgentBufferIntent("conflict","active","Codex",java.util.List.of(new AgentEditPresentation.Range(0,5)),0));
                var warning=paint(area);tick.invoke(presentation);assertEquals(0,changedPixels(warning,paint(area),badgeX),"Conflict status must not look like ongoing work");
                assertEquals("Codex needs attention",area.getClientProperty("shed.agentActivity"));
                assertEquals("alpha beta\n",area.getText());assertEquals(2,area.getSelectionStart());assertEquals(4,area.getSelectionEnd());assertFalse(history.canUndo());
            }catch(ReflectiveOperationException|javax.swing.text.BadLocationException failure){throw new AssertionError(failure);}
        });
    }
    @Test void deletionBadgePaintsAboveThePreviewAndStopsWhenTheTourFinishes()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var area=new JTextArea("remaining\n"){
                @Override public boolean isShowing(){return true;}
                @Override protected void paintComponent(java.awt.Graphics g){super.paintComponent(g);AgentChangePlayback.paint(this,g);AgentEditPresentation.paintPlaybackBadge(this,g);}
            };
            area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,0,16));area.setBackground(java.awt.Color.WHITE);area.setSize(600,100);
            try(var presentation=new AgentEditPresentation(area)){
                var range=new AgentEditPresentation.Range(0,0);presentation.showApplied(java.util.List.of(range));presentation.setAgentName("Codex deleting","Codex");presentation.moveMarker(0,0);
                try(var playback=new AgentChangePlayback(area,area.getText(),java.util.List.of(new AgentChangePlayback.Edit(range,"removed line of source\n")),presentation::agentColor)){
                    playback.frame(0,.3);var before=paint(area);
                    var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);tick.invoke(presentation);
                    int badgeX=(int)area.modelToView2D(9).getX()+12;
                    assertTrue(changedPixels(before,paint(area),badgeX)>0,"Deletion preview hid the spinner");
                }
                presentation.finishTour();var before=paint(area);
                var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);tick.invoke(presentation);
                int badgeX=(int)area.modelToView2D(9).getX()+12;
                assertEquals(0,changedPixels(before,paint(area),badgeX),"Finished tour badge still spins");
                assertEquals("remaining\n",area.getText());
            }catch(ReflectiveOperationException|javax.swing.text.BadLocationException failure){throw new AssertionError(failure);}
            assertNull(area.getClientProperty("shed.agentPresentation"));
        });
    }
    private static int changedPixels(java.awt.image.BufferedImage before,java.awt.image.BufferedImage after,int start){
        int changed=0;for(int y=0;y<before.getHeight();y++)for(int x=start;x<before.getWidth();x++)if(before.getRGB(x,y)!=after.getRGB(x,y))changed++;return changed;
    }
    private static String textField(AgentEditPresentation presentation,String name)throws ReflectiveOperationException {
        var field=AgentEditPresentation.class.getDeclaredField(name);field.setAccessible(true);return (String)field.get(presentation);
    }
    private static java.awt.image.BufferedImage paint(JTextArea area){
        var image=new java.awt.image.BufferedImage(600,100,java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();try{area.paint(graphics);}finally{graphics.dispose();}return image;
    }
    @Test void firstLineLabelUsesFreeSpaceInsteadOfCoveringSource()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var area=new JTextArea("class Example {}\n");area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,0,16));area.setSize(600,100);
            try(var presentation=new AgentEditPresentation(area)){
                presentation.showApplied(0,1);presentation.setAgentName("Agent");
                var image=new java.awt.image.BufferedImage(600,100,java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();area.paint(graphics);graphics.dispose();
                try{
                    int end=(int)area.modelToView2D(area.getLineEndOffset(0)-1).getX();int color=presentation.agentColor().getRGB(),overlap=0,label=0;
                    for(int y=0;y<area.getFontMetrics(area.getFont()).getHeight();y++)for(int x=20;x<600;x++)if(image.getRGB(x,y)==color){if(x<end)overlap++;else label++;}
                    assertEquals(0,overlap);assertTrue(label>0,"Agent label missing from the free line margin");
                }catch(javax.swing.text.BadLocationException failure){throw new AssertionError(failure);}
            }
        });
    }
    @Test void largeWrappedAndComplexDocumentsDoNotEnterPresentationLayout() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area=new JTextArea("x".repeat(EditorNavigation.MAX_BOUNDARY_UNITS+1));area.setLineWrap(true);
            try(var presentation=new AgentEditPresentation(area)){
                assertFalse(presentation.available());presentation.showPending(0,10);
                assertEquals(0,area.getHighlighter().getHighlights().length);
                area.setLineWrap(false);assertTrue(presentation.available());
                area.getDocument().putProperty("i18n",true);assertFalse(presentation.available());
            }
        });
    }
    @Test void multipleChunksPaintIndependentlyAndAgentColorDiffersFromHuman() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea("first block\nunchanged middle\nlast block\n");
            area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 16)); area.setSize(600, 150);
            var original = new java.awt.image.BufferedImage(600,150,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = original.createGraphics(); area.paint(graphics); graphics.dispose();
            var human = area.getCaret(); area.setCaretColor(new java.awt.Color(0x27815C)); var color = area.getCaretColor();
            try (var presentation = new AgentEditPresentation(area)) {
                assertNotEquals(color, presentation.agentColor());
                presentation.showApplied(java.util.List.of(new AgentEditPresentation.Range(0,5),new AgentEditPresentation.Range(29,33)));
                assertEquals(2,area.getHighlighter().getHighlights().length);
                var decorated = new java.awt.image.BufferedImage(600,150,java.awt.image.BufferedImage.TYPE_INT_RGB);
                graphics = decorated.createGraphics(); area.paint(graphics); graphics.dispose();
                int changed = 0;
                for(int y=0;y<150;y++)for(int x=0;x<600;x++)if(original.getRGB(x,y)!=decorated.getRGB(x,y))changed++;
                assertTrue(changed>0); assertSame(human,area.getCaret());assertEquals(color,area.getCaretColor());
                assertEquals("first block\nunchanged middle\nlast block\n",area.getText());
            }
        });
    }

    @Test void animationExpiresAndReducedMotionCreatesNoRunningTimer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea("bounded change") { @Override public boolean isShowing() { return true; } };
            area.setSize(600,100);
            try (var presentation = new AgentEditPresentation(area)) {
                var field=AgentEditPresentation.class.getDeclaredField("timer");field.setAccessible(true);var timer=(Timer)field.get(presentation);
                presentation.showPending(0,5);assertTrue(timer.isRunning());
                var tick=AgentEditPresentation.class.getDeclaredMethod("advance");tick.setAccessible(true);
                for(int i=0;i<48;i++)tick.invoke(presentation);
                assertFalse(timer.isRunning());assertEquals(0,area.getHighlighter().getHighlights().length);
                area.putClientProperty("shed.caretAnimation",false);presentation.showApplied(0,5);assertFalse(timer.isRunning());
                assertEquals(1,area.getHighlighter().getHighlights().length);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }

    @Test void decorationsPreserveSourceCaretAndHistoryAndCleanUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea("hello world");
            UndoManager history = new UndoManager();
            area.getDocument().addUndoableEditListener(history);
            area.setCaretPosition(3);
            var caret = area.getCaret();
            var color = area.getCaretColor();
            int listeners = area.getHierarchyListeners().length;
            AgentEditPresentation presentation = new AgentEditPresentation(area);
            presentation.showPending(1, 5);
            assertEquals(1, area.getHighlighter().getHighlights().length);
            assertEquals("hello world", area.getText());
            assertSame(caret, area.getCaret());
            assertEquals(color, area.getCaretColor());
            assertEquals(3, area.getCaretPosition());
            assertFalse(history.canUndo());
            area.append("!");
            assertEquals(0, area.getHighlighter().getHighlights().length);
            presentation.showApplied(0, 2);
            area.setDocument(new PlainDocument());
            assertEquals(0, area.getHighlighter().getHighlights().length);
            presentation.close();
            presentation.close();
            assertEquals(listeners, area.getHierarchyListeners().length);
            presentation.showPending(0, 0);
            assertEquals(0, area.getHighlighter().getHighlights().length);
        });
    }
    @Test void disabledAnimationAndExplicitClearRemainDeterministic() throws Exception {
        String old = System.getProperty("shed.caretAnimation");
        try {
            System.setProperty("shed.caretAnimation", "false");
            SwingUtilities.invokeAndWait(() -> {
                JTextArea area = new JTextArea("source");
                try (AgentEditPresentation presentation = new AgentEditPresentation(area)) {
                    assertThrows(IllegalArgumentException.class, () -> presentation.showPending(-1, 4));
                    presentation.showApplied(0, 6);
                    assertEquals(1, area.getHighlighter().getHighlights().length);
                    presentation.clear();
                    assertEquals(0, area.getHighlighter().getHighlights().length);
                }
            });
        } finally {
            if (old == null) System.clearProperty("shed.caretAnimation");
            else System.setProperty("shed.caretAnimation", old);
        }
    }
}
