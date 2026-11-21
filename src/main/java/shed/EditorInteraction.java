package shed;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretListener;
import javax.swing.event.ChangeListener;
import java.beans.PropertyChangeListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.InputMethodEvent;

/** grant-lifetime interaction counter, not another caret/document model. All targets are weak. */
final class EditorInteraction implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final WeakHashMap<EditorPane, Tracking> targets = new WeakHashMap<>();
    private long revision;

    void changed() { if (!closed.get()) revision++; }
    long revision() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Interaction state belongs to the EDT");
        if (closed.get()) throw EditorMetadata.failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
        return revision;
    }
    boolean watch(EditorPane pane) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Interaction tracking belongs to the EDT");
        if (closed.get() || pane == null || pane.isDisposed()) return false;
        if (targets.containsKey(pane)) return true;
        if (targets.size() >= EditorMetadata.MAX_PANES) return false;
        targets.put(pane, new Tracking(pane.getTextArea(), pane.getScrollPane().getViewport()));
        changed(); return true;
    }

    private final class Tracking {
        private final WeakReference<JTextArea> area;
        private final WeakReference<JViewport> viewport;
        private final CaretListener caret = event -> changed();
        private final ChangeListener scroll = event -> changed();
        private final PropertyChangeListener property = event -> {
            if ("document".equals(event.getPropertyName()) || "caret".equals(event.getPropertyName())) changed();
        };
        private final KeyAdapter key = new KeyAdapter() { @Override public void keyPressed(KeyEvent event) { changed(); } };
        private final FocusAdapter focus = new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { changed(); }
            @Override public void focusLost(FocusEvent event) { changed(); }
        };
        private final InputMethodListener input = new InputMethodListener() {
            public void inputMethodTextChanged(InputMethodEvent event) { changed(); }
            public void caretPositionChanged(InputMethodEvent event) { changed(); }
        };
        Tracking(JTextArea component, JViewport view) {
            area = new WeakReference<>(component); viewport = new WeakReference<>(view);
            component.addCaretListener(caret); component.addPropertyChangeListener(property);
            component.addKeyListener(key); component.addFocusListener(focus); component.addInputMethodListener(input);
            view.addChangeListener(scroll);
        }
        void detach() {
            var component = area.get(); var view = viewport.get();
            if (component != null) {
                component.removeCaretListener(caret); component.removePropertyChangeListener(property);
                component.removeKeyListener(key); component.removeFocusListener(focus); component.removeInputMethodListener(input);
            }
            if (view != null) view.removeChangeListener(scroll);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable cleanup = () -> { for (var target : targets.values()) target.detach(); targets.clear(); };
        if (SwingUtilities.isEventDispatchThread()) cleanup.run(); else SwingUtilities.invokeLater(cleanup);
    }
}
