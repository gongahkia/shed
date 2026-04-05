package shed;

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

    public static class TextEdit {
        private final String uri;
        private final int startLine;
        private final int startCharacter;
        private final int endLine;
        private final int endCharacter;
        private final String newText;

        public TextEdit(String uri, int startLine, int startCharacter, int endLine, int endCharacter, String newText) {
            this.uri = uri;
            this.startLine = startLine;
            this.startCharacter = startCharacter;
            this.endLine = endLine;
            this.endCharacter = endCharacter;
            this.newText = newText == null ? "" : newText;
        }

        public String getUri() {
            return uri;
        }

        public int getStartLine() {
            return startLine;
        }

        public int getStartCharacter() {
            return startCharacter;
        }

        public int getEndLine() {
            return endLine;
        }

        public int getEndCharacter() {
            return endCharacter;
        }

        public String getNewText() {
            return newText;
        }
    }

    public static class CodeAction {
        private final String title;
        private final String kind;
        private final boolean preferred;
        private final List<TextEdit> edits;
        private final String commandId;
        private final Object commandArguments;

        public CodeAction(String title, String kind, boolean preferred, List<TextEdit> edits, String commandId, Object commandArguments) {
            this.title = title == null ? "" : title;
            this.kind = kind == null ? "" : kind;
            this.preferred = preferred;
            this.edits = edits == null ? List.of() : new ArrayList<>(edits);
            this.commandId = commandId == null ? "" : commandId;
            this.commandArguments = commandArguments;
        }

        public String getTitle() {
            return title;
        }

        public String getKind() {
            return kind;
        }

        public boolean isPreferred() {
            return preferred;
        }

        public List<TextEdit> getEdits() {
            return new ArrayList<>(edits);
        }

        public String getCommandId() {
            return commandId;
        }

        public Object getCommandArguments() {
            return commandArguments;
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

    public List<Location> references(String uri, int line, int character, boolean includeDeclaration) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("line", line);
        position.put("character", character);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("includeDeclaration", includeDeclaration);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("position", position);
        params.put("context", context);

        Map<String, Object> response = sendRequest("textDocument/references", params, 2500L);
        if (response == null) {
            return List.of();
        }
        List<Object> result = MiniJson.asArray(response.get("result"));
        if (result == null) {
            return List.of();
        }
        List<Location> locations = new ArrayList<>();
        for (Object item : result) {
            Map<String, Object> candidate = MiniJson.asObject(item);
            if (candidate == null) {
                continue;
            }
            Location parsed = parseLocation(candidate);
            if (parsed != null) {
                locations.add(parsed);
            }
        }
        return locations;
    }

    public List<TextEdit> rename(String uri, int line, int character, String newName) {
        if (newName == null || newName.isBlank()) {
            return List.of();
        }
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("line", line);
        position.put("character", character);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("position", position);
        params.put("newName", newName);

        Map<String, Object> response = sendRequest("textDocument/rename", params, 3000L);
        if (response == null) {
            return List.of();
        }
        Map<String, Object> result = MiniJson.asObject(response.get("result"));
        if (result == null) {
            return List.of();
        }
        return parseWorkspaceEdits(result);
    }

    public List<CodeAction> codeActions(String uri, int line, int character, List<Diagnostic> diagnosticsAtCursor) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("line", line);
        start.put("character", character);
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("line", line);
        end.put("character", character + 1);
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", start);
        range.put("end", end);

        List<Map<String, Object>> diagnostics = new ArrayList<>();
        if (diagnosticsAtCursor != null) {
            for (Diagnostic diagnostic : diagnosticsAtCursor) {
                if (diagnostic == null) {
                    continue;
                }
                Map<String, Object> diagnosticRangeStart = new LinkedHashMap<>();
                diagnosticRangeStart.put("line", diagnostic.getLine());
                diagnosticRangeStart.put("character", diagnostic.getCharacter());
                Map<String, Object> diagnosticRangeEnd = new LinkedHashMap<>();
                diagnosticRangeEnd.put("line", diagnostic.getLine());
                diagnosticRangeEnd.put("character", diagnostic.getCharacter() + 1);
                Map<String, Object> diagnosticRange = new LinkedHashMap<>();
                diagnosticRange.put("start", diagnosticRangeStart);
                diagnosticRange.put("end", diagnosticRangeEnd);
                Map<String, Object> diagnosticEntry = new LinkedHashMap<>();
                diagnosticEntry.put("range", diagnosticRange);
                diagnosticEntry.put("severity", diagnostic.getSeverity());
                diagnosticEntry.put("message", diagnostic.getMessage());
                diagnostics.add(diagnosticEntry);
            }
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("diagnostics", diagnostics);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("range", range);
        params.put("context", context);

        Map<String, Object> response = sendRequest("textDocument/codeAction", params, 2500L);
        if (response == null) {
            return List.of();
        }
        List<Object> result = MiniJson.asArray(response.get("result"));
        if (result == null) {
            return List.of();
        }

        List<CodeAction> actions = new ArrayList<>();
        for (Object item : result) {
            Map<String, Object> actionObject = MiniJson.asObject(item);
            if (actionObject == null) {
                continue;
            }
            String title = MiniJson.asString(actionObject.get("title"));
            if (title == null || title.isBlank()) {
                continue;
            }
            String kind = MiniJson.asString(actionObject.get("kind"));
            boolean preferred = Boolean.TRUE.equals(actionObject.get("isPreferred"));
            List<TextEdit> edits = parseWorkspaceEdits(MiniJson.asObject(actionObject.get("edit")));
            String commandId = "";
            Object commandArguments = null;

            Map<String, Object> nestedCommand = MiniJson.asObject(actionObject.get("command"));
            if (nestedCommand != null) {
                String nestedCommandId = MiniJson.asString(nestedCommand.get("command"));
                if (nestedCommandId != null) {
                    commandId = nestedCommandId;
                    commandArguments = nestedCommand.get("arguments");
                }
            } else {
                String directCommandId = MiniJson.asString(actionObject.get("command"));
                if (directCommandId != null) {
                    commandId = directCommandId;
                    commandArguments = actionObject.get("arguments");
                }
            }

            actions.add(new CodeAction(title, kind, preferred, edits, commandId, commandArguments));
        }
        return actions;
    }

    public boolean executeCommand(String commandId, Object arguments) {
        if (commandId == null || commandId.isBlank()) {
            return false;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("command", commandId);
        if (arguments == null) {
            params.put("arguments", List.of());
        } else if (arguments instanceof List) {
            params.put("arguments", arguments);
        } else {
            params.put("arguments", List.of(arguments));
        }
        Map<String, Object> response = sendRequest("workspace/executeCommand", params, 4000L);
        return response != null && response.get("error") == null;
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
        if (uri == null) {
            uri = MiniJson.asString(location.get("targetUri"));
            if (range == null) {
                range = MiniJson.asObject(location.get("targetSelectionRange"));
                if (range == null) {
                    range = MiniJson.asObject(location.get("targetRange"));
                }
            }
        }
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

    private List<TextEdit> parseTextEdits(String uri, List<Object> editObjects) {
        if (uri == null || editObjects == null || editObjects.isEmpty()) {
            return List.of();
        }
        List<TextEdit> edits = new ArrayList<>();
        for (Object item : editObjects) {
            Map<String, Object> edit = MiniJson.asObject(item);
            if (edit == null) {
                continue;
            }
            Map<String, Object> range = MiniJson.asObject(edit.get("range"));
            Map<String, Object> start = range == null ? null : MiniJson.asObject(range.get("start"));
            Map<String, Object> end = range == null ? null : MiniJson.asObject(range.get("end"));
            Integer startLine = start == null ? null : MiniJson.asInt(start.get("line"));
            Integer startCharacter = start == null ? null : MiniJson.asInt(start.get("character"));
            Integer endLine = end == null ? null : MiniJson.asInt(end.get("line"));
            Integer endCharacter = end == null ? null : MiniJson.asInt(end.get("character"));
            if (startLine == null || startCharacter == null || endLine == null || endCharacter == null) {
                continue;
            }
            String newText = MiniJson.asString(edit.get("newText"));
            edits.add(new TextEdit(uri, startLine, startCharacter, endLine, endCharacter, newText));
        }
        return edits;
    }

    private List<TextEdit> parseWorkspaceEdits(Map<String, Object> workspaceEdit) {
        if (workspaceEdit == null) {
            return List.of();
        }
        List<TextEdit> edits = new ArrayList<>();

        Map<String, Object> changes = MiniJson.asObject(workspaceEdit.get("changes"));
        if (changes != null) {
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                List<Object> editArray = MiniJson.asArray(entry.getValue());
                if (editArray == null) {
                    continue;
                }
                edits.addAll(parseTextEdits(entry.getKey(), editArray));
            }
        }

        List<Object> documentChanges = MiniJson.asArray(workspaceEdit.get("documentChanges"));
        if (documentChanges != null) {
            for (Object item : documentChanges) {
                Map<String, Object> change = MiniJson.asObject(item);
                if (change == null) {
                    continue;
                }
                Map<String, Object> textDocumentObject = MiniJson.asObject(change.get("textDocument"));
                String changeUri = textDocumentObject == null ? null : MiniJson.asString(textDocumentObject.get("uri"));
                if (changeUri == null) {
                    continue;
                }
                List<Object> editArray = MiniJson.asArray(change.get("edits"));
                if (editArray == null) {
                    continue;
                }
                edits.addAll(parseTextEdits(changeUri, editArray));
            }
        }
        return edits;
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
