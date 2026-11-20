package shed;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory JSON-RPC peer. No OS process, socket, shell, or personal file access. */
final class InProcessLspFixture extends Process implements AutoCloseable {
    final BlockingQueue<Map<String, Object>> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private byte[] current = new byte[0];
    private int offset;
    volatile boolean failDefinition;
    volatile boolean immediateDefinition;
    final java.util.concurrent.CountDownLatch readerClosed = new java.util.concurrent.CountDownLatch(1);
    private final InputStream input = new InputStream() {
        @Override public void close() { readerClosed.countDown(); }
        @Override public int read() throws IOException {
            while (offset == current.length) {
                try { current = incoming.take(); offset = 0; }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                if (current.length == 0) return -1;
            }
            return current[offset++] & 255;
        }
        @Override public int read(byte[] bytes, int start, int length) throws IOException {
            if (length == 0) return 0;
            int first = read();
            if (first < 0) return -1;
            bytes[start] = (byte) first;
            int copied = Math.min(length - 1, current.length - offset);
            System.arraycopy(current, offset, bytes, start + 1, copied); offset += copied;
            return copied + 1;
        }
    };
    private final OutputStream output = new ByteArrayOutputStream() {
        @Override public synchronized void flush() {
            String frame = toString(StandardCharsets.UTF_8); reset();
            int header = frame.indexOf("\r\n\r\n");
            if (header < 0) throw new AssertionError("incomplete LSP frame");
            Map<String, Object> message = MiniJson.asObject(MiniJson.parseStrict(frame.substring(header + 4)));
            String method = String.valueOf(message.get("method"));
            if (failDefinition && "textDocument/definition".equals(method)) throw new IllegalStateException("synthetic transport failure");
            if ("initialize".equals(method)) {
                respond(message, Map.of("capabilities", Map.of("definitionProvider", true, "hoverProvider", true,
                    "referencesProvider", true, "completionProvider", Map.of(), "textDocumentSync", 2)));
            } else if ("shutdown".equals(method)) {
                respond(message, Map.of());
            } else {
                requests.add(message);
                if (immediateDefinition && "textDocument/definition".equals(method)) respond(message, location("file:///immediate.java"));
            }
        }
    };

    LspClient client() throws IOException {
        return new LspClient(this, "file:///synthetic-project", LspFeatureSettings.defaults());
    }

    Map<String, Object> await(String method) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            Map<String, Object> request = requests.poll(10, TimeUnit.MILLISECONDS);
            if (request != null && method.equals(request.get("method"))) return request;
        }
        throw new AssertionError("No in-process request: " + method);
    }

    Map<String, Object> awaitResponse(String id) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            Map<String, Object> response = requests.poll(10, TimeUnit.MILLISECONDS);
            if (response != null && id.equals(response.get("id")) && !response.containsKey("method")) return response;
        }
        throw new AssertionError("No in-process response: " + id);
    }

    void respond(Map<String, Object> request, Object result) {
        send(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
    }

    void error(Map<String, Object> request) {
        send(Map.of("jsonrpc", "2.0", "id", request.get("id"), "error", Map.of("code", -32603, "message", "synthetic failure")));
    }

    void send(Map<String, Object> message) {
        byte[] body = MiniJson.stringify(message).getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] frame = new byte[header.length + body.length];
        System.arraycopy(header, 0, frame, 0, header.length); System.arraycopy(body, 0, frame, header.length, body.length);
        incoming.add(frame);
    }

    static List<Map<String, Object>> location(String uri) {
        return List.of(Map.of("uri", uri, "range", Map.of("start", Map.of("line", 2, "character", 3),
            "end", Map.of("line", 2, "character", 5))));
    }

    @Override public InputStream getInputStream() { return input; }
    @Override public OutputStream getOutputStream() { return output; }
    @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
    @Override public int waitFor() throws InterruptedException { while (alive.get()) TimeUnit.MILLISECONDS.sleep(1); return 0; }
    @Override public boolean waitFor(long time, TimeUnit unit) { return !alive.get(); }
    @Override public int exitValue() { if (alive.get()) throw new IllegalThreadStateException(); return 0; }
    @Override public boolean isAlive() { return alive.get(); }
    @Override public void destroy() { if (alive.getAndSet(false)) incoming.add(new byte[0]); }
    @Override public void close() { destroy(); }
}
