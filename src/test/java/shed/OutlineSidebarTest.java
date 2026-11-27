package shed;
import static org.junit.jupiter.api.Assertions.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
class OutlineSidebarTest {
    @Test void outlineIsIndependentOfExplorerAndFocusMode()throws Exception{
        SwingUtilities.invokeAndWait(()->{var source=new JPanel();var tree=new JPanel();var outline=new JPanel();var sidebar=new FileExplorerSidebar(source,260,6);sidebar.explorer(tree);sidebar.outline(outline,true);assertTrue(sidebar.showingOutline());assertFalse(sidebar.showingExplorer());assertSame(outline,sidebar.getLeftComponent());sidebar.open(true);assertTrue(sidebar.getLeftComponent() instanceof JSplitPane);sidebar.focusMode(true);assertNull(sidebar.getLeftComponent());sidebar.focusMode(false);assertTrue(sidebar.showingExplorer());assertTrue(sidebar.showingOutline());sidebar.outline(outline,false);assertSame(tree,sidebar.getLeftComponent());sidebar.open(false);assertNull(sidebar.getLeftComponent());assertSame(source,sidebar.getRightComponent());});
    }
}
