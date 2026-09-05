package shed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

final class ReferenceDebugAdapter implements AutoCloseable {
    enum Mode { NORMAL, TIMEOUT_THREADS, MALFORMED_OUTPUT }

    private final PipedInputStream clientInput;
    private final PipedOutputStream adapterOutput;
    private final PipedInputStream adapterInput;
    private final PipedOutputStream clientOutput;
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final FakeProcess process = new FakeProcess();
    private volatile Mode mode;
    private volatile boolean closed;
    private int sequence;
    private int launchRequest;

    ReferenceDebugAdapter(Mode mode) throws IOException {
        this.mode = mode == null ? Mode.NORMAL : mode;
        clientInput = new PipedInputStream();
        adapterOutput = new PipedOutputStream(clientInput);
        adapterInput = new PipedInputStream();
        clientOutput = new PipedOutputStream(adapterInput);
        Thread thread = new Thread(this::run, "reference-dap-adapter");
        thread.setDaemon(true);
        thread.start();
    }

    InputStream clientInput() { return clientInput; }
    OutputStream clientOutput() { return clientOutput; }
    Process process() { return process; }
    List<String> commands() { return List.copyOf(commands); }
    void mode(Mode mode) { this.mode = mode == null ? Mode.NORMAL : mode; }
    boolean destroyed() { return process.destroyed; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { clientOutput.close(); } catch (IOException ignored) { }
        try { adapterOutput.close(); } catch (IOException ignored) { }
        try { adapterInput.close(); } catch (IOException ignored) { }
        try { clientInput.close(); } catch (IOException ignored) { }
    }

    private void run() {
        if (mode == Mode.MALFORMED_OUTPUT) {
            try {
                adapterOutput.write("Content-Length: invalid\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                adapterOutput.flush();
            } catch (IOException ignored) { }
            return;
        }
        try {
            while (!closed) {
                Map<String, Object> request = DebugAdapterTransport.readMessage(adapterInput);
                if (request == null) return;
                String command = MiniJson.asString(request.get("command"));
                int requestSeq = number(request.get("seq"));
                if (command == null || requestSeq < 1) return;
                commands.add(command);
                handle(command, requestSeq, request);
                if ("disconnect".equals(command)) return;
            }
        } catch (IOException ignored) {
        }
    }

    private void handle(String command, int requestSeq, Map<String, Object> request) throws IOException {
        switch (command) {
            case "initialize" -> response(requestSeq, command, true,
                Map.of("supportsCancelRequest", true, "supportsConfigurationDoneRequest", true, "supportsFunctionBreakpoints", true), "");
            case "launch", "attach" -> {
                launchRequest = requestSeq;
                event("initialized", Map.of());
            }
            case "setBreakpoints" -> response(requestSeq, command, true, Map.of("breakpoints", breakpoints(request)), "");
            case "setFunctionBreakpoints" -> response(requestSeq, command, true, Map.of("breakpoints", breakpoints(request)), "");
            case "configurationDone" -> {
                response(requestSeq, command, true, Map.of(), "");
                if (launchRequest > 0) response(launchRequest, "launch", true, Map.of(), "");
                event("stopped", Map.of("reason", "breakpoint", "threadId", 7));
            }
            case "threads" -> {
                if (mode != Mode.TIMEOUT_THREADS) response(requestSeq, command, true, Map.of("threads", List.of(Map.of("id", 7, "name", "main"))), "");
            }
            case "stackTrace" -> response(requestSeq, command, true, Map.of("stackFrames", List.of(
                Map.of("id", 44, "name", "main", "source", Map.of("path", "Main.java"), "line", 8, "column", 1))), "");
            case "scopes" -> response(requestSeq, command, true, Map.of("scopes", List.of(Map.of("name", "Locals", "variablesReference", 55))), "");
            case "variables" -> response(requestSeq, command, true, Map.of("variables", variables(request)), "");
            case "evaluate" -> response(requestSeq, command, true, evaluation(request), "");
            case "cancel", "disconnect" -> response(requestSeq, command, true, Map.of(), "");
            default -> response(requestSeq, command, false, Map.of(), "unsupported test command");
        }
    }

    private List<Map<String, Object>> breakpoints(Map<String, Object> request) {
        Map<String, Object> arguments = MiniJson.asObject(request.get("arguments"));
        List<Object> requested = arguments == null ? null : MiniJson.asArray(arguments.get("breakpoints"));
        if (requested == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : requested) {
            Map<String, Object> breakpoint = MiniJson.asObject(value);
            result.add(Map.of("verified", true, "line", number(breakpoint == null ? null : breakpoint.get("line"))));
        }
        return result;
    }

    private List<Map<String, Object>> variables(Map<String, Object> request) {
        Map<String, Object> arguments = MiniJson.asObject(request.get("arguments"));
        return switch (number(arguments == null ? null : arguments.get("variablesReference"))) {
            case 55 -> List.of(Map.of("name", "count", "value", "2", "type", "int", "variablesReference", 56));
            case 56 -> List.of(Map.of("name", "bits", "value", "0b10", "type", "binary"));
            default -> List.of();
        };
    }

    private Map<String, Object> evaluation(Map<String, Object> request) {
        Map<String, Object> arguments = MiniJson.asObject(request.get("arguments"));
        String expression = MiniJson.asString(arguments == null ? null : arguments.get("expression"));
        String context = MiniJson.asString(arguments == null ? null : arguments.get("context"));
        if ("count + 1".equals(expression) && "repl".equals(context)) return Map.of("result", "3", "type", "int");
        return Map.of("result", "2", "type", "int");
    }

    private void response(int requestSeq, String command, boolean success, Object body, String message) throws IOException {
        DebugAdapterTransport.writeMessage(adapterOutput, Map.of("seq", ++sequence, "type", "response", "request_seq", requestSeq,
            "success", success, "command", command, "body", body, "message", message));
    }

    private void event(String event, Object body) throws IOException {
        DebugAdapterTransport.writeMessage(adapterOutput, Map.of("seq", ++sequence, "type", "event", "event", event, "body", body));
    }

    private static int number(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue() || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE) return 0;
        return (int) number.longValue();
    }

    private static final class FakeProcess extends Process {
        private volatile boolean destroyed;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { destroyed = true; return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return destroyed; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public boolean isAlive() { return !destroyed; }
    }
}
