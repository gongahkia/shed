package shed;

import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.Terminal;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalPanel;
import java.io.IOException;

final class TerminalConformanceFixture {
    private TerminalConformanceFixture() {
    }

    static Result render(String output) throws IOException {
        StyleState style = new StyleState();
        TerminalTextBuffer buffer = new TerminalTextBuffer(80, 24, style);
        TerminalPanel display = new TerminalPanel(new PtyTerminalPane.ShedTerminalSettingsProvider(null, null), buffer, style);
        JediTerminal terminal = new JediTerminal(display, buffer, style);
        JediEmulator emulator = new JediEmulator(new ArrayTerminalDataStream(output.toCharArray()), terminal);
        while (emulator.hasNext()) {
            emulator.next();
        }
        return new Result(buffer.getScreenLines(), terminal);
    }

    record Result(String screen, Terminal terminal) {
    }
}
