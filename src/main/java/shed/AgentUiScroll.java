package shed;

import java.awt.*;
import java.util.Map;
import javax.swing.*;

/** A bounded viewport motion; never changes the text selection or source model. */
final class AgentUiScroll {
    enum Unit { PIXELS, LINES, PAGES }
    enum Axis { VERTICAL, HORIZONTAL }
    private final JScrollPane pane;
    private final JScrollBar bar;
    private final int from, to;
    private int last;

    AgentUiScroll(JScrollPane pane, Axis axis, Unit unit, int amount) {
        this(pane,bar(pane,axis),unit,amount);
    }
    private static JScrollBar bar(JScrollPane pane,Axis axis){if(pane==null||axis==null)throw invalid();return axis==Axis.VERTICAL?pane.getVerticalScrollBar():pane.getHorizontalScrollBar();}
    AgentUiScroll(JScrollBar bar,Unit unit,int amount){this(null,bar,unit,amount);}
    private AgentUiScroll(JScrollPane pane,JScrollBar bar,Unit unit,int amount){
        if(bar==null||unit==null||Math.abs((long)amount)>10000)throw invalid();this.pane=pane;this.bar=bar;
        if(!bar.isEnabled())throw invalid();
        from=last=bar.getValue();
        int direction=amount<0?-1:1;
        int step=switch(unit){case PIXELS->1;case LINES->Math.max(1,bar.getUnitIncrement(direction));case PAGES->Math.max(1,bar.getBlockIncrement(direction));};
        long desired=(long)from+(long)step*amount;
        to=(int)Math.clamp(desired,bar.getMinimum(),Math.max(bar.getMinimum(),bar.getMaximum()-bar.getVisibleAmount()));
    }
    void frame(double progress) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Scrolling belongs on the EDT");
        if(pane!=null&&pane.getViewport()==null||bar.getValue()!=last)throw new WorkspaceApiException(WorkspaceApiException.Code.CONTEXT_CHANGED,"Viewport changed during scrolling");
        double eased=1-Math.pow(1-Math.clamp(progress,0,1),3);
        last=(int)Math.round(from+(to-from)*eased);bar.setValue(last);
        // the model may clamp further when content/layout changes
        last=bar.getValue();
    }
    Map<String,Object> state(){if(pane==null){boolean vertical=bar.getOrientation()==JScrollBar.VERTICAL;return Map.of("x",vertical?0:bar.getValue(),"y",vertical?bar.getValue():0,"width",vertical?0:bar.getVisibleAmount(),"height",vertical?bar.getVisibleAmount():0,"contentWidth",vertical?0:bar.getMaximum()-bar.getMinimum(),"contentHeight",vertical?bar.getMaximum()-bar.getMinimum():0,"units","scrollbar-model");}var v=pane.getViewport();return Map.of("x",v.getViewPosition().x,"y",v.getViewPosition().y,"width",v.getExtentSize().width,"height",v.getExtentSize().height,"contentWidth",v.getViewSize().width,"contentHeight",v.getViewSize().height);}
    int destination(){return to;}
    static JScrollPane pane(Component component){
        if(component instanceof JScrollPane scroll)return scroll;
        return (JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,component);
    }
    private static WorkspaceApiException invalid(){return new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST,"Invalid scroll target or distance");}
}
