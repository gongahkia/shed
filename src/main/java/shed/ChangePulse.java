package shed;

import java.awt.Color;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Finite row feedback; no timer runs when idle or hidden. */
final class ChangePulse implements AutoCloseable {
    private final JComponent owner;
    private final Timer timer;
    private Set<String> keys=Set.of();
    private int ticks;private boolean closed;
    private final java.awt.event.HierarchyListener visibility=e->{if(!ownerShowing())clear();};

    ChangePulse(JComponent owner){
        this.owner=owner;timer=new Timer(80,e->advance());timer.setCoalesce(true);owner.addHierarchyListener(visibility);
    }
    void flash(Set<String> changed){
        clear();if(closed||!ownerShowing()||!animated())return;
        keys=changed.stream().limit(4096).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(!keys.isEmpty()){owner.putClientProperty("shed.changePulse",keys.size());timer.start();owner.repaint();}
    }
    private boolean ownerShowing(){return owner!=null&&owner.isShowing();}
    private boolean animated(){return !"false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"))&&!Boolean.FALSE.equals(owner.getClientProperty("shed.caretAnimation"));}
    void advance(){if(!ownerShowing()||!animated()||++ticks>=30)clear();else owner.repaint();}
    double strength(String key){return keys.contains(key)?.20*(1-ticks/30.0)*(.6+.4*Math.cos(ticks*.55)):0;}
    boolean running(){return timer.isRunning();}
    void clear(){timer.stop();keys=Set.of();ticks=0;if(owner!=null){owner.putClientProperty("shed.changePulse",null);owner.repaint();}}
    static Color color(String kind){
        Color result=UIManager.getColor(kind.startsWith("D")?"shed.error":kind.startsWith("A")?"shed.success":"Component.focusColor");
        return result==null?UIManager.getColor("TextArea.caretForeground"):result;
    }
    static Color tint(Color base,Color accent,double amount){
        if(accent==null||amount<=0)return base;
        return new Color((int)(base.getRed()*(1-amount)+accent.getRed()*amount),(int)(base.getGreen()*(1-amount)+accent.getGreen()*amount),(int)(base.getBlue()*(1-amount)+accent.getBlue()*amount));
    }
    @Override public void close(){closed=true;clear();owner.removeHierarchyListener(visibility);}
}
