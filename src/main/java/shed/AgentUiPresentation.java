package shed;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.lang.ref.WeakReference;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Click-through, window-owned feedback; never moves the system pointer. */
final class AgentUiPresentation extends JComponent implements AutoCloseable {
    private static final Color COLOR = new Color(20, 115, 82);
    private static final double[][] POINTER = {{0,0},{20,7},{21,9},{14,12},{12,14},{10,22},{8,23},{6,20},{0,3},{-1,1},{0,0},{0,0}};
    private static final double[][] BEAM = {{2,-10},{14,-10},{14,-8},{10,-8},{10,14},{14,14},{14,16},{2,16},{2,14},{6,14},{6,-8},{2,-8}};
    private JLayeredPane layer;
    private WeakReference<Component> target = new WeakReference<>(null);
    private String agent = "Agent";
    private Icon logo;
    private Point2D.Double point = new Point2D.Double(), origin = new Point2D.Double();
    private float progress, morph;
    private boolean typing, motion, settled;
    private Point explicitPoint;
    private String activity = "";
    private double activityPhase;

    AgentUiPresentation() { setOpaque(false); setFocusable(false); }
    @Override public boolean contains(int x, int y) { return false; }

    void show(Component control, String agent, boolean motion) {
        requireEdt();
        JRootPane root = SwingUtilities.getRootPane(control);
        if (root == null) { close(); return; }
        JLayeredPane destination = root.getLayeredPane();
        if (layer != destination) {
            close(); layer = destination;
            setBounds(0, 0, layer.getWidth(), layer.getHeight());
            layer.add(this, JLayeredPane.DRAG_LAYER);
            Point initial = SwingUtilities.convertPoint(control, new Point(0, control.getHeight()), this);
            point = new Point2D.Double(initial.x - 35, initial.y + 25);
        }
        this.target = new WeakReference<>(control); this.agent = agent; this.motion = motion;
        explicitPoint = null; activity = "";
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

    void frame(float progress, boolean typing) {
        requireEdt();
        Rectangle old = drawingBounds();
        this.progress = Math.clamp(progress, 0, 1); this.typing = typing;
        if(typing)explicitPoint=null;
        morph = motion ? Math.clamp(morph + (typing ? .16f : -.16f), 0, 1) : typing ? 1 : 0;
        Component control = target.get();
        if (layer != null && control != null) {
            setSize(layer.getSize());
            Point anchor = anchor(control);
            double blend = motion ? 1 - Math.pow(1 - Math.min(1, this.progress / .55), 3) : 1;
            if (typing) {
                point.x += (anchor.x - point.x) * (motion ? .65 : 1);
                point.y += (anchor.y - point.y) * (motion ? .65 : 1);
            } else if (morph == 0 && !settled) {
                point.x = origin.x + (anchor.x - origin.x) * blend;
                point.y = origin.y + (anchor.y - origin.y) * blend;
            }
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
            if (!settled && progress >= .55f && !typing) {
                float phase = (progress - .55f) / .45f;
                int radius = motion ? 5 + (int)(18 * phase) : 10;
                g.setColor(new Color(COLOR.getRed(), COLOR.getGreen(), COLOR.getBlue(), (int)(130 * (1 - phase))));
                g.setStroke(new BasicStroke(2)); g.drawOval(-radius, -radius, radius * 2, radius * 2);
            }
            Path2D shape = shape(morph);
            g.setColor(new Color(0,0,0,85));g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));g.draw(shape);
            g.setColor(Color.WHITE);g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));g.draw(shape);
            g.setColor(COLOR);g.fill(shape);
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
    void settle() { requireEdt(); Rectangle old=drawingBounds(); settled = true; morph = 0; typing = false; activity=""; target.clear(); repaint(old.union(drawingBounds())); }
    boolean installed() { return layer != null; }
    Point2D pointerPosition() { return new Point2D.Double(point.x,point.y); }
    float morph() { return morph; }
    @Override public void close() {
        requireEdt();
        if (layer != null) { JLayeredPane old = layer; Rectangle bounds = drawingBounds(); old.remove(this); old.repaint(bounds); }
        layer = null; target.clear(); logo = null; typing = false; progress = 0; morph = 0;
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("UI presentation belongs on the EDT");
    }
}
