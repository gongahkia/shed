package shed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

class KeyboardFocusSupportTest {
    @Test
    void escapeActionIsAvailableFromEveryFocusedChild() {
        JRootPane root = new JRootPane();
        AtomicBoolean dismissed = new AtomicBoolean();

        KeyboardFocusSupport.installEscape(root, () -> dismissed.set(true));

        Object key = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ESCAPE"));
        Action action = root.getActionMap().get(key);
        action.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "escape"));
        assertTrue(dismissed.get());
    }
}
