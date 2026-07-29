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
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class LspClient {
    public static class CompletionItem {
        private final String label;
        private final String detail;
        private final Integer kind;
        private final String documentation;
        private final String insertText;
        private final boolean snippet;
        private final List<CompletionTextEdit> textEdits;

        public CompletionItem(String label, String detail, Integer kind) {
            this(label, detail, kind, "");
        }

        public CompletionItem(String label, String detail, Integer kind, String documentation) {
            this(label, detail, kind, documentation, label, false, List.of());
        }

        public CompletionItem(String label, String detail, Integer kind, String documentation, String insertText,
                              boolean snippet, List<CompletionTextEdit> textEdits) {
            this.label = label;
            this.detail = detail == null ? "" : detail;
            this.kind = kind;
            this.documentation = documentation == null ? "" : documentation;
            this.insertText = insertText == null || insertText.isEmpty() ? label : insertText;
            this.snippet = snippet;
            this.textEdits = textEdits == null ? List.of() : List.copyOf(textEdits);
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

        public String getDocumentation() {
            return documentation;
        }

        public String getInsertText() {
            return insertText;
        }

        public boolean isSnippet() {
            return snippet;
        }

        public List<CompletionTextEdit> getTextEdits() {
            return textEdits;
        }

        @Override
        public String toString() {
            return detail.isBlank() ? label : label + " — " + detail;
        }
    }

    public static class CompletionTextEdit {
        private final int startLine;
        private final int startCharacter;
        private final int endLine;
        private final int endCharacter;
        private final String newText;

        public CompletionTextEdit(int startLine, int startCharacter, int endLine, int endCharacter, String newText) {
            this.startLine = startLine;
            this.startCharacter = startCharacter;
            this.endLine = endLine;
            this.endCharacter = endCharacter;
            this.newText = newText == null ? "" : newText;
        }

        public int getStartLine() { return startLine; }
        public int getStartCharacter() { return startCharacter; }
        public int getEndLine() { return endLine; }
        public int getEndCharacter() { return endCharacter; }
        public String getNewText() { return newText; }
    }

    public static class SignatureHelp {
        private final String label;
        private final String documentation;
        private final int activeParameter;

        public SignatureHelp(String label, String documentation, int activeParameter) {
            this.label = label == null ? "" : label;
            this.documentation = documentation == null ? "" : documentation;
            this.activeParameter = activeParameter;
        }

        public String getLabel() { return label; }
        public String getDocumentation() { return documentation; }
        public int getActiveParameter() { return activeParameter; }
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
        private final Integer documentVersion;

        public TextEdit(String uri, int startLine, int startCharacter, int endLine, int endCharacter, String newText) {
            this(uri, startLine, startCharacter, endLine, endCharacter, newText, null);
        }

        public TextEdit(String uri, int startLine, int startCharacter, int endLine, int endCharacter, String newText, Integer documentVersion) {
            this.uri = uri;
            this.startLine = startLine;
            this.startCharacter = startCharacter;
            this.endLine = endLine;
            this.endCharacter = endCharacter;
            this.newText = newText == null ? "" : newText;
            this.documentVersion = documentVersion;
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

        public Integer getDocumentVersion() {
            return documentVersion;
        }
    }

    public static class WorkspaceEditOperation {
        public enum Kind {
            TEXT_EDIT,
            CREATE,
            RENAME,
            DELETE
        }

        private final Kind kind;
        private final TextEdit textEdit;
        private final String uri;
        private final String oldUri;
        private final String newUri;
        private final boolean overwrite;
        private final boolean ignoreIfExists;
        private final boolean recursive;
        private final boolean ignoreIfNotExists;

        private WorkspaceEditOperation(Kind kind, TextEdit textEdit, String uri, String oldUri, String newUri, boolean overwrite, boolean ignoreIfExists, boolean recursive, boolean ignoreIfNotExists) {
            this.kind = kind;
            this.textEdit = textEdit;
            this.uri = uri;
            this.oldUri = oldUri;
            this.newUri = newUri;
            this.overwrite = overwrite;
            this.ignoreIfExists = ignoreIfExists;
            this.recursive = recursive;
            this.ignoreIfNotExists = ignoreIfNotExists;
        }

        public static WorkspaceEditOperation textEdit(TextEdit edit) {
            return new WorkspaceEditOperation(Kind.TEXT_EDIT, edit, edit == null ? null : edit.getUri(), null, null, false, false, false, false);
        }

        public static WorkspaceEditOperation create(String uri, boolean overwrite, boolean ignoreIfExists) {
            return new WorkspaceEditOperation(Kind.CREATE, null, uri, null, null, overwrite, ignoreIfExists, false, false);
        }

        public static WorkspaceEditOperation rename(String oldUri, String newUri, boolean overwrite, boolean ignoreIfExists) {
            return new WorkspaceEditOperation(Kind.RENAME, null, null, oldUri, newUri, overwrite, ignoreIfExists, false, false);
        }

        public static WorkspaceEditOperation delete(String uri, boolean recursive, boolean ignoreIfNotExists) {
            return new WorkspaceEditOperation(Kind.DELETE, null, uri, null, null, false, false, recursive, ignoreIfNotExists);
        }

        public Kind getKind() {
            return kind;
        }

        public TextEdit getTextEdit() {
            return textEdit;
        }

        public String getUri() {
            return uri;
        }

        public String getOldUri() {
            return oldUri;
        }

        public String getNewUri() {
            return newUri;
        }

        public boolean isOverwrite() {
            return overwrite;
        }

        public boolean isIgnoreIfExists() {
            return ignoreIfExists;
        }

        public boolean isRecursive() {
            return recursive;
        }

        public boolean isIgnoreIfNotExists() {
            return ignoreIfNotExists;
        }
    }

    public interface WorkspaceEditHandler {
        WorkspaceEditResponse applyWorkspaceEdit(String label, List<WorkspaceEditOperation> operations);
    }

    public static class WorkspaceEditResponse {
        private final boolean applied;
        private final String failureReason;

        public WorkspaceEditResponse(boolean applied, String failureReason) {
            this.applied = applied;
            this.failureReason = failureReason == null ? "" : failureReason;
        }

        public boolean isApplied() {
            return applied;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }

    public static class CodeAction {
        private final String title;
        private final String kind;
        private final boolean preferred;
        private final List<WorkspaceEditOperation> operations;
        private final String commandId;
        private final Object commandArguments;

        public CodeAction(String title, String kind, boolean preferred, List<WorkspaceEditOperation> operations, String commandId, Object commandArguments) {
            this.title = title == null ? "" : title;
            this.kind = kind == null ? "" : kind;
            this.preferred = preferred;
            this.operations = operations == null ? List.of() : new ArrayList<>(operations);
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
            List<TextEdit> edits = new ArrayList<>();
            for (WorkspaceEditOperation operation : operations) {
                if (operation != null && operation.getKind() == WorkspaceEditOperation.Kind.TEXT_EDIT && operation.getTextEdit() != null) {
                    edits.add(operation.getTextEdit());
                }
            }
            return new ArrayList<>(edits);
        }

        public List<WorkspaceEditOperation> getOperations() {
            return new ArrayList<>(operations);
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
    private final Set<Integer> staleRequestIds;
    private final LspFeatureSettings featureSettings;
    private WorkspaceEditHandler workspaceEditHandler;
    private int requestId;
    private boolean initialized;
    private LspCapabilityModel capabilityModel;

    public LspClient(String command, String[] args, Path rootPath) throws IOException {
        this(command, args, rootPath, LspFeatureSettings.defaults());
    }

    LspClient(String command, String[] args, Path rootPath, LspFeatureSettings featureSettings) throws IOException {
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
        this.staleRequestIds = ConcurrentHashMap.newKeySet();
        this.featureSettings = featureSettings == null ? LspFeatureSettings.defaults() : featureSettings;
        this.requestId = 0;
        this.initialized = false;
        this.capabilityModel = LspCapabilityModel.uninitialized();
        startReaderThread();
        initialize(rootPath);
    }

    public void setWorkspaceEditHandler(WorkspaceEditHandler workspaceEditHandler) {
        this.workspaceEditHandler = workspaceEditHandler;
    }

    public boolean isAlive() {
        return initialized && process.isAlive();
    }

    public boolean supports(LspCapability capability) {
        return capabilityModel.allows(capability);
    }

    public String capabilityUnavailableReason(LspCapability capability) {
        return capabilityModel.unavailableReason(capability);
    }

    public LspCapabilityModel.Availability capabilityAvailability(LspCapability capability) {
        return capabilityModel.availability(capability);
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
        if (!supports(LspCapability.COMPLETION)) {
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

        Map<String, Object> response = sendRequest("textDocument/completion", params, 2000L);
        if (response == null) {
            return List.of();
        }

        return parseCompletionItems(response.get("result"));
    }

    static List<CompletionItem> parseCompletionItems(Object result) {
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
            String insertText = MiniJson.asString(itemObject.get("insertText"));
            Integer insertTextFormat = MiniJson.asInt(itemObject.get("insertTextFormat"));
            List<CompletionTextEdit> textEdits = completionTextEdits(itemObject);
            completions.add(new CompletionItem(label, detail, kind, completionDocumentation(itemObject.get("documentation")),
                insertText == null ? label : insertText, Integer.valueOf(2).equals(insertTextFormat), textEdits));
        }
        return completions;
    }

    private static String completionDocumentation(Object documentation) {
        String text = MiniJson.asString(documentation);
        if (text != null) {
            return text;
        }
        Map<String, Object> markup = MiniJson.asObject(documentation);
        String value = MiniJson.asString(markup == null ? null : markup.get("value"));
        return value == null ? "" : value;
    }

    private static List<CompletionTextEdit> completionTextEdits(Map<String, Object> item) {
        List<CompletionTextEdit> edits = new ArrayList<>();
        CompletionTextEdit primary = completionTextEdit(item == null ? null : MiniJson.asObject(item.get("textEdit")));
        if (primary != null) edits.add(primary);
        List<Object> additional = MiniJson.asArray(item == null ? null : item.get("additionalTextEdits"));
        if (additional != null) {
            for (Object value : additional) {
                CompletionTextEdit edit = completionTextEdit(MiniJson.asObject(value));
                if (edit != null) edits.add(edit);
            }
        }
        return edits;
    }

    private static CompletionTextEdit completionTextEdit(Map<String, Object> edit) {
        if (edit == null) return null;
        Map<String, Object> range = MiniJson.asObject(edit.get("range"));
        if (range == null) range = MiniJson.asObject(edit.get("replace"));
        Map<String, Object> start = range == null ? null : MiniJson.asObject(range.get("start"));
        Map<String, Object> end = range == null ? null : MiniJson.asObject(range.get("end"));
        Integer startLine = start == null ? null : MiniJson.asInt(start.get("line"));
        Integer startCharacter = start == null ? null : MiniJson.asInt(start.get("character"));
        Integer endLine = end == null ? null : MiniJson.asInt(end.get("line"));
        Integer endCharacter = end == null ? null : MiniJson.asInt(end.get("character"));
        String newText = MiniJson.asString(edit.get("newText"));
        if (startLine == null || startCharacter == null || endLine == null || endCharacter == null || newText == null) return null;
        return new CompletionTextEdit(startLine, startCharacter, endLine, endCharacter, newText);
    }

    public String hover(String uri, int line, int character) {
        if (!supports(LspCapability.HOVER)) {
            return null;
        }
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

    public SignatureHelp signatureHelp(String uri, int line, int character) {
        if (!supports(LspCapability.SIGNATURE_HELP)) return null;
        Map<String, Object> response = sendTextDocumentPositionRequest("textDocument/signatureHelp", uri, line, character, 2000L);
        return parseSignatureHelp(response == null ? null : response.get("result"));
    }

    static SignatureHelp parseSignatureHelp(Object value) {
        Map<String, Object> result = MiniJson.asObject(value);
        List<Object> signatures = result == null ? null : MiniJson.asArray(result.get("signatures"));
        if (signatures == null || signatures.isEmpty()) return null;
        Integer activeSignature = MiniJson.asInt(result.get("activeSignature"));
        int index = activeSignature == null ? 0 : Math.max(0, Math.min(activeSignature, signatures.size() - 1));
        Map<String, Object> signature = MiniJson.asObject(signatures.get(index));
        String label = MiniJson.asString(signature == null ? null : signature.get("label"));
        if (label == null || label.isBlank()) return null;
        Integer activeParameter = MiniJson.asInt(result.get("activeParameter"));
        return new SignatureHelp(label, completionDocumentation(signature.get("documentation")), activeParameter == null ? -1 : activeParameter);
    }

    public Location definition(String uri, int line, int character) {
        if (!supports(LspCapability.DEFINITION)) {
            return null;
        }
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
        if (!supports(LspCapability.REFERENCES)) {
            return List.of();
        }
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
        if (!supports(LspCapability.RENAME) || newName == null || newName.isBlank()) {
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
        if (!supports(LspCapability.CODE_ACTION)) {
            return List.of();
        }
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
            List<WorkspaceEditOperation> operations = parseWorkspaceOperations(MiniJson.asObject(actionObject.get("edit")));
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

            actions.add(new CodeAction(title, kind, preferred, operations, commandId, commandArguments));
        }
        return actions;
    }

    public boolean executeCommand(String commandId, Object arguments) {
        if (!supports(LspCapability.EXECUTE_COMMAND) || commandId == null || commandId.isBlank()) {
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
                handleIncomingMethod(message);
            } else {
                Integer responseId = MiniJson.asInt(message.get("id"));
                if (responseId != null && staleRequestIds.remove(responseId)) {
                    continue;
                }
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
        completionItem.put("snippetSupport", featureSettings.snippets());
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("completionItem", completionItem);
        Map<String, Object> hover = new LinkedHashMap<>();
        hover.put("contentFormat", List.of("plaintext"));
        Map<String, Object> diagnosticsCapability = new LinkedHashMap<>();
        diagnosticsCapability.put("relatedInformation", Boolean.FALSE);
        Map<String, Object> changeAnnotationSupport = new LinkedHashMap<>();
        changeAnnotationSupport.put("groupsOnLabel", Boolean.TRUE);
        Map<String, Object> workspaceEdit = new LinkedHashMap<>();
        workspaceEdit.put("documentChanges", Boolean.TRUE);
        workspaceEdit.put("resourceOperations", List.of("create", "rename", "delete"));
        workspaceEdit.put("changeAnnotationSupport", changeAnnotationSupport);
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("applyEdit", Boolean.TRUE);
        workspace.put("workspaceEdit", workspaceEdit);

        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("completion", completion);
        textDocument.put("hover", hover);
        textDocument.put("definition", new LinkedHashMap<>());
        textDocument.put("publishDiagnostics", diagnosticsCapability);
        capabilities.put("textDocument", textDocument);
        capabilities.put("workspace", workspace);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", rootPath.toAbsolutePath().toUri().toString());
        params.put("capabilities", capabilities);

        Map<String, Object> response = sendRequest("initialize", params, 5000L);
        if (response == null) {
            throw new IOException("LSP initialize timed out");
        }
        capabilityModel = LspCapabilityModel.fromInitializeResult(response, featureSettings.capabilityEnablement());
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

    private synchronized Map<String, Object> sendRequest(String method, Object params, long timeoutMs) {
        int id = nextRequestId();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        writeMessage(request);
        Map<String, Object> response = waitForResponse(id, timeoutMs);
        if (response == null) {
            markRequestStale(id);
            sendCancelRequest(id);
        }
        return response;
    }

    private void sendNotification(String method, Object params) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        writeMessage(notification);
    }

    private void sendCancelRequest(int id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        sendNotification("$/cancelRequest", params);
    }

    private void markRequestStale(int id) {
        staleRequestIds.add(id);
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
                staleRequestIds.remove(id);
                return message;
            }
            if (responseId != null && staleRequestIds.remove(responseId)) {
                continue;
            }
            if (message.containsKey("method")) {
                handleIncomingMethod(message);
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
                if (responseId != null && staleRequestIds.remove(responseId)) {
                    deferredMessages.remove(i);
                    i--;
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

    private void handleIncomingMethod(Map<String, Object> message) {
        if (message == null) {
            return;
        }
        if (message.containsKey("id")) {
            handleServerRequest(message);
            return;
        }
        handleNotification(message);
    }

    private void handleServerRequest(Map<String, Object> message) {
        Object id = message.get("id");
        String method = MiniJson.asString(message.get("method"));
        if ("workspace/applyEdit".equals(method)) {
            handleWorkspaceApplyEditRequest(id, MiniJson.asObject(message.get("params")));
            return;
        }
        sendErrorResponse(id, -32601, "Method not found");
    }

    private void handleWorkspaceApplyEditRequest(Object id, Map<String, Object> params) {
        String label = params == null ? null : MiniJson.asString(params.get("label"));
        Map<String, Object> edit = params == null ? null : MiniJson.asObject(params.get("edit"));
        List<WorkspaceEditOperation> operations = parseWorkspaceOperations(edit);
        WorkspaceEditResponse applied = workspaceEditHandler == null
            ? new WorkspaceEditResponse(false, "workspace/applyEdit handler unavailable")
            : workspaceEditHandler.applyWorkspaceEdit(label, operations);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", applied != null && applied.isApplied());
        if (applied != null && !applied.isApplied() && !applied.getFailureReason().isBlank()) {
            result.put("failureReason", applied.getFailureReason());
        }
        sendResponse(id, result);
    }

    private void sendResponse(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        writeMessage(response);
    }

    private void sendErrorResponse(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        writeMessage(response);
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

    static List<TextEdit> parseTextEdits(String uri, List<Object> editObjects, Integer documentVersion) {
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
            edits.add(new TextEdit(uri, startLine, startCharacter, endLine, endCharacter, newText, documentVersion));
        }
        return edits;
    }

    static List<TextEdit> parseWorkspaceEdits(Map<String, Object> workspaceEdit) {
        List<WorkspaceEditOperation> operations = parseWorkspaceOperations(workspaceEdit);
        List<TextEdit> edits = new ArrayList<>();
        for (WorkspaceEditOperation operation : operations) {
            if (operation != null && operation.getKind() == WorkspaceEditOperation.Kind.TEXT_EDIT && operation.getTextEdit() != null) {
                edits.add(operation.getTextEdit());
            }
        }
        return edits;
    }

    static List<WorkspaceEditOperation> parseWorkspaceOperations(Map<String, Object> workspaceEdit) {
        if (workspaceEdit == null) {
            return List.of();
        }
        List<WorkspaceEditOperation> operations = new ArrayList<>();

        List<Object> documentChanges = MiniJson.asArray(workspaceEdit.get("documentChanges"));
        if (documentChanges != null) {
            for (Object item : documentChanges) {
                Map<String, Object> change = MiniJson.asObject(item);
                if (change == null) {
                    continue;
                }
                String resourceKind = MiniJson.asString(change.get("kind"));
                if (resourceKind != null) {
                    WorkspaceEditOperation operation = parseResourceOperation(resourceKind, change);
                    if (operation != null) {
                        operations.add(operation);
                    }
                    continue;
                }
                Map<String, Object> textDocumentObject = MiniJson.asObject(change.get("textDocument"));
                String changeUri = textDocumentObject == null ? null : MiniJson.asString(textDocumentObject.get("uri"));
                if (changeUri == null) {
                    continue;
                }
                Integer version = MiniJson.asInt(textDocumentObject.get("version"));
                List<Object> editArray = MiniJson.asArray(change.get("edits"));
                if (editArray == null) {
                    continue;
                }
                operations.addAll(textEditOperations(parseTextEdits(changeUri, editArray, version)));
            }
            return operations;
        }

        Map<String, Object> changes = MiniJson.asObject(workspaceEdit.get("changes"));
        if (changes != null) {
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                List<Object> editArray = MiniJson.asArray(entry.getValue());
                if (editArray == null) {
                    continue;
                }
                operations.addAll(textEditOperations(parseTextEdits(entry.getKey(), editArray, null)));
            }
        }
        return operations;
    }

    private static List<WorkspaceEditOperation> textEditOperations(List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return List.of();
        }
        List<WorkspaceEditOperation> operations = new ArrayList<>();
        for (TextEdit edit : edits) {
            if (edit != null) {
                operations.add(WorkspaceEditOperation.textEdit(edit));
            }
        }
        return operations;
    }

    private static WorkspaceEditOperation parseResourceOperation(String kind, Map<String, Object> change) {
        Map<String, Object> options = MiniJson.asObject(change.get("options"));
        boolean overwrite = options != null && Boolean.TRUE.equals(options.get("overwrite"));
        boolean ignoreIfExists = options != null && Boolean.TRUE.equals(options.get("ignoreIfExists"));
        boolean recursive = options != null && Boolean.TRUE.equals(options.get("recursive"));
        boolean ignoreIfNotExists = options != null && Boolean.TRUE.equals(options.get("ignoreIfNotExists"));
        switch (kind) {
            case "create":
                return WorkspaceEditOperation.create(MiniJson.asString(change.get("uri")), overwrite, ignoreIfExists);
            case "rename":
                return WorkspaceEditOperation.rename(MiniJson.asString(change.get("oldUri")), MiniJson.asString(change.get("newUri")), overwrite, ignoreIfExists);
            case "delete":
                return WorkspaceEditOperation.delete(MiniJson.asString(change.get("uri")), recursive, ignoreIfNotExists);
            default:
                return null;
        }
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
