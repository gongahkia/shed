package shed;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import java.nio.charset.Charset;
import java.util.List;

final class PtyTerminalConnector extends ProcessTtyConnector {
    private final PtyProcess process;
    private final String name;
    private TermSize lastSize;

    PtyTerminalConnector(PtyProcess process, List<String> commandLine, Charset charset) {
        super(process, charset, commandLine);
        this.process = process;
        this.name = String.join(" ", commandLine);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public synchronized void resize(TermSize termSize) {
        if (termSize == null || termSize.getColumns() <= 0 || termSize.getRows() <= 0 || termSize.equals(lastSize) || !process.isRunning()) {
            return;
        }
        try {
            process.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
            lastSize = termSize;
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public void close() {
        super.close();
        if (process.isRunning()) {
            process.destroy();
        }
    }
}
