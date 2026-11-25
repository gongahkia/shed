package shed;

import java.awt.Component;
import java.awt.Point;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollPaneUI;

/** Shared wheel motion only; caret reveals, selection, keyboard navigation and scrollbar drags stay immediate. */
public final class SmoothScrollPaneUI extends BasicScrollPaneUI {
    private static final long DURATION=140_000_000L;
    private final Timer animation=new Timer(16,event->advance(System.nanoTime()));
    private JScrollBar vertical,horizontal,active;
    private BoundedRangeModel verticalModel,horizontalModel;
    private JViewport observedViewport;
    private Component view;
    private double target;
    private int origin,direction;
    private long started;
    private boolean applying;
    private final ChangeListener changed=event->{if(!applying)cancel();};
    private final MouseAdapter mouse=new MouseAdapter(){@Override public void mousePressed(MouseEvent event){cancel();}};
    private final KeyAdapter key=new KeyAdapter(){@Override public void keyPressed(KeyEvent event){cancel();}};
    private final FocusAdapter focus=new FocusAdapter(){@Override public void focusLost(FocusEvent event){cancel();}};
    private final PropertyChangeListener properties=event->{
        String name=event.getPropertyName();
        if("viewport".equals(name)||"verticalScrollBar".equals(name)||"horizontalScrollBar".equals(name)||"model".equals(name))observe();
        else if("document".equals(name)||"wheelScrollingEnabled".equals(name)||"enabled".equals(name))cancel();
    };
    private final ContainerAdapter contents=new ContainerAdapter(){
        @Override public void componentAdded(ContainerEvent event){observe();}
        @Override public void componentRemoved(ContainerEvent event){observe();}
    };
    private final HierarchyListener visibility=event->{if(scrollpane!=null&&!scrollpane.isShowing())cancel();};

