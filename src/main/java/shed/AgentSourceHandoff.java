package shed;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.Document;

/** Holds only the previously painted viewport until an observed replay is installed. */
final class AgentSourceHandoff implements AutoCloseable {
    private static final String KEY="shed.agentSourceHandoff";
    private final JTextArea area;
    private final Document document;
    private final Rectangle bounds;
    private final Font font;
    private final Color foreground,background;
    private final boolean wrapped;
    private final Timer expiry;
    private BufferedImage image;
    private JScrollPane scroll;
    private boolean installed;
    private final KeyAdapter keys=new KeyAdapter(){public void keyPressed(KeyEvent e){close();}};
    private final MouseAdapter mouse=new MouseAdapter(){public void mousePressed(MouseEvent e){close();}public void mouseWheelMoved(MouseWheelEvent e){close();}};
    private final FocusAdapter focus=new FocusAdapter(){public void focusLost(FocusEvent e){close();}};
    private final DocumentListener edits=new DocumentListener(){public void insertUpdate(DocumentEvent e){close();}public void removeUpdate(DocumentEvent e){close();}public void changedUpdate(DocumentEvent e){close();}};
    private final PropertyChangeListener properties=e->{String name=e.getPropertyName();if("document".equals(name)||"font".equals(name)||"lineWrap".equals(name)||"foreground".equals(name)||"background".equals(name)||"UI".equals(name))close();};
    private final HierarchyListener hierarchy=e->visibilityChanged();
    private final ChangeListener viewport=e->viewportChanged();
    private void visibilityChanged(){if(!area.isShowing())close();}
    private void viewportChanged(){if(!area.getVisibleRect().equals(bounds))close();}

    private AgentSourceHandoff(JTextArea area,Rectangle bounds,BufferedImage image){
        this.area=area;this.document=area.getDocument();this.bounds=bounds;this.image=image;
        font=area.getFont();foreground=area.getForeground();background=area.getBackground();wrapped=area.getLineWrap();
        expiry=new Timer(1000,e->close());expiry.setRepeats(false);
    }
    static AgentSourceHandoff capture(JTextArea area){
        clear(area);if(!area.isShowing())return null;
        var bounds=area.getVisibleRect();if(bounds.isEmpty())return null;
        try{return new AgentSourceHandoff(area,bounds,SwingComponentCapture.capture(area,bounds.x,bounds.y,bounds.width,bounds.height,4_000_000));}
        catch(IllegalArgumentException unavailable){return null;}
    }
    void install(){
        if(image==null)return;
        if(area.getDocument()!=document||!area.isShowing()||!area.getVisibleRect().equals(bounds)||!java.util.Objects.equals(font,area.getFont())
                ||!java.util.Objects.equals(foreground,area.getForeground())||!java.util.Objects.equals(background,area.getBackground())||wrapped!=area.getLineWrap()){close();return;}
        clear(area);installed=true;area.putClientProperty(KEY,this);
        area.addKeyListener(keys);area.addMouseListener(mouse);area.addFocusListener(focus);area.addPropertyChangeListener(properties);area.addHierarchyListener(hierarchy);document.addDocumentListener(edits);
        scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,area);
        if(scroll!=null){scroll.addMouseWheelListener(mouse);scroll.getViewport().addChangeListener(viewport);scroll.getVerticalScrollBar().addMouseListener(mouse);scroll.getHorizontalScrollBar().addMouseListener(mouse);}
        expiry.start();area.repaint(bounds);
    }
    static void clear(JTextArea area){if(area.getClientProperty(KEY) instanceof AgentSourceHandoff handoff)handoff.close();}
    static void paint(JTextArea area,Graphics graphics){
        if(area.getClientProperty(KEY) instanceof AgentSourceHandoff handoff&&handoff.image!=null){
            if(area.getClientProperty(AgentChangePlayback.KEY)!=null){handoff.close();return;}
            graphics.drawImage(handoff.image,handoff.bounds.x,handoff.bounds.y,handoff.bounds.width,handoff.bounds.height,null);
        }
    }
    @Override public void close(){
        expiry.stop();if(image==null)return;image.flush();image=null;
        if(installed){
            area.removeKeyListener(keys);area.removeMouseListener(mouse);area.removeFocusListener(focus);area.removePropertyChangeListener(properties);area.removeHierarchyListener(hierarchy);document.removeDocumentListener(edits);
            if(scroll!=null){scroll.removeMouseWheelListener(mouse);scroll.getViewport().removeChangeListener(viewport);scroll.getVerticalScrollBar().removeMouseListener(mouse);scroll.getHorizontalScrollBar().removeMouseListener(mouse);}
            if(area.getClientProperty(KEY)==this)area.putClientProperty(KEY,null);
            installed=false;area.repaint(bounds);
        }
    }
}
