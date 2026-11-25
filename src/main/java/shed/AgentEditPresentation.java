package shed;

import java.awt.*;
import java.awt.event.HierarchyListener;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.jdesktop.swingx.painter.BusyPainter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;

/** Transient agent decoration; the human caret and authoritative source remain untouched. */
final class AgentEditPresentation implements AutoCloseable {
    record Range(int start, int end) { }
    private static final int MAX_RANGES = 32, TICKS = 48;
    private static final String PRESENTATION_KEY="shed.agentPresentation";
    private final JTextArea area;
    private final Timer timer;
    private final List<Object> tags = new ArrayList<>();
    private List<Range> ranges = List.of();
    private Document document;
    private Highlighter highlighter;
    private boolean closed, pending, touring, reading;
    private int ticks, maximumTicks = TICKS, activeRange, markerOffset;
    private long version;
    private String agentName = "Agent", agentProvider = "Agent";
    private final BusyPainter spinner = new BusyPainter(20);
    private AgentBufferIntent intent;
    private JViewport intentViewport;
    private JScrollPane intentScroll;
    private int intentFromY,intentTargetY;
    private final KeyAdapter intentKeys=new KeyAdapter(){@Override public void keyPressed(KeyEvent e){clear();}};
    private final MouseAdapter intentMouse=new MouseAdapter(){@Override public void mousePressed(MouseEvent e){clear();}@Override public void mouseWheelMoved(MouseWheelEvent e){clear();}};
    private final FocusAdapter intentFocus=new FocusAdapter(){@Override public void focusLost(FocusEvent e){if(e.getOppositeComponent()!=null)clear();}};
    void setAgentName(String name) { setAgentName(name,name); }
    void setAgentName(String label,String provider) { checkEdt(); agentName = label; agentProvider = provider; repaintRanges(); }
    private final DocumentListener edits = new DocumentListener() {
        public void insertUpdate(DocumentEvent event) { clear(); }
        public void removeUpdate(DocumentEvent event) { clear(); }
        public void changedUpdate(DocumentEvent event) { clear(); }
    };
    private final PropertyChangeListener properties = event -> {
        if ("document".equals(event.getPropertyName())) { clear(); observe(); }
        else if ("highlighter".equals(event.getPropertyName())) clear();
    };
    private final HierarchyListener hierarchy = event -> visibilityChanged();

