package shed;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.lang.ref.WeakReference;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Click-through, window-owned feedback; never moves the system pointer. */
final class AgentUiPresentation extends JComponent implements AutoCloseable {
    private static final String KEY="shed.agentUiPresence";
    private static final Color COLOR = new Color(20, 115, 82);
    private static final double[][] POINTER = {{0,0},{20,7},{21,9},{14,12},{12,14},{10,22},{8,23},{6,20},{0,3},{-1,1},{0,0},{0,0}};
    private static final double[][] BEAM = {{2,-10},{14,-10},{14,-8},{10,-8},{10,14},{14,14},{14,16},{2,16},{2,14},{6,14},{6,-8},{2,-8}};
    private JLayeredPane layer;
    private WeakReference<JRootPane> home=new WeakReference<>(null);
    private WeakReference<Component> target = new WeakReference<>(null);
    private String agent = "Agent";
    private Icon logo;
    private Point2D.Double point = new Point2D.Double(), origin = new Point2D.Double();
    private float progress, morph, cursorAlpha=1;
    private boolean typing, motion, settled, badgeOnly;
    private Point explicitPoint,lastScreen;
    private String activity = "";
    private double activityPhase;
    private long generation, followStarted;
    private boolean following;

    AgentUiPresentation() { setOpaque(false); setFocusable(false); }
    void attach(JRootPane root) { requireEdt(); root.putClientProperty(KEY,this);home=new WeakReference<>(root); }
    static AgentUiPresentation find(Component component) { var root=SwingUtilities.getRootPane(component); return root!=null&&root.getClientProperty(KEY) instanceof AgentUiPresentation value?value:null; }
    long beginFollow(Component component,Point anchor,String agent,boolean motion) {
        showAt(component,anchor,agent,motion);following=true;followStarted=System.nanoTime();return generation;
    }
    boolean follows(long token) { return following&&generation==token&&layer!=null; }
    void follow(long token,Point anchor,boolean typing,String activity) {
        follow(token,anchor,typing,activity,false);
    }
    void follow(long token,Point anchor,boolean typing,String activity,boolean badgeOnly) {
        if(!follows(token))return;this.explicitPoint=new Point(anchor);this.activity=activity;
        this.badgeOnly=badgeOnly;
        frame(Math.min(1,(System.nanoTime()-followStarted)/300_000_000f),typing);
    }
    void endFollow(long token) { if(follows(token))settle(); }

    @Override public boolean contains(int x, int y) { return false; }

    void show(Component control, String agent, boolean motion) {
        requireEdt();generation++;following=false;
        JRootPane root = SwingUtilities.getRootPane(control);
        if (root == null) { close(); return; }
        JLayeredPane destination = root.getLayeredPane();
        if (layer != destination) {
            Point screen=lastScreen==null?null:new Point(lastScreen);
            if(layer!=null&&layer.isShowing()&&destination.isShowing()) {screen=new Point((int)point.x,(int)point.y);SwingUtilities.convertPointToScreen(screen,this);}
            if(layer!=null){var old=layer;old.remove(this);old.repaint(drawingBounds());}
            layer = destination;
            setBounds(0, 0, layer.getWidth(), layer.getHeight());
            layer.add(this, JLayeredPane.DRAG_LAYER);
            Point initial = SwingUtilities.convertPoint(control, new Point(0, control.getHeight()), this);
            if(screen!=null){SwingUtilities.convertPointFromScreen(screen,this);point=new Point2D.Double(screen.x,screen.y);}
            else point = new Point2D.Double(initial.x - 35, initial.y + 25);
        }
        this.target = new WeakReference<>(control); this.agent = agent; this.motion = motion;
        explicitPoint = null; activity = ""; badgeOnly=false;
        logo = ProviderIcons.forValue(agent, COLOR);
        origin = new Point2D.Double(point.x, point.y); progress = 0; typing = false; settled = false;
        frame(0, false);
    }

    void showAt(Component control, Point point, String agent, boolean motion) {
        show(control,agent,motion); explicitPoint = new Point(point); frame(0,false);
    }

    void anchor(Point point) {requireEdt();explicitPoint=new Point(point);}

    void activity(String value,double phase) {
        requireEdt(); activity=value; activityPhase=Math.clamp(phase,0,1); repaint(drawingBounds());
    }
    void badgeOnly(boolean value){requireEdt();badgeOnly=value;}

    void frame(float progress, boolean typing) {
        requireEdt();
        Rectangle old = drawingBounds();
        this.progress = Math.clamp(progress, 0, 1); this.typing = typing;
        cursorAlpha=motion?Math.clamp(cursorAlpha+(badgeOnly?-.16f:.16f),0,1):badgeOnly?0:1;
        if(typing&&!following)explicitPoint=null;
        morph = motion ? Math.clamp(morph + (typing ? .16f : -.16f), 0, 1) : typing ? 1 : 0;
        Component control = target.get();
        if (layer != null && control != null) {
            setSize(layer.getSize());
            Point anchor = anchor(control);
            double blend = motion ? 1 - Math.pow(1 - Math.min(1, this.progress / .55), 3) : 1;
            if (typing) {
                if(following&&this.progress<1){point.x=origin.x+(anchor.x-origin.x)*blend;point.y=origin.y+(anchor.y-origin.y)*blend;}
                else {point.x += (anchor.x - point.x) * (motion?.65:1);point.y += (anchor.y - point.y) * (motion?.65:1);}
            } else if (morph == 0 && !settled) {
                point.x = origin.x + (anchor.x - origin.x) * blend;
                point.y = origin.y + (anchor.y - origin.y) * blend;
            }
            if(isShowing()){lastScreen=new Point((int)point.x,(int)point.y);SwingUtilities.convertPointToScreen(lastScreen,this);}
            repaint(old.union(drawingBounds()));
        }
    }

