package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ToolOutputTest {
    @Test void refreshPreservesDirectedSelectionsAndOnlyNewSelectionsReturnToStart() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea output = new JTextArea();
            ToolOutput.update(output, "first line\nsecond line\n", true);
            output.getCaret().setDot(8); output.getCaret().moveDot(3);
            ToolOutput.update(output, "first line\nsecond line\nmore output\n", false);
            assertEquals(3, output.getCaret().getDot()); assertEquals(8, output.getCaret().getMark());
            ToolOutput.update(output, "different job", true);
            assertEquals(0, output.getCaretPosition());
            output.setCaretPosition(5);
            ToolOutput.update(output, "different job", true);
            assertEquals(0, output.getCaretPosition(), "Two different jobs can have identical output");
            output.setCaretPosition(output.getDocument().getLength());
            ToolOutput.update(output, "different job\nappended", false);
            assertEquals(output.getDocument().getLength(), output.getCaretPosition());
        });
    }
}
