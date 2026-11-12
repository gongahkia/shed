package shed;

import java.awt.Rectangle;
import javax.swing.JComponent;

/** Prototype presentation only. Targets are actual on-screen bounds for interaction probes. */
public interface LabView extends AutoCloseable {
    JComponent component();
    Rectangle target(String id);
    void focusSearch();
    @Override void close();
}
