package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

public class LineNumberPanelTest {
    @Test
    void relativeModeRepaintsTheWholeGutterAfterCaretMove() {
        RecordingLineNumberPanel panel = new RecordingLineNumberPanel(new JTextArea("one\ntwo\nthree\n"));
        panel.setMode(LineNumberMode.RELATIVE);
        panel.resetCounts();

        panel.repaintForCaretChange(1, 2);

        assertEquals(1, panel.fullRepaints);
        assertEquals(0, panel.regionRepaints);
    }

    @Test
    void hybridModeRepaintsTheWholeGutterAfterCaretMove() {
        RecordingLineNumberPanel panel = new RecordingLineNumberPanel(new JTextArea("one\ntwo\nthree\n"));
        panel.setMode(LineNumberMode.RELATIVE_ABSOLUTE);
        panel.resetCounts();

        panel.repaintForCaretChange(1, 2);

        assertEquals(1, panel.fullRepaints);
        assertEquals(0, panel.regionRepaints);
    }

    private static final class RecordingLineNumberPanel extends LineNumberPanel {
        int fullRepaints;
        int regionRepaints;

        RecordingLineNumberPanel(JTextArea textArea) {
            super(textArea);
        }

        void resetCounts() {
            fullRepaints = 0;
            regionRepaints = 0;
        }

        @Override public void repaint() {
            fullRepaints++;
        }

        @Override public void repaint(int x, int y, int width, int height) {
            regionRepaints++;
        }
    }
}
