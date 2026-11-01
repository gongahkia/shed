package shed;

import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.jediterm.terminal.util.CharUtils;
import java.awt.*;
import java.awt.event.InputMethodEvent;
import java.util.function.IntSupplier;
import javax.swing.Timer;

/** Adds motion while leaving JediTerm responsible for the settled cursor and terminal semantics. */
class AnimatedTerminalPanel extends TerminalPanel {
    private final CaretMotion motion = new CaretMotion();
    private final Timer animation = new Timer(16, event -> {
        if (!isShowing() || !isFocusOwner()) resetMotion();
        repaint();
    });
    private volatile Point cursor = new Point(0, 1);
    private volatile boolean cursorVisible = true;
    private volatile CursorShape shape;
    private volatile CursorShape defaultShape = CursorShape.BLINK_BLOCK;
    private IntSupplier cursorColumn;
    private Rectangle target;
    private int scrollOrigin;
    private Dimension viewport;
    private boolean alternateScreen;
    private CursorShape paintedShape;
    private boolean composing;

    AnimatedTerminalPanel(SettingsProvider settings, TerminalTextBuffer buffer, StyleState style) {
        super(settings, buffer, style);
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent event) { resetMotion(); }
        });
        addHierarchyListener(event -> { if (!isShowing()) resetMotion(); });
        addPropertyChangeListener(event -> {
            if ("font".equals(event.getPropertyName()) || "shed.caretAnimation".equals(event.getPropertyName())) resetMotion();
        });
    }

    void setCursorColumnSupplier(IntSupplier supplier) { cursorColumn = supplier; }

    @Override public void setCursor(int x, int y) {
        cursor = new Point(x, y);
        super.setCursor(x, y);
    }

    @Override public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        super.setCursorVisible(visible);
    }

    @Override public void setCursorShape(CursorShape value) {
        shape = value;
        super.setCursorShape(value);
    }

    @Override public void setDefaultCursorShape(CursorShape value) {
        defaultShape = value;
        super.setDefaultCursorShape(value);
    }

    @Override protected void processInputMethodEvent(InputMethodEvent event) {
        var text = event.getText();
        composing = text != null && text.getEndIndex() - text.getBeginIndex() > event.getCommittedCharacterCount();
        if (composing) resetMotion();
        super.processInputMethodEvent(event);
    }

    private void resetMotion() {
        animation.stop();
        target = null;
    }

    @Override public void removeNotify() {
        resetMotion();
        super.removeNotify();
    }

    @Override public void dispose() {
        resetMotion();
        super.dispose();
    }

    @Override public void paintComponent(Graphics graphics) {
        CursorShape effectiveShape = shape == null ? defaultShape : shape;
        Rectangle bounds = cursorBounds(effectiveShape);
        boolean enabled = isShowing() && isFocusOwner() && cursorVisible && !composing && getSelection() == null
            && !Boolean.FALSE.equals(getClientProperty("shed.caretAnimation"))
            && !"false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"));
        boolean alternate = getTerminalTextBuffer().isUsingAlternateBuffer();
        long now = System.nanoTime();
        if (!enabled || target == null || bounds.isEmpty() || scrollOrigin != myClientScrollOrigin
            || !getSize().equals(viewport) || alternate != alternateScreen || paintedShape != effectiveShape) {
            motion.snap(bounds);
            animation.stop();
        } else {
            motion.moveTo(bounds, now);
        }
        target = enabled && !bounds.isEmpty() ? bounds : null;
        scrollOrigin = myClientScrollOrigin;
        viewport = getSize();
        alternateScreen = alternate;
        paintedShape = effectiveShape;
        motion.advance(now);
        boolean moving = target != null && motion.moving();
        // Hide only the native cursor during the smear. JediTerm still paints all text,
        // selection, IME composition and (once settled) its exact cursor glyph and shape.
        super.setCursorVisible(cursorVisible && !moving);
        try {
            super.paintComponent(graphics);
        } finally {
            super.setCursorVisible(cursorVisible);
        }
        if (moving && graphics instanceof Graphics2D original) {
            Graphics2D g = (Graphics2D) original.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getForeground());
                g.setComposite(AlphaComposite.SrcOver.derive(.75f));
                g.fill(motion.shape());
            } finally { g.dispose(); }
            if (!animation.isRunning()) animation.start();
        } else {
            animation.stop();
        }
    }

    private Rectangle cursorBounds(CursorShape effectiveShape) {
        Point position = cursor;
        int column = cursorColumn == null ? position.x : cursorColumn.getAsInt();
        int row = position.y - 1 - myClientScrollOrigin;
        TerminalTextBuffer buffer = getTerminalTextBuffer();
        if (column < 0 || column >= buffer.getWidth() || row < 0 || row >= buffer.getHeight()) return new Rectangle();
        int cells = 1;
        buffer.lock();
        try {
            char first = buffer.getCharAt(column, position.y - 1);
            String glyph = String.valueOf(first);
            if (Character.isHighSurrogate(first) && column + 1 < buffer.getWidth()) {
                glyph += buffer.getCharAt(column + 1, position.y - 1);
            }
            char[] chars = glyph.toCharArray();
            cells = Math.max(1, CharUtils.getTextLengthDoubleWidthAware(chars, 0, chars.length, ambiguousCharsAreDoubleWidth()));
        } finally { buffer.unlock(); }
        int x = getInsetX() + column * myCharSize.width;
        int y = row * myCharSize.height;
        int width = Math.min(cells * myCharSize.width, getWidth() - x);
        int height = Math.min(myCharSize.height, getHeight() - y);
        if (effectiveShape == CursorShape.BLINK_VERTICAL_BAR || effectiveShape == CursorShape.STEADY_VERTICAL_BAR) width = Math.min(2, width);
        if (effectiveShape == CursorShape.BLINK_UNDERLINE || effectiveShape == CursorShape.STEADY_UNDERLINE) {
            y += height; height = 2;
        }
        return new Rectangle(x, y, Math.max(0, width), Math.max(0, height));
    }
}
