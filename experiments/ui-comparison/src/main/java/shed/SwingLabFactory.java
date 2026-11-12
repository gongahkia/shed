package shed;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public final class SwingLabFactory implements LabFactory {
    static Color color(int rgb) { return new Color(rgb, true); }
    private static int px(LabModel.State s, int size) { return UiZoom.scale(size, s.style().zoom()); }
    private static Font font(LabModel.State s, int size, boolean bold) { return new Font(s.style().fontFamily(), bold ? Font.BOLD : Font.PLAIN, px(s,Math.round(size*s.style().fontSize()/13f))); }
    private static JButton button(String text, String id, Runnable action) {
        JButton button = new JButton(text); button.setName(id); button.addActionListener(e -> action.run());
        button.getAccessibleContext().setAccessibleName(text); WorkbenchStyle.quiet(button); return button;
    }
    private abstract static class View extends JPanel implements LabView {
        final LabModel model; final String slot; AutoCloseable subscription;
        View(LabModel model, String slot) { this.model = model; this.slot = slot; }
        public JComponent component() { return this; }
        @Override protected void paintComponent(Graphics g) { super.paintComponent(g); model.painted(slot); }
        public Rectangle target(String id) {
            Component child = find(this,id);
            if (child == null || !child.isShowing()) return null;
            return new Rectangle(child.getLocationOnScreen(),child.getSize());
        }
        static Component find(Component root,String id) {
            if (id.equals(root.getName())) return root;
            if (root instanceof Container container) for (Component child: container.getComponents()) { var hit=find(child,id); if(hit!=null)return hit; }
            return null;
        }
        public void focusSearch() { }
        public void close() { try { subscription.close(); } catch(Exception error) { throw new IllegalStateException(error); } }
    }
    @Override public LabView navigation(LabModel model) {
        return new View(model,"navigation") {
            final JPanel documents = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
            final JLabel status = new JLabel();
            LabModel.State previous;
            {
                setLayout(new BorderLayout());
                JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false);
                JLabel title = new JLabel("SHED  /  Workbench"); top.add(title,BorderLayout.WEST);
                JButton appearance=button("Appearance", "appearance", model::show);top.add(appearance,BorderLayout.EAST);
                add(top,BorderLayout.NORTH);
                JScrollPane strip = new JScrollPane(documents,JScrollPane.VERTICAL_SCROLLBAR_NEVER,JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
                strip.setBorder(null); add(strip,BorderLayout.CENTER); add(status,BorderLayout.SOUTH);
                subscription = model.listen(s -> {
                    setBackground(color(s.style().raised())); documents.setBackground(getBackground());
                    setBorder(BorderFactory.createEmptyBorder(px(s,10),px(s,18),px(s,10),px(s,18)));
                    appearance.setPreferredSize(new Dimension(px(s,120),px(s,40)));
                    title.setFont(font(s,14,true)); title.setForeground(color(s.style().foreground()));
                    status.setText(s.status()); status.setFont(font(s,12,false)); status.setForeground(color(s.style().muted()));
                    if (previous == null || !previous.documents().equals(s.documents()) || !previous.style().equals(s.style())) {
                        documents.removeAll(); int index=0;
                        for (var d:s.documents()) {
                            JButton tab=button((d.dirty()?"● ":"")+d.name(),"doc."+index++,()->model.activate(d.id()));
                            tab.setFont(font(s,13,d.active())); tab.setForeground(color(s.style().foreground()));
                            tab.setPreferredSize(new Dimension(px(s,180),px(s,40)));
                            tab.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,px(s,2),0,
                                color(d.active()?s.style().accent():s.style().raised())),BorderFactory.createEmptyBorder(0,px(s,12),0,px(s,12))));
                            documents.add(tab);
                        }
                        revalidate(); repaint();
                    }
                    previous=s;
                });
            }
        };
    }
    @Override public LabView settings(LabModel model) {
        return new View(model,"settings") {
            final JTextField search = new JTextField();
            final JPanel rows = new JPanel();
            final JLabel feedback = new JLabel();
            final JLabel heading = new JLabel("Appearance");
            final JPanel toolbar = new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEFT,4,0))) { public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);} };
            LabModel.State previous;
            boolean syncing;
            {
                setLayout(new BorderLayout(0,12));
                JPanel top=new JPanel(); top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS)); top.setOpaque(false);
                heading.setAlignmentX(0); top.add(heading);
                JLabel subtitle=new JLabel("Make space for your work."); subtitle.setAlignmentX(0);top.add(subtitle);
                toolbar.setOpaque(false); toolbar.setAlignmentX(0);
                toolbar.add(button("Dark","dark",()->model.update("theme","shed-dark")));
                toolbar.add(button("Light","light",()->model.update("theme","shed-light")));
                toolbar.add(button("Detach","detach",model::detach));
                toolbar.add(button("Close","dismiss",model::dismiss));top.add(toolbar);
                search.setName("query"); search.getAccessibleContext().setAccessibleName("Search appearance settings");
                search.setToolTipText("Filter appearance settings"); search.setAlignmentX(0);
                JLabel searchLabel=new JLabel("Search appearance settings");searchLabel.setLabelFor(search);searchLabel.setAlignmentX(0);top.add(searchLabel);top.add(search);
                rows.setLayout(new BoxLayout(rows,BoxLayout.Y_AXIS));
                StretchPanel content=new StretchPanel();content.setLayout(new BorderLayout());content.setOpaque(false);content.add(top,BorderLayout.NORTH);content.add(rows,BorderLayout.CENTER);
                JScrollPane scroll=new JScrollPane(content); scroll.setBorder(null);scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);add(scroll,BorderLayout.CENTER);
                feedback.getAccessibleContext().setAccessibleName("Settings result"); add(feedback,BorderLayout.SOUTH);
                search.getDocument().addDocumentListener(new DocumentListener() {
                    public void insertUpdate(DocumentEvent e){if(!syncing)model.query(search.getText());}
                    public void removeUpdate(DocumentEvent e){if(!syncing)model.query(search.getText());}
                    public void changedUpdate(DocumentEvent e){if(!syncing)model.query(search.getText());}
                });
                KeyboardFocusSupport.installEscape(this,model::dismiss);
                subscription=model.listen(s -> {
                    setBackground(color(s.style().surface())); rows.setBackground(getBackground());
                    setBorder(BorderFactory.createEmptyBorder(px(s,20),px(s,20),px(s,20),px(s,20)));
                    if(!search.getText().equals(s.query())) { syncing=true;search.setText(s.query());syncing=false; }
                    ((JButton)View.find(toolbar,"detach")).setText(s.detached()?"Dock":"Detach");
                    for(Component action:toolbar.getComponents()){int width=action.getFontMetrics(action.getFont()).stringWidth(((JButton)action).getText())+px(s,24);action.setPreferredSize(new Dimension(Math.max(px(s,64),width),px(s,40)));}
                    heading.setFont(font(s,20,true)); heading.setForeground(color(s.style().foreground()));
                    subtitle.setForeground(color(s.style().muted())); subtitle.setFont(font(s,12,false));
                    search.setFont(font(s,13,false)); search.setPreferredSize(new Dimension(200,px(s,40)));search.setMaximumSize(new Dimension(Integer.MAX_VALUE,px(s,40)));
                    feedback.setText("<html>"+escape(s.settings().isEmpty()?"No appearance settings match. Try font or theme.":s.feedback())+"</html>"); feedback.getAccessibleContext().setAccessibleDescription(s.feedback()); feedback.setFont(font(s,12,false));
                    feedback.setForeground(color(s.error()?s.style().accent():s.style().muted()));
                    if (previous==null || !previous.settings().equals(s.settings()) || !previous.style().equals(s.style()) || !previous.feedback().equals(s.feedback())) {
                        rows.removeAll();
                        if(s.settings().isEmpty()) { JLabel empty=new JLabel("No appearance settings match.");empty.setForeground(color(s.style().muted()));rows.add(empty); }
                        for(var setting:s.settings()) {
                            JPanel row=new JPanel(new BorderLayout(0,px(s,6))); row.setOpaque(false);
                            row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,color(s.style().border())),BorderFactory.createEmptyBorder(px(s,12),0,px(s,12),0)));
                            JLabel label=new JLabel(LabModel.title(setting.key()));label.setFont(font(s,13,true));label.setForeground(color(s.style().foreground()));row.add(label,BorderLayout.NORTH);
                            JTextField value=new JTextField(model.draft(setting));value.setName("value."+setting.key());
                            value.setFont(font(s,13,false));value.getAccessibleContext().setAccessibleName(LabModel.title(setting.key())+" ("+setting.key()+")");label.setLabelFor(value);
                            JPanel input=new JPanel(new BorderLayout(px(s,8),0));input.setOpaque(false);input.add(value,BorderLayout.CENTER);
                            JButton apply=button("Apply","apply."+setting.key(),()->model.update(setting.key(),value.getText()));
                            apply.setPreferredSize(new Dimension(px(s,70),px(s,40)));input.add(apply,BorderLayout.EAST);
                            value.addActionListener(e->model.update(setting.key(),value.getText()));
                            value.getDocument().addDocumentListener(new DocumentListener(){
                                void changed(){model.draft(setting.key(),value.getText());}
                                public void insertUpdate(DocumentEvent e){changed();} public void removeUpdate(DocumentEvent e){changed();} public void changedUpdate(DocumentEvent e){changed();}
                            });
                            row.add(input,BorderLayout.CENTER);
                            JLabel detail=new JLabel("<html>"+escape(setting.description())+"</html>");detail.setFont(font(s,12,false));detail.setForeground(color(s.style().muted()));
                            detail.setPreferredSize(new Dimension(220,px(s,28)));row.add(detail,BorderLayout.SOUTH);
                            row.setMaximumSize(new Dimension(Integer.MAX_VALUE,row.getPreferredSize().height));rows.add(row);
                        }
                        rows.revalidate();rows.repaint();
                    }
                    previous=s;
                });
            }
            public void focusSearch(){search.scrollRectToVisible(new Rectangle(search.getSize()));search.requestFocusInWindow();}
        };
    }
    private static final class StretchPanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize(){return new Dimension(380,500);}
        public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}
        public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(24,r.height-24);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
    static String escape(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
}
