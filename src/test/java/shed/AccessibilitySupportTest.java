package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

class AccessibilitySupportTest {
    @Test
    void exposesMeaningfulAccessibleMetadata() {
        JTextArea area = AccessibilitySupport.describe(new JTextArea(), "Editor", "Edit the active source buffer.");

        assertEquals("Editor", area.getAccessibleContext().getAccessibleName());
        assertEquals("Edit the active source buffer.", area.getAccessibleContext().getAccessibleDescription());
    }

    @Test
    void allowsComponentsWithoutAnAccessibleContext() {
        JComponent component = new JComponent() {
            @Override public AccessibleContext getAccessibleContext() { return null; }
        };

        assertEquals(component, AccessibilitySupport.describe(component, "Graph", "Git graph."));
    }

    @Test
    void calculatesWcagTextContrast() {
        assertTrue(AccessibilitySupport.meetsTextContrast(Color.WHITE, Color.BLACK));
        assertFalse(AccessibilitySupport.meetsTextContrast(new Color(120, 120, 120), new Color(100, 100, 100)));
    }
}
