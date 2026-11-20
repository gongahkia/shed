package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class LspDefinitionResponsivenessTest {
    @Test void synchronousTransportReproducesEdtInputBlockWithoutNativeProcess() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var peer = new InProcessLspFixture()) {
            LspClient client = peer.client();
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            try {
                for (int sample = 0; sample < 3; sample++) {
                    CountDownLatch commandDone = new CountDownLatch(1), input = new CountDownLatch(1);
                    AtomicReference<LspClient.Location> location = new AtomicReference<>();
                    long[] timing = new long[3];
                    SwingUtilities.invokeLater(() -> {
                        timing[0] = System.nanoTime();
                        location.set(client.definition("file:///source.java", 0, 1));
                        timing[1] = System.nanoTime(); commandDone.countDown();
                    });
                    var request = peer.await("textDocument/definition");
                    AtomicReference<StackTraceElement[]> stack = new AtomicReference<>();
                    for (var entry : Thread.getAllStackTraces().entrySet())
                        if (entry.getKey().getName().startsWith("AWT-EventQueue")) stack.set(entry.getValue());
                    long queued = System.nanoTime();
                    SwingUtilities.invokeLater(() -> { timing[2] = System.nanoTime(); input.countDown(); });
                    var release = new CountDownLatch(1);
                    scheduler.schedule(() -> { peer.respond(request, InProcessLspFixture.location("file:///target.java")); release.countDown(); }, 200, TimeUnit.MILLISECONDS);
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    assertTrue(commandDone.await(2, TimeUnit.SECONDS)); assertTrue(input.await(2, TimeUnit.SECONDS));
                    assertNotNull(location.get());
                    assertTrue(List.of(stack.get()).stream().anyMatch(frame -> frame.getClassName().equals("shed.LspClient") && frame.getMethodName().equals("waitForResponse")));
                    var row = Map.<String,Object>of("sample", sample, "dispatch_ms", (timing[1]-timing[0])/1e6,
                        "queued_input_ms", (timing[2]-queued)/1e6, "edt_stack", List.of(stack.get()).stream().map(Object::toString).toList());
                    rows.add(row);
                }
            } finally { scheduler.shutdownNow(); client.stop(); }
        }
        System.out.println("B1A_BASELINE " + MiniJson.stringify(rows));
    }
}
