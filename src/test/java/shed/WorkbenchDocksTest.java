package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

/** In-memory EDT-owned containers only; no frames, filesystem or processes. */
class WorkbenchDocksTest {
    private WorkbenchDocks fixture(){var result=new WorkbenchDocks(new JPanel(),new JPanel(),new JPanel());result.setSize(1200,900);result.doLayout();result.bottomSplit().doLayout();return result;}
    @Test void rightAndBottomVisibilityAndOwnershipAreIndependent()throws Exception{SwingUtilities.invokeAndWait(()->{
        var docks=fixture();var source=docks.bottomSplit().getTopComponent();var right=docks.getRightComponent();assertFalse(docks.rightVisible());assertFalse(docks.bottomVisible());
        docks.showRight(true);docks.showBottom(true);docks.setDividerLocation(740);docks.bottomSplit().setDividerLocation(550);var state=docks.snapshot();
        docks.showBottom(false);assertTrue(docks.rightVisible());assertSame(source,docks.bottomSplit().getTopComponent());assertSame(right,docks.getRightComponent());
        docks.showBottom(true);assertEquals(state.get("bottomHeight"),docks.snapshot().get("bottomHeight"));
        docks.showRight(false);assertTrue(docks.bottomVisible());docks.showRight(true);assertEquals(state.get("rightWidth"),docks.snapshot().get("rightWidth"));
    });}
    @Test void focusAndSessionSnapshotsRestoreBothRegionsAndDraggedSizes()throws Exception{SwingUtilities.invokeAndWait(()->{
        var docks=fixture();docks.showRight(true);docks.showBottom(true);docks.setDividerLocation(700);docks.bottomSplit().setDividerLocation(600);var state=docks.snapshot();
        docks.showRight(false);docks.showBottom(false);docks.restore(state);assertEquals(state,docks.snapshot());
        var other=fixture();other.restore(state);assertEquals(state,other.snapshot());assertEquals(JSplitPane.HORIZONTAL_SPLIT,other.getOrientation());assertEquals(JSplitPane.VERTICAL_SPLIT,other.bottomSplit().getOrientation());
    });}
    @Test void denseToolsGrowAnAutomaticBottomDockButPreserveManualSizing()throws Exception{SwingUtilities.invokeAndWait(()->{
        var docks=fixture();docks.showBottom(true);docks.preferBottomHeight(360);int initial=docks.bottomSplit().getDividerLocation();docks.preferBottomHeight(520);assertTrue(docks.bottomSplit().getDividerLocation()<=initial-150);
        docks.bottomSplit().setDividerLocation(500);int manual=docks.bottomSplit().getDividerLocation();docks.preferBottomHeight(640);assertEquals(manual,docks.bottomSplit().getDividerLocation());
    });}
    @Test void hiddenRegionsRemainHiddenAndUntrustedDimensionsAreBounded()throws Exception{SwingUtilities.invokeAndWait(()->{
        var docks=fixture();docks.restore(java.util.Map.of("rightWidth",Integer.MAX_VALUE,"bottomHeight",-9,"bottomVisible",true));assertFalse(docks.rightVisible());assertTrue(docks.bottomVisible());assertEquals(4096,docks.snapshot().get("rightWidth"));assertEquals(80,docks.snapshot().get("bottomHeight"));
        docks.restore(java.util.Map.of());assertFalse(docks.bottomVisible());assertFalse(docks.rightVisible());
    });}
}
