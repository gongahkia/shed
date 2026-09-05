package shed;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class LspClient {
    private static final List<String> STANDARD_SEMANTIC_TOKEN_TYPES = List.of(
        "namespace", "type", "class", "enum", "interface", "struct", "typeParameter", "parameter", "variable",
        "property", "enumMember", "event", "function", "method", "macro", "keyword", "modifier", "comment",
        "string", "number", "regexp", "operator", "decorator"
    );
    private static final List<String> STANDARD_SEMANTIC_TOKEN_MODIFIERS = List.of(
        "declaration", "definition", "readonly", "static", "deprecated", "abstract", "async", "modification",
        "documentation", "defaultLibrary"
    );

    enum DocumentSyncKind {
        NONE,
        FULL,
        INCREMENTAL
    }

    enum CompletionTriggerKind {
        INVOKED(1),
        TRIGGER_CHARACTER(2);

        private final int value;

        CompletionTriggerKind(int value) { this.value = value; }
    }

    public static class CompletionItem {
        private final String label;
        private final String detail;
        private final Integer kind;
        private final String documentation;
        private final String insertText;
        private final boolean snippet;
        private final List<CompletionTextEdit> textEdits;
        private final String filterText;
        private final String sortText;
        private final boolean preselect;
        private final List<String> commitCharacters;
        private final Map<String, Object> resolvePayload;

        public CompletionItem(String label, String detail, Integer kind) {
            this(label, detail, kind, "");
        }

        public CompletionItem(String label, String detail, Integer kind, String documentation) {
            this(label, detail, kind, documentation, label, false, List.of());
        }

        public CompletionItem(String label, String detail, Integer kind, String documentation, String insertText,
                              boolean snippet, List<CompletionTextEdit> textEdits) {
            this(label, detail, kind, documentation, insertText, snippet, textEdits, label, label, false, List.of(), Map.of());
        }

        private CompletionItem(String label, String detail, Integer kind, String documentation, String insertText,
                               boolean snippet, List<CompletionTextEdit> textEdits, String filterText, String sortText,
                               boolean preselect, List<String> commitCharacters, Map<String, Object> resolvePayload) {
            this.label = label;
            this.detail = detail == null ? "" : detail;
            this.kind = kind;
            this.documentation = documentation == null ? "" : documentation;
            this.insertText = insertText == null || insertText.isEmpty() ? label : insertText;
            this.snippet = snippet;
            this.textEdits = textEdits == null ? List.of() : List.copyOf(textEdits);
            this.filterText = filterText == null ? "" : filterText;
            this.sortText = sortText == null ? "" : sortText;
            this.preselect = preselect;
            this.commitCharacters = commitCharacters == null ? List.of() : List.copyOf(commitCharacters);
            this.resolvePayload = resolvePayload == null || resolvePayload.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(resolvePayload));
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

        public String getFilterText() { return filterText; }

        public String getSortText() { return sortText; }

        public boolean isPreselect() { return preselect; }

        public List<String> getCommitCharacters() { return commitCharacters; }

        Map<String, Object> getResolvePayload() { return resolvePayload; }

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

    public record SemanticToken(int line, int character, int length, int type) {
    }

    public record InlayHint(int line, int character, String label) {
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

    public static class HierarchyItem {
        private final String name;
        private final String detail;
        private final int kind;
        private final String uri;
        private final int line;
        private final int character;
        private final Map<String, Object> payload;

        private HierarchyItem(String name, String detail, int kind, String uri, int line, int character, Map<String, Object> payload) {
            this.name = name == null ? "" : name;
            this.detail = detail == null ? "" : detail;
            this.kind = Math.max(0, kind);
            this.uri = uri == null ? "" : uri;
            this.line = Math.max(0, line);
            this.character = Math.max(0, character);
            this.payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }

        public String getName() { return name; }
        public String getDetail() { return detail; }
        public int getKind() { return kind; }
        public String getUri() { return uri; }
        public int getLine() { return line; }
        public int getCharacter() { return character; }
        Map<String, Object> requestPayload() { return payload; }
        @Override public String toString() { return detail.isBlank() ? name : name + " — " + detail; }
    }

    public record CallHierarchyCall(HierarchyItem item) { }

    public static class Diagnostic {
        private final int line;
        private final int character;
        private final int endLine;
        private final int endCharacter;
        private final int severity;
        private final String message;

        public Diagnostic(int line, int character, int severity, String message) {
            this(line, character, line, character + 1, severity, message);
        }

        public Diagnostic(int line, int character, int endLine, int endCharacter, int severity, String message) {
            this.line = line;
            this.character = character;
            this.endLine = Math.max(line, endLine);
            this.endCharacter = Math.max(0, endCharacter);
            this.severity = severity;
            this.message = message;
        }

        public int getLine() {
            return line;
        }

        public int getCharacter() {
            return character;
        }

        public int getEndLine() { return endLine; }

        public int getEndCharacter() { return endCharacter; }

        public int getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class NavigationSymbol {
        private final String name;
        private final String detail;
        private final int kind;
        private final String uri;
        private final int line;
        private final int character;
        private final int level;

        public NavigationSymbol(String name, String detail, int kind, String uri, int line, int character, int level) {
            this.name = name == null ? "" : name;
            this.detail = detail == null ? "" : detail;
            this.kind = kind;
            this.uri = uri == null ? "" : uri;
            this.line = Math.max(0, line);
            this.character = Math.max(0, character);
            this.level = Math.max(1, level);
        }

        public String getName() { return name; }
        public String getDetail() { return detail; }
        public int getKind() { return kind; }
        public String getUri() { return uri; }
        public int getLine() { return line; }
        public int getCharacter() { return character; }
        public int getLevel() { return level; }
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
    private Runnable diagnosticsChangedHandler;
    private int requestId;
    private boolean initialized;
    private LspCapabilityModel capabilityModel;
    private DocumentSyncKind documentSyncKind;
    private List<String> semanticTokenTypes;
    private Set<String> completionTriggerCharacters;
    private boolean completionResolveSupported;

    public LspClient(String command, String[] args, Path rootPath) throws IOException {
        this(command, args, rootPath, LspFeatureSettings.defaults());
    }

    LspClient(String command, String[] args, Path rootPath, LspFeatureSettings featureSettings) throws IOException {
        this(commandLine(command, args), rootPath, localRootUri(rootPath), featureSettings);
    }

    LspClient(List<String> commandLine, Path rootPath, String rootUri, LspFeatureSettings featureSettings) throws IOException {
        if (rootPath == null || rootUri == null || rootUri.isBlank()) throw new IOException("LSP workspace root is required");
        List<String> invocation = commandLine == null ? List.of() : List.copyOf(commandLine);
        if (invocation.isEmpty() || invocation.getFirst() == null || invocation.getFirst().isBlank()) throw new IOException("LSP command is required");
        for (String argument : invocation) {
            if (argument == null || argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new IOException("LSP command contains an invalid argument");
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(invocation);
        processBuilder.directory(rootPath.toFile());
        this.process = processBuilder.start();
        this.stdin = new BufferedOutputStream(process.getOutputStream());
        this.messageQueue = new LinkedBlockingQueue<>();
        this.deferredMessages = new ArrayList<>();
        this.diagnostics = new ConcurrentHashMap<>();
        this.staleRequestIds = ConcurrentHashMap.newKeySet();
        this.featureSettings = featureSettings == null ? LspFeatureSettings.defaults() : featureSettings;
        this.requestId = 0;
        this.initialized = false;
        this.capabilityModel = LspCapabilityModel.uninitialized();
        this.documentSyncKind = DocumentSyncKind.FULL;
        this.semanticTokenTypes = List.of();
        this.completionTriggerCharacters = Set.of();
        this.completionResolveSupported = false;
        startReaderThread();
        initialize(rootUri);
    }

    private static List<String> commandLine(String command, String[] args) {
        List<String> commandLine = new ArrayList<>();
        if (command != null && !command.isBlank()) commandLine.add(command);
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) {
                    commandLine.add(arg);
                }
            }
        }
        return List.copyOf(commandLine);
    }

    private static String localRootUri(Path rootPath) throws IOException {
        if (rootPath == null) throw new IOException("LSP workspace root is required");
        return rootPath.toAbsolutePath().normalize().toUri().toString();
    }

    public void setWorkspaceEditHandler(WorkspaceEditHandler workspaceEditHandler) {
        this.workspaceEditHandler = workspaceEditHandler;
    }

    public void setDiagnosticsChangedHandler(Runnable diagnosticsChangedHandler) {
        this.diagnosticsChangedHandler = diagnosticsChangedHandler;
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
        didChange(uri, version, List.of(), text);
    }

    public void didChange(String uri, int version, List<LspDocumentChange> changes, String fullText) {
        if (documentSyncKind == DocumentSyncKind.NONE) {
            return;
        }
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        textDocument.put("version", version);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        if (documentSyncKind == DocumentSyncKind.INCREMENTAL && changes != null && !changes.isEmpty()) {
            List<Map<String, Object>> contentChanges = new ArrayList<>();
            for (LspDocumentChange change : changes) {
                Map<String, Object> range = new LinkedHashMap<>();
                range.put("start", Map.of("line", change.startLine(), "character", change.startCharacter()));
                range.put("end", Map.of("line", change.endLine(), "character", change.endCharacter()));
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("range", range);
                content.put("text", change.text());
                contentChanges.add(content);
            }
            params.put("contentChanges", contentChanges);
        } else {
            params.put("contentChanges", List.of(Map.of("text", fullText == null ? "" : fullText)));
        }
        sendNotification("textDocument/didChange", params);
    }

    DocumentSyncKind documentSyncKind() {
        return documentSyncKind;
    }

    String semanticTokenTypeName(int type) {
        return type >= 0 && type < semanticTokenTypes.size() ? semanticTokenTypes.get(type) : "";
    }

    public void didSave(String uri) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        sendNotification("textDocument/didSave", params);
    }

    public List<CompletionItem> completion(String uri, int line, int character) {
        return completion(uri, line, character, CompletionTriggerKind.INVOKED, null);
    }

    public List<CompletionItem> completion(String uri, int line, int character, CompletionTriggerKind triggerKind,
                                           Character triggerCharacter) {
        if (!supports(LspCapability.COMPLETION)) {
            return List.of();
        }
        Map<String, Object> params = completionParameters(uri, line, character, triggerKind, triggerCharacter);
        Map<String, Object> response = sendRequest("textDocument/completion", params, 2000L);
        if (response == null) {
            return List.of();
        }

        return parseCompletionItems(response.get("result"));
    }

    static Map<String, Object> completionParameters(String uri, int line, int character, CompletionTriggerKind triggerKind,
                                                      Character triggerCharacter) {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);

        Map<String, Object> position = new LinkedHashMap<>();
        position.put("line", line);
        position.put("character", character);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("position", position);
        Map<String, Object> context = new LinkedHashMap<>();
        CompletionTriggerKind resolvedKind = triggerKind == null ? CompletionTriggerKind.INVOKED : triggerKind;
        context.put("triggerKind", resolvedKind.value);
        if (resolvedKind == CompletionTriggerKind.TRIGGER_CHARACTER && triggerCharacter != null) {
            context.put("triggerCharacter", String.valueOf(triggerCharacter));
        }
        params.put("context", context);
        return params;
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
            CompletionItem completion = parseCompletionItem(itemObject);
            if (completion != null) completions.add(completion);
        }
        return completions;
    }

    private static CompletionItem parseCompletionItem(Map<String, Object> itemObject) {
        if (itemObject == null) return null;
        String label = MiniJson.asString(itemObject.get("label"));
        if (label == null || label.isEmpty()) return null;
        String detail = completionDetail(itemObject);
        Integer kind = MiniJson.asInt(itemObject.get("kind"));
        String insertText = MiniJson.asString(itemObject.get("insertText"));
        Integer insertTextFormat = MiniJson.asInt(itemObject.get("insertTextFormat"));
        String filterText = MiniJson.asString(itemObject.get("filterText"));
        String sortText = MiniJson.asString(itemObject.get("sortText"));
        boolean preselect = Boolean.TRUE.equals(itemObject.get("preselect"));
        return new CompletionItem(label, detail, kind, completionDocumentation(itemObject.get("documentation")),
            insertText == null ? label : insertText, Integer.valueOf(2).equals(insertTextFormat), completionTextEdits(itemObject),
            filterText == null ? label : filterText, sortText == null ? "" : sortText, preselect,
            completionStringList(itemObject.get("commitCharacters")), itemObject);
    }

    private static String completionDetail(Map<String, Object> itemObject) {
        String detail = MiniJson.asString(itemObject.get("detail"));
        Map<String, Object> labelDetails = MiniJson.asObject(itemObject.get("labelDetails"));
        String labelDetail = MiniJson.asString(labelDetails == null ? null : labelDetails.get("detail"));
        String description = MiniJson.asString(labelDetails == null ? null : labelDetails.get("description"));
        if (detail == null || detail.isBlank()) detail = labelDetail;
        if (description == null || description.isBlank()) return detail;
        if (detail == null || detail.isBlank()) return description;
        return detail.contains(description) ? detail : detail + " — " + description;
    }

    private static List<String> completionStringList(Object value) {
        List<Object> values = MiniJson.asArray(value);
        if (values == null || values.isEmpty()) return List.of();
        List<String> parsed = new ArrayList<>();
        for (Object candidate : values) {
            String text = MiniJson.asString(candidate);
            if (text != null && !text.isEmpty()) parsed.add(text);
        }
        return List.copyOf(parsed);
    }

    public boolean isCompletionTriggerCharacter(char character) {
        return completionTriggerCharacters.contains(String.valueOf(character));
    }

    public boolean supportsCompletionResolve() { return completionResolveSupported; }

    public CompletionItem resolveCompletionItem(CompletionItem item) {
        if (item == null || !completionResolveSupported || item.getResolvePayload().isEmpty()) return item;
        Map<String, Object> response = sendRequest("completionItem/resolve", item.getResolvePayload(), 2000L);
        if (response == null) return item;
        CompletionItem resolved = parseCompletionItem(MiniJson.asObject(response.get("result")));
        return resolved == null ? item : resolved;
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

    public List<SemanticToken> semanticTokens(String uri) {
        if (!supports(LspCapability.SEMANTIC_TOKENS)) return List.of();
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        Map<String, Object> response = sendRequest("textDocument/semanticTokens/full", params, 2500L);
        return parseSemanticTokens(response == null ? null : response.get("result"));
    }

    static List<SemanticToken> parseSemanticTokens(Object value) {
        Map<String, Object> result = MiniJson.asObject(value);
        List<Object> data = MiniJson.asArray(result == null ? null : result.get("data"));
        if (data == null || data.size() % 5 != 0) return List.of();
        List<SemanticToken> tokens = new ArrayList<>();
        int line = 0;
        int character = 0;
        for (int i = 0; i < data.size(); i += 5) {
            Integer lineDelta = MiniJson.asInt(data.get(i));
            Integer characterDelta = MiniJson.asInt(data.get(i + 1));
            Integer length = MiniJson.asInt(data.get(i + 2));
            Integer type = MiniJson.asInt(data.get(i + 3));
            if (lineDelta == null || characterDelta == null || length == null || type == null || lineDelta < 0 || characterDelta < 0 || length < 0) return List.of();
            line += lineDelta;
            character = lineDelta == 0 ? character + characterDelta : characterDelta;
            tokens.add(new SemanticToken(line, character, length, type));
        }
        return tokens;
    }

    public List<InlayHint> inlayHints(String uri, int endLine, int endCharacter) {
        if (!supports(LspCapability.INLAY_HINTS)) return List.of();
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("line", Math.max(0, endLine));
        end.put("character", Math.max(0, endCharacter));
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", Map.of("line", 0, "character", 0));
        range.put("end", end);
        Map<String, Object> response = sendRequest("textDocument/inlayHint", Map.of("textDocument", textDocument, "range", range), 2500L);
        return parseInlayHints(response == null ? null : response.get("result"));
    }

    static List<InlayHint> parseInlayHints(Object value) {
        List<Object> values = MiniJson.asArray(value);
        if (values == null) return List.of();
        List<InlayHint> hints = new ArrayList<>();
        for (Object valueItem : values) {
            Map<String, Object> hint = MiniJson.asObject(valueItem);
            Map<String, Object> position = hint == null ? null : MiniJson.asObject(hint.get("position"));
            Integer line = position == null ? null : MiniJson.asInt(position.get("line"));
            Integer character = position == null ? null : MiniJson.asInt(position.get("character"));
            String label = MiniJson.asString(hint == null ? null : hint.get("label"));
            if (label == null) {
                List<Object> parts = MiniJson.asArray(hint == null ? null : hint.get("label"));
                StringBuilder joined = new StringBuilder();
                if (parts != null) for (Object part : parts) joined.append(MiniJson.asString(MiniJson.asObject(part) == null ? part : MiniJson.asObject(part).get("value")));
                label = joined.toString();
            }
            if (line != null && character != null && label != null && !label.isBlank()) hints.add(new InlayHint(line, character, label));
        }
        return hints;
    }

    public Location definition(String uri, int line, int character) {
        if (!supports(LspCapability.DEFINITION)) {
            return null;
        }
        return textDocumentLocation("textDocument/definition", uri, line, character);
    }

    public Location typeDefinition(String uri, int line, int character) {
        if (!supports(LspCapability.TYPE_DEFINITION)) return null;
        return textDocumentLocation("textDocument/typeDefinition", uri, line, character);
    }

    public List<Location> implementations(String uri, int line, int character) {
        if (!supports(LspCapability.IMPLEMENTATION)) return List.of();
        return textDocumentLocations("textDocument/implementation", uri, line, character, 2500L);
    }

    public List<HierarchyItem> prepareCallHierarchy(String uri, int line, int character) {
        if (!supports(LspCapability.CALL_HIERARCHY)) return List.of();
        Map<String, Object> response = sendTextDocumentPositionRequest("textDocument/prepareCallHierarchy", uri, line, character, 2500L);
        return parseHierarchyItems(response == null ? null : response.get("result"));
    }

    public List<CallHierarchyCall> incomingCalls(HierarchyItem item) {
        return callHierarchyCalls("callHierarchy/incomingCalls", "from", item);
    }

    public List<CallHierarchyCall> outgoingCalls(HierarchyItem item) {
        return callHierarchyCalls("callHierarchy/outgoingCalls", "to", item);
    }

    public List<HierarchyItem> prepareTypeHierarchy(String uri, int line, int character) {
        if (!supports(LspCapability.TYPE_HIERARCHY)) return List.of();
        Map<String, Object> response = sendTextDocumentPositionRequest("textDocument/prepareTypeHierarchy", uri, line, character, 2500L);
        return parseHierarchyItems(response == null ? null : response.get("result"));
    }

    public List<HierarchyItem> typeSupertypes(HierarchyItem item) {
        return typeHierarchyItems("typeHierarchy/supertypes", item);
    }

    public List<HierarchyItem> typeSubtypes(HierarchyItem item) {
        return typeHierarchyItems("typeHierarchy/subtypes", item);
    }

    private List<CallHierarchyCall> callHierarchyCalls(String method, String field, HierarchyItem item) {
        if (!supports(LspCapability.CALL_HIERARCHY) || item == null) return List.of();
        Map<String, Object> response = sendRequest(method, Map.of("item", item.requestPayload()), 3000L);
        List<Object> values = MiniJson.asArray(response == null ? null : response.get("result"));
        if (values == null) return List.of();
        List<CallHierarchyCall> calls = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> call = MiniJson.asObject(value);
            HierarchyItem target = parseHierarchyItem(call == null ? null : MiniJson.asObject(call.get(field)));
            if (target != null) calls.add(new CallHierarchyCall(target));
        }
        return calls;
    }

    private List<HierarchyItem> typeHierarchyItems(String method, HierarchyItem item) {
        if (!supports(LspCapability.TYPE_HIERARCHY) || item == null) return List.of();
        Map<String, Object> response = sendRequest(method, Map.of("item", item.requestPayload()), 3000L);
        return parseHierarchyItems(response == null ? null : response.get("result"));
    }

    private Location textDocumentLocation(String method, String uri, int line, int character) {
        List<Location> locations = textDocumentLocations(method, uri, line, character, 2000L);
        return locations.isEmpty() ? null : locations.getFirst();
    }

    private List<Location> textDocumentLocations(String method, String uri, int line, int character, long timeoutMs) {
        Map<String, Object> response = sendTextDocumentPositionRequest(method, uri, line, character, timeoutMs);
        if (response == null) {
            return List.of();
        }
        return parseLocations(response.get("result"));
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
        return parseLocations(response.get("result"));
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

    public List<TextEdit> formatting(String uri, int tabSize, boolean insertSpaces) {
        try {
            return formattingChecked(uri, tabSize, insertSpaces);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    public List<TextEdit> formattingChecked(String uri, int tabSize, boolean insertSpaces) throws IOException {
        if (!supports(LspCapability.FORMATTING)) return List.of();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("tabSize", Math.max(1, tabSize));
        options.put("insertSpaces", insertSpaces);
        Map<String, Object> response = sendRequest("textDocument/formatting", Map.of("textDocument", Map.of("uri", uri), "options", options), 3000L);
        if (response == null) throw new IOException("LSP formatting request timed out");
        if (response.get("error") != null) throw new IOException("LSP formatting request failed: " + response.get("error"));
        return parseTextEdits(uri, MiniJson.asArray(response.get("result")), null);
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
                diagnosticRangeEnd.put("line", diagnostic.getEndLine());
                diagnosticRangeEnd.put("character", diagnostic.getEndCharacter());
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

    public Map<String, List<Diagnostic>> diagnosticsSnapshot() {
        drainNotifications();
        Map<String, List<Diagnostic>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Diagnostic>> entry : diagnostics.entrySet()) {
            snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(snapshot);
    }

    public List<NavigationSymbol> documentSymbols(String uri) {
        if (!supports(LspCapability.DOCUMENT_SYMBOLS) || uri == null || uri.isBlank()) return List.of();
        Map<String, Object> response = sendRequest("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", uri)), 2500L);
        return parseNavigationSymbols(response == null ? null : response.get("result"), uri, true);
    }

    public List<NavigationSymbol> workspaceSymbols(String query) {
        if (!supports(LspCapability.WORKSPACE_SYMBOLS) || query == null || query.isBlank()) return List.of();
        Map<String, Object> response = sendRequest("workspace/symbol", Map.of("query", query), 2500L);
        return parseNavigationSymbols(response == null ? null : response.get("result"), "", false);
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

    private void initialize(String rootUri) throws IOException {
        Map<String, Object> capabilities = new LinkedHashMap<>();

        Map<String, Object> completionItem = new LinkedHashMap<>();
        completionItem.put("snippetSupport", featureSettings.snippets());
        completionItem.put("commitCharacterSupport", Boolean.TRUE);
        completionItem.put("preselectSupport", Boolean.TRUE);
        completionItem.put("labelDetailsSupport", Boolean.TRUE);
        completionItem.put("documentationFormat", List.of("markdown", "plaintext"));
        completionItem.put("resolveSupport", Map.of("properties", List.of("documentation", "detail", "additionalTextEdits")));
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("completionItem", completionItem);
        completion.put("contextSupport", Boolean.TRUE);
        Map<String, Object> hover = new LinkedHashMap<>();
        hover.put("contentFormat", List.of("plaintext"));
        Map<String, Object> semanticTokens = new LinkedHashMap<>();
        semanticTokens.put("requests", Map.of("full", Boolean.TRUE));
        semanticTokens.put("tokenTypes", STANDARD_SEMANTIC_TOKEN_TYPES);
        semanticTokens.put("tokenModifiers", STANDARD_SEMANTIC_TOKEN_MODIFIERS);
        semanticTokens.put("formats", List.of("relative"));
        Map<String, Object> inlayHint = new LinkedHashMap<>();
        inlayHint.put("dynamicRegistration", Boolean.FALSE);
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
        workspace.put("symbol", Map.of("dynamicRegistration", Boolean.FALSE));

        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("completion", completion);
        textDocument.put("hover", hover);
        textDocument.put("definition", new LinkedHashMap<>());
        textDocument.put("typeDefinition", new LinkedHashMap<>());
        textDocument.put("implementation", new LinkedHashMap<>());
        textDocument.put("callHierarchy", Map.of("dynamicRegistration", Boolean.FALSE));
        textDocument.put("typeHierarchy", Map.of("dynamicRegistration", Boolean.FALSE));
        textDocument.put("documentSymbol", Map.of("hierarchicalDocumentSymbolSupport", Boolean.TRUE));
        textDocument.put("publishDiagnostics", diagnosticsCapability);
        textDocument.put("semanticTokens", semanticTokens);
        textDocument.put("inlayHint", inlayHint);
        capabilities.put("textDocument", textDocument);
        capabilities.put("workspace", workspace);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", rootUri);
        params.put("capabilities", capabilities);

        Map<String, Object> response = sendRequest("initialize", params, 5000L);
        if (response == null) {
            throw new IOException("LSP initialize timed out");
        }
        capabilityModel = LspCapabilityModel.fromInitializeResult(response, featureSettings.capabilityEnablement());
        documentSyncKind = parseDocumentSyncKind(response);
        semanticTokenTypes = parseSemanticTokenTypes(response);
        completionTriggerCharacters = parseCompletionTriggerCharacters(response);
        completionResolveSupported = parseCompletionResolveSupport(response);
        sendNotification("initialized", new LinkedHashMap<>());
        initialized = true;
    }

    static DocumentSyncKind parseDocumentSyncKind(Map<String, Object> response) {
        Map<String, Object> result = MiniJson.asObject(response == null ? null : response.get("result"));
        Map<String, Object> capabilities = MiniJson.asObject(result == null ? null : result.get("capabilities"));
        Object value = capabilities == null ? null : capabilities.get("textDocumentSync");
        Integer kind = MiniJson.asInt(value);
        if (kind == null) {
            Map<String, Object> options = MiniJson.asObject(value);
            kind = MiniJson.asInt(options == null ? null : options.get("change"));
        }
        return switch (kind == null ? 1 : kind) {
            case 0 -> DocumentSyncKind.NONE;
            case 2 -> DocumentSyncKind.INCREMENTAL;
            default -> DocumentSyncKind.FULL;
        };
    }

    static List<String> parseSemanticTokenTypes(Map<String, Object> response) {
        Map<String, Object> result = MiniJson.asObject(response == null ? null : response.get("result"));
        Map<String, Object> capabilities = MiniJson.asObject(result == null ? null : result.get("capabilities"));
        Map<String, Object> provider = MiniJson.asObject(capabilities == null ? null : capabilities.get("semanticTokensProvider"));
        Map<String, Object> legend = MiniJson.asObject(provider == null ? null : provider.get("legend"));
        List<Object> tokenTypes = MiniJson.asArray(legend == null ? null : legend.get("tokenTypes"));
        if (tokenTypes == null || tokenTypes.isEmpty()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (Object tokenType : tokenTypes) {
            String value = MiniJson.asString(tokenType);
            parsed.add(value == null ? "" : value);
        }
        return List.copyOf(parsed);
    }

    static Set<String> parseCompletionTriggerCharacters(Map<String, Object> response) {
        Map<String, Object> provider = completionProvider(response);
        List<Object> values = MiniJson.asArray(provider == null ? null : provider.get("triggerCharacters"));
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> characters = new LinkedHashSet<>();
        for (Object value : values) {
            String character = MiniJson.asString(value);
            if (character != null && !character.isEmpty()) characters.add(character);
        }
        return Set.copyOf(characters);
    }

    static boolean parseCompletionResolveSupport(Map<String, Object> response) {
        Map<String, Object> provider = completionProvider(response);
        return Boolean.TRUE.equals(provider == null ? null : provider.get("resolveProvider"));
    }

    private static Map<String, Object> completionProvider(Map<String, Object> response) {
        Map<String, Object> result = MiniJson.asObject(response == null ? null : response.get("result"));
        Map<String, Object> capabilities = MiniJson.asObject(result == null ? null : result.get("capabilities"));
        return MiniJson.asObject(capabilities == null ? null : capabilities.get("completionProvider"));
    }

    static List<NavigationSymbol> parseNavigationSymbols(Object result, String defaultUri, boolean hierarchical) {
        List<Object> values = MiniJson.asArray(result);
        if (values == null || values.isEmpty()) return List.of();
        List<NavigationSymbol> symbols = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> symbol = MiniJson.asObject(value);
            if (symbol == null) continue;
            if (hierarchical && symbol.containsKey("selectionRange")) {
                appendDocumentSymbol(symbols, symbol, defaultUri, 1);
            } else {
                NavigationSymbol parsed = parseFlatSymbol(symbol, defaultUri);
                if (parsed != null) symbols.add(parsed);
            }
        }
        return List.copyOf(symbols);
    }

    private static void appendDocumentSymbol(List<NavigationSymbol> symbols, Map<String, Object> symbol, String uri, int level) {
        String name = MiniJson.asString(symbol.get("name"));
        if (name == null || name.isBlank()) return;
        Map<String, Object> selectionRange = MiniJson.asObject(symbol.get("selectionRange"));
        Map<String, Object> start = MiniJson.asObject(selectionRange == null ? null : selectionRange.get("start"));
        int line = intValue(start == null ? null : start.get("line"));
        int character = intValue(start == null ? null : start.get("character"));
        String detail = MiniJson.asString(symbol.get("detail"));
        symbols.add(new NavigationSymbol(name, detail, intValue(symbol.get("kind")), uri, line, character, level));
        List<Object> children = MiniJson.asArray(symbol.get("children"));
        if (children == null) return;
        for (Object child : children) {
            Map<String, Object> childSymbol = MiniJson.asObject(child);
            if (childSymbol != null) appendDocumentSymbol(symbols, childSymbol, uri, level + 1);
        }
    }

    private static NavigationSymbol parseFlatSymbol(Map<String, Object> symbol, String defaultUri) {
        String name = MiniJson.asString(symbol.get("name"));
        if (name == null || name.isBlank()) return null;
        String detail = MiniJson.asString(symbol.get("containerName"));
        if (detail == null) detail = MiniJson.asString(symbol.get("detail"));
        Map<String, Object> location = MiniJson.asObject(symbol.get("location"));
        String uri = MiniJson.asString(location == null ? null : location.get("uri"));
        if (uri == null || uri.isBlank()) uri = defaultUri;
        Map<String, Object> range = MiniJson.asObject(location == null ? null : location.get("range"));
        Map<String, Object> start = MiniJson.asObject(range == null ? null : range.get("start"));
        return new NavigationSymbol(name, detail, intValue(symbol.get("kind")), uri,
            intValue(start == null ? null : start.get("line")), intValue(start == null ? null : start.get("character")), 1);
    }

    private static int intValue(Object value) {
        Integer parsed = MiniJson.asInt(value);
        return parsed == null ? 0 : Math.max(0, parsed);
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

    private synchronized void writeMessage(Map<String, Object> body) {
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
            Map<String, Object> end = range == null ? null : MiniJson.asObject(range.get("end"));
            int line = start == null || MiniJson.asInt(start.get("line")) == null ? 0 : MiniJson.asInt(start.get("line"));
            int character = start == null || MiniJson.asInt(start.get("character")) == null ? 0 : MiniJson.asInt(start.get("character"));
            int endLine = end == null || MiniJson.asInt(end.get("line")) == null ? line : MiniJson.asInt(end.get("line"));
            int endCharacter = end == null || MiniJson.asInt(end.get("character")) == null ? character + 1 : MiniJson.asInt(end.get("character"));
            int severity = MiniJson.asInt(entry.get("severity")) == null ? 0 : MiniJson.asInt(entry.get("severity"));
            String messageText = MiniJson.asString(entry.get("message"));
            parsed.add(new Diagnostic(line, character, endLine, endCharacter, severity, messageText == null ? "" : messageText));
        }
        diagnostics.put(uri, parsed);
        Runnable handler = diagnosticsChangedHandler;
        if (handler != null) handler.run();
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

    static List<Location> parseLocations(Object value) {
        Map<String, Object> single = MiniJson.asObject(value);
        List<Object> candidates = single == null ? MiniJson.asArray(value) : List.of(single);
        if (candidates == null || candidates.isEmpty()) return List.of();
        Map<String, Location> unique = new LinkedHashMap<>();
        for (Object candidate : candidates) {
            Location location = parseLocation(MiniJson.asObject(candidate));
            if (location != null) {
                unique.putIfAbsent(location.getUri() + "\u0000" + location.getLine() + "\u0000" + location.getCharacter(), location);
            }
        }
        return List.copyOf(unique.values());
    }

    private static Location parseLocation(Map<String, Object> location) {
        if (location == null) return null;
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

    static List<HierarchyItem> parseHierarchyItems(Object value) {
        List<Object> values = MiniJson.asArray(value);
        if (values == null) return List.of();
        List<HierarchyItem> items = new ArrayList<>();
        for (Object candidate : values) {
            HierarchyItem item = parseHierarchyItem(MiniJson.asObject(candidate));
            if (item != null) items.add(item);
        }
        return items;
    }

    private static HierarchyItem parseHierarchyItem(Map<String, Object> value) {
        if (value == null) return null;
        String name = MiniJson.asString(value.get("name"));
        String uri = MiniJson.asString(value.get("uri"));
        Map<String, Object> selection = MiniJson.asObject(value.get("selectionRange"));
        if (selection == null) selection = MiniJson.asObject(value.get("range"));
        Map<String, Object> start = selection == null ? null : MiniJson.asObject(selection.get("start"));
        Integer line = start == null ? null : MiniJson.asInt(start.get("line"));
        Integer character = start == null ? null : MiniJson.asInt(start.get("character"));
        if (name == null || name.isBlank() || uri == null || line == null || character == null) return null;
        String detail = MiniJson.asString(value.get("detail"));
        Integer kind = MiniJson.asInt(value.get("kind"));
        return new HierarchyItem(name, detail, kind == null ? 0 : kind, uri, line, character, value);
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
