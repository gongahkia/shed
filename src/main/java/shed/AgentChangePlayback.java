package shed;

import java.awt.*;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.text.BadLocationException;

/** bounded paint-only replay of an already-observed diff; owns no document, timer or worker. */
final class AgentChangePlayback implements AutoCloseable {
    static final String KEY="shed.agentPlayback";
    record Edit(AgentEditPresentation.Range range,String removed) { }
    private final JTextArea area;
    private final String source;
    private final List<Edit> edits;
    private final Supplier<Color> markerColor;
    private Edit active;
    private double writing,erasing;
    private boolean deleting,closed,maskable;
    private String preview="";
    private int removedLines,revealed;

    AgentChangePlayback(JTextArea area,String source,List<Edit> edits,Supplier<Color> markerColor){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Playback belongs to the EDT");
        if(source.length()>65536||edits.size()>32)throw new IllegalArgumentException("Playback exceeds bounds");
        int removed=0;
        for(var edit:edits){var range=edit.range();if(range.start()<0||range.end()<range.start()||range.end()>source.length()||(removed+=edit.removed().length())>65536)throw new IllegalArgumentException("Invalid playback edit");}
        this.area=area;this.source=source;this.edits=List.copyOf(edits);this.markerColor=markerColor;
    }
    private boolean small(Edit edit){return edit.range().end()-edit.range().start()<=160&&source.substring(edit.range().start(),edit.range().end()).chars().filter(c->c=='\n').count()<=2;}
    private boolean removal(Edit edit){return !edit.removed().isEmpty()&&(edit.range().start()==edit.range().end()||!small(edit)||edit.removed().length()>160);}
    long durationMillis(int index){var edit=edits.get(index);int added=edit.range().end()-edit.range().start();return (removal(edit)?650:0)+(added==0?0:small(edit)?Math.max(180,Math.min(850,added*14L)):1100);}
    int frame(int index,double progress){
        if(closed)return 0;
        var next=edits.get(index);
        if(active!=next){active=next;maskable=GraphemeBoundary.isBoundary(source,next.range().start())&&GraphemeBoundary.isBoundary(source,next.range().end());int end=Math.min(next.removed().length(),1024);preview=next.removed().substring(0,GraphemeBoundary.floor(next.removed(),end));removedLines=next.removed().isEmpty()?0:(int)next.removed().chars().filter(c->c=='\n').count()+(next.removed().endsWith("\n")?0:1);}
        double total=durationMillis(index),elapsed=Math.max(0,Math.min(1,progress))*total;
        double removal=removal(active)?650:0;
        deleting=elapsed<removal;
        erasing=Math.max(0,Math.min(1,(elapsed-220)/430));
        writing=deleting?0:Math.max(0,Math.min(1,(elapsed-removal)/Math.max(1,total-removal)));
        area.putClientProperty(KEY,this);
        area.putClientProperty("shed.agentAnimation",deleting?"deleting":small(active)?"typing":"chunks");
        area.putClientProperty("shed.agentDirection",deleting?"right-to-left":"left-to-right");
        int start=active.range().start(),end=active.range().end();
        int offset=small(active)?start+(int)((end-start)*writing):chunkOffset(start,end,writing);
        area.repaint(area.getVisibleRect());
        revealed=Math.max(start,GraphemeBoundary.floor(source,offset));return revealed;
    }
    double directionalProgress(){return deleting?erasing:writing;}
    private int chunkOffset(int start,int end,double progress){
        int target=start+(int)((end-start)*progress);
        if(progress>=1)return end;
        // reveal complete lines for larger edits; the final stage always includes EOF
        int newline=source.lastIndexOf('\n',Math.max(start,target-1));
        return newline<start?start:newline+1;
    }
    static void paint(JTextArea area,Graphics graphics){
        if(area.getClientProperty(KEY) instanceof AgentChangePlayback playback)playback.paint(graphics);
    }
    private void paint(Graphics graphics){
        if(closed||active==null||!area.isShowing()||area.getDocument().getLength()!=source.length()||!(graphics instanceof Graphics2D))return;
        // wrapped/bidi geometry retains the directional highlight without glyph masking
        if(!maskable||area.getLineWrap()||Boolean.TRUE.equals(area.getDocument().getProperty("i18n")))return;
        var copy=(Graphics2D)graphics.create();
        try{
            copy.clip(area.getVisibleRect());
            if(deleting)paintDeletion(copy);
            else{
                if(writing<1)maskUnrevealed(copy);
                var caret=area.modelToView2D(revealed);
                if(caret!=null){copy.setColor(markerColor.get());copy.fillRect((int)caret.getX(),(int)caret.getY(),3,(int)caret.getHeight());}
            }
        }catch(BadLocationException unavailable){close();}
        finally{copy.dispose();}
    }
    private void maskUnrevealed(Graphics2D graphics)throws BadLocationException{
        int start=active.range().start(),end=active.range().end();
        int reveal=revealed;
        if(reveal>=end)return;
        var visible=area.getVisibleRect();int first=Math.max(reveal,area.viewToModel2D(new Point(visible.x,visible.y)));
        if(first<0||first>end)return;
        int line=area.getLineOfOffset(Math.min(first,source.length()));
        for(int rows=0;rows<100&&line<area.getLineCount();rows++,line++){
            int a=Math.max(reveal,area.getLineStartOffset(line)),b=Math.min(end,Math.min(source.length(),area.getLineEndOffset(line)));
            if(a>=end)break;if(b<=a)continue;
            var left=area.modelToView2D(a);if(left==null||left.getY()>visible.getMaxY())break;
            if(b>a&&source.charAt(b-1)=='\n')b--;
            var right=area.modelToView2D(b);if(right==null)continue;
            int width=Math.max(1,(int)Math.ceil(right.getX()-left.getX()));
            Color background=area.getBackground();int alpha=255;
            if(!small(active)&&a==reveal&&b>a){double target=start+(end-start)*writing;alpha=(int)(255*(1-Math.max(0,Math.min(1,(target-a)/(b-a)))));}
            graphics.setColor(new Color(background.getRed(),background.getGreen(),background.getBlue(),alpha));
            graphics.fillRect((int)left.getX(),(int)left.getY(),width,(int)Math.ceil(left.getHeight()));
        }
    }
    private void paintDeletion(Graphics2D graphics)throws BadLocationException{
        var anchor=area.modelToView2D(active.range().start());if(anchor==null)return;
        Rectangle visible=area.getVisibleRect();var metrics=area.getFontMetrics(area.getFont());int height=metrics.getHeight();
        int x=Math.max(visible.x+4,(int)anchor.getX()),y=Math.max(visible.y,(int)anchor.getY());
        int width=Math.max(0,visible.x+visible.width-x-8);if(width==0)return;
        int rows=Math.min(Math.min(8,removedLines),Math.max(1,(visible.y+visible.height-y-height)/height));
        int panelHeight=(rows+1)*height;
        graphics.setColor(area.getBackground());graphics.fillRect(x,y,width,panelHeight);
        Color color=UIManager.getColor("shed.error");if(color==null)color=new Color(0xCC4855);
        color=AccessibilitySupport.readableText(color,area.getBackground());
        graphics.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),35));graphics.fillRect(x,y,width,panelHeight);
        graphics.setColor(color);graphics.setFont(area.getFont());graphics.drawString((removedLines==1?"Removing text":"Removing "+removedLines+" lines")+" · observed change",x+4,y+metrics.getAscent());
        String[] lines=preview.split("\n",9);int bodyWidth=8;
        for(int row=0;row<Math.min(rows,lines.length);row++){
            if(lines[row].length()>200)lines[row]=lines[row].substring(0,GraphemeBoundary.floor(lines[row],200))+"…";
            bodyWidth=Math.max(bodyWidth,Math.min(width,metrics.stringWidth(lines[row])+8));
        }
        var text=(Graphics2D)graphics.create();
        try{
            text.clipRect(x,y+height,(int)(bodyWidth*(1-erasing)),rows*height);
            for(int row=0;row<Math.min(rows,lines.length);row++){
                text.drawString(lines[row],x+4,y+(row+1)*height+metrics.getAscent());
            }
        }finally{text.dispose();}
        int edge=x+(int)(bodyWidth*(1-erasing));graphics.fillRect(edge,y+height,2,rows*height);
    }
    @Override public void close(){
        if(closed)return;closed=true;active=null;preview="";
        if(area.getClientProperty(KEY)==this){area.putClientProperty(KEY,null);area.putClientProperty("shed.agentAnimation",null);area.putClientProperty("shed.agentDirection",null);area.repaint(area.getVisibleRect());}
    }
}
