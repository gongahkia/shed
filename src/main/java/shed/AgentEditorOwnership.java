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
    private final Map<javax.swing.text.JTextComponent,Boolean> editable=new IdentityHashMap<>();
    private final Map<JMenu,Boolean> menus=new IdentityHashMap<>();
    private final KeyEventDispatcher keys;
    private final JPanel shield=new JPanel(new BorderLayout());
    private boolean closed;
    AgentEditorOwnership(JRootPane root,Supplier<List<EditorPane>> panes,String agent,Runnable takeBack) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Editor ownership belongs to the EDT");
        this.root=root;this.panes=panes;previous=root.getGlassPane();visible=previous.isVisible();
        shield.setOpaque(false);shield.setName("agent.controlShield");
        var strip=new JPanel(new FlowLayout(FlowLayout.TRAILING,10,6));
        var label=new JLabel(agent+" has control");label.putClientProperty("html.disable",true);strip.add(label);
        var stop=new JButton("Take Back Control");stop.setName("agent.takeBack");stop.setToolTipText("Return control to you (Ctrl+Shift+Escape)");stop.addActionListener(e->takeBack.run());strip.add(stop);shield.add(strip,BorderLayout.NORTH);
        shield.addMouseListener(new MouseAdapter(){});shield.addMouseMotionListener(new MouseMotionAdapter(){});shield.addMouseWheelListener(e->e.consume());
        keys=e->{
            if(closed||!(e.getSource() instanceof Component c)||!SwingUtilities.isDescendingFrom(c,root))return false;
            if(e.getID()==KeyEvent.KEY_PRESSED&&e.getKeyCode()==KeyEvent.VK_ESCAPE&&e.isControlDown()&&e.isShiftDown()){takeBack.run();return true;}
            return !SwingUtilities.isDescendingFrom(c,strip);
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        if(root.getJMenuBar()!=null)for(int i=0;i<root.getJMenuBar().getMenuCount();i++){var menu=root.getJMenuBar().getMenu(i);if(menu!=null){menus.put(menu,menu.isEnabled());menu.setEnabled(false);}}
        refresh();root.setGlassPane(shield);shield.setVisible(true);stop.requestFocusInWindow();
    }
    void refresh() {
        if(closed)return;
        var live=Collections.newSetFromMap(new IdentityHashMap<javax.swing.text.JTextComponent,Boolean>());
        for(var pane:panes.get())if(!pane.isDisposed()) {var area=pane.getTextArea();live.add(area);editable.putIfAbsent(area,area.isEditable());area.setEditable(false);}
        editable.keySet().removeIf(area->{if(live.contains(area))return false;area.setEditable(editable.get(area));return true;});
    }
    @Override public void close() {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Editor ownership belongs to the EDT");
        if(closed)return;closed=true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        editable.forEach((area,value)->area.setEditable(value));editable.clear();menus.forEach(JMenu::setEnabled);menus.clear();
        shield.setVisible(false);if(root.getGlassPane()==shield){root.setGlassPane(previous);previous.setVisible(visible);}
    }
}
