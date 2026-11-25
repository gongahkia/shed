package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class SmoothScrollPaneUITest {
    private static final class View implements AutoCloseable {
        boolean showing=true;
        final JScrollPane pane=new JScrollPane(){@Override public boolean isShowing(){return showing;}};
        final SmoothScrollPaneUI ui=new SmoothScrollPaneUI();
        View(JComponent content){
            pane.setUI(ui);content.setPreferredSize(new Dimension(2000,4000));pane.setViewportView(content);pane.setSize(320,160);pane.doLayout();
            content.setSize(2000,4000);pane.getViewport().setViewSize(new Dimension(2000,4000));pane.doLayout();
            pane.getVerticalScrollBar().setUnitIncrement(20);pane.getHorizontalScrollBar().setUnitIncrement(20);
        }
        MouseWheelEvent wheel(double rotation,boolean shift){
            var event=new MouseWheelEvent(pane,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),shift?InputEvent.SHIFT_DOWN_MASK:0,10,10,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,(int)rotation,rotation);pane.dispatchEvent(event);return event;
        }
        void frame(long elapsed)throws ReflectiveOperationException{var field=SmoothScrollPaneUI.class.getDeclaredField("started");field.setAccessible(true);ui.advance(field.getLong(ui)+elapsed);}
        void finish()throws ReflectiveOperationException{frame(1_000_000_000L);}
        public void close(){pane.setUI(new javax.swing.plaf.basic.BasicScrollPaneUI());}
    }
    @Test void sourceScrollIsInterpolatedWithoutEditingOrMovingTheHumanCaret()throws Exception {
        EditorControlTest.edt(()->{
            var text=new JTextArea("source line\n".repeat(300));text.select(3,8);var undo=new javax.swing.undo.UndoManager();text.getDocument().addUndoableEditListener(undo);
            try(var view=new View(text)){
                var event=view.wheel(1,false);assertTrue(event.isConsumed());assertTrue(view.ui.animating());assertEquals(0,view.pane.getVerticalScrollBar().getValue());
                view.frame(60_000_000L);int middle=view.pane.getVerticalScrollBar().getValue();assertTrue(middle>0&&middle<60);
                view.finish();assertEquals(60,view.pane.getVerticalScrollBar().getValue());assertFalse(view.ui.animating());assertNull(view.pane.getClientProperty("shed.smoothScrollActive"));
                assertEquals(3,text.getSelectionStart());assertEquals(8,text.getSelectionEnd());assertFalse(undo.canUndo());assertEquals("source line\n".repeat(300),text.getText());
            }return null;
        });
    }
    @Test void preciseHorizontalAndRapidReverseInputStayBounded()throws Exception {
        EditorControlTest.edt(()->{
            try(var view=new View(new JPanel())){
                view.wheel(.25,false);view.finish();assertEquals(15,view.pane.getVerticalScrollBar().getValue());
                view.wheel(.5,true);view.finish();assertEquals(30,view.pane.getHorizontalScrollBar().getValue());assertEquals(15,view.pane.getVerticalScrollBar().getValue());
                view.wheel(10,false);view.wheel(-.25,false);view.finish();assertEquals(0,view.pane.getVerticalScrollBar().getValue());
                view.wheel(100000,false);view.finish();var bar=view.pane.getVerticalScrollBar();assertEquals(bar.getMaximum()-bar.getVisibleAmount(),bar.getValue());
            }return null;
        });
    }
    @Test void manualNavigationDocumentReplacementAndReducedMotionCancelPendingFrames()throws Exception {
        EditorControlTest.edt(()->{
            var text=new JTextArea("line\n".repeat(100));
            try(var view=new View(text)){
                view.wheel(2,false);view.pane.getVerticalScrollBar().setValue(200);view.finish();assertEquals(200,view.pane.getVerticalScrollBar().getValue());assertFalse(view.ui.animating());
                view.wheel(2,false);for(var listener:text.getKeyListeners())listener.keyPressed(new KeyEvent(text,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_DOWN,KeyEvent.CHAR_UNDEFINED));assertFalse(view.ui.animating());
                view.wheel(2,false);text.setDocument(new javax.swing.text.PlainDocument());assertFalse(view.ui.animating());
                view.pane.putClientProperty("shed.caretAnimation",false);view.wheel(1,false);assertFalse(view.ui.animating());
                assertNull(view.pane.getClientProperty("shed.smoothScrollActive"));
            }return null;
        });
    }
    @Test void listsTablesTreesAndRichPreviewsUseTheSameMotion()throws Exception {
        EditorControlTest.edt(()->{
            for(var component:new JComponent[]{new JList<>(new String[200]),new JTable(200,3),new JTree(),new JTextPane(),new JEditorPane("text/html","<html>Preview</html>")}){
                try(var view=new View(component)){view.wheel(1,false);assertTrue(view.ui.animating(),component.getClass().getName());view.finish();assertTrue(view.pane.getVerticalScrollBar().getValue()>0);}
            }return null;
        });
    }
    @Test void nestedViewsForwardWheelAtTheirBoundary()throws Exception {
        EditorControlTest.edt(()->{
            var content=new JPanel(null);
            try(var outer=new View(content);var inner=new View(new JPanel())){
                content.add(inner.pane);inner.pane.setBounds(0,0,200,100);inner.pane.doLayout();
                var bar=inner.pane.getVerticalScrollBar();bar.setValue(bar.getMaximum()-bar.getVisibleAmount());
                assertTrue(inner.wheel(1,false).isConsumed());assertTrue(outer.ui.animating());assertFalse(inner.ui.animating());outer.finish();assertTrue(outer.pane.getVerticalScrollBar().getValue()>0);
            }return null;
        });
    }
    @Test void uninstallReleasesTimersAndBorrowedViewListeners()throws Exception {
        EditorControlTest.edt(()->{
            var text=new JTextArea("line\n".repeat(100));int keys=text.getKeyListeners().length,mouse=text.getMouseListeners().length;
            var view=new View(text);assertEquals(keys+1,text.getKeyListeners().length);assertEquals(mouse+1,text.getMouseListeners().length);
            var callbacks=view.pane.getMouseWheelListeners();view.wheel(1,false);view.close();assertFalse(view.ui.animating());assertEquals(keys,text.getKeyListeners().length);assertEquals(mouse,text.getMouseListeners().length);
            for(var callback:callbacks)assertDoesNotThrow(()->callback.mouseWheelMoved(new MouseWheelEvent(view.pane,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1)));return null;
        });
    }
    @Test void hiddenDisabledAndEmptyViewsDoNotRunAnimation()throws Exception {
        EditorControlTest.edt(()->{
            try(var view=new View(new JPanel())){
                view.pane.setWheelScrollingEnabled(false);view.wheel(1,false);assertFalse(view.ui.animating());assertEquals(0,view.pane.getVerticalScrollBar().getValue());
                view.pane.setWheelScrollingEnabled(true);view.wheel(1,false);view.showing=false;view.pane.dispatchEvent(new HierarchyEvent(view.pane,HierarchyEvent.HIERARCHY_CHANGED,view.pane,null,HierarchyEvent.SHOWING_CHANGED));
                assertFalse(view.ui.animating());view.showing=true;view.wheel(1,false);view.pane.setEnabled(false);view.finish();assertFalse(view.ui.animating());
                view.pane.setEnabled(true);view.pane.setViewportView(new JPanel());view.pane.doLayout();view.wheel(1,false);assertFalse(view.ui.animating());
            }return null;
        });
    }
    @Test void repeatedInputAndViewReplacementDoNotAccumulateListeners()throws Exception {
        EditorControlTest.edt(()->{
            var old=new JTextArea();int before=old.getKeyListeners().length;
            try(var view=new View(old)){
                for(int i=0;i<10;i++)view.wheel(1,false);view.finish();assertEquals(600,view.pane.getVerticalScrollBar().getValue());
                var replacement=new JTextArea();int keys=replacement.getKeyListeners().length;view.pane.setViewportView(replacement);assertEquals(before,old.getKeyListeners().length);assertEquals(keys+1,replacement.getKeyListeners().length);
                view.close();assertEquals(keys,replacement.getKeyListeners().length);
            }return null;
        });
    }
}
