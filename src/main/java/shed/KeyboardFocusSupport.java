package shed;

import java.awt.event.ActionEvent;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;

final class KeyboardFocusSupport {
    private static final String ESCAPE_ACTION = "shed.dismiss.escape";

    private KeyboardFocusSupport() {
    }

    static void installEscape(JRootPane rootPane, Runnable dismiss) {
        Objects.requireNonNull(rootPane, "rootPane");
        Objects.requireNonNull(dismiss, "dismiss");
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), ESCAPE_ACTION);
        rootPane.getActionMap().put(ESCAPE_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dismiss.run();
            }
        });
    }
}
