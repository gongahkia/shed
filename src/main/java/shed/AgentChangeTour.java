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
    private boolean closed;
    private int index,fromY;
    private long started,version;
    private static final long MOVE=300_000_000L,DWELL=700_000_000L;
    private final KeyAdapter key=new KeyAdapter(){public void keyPressed(KeyEvent event){user();}};
    private final MouseAdapter mouse=new MouseAdapter(){public void mousePressed(MouseEvent event){user();}public void mouseWheelMoved(MouseWheelEvent event){user();}};
    private final FocusAdapter focus=new FocusAdapter(){public void focusLost(FocusEvent event){if(event.getOppositeComponent()!=null&&SwingUtilities.getWindowAncestor(event.getOppositeComponent())==SwingUtilities.getWindowAncestor(area))user();}};
    private final DocumentListener edits=new DocumentListener(){public void insertUpdate(DocumentEvent e){end(true);}public void removeUpdate(DocumentEvent e){end(true);}public void changedUpdate(DocumentEvent e){end(true);}};

    AgentChangeTour(JTextArea area,AgentEditPresentation presentation,List<AgentEditPresentation.Range> ranges,String source,String name,BooleanSupplier current,Runnable pause,Runnable finished){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Change tour belongs to the EDT");
        this.area=area;this.presentation=presentation;this.ranges=List.copyOf(ranges);this.source=source;this.name=name;this.current=current;this.pause=pause;this.finished=finished;
        this.document=area.getDocument();
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
    private void begin(long now){started=now;fromY=viewport.getViewPosition().y;presentation.showApplied(ranges);presentation.setAgentName(name);version=presentation.version();presentation.moveMarker(index,ranges.get(index).start());area.putClientProperty("shed.agentTour",index+1);}
    void advance(long now){
        if(closed)return;
        if(!area.isShowing()||area.getDocument()!=document||!current.getAsBoolean()||presentation.version()!=version){end(true);return;}
        if(!animated()){reveal(ranges.get(index).start(),1);end(false);return;}
        long elapsed=Math.max(0,now-started);double progress=Math.min(1,elapsed/(double)MOVE);var range=ranges.get(index);
        if(elapsed<MOVE)reveal(range.start(),1-Math.pow(1-progress,3));
        else{
            double moving=Math.min(1,(elapsed-MOVE)/(double)DWELL);
            int offset=range.start()+(int)((range.end()-range.start())*moving);
            offset=Math.max(range.start(),GraphemeBoundary.floor(source,offset));reveal(offset,1);presentation.moveMarker(index,offset);
        }
        if(elapsed>=MOVE+DWELL){if(++index>=ranges.size())end(false);else begin(now);}
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
        area.removeKeyListener(key);area.removeMouseListener(mouse);area.removeFocusListener(focus);document.removeDocumentListener(edits);
        if(scroll!=null){scroll.removeMouseWheelListener(mouse);scroll.getVerticalScrollBar().removeMouseListener(mouse);scroll.getHorizontalScrollBar().removeMouseListener(mouse);}
        if(clear&&presentation.version()==version)presentation.clear();area.putClientProperty("shed.agentTour",null);finished.run();
    }
    boolean running(){return timer.isRunning();}
    @Override public void close(){end(true);}
}
