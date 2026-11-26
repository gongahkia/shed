package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

/** Pure Swing containers on the EDT; no windows, files, subprocesses or editor instance. */
class FileExplorerSidebarTest {
    @Test void togglingRetainsTheExplorerAndItsWidthWithoutReparentingDocuments()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var content=new JPanel();var explorer=new JPanel();var sidebar=new FileExplorerSidebar(content,260,6);
            sidebar.explorer(explorer);assertFalse(sidebar.showingExplorer());assertNull(sidebar.getLeftComponent());
            sidebar.open(true);assertSame(explorer,sidebar.getLeftComponent());sidebar.setDividerLocation(310);
            sidebar.open(false);assertNull(sidebar.getLeftComponent());assertSame(content,sidebar.getRightComponent());assertEquals(0,sidebar.getDividerSize());
            sidebar.open(true);assertSame(explorer,sidebar.getLeftComponent());assertEquals(310,sidebar.getDividerLocation());assertEquals(6,sidebar.getDividerSize());
        });
    }
    @Test void focusModeHidesTemporarilyAndRespectsAnExplicitlyClosedSidebar()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var sidebar=new FileExplorerSidebar(new JPanel(),260,6);var explorer=new JPanel();sidebar.explorer(explorer);sidebar.open(true);sidebar.setDividerLocation(290);
            sidebar.focusMode(true);assertTrue(sidebar.isOpen());assertFalse(sidebar.showingExplorer());assertNull(sidebar.getLeftComponent());
            sidebar.focusMode(false);assertTrue(sidebar.showingExplorer());assertEquals(290,sidebar.getDividerLocation());
            sidebar.open(false);sidebar.focusMode(true);sidebar.focusMode(false);assertFalse(sidebar.showingExplorer());assertNull(sidebar.getLeftComponent());
        });
    }
    @Test void projectReplacementReleasesTheOldSurfaceAndRetainsVisibilityChoice()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var sidebar=new FileExplorerSidebar(new JPanel(),260,6);var first=new JPanel();var second=new JPanel();sidebar.explorer(first);sidebar.open(true);
            sidebar.explorer(second);assertNull(first.getParent());assertSame(second,sidebar.getLeftComponent());assertTrue(sidebar.isOpen());
            sidebar.explorer(null);assertNull(second.getParent());assertFalse(sidebar.showingExplorer());assertEquals(0,sidebar.getDividerSize());
            sidebar.explorer(first);assertTrue(sidebar.showingExplorer());sidebar.open(false);sidebar.explorer(second);assertFalse(sidebar.showingExplorer());
        });
    }
}
