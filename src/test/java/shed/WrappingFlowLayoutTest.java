package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class WrappingFlowLayoutTest {
    @Test void reportsEveryRowAndExcludesHiddenActions() {
        JPanel toolbar = new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEFT, 8, 4)));
        for (int i = 0; i < 4; i++) { JButton button = new JButton("Action"); button.setPreferredSize(new Dimension(100, 40)); toolbar.add(button); }
        toolbar.setSize(240, 200);
        assertEquals(92, toolbar.getPreferredSize().height);
        toolbar.doLayout();
        for (Component child : toolbar.getComponents()) assertTrue(child.getY() + child.getHeight() <= toolbar.getPreferredSize().height);
        toolbar.getComponent(2).setVisible(false); toolbar.getComponent(3).setVisible(false);
        assertEquals(48, toolbar.getPreferredSize().height);
        toolbar.setSize(120, 200);
        assertEquals(92, toolbar.getPreferredSize().height);
    }
}
