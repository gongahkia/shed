package shed;

import java.awt.Point;
import java.awt.event.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A finite presentation of observed blocks; never moves the human caret or edits text. */
final class AgentChangeTour implements AutoCloseable {
    private static final String OWNER="shed.agentTourOwner";
    private final JTextArea area;
    private final JViewport viewport;
    private final JScrollPane scroll;
    private final AgentEditPresentation presentation;
    private final List<AgentEditPresentation.Range> ranges;
    private final String source,name;
    private final BooleanSupplier current;
    private final Runnable pause,finished;
    private final javax.swing.text.Document document;
    private final Timer timer;
    private final List<AgentChangePlayback.Edit> playbackEdits;
    private AgentChangePlayback playback;
    private boolean closed;
    private int index,groupEnd,fromY;
    private long started,version;
    private static final long MOVE=300_000_000L,DWELL=700_000_000L,SETTLE=450_000_000L;
    private final KeyAdapter key=new KeyAdapter(){public void keyPressed(KeyEvent event){user();}};
    private final MouseAdapter mouse=new MouseAdapter(){public void mousePressed(MouseEvent event){user();}public void mouseWheelMoved(MouseWheelEvent event){user();}};
    private final FocusAdapter focus=new FocusAdapter(){public void focusLost(FocusEvent event){if(event.getOppositeComponent()!=null&&SwingUtilities.getWindowAncestor(event.getOppositeComponent())==SwingUtilities.getWindowAncestor(area))user();}};
    private final DocumentListener edits=new DocumentListener(){public void insertUpdate(DocumentEvent e){end(true);}public void removeUpdate(DocumentEvent e){end(true);}public void changedUpdate(DocumentEvent e){end(true);}};

