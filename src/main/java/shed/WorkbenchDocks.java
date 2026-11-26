package shed;

import java.awt.Dimension;
import java.util.Map;
import javax.swing.*;

/** Independent right and bottom regions; hiding never closes a tool or its work. */
final class WorkbenchDocks extends JSplitPane {
    private final JSplitPane bottom;
    private int rightWidth=400,bottomHeight=260,divider=6;
    private boolean changing,automaticBottom=true;

    WorkbenchDocks(JComponent source,JComponent bottomTools,JComponent rightTools){
        super(HORIZONTAL_SPLIT);setName("workbench.docks");
        bottom=new JSplitPane(VERTICAL_SPLIT,source,bottomTools);bottom.setResizeWeight(1);bottom.setContinuousLayout(true);bottom.setBorder(BorderFactory.createEmptyBorder());
        setLeftComponent(bottom);setRightComponent(rightTools);setResizeWeight(1);setContinuousLayout(true);setBorder(BorderFactory.createEmptyBorder());
        bottomTools.setMinimumSize(new Dimension(0,80));rightTools.setMinimumSize(new Dimension(180,0));
        bottom.addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY,e->{if(!changing&&bottomVisible()&&bottom.getHeight()>0){bottomHeight=Math.max(80,bottom.getHeight()-bottom.getDividerLocation()-bottom.getDividerSize());automaticBottom=false;}});
        addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY,e->{if(!changing&&rightVisible()&&getWidth()>0)rightWidth=Math.max(180,getWidth()-getDividerLocation()-getDividerSize());});
        showBottom(false);showRight(false);
    }
    JSplitPane bottomSplit(){return bottom;}
    boolean bottomVisible(){return bottom.getBottomComponent().isVisible();}
    boolean rightVisible(){return getRightComponent().isVisible();}
    void install(JComponent bottomTools,JComponent rightTools){bottom.setBottomComponent(bottomTools);setRightComponent(rightTools);showBottom(false);showRight(false);}
    void showBottom(boolean visible){changing=true;try{bottom.getBottomComponent().setVisible(visible);bottom.setDividerSize(visible?divider:0);bottom.setDividerLocation(visible?Math.max(80,bottom.getHeight()-bottomHeight-divider):Math.max(0,bottom.getHeight()));bottom.revalidate();}finally{changing=false;}}
    void showRight(boolean visible){changing=true;try{getRightComponent().setVisible(visible);setDividerSize(visible?divider:0);setDividerLocation(visible?Math.max(120,getWidth()-rightWidth-divider):Math.max(0,getWidth()));revalidate();}finally{changing=false;}}
    void preferBottomHeight(int height){if(automaticBottom&&height>bottomHeight){bottomHeight=height;if(bottomVisible())showBottom(true);}}
    void dividerSize(int value){divider=value;bottom.setDividerSize(bottomVisible()?value:0);setDividerSize(rightVisible()?value:0);}
    Map<String,Object> snapshot(){return Map.of("bottomVisible",bottomVisible(),"rightVisible",rightVisible(),"bottomHeight",bottomHeight,"rightWidth",rightWidth,"automaticBottom",automaticBottom);}
    void restore(Map<String,Object> state){
        automaticBottom=Boolean.TRUE.equals(state.get("automaticBottom"));
        if(state.get("bottomHeight") instanceof Number n)bottomHeight=Math.max(80,Math.min(4096,n.intValue()));
        if(state.get("rightWidth") instanceof Number n)rightWidth=Math.max(180,Math.min(4096,n.intValue()));
        showBottom(Boolean.TRUE.equals(state.get("bottomVisible")));showRight(Boolean.TRUE.equals(state.get("rightVisible")));
    }
}
