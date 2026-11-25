package shed;

import java.awt.*;
import java.awt.event.HierarchyListener;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;

/** Transient agent decoration; the human caret and authoritative source remain untouched. */
final class AgentEditPresentation implements AutoCloseable {
    record Range(int start, int end) { }
    private static final int MAX_RANGES = 32, TICKS = 48;
    private final JTextArea area;
    private final Timer timer;
    private final List<Object> tags = new ArrayList<>();
    private List<Range> ranges = List.of();
    private Document document;
    private Highlighter highlighter;
    private boolean closed, pending, touring;
    private int ticks, maximumTicks = TICKS, activeRange, markerOffset;
    private long version;
    private String agentName = "Agent";
    void setAgentName(String name) { checkEdt(); agentName = name; repaintRanges(); }
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
        area.addPropertyChangeListener(properties); area.addHierarchyListener(hierarchy); observe();
    }
    void showProgress(int start, int end, String name) { showPending(start,end); maximumTicks=600; setAgentName(name); }
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
    private boolean animated() {
        return !"false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"))
            && !Boolean.FALSE.equals(area.getClientProperty("shed.caretAnimation"));
    }
    boolean available() {
        return document.getLength() <= EditorNavigation.MAX_BOUNDARY_UNITS
            || !area.getLineWrap() && !Boolean.TRUE.equals(document.getProperty("i18n"));
    }
    private void advance() {
        if (!area.isShowing() || !animated() || ++ticks >= maximumTicks) clear(); else repaintRanges();
    }
    private void visibilityChanged() { if (!area.isShowing()) clear(); }
    private void observe() {
        if (document != null) document.removeDocumentListener(edits);
        document = area.getDocument(); document.addDocumentListener(edits);
    }
    Color agentColor() {
        Color base = UIManager.getColor("shed.success");
        if (base == null) base = new Color(0x27815C);
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
                part.grow(area.getFontMetrics(area.getFont()).stringWidth(agentName) + 8, 3);
                part = part.intersection(visible);
                if (!part.isEmpty()) dirty = dirty == null ? part : dirty.union(part);
            } catch (BadLocationException unavailable) { }
        }
        if (dirty != null) area.repaint(dirty.x, dirty.y, dirty.width, dirty.height);
    }
    void clear() {
        checkEdt(); version++;timer.stop(); repaintRanges();
        if (highlighter != null) for (Object tag : tags) highlighter.removeHighlight(tag);
        tags.clear();touring=false; ranges = List.of(); highlighter = null; ticks = 0; agentName = "Agent";
        area.putClientProperty("shed.agentActivity", null);
    }
    @Override public void close() {
        checkEdt(); if (closed) return; clear(); closed = true;
        document.removeDocumentListener(edits); area.removePropertyChangeListener(properties); area.removeHierarchyListener(hierarchy);
    }
    private static void checkEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Agent presentation belongs to the EDT");
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
                    Color color = agentColor();
                    int alpha = !animated() ? 48 : (pending||touring&&range.equals(ranges.get(activeRange))) ? 36 + (int)(22 * Math.sin(ticks * .45)) : Math.max(8, 60 - ticks);
                    copy.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
                    copy.fill(line);
                    if (range.equals(ranges.get(activeRange))&&start<=markerOffset&&markerOffset<=end) {
                        Rectangle2D caret = area.modelToView2D(markerOffset);
                        if (caret != null) {
                            copy.setColor(color); copy.fillRect((int)caret.getX(), (int)caret.getY(), 3, (int)caret.getHeight());
                            int sourceLine=area.getLineOfOffset(markerOffset),lineEnd=Math.min(document.getLength(),area.getLineEndOffset(sourceLine));
                            if(lineEnd-area.getLineStartOffset(sourceLine)<=256){
                                if(lineEnd>0&&"\n".equals(document.getText(lineEnd-1,1)))lineEnd--;
                                var ending=area.modelToView2D(lineEnd);var visible=area.getVisibleRect();var metrics=area.getFontMetrics(area.getFont());
                                if(ending!=null&&Math.abs(ending.getY()-caret.getY())<1){
                                    int labelX=(int)Math.max(caret.getX()+8,ending.getX()+12);
                                    if(labelX+metrics.stringWidth(agentName)<visible.x+visible.width-4){copy.setFont(area.getFont());copy.drawString(agentName,labelX,(int)caret.getY()+metrics.getAscent());}
                                }
                            }
                        }
                    }
                } finally { copy.dispose(); }
                return line;
            } catch (BadLocationException unavailable) { return null; }
        }
    }
}