    public static ComponentUI createUI(JComponent component){return new SmoothScrollPaneUI();}
    @Override protected void installListeners(JScrollPane pane){
        super.installListeners(pane);animation.setCoalesce(true);
        pane.addPropertyChangeListener(properties);pane.addHierarchyListener(visibility);observe();
    }
    @Override protected void uninstallListeners(JComponent component){
        cancel();unobserve();component.removePropertyChangeListener(properties);component.removeHierarchyListener(visibility);super.uninstallListeners(component);
    }
    private void unobserve(){
        if(verticalModel!=null)verticalModel.removeChangeListener(changed);
        if(horizontalModel!=null)horizontalModel.removeChangeListener(changed);
        for(var bar:new JScrollBar[]{vertical,horizontal})if(bar!=null){bar.removeMouseListener(mouse);bar.removePropertyChangeListener(properties);}
        if(observedViewport!=null)observedViewport.removeContainerListener(contents);
        if(view!=null){view.removeMouseListener(mouse);view.removeKeyListener(key);view.removeFocusListener(focus);view.removePropertyChangeListener(properties);}
    }
    private void observe(){
        cancel();unobserve();if(scrollpane==null)return;
        vertical=scrollpane.getVerticalScrollBar();horizontal=scrollpane.getHorizontalScrollBar();
        verticalModel=vertical==null?null:vertical.getModel();horizontalModel=horizontal==null?null:horizontal.getModel();
        if(verticalModel!=null)verticalModel.addChangeListener(changed);
        if(horizontalModel!=null)horizontalModel.addChangeListener(changed);
        for(var bar:new JScrollBar[]{vertical,horizontal})if(bar!=null){bar.addMouseListener(mouse);bar.addPropertyChangeListener(properties);}
        observedViewport=scrollpane.getViewport();view=observedViewport==null?null:observedViewport.getView();
        if(observedViewport!=null)observedViewport.addContainerListener(contents);
        if(view!=null){view.addMouseListener(mouse);view.addKeyListener(key);view.addFocusListener(focus);view.addPropertyChangeListener(properties);}
    }
    @Override protected MouseWheelListener createMouseWheelListener(){
        var standard=super.createMouseWheelListener();
        return event->{
            if(event.isConsumed())return;
            if(scrollpane==null)return;
            if(!scrollpane.isEnabled()||!scrollpane.isWheelScrollingEnabled()||event.isControlDown()||event.isMetaDown()){cancel();standard.mouseWheelMoved(event);return;}
            double rotation=event.getPreciseWheelRotation();if(!Double.isFinite(rotation)||rotation==0)return;
            var bar=event.isShiftDown()?horizontal:vertical;
            if(!canScroll(bar)&&!event.isShiftDown())bar=horizontal;
            if(!canScroll(bar)){forward(event);return;}
            int sign=rotation>0?1:-1;
            double unit=event.getScrollType()==MouseWheelEvent.WHEEL_BLOCK_SCROLL?bar.getBlockIncrement(sign):(double)Math.max(1,event.getScrollAmount())*bar.getUnitIncrement(sign);
            double delta=rotation*Math.max(1,Math.abs(unit));
            double base=active==bar&&animation.isRunning()&&direction==sign?target:bar.getValue();
            double next=clamp(bar,base+delta);
            if(next==base&&bar.getValue()==Math.round(next)){forward(event);return;}
            event.consume();
            if(!animated()||!scrollpane.isShowing()||bar.getValueIsAdjusting()){cancel();setValue(bar,(int)Math.round(next));return;}
            active=bar;origin=bar.getValue();target=next;direction=sign;started=System.nanoTime();
            scrollpane.putClientProperty("shed.smoothScrollActive",true);animation.start();
        };
    }
    private static boolean canScroll(JScrollBar bar){return bar!=null&&bar.isEnabled()&&bar.getMaximum()-bar.getVisibleAmount()>bar.getMinimum();}
    private static double clamp(JScrollBar bar,double value){return Math.max(bar.getMinimum(),Math.min(bar.getMaximum()-bar.getVisibleAmount(),value));}
    private boolean animated(){
        if("false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"))||Boolean.getBoolean("prefers.reduced.motion"))return false;
        if(view instanceof JComponent component&&Boolean.FALSE.equals(component.getClientProperty("shed.caretAnimation")))return false;
        for(Component current=scrollpane;current!=null;current=current.getParent())if(current instanceof JComponent component&&(Boolean.FALSE.equals(component.getClientProperty("shed.caretAnimation"))||Boolean.FALSE.equals(component.getClientProperty("shed.smoothScrolling"))))return false;
        return true;
    }
    private void setValue(JScrollBar bar,int value){applying=true;try{bar.setValue(value);}finally{applying=false;}}
    void advance(long now){
        var bar=active;if(bar==null)return;
        if(scrollpane==null||!scrollpane.isShowing()||!scrollpane.isEnabled()||!scrollpane.isWheelScrollingEnabled()){cancel();return;}
        double progress=animated()?Math.min(1,Math.max(0,(now-started)/(double)DURATION)):1;
        setValue(bar,(int)Math.round(clamp(bar,origin+(target-origin)*(1-Math.pow(1-progress,3)))));
        if(progress>=1)cancel();
    }
    boolean animating(){return animation.isRunning();}
    private void cancel(){animation.stop();active=null;if(scrollpane!=null&&scrollpane.getClientProperty("shed.smoothScrollActive")!=null)scrollpane.putClientProperty("shed.smoothScrollActive",null);}
    private void forward(MouseWheelEvent event){
        var parent=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,scrollpane.getParent());if(parent==null)return;
        Point point=SwingUtilities.convertPoint(scrollpane,event.getPoint(),parent);
        var forwarded=new MouseWheelEvent(parent,event.getID(),event.getWhen(),event.getModifiersEx(),point.x,point.y,event.getXOnScreen(),event.getYOnScreen(),event.getClickCount(),event.isPopupTrigger(),event.getScrollType(),event.getScrollAmount(),event.getWheelRotation(),event.getPreciseWheelRotation());
        parent.dispatchEvent(forwarded);if(forwarded.isConsumed())event.consume();
    }
}