    private Point anchor(Component control) {
        if(explicitPoint!=null)return SwingUtilities.convertPoint(control,explicitPoint,this);
        Point result = new Point(Math.max(0, control.getWidth() / 2), Math.max(0, control.getHeight() / 2));
        if (control instanceof JTextComponent field) {
            try {
                var caret = field.modelToView2D(field.getCaretPosition());
                if (caret != null) result = new Point((int)caret.getX() - 8, (int)caret.getCenterY());
            } catch (javax.swing.text.BadLocationException unavailable) { return new Point((int)point.x, (int)point.y); }
        }
        return SwingUtilities.convertPoint(control, result, this);
    }

    private Rectangle drawingBounds() { return new Rectangle((int)point.x - 300, (int)point.y - 35, 600, 90); }

    @Override protected void paintComponent(Graphics graphics) {
        if (layer == null) return;
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(point.x, point.y);
            if(cursorAlpha>0){
                var cursor=(Graphics2D)g.create();
                try{
                    cursor.setComposite(AlphaComposite.SrcOver.derive(cursorAlpha));
                    if (!settled && progress >= .55f && !typing && !badgeOnly) {
                        float phase = (progress - .55f) / .45f;
                        int radius = motion ? 5 + (int)(18 * phase) : 10;
                        cursor.setColor(new Color(COLOR.getRed(), COLOR.getGreen(), COLOR.getBlue(), (int)(130 * (1 - phase))));
                        cursor.setStroke(new BasicStroke(2)); cursor.drawOval(-radius, -radius, radius * 2, radius * 2);
                    }
                    Path2D shape = shape(morph);
                    cursor.setColor(new Color(0,0,0,85));cursor.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));cursor.draw(shape);
                    cursor.setColor(Color.WHITE);cursor.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));cursor.draw(shape);
                    cursor.setColor(COLOR);cursor.fill(shape);
                }finally{cursor.dispose();}
            }
            g.setColor(COLOR);
            if(activity.equals("scrolling")) {
                g.setColor(Color.WHITE);g.setStroke(new BasicStroke(2));g.drawRoundRect(-12,9,8,15,5,5);
                int position=12+(int)(6*activityPhase);g.drawLine(-8,position,-8,position+2);
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            String label = AgentUiTarget.bounded(agent,22)+(activity.isEmpty()?"":" · "+activity);
            int iconWidth = logo == null ? 0 : logo.getIconWidth() + 5;
            int width = g.getFontMetrics().stringWidth(label) + iconWidth + 12;
            int badgeX = point.x + 26 + width > getWidth() - 8 ? -width - 8 : 26;
            g.fillRoundRect(badgeX, 16, width, 24, 7, 7);
            if (logo != null) logo.paintIcon(this,g,badgeX + 5,19);
            g.setColor(Color.WHITE);g.drawString(label,badgeX + 6 + iconWidth,32);
        } finally { g.dispose(); }
    }

    private static Path2D shape(float morph) {
        double[][] points = new double[POINTER.length][2];
        for (int i=0;i<points.length;i++) for(int j=0;j<2;j++) points[i][j] = POINTER[i][j] * (1-morph) + BEAM[i][j] * morph;
        var path = new Path2D.Double();
        path.moveTo((points[0][0]+points[11][0])/2,(points[0][1]+points[11][1])/2);
        for(int i=0;i<points.length;i++) {
            double[] next=points[(i+1)%points.length];
            path.quadTo(points[i][0],points[i][1],(points[i][0]+next[0])/2,(points[i][1]+next[1])/2);
        }
        path.closePath();return path;
    }

    /** Keep the last location between requests without retaining a control or running a timer. */
    void settle() { requireEdt();var destination=home.get();if(layer!=null&&!layer.isShowing()&&destination!=null&&destination.isShowing()&&layer!=destination.getLayeredPane())show(destination,agent,motion);Rectangle old=drawingBounds(); settled = true; following=false; typing = false; activity=""; target.clear(); repaint(old.union(drawingBounds())); }
    boolean installed() { return layer != null; }
    Point2D pointerPosition() { return new Point2D.Double(point.x,point.y); }
    float morph() { return morph; }
    float cursorAlpha(){return cursorAlpha;}
    boolean badgeOnly(){return badgeOnly;}
    @Override public void close() {
        requireEdt();
        if (layer != null) { JLayeredPane old = layer; Rectangle bounds = drawingBounds(); old.remove(this); old.repaint(bounds); }
        generation++;following=false;lastScreen=null;layer = null; target.clear(); logo = null; typing = false; progress = 0; morph = 0;badgeOnly=false;cursorAlpha=1;
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("UI presentation belongs on the EDT");
    }
}
