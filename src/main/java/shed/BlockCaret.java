package shed;

import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import java.beans.PropertyChangeListener;
import java.awt.event.HierarchyListener;
import java.awt.RenderingHints;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

class BlockCaret extends DefaultCaret {
    private static final long serialVersionUID = 1L;

    private final CaretMotion motion = new CaretMotion();
    private final Timer animation = new Timer(16, event -> tick());
    private int paintedDot = -1;
    private Rectangle paintedTarget;
    private Rectangle paintedViewport;
    private Document observedDocument;
    private final DocumentListener edits = new DocumentListener() {
        public void insertUpdate(DocumentEvent event) { resetMotion(); }
        public void removeUpdate(DocumentEvent event) { resetMotion(); }
        public void changedUpdate(DocumentEvent event) { resetMotion(); }
    };
    private final PropertyChangeListener properties = event -> {
        if ("document".equals(event.getPropertyName())) observeDocument();
        resetMotion();
    };
    private final HierarchyListener hierarchy = event -> {
        if (getComponent() != null && !getComponent().isShowing()) resetMotion();
    };

    @Override
    public void install(JTextComponent component) {
        super.install(component);
        component.addPropertyChangeListener(properties);
        component.addHierarchyListener(hierarchy);
        observeDocument();
    }

    @Override
    public void deinstall(JTextComponent component) {
        resetMotion();
        component.removePropertyChangeListener(properties);
        component.removeHierarchyListener(hierarchy);
        if (observedDocument != null) observedDocument.removeDocumentListener(edits);
        observedDocument = null;
        super.deinstall(component);
    }

    private void observeDocument() {
        if (observedDocument != null) observedDocument.removeDocumentListener(edits);
        observedDocument = getComponent().getDocument();
        if (observedDocument != null) observedDocument.addDocumentListener(edits);
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (!visible) resetMotion();
    }

    private void resetMotion() {
        animation.stop();
        repaintMotion();
        paintedDot = -1;
        paintedTarget = null;
        paintedViewport = null;
    }

    private void repaintMotion() {
        JTextComponent component = getComponent();
        if (component == null || paintedTarget == null) return;
        Rectangle dirty = motion.bounds().union(paintedTarget);
        dirty.grow(2, 2);
        component.repaint(dirty.x, dirty.y, dirty.width, dirty.height);
    }

    private void tick() {
        JTextComponent component = getComponent();
        if (component == null || !component.isShowing() || !isVisible()) {
            resetMotion();
            return;
        }
        repaintMotion();
        motion.advance(System.nanoTime());
        repaintMotion();
        if (!motion.moving()) animation.stop();
    }

    private boolean animate(JTextComponent component) {
        return component.isShowing() && getDot() == getMark()
            && !Boolean.FALSE.equals(component.getClientProperty("shed.caretAnimation"))
            && !"false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"))
            && !(component.getUI() instanceof InlineCompletionDisplay preview && preview.showing());
    }

    @Override
    public void paint(Graphics g) {
        JTextComponent component = getComponent();
        if (component == null || !isVisible()) {
            return;
        }
        try {
            Rectangle2D modelBounds = component.modelToView2D(getDot());
            if (modelBounds == null) {
                return;
            }
            Rectangle bounds = modelBounds.getBounds();
            int caretWidth = Math.max(1, blockWidth(component, getDot()));
            Rectangle target = new Rectangle(bounds.x, bounds.y, caretWidth, bounds.height);
            long now = System.nanoTime();
            // Layout changes at the same offset (scroll, resize, font) must never smear.
            if (!animate(component) || paintedDot < 0
                || (paintedDot == getDot() && !target.equals(paintedTarget))
                || !component.getVisibleRect().equals(paintedViewport)
                || (paintedTarget != null && !component.getVisibleRect().contains(paintedTarget))) {
                motion.snap(target);
                animation.stop();
            } else {
                motion.moveTo(target, now);
            }
            paintedDot = getDot();
            paintedTarget = target;
            paintedViewport = component.getVisibleRect();
            motion.advance(now);
            if (motion.moving() && g instanceof Graphics2D graphics) {
                Graphics2D smear = (Graphics2D) graphics.create();
                try {
                    smear.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color color = component.getCaretColor();
                    smear.setColor(color == null ? component.getForeground() : color);
                    smear.fill(motion.shape());
                } finally { smear.dispose(); }
                if (!animation.isRunning()) animation.start();
            } else {
                paintBlockCaret(g, component, bounds, caretWidth, getDot());
            }
        } catch (BadLocationException ignored) {
        }
    }

    static void paintBlockCaret(Graphics graphics, JTextComponent component, Rectangle bounds, int caretWidth, int dot)
        throws BadLocationException {
        if (!(graphics instanceof Graphics2D graphics2D) || component == null || bounds == null) {
            return;
        }
        Graphics2D caretGraphics = (Graphics2D) graphics2D.create();
        try {
            EditorUiController.applyEditorTextRenderingHints(caretGraphics);
            Color caretColor = component.getCaretColor();
            caretGraphics.setColor(caretColor == null ? component.getForeground() : caretColor);
            if (component.getUI() instanceof InlineCompletionDisplay preview && preview.showing()) {
                // A source offset can span the entire virtual suggestion. Keep the caret at
                // its insertion boundary without hiding the ghost or redrawing a source glyph.
                caretGraphics.fillRect(bounds.x, bounds.y, 2, bounds.height);
                return;
            }
            caretGraphics.fillRect(bounds.x, bounds.y, caretWidth, bounds.height);
            if (dot < 0 || dot >= component.getDocument().getLength()) {
                return;
            }
            String glyph = component instanceof javax.swing.JPasswordField password && password.echoCharIsSet()
                ? String.valueOf(password.getEchoChar()) : component.getDocument().getText(dot, 1);
            if (glyph.isEmpty() || glyph.charAt(0) == '\n' || glyph.charAt(0) == '\r' || glyph.charAt(0) == '\t') {
                return;
            }
            caretGraphics.setFont(component.getFont());
            FontMetrics metrics = caretGraphics.getFontMetrics();
            caretGraphics.setColor(component.getBackground());
            caretGraphics.drawString(glyph, bounds.x, bounds.y + metrics.getAscent());
        } finally {
            caretGraphics.dispose();
        }
    }

    @Override
    protected synchronized void damage(Rectangle r) {
        if (r == null) {
            return;
        }
        JTextComponent component = getComponent();
        Rectangle dirty = new Rectangle(r.x, r.y,
            component == null ? Math.max(1, r.width) : Math.max(1, blockWidth(component, getDot())), r.height);
        if (paintedTarget != null) dirty = dirty.union(paintedTarget).union(motion.bounds());
        dirty.grow(2, 2);
        x = dirty.x;
        y = dirty.y;
        height = dirty.height;
        width = dirty.width;
        repaint();
    }

    private int blockWidth(JTextComponent component, int dot) {
        if (component.getUI() instanceof InlineCompletionDisplay preview && preview.showing()) return 2;
        try {
            int length = component.getDocument().getLength();
            if (dot < length) {
                Rectangle2D current = component.modelToView2D(dot);
                Rectangle2D next = component.modelToView2D(dot + 1);
                if (current != null && next != null) {
                    int width = (int) Math.round(next.getX() - current.getX());
                    if (width > 0) {
                        return width;
                    }
                }
            }
        } catch (BadLocationException ignored) {
        }
        return Math.max(1, component.getFontMetrics(component.getFont()).charWidth('W'));
    }
}
