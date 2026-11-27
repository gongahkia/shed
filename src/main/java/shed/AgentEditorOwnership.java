package shed;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.*;

/** Temporary human-input shield. It never becomes a document or caret owner. */
final class AgentEditorOwnership implements AutoCloseable {
    private final JRootPane root;
    private final Component previous;
    private final boolean visible;
    private final Supplier<List<EditorPane>> panes;
    private record Target(java.lang.ref.WeakReference<EditorPane> pane,boolean editable) {
        void restore(javax.swing.text.JTextComponent area){var owner=pane.get();if(owner!=null&&!owner.isDisposed())area.setEditable(editable);}
    }
    private final Map<javax.swing.text.JTextComponent,Target> editable=new IdentityHashMap<>();
    private final Map<JMenu,Boolean> menus=new IdentityHashMap<>();
    private final KeyEventDispatcher keys;
    private Component interactive;
    private final JPanel shield=new JPanel(new BorderLayout()) {
        @Override public boolean contains(int x,int y){
            if(interactive!=null&&interactive.isShowing()&&SwingUtilities.isDescendingFrom(interactive,root)){
                var point=SwingUtilities.convertPoint(this,x,y,interactive);if(interactive.contains(point))return false;
            }
            return super.contains(x,y);
        }
    };
    private boolean closed;
    private final java.beans.PropertyChangeListener guardEditable=event->{
        if(!closed&&Boolean.TRUE.equals(event.getNewValue())&&event.getSource() instanceof javax.swing.text.JTextComponent area)area.setEditable(false);
    };
    AgentEditorOwnership(JRootPane root,Supplier<List<EditorPane>> panes,String agent,Runnable takeBack) {
        this(root,panes,agent,takeBack,null);
    }
    AgentEditorOwnership(JRootPane root,Supplier<List<EditorPane>> panes,String agent,Runnable takeBack,Component interactive) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Editor ownership belongs to the EDT");
        this.interactive=interactive;
        this.root=root;this.panes=panes;previous=root.getGlassPane();visible=previous.isVisible();
        shield.setOpaque(false);shield.setName("agent.controlShield");
        var strip=new JPanel(new FlowLayout(FlowLayout.TRAILING,10,0));
        var label=new JLabel(agent+" has control");label.putClientProperty("html.disable",true);strip.add(label);
        var stop=new JButton("Take Back Control");stop.setName("agent.takeBack");stop.setToolTipText("Return control to you (Ctrl+Shift+Escape)");stop.addActionListener(e->takeBack.run());strip.add(stop);var top=new JPanel(new FlowLayout(FlowLayout.TRAILING,0,0));top.setOpaque(false);top.setBorder(BorderFactory.createEmptyBorder(root.getJMenuBar()==null?0:root.getJMenuBar().getHeight(),0,0,0));top.add(strip);shield.add(top,BorderLayout.NORTH);
        shield.addMouseListener(new MouseAdapter(){});shield.addMouseMotionListener(new MouseMotionAdapter(){});shield.addMouseWheelListener(e->e.consume());
        keys=e->{
            if(closed||!(e.getSource() instanceof Component c)||!SwingUtilities.isDescendingFrom(c,root))return false;
            if(e.getID()==KeyEvent.KEY_PRESSED&&e.getKeyCode()==KeyEvent.VK_ESCAPE&&e.isControlDown()&&e.isShiftDown()){takeBack.run();return true;}
            return !SwingUtilities.isDescendingFrom(c,strip)&&!(this.interactive!=null&&SwingUtilities.isDescendingFrom(c,this.interactive));
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        if(root.getJMenuBar()!=null)for(int i=0;i<root.getJMenuBar().getMenuCount();i++){var menu=root.getJMenuBar().getMenu(i);if(menu!=null){menus.put(menu,menu.isEnabled());menu.setEnabled(false);}}
        refresh();root.setGlassPane(shield);shield.setVisible(true);stop.requestFocusInWindow();
    }
    void refresh() {
        if(closed)return;
        var live=Collections.newSetFromMap(new IdentityHashMap<javax.swing.text.JTextComponent,Boolean>());
        for(var pane:panes.get())if(!pane.isDisposed()) {var area=pane.getTextArea();live.add(area);if(!editable.containsKey(area)){editable.put(area,new Target(new java.lang.ref.WeakReference<>(pane),area.isEditable()));area.addPropertyChangeListener("editable",guardEditable);}area.setEditable(false);}
        editable.keySet().removeIf(area->{if(live.contains(area))return false;area.removePropertyChangeListener("editable",guardEditable);editable.get(area).restore(area);return true;});
    }
    boolean allowsChatNavigationFrom(Component focused) {
        return !closed&&interactive!=null&&focused!=null
            &&(SwingUtilities.isDescendingFrom(focused,interactive)||SwingUtilities.isDescendingFrom(focused,shield));
    }
    void enableAgentMenus() { if(!closed)menus.forEach(JMenu::setEnabled); }
    @Override public void close() {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Editor ownership belongs to the EDT");
        if(closed)return;closed=true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        editable.forEach((area,value)->{area.removePropertyChangeListener("editable",guardEditable);value.restore(area);});editable.clear();menus.forEach(JMenu::setEnabled);menus.clear();
        shield.setVisible(false);if(root.getGlassPane()==shield){root.setGlassPane(previous);previous.setVisible(visible);}
    }
}
