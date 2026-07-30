package shed;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jediterm.core.util.TermSize;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class PtyTerminalConnectorTest {
    @Test
    void ptySupportsInputOutputAndResize() throws Exception {
        Path shell = Path.of("/bin/sh");
        assumeTrue(Files.isExecutable(shell), "/bin/sh unavailable");

        List<String> command = List.of(shell.toString());
        PtyProcess process = new PtyProcessBuilder(command.toArray(new String[0]))
            .setInitialColumns(80)
            .setInitialRows(24)
            .start();
        PtyTerminalConnector connector = new PtyTerminalConnector(process, command, StandardCharsets.UTF_8);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<String> output = reader.submit(() -> readUntilExit(connector));
            connector.resize(new TermSize(100, 40));
            connector.resize(new TermSize(100, 40));
            connector.resize(new TermSize(0, 0));
            connector.write("stty size; printf shed-pty-ok; exit\n");
            String text = output.get(8, TimeUnit.SECONDS);
            assertTrue(text.contains("shed-pty-ok"), text);
            assertTrue(text.contains("40 100"), text);
        } finally {
            connector.close();
            reader.shutdownNow();
        }
    }

    private static String readUntilExit(PtyTerminalConnector connector) throws Exception {
        StringBuilder output = new StringBuilder();
        char[] buffer = new char[256];
        while (true) {
            int count = connector.read(buffer, 0, buffer.length);
            if (count < 0) {
                break;
            }
            output.append(buffer, 0, count);
        }
        return output.toString();
    }
}
