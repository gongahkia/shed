package shed;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class DebugAdapterTransport implements AutoCloseable {
    enum State { RUNNING, FAILED, CLOSED }

    record Response(int seq, int requestSeq, String command, boolean success, Object body, String message) {
        Response { command = command == null ? "" : command; message = message == null ? "" : message; }
    }

    record Event(int seq, String event, Object body) {
        Event { event = event == null ? "" : event; }
    }

    record Diagnostic(String code, String message) {
        Diagnostic { code = code == null ? "debug-adapter" : code; message = message == null ? "" : message; }
    }

    interface Listener {
        default void onEvent(Event event) { }
        default void onDiagnostic(Diagnostic diagnostic) { }
    }

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_CONTENT_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final Duration STOP_TIMEOUT = Duration.ofMillis(250);
    private final InputStream input;
    private final OutputStream output;
    private final Process process;
    private final Closeable endpoint;
    private final Listener listener;
    private final DiagnosticLog diagnosticLog;
    private final boolean terminateDebuggee;
    private final AtomicInteger sequence;
    private final Map<Integer, CompletableFuture<Response>> pending;
    private final Set<Integer> ignoredResponses;
    private final Object outputLock;
    private volatile State state;
    private volatile boolean supportsCancelRequest;

    private DebugAdapterTransport(InputStream input, OutputStream output, Process process, Closeable endpoint, Listener listener,
        DiagnosticLog diagnosticLog, boolean terminateDebuggee) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.process = process;
        this.endpoint = endpoint;
        this.listener = listener == null ? new Listener() { } : listener;
        this.diagnosticLog = diagnosticLog;
        this.terminateDebuggee = terminateDebuggee;
        this.sequence = new AtomicInteger();
        this.pending = new ConcurrentHashMap<>();
        this.ignoredResponses = ConcurrentHashMap.newKeySet();
        this.outputLock = new Object();
        this.state = State.RUNNING;
        this.supportsCancelRequest = false;
        startReader();
        startStderrDrain();
    }

    static DebugAdapterTransport start(DebugAdapterRegistry.Plan plan, DebugFeatureSettings features, Listener listener) throws IOException {
        return start(plan, features, listener, null);
    }

    static DebugAdapterTransport start(DebugAdapterRegistry.Plan plan, DebugFeatureSettings features, Listener listener, DiagnosticLog diagnosticLog) throws IOException {
        if (plan == null || plan.adapter() == null || plan.configuration() == null || plan.cwd() == null) {
            throw new IOException("A validated debug adapter plan is required");
        }
        validatePlanPaths(plan);
        DebugFeatureSettings settings = features == null ? DebugFeatureSettings.defaults() : features;
        if (!settings.enabled()) throw new IOException("Debugging is disabled by settings");
        if (plan.configuration().request() == DebugAdapterRegistry.Request.ATTACH && !settings.attach()) {
            throw new IOException("Debug attach is disabled by settings");
        }
        DebugAdapterRegistry.Adapter adapter = plan.adapter();
        boolean terminateDebuggee = plan.configuration().request() == DebugAdapterRegistry.Request.LAUNCH;
        if (adapter.transport() == DebugAdapterRegistry.Transport.TCP) {
            String host = plan.configuration().host();
            if (!loopback(host) || plan.configuration().port() < 1 || plan.configuration().port() > 65535) {
                throw new IOException("Debug adapter TCP targets must be loopback with a valid port");
            }
            InetAddress address = InetAddress.getByName(host);
            if (!address.isLoopbackAddress()) throw new IOException("Debug adapter TCP target did not resolve to loopback");
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, plan.configuration().port()), CONNECT_TIMEOUT_MS);
                return new DebugAdapterTransport(socket.getInputStream(), socket.getOutputStream(), null, socket, listener, diagnosticLog, terminateDebuggee);
            } catch (IOException error) {
                try { socket.close(); } catch (IOException ignored) { }
                throw error;
            }
        }
        if (adapter.command().isBlank() || containsControl(adapter.command())) {
            throw new IOException("Debug adapter command is invalid");
        }
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(adapter.command());
        for (String argument : adapter.args()) {
            if (argument == null || containsControl(argument)) throw new IOException("Debug adapter argument is invalid");
            command.add(argument);
        }
        Process process = new ProcessBuilder(command).directory(plan.cwd().toFile()).start();
        return new DebugAdapterTransport(process.getInputStream(), process.getOutputStream(), process, null, listener, diagnosticLog, terminateDebuggee);
    }

    static DebugAdapterTransport startRemote(DebugAdapterRegistry.Plan plan, List<String> command, DebugFeatureSettings features, Listener listener,
                                             DiagnosticLog diagnosticLog) throws IOException {
        if (plan == null || plan.adapter() == null || plan.configuration() == null || plan.workspace() == null) {
            throw new IOException("A validated remote debug adapter plan is required");
        }
        validatePlanPaths(plan);
        DebugFeatureSettings settings = features == null ? DebugFeatureSettings.defaults() : features;
        if (!settings.enabled()) throw new IOException("Debugging is disabled by settings");
        if (plan.adapter().transport() != DebugAdapterRegistry.Transport.STDIO) {
            throw new IOException("Remote debugging requires a stdio debug adapter; TCP remains local-only");
        }
        List<String> invocation = command == null ? List.of() : List.copyOf(command);
        if (invocation.isEmpty()) throw new IOException("Remote debug adapter command is unavailable");
        for (String argument : invocation) {
            if (argument == null || argument.isBlank() || containsControl(argument)) throw new IOException("Remote debug adapter command is invalid");
        }
        Process process = new ProcessBuilder(invocation).directory(plan.workspace().toFile()).start();
        boolean terminateDebuggee = plan.configuration().request() == DebugAdapterRegistry.Request.LAUNCH;
        return new DebugAdapterTransport(process.getInputStream(), process.getOutputStream(), process, null, listener, diagnosticLog, terminateDebuggee);
    }

    static DebugAdapterTransport forStreams(InputStream input, OutputStream output, Listener listener) {
        return new DebugAdapterTransport(input, output, null, null, listener, null, false);
    }

    static DebugAdapterTransport forStreams(InputStream input, OutputStream output, Process process, Listener listener) {
        return new DebugAdapterTransport(input, output, process, null, listener, null, false);
    }

    State state() { return state; }

    Response request(String command, Map<String, Object> arguments, Duration timeout) throws IOException, TimeoutException, InterruptedException {
        if (state != State.RUNNING) {
            throw new IOException("Debug adapter transport is not running");
        }
        if (command == null || command.isBlank() || containsControl(command)) {
            throw new IllegalArgumentException("DAP command is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("DAP request timeout must be positive");
        }
        int requestSeq = nextSequence();
        CompletableFuture<Response> response = new CompletableFuture<>();
        pending.put(requestSeq, response);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("seq", requestSeq);
        request.put("type", "request");
        request.put("command", command);
        request.put("arguments", immutableMap(arguments));
        try {
            write(request);
        } catch (IOException error) {
            pending.remove(requestSeq);
            throw error;
        }
        try {
            return response.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            pending.remove(requestSeq);
            ignoredResponses.add(requestSeq);
            cancel(requestSeq);
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Debug adapter request failed", cause);
        }
    }

    @Override
    public void close() {
        if (state == State.CLOSED) return;
        if (state == State.RUNNING) {
            try { sendDisconnect(); } catch (IOException ignored) { }
        }
        state = State.CLOSED;
        failPending(new IOException("Debug adapter transport closed"));
        closeResources();
    }

    static void writeMessage(OutputStream output, Map<String, Object> message) throws IOException {
        if (output == null || message == null) throw new IOException("DAP message output is unavailable");
        byte[] payload = MiniJson.stringify(message).getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_CONTENT_BYTES) throw new IOException("DAP message exceeds the content limit");
        output.write(("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
    }

    static Map<String, Object> readMessage(InputStream input) throws IOException {
        if (input == null) throw new IOException("DAP message input is unavailable");
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) {
                if (header.size() == 0) return null;
                throw new IOException("DAP stream ended inside a header");
            }
            if (current > 0x7f) throw new IOException("DAP header is not ASCII");
            header.write(current);
            if (header.size() > MAX_HEADER_BYTES) throw new IOException("DAP header exceeds the size limit");
            if (previous == '\r' && current == '\n' && endsWithHeaderSeparator(header)) break;
            previous = current;
        }
        int contentLength = contentLength(header.toString(StandardCharsets.US_ASCII));
        byte[] payload = input.readNBytes(contentLength);
        if (payload.length != contentLength) throw new IOException("DAP stream ended inside a message body");
        String json = decodeUtf8(payload);
        validateJson(json);
        try {
            Map<String, Object> message = MiniJson.asObject(MiniJson.parse(json));
            if (message == null) throw new IOException("DAP message body must be a JSON object");
            return message;
        } catch (RuntimeException error) {
            throw new IOException("DAP message body is invalid JSON", error);
        }
    }

    private void cancel(int requestSeq) {
        if (state != State.RUNNING || !supportsCancelRequest) return;
        int cancelSeq;
        try {
            cancelSeq = nextSequence();
        } catch (IllegalStateException ignored) {
            return;
        }
        ignoredResponses.add(cancelSeq);
        Map<String, Object> cancellation = new LinkedHashMap<>();
        cancellation.put("seq", cancelSeq);
        cancellation.put("type", "request");
        cancellation.put("command", "cancel");
        cancellation.put("arguments", Map.of("requestId", requestSeq));
        try {
            write(cancellation);
        } catch (IOException ignored) { }
    }

    private void sendDisconnect() throws IOException {
        int disconnectSeq = nextSequence();
        ignoredResponses.add(disconnectSeq);
        Map<String, Object> disconnect = new LinkedHashMap<>();
        disconnect.put("seq", disconnectSeq);
        disconnect.put("type", "request");
        disconnect.put("command", "disconnect");
        disconnect.put("arguments", Map.of("terminateDebuggee", terminateDebuggee));
        write(disconnect);
    }

    private int nextSequence() {
        int next = sequence.incrementAndGet();
        if (next <= 0) throw new IllegalStateException("DAP sequence space exhausted");
        return next;
    }

    private void write(Map<String, Object> message) throws IOException {
        if (state != State.RUNNING) throw new IOException("Debug adapter transport is not running");
        try {
            synchronized (outputLock) { writeMessage(output, message); }
        } catch (IOException error) {
            fail("write-failed", "Writing a DAP message failed", error);
            throw error;
        }
    }

    private void startReader() {
        Thread reader = new Thread(() -> {
            try {
                while (state == State.RUNNING) {
                    Map<String, Object> message = readMessage(input);
                    if (message == null) {
                        fail("adapter-closed", "The debug adapter closed its protocol stream", new IOException("Adapter protocol stream closed"));
                        return;
                    }
                    dispatch(message);
                }
            } catch (IOException error) {
                fail("malformed-adapter-output", "The debug adapter emitted malformed protocol output", error);
            } catch (RuntimeException error) {
                fail("reader-failed", "The debug adapter reader failed safely", error);
            }
        }, "shed-dap-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void startStderrDrain() {
        if (process == null) return;
        Thread stderr = new Thread(() -> {
            try (InputStream errors = process.getErrorStream()) {
                byte[] buffer = new byte[4_096];
                while (errors.read(buffer) >= 0) { }
            } catch (IOException ignored) { }
        }, "shed-dap-stderr");
        stderr.setDaemon(true);
        stderr.start();
    }

    private void dispatch(Map<String, Object> message) throws IOException {
        String type = MiniJson.asString(message.get("type"));
        int seq = positiveInteger(message.get("seq"), "seq");
        if ("response".equals(type)) {
            int requestSeq = positiveInteger(message.get("request_seq"), "request_seq");
            Boolean success = message.get("success") instanceof Boolean value ? value : null;
            if (success == null) throw new IOException("DAP response success must be boolean");
            String command = MiniJson.asString(message.get("command"));
            if (command == null || command.isBlank()) throw new IOException("DAP response command is required");
            Object body = message.get("body");
            String responseMessage = MiniJson.asString(message.get("message"));
            if (ignoredResponses.remove(requestSeq)) return;
            CompletableFuture<Response> response = pending.remove(requestSeq);
            if (response != null) {
                if (success && "initialize".equals(command)) {
                    Map<String, Object> capabilities = MiniJson.asObject(body);
                    supportsCancelRequest = Boolean.TRUE.equals(capabilities == null ? null : capabilities.get("supportsCancelRequest"));
                }
                response.complete(new Response(seq, requestSeq, command, success, body, responseMessage));
            }
            return;
        }
        if ("event".equals(type)) {
            String event = MiniJson.asString(message.get("event"));
            if (event == null || event.isBlank()) throw new IOException("DAP event name is required");
            Event incoming = new Event(seq, event, message.get("body"));
            try { listener.onEvent(incoming); }
            catch (RuntimeException error) { report("event-listener-failed", "A debug event listener failed", error); }
            return;
        }
        if ("request".equals(type)) {
            String command = MiniJson.asString(message.get("command"));
            if (command == null || command.isBlank()) throw new IOException("DAP request command is required");
            sendUnsupportedRequestResponse(seq, command);
            return;
        }
        throw new IOException("DAP message type is unsupported");
    }

    private void sendUnsupportedRequestResponse(int requestSeq, String command) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seq", nextSequence());
        response.put("type", "response");
        response.put("request_seq", requestSeq);
        response.put("success", Boolean.FALSE);
        response.put("command", command);
        response.put("message", "Shed does not support adapter-initiated requests yet");
        write(response);
    }

    private void fail(String code, String message, Throwable cause) {
        State previous = state;
        if (previous == State.CLOSED || previous == State.FAILED) return;
        state = State.FAILED;
        report(code, message, cause);
        failPending(new IOException(message, cause));
        closeResources();
    }

    private void failPending(IOException error) {
        for (CompletableFuture<Response> response : pending.values()) response.completeExceptionally(error);
        pending.clear();
    }

    private void closeResources() {
        try { input.close(); } catch (IOException ignored) { }
        try { output.close(); } catch (IOException ignored) { }
        if (endpoint != null) {
            try { endpoint.close(); } catch (IOException ignored) { }
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void report(String code, String message, Throwable cause) {
        Diagnostic diagnostic = new Diagnostic(code, message);
        try { listener.onDiagnostic(diagnostic); } catch (RuntimeException ignored) { }
        if (diagnosticLog != null && cause != null) {
            diagnosticLog.record(DiagnosticLog.Severity.ERROR, "debug-adapter", code, cause, "docs/DAP.md#transport");
        }
    }

    private static int contentLength(String rawHeader) throws IOException {
        if (!rawHeader.endsWith("\r\n\r\n")) throw new IOException("DAP header is unterminated");
        Integer value = null;
        String fields = rawHeader.substring(0, rawHeader.length() - 4);
        for (String line : fields.split("\r\n", -1)) {
            if (line.isEmpty()) throw new IOException("DAP header contains an empty field");
            if (!line.regionMatches(true, 0, "Content-Length: ", 0, "Content-Length: ".length())) {
                throw new IOException("DAP header field is unsupported");
            }
            if (value != null) throw new IOException("DAP header repeats Content-Length");
            String length = line.substring("Content-Length: ".length());
            if (!length.matches("[0-9]+")) throw new IOException("DAP Content-Length is invalid");
            try { value = Integer.parseInt(length); }
            catch (NumberFormatException error) { throw new IOException("DAP Content-Length is invalid", error); }
        }
        if (value == null || value < 0 || value > MAX_CONTENT_BYTES) throw new IOException("DAP Content-Length is outside the allowed range");
        return value;
    }

    private static String decodeUtf8(byte[] payload) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("DAP message body is not UTF-8", error);
        }
    }

    private static void validateJson(String json) throws IOException {
        try { new JsonSyntax(json).validate(); }
        catch (IllegalArgumentException error) { throw new IOException("DAP message body is invalid JSON", error); }
    }

    private static boolean endsWithHeaderSeparator(ByteArrayOutputStream header) {
        byte[] bytes = header.toByteArray();
        int length = bytes.length;
        return length >= 4 && bytes[length - 4] == '\r' && bytes[length - 3] == '\n' && bytes[length - 2] == '\r' && bytes[length - 1] == '\n';
    }

    private static int positiveInteger(Object value, String field) throws IOException {
        if (!(value instanceof Number number)) throw new IOException("DAP " + field + " must be an integer");
        long integer = number.longValue();
        if (number.doubleValue() != integer || integer < 1 || integer > Integer.MAX_VALUE) {
            throw new IOException("DAP " + field + " must be a positive 32-bit integer");
        }
        return (int) integer;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static boolean loopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static boolean containsControl(String value) {
        if (value == null) return true;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }

    private static void validatePlanPaths(DebugAdapterRegistry.Plan plan) throws IOException {
        if (plan.workspace() == null) throw new IOException("Debug adapter workspace is required");
        Path workspace = plan.workspace().toAbsolutePath().normalize();
        Path cwd = plan.cwd().toAbsolutePath().normalize();
        if (!cwd.startsWith(workspace)) throw new IOException("Debug adapter cwd escapes the workspace");
        if (plan.configuration().request() == DebugAdapterRegistry.Request.LAUNCH && !plan.configuration().program().isBlank()) {
            if (plan.program() == null || !plan.program().toAbsolutePath().normalize().startsWith(workspace)) {
                throw new IOException("Debug adapter program escapes the workspace");
            }
        }
    }

    private static final class JsonSyntax {
        private final String source;
        private int index;
        private int depth;

        private JsonSyntax(String source) { this.source = source == null ? "" : source; }

        private void validate() {
            whitespace(); value(); whitespace();
            if (index != source.length()) throw error("trailing content");
        }

        private void value() {
            if (index >= source.length()) throw error("missing value");
            switch (source.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true");
                case 'f' -> literal("false");
                case 'n' -> literal("null");
                default -> number();
            }
        }

        private void object() {
            nested(); expect('{'); whitespace();
            if (consume('}')) { depth--; return; }
            while (true) {
                string(); whitespace(); expect(':'); whitespace(); value(); whitespace();
                if (consume('}')) { depth--; return; }
                expect(','); whitespace();
            }
        }

        private void array() {
            nested(); expect('['); whitespace();
            if (consume(']')) { depth--; return; }
            while (true) {
                value(); whitespace();
                if (consume(']')) { depth--; return; }
                expect(','); whitespace();
            }
        }

        private void string() {
            expect('"');
            while (index < source.length()) {
                char current = source.charAt(index++);
                if (current == '"') return;
                if (current < 0x20) throw error("control character in string");
                if (current != '\\') continue;
                if (index >= source.length()) throw error("unfinished escape");
                char escape = source.charAt(index++);
                if ("\"\\/bfnrt".indexOf(escape) >= 0) continue;
                if (escape != 'u') throw error("invalid escape");
                for (int remaining = 0; remaining < 4; remaining++) {
                    if (index >= source.length() || Character.digit(source.charAt(index++), 16) < 0) throw error("invalid unicode escape");
                }
            }
            throw error("unterminated string");
        }

        private void number() {
            consume('-');
            if (consume('0')) { }
            else {
                if (index >= source.length() || source.charAt(index) < '1' || source.charAt(index) > '9') throw error("invalid number");
                while (digit()) { }
            }
            if (consume('.')) {
                if (!digit()) throw error("invalid fraction");
                while (digit()) { }
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-');
                if (!digit()) throw error("invalid exponent");
                while (digit()) { }
            }
        }

        private void literal(String value) {
            if (!source.startsWith(value, index)) throw error("invalid literal");
            index += value.length();
        }

        private void whitespace() {
            while (index < source.length() && (source.charAt(index) == ' ' || source.charAt(index) == '\t' || source.charAt(index) == '\r' || source.charAt(index) == '\n')) index++;
        }

        private void nested() {
            if (++depth > 64) throw error("JSON nesting exceeds the limit");
        }

        private void expect(char expected) {
            if (!consume(expected)) throw error("expected '" + expected + "'");
        }

        private boolean consume(char expected) {
            if (index < source.length() && source.charAt(index) == expected) { index++; return true; }
            return false;
        }

        private boolean digit() {
            if (index >= source.length() || source.charAt(index) < '0' || source.charAt(index) > '9') return false;
            index++;
            return true;
        }

        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at character " + index); }
    }
}