    AgentChangeTour(JTextArea area,AgentEditPresentation presentation,List<AgentEditPresentation.Range> ranges,String source,String name,BooleanSupplier current,Runnable pause,Runnable finished){
        this(area,presentation,ranges,source,name,current,pause,finished,null);
    }
    AgentChangeTour(JTextArea area,AgentEditPresentation presentation,List<AgentEditPresentation.Range> ranges,String source,String name,BooleanSupplier current,Runnable pause,Runnable finished,List<AgentChangePlayback.Edit> edits){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Change tour belongs to the EDT");
        this.area=area;this.presentation=presentation;this.ranges=List.copyOf(ranges);this.source=source;this.name=name;this.current=current;this.pause=pause;this.finished=finished;
        this.document=area.getDocument();this.playbackEdits=edits==null?null:List.copyOf(edits);
        if(this.playbackEdits!=null&&(this.playbackEdits.size()!=ranges.size()||!this.playbackEdits.stream().map(AgentChangePlayback.Edit::range).toList().equals(this.ranges)))throw new IllegalArgumentException("Playback ranges do not match tour");
        if(ranges.size()>32||source.length()>65536||source.length()!=document.getLength())throw new IllegalArgumentException("Change tour exceeds source bounds");
        for(var range:ranges)if(range.start()<0||range.end()<range.start()||range.end()>source.length())throw new IllegalArgumentException("Invalid tour range");
        this.viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,area);this.scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,area);
        timer=new Timer(16,event->advance(System.nanoTime()));timer.setCoalesce(true);
    }
    void start(){
        if(closed||viewport==null||ranges.isEmpty()||!presentation.available()||!current.getAsBoolean()){end(false);return;}
        area.addKeyListener(key);area.addMouseListener(mouse);area.addFocusListener(focus);document.addDocumentListener(edits);
        if(scroll!=null){scroll.addMouseWheelListener(mouse);scroll.getVerticalScrollBar().addMouseListener(mouse);scroll.getHorizontalScrollBar().addMouseListener(mouse);}
        presentation.showApplied(ranges);presentation.setAgentName(name);version=presentation.version();
        if(!animated()){fromY=viewport.getViewPosition().y;presentation.moveMarker(0,ranges.getFirst().end());reveal(ranges.getFirst().start(),1);end(false);return;}
        begin(System.nanoTime());timer.start();
    }
    private boolean animated(){return !"false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"))&&!Boolean.FALSE.equals(area.getClientProperty("shed.caretAnimation"));}
    private void begin(long now){
        started=now;fromY=viewport.getViewPosition().y;presentation.showApplied(ranges);presentation.setAgentName(name);version=presentation.version();
        groupEnd=index+1;
        if(playbackEdits!=null){playback=new AgentChangePlayback(area,source,playbackEdits,presentation::agentColor);groupEnd=parallelEnd();playback.frames(index,groupEnd,0);presentation.setAgentName(name+("deleting".equals(area.getClientProperty("shed.agentAnimation"))?" deleting":" writing"),name);}
        presentation.moveMarker(index,ranges.get(index).start());area.putClientProperty(OWNER,this);area.putClientProperty("shed.agentTour",index+1);
    }
    private int parallelEnd(){
        if(!playback.inline(index)||area.getLineWrap()||Boolean.TRUE.equals(document.getProperty("i18n")))return index+1;
        try{
            var first=area.modelToView2D(ranges.get(index).start());if(first==null)return index+1;
            double previous=first.getY();int end=index+1;
            while(end<ranges.size()&&playback.inline(end)&&playback.durationMillis(end)==playback.durationMillis(index)){
                var next=area.modelToView2D(ranges.get(end).start());
                if(next==null||next.getY()<=previous||next.getY()-first.getY()>viewport.getExtentSize().height/2.0)break;
                previous=next.getY();end++;
            }
            return end;
        }catch(javax.swing.text.BadLocationException unavailable){return index+1;}
    }
    void advance(long now){
        if(closed)return;
        if(!area.isShowing()||area.getDocument()!=document||!current.getAsBoolean()||presentation.version()!=version){end(true);return;}
        if(!animated()){reveal(ranges.get(index).start(),1);end(false);return;}
        long elapsed=Math.max(0,now-started);double progress=Math.min(1,elapsed/(double)MOVE);var range=ranges.get(index);
        long dwell=playback==null?DWELL:playback.durationMillis(index)*1_000_000L;
        if(elapsed<MOVE)reveal(range.start(),1-Math.pow(1-progress,3));
        else{
            double moving=Math.min(1,(elapsed-MOVE)/(double)Math.max(1,dwell));
            int offset=range.start()+(int)((range.end()-range.start())*moving);
            if(playback!=null){offset=playback.frames(index,groupEnd,moving);presentation.setAgentName(name+("deleting".equals(area.getClientProperty("shed.agentAnimation"))?" deleting":" writing"),name);}
            offset=Math.max(range.start(),GraphemeBoundary.floor(source,offset));reveal(offset,1);presentation.moveMarker(index,offset);
        }
        if(elapsed>=MOVE+dwell+(playback==null?0:SETTLE)){index=groupEnd;if(index>=ranges.size())end(false);else begin(now);}
    }
    private void reveal(int offset,double amount){
        try{
            var bounds=area.modelToView2D(offset);if(bounds==null){end(true);return;}
            int limit=Math.max(0,area.getHeight()-viewport.getExtentSize().height);
            int target=Math.max(0,Math.min(limit,(int)bounds.getY()-viewport.getExtentSize().height/3));
            viewport.setViewPosition(new Point(viewport.getViewPosition().x,Math.max(0,Math.min(limit,fromY+(int)((target-fromY)*amount)))));
        }catch(javax.swing.text.BadLocationException unavailable){end(true);}
    }
    private void user(){if(!closed){end(true);pause.run();}}
    private void end(boolean clear){
        if(closed)return;closed=true;timer.stop();
        if(playback!=null){playback.close();playback=null;}
        area.removeKeyListener(key);area.removeMouseListener(mouse);area.removeFocusListener(focus);document.removeDocumentListener(edits);
        if(scroll!=null){scroll.removeMouseWheelListener(mouse);scroll.getVerticalScrollBar().removeMouseListener(mouse);scroll.getHorizontalScrollBar().removeMouseListener(mouse);}
        if(presentation.version()==version){if(clear)presentation.clear();else presentation.finishTour();}
        if(area.getClientProperty(OWNER)==this){area.putClientProperty(OWNER,null);area.putClientProperty("shed.agentTour",null);}finished.run();
    }
    boolean running(){return timer.isRunning();}
    @Override public void close(){end(true);}
}
