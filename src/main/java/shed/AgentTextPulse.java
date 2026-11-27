package shed;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import javax.swing.*;
import javax.swing.text.*;

/** Shared directional paint for source decorations and ordinary fields; owns no timer or source state. */
final class AgentTextPulse extends LayeredHighlighter.LayerPainter implements AutoCloseable {
    private final JTextComponent field;
    private final Document document;
    private final Highlighter highlighter;
    private final String activity;
    private final boolean motion;
    private Object tag;
    private int start,end;
    private long elapsed;
    private double progress;
    AgentTextPulse(JTextComponent field,String activity,boolean motion){
        this.field=field;this.document=field.getDocument();highlighter=field.getHighlighter();this.activity=activity;this.motion=motion;
    }
    static boolean small(String text){return text.length()<=160&&text.chars().filter(c->c=='\n').count()<=2;}
    static int typedUnits(String text,long elapsed,boolean motion){return motion&&small(text)&&text.chars().allMatch(c->c<128)?Math.min(text.length(),(int)(Math.max(0,elapsed)/Math.max(1,Math.min(14,650/Math.max(1,text.length()))))+1):text.length();}
    static Color color(JTextComponent field,String role){
        Color base=UIManager.getColor("shed."+role);
        if(base==null)base=new Color(role.equals("navigation")?0x8B52C7:role.equals("info")?0x087F9C:role.equals("warning")?0xA87808:role.equals("error")?0xCC4855:0x27815C);
        Color human=field.getCaretColor();if(human!=null&&Math.abs(base.getRed()-human.getRed())+Math.abs(base.getGreen()-human.getGreen())+Math.abs(base.getBlue()-human.getBlue())<100)base=new Color(0x8B52C7);
        return AccessibilitySupport.readableText(base,field.getBackground());
    }
    static int alpha(boolean motion,boolean reading,boolean active,int tick){return !motion?48:reading?30+(int)(12*Math.sin(tick*.18)):active?36+(int)(22*Math.sin(tick*.45)):Math.max(8,60-tick);}
    static void fill(Graphics2D g,Rectangle line,Color color,int alpha){g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),alpha));g.fill(line);}
    static void sweep(Graphics2D graphics,Rectangle line,Color color,String direction,double progress,Rectangle2D first,Rectangle2D last,int fontHeight){
        var g=(Graphics2D)graphics.create();try{
            g.clip(line);g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),38));double phase=Math.clamp(progress,0,1);
            if(direction.equals("top-to-bottom")||direction.equals("bottom-to-top")){
                if(first==null||last==null)return;int height=Math.max(2,fontHeight/2);
                int y=(int)(first.getY()+(direction.equals("bottom-to-top")?(1-phase)*Math.max(0,last.getMaxY()-first.getY()-height):phase*(last.getMaxY()-first.getY())));
                g.fillRect(line.x,y,line.width,height);
            }else{
                int x=line.x+(int)(phase*line.width);if(direction.equals("right-to-left"))x=line.x+line.width-(int)(phase*line.width);
                g.fillRect(x,line.y,Math.max(3,line.width/12),line.height);
            }
        }finally{g.dispose();}
    }
    void frame(int start,int end,double progress,long elapsed)throws BadLocationException{
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Text pulse belongs on the EDT");
        if(field.getDocument()!=document||field.getHighlighter()!=highlighter)throw new WorkspaceApiException(WorkspaceApiException.Code.CONTEXT_CHANGED,"Text decoration context changed");
        if(start<0||end<start||end>document.getLength())throw new BadLocationException("Invalid activity range",end);
        this.start=start;this.end=end;this.progress=progress;this.elapsed=elapsed;
        if(tag==null)tag=highlighter.addHighlight(start,end,this);else highlighter.changeHighlight(tag,start,end);
        field.repaint(field.getVisibleRect());
    }
    @Override public void paint(Graphics graphics,int start,int end,Shape bounds,JTextComponent component){
        // BasicTextFieldUI may use a non-layered highlighter path.
        try{var first=component.modelToView2D(start);var last=component.modelToView2D(end);if(first!=null&&last!=null)paintRegion(graphics,first.createUnion(last).getBounds());}catch(BadLocationException unavailable){ }
    }
    @Override public Shape paintLayer(Graphics graphics,int start,int end,Shape bounds,JTextComponent component,View view){
        try{var shape=view.modelToView(start,Position.Bias.Forward,end,Position.Bias.Backward,bounds);if(shape!=null){var line=shape.getBounds();paintRegion(graphics,line);return line;}}catch(BadLocationException unavailable){ }return null;
    }
    private void paintRegion(Graphics graphics,Rectangle rectangle)throws BadLocationException{
        if(field.getDocument()!=document||field.getHighlighter()!=highlighter||!(graphics instanceof Graphics2D))return;
        Rectangle line=rectangle.intersection(field.getVisibleRect());if(line.isEmpty())return;boolean reading=activity.equals("reading");
        Color color=color(field,reading?"warning":activity.equals("deleting")?"error":"success");var g=(Graphics2D)graphics.create();
        try{fill(g,line,color,alpha(motion,reading,true,(int)(elapsed/50)));if(motion)sweep(g,line,color,reading?"top-to-bottom":activity.equals("deleting")?"right-to-left":"left-to-right",progress,reading?field.modelToView2D(start):null,reading?field.modelToView2D(end):null,field.getFontMetrics(field.getFont()).getHeight());}finally{g.dispose();}
    }
    @Override public void close(){if(tag!=null){highlighter.removeHighlight(tag);tag=null;}}
}
