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
    static List<Edit> refineAll(String source,Edit edit){
        String added=source.substring(edit.range().start(),edit.range().end());
        if(added.length()>160||edit.removed().length()>160)return List.of(refine(source,edit));
        String[] before=edit.removed().split("(?<=\\n)"),after=added.split("(?<=\\n)");
        if(before.length!=after.length||after.length>32)return List.of(refine(source,edit));
        var result=new java.util.ArrayList<Edit>();int offset=edit.range().start();
        for(int i=0;i<after.length;i++){
            if(!before[i].equals(after[i]))result.add(refine(source,new Edit(new AgentEditPresentation.Range(offset,offset+after[i].length()),before[i])));
            offset+=after[i].length();
        }
        return result.isEmpty()?List.of(edit):List.copyOf(result);
    }
    static Edit refine(String source,Edit edit){
        var range=edit.range();String after=source.substring(range.start(),range.end()),before=edit.removed();
        if(before.length()>160||after.length()>160)return edit;
        int prefix=0,suffix=0;
        while(prefix<Math.min(before.length(),after.length())&&before.charAt(prefix)==after.charAt(prefix))prefix++;
        while(prefix>0&&(!GraphemeBoundary.isBoundary(before,prefix)||!GraphemeBoundary.isBoundary(after,prefix)))prefix--;
        while(suffix<Math.min(before.length(),after.length())-prefix&&before.charAt(before.length()-suffix-1)==after.charAt(after.length()-suffix-1))suffix++;
        while(suffix>0&&(!GraphemeBoundary.isBoundary(before,before.length()-suffix)||!GraphemeBoundary.isBoundary(after,after.length()-suffix)))suffix--;
        String removed=before.substring(prefix,before.length()-suffix),added=after.substring(prefix,after.length()-suffix);
        if(removed.indexOf('\n')>=0||added.indexOf('\n')>=0)return edit;
        return new Edit(new AgentEditPresentation.Range(range.start()+prefix,range.end()-suffix),removed);
    }
    private final JTextArea area;
    private final String source;
    private final List<Edit> edits;
    private final Supplier<Color> markerColor;
    private Edit active;
    private double writing,erasing;
    private boolean deleting,closed,maskable;
    private String preview="";
    private int removedLines,revealed;
    private int first,last;
    private double progress;

    AgentChangePlayback(JTextArea area,String source,List<Edit> edits,Supplier<Color> markerColor){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Playback belongs to the EDT");
        if(source.length()>65536||edits.size()>32)throw new IllegalArgumentException("Playback exceeds bounds");
        int removed=0;
        for(var edit:edits){var range=edit.range();if(range.start()<0||range.end()<range.start()||range.end()>source.length()||(removed+=edit.removed().length())>65536)throw new IllegalArgumentException("Invalid playback edit");}
        this.area=area;this.source=source;this.edits=List.copyOf(edits);this.markerColor=markerColor;
    }
    private boolean small(Edit edit){return edit.range().end()-edit.range().start()<=160&&AgentTextPulse.small(source.substring(edit.range().start(),edit.range().end()));}
    private boolean removal(Edit edit){return !edit.removed().isEmpty();}
    boolean inline(int index){return inline(edits.get(index));}
    private boolean inline(Edit edit){return small(edit)&&edit.removed().indexOf('\n')<0&&source.substring(edit.range().start(),edit.range().end()).indexOf('\n')<0;}
    long durationMillis(int index){var edit=edits.get(index);int added=edit.range().end()-edit.range().start();return (removal(edit)?650:0)+(added==0?0:small(edit)?Math.max(700,Math.min(850,added*14L)):1100);}
    int frame(int index,double progress){
        return frames(index,index+1,progress);
    }
    int frames(int first,int last,double progress){
        if(closed)return 0;
        if(first<0||last<=first||last>edits.size())throw new IllegalArgumentException("Invalid playback group");
        this.first=first;this.last=last;this.progress=progress;select(first,progress);
        area.putClientProperty(KEY,this);
        area.putClientProperty("shed.agentCursorCount",badgeOnly()?0:last-first);
        area.putClientProperty("shed.agentAnimation",deleting?"deleting":small(active)?"typing":"chunks");
        area.putClientProperty("shed.agentDirection",deleting?"right-to-left":"left-to-right");
        area.repaint(area.getVisibleRect());return revealed;
    }
    private void select(int index,double progress){
        var next=edits.get(index);
        if(active!=next){active=next;maskable=GraphemeBoundary.isBoundary(source,next.range().start())&&GraphemeBoundary.isBoundary(source,next.range().end());int end=Math.min(next.removed().length(),1024);preview=next.removed().substring(0,GraphemeBoundary.floor(next.removed(),end));removedLines=next.removed().isEmpty()?0:(int)next.removed().chars().filter(c->c=='\n').count()+(next.removed().endsWith("\n")?0:1);}
        double total=durationMillis(index),elapsed=Math.max(0,Math.min(1,progress))*total;
        double removal=removal(active)?650:0;
        deleting=elapsed<removal;
        erasing=Math.max(0,Math.min(1,(elapsed-220)/430));
        writing=deleting?0:Math.max(0,Math.min(1,(elapsed-removal)/Math.max(1,total-removal)));
        int start=active.range().start(),end=active.range().end();
        int offset=small(active)?start+(int)((end-start)*writing):chunkOffset(start,end,writing);
        revealed=Math.max(start,GraphemeBoundary.floor(source,offset));
    }
    boolean badgeOnly(){return active!=null&&!deleting&&!small(active);}
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
            for(int index=first;index<edits.size();index++){
                boolean pending=index>=last;select(index,pending?0:progress);if(!maskable)continue;
                if(deleting){if(!paintInlineDeletion(copy,!pending))paintDeletion(copy);}
                else{
                    if(writing<1&&!slideInlineTail(copy))maskUnrevealed(copy);
                    var caret=pending||!small(active)?null:area.modelToView2D(revealed);
                    if(caret!=null){copy.setColor(markerColor.get());copy.fillRect((int)caret.getX(),(int)caret.getY(),3,(int)caret.getHeight());}
                }
            }
        }catch(BadLocationException unavailable){close();}
        finally{if(!closed)select(first,progress);copy.dispose();}
    }
    private boolean paintInlineDeletion(Graphics2D graphics,boolean cursor)throws BadLocationException{
        if(!inline(active))return false;
        var anchor=area.modelToView2D(active.range().start());if(anchor==null)return false;
        int width=area.getFontMetrics(area.getFont()).stringWidth(active.removed());
        int remaining=(int)(width*(1-erasing)),x=(int)anchor.getX(),y=(int)anchor.getY(),height=(int)Math.ceil(anchor.getHeight());
        if(!moveInlineTail(graphics,x+remaining))return false;
        graphics.setColor(area.getBackground());graphics.fillRect(x,y,remaining,height);
        Color color=AgentTextPulse.color(area,"error");
        int alpha=cursor?48+(int)(12*Math.sin(progress*durationMillis(first)*.009)):32;
        graphics.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),alpha));graphics.fillRect(x,y,remaining,height);
        if(cursor&&remaining>0){
            var sweep=(Graphics2D)graphics.create();
            try{
                sweep.clipRect(x,y,remaining,height);int band=Math.max(2,area.getFontMetrics(area.getFont()).charWidth('x')/2);
                sweep.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),38));
                sweep.fillRect(x+(int)((remaining-band)*(1-erasing)),y,band,height);
            }finally{sweep.dispose();}
        }
        var text=(Graphics2D)graphics.create();
        try{
            text.clipRect(x,y,remaining,height);text.setFont(area.getFont());text.setColor(color);
            text.drawString(active.removed(),x,y+area.getFontMetrics(area.getFont()).getAscent());
        }finally{text.dispose();}
        if(cursor){graphics.setColor(color);graphics.fillRect(x+remaining,y,2,height);}return true;
    }
    private boolean slideInlineTail(Graphics2D graphics)throws BadLocationException{
        var to=area.modelToView2D(revealed);return to!=null&&moveInlineTail(graphics,(int)to.getX());
    }
    private boolean moveInlineTail(Graphics2D graphics,int targetX)throws BadLocationException{
        int start=active.range().start(),end=active.range().end();
        if(!small(active)||source.substring(start,end).indexOf('\n')>=0)return false;
        int line=area.getLineOfOffset(end),lineEnd=Math.min(source.length(),area.getLineEndOffset(line));
        if(lineEnd>0&&source.charAt(lineEnd-1)=='\n')lineEnd--;
        if(lineEnd-start>256)return false;
        var from=area.modelToView2D(end);var to=area.modelToView2D(start);var last=area.modelToView2D(lineEnd);
        if(from==null||to==null||last==null||Math.abs(from.getY()-to.getY())>1||Math.abs(from.getY()-last.getY())>1)return false;
        var visible=area.getVisibleRect();int x=(int)from.getX(),y=(int)from.getY(),tail=Math.max(0,(int)Math.ceil(last.getX()-from.getX()));
        int shift=x-targetX,height=(int)Math.ceil(from.getHeight());
        if(shift==0)return true;
        if(x<visible.x||x+tail>visible.getMaxX()||targetX<visible.x||targetX+tail>visible.getMaxX())return false;
        if(tail>0)graphics.copyArea(x,y,tail,height,-shift,0);
        if(shift>0){graphics.setColor(area.getBackground());graphics.fillRect(x-shift+tail,y,shift,height);}
        return true;
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
    private String previewLine(String line){return line.length()>200?line.substring(0,GraphemeBoundary.floor(line,200))+"…":line;}
    int badgeMinimumX()throws BadLocationException{
        if(!deleting||active==null)return 0;
        var anchor=area.modelToView2D(active.range().start());if(anchor==null)return 0;
        int newline=preview.indexOf('\n');String first=previewLine(newline<0?preview:preview.substring(0,newline));
        return Math.max(area.getVisibleRect().x+4,(int)anchor.getX())+4+area.getFontMetrics(area.getFont()).stringWidth(first)+12;
    }
    private void paintDeletion(Graphics2D graphics)throws BadLocationException{
        var anchor=area.modelToView2D(active.range().start());if(anchor==null)return;
        Rectangle visible=area.getVisibleRect();var metrics=area.getFontMetrics(area.getFont());int height=metrics.getHeight();
        int x=Math.max(visible.x+4,(int)anchor.getX()),y=Math.max(visible.y,(int)anchor.getY());
        int width=Math.max(0,visible.x+visible.width-x-8);if(width==0)return;
        int rows=Math.min(Math.min(8,removedLines),Math.max(1,(visible.y+visible.height-y)/height));
        int panelHeight=rows*height;
        graphics.setColor(area.getBackground());graphics.fillRect(x,y,width,panelHeight);
        Color color=AgentTextPulse.color(area,"error");
        graphics.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),35));graphics.fillRect(x,y,width,panelHeight);
        graphics.setColor(color);graphics.setFont(area.getFont());
        String[] lines=preview.split("\n",9);int bodyWidth=8;
        for(int row=0;row<Math.min(rows,lines.length);row++){
            lines[row]=previewLine(lines[row]);
            bodyWidth=Math.max(bodyWidth,Math.min(width,metrics.stringWidth(lines[row])+8));
        }
        var text=(Graphics2D)graphics.create();
        try{
            text.clipRect(x,y,(int)(bodyWidth*(1-erasing)),rows*height);
            for(int row=0;row<Math.min(rows,lines.length);row++){
                text.drawString(lines[row],x+4,y+row*height+metrics.getAscent());
            }
        }finally{text.dispose();}
        int edge=x+(int)(bodyWidth*(1-erasing));graphics.fillRect(edge,y,2,rows*height);
    }
    @Override public void close(){
        if(closed)return;closed=true;active=null;preview="";
        if(area.getClientProperty(KEY)==this){area.putClientProperty(KEY,null);area.putClientProperty("shed.agentCursorCount",null);area.putClientProperty("shed.agentAnimation",null);area.putClientProperty("shed.agentDirection",null);area.repaint(area.getVisibleRect());}
    }
}
