package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Font;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class ReadOnlyComparisonPanelTest {
    private static <T extends java.awt.Component>T find(java.awt.Container parent,Class<T> type,String title){for(var child:parent.getComponents()){if(type.isInstance(child)&&(title==null||child instanceof JButton b&&title.equals(b.getText())))return type.cast(child);if(child instanceof java.awt.Container c){var found=find(c,type,title);if(found!=null)return found;}}return null;}
    @Test void centralPatchIsReadOnlyAndBackAndExportAreExplicit()throws Exception{SwingUtilities.invokeAndWait(()->{
        var back=new java.util.concurrent.atomic.AtomicInteger();var exports=new java.util.ArrayList<String>();var panel=new ReadOnlyComparisonPanel("Diff · Demo.java","@@ -1 +1 @@\n-before\n+after\n",new Font(Font.MONOSPACED,Font.PLAIN,13),back::incrementAndGet,(title,text)->exports.add(text));
        var text=find(panel,ChangeDiffView.class,null);assertFalse(text.isEditable());assertTrue(panel.displayed().contains("+after"));assertEquals(0,back.get());assertTrue(exports.isEmpty());
        find(panel,JButton.class,"Export displayed patch").doClick();assertEquals(panel.displayed(),exports.getFirst());find(panel,JButton.class,"Back to source").doClick();assertEquals(1,back.get());
        String before=panel.displayed();panel.appearance(new Font(Font.MONOSPACED,Font.PLAIN,16));assertEquals(before,panel.displayed());
    });}
}
