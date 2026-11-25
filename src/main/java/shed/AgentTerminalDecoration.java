package shed;

import java.awt.*;
import javax.swing.*;
import org.jdesktop.swingx.painter.BusyPainter;

/** Paints the provider beside the normal terminal cursor, without adding terminal bytes. */
final class AgentTerminalDecoration implements AutoCloseable {
    private static final String KEY="shed.agentTerminalGlow",BADGE="shed.agentTerminalBadge";
    private final JComponent terminal,input;
    private final String agent;
    private final Object previous,previousBadge;
    private final BusyPainter spinner=new BusyPainter(20);
    private final long started=System.nanoTime();
    private long lastPaint;
    private String phase="waiting for prompt";
    private Rectangle painted=new Rectangle();
    private boolean closed;

    AgentTerminalDecoration(JComponent terminal,JComponent input,String agent){
        this.terminal=terminal;this.input=input;this.agent=agent;
        previous=terminal.getClientProperty(KEY);previousBadge=input.getClientProperty(BADGE);
        spinner.setPoints(16);spinner.setTrailLength(8);spinner.setAntialiasing(true);spinner.setPointShape(new java.awt.geom.RoundRectangle2D.Double(0,0,5,2,2,2));
        terminal.putClientProperty(KEY,this);input.putClientProperty(BADGE,this);input.putClientProperty("shed.agentControl",true);
    }
    void phase(String value){if(!phase.equals(value))lastPaint=0;phase=value;pulse();}
    private Color color(){
        String role=phase.equals("typing")?"success":phase.startsWith("exit ")&&!phase.equals("exit 0")?"error":"warning";
        Color color=UIManager.getColor("shed."+role);
        if(color==null)color=new Color(role.equals("success")?0x27815C:role.equals("error")?0xCC4855:0xA87808);
        return AccessibilitySupport.readableText(color,input.getBackground());
    }
    static void paintGlow(JComponent component,Graphics graphics){
        if(!(component.getClientProperty(KEY) instanceof AgentTerminalDecoration decoration)||decoration.closed)return;
        var g=(Graphics2D)graphics.create();
        try{Color color=decoration.color();int alpha=decoration.animated()?90+(int)(50*Math.sin((System.nanoTime()-decoration.started)/500_000_000.0)):120;
            for(int edge=0;edge<3;edge++){g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),Math.max(12,alpha/(edge+1))));g.drawRect(edge,edge,Math.max(0,component.getWidth()-1-edge*2),Math.max(0,component.getHeight()-1-edge*2));}
        }finally{g.dispose();}
    }
    static void paintBadge(JComponent input,Graphics graphics,Rectangle cursor,Font font,int available){
        if(input.getClientProperty(BADGE) instanceof AgentTerminalDecoration decoration&&!decoration.closed)
            decoration.paintBadge(graphics,cursor,font,available);
    }
    static Color cursorColor(JComponent input){return input.getClientProperty(BADGE) instanceof AgentTerminalDecoration decoration&&!decoration.closed?decoration.color():null;}
    static boolean active(JComponent input){return input.getClientProperty(BADGE) instanceof AgentTerminalDecoration decoration&&!decoration.closed;}
    private void paintBadge(Graphics graphics,Rectangle cursor,Font font,int available){
        if(cursor.isEmpty()||available<=0)return;
        var metrics=input.getFontMetrics(font);int size=cursor.height,gap=6,spinnerSize=Math.max(12,metrics.getAscent());
        Icon logo=ProviderIcons.forValue(agent,input.getBackground());String label=AgentTerminalCommand.activityLabel(agent,phase);
        int mark=logo==null?0:size+gap,width=mark+metrics.stringWidth(label)+gap+spinnerSize;
        boolean text=true,loading=true;
        if(width>available){
            if(logo==null)return;
            text=false;width=size+gap+spinnerSize;
            if(width>available){loading=false;width=size;}
            if(width>available)return;
        }
        int x=cursor.x+cursor.width+gap,y=cursor.y;painted=new Rectangle(x,y,width,size);
        var g=(Graphics2D)graphics.create();
        try{
            g.clip(painted);g.setColor(input.getBackground());g.fill(painted);
            if(logo!=null){var icon=(Graphics2D)g.create();try{icon.translate(x,y);icon.scale(size/(double)logo.getIconWidth(),size/(double)logo.getIconHeight());logo.paintIcon(input,icon,0,0);}finally{icon.dispose();}x+=size+gap;}
            Color color=color();
            if(text){g.setFont(font);g.setColor(color);g.drawString(label,x,y+(size-metrics.getHeight())/2+metrics.getAscent());x+=metrics.stringWidth(label)+gap;}
            if(loading){
                spinner.setBaseColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),45));spinner.setHighlightColor(color);
                spinner.setFrame(animated()?(int)((System.nanoTime()-started)/50_000_000L%16):0);
                g.translate(x,y+(size-spinnerSize)/2);g.scale(spinnerSize/20.0,spinnerSize/20.0);spinner.paint(g,input,20,20);
            }
        }finally{g.dispose();}
    }
    private boolean animated(){return !Boolean.FALSE.equals(input.getClientProperty("shed.caretAnimation"))&&!"false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"));}
    void pulse(){
        long now=System.nanoTime();if(closed||now-lastPaint<50_000_000L)return;lastPaint=now;
        int w=terminal.getWidth(),h=terminal.getHeight();terminal.repaint(0,0,w,3);terminal.repaint(0,h-3,w,3);terminal.repaint(0,0,3,h);terminal.repaint(w-3,0,3,h);
        Rectangle dirty=new Rectangle(painted);
        if(input instanceof AnimatedTerminalPanel panel){
            var cursor=panel.agentCursorBounds();var metrics=input.getFontMetrics(panel.agentFont());
            if(!cursor.isEmpty()){
                var current=new Rectangle(cursor.x+cursor.width+6,cursor.y,cursor.height*2+18+metrics.stringWidth(AgentTerminalCommand.activityLabel(agent,phase)),cursor.height);
                current=current.union(cursor);dirty=dirty.isEmpty()?current:dirty.union(current);
            }
        }
        if(!dirty.isEmpty())input.repaint(dirty);
    }
    public void close(){
        if(closed)return;closed=true;
        input.putClientProperty("shed.agentControl",null);if(input.getClientProperty(BADGE)==this)input.putClientProperty(BADGE,previousBadge);
        if(terminal.getClientProperty(KEY)==this)terminal.putClientProperty(KEY,previous);
        terminal.repaint();input.repaint();
    }
}
