import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class LspClient {
    public static class CompletionItem {
        private final String label;
        private final String detail;
        private final Integer kind;

        public CompletionItem(String label, String detail, Integer kind) {
            this.label = label;
            this.detail = detail;
            this.kind = kind;
        }

        public String getLabel() {
            return label;
        }

        public String getDetail() {
            return detail;
        }

        public Integer getKind() {
            return kind;
        }
    }

    public static class Location {
        private final String uri;
        private final int line;
        private final int character;

        public Location(String uri, int line, int character) {
            this.uri = uri;
            this.line = line;
            this.character = character;
        }

        public String getUri() {
            return uri;
        }

        public int getLine() {
            return line;
        }

        public int getCharacter() {
            return character;
        }
    }

    public static class Diagnostic {
        private final int line;
        private final int character;
        private final int severity;
        private final String message;

        public Diagnostic(int line, int character, int severity, String message) {
            this.line = line;
            this.character = character;
            this.severity = severity;
            this.message = message;
        }

        public int getLine() {
            return line;
        }

        public int getCharacter() {
            return character;
        }

        public int getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }
    }

    private final Process process;
    private final BufferedOutputStream stdin;
    private final BlockingQueue<Map<String, Object>> messageQueue;
    private final List<Map<String, Object>> deferredMessages;
    private final Map<String, List<Diagnostic>> diagnostics;
    private int requestId;
    private boolean initialized;

    public LspClient(String command, String[] args, Path rootPath) throws IOException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) {
                    commandLine.add(arg);
                }
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
        processBuilder.directory(rootPath.toFile());
        this.process = processBuilder.start();
        this.stdin = new BufferedOutputStream(process.getOutputStream());
        this.messageQueue = new LinkedBlockingQueue<>();
        this.deferredMessages = new ArrayList<>();
        this.diagnostics = new HashMap<>();
        this.requestId = 0;
        this.initialized = false;
        startReaderThread();
        initialize(rootPath);
    }

    public boolean isAlive() {
        return initialized && process.isAlive();
    }

    public void didOpen(String uri, String languageId, String text) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        textDocument.put("languageId", languageId);
        textDocument.put("version", 1);
        textDocument.put("text", text);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        sendNotification("textDocument/didOpen", params);
    }

    public void didChange(String uri, int version, String text) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        textDocument.put("version", version);

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("text", text);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("contentChanges", List.of(change));
        sendNotification("textDocument/didChange", params);
    }

    public void didSave(String uri) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        sendNotification("textDocument/didSave", params);
    }

    public List<CompletionItem> completion(String uri, int line, int character) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);

        Map<String, Object> position = new LinkedHashMap<>();
        position.put("line", line);
        position.put("character", character);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("position", position);

        Map<String, Object> response = sendRequest("textDocument/completion", params, 2000L);
        if (response == null) {
            return List.of();
        }

        Object result = response.get("result");
        List<Object> items = MiniJson.asArray(result);
        if (items == null) {
            Map<String, Object> resultObject = MiniJson.asObject(result);
            items = resultObject == null ? null : MiniJson.asArray(resultObject.get("items"));
        }
        if (items == null) {
            return List.of();
        }

        List<CompletionItem> completions = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> itemObject = MiniJson.asObject(item);
            if (itemObject == null) {
                continue;
            }
            String label = MiniJson.asString(itemObject.get("label"));
            if (label == null || label.isEmpty()) {
                continue;
            }
            String detail = MiniJson.asString(itemObject.get("detail"));
            Integer kind = MiniJson.asInt(itemObject.get("kind"));
            completions.add(new CompletionItem(label, detail, kind));
        }
        return completions;
    }

    public String hover(String uri, int line, int character) {
        Map<String, Object> response = sendTextDocumentPositionRequest("textDocument/hover", uri, line, character, 2000L);
        if (response == null) {
            return null;
        }
        Map<String, Object> result = MiniJson.asObject(response.get("result"));
        if (result == null) {
            return null;
        }
        Object contents = result.get("contents");
        String simple = MiniJson.asString(contents);
        if (simple != null) {
            return simple;
        }
        Map<String, Object> contentObject = MiniJson.asObject(contents);
        if (contentObject != null) {
            return MiniJson.asString(contentObject.get("value"));
        }
        List<Object> contentList = MiniJson.asArray(contents);
        if (contentList == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Object item : contentList) {
            String part = MiniJson.asString(item);
            if (part != null) {
                parts.add(part);
                continue;
            }
            Map<String, Object> itemObject = MiniJson.asObject(item);
            if (itemObject != null) {
                String value = MiniJson.asString(itemObject.get("value"));
                if (value != null) {
                    parts.add(value);
                }
            }
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    public Location definition(String uri, int line, int character) {
        Map<String, Object> response = sendTextDocumentPositionRequest("textDocument/definition", uri, line, character, 2000L);
        if (response == null) {
            return null;
        }
        Object result = response.get("result");
        Map<String, Object> location = MiniJson.asObject(result);
        if (location == null) {
            List<Object> locations = MiniJson.asArray(result);
            if (locations == null || locations.isEmpty()) {
                return null;
            }
            location = MiniJson.asObject(locations.get(0));
        }
        if (location == null) {
            return null;
        }
        return parseLocation(location);
    }

    public List<Diagnostic> getDiagnostics(String uri) {
        drainNotifications();
        List<Diagnostic> entries = diagnostics.get(uri);
        return entries == null ? List.of() : new ArrayList<>(entries);
    }

    public void drainNotifications() {
        while (true) {
            Map<String, Object> message = messageQueue.poll();
            if (message == null) {
                return;
            }
            if (message.containsKey("method")) {
                handleNotification(message);
            } else {
                synchronized (deferredMessages) {
                    deferredMessages.add(message);
                }
            }
        }
    }

    public void stop() {
        try {
            sendRequest("shutdown", null, 1000L);
            sendNotification("exit", null);
        } catch (Exception ignored) {
        }
        process.destroy();
    }

    private void initialize(Path rootPath) throws IOException {
        Map<String, Object> capabilities = new LinkedHashMap<>();

        Map<String, Object> completionItem = new LinkedHashMap<>();
        completionItem.put("snippetSupport", Boolean.FALSE);
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("completionItem", completionItem);
        Map<String, Object> hover = new LinkedHashMap<>();
        hover.put("contentFormat", List.of("plaintext"));
        Map<String, Object> diagnosticsCapability = new LinkedHashMap<>();
        diagnosticsCapability.put("relatedInformation", Boolean.FALSE);

        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("completion", completion);
        textDocument.put("hover", hover);
        textDocument.put("definition", new LinkedHashMap<>());
        textDocument.put("publishDiagnostics", diagnosticsCapability);
        capabilities.put("textDocument", textDocument);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", rootPath.toAbsolutePath().toUri().toString());
        params.put("capabilities", capabilities);

        Map<String, Object> response = sendRequest("initialize", params, 5000L);
        if (response == null) {
            throw new IOException("LSP initialize timed out");
        }
        sendNotification("initialized", new LinkedHashMap<>());
        initialized = true;
    }

    private Map<String, Object> sendTextDocumentPositionRequest(String method, String uri, int line, int character, long timeoutMs) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("line", line);
        position.put("character", character);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("position", position);
        return sendRequest(method, params, timeoutMs);
    }

    private Map<String, Object> sendRequest(String method, Object params, long timeoutMs) {
        int id = nextRequestId();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        writeMessage(request);
        return waitForResponse(id, timeoutMs);
    }

    private void sendNotification(String method, Object params) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        writeMessage(notification);
    }

    private void writeMessage(Map<String, Object> body) {
        try {
            byte[] payload = MiniJson.stringify(body).getBytes(StandardCharsets.UTF_8);
            stdin.write(("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            stdin.write(payload);
            stdin.flush();
        } catch (IOException ignored) {
        }
    }

    private Map<String, Object> waitForResponse(int id, long timeoutMs) {
        Map<String, Object> deferred = removeDeferredResponse(id);
        if (deferred != null) {
            return deferred;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            Map<String, Object> message;
            try {
                message = messageQueue.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (message == null) {
                continue;
            }
            Integer responseId = MiniJson.asInt(message.get("id"));
            if (responseId != null && responseId == id) {
                return message;
            }
            if (message.containsKey("method")) {
                handleNotification(message);
            } else {
                synchronized (deferredMessages) {
                    deferredMessages.add(message);
                }
            }
        }
        return null;
    }

    private Map<String, Object> removeDeferredResponse(int id) {
        synchronized (deferredMessages) {
            for (int i = 0; i < deferredMessages.size(); i++) {
                Map<String, Object> candidate = deferredMessages.get(i);
                Integer responseId = MiniJson.asInt(candidate.get("id"));
                if (responseId != null && responseId == id) {
                    return deferredMessages.remove(i);
                }
            }
        }
        return null;
    }

    private void handleNotification(Map<String, Object> message) {
        String method = MiniJson.asString(message.get("method"));
        if (!"textDocument/publishDiagnostics".equals(method)) {
            return;
        }
        Map<String, Object> params = MiniJson.asObject(message.get("params"));
        if (params == null) {
            return;
        }
        String uri = MiniJson.asString(params.get("uri"));
        List<Object> diagnosticObjects = MiniJson.asArray(params.get("diagnostics"));
        if (uri == null || diagnosticObjects == null) {
            return;
        }
        List<Diagnostic> parsed = new ArrayList<>();
        for (Object diagnosticObject : diagnosticObjects) {
            Map<String, Object> entry = MiniJson.asObject(diagnosticObject);
            if (entry == null) {
                continue;
            }
            Map<String, Object> range = MiniJson.asObject(entry.get("range"));
            Map<String, Object> start = range == null ? null : MiniJson.asObject(range.get("start"));
            int line = start == null || MiniJson.asInt(start.get("line")) == null ? 0 : MiniJson.asInt(start.get("line"));
            int character = start == null || MiniJson.asInt(start.get("character")) == null ? 0 : MiniJson.asInt(start.get("character"));
            int severity = MiniJson.asInt(entry.get("severity")) == null ? 0 : MiniJson.asInt(entry.get("severity"));
            String messageText = MiniJson.asString(entry.get("message"));
            parsed.add(new Diagnostic(line, character, severity, messageText == null ? "" : messageText));
        }
        diagnostics.put(uri, parsed);
    }

    private Location parseLocation(Map<String, Object> location) {
        String uri = MiniJson.asString(location.get("uri"));
        Map<String, Object> range = MiniJson.asObject(location.get("range"));
        Map<String, Object> start = range == null ? null : MiniJson.asObject(range.get("start"));
        if (uri == null || start == null) {
            return null;
        }
        Integer line = MiniJson.asInt(start.get("line"));
        Integer character = MiniJson.asInt(start.get("character"));
        if (line == null || character == null) {
            return null;
        }
        return new Location(uri, line, character);
    }

    private synchronized int nextRequestId() {
        requestId += 1;
        return requestId;
    }

    private void startReaderThread() {
        Thread reader = new Thread(() -> {
            try (BufferedInputStream stdout = new BufferedInputStream(process.getInputStream())) {
                while (true) {
                    int contentLength = readContentLength(stdout);
                    if (contentLength < 0) {
                        return;
                    }
                    byte[] body = stdout.readNBytes(contentLength);
                    if (body.length != contentLength) {
                        return;
                    }
                    Object parsed = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                    Map<String, Object> message = MiniJson.asObject(parsed);
                    if (message != null) {
                        messageQueue.offer(message);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "shed-lsp-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private int readContentLength(BufferedInputStream stream) throws IOException {
        StringBuilder headers = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = stream.read()) != -1) {
            headers.append((char) current);
            if (previous == '\r' && current == '\n' && headers.toString().endsWith("\r\n\r\n")) {
                break;
            }
            previous = current;
        }
        if (headers.length() == 0) {
            return -1;
        }
        for (String line : headers.toString().split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                return Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
        }
        return -1;
    }
}