    AgentEditPresentation(JTextArea area) {
        checkEdt(); this.area = java.util.Objects.requireNonNull(area);
        timer = new Timer(50, event -> advance()); timer.setCoalesce(true);
        spinner.setPoints(16); spinner.setTrailLength(8); spinner.setAntialiasing(true);
        spinner.setPointShape(new java.awt.geom.RoundRectangle2D.Double(0,0,5,2,2,2));
        area.addPropertyChangeListener(properties); area.addHierarchyListener(hierarchy); observe();
        area.putClientProperty(PRESENTATION_KEY,this);
    }
    void showProgress(int start, int end, String name) { showProgress(start,end,name,"thinking"); }
    void showProgress(int start,int end,String name,String phase) { showPending(start,end); maximumTicks=600; setAgentName(name+" "+phase,name); }
    void showIntent(AgentBufferIntent event){
        show(event.ranges(),true);if(ranges.isEmpty())return;
        intent=event;activeRange=event.focus();markerOffset=ranges.get(activeRange).start();maximumTicks=120;
        setAgentName(event.label(activeRange),event.agent());
        area.putClientProperty("shed.agentIntent",event.kind());area.putClientProperty("shed.agentIntentStatus",event.status());
        area.putClientProperty("shed.agentActivity",agentName);area.putClientProperty("shed.agentDirection",event.examining()?"top-to-bottom":event.kind().equals("search")?"bottom-to-top":"left-to-right");
        area.addKeyListener(intentKeys);area.addMouseListener(intentMouse);area.addFocusListener(intentFocus);
        intentScroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,area);
        if(intentScroll!=null){intentScroll.addMouseWheelListener(intentMouse);intentScroll.getVerticalScrollBar().addMouseListener(intentMouse);intentScroll.getHorizontalScrollBar().addMouseListener(intentMouse);}
        if(area.isShowing())try{
            var bounds=area.modelToView2D(markerOffset);
            intentViewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,area);
            if(bounds!=null&&intentViewport!=null){intentFromY=intentViewport.getViewPosition().y;intentTargetY=Math.max(0,Math.min(Math.max(0,area.getHeight()-intentViewport.getExtentSize().height),(int)bounds.getY()-intentViewport.getExtentSize().height/3));if(!animated())scrollIntent(1);}
        }catch(BadLocationException unavailable){clear();}
        repaintRanges();
    }
    private void scrollIntent(double progress){if(intentViewport!=null)intentViewport.setViewPosition(new Point(intentViewport.getViewPosition().x,intentFromY+(int)((intentTargetY-intentFromY)*(1-Math.pow(1-progress,3)))));}
    void showReading(int start,int end,String name) {
        show(List.of(new Range(start,end)),true);if(ranges.isEmpty())return;
        reading=true;markerOffset=start;maximumTicks=120;setAgentName(name+" reading",name);
        area.putClientProperty("shed.agentDirection","top-to-bottom");
        if(!ranges.isEmpty())area.putClientProperty("shed.agentActivity","Agent reading");
    }
    void showPending(int start, int end) { show(List.of(new Range(start, end)), true); }
    void showPending(List<Range> changes) { show(changes, true); }
    void showApplied(int start, int end) { showApplied(List.of(new Range(start, end))); }
    void showApplied(List<Range> changes) { show(changes, false); }
    private void show(List<Range> changes, boolean preparing) {
        checkEdt(); if (closed) return;
        if (!available()) { clear(); return; }
        if (changes.size() > MAX_RANGES) throw new IllegalArgumentException("Too many agent ranges");
        for (Range range : changes)
            if (range.start() < 0 || range.end() < range.start() || range.end() > document.getLength())
                throw new IllegalArgumentException("Invalid agent range");
        clear(); maximumTicks=TICKS; ranges = List.copyOf(changes); pending = preparing; highlighter = area.getHighlighter();
        try {
            for (Range range : ranges) tags.add(highlighter.addHighlight(range.start(), range.end(), new Painter(range)));
        } catch (BadLocationException invalid) { clear(); throw new IllegalArgumentException(invalid); }
        if (ranges.isEmpty()) return;
        activeRange=ranges.size()-1;markerOffset=ranges.getLast().end();
        area.putClientProperty("shed.agentActivity", preparing ? "Agent preparing change" : "Agent change applied");
        if (animated() && area.isShowing()) timer.start();
        repaintRanges();
    }
    long version(){return version;}
    void moveMarker(int index,int offset){
        checkEdt();if(closed||index<0||index>=ranges.size())return;
        var range=ranges.get(index);if(offset<range.start()||offset>range.end())throw new IllegalArgumentException("Marker outside change");
        repaintRanges();touring=true;activeRange=index;markerOffset=offset;repaintRanges();
    }
    void finishTour() { checkEdt(); touring=false; repaintRanges(); }
    private boolean animated() {
        return !"false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"))
            && !Boolean.FALSE.equals(area.getClientProperty("shed.caretAnimation"));
    }
    boolean available() {
        return document.getLength() <= EditorNavigation.MAX_BOUNDARY_UNITS
            || !area.getLineWrap() && !Boolean.TRUE.equals(document.getProperty("i18n"));
    }
    private void advance() {
        if (!area.isShowing() || !animated() || ++ticks >= maximumTicks) clear(); else {if(intent!=null&&ticks<=6)scrollIntent(ticks/6.0);repaintRanges();}
    }
    private void visibilityChanged() { if (!area.isShowing()) clear(); }
    private void observe() {
        if (document != null) document.removeDocumentListener(edits);
        document = area.getDocument(); document.addDocumentListener(edits);
    }
    Color agentColor() {
        return rangeColor(activeRange);
    }
    private Color rangeColor(int index) {
        boolean deleting="deleting".equals(area.getClientProperty("shed.agentAnimation"));
        String role=intent!=null?intent.role(index):reading?"warning":deleting?"error":"success";
        Color base = UIManager.getColor("shed."+role);
        if (base == null) base = new Color(role.equals("navigation")?0x8B52C7:role.equals("info")?0x087F9C:role.equals("warning")?0xA87808:role.equals("error")?0xCC4855:0x27815C);
        Color human = area.getCaretColor();
        if (human != null && distance(base, human) < 100) base = new Color(0x8B52C7);
        return AccessibilitySupport.readableText(base, area.getBackground());
    }
    private static int distance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen()) + Math.abs(a.getBlue() - b.getBlue());
    }
    private void repaintRanges() {
        if (!area.isShowing() || !available()) return;
        Rectangle visible = area.getVisibleRect(), dirty = null;
        for (Range range : ranges) {
            try {
                Rectangle2D first = area.modelToView2D(range.start()), last = area.modelToView2D(range.end());
                if (first == null || last == null) continue;
                Rectangle part = first.getBounds().union(last.getBounds());
                if (first.getY() != last.getY() || range.equals(ranges.get(activeRange))) { part.x = visible.x; part.width = visible.width; }
                part.grow(area.getFontMetrics(area.getFont()).stringWidth(agentName) + 2*area.getFontMetrics(area.getFont()).getHeight() + 24, 3);
                part = part.intersection(visible);
                if (!part.isEmpty()) dirty = dirty == null ? part : dirty.union(part);
            } catch (BadLocationException unavailable) { }
        }
        if (dirty != null) area.repaint(dirty.x, dirty.y, dirty.width, dirty.height);
    }
    void clear() {
        checkEdt(); AgentSourceHandoff.clear(area);version++;timer.stop(); repaintRanges();
        if(intent!=null){area.removeKeyListener(intentKeys);area.removeMouseListener(intentMouse);area.removeFocusListener(intentFocus);
            if(intentScroll!=null){intentScroll.removeMouseWheelListener(intentMouse);intentScroll.getVerticalScrollBar().removeMouseListener(intentMouse);intentScroll.getHorizontalScrollBar().removeMouseListener(intentMouse);}}
        intent=null;intentViewport=null;intentScroll=null;area.putClientProperty("shed.agentIntent",null);area.putClientProperty("shed.agentIntentStatus",null);
        if(area.getClientProperty(AgentChangePlayback.KEY) instanceof AgentChangePlayback playback)playback.close();
        area.putClientProperty("shed.agentDirection",null);
        if (highlighter != null) for (Object tag : tags) highlighter.removeHighlight(tag);
        tags.clear();touring=false;reading=false; ranges = List.of(); highlighter = null; ticks = 0; agentName = agentProvider = "Agent";
        area.putClientProperty("shed.agentActivity", null);
    }
    @Override public void close() {
        checkEdt(); if (closed) return; clear(); closed = true;
        document.removeDocumentListener(edits); area.removePropertyChangeListener(properties); area.removeHierarchyListener(hierarchy);
        if(area.getClientProperty(PRESENTATION_KEY)==this)area.putClientProperty(PRESENTATION_KEY,null);
    }
    private static void checkEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Agent presentation belongs to the EDT");
    }
    private boolean busy() {
        if(intent!=null)return intent.kind().equals("verify")?intent.status().equals("checking"):!intent.kind().equals("conflict");
        return reading || pending || touring;
    }
    private void paintAgentLabel(Graphics2D graphics,int x,int y,int availableWidth,Color color) {
        var metrics=area.getFontMetrics(area.getFont());
        int height=metrics.getHeight(),gap=6,spinnerSize=Math.max(12,metrics.getAscent());
        Icon icon=ProviderIcons.forValue(agentProvider,area.getBackground());
        int width=metrics.stringWidth(agentName)+(icon==null?0:height+gap)+(busy()?spinnerSize+gap:0);
        if(width>availableWidth)return;
        var copy=(Graphics2D)graphics.create();
        try{
            copy.clipRect(x,y,width,height);
            if(icon!=null){
                var logo=(Graphics2D)copy.create();
                try{logo.translate(x,y);logo.scale(height/(double)icon.getIconWidth(),height/(double)icon.getIconHeight());icon.paintIcon(area,logo,0,0);}
                finally{logo.dispose();}
                x+=height+gap;
            }
            copy.setColor(color);copy.setFont(area.getFont());copy.drawString(agentName,x,y+metrics.getAscent());
            if(busy()){
                spinner.setBaseColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),45));
                spinner.setHighlightColor(color);spinner.setFrame(animated()?ticks%16:0);
                copy.translate(x+metrics.stringWidth(agentName)+gap,y+(height-spinnerSize)/2);
                copy.scale(spinnerSize/20.0,spinnerSize/20.0);
                spinner.paint(copy,area,20,20);
            }
        }finally{copy.dispose();}
    }
    static void paintPlaybackBadge(JTextArea area,Graphics graphics) {
        if(area.getClientProperty(AgentChangePlayback.KEY) instanceof AgentChangePlayback
            &&area.getClientProperty(PRESENTATION_KEY) instanceof AgentEditPresentation presentation
            &&!presentation.closed&&presentation.touring&&!presentation.ranges.isEmpty()&&graphics instanceof Graphics2D){
            var copy=(Graphics2D)graphics.create();
            try{copy.clip(area.getVisibleRect());presentation.paintMarker(copy);}finally{copy.dispose();}
        }
    }
    private void paintMarker(Graphics2D graphics) {
        try{
            Rectangle2D caret=area.modelToView2D(markerOffset);if(caret==null)return;
            Color color=rangeColor(activeRange);graphics.setColor(color);
            if(reading)graphics.fillRect((int)caret.getX(),(int)caret.getMaxY()-2,7,2);
            else graphics.fillRect((int)caret.getX(),(int)caret.getY(),3,(int)caret.getHeight());
            int sourceLine=area.getLineOfOffset(markerOffset),lineEnd=Math.min(document.getLength(),area.getLineEndOffset(sourceLine));
            if(lineEnd-area.getLineStartOffset(sourceLine)>256)return;
            if(lineEnd>0&&"\n".equals(document.getText(lineEnd-1,1)))lineEnd--;
            var ending=area.modelToView2D(lineEnd);var visible=area.getVisibleRect();
            if(ending!=null&&Math.abs(ending.getY()-caret.getY())<1){
                int labelX=(int)Math.max(caret.getX()+8,ending.getX()+12);
                if(area.getClientProperty(AgentChangePlayback.KEY) instanceof AgentChangePlayback playback)labelX=Math.max(labelX,playback.badgeMinimumX());
                paintAgentLabel(graphics,labelX,(int)caret.getY(),visible.x+visible.width-4-labelX,color);
            }
        }catch(BadLocationException unavailable){ }
    }
    private final class Painter extends LayeredHighlighter.LayerPainter {
        private final Range range;
        Painter(Range range) { this.range = range; }
        @Override public void paint(Graphics graphics, int start, int end, Shape bounds, JTextComponent component) { }
        @Override public Shape paintLayer(Graphics graphics, int start, int end, Shape bounds, JTextComponent component, View view) {
            try {
                Shape shape = view.modelToView(start, Position.Bias.Forward, end, Position.Bias.Backward, bounds);
                if (shape == null) return null;
                Rectangle line = shape.getBounds().intersection(area.getVisibleRect());
                Graphics2D copy = (Graphics2D) graphics.create();
                try {
                    Color color = rangeColor(ranges.indexOf(range));
                    int alpha = !animated() ? 48 : reading ? 30+(int)(12*Math.sin(ticks*.18)) : (pending||touring&&range.equals(ranges.get(activeRange))) ? 36 + (int)(22 * Math.sin(ticks * .45)) : Math.max(8, 60 - ticks);
                    copy.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
                    copy.fill(line);
                    if(intent!=null){copy.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),150));copy.drawRect(line.x,line.y,Math.max(1,line.width-1),Math.max(1,line.height-1));}
                    if(animated()){
                        var sweep=(Graphics2D)copy.create();
                        try{
                            sweep.clip(line);sweep.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),38));
                            double phase=(ticks%40)/40.0;
                            if(area.getClientProperty(AgentChangePlayback.KEY) instanceof AgentChangePlayback playback)phase=playback.directionalProgress();
                            if(reading||"top-to-bottom".equals(area.getClientProperty("shed.agentDirection"))||"bottom-to-top".equals(area.getClientProperty("shed.agentDirection"))){
                                var first=area.modelToView2D(range.start());var last=area.modelToView2D(range.end());
                                if(first!=null&&last!=null){
                                    int height=Math.max(2,area.getFontMetrics(area.getFont()).getHeight()/2);
                                    boolean upward="bottom-to-top".equals(area.getClientProperty("shed.agentDirection"));
                                    int y=(int)(first.getY()+(upward?(1-phase)*Math.max(0,last.getMaxY()-first.getY()-height):phase*(last.getMaxY()-first.getY())));
                                    sweep.fillRect(line.x,y,line.width,height);
                                }
                            }else{
                                int x=line.x+(int)(phase*line.width);if("right-to-left".equals(area.getClientProperty("shed.agentDirection")))x=line.x+line.width-(int)(phase*line.width);
                                sweep.fillRect(x,line.y,Math.max(3,line.width/12),line.height);
                            }
                        }finally{sweep.dispose();}
                    }
                    if(range.equals(ranges.get(activeRange))&&start<=markerOffset&&markerOffset<=end
                        &&!(area.getClientProperty(AgentChangePlayback.KEY) instanceof AgentChangePlayback))paintMarker(copy);
                } finally { copy.dispose(); }
                return line;
            } catch (BadLocationException unavailable) { return null; }
        }
    }
}
