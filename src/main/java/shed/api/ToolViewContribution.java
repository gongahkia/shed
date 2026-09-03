package shed.api;

import javax.swing.JComponent;

/** A Swing workbench panel contributed by an extension. */
public interface ToolViewContribution {
    String id();

    String title();

    JComponent createComponent();

    default void refresh() {
    }
}
