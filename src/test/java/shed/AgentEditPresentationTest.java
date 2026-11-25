package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.*;
import javax.swing.text.PlainDocument;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;

class AgentEditPresentationTest {
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
