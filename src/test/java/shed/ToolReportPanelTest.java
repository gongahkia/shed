package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

/** Pure styled/container rendering; export is a synthetic callback, never disk or a source document. */
class ToolReportPanelTest {
    static <T extends java.awt.Component> T find(java.awt.Container parent,Class<T> type,String title){for(var child:parent.getComponents()){if(type.isInstance(child)&&(title==null||child instanceof JButton b&&title.equals(b.getText())))return type.cast(child);if(child instanceof java.awt.Container container){var found=find(container,type,title);if(found!=null)return found;}}return null;}
    @Test void reportIsReadOnlyAndExportRequiresAnExplicitAction()throws Exception{SwingUtilities.invokeAndWait(()->{
        var exported=new java.util.ArrayList<String>();var source=new JTextArea("human source");var panel=new ToolReportPanel(new JPanel(),"Tasks",(title,text)->exported.add(title+":"+text));
        panel.show("Task output","line 1\nline 2");assertTrue(exported.isEmpty());assertFalse(find(panel,JTextArea.class,null).isEditable());assertEquals("human source",source.getText());
        find(panel,JButton.class,"Export displayed text").doClick();assertEquals(java.util.List.of("Task output:line 1\nline 2"),exported);panel.primary();panel.show("Next output","other");assertEquals("other",panel.displayed());
    });}
    @Test void boundedReportDoesNotSplitGraphemesAndClearlyMarksTruncation()throws Exception{SwingUtilities.invokeAndWait(()->{
        var panel=new ToolReportPanel(new JPanel(),"Language",(a,b)->fail("No implicit export"));panel.show("<html>literal title","x".repeat(ToolReportPanel.MAX_TEXT-1)+"😀tail");assertTrue(panel.displayed().contains("Report shortened"));assertFalse(panel.displayed().contains("\ud83d"));assertTrue(panel.displayed().length()<ToolReportPanel.MAX_TEXT+100);
    });}
}
