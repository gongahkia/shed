package shed;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.List;

final class LspController {
    private static final int CHANGE_DEBOUNCE_MS = 120;
    private static final int DECORATION_DEBOUNCE_MS = 350;
    private static final int MAX_INLINE_INLAY_HINTS = 160;
    private final Texteditor editor;
    private final ManagedLanguageSupportService managedLanguageSupport;
    private final Map<String, LspDocumentSyncState> documentSyncStates = new HashMap<>();
    private final Map<LspServerKey, RemoteLspEndpoint> remoteLspEndpoints = new HashMap<>();
    private Timer lspChangeTimer;
    private FileBuffer pendingLspChange;
    private Timer lspDecorationTimer;
    private FileBuffer pendingLspDecoration;
    private int lspDecorationJobId = -1;
    private int workspaceSymbolQueryGeneration;
    private int codeActionJobId = -1;
    private long codeActionGeneration;

    private record CodeActionRequest(FileBuffer buffer, LspClient client, String uri, Integer version, int caretOffset,
                                     int line, int column, List<LspClient.Diagnostic> diagnostics, int requestedIndex, long generation) { }
    private record CodeActionResult(CodeActionRequest request, List<LspClient.CodeAction> actions) { }
    private record PeekRequest(FileBuffer buffer, LspClient.Location location, String label, String uri, Integer version, int caret) { }

    LspController(Texteditor editor) {
        this.editor = editor;
        Path managedLanguageDirectory = editor != null && editor.configManager != null
            ? Path.of(editor.configManager.getShedDirectoryPath()) : Path.of(System.getProperty("user.home"), ".shed");
        this.managedLanguageSupport = new ManagedLanguageSupportService(new LanguageServerDetector(null, null, null),
            ManagedLanguageDistributionCatalog.trust(), managedLanguageDirectory,
            ManagedLanguageSupportService.platformFor(System.getProperty("os.name")));
    }

    String getWordAtCaret() {
        String text = editor.writingArea.getText();
        int caret = editor.writingArea.getCaretPosition();
        if (text.isEmpty() || caret >= text.length()) return "";
        int start = caret, end = caret;
        while (start > 0 && editor.isWordCharacter(text.charAt(start - 1))) start--;
        while (end < text.length() && editor.isWordCharacter(text.charAt(end))) end++;
        return start == end ? "" : text.substring(start, end);
    }


    public String showLspCompletionStatus() {
        FileBuffer buffer = editor.getCurrentBuffer();
        String prefix = currentCompletionPrefix();
        List<String> completions = new ArrayList<>();
        LspClient client = resolveLspClient(buffer);
        String fallbackReason = null;
        if (buffer != null && client != null && buffer.hasFilePath()) {
            if (!client.supports(LspCapability.COMPLETION)) {
                fallbackReason = client.capabilityUnavailableReason(LspCapability.COMPLETION);
            } else {
                String uri = bufferUri(buffer);
                try {
                    int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
                    int column = editor.writingArea.getCaretPosition() - editor.writingArea.getLineStartOffset(line);
                    List<LspClient.CompletionItem> items = client.completion(uri, line, column);
                    for (LspClient.CompletionItem item : items) {
                        if (item.getLabel() != null && !item.getLabel().isEmpty()) {
                            completions.add(item.getLabel());
                        }
                    }
                } catch (BadLocationException ignored) {
                }
            }
        } else if (buffer != null) {
            fallbackReason = lspErrorFor(buffer);
        }

        if (completions.isEmpty()) {
            if (prefix.isEmpty()) {
                return fallbackReason == null ? "No completion prefix" : "LSP unavailable: " + fallbackReason;
            }
            completions = collectBufferCompletions(prefix);
        }
        if (completions.isEmpty()) {
            return fallbackReason == null ? "No completions" : "LSP unavailable: " + fallbackReason + "; no local completions";
        }
        String selection = editor.showPaletteDialog("Completions", completions);
        if (selection == null || selection.isEmpty()) {
            return "Completion cancelled";
        }
        applyCompletion(prefix, selection);
        return fallbackReason == null ? "Inserted completion" : "Inserted completion (local fallback; LSP unavailable: " + fallbackReason + ")";
    }


    public String handleLspCommand(String argument) {
        flushPendingLspChange(editor.getCurrentBuffer());
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "help".equals(trimmed)) {
            return "Usage: :lsp completion|definition|typedefinition|peek definition|peek type|calls incoming|outgoing|typehierarchy supertypes|subtypes|hover|semantic|inlay|references|rename <newName>|renameapply|renamecancel|codeaction [index]";
        }
        int split = trimmed.indexOf(' ');
        String subcommand = split < 0 ? trimmed.toLowerCase() : trimmed.substring(0, split).toLowerCase();
        String args = split < 0 ? "" : trimmed.substring(split + 1).trim();
        switch (subcommand) {
            case "completion":
            case "complete":
            case "comp":
                return showLspCompletionStatus();
            case "definition":
            case "def":
                return lspGoToDefinition();
            case "typedefinition":
            case "type-definition":
            case "type":
            case "typedef":
                return lspGoToTypeDefinition();
            case "hover":
                return lspHover();
            case "semantic":
            case "semantictokens":
                return lspSemanticTokens();
            case "inlay":
            case "inlayhints":
                return lspInlayHints();
            case "format":
                return lspFormat();
            case "peek":
                return lspPeek(args);
            case "calls":
            case "callhierarchy":
                return lspCallHierarchy(args);
            case "typehierarchy":
            case "type-hierarchy":
                return lspTypeHierarchy(args);
            case "references":
            case "refs":
                return lspReferences();
            case "rename":
                return lspRename(args);
            case "renameapply":
            case "rename!":
                return lspRenameApply();
            case "renamecancel":
            case "renameclear":
                return lspRenameCancel();
            case "codeaction":
            case "codeactions":
            case "actions":
            case "ca":
                if ("apply".equalsIgnoreCase(args)) return lspCodeActionApply();
                return lspCodeActions(args);
            case "diagnostics":
            case "diag":
                return showDiagnostics();
            case "status":
                return lspStatus();
            case "restart":
                return lspRestart(args);
            case "stop":
                return lspStop(args);
            case "servers":
                return lspServers();
            case "manage":
            case "manager":
                return lspManage(args);
            case "log":
                return lspLog();
            default:
                return "Unknown :lsp subcommand: " + subcommand;
        }
    }


    public String lspStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("LSP Server Status\n");
        sb.append("=".repeat(40)).append("\n\n");
        if (editor.lspClients.isEmpty() && editor.lspErrors.isEmpty()) {
            sb.append("No LSP servers active.\n");
            sb.append("Open a file with a configured language to start a server.\n");
        }
        for (Map.Entry<LspServerKey, LspClient> entry : editor.lspClients.entrySet()) {
            LspServerKey key = entry.getKey();
            LspClient client = entry.getValue();
            sb.append("  ").append(key.displayName()).append("  ");
            sb.append(client.isAlive() ? "running" : "stopped");
            Path root = key.workspaceRoot();
            if (root != null) {
                sb.append("  ").append(root);
            }
            sb.append("\n");
            for (LspCapability capability : LspCapability.values()) {
                if (client.capabilityAvailability(capability) != LspCapabilityModel.Availability.AVAILABLE) {
                    sb.append("    ").append(client.capabilityUnavailableReason(capability)).append("\n");
                }
            }
        }
        if (!editor.lspErrors.isEmpty()) {
            sb.append("\nErrors:\n");
            for (Map.Entry<LspServerKey, String> entry : editor.lspErrors.entrySet()) {
                LspServerKey key = entry.getKey();
                sb.append("  ").append(key.displayName());
                if (key.workspaceRoot() != null) sb.append("  ").append(key.workspaceRoot());
                sb.append(": ").append(entry.getValue()).append("\n");
            }
        }
        sb.append("\nManaged support: :lsp manage (detect/install/update/remove/retry/manual)\n");
        editor.showScratchBuffer("[lsp status]", sb.toString());
        return "Showing LSP status";
    }


    public String lspRestart(String ext) {
        String extension = ext.isEmpty() ? currentBufferExtension() : ext.replace(".", "").toLowerCase();
        if (extension.isEmpty()) return "No extension specified and no file open";
        int stopped = stopServersForExtension(extension);
        removeErrorsForExtension(extension);
        // remove document versions for this extension so didOpen fires again
        editor.lspDocumentVersions.entrySet().removeIf(e -> {
            String uri = e.getKey();
            int dot = uri.lastIndexOf('.');
            return dot >= 0 && uri.substring(dot + 1).equalsIgnoreCase(extension);
        });
        documentSyncStates.entrySet().removeIf(e -> hasExtension(e.getKey(), extension));
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf != null && bufferExtension(buf).equals(extension)) {
            LspClient client = resolveLspClient(buf);
            if (client != null) return "Restarted LSP for ." + extension;
            return "Failed to restart LSP for ." + extension;
        }
        return stopped == 0 ? "No LSP server running for ." + extension + " (will start on next use)"
            : "Stopped " + stopped + " LSP server" + (stopped == 1 ? "" : "s") + " for ." + extension + " (will restart on next use)";
    }


    public String lspStop(String ext) {
        String extension = ext.isEmpty() ? currentBufferExtension() : ext.replace(".", "").toLowerCase();
        if (extension.isEmpty()) return "No extension specified and no file open";
        int stopped = stopServersForExtension(extension);
        if (stopped == 0) return "No LSP server running for ." + extension;
        removeErrorsForExtension(extension);
        return "Stopped " + stopped + " LSP server" + (stopped == 1 ? "" : "s") + " for ." + extension;
    }


    public String lspServers() {
        StringBuilder sb = new StringBuilder();
        sb.append("LSP Servers\n");
        sb.append("=".repeat(40)).append("\n\n");
        Map<String, String> configured = editor.configManager.getConfiguredLspServers();
        sb.append("Configured (config.toml):\n");
        if (configured.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (Map.Entry<String, String> entry : configured.entrySet()) {
                sb.append("  .").append(entry.getKey()).append(" -> ").append(entry.getValue()).append("\n");
            }
        }
        sb.append("\nBuiltin:\n");
        for (String ext : editor.lspService.getBuiltinExtensions()) {
            if (configured.containsKey(ext)) continue;
            ExtensionRegistry.Owned<shed.api.LanguageContribution> contribution = extensionLanguage(ext);
            if (contribution != null) continue;
            String[] cmd = editor.lspService.builtinCommand(ext);
            if (cmd != null) {
                sb.append("  .").append(ext).append(" -> ").append(String.join(" ", cmd)).append("\n");
            }
        }
        List<ExtensionRegistry.Owned<shed.api.LanguageContribution>> contributed = editor.extensionManager == null ? List.of() : editor.extensionManager.languages();
        if (!contributed.isEmpty()) {
            sb.append("\nExtensions:\n");
            for (ExtensionRegistry.Owned<shed.api.LanguageContribution> contribution : contributed) {
                shed.api.LanguageContribution language = contribution.value();
                sb.append("  ").append(contribution.extensionId()).append(":").append(language.id()).append("  .")
                    .append(String.join(", .", language.fileExtensions()));
                if (language.serverCommand().isEmpty()) sb.append("  (no LSP command)");
                else sb.append(" -> ").append(String.join(" ", language.serverCommand())).append(language.serverArguments().isEmpty() ? "" : " " + String.join(" ", language.serverArguments()));
                sb.append("\n");
            }
        }
        editor.showScratchBuffer("[lsp servers]", sb.toString());
        return "Showing LSP servers";
    }

    private String lspManage(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "ui".equalsIgnoreCase(trimmed) || "gui".equalsIgnoreCase(trimmed)) {
            ManagedLanguageServicesDialog.showFor(editor, managedLanguageSupport);
            return "Opened language services";
        }
        if ("status".equalsIgnoreCase(trimmed) || "list".equalsIgnoreCase(trimmed)) {
            editor.showScratchBuffer("[lsp manage]", managedLanguageSupport.overview());
            return "Showing managed LSP support";
        }
        int split = trimmed.indexOf(' ');
        String action = (split < 0 ? trimmed : trimmed.substring(0, split)).toLowerCase(Locale.ROOT);
        String target = split < 0 ? "" : trimmed.substring(split + 1).trim();
        if (target.isEmpty()) target = currentBufferExtension();
        if (target.isEmpty()) return "Usage: :lsp manage detect|install|update|remove|retry|manual <extension>";
        ManagedLanguageCatalog.Entry entry = managedLanguageSupport.entryFor(target);
        if (entry == null) return "No managed LSP catalog entry for: " + target;
        return switch (action) {
            case "detect", "retry" -> startManagedLspDetection(entry);
            case "install", "update" -> {
                ManagedLanguageServicesDialog.showFor(editor, managedLanguageSupport, entry);
                yield "Opened language services for " + action;
            }
            case "remove", "uninstall" -> managedLanguageSupport.remove(entry).detail();
            case "manual", "configure", "config" -> showManagedLspText("[lsp manual " + target + "]",
                managedLanguageSupport.manualInstructions(entry), "Showing manual LSP setup");
            default -> "Unknown :lsp manage action: " + action;
        };
    }

    private String startManagedLspDetection(ManagedLanguageCatalog.Entry entry) {
        int jobId = editor.asyncJobService.submit("LSP detection: " + entry.displayName(), token -> managedLanguageSupport.detect(entry),
            (snapshot, result, error) -> {
                if (snapshot.getStatus() == AsyncJobService.Status.SUCCEEDED && result != null) {
                    editor.showMessage(entry.displayName() + " detection complete; run :lsp manage to review");
                } else {
                    editor.showMessage(entry.displayName() + " detection failed: " + (error == null ? snapshot.getErrorMessage() : error.getMessage()));
                }
            });
        return "LSP detection job " + jobId + " started";
    }

    private String showManagedLspText(String title, String text, String result) {
        editor.showScratchBuffer(title, text);
        return result;
    }


    public String lspLog() {
        if (editor.lspErrors.isEmpty()) return "No LSP errors";
        StringBuilder sb = new StringBuilder();
        sb.append("LSP Error Log\n");
        sb.append("=".repeat(40)).append("\n\n");
        for (Map.Entry<LspServerKey, String> entry : editor.lspErrors.entrySet()) {
            LspServerKey key = entry.getKey();
            sb.append(key.displayName());
            if (key.workspaceRoot() != null) sb.append("  ").append(key.workspaceRoot());
            sb.append(": ").append(entry.getValue()).append("\n");
        }
        editor.showScratchBuffer("[lsp log]", sb.toString());
        return "Showing LSP log";
    }


    String currentBufferExtension() {
        FileBuffer buf = editor.getCurrentBuffer();
        return buf == null ? "" : bufferExtension(buf);
    }


    public String lspGoToDefinition() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return "LSP definition requires a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
        String unavailable = capabilityUnavailable(client, LspCapability.DEFINITION);
        if (unavailable != null) return unavailable;
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int column = editor.writingArea.getCaretPosition() - editor.writingArea.getLineStartOffset(line);
            LspClient.Location location = client.definition(uri, line, column);
            if (location == null) {
                return "No definition found";
            }
            return openLspLocation(location, "definition");
        } catch (BadLocationException e) {
            return "LSP definition failed: " + e.getMessage();
        }
    }

    public String lspGoToTypeDefinition() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) return "LSP type definition requires a file-backed buffer";
        LspClient client = resolveLspClient(buffer);
        if (client == null) return "LSP unavailable";
        String unavailable = capabilityUnavailable(client, LspCapability.TYPE_DEFINITION);
        if (unavailable != null) return unavailable;
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int column = editor.writingArea.getCaretPosition() - editor.writingArea.getLineStartOffset(line);
            LspClient.Location location = client.typeDefinition(uri, line, column);
            if (location == null) return "No type definition found";
            return openLspLocation(location, "type definition");
        } catch (BadLocationException error) {
            return "LSP type definition failed: " + error.getMessage();
        }
    }

    private String lspPeek(String argument) {
        String target = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if ("definition".equals(target) || "def".equals(target)) return requestPeek(false);
        if ("type".equals(target) || "typedefinition".equals(target) || "type-definition".equals(target)) return requestPeek(true);
        return "Usage: :lsp peek definition|type";
    }

    private String requestPeek(boolean typeDefinition) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) return "LSP peek requires a file-backed buffer";
        LspClient client = resolveLspClient(buffer);
        if (client == null) return "LSP unavailable";
        LspCapability capability = typeDefinition ? LspCapability.TYPE_DEFINITION : LspCapability.DEFINITION;
        String unavailable = capabilityUnavailable(client, capability);
        if (unavailable != null) return unavailable;
        try {
            syncLspOpen(buffer);
            String uri = bufferUri(buffer);
            int caret = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caret);
            int column = caret - editor.writingArea.getLineStartOffset(line);
            Integer version = editor.lspDocumentVersions.get(uri);
            String label = typeDefinition ? "type definition" : "definition";
            editor.asyncJobService.submit("LSP peek " + label, token -> {
                LspClient.Location location = typeDefinition ? client.typeDefinition(uri, line, column) : client.definition(uri, line, column);
                return location == null ? null : new PeekRequest(buffer, location, label, uri, version, caret);
            }, (job, request, error) -> completePeek(job, request, error));
            return "LSP peek requested";
        } catch (BadLocationException error) {
            return "LSP peek failed: " + error.getMessage();
        }
    }

    private void completePeek(AsyncJobService.JobSnapshot job, PeekRequest request, Exception error) {
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) { editor.showMessage("LSP peek cancelled"); return; }
        if (error != null) { editor.showMessage("LSP peek failed: " + error.getMessage()); return; }
        if (request == null) { editor.showMessage("No LSP target found"); return; }
        if (editor.getCurrentBuffer() != request.buffer() || editor.writingArea.getCaretPosition() != request.caret()
            || !Objects.equals(editor.lspDocumentVersions.get(request.uri()), request.version())) {
            editor.showMessage("LSP peek became stale");
            return;
        }
        editor.asyncJobService.submit("LSP peek file", token -> editor.peekView.load(request.location(), request.label()),
            (loadJob, preview, loadError) -> {
                if (loadJob.getStatus() == AsyncJobService.Status.CANCELLED) return;
                if (loadError != null) editor.showMessage("LSP peek unavailable: " + loadError.getMessage());
                else editor.peekView.show(preview);
            });
    }

    private String lspCallHierarchy(String argument) {
        String direction = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (!"incoming".equals(direction) && !"outgoing".equals(direction)) return "Usage: :lsp calls incoming|outgoing";
        return requestHierarchy(true, direction);
    }

    private String lspTypeHierarchy(String argument) {
        String direction = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (!"supertypes".equals(direction) && !"subtypes".equals(direction)) return "Usage: :lsp typehierarchy supertypes|subtypes";
        return requestHierarchy(false, direction);
    }

    private String requestHierarchy(boolean callHierarchy, String direction) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) return "LSP hierarchy requires a file-backed buffer";
        LspClient client = resolveLspClient(buffer);
        if (client == null) return "LSP unavailable";
        LspCapability capability = callHierarchy ? LspCapability.CALL_HIERARCHY : LspCapability.TYPE_HIERARCHY;
        String unavailable = capabilityUnavailable(client, capability);
        if (unavailable != null) return unavailable;
        try {
            syncLspOpen(buffer);
            String uri = bufferUri(buffer);
            int caret = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caret);
            int column = caret - editor.writingArea.getLineStartOffset(line);
            Integer version = editor.lspDocumentVersions.get(uri);
            editor.asyncJobService.submit("LSP hierarchy prepare", token -> callHierarchy
                ? client.prepareCallHierarchy(uri, line, column) : client.prepareTypeHierarchy(uri, line, column), (job, roots, error) -> {
                    if (job.getStatus() == AsyncJobService.Status.CANCELLED) { editor.showMessage("LSP hierarchy cancelled"); return; }
                    if (error != null) { editor.showMessage("LSP hierarchy failed: " + error.getMessage()); return; }
                    if (editor.getCurrentBuffer() != buffer || editor.writingArea.getCaretPosition() != caret
                        || !Objects.equals(editor.lspDocumentVersions.get(uri), version)) { editor.showMessage("LSP hierarchy became stale"); return; }
                    if (roots == null || roots.isEmpty()) { editor.showMessage("No LSP hierarchy target found"); return; }
                    String title = "LSP " + (callHierarchy ? "Call" : "Type") + " Hierarchy — " + direction;
                    LspHierarchyDialog.show(editor, title, roots, item -> {
                        if (callHierarchy) {
                            List<LspClient.CallHierarchyCall> calls = "incoming".equals(direction) ? client.incomingCalls(item) : client.outgoingCalls(item);
                            return calls.stream().map(LspClient.CallHierarchyCall::item).toList();
                        }
                        return "supertypes".equals(direction) ? client.typeSupertypes(item) : client.typeSubtypes(item);
                    });
                });
            return "LSP hierarchy requested";
        } catch (BadLocationException error) {
            return "LSP hierarchy failed: " + error.getMessage();
        }
    }


    public String lspHover() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return "LSP hover requires a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
        String unavailable = capabilityUnavailable(client, LspCapability.HOVER);
        if (unavailable != null) return unavailable;
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int column = editor.writingArea.getCaretPosition() - editor.writingArea.getLineStartOffset(line);
            String hoverText = client.hover(uri, line, column);
            if (hoverText == null || hoverText.isBlank()) {
                return "No hover information";
            }
            editor.showScratchBuffer("[lsp hover]", hoverText.strip() + "\n");
            return "Showing hover";
        } catch (BadLocationException e) {
            return "LSP hover failed: " + e.getMessage();
        }
    }

    public String lspSemanticTokens() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) return "LSP semantic tokens require a file-backed buffer";
        LspClient client = resolveLspClient(buffer);
        if (client == null) return "LSP unavailable";
        String unavailable = capabilityUnavailable(client, LspCapability.SEMANTIC_TOKENS);
        if (unavailable != null) return unavailable;
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        Integer version = editor.lspDocumentVersions.get(uri);
        List<LspClient.SemanticToken> tokens = client.semanticTokens(uri);
        if (!Objects.equals(version, editor.lspDocumentVersions.get(uri))) return "Semantic tokens became stale; refresh again";
        StringBuilder text = new StringBuilder("LSP Semantic Tokens\n\n");
        for (LspClient.SemanticToken token : tokens) text.append(token.line() + 1).append(":").append(token.character() + 1)
            .append(" length ").append(token.length()).append(" type ").append(token.type()).append("\n");
        if (tokens.isEmpty()) text.append("No semantic tokens\n");
        editor.showScratchBuffer("[lsp semantic]", text.toString());
        return "Showing semantic tokens";
    }

    public String lspInlayHints() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) return "LSP inlay hints require a file-backed buffer";
        LspClient client = resolveLspClient(buffer);
        if (client == null) return "LSP unavailable";
        String unavailable = capabilityUnavailable(client, LspCapability.INLAY_HINTS);
        if (unavailable != null) return unavailable;
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        try {
            int lastLine = Math.max(0, editor.writingArea.getLineCount() - 1);
            int lastCharacter = editor.writingArea.getDocument().getLength() - editor.writingArea.getLineStartOffset(lastLine);
            Integer version = editor.lspDocumentVersions.get(uri);
            List<LspClient.InlayHint> hints = client.inlayHints(uri, lastLine, lastCharacter);
            if (!Objects.equals(version, editor.lspDocumentVersions.get(uri))) return "Inlay hints became stale; refresh again";
            StringBuilder text = new StringBuilder("LSP Inlay Hints\n\n");
            for (LspClient.InlayHint hint : hints) text.append(hint.line() + 1).append(":").append(hint.character() + 1)
                .append("  ").append(hint.label()).append("\n");
            if (hints.isEmpty()) text.append("No inlay hints\n");
            editor.showScratchBuffer("[lsp inlay]", text.toString());
            return "Showing inlay hints";
        } catch (BadLocationException error) {
            return "LSP inlay hints failed: " + error.getMessage();
        }
    }

    public String lspFormat() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) return "LSP formatting requires a file-backed buffer";
        LspClient client = resolveLspClient(buffer);
        if (client == null) return "LSP unavailable";
        String unavailable = capabilityUnavailable(client, LspCapability.FORMATTING);
        if (unavailable != null) return unavailable;
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        Integer version = editor.lspDocumentVersions.get(uri);
        List<LspClient.TextEdit> edits = client.formatting(uri, editor.writingArea.getTabSize(), editor.effectiveExpandTab());
        if (!Objects.equals(version, editor.lspDocumentVersions.get(uri))) return "Formatting became stale; document was not changed";
        if (edits.isEmpty()) return "No formatting edits";
        WorkspaceEditApplyResult result = applyWorkspaceTextEdits(edits);
        if (result.appliedEditCount <= 0) return result.failureReason == null ? "Formatting produced no applicable edits" : "Formatting failed: " + result.failureReason;
        return "Applied " + result.appliedEditCount + " formatting edit" + (result.appliedEditCount == 1 ? "" : "s");
    }


    public String lspReferences() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return "LSP references requires a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
        String unavailable = capabilityUnavailable(client, LspCapability.REFERENCES);
        if (unavailable != null) return unavailable;
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int column = editor.writingArea.getCaretPosition() - editor.writingArea.getLineStartOffset(line);
            List<LspClient.Location> locations = client.references(uri, line, column, true);
            if (locations.isEmpty()) {
                editor.problemsController.clearQuickfixSource("lsp");
                return "No references found";
            }
            List<QuickfixService.Entry> entries = new ArrayList<>();
            for (LspClient.Location location : locations) {
                String path = filePathFromUri(location.getUri());
                if (path == null || path.isBlank()) {
                    continue;
                }
                entries.add(new QuickfixService.Entry(path, location.getLine() + 1, location.getCharacter() + 1, "reference", "lsp"));
            }
            if (entries.isEmpty()) {
                editor.problemsController.clearQuickfixSource("lsp");
                return "No file references found";
            }
            editor.updateQuickfixEntries("lsp references", entries);
            return editor.openQuickfixList();
        } catch (BadLocationException e) {
            return "LSP references failed: " + e.getMessage();
        }
    }

    String showSymbols(String argument) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return editor.showHeuristicSymbols(argument);
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null || !client.supports(LspCapability.DOCUMENT_SYMBOLS)) {
            return editor.showHeuristicSymbols(argument);
        }
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        int jobId = editor.asyncJobService.submit("lsp document symbols", token -> {
            if (token.isCancelled()) return List.<LspClient.NavigationSymbol>of();
            return client.documentSymbols(uri);
        }, (snapshot, symbols, error) -> {
            if (snapshot.getStatus() == AsyncJobService.Status.CANCELLED) return;
            if (error != null || symbols == null || symbols.isEmpty()) {
                editor.showHeuristicSymbols(argument);
                return;
            }
            editor.showLspSymbols(symbols, argument, false);
        });
        return "Loading document symbols (job " + jobId + ")";
    }

    String showWorkspaceSymbols(String argument) {
        String query = argument == null ? "" : argument.trim();
        if (query.isBlank()) return "Usage: :workspace symbols <query>";
        FileBuffer active = editor.getCurrentBuffer();
        if (active != null && active.hasFilePath() && !active.isLargeFile()) syncLspOpen(active);
        List<LspClient> clients = new ArrayList<>(new LinkedHashSet<>(editor.lspClients.values()));
        clients.removeIf(client -> client == null || !client.isAlive() || !client.supports(LspCapability.WORKSPACE_SYMBOLS));
        if (clients.isEmpty()) return "No active LSP server supports workspace symbols";
        int generation = ++workspaceSymbolQueryGeneration;
        int jobId = editor.asyncJobService.submit("lsp workspace symbols", token -> collectWorkspaceSymbols(clients, query, token),
            (snapshot, symbols, error) -> {
                if (generation != workspaceSymbolQueryGeneration || snapshot.getStatus() == AsyncJobService.Status.CANCELLED) return;
                if (error != null) { editor.showMessage("Workspace symbol search failed: " + error.getMessage()); return; }
                if (symbols == null || symbols.isEmpty()) { editor.showMessage("No workspace symbols found: " + query); return; }
                editor.showLspSymbols(symbols, query, true);
            });
        return "Loading workspace symbols (job " + jobId + ")";
    }

    private List<LspClient.NavigationSymbol> collectWorkspaceSymbols(List<LspClient> clients, String query, AsyncJobService.JobToken token) {
        Map<String, LspClient.NavigationSymbol> unique = Collections.synchronizedMap(new LinkedHashMap<>());
        clients.parallelStream().forEach(client -> {
            if (token.isCancelled()) return;
            for (LspClient.NavigationSymbol symbol : client.workspaceSymbols(query)) {
                if (token.isCancelled()) return;
                String key = symbol.getUri() + "\u0000" + symbol.getLine() + "\u0000" + symbol.getCharacter() + "\u0000" + symbol.getName();
                unique.putIfAbsent(key, symbol);
            }
        });
        List<LspClient.NavigationSymbol> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing(LspClient.NavigationSymbol::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(LspClient.NavigationSymbol::getUri, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(LspClient.NavigationSymbol::getLine));
        return result.size() <= 300 ? result : new ArrayList<>(result.subList(0, 300));
    }

    String openLspSymbol(LspClient.NavigationSymbol symbol) {
        if (symbol == null) return "No symbol selected";
        return openLspLocation(new LspClient.Location(symbol.getUri(), symbol.getLine(), symbol.getCharacter()), "symbol");
    }


    public String lspRename(String newName) {
        if (newName == null || newName.isBlank()) {
            return "Usage: :lsp rename <newName>";
        }
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return "LSP rename requires a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
        String unavailable = capabilityUnavailable(client, LspCapability.RENAME);
        if (unavailable != null) return unavailable;
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int column = editor.writingArea.getCaretPosition() - editor.writingArea.getLineStartOffset(line);
            List<LspClient.TextEdit> edits = client.rename(uri, line, column, newName.trim());
            if (edits.isEmpty()) {
                return "No rename edits returned";
            }
            editor.pendingLspRenameEdits = new ArrayList<>(edits);
            editor.pendingLspRenameTarget = newName.trim();
            editor.showScratchBuffer("[lsp rename preview]", buildLspRenamePreview(editor.pendingLspRenameTarget, editor.pendingLspRenameEdits));
            return "Prepared rename preview (" + edits.size() + " edit" + (edits.size() == 1 ? "" : "s") + "). Run :lsp renameapply to confirm.";
        } catch (BadLocationException e) {
            return "LSP rename failed: " + e.getMessage();
        }
    }


    public String lspRenameApply() {
        if (editor.pendingLspRenameEdits == null || editor.pendingLspRenameEdits.isEmpty()) {
            return "No pending rename preview (run :lsp rename <newName> first)";
        }
        WorkspaceEditApplyResult applyResult = applyWorkspaceTextEdits(editor.pendingLspRenameEdits);
        if (applyResult.appliedEditCount <= 0) {
            editor.pendingLspRenameEdits = null;
            editor.pendingLspRenameTarget = null;
            return "Pending rename had no applicable edits";
        }
        StringBuilder message = new StringBuilder();
        message.append("Applied ")
            .append(applyResult.appliedEditCount)
            .append(" rename edit")
            .append(applyResult.appliedEditCount == 1 ? "" : "s")
            .append(" across ")
            .append(applyResult.touchedFiles)
            .append(" file")
            .append(applyResult.touchedFiles == 1 ? "" : "s");
        if (applyResult.failedFiles > 0) {
            message.append(" (").append(applyResult.failedFiles).append(" file failures)");
        }
        editor.pendingLspRenameEdits = null;
        editor.pendingLspRenameTarget = null;
        return message.toString();
    }


    public String lspRenameCancel() {
        editor.pendingLspRenameEdits = null;
        editor.pendingLspRenameTarget = null;
        return "Cleared pending rename preview";
    }


    String buildLspRenamePreview(String targetName, List<LspClient.TextEdit> edits) {
        StringBuilder builder = new StringBuilder();
        builder.append("LSP Rename Preview\n");
        builder.append("=".repeat(40)).append("\n\n");
        builder.append("Target name: ").append(targetName == null ? "" : targetName).append("\n");
        builder.append("Total edits: ").append(edits == null ? 0 : edits.size()).append("\n\n");
        if (edits == null || edits.isEmpty()) {
            builder.append("(no edits)\n");
            return builder.toString();
        }
        Map<String, List<LspClient.TextEdit>> byFile = new LinkedHashMap<>();
        for (LspClient.TextEdit edit : edits) {
            String path = filePathFromUri(edit.getUri());
            if (path == null || path.isBlank()) {
                path = edit.getUri();
            }
            byFile.computeIfAbsent(path, key -> new ArrayList<>()).add(edit);
        }
        for (Map.Entry<String, List<LspClient.TextEdit>> entry : byFile.entrySet()) {
            builder.append(entry.getKey()).append("\n");
            List<LspClient.TextEdit> fileEdits = entry.getValue();
            fileEdits.sort((a, b) -> {
                if (a.getStartLine() != b.getStartLine()) {
                    return Integer.compare(a.getStartLine(), b.getStartLine());
                }
                return Integer.compare(a.getStartCharacter(), b.getStartCharacter());
            });
            int limit = Math.min(8, fileEdits.size());
            for (int i = 0; i < limit; i++) {
                LspClient.TextEdit edit = fileEdits.get(i);
                builder.append("  - ")
                    .append(edit.getStartLine() + 1)
                    .append(":")
                    .append(edit.getStartCharacter() + 1)
                    .append(" -> ")
                    .append(editor.safePreviewText(edit.getNewText(), 60))
                    .append("\n");
            }
            if (fileEdits.size() > limit) {
                builder.append("  ... ").append(fileEdits.size() - limit).append(" more edits\n");
            }
            builder.append("\n");
        }
        builder.append("Run :lsp renameapply to apply, or :lsp renamecancel to discard.\n");
        return builder.toString();
    }


    public String lspCodeActions(String selectionArgument) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return "LSP code actions require a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
        String unavailable = capabilityUnavailable(client, LspCapability.CODE_ACTION);
        if (unavailable != null) return unavailable;
        int requestedIndex = parseOneBasedIndex(selectionArgument);
        if (selectionArgument != null && !selectionArgument.isBlank() && requestedIndex < 1) return "Usage: :lsp codeaction [index]";
        syncLspOpen(buffer);
        flushPendingLspChange(buffer);
        String uri = bufferUri(buffer);
        try {
            int caret = editor.writingArea.getCaretPosition();
            int line = editor.writingArea.getLineOfOffset(caret);
            int column = caret - editor.writingArea.getLineStartOffset(line);
            List<LspClient.Diagnostic> diagnostics = collectCursorDiagnostics(client, uri, line, column);
            if (diagnostics.isEmpty()) return "No diagnostic at caret";
            long generation = ++codeActionGeneration;
            if (codeActionJobId >= 0) editor.asyncJobService.cancel(codeActionJobId);
            CodeActionRequest request = new CodeActionRequest(buffer, client, uri, editor.lspDocumentVersions.get(uri), caret, line, column,
                List.copyOf(diagnostics), requestedIndex, generation);
            codeActionJobId = editor.asyncJobService.submit("LSP code actions", token -> new CodeActionResult(request,
                client.codeActions(uri, line, column, request.diagnostics())), this::completeCodeActions);
            return "Loading code actions…";
        } catch (BadLocationException e) {
            return "LSP code actions failed: " + e.getMessage();
        }
    }

    private void completeCodeActions(AsyncJobService.JobSnapshot job, CodeActionResult result, Exception error) {
        if (result == null || result.request().generation() != codeActionGeneration) return;
        codeActionJobId = -1;
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) return;
        if (error != null) { editor.showMessage("Code actions failed: " + error.getMessage()); return; }
        CodeActionRequest request = result.request();
        if (editor.getCurrentBuffer() != request.buffer() || !Objects.equals(editor.lspDocumentVersions.get(request.uri()), request.version())
            || editor.writingArea.getCaretPosition() != request.caretOffset()) {
            editor.showMessage("Code actions became stale");
            return;
        }
        List<LspClient.CodeAction> actions = result.actions() == null ? List.of() : result.actions();
        if (actions.isEmpty()) { editor.showMessage("No code actions"); return; }
        if (request.requestedIndex() > 0) {
            if (request.requestedIndex() > actions.size()) { editor.showMessage("Code action index out of range: " + request.requestedIndex()); return; }
            prepareCodeActionPreview(actions.get(request.requestedIndex() - 1));
            return;
        }
        showCodeActionPicker(actions);
    }

    private void showCodeActionPicker(List<LspClient.CodeAction> actions) {
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(editor.configManager.getSelectionColor()));
        for (LspClient.CodeAction action : actions) {
            String label = action.getTitle() + (action.isPreferred() ? "  ★" : "");
            JMenuItem item = new JMenuItem(label);
            item.setFont(editor.resolveUiFont());
            item.addActionListener(event -> prepareCodeActionPreview(action));
            popup.add(item);
        }
        try {
            java.awt.Rectangle bounds = editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition()).getBounds();
            popup.show(editor.writingArea, bounds.x, bounds.y + bounds.height);
        } catch (BadLocationException error) {
            editor.showMessage("Code action picker failed: " + error.getMessage());
        }
    }

    private void prepareCodeActionPreview(LspClient.CodeAction action) {
        editor.pendingLspCodeAction = action;
        editor.showScratchBuffer("[lsp code action preview]", buildWorkspaceEditPreview(action.getTitle(), action.getOperations(), new WorkspaceEditApplyResult())
            + "\nRun :lsp codeaction apply to confirm, or choose another action to replace this preview.\n");
        editor.showMessage("Prepared code action preview. Run :lsp codeaction apply to confirm.");
    }

    public String lspCodeActionApply() {
        LspClient.CodeAction action = editor.pendingLspCodeAction;
        if (action == null) return "No pending code action preview";
        WorkspaceEditApplyResult applyResult = applyWorkspaceOperations(action.getOperations());
        editor.pendingLspCodeAction = null;
        if (applyResult.appliedEditCount == 0 && applyResult.appliedResourceOperationCount == 0) {
            return applyResult.failureReason == null || applyResult.failureReason.isBlank() ? "Code action produced no applicable edit" : "Code action failed: " + applyResult.failureReason;
        }
        return "Applied code action: " + action.getTitle();
    }


    List<LspClient.CodeAction> collectCursorCodeActions(LspClient client, String uri, int line, int column) {
        return client.codeActions(uri, line, column, collectCursorDiagnostics(client, uri, line, column));
    }

    List<LspClient.Diagnostic> collectCursorDiagnostics(LspClient client, String uri, int line, int column) {
        List<LspClient.Diagnostic> scoped = new ArrayList<>();
        for (LspClient.Diagnostic diagnostic : client.getDiagnostics(uri)) {
            if (contains(diagnostic, line, column)) scoped.add(diagnostic);
        }
        return scoped;
    }

    private static boolean contains(LspClient.Diagnostic diagnostic, int line, int column) {
        if (diagnostic == null) return false;
        int startLine = diagnostic.getLine();
        int startColumn = diagnostic.getCharacter();
        int endLine = diagnostic.getEndLine();
        int endColumn = diagnostic.getEndCharacter();
        if (line < startLine || line > endLine) return false;
        if (startLine == endLine) return line == startLine && column >= startColumn && column <= Math.max(startColumn, endColumn);
        if (line == startLine) return column >= startColumn;
        if (line == endLine) return column <= endColumn;
        return true;
    }

    private String capabilityUnavailable(LspClient client, LspCapability capability) {
        return client.supports(capability) ? null : client.capabilityUnavailableReason(capability);
    }


    int parseOneBasedIndex(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }


    public String showDiagnostics() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return "Diagnostics require a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
        syncLspOpen(buffer);
        List<LspClient.Diagnostic> diagnostics = client.getDiagnostics(bufferUri(buffer));
        if (diagnostics.isEmpty()) {
            return "No diagnostics";
        }
        List<QuickfixService.Entry> entries = diagnosticsToQuickfixEntries(buffer.getFilePath(), diagnostics);
        if (entries.isEmpty()) {
            return "No diagnostics";
        }
        editor.updateQuickfixEntries("diagnostics", entries);
        return editor.openQuickfixList();
    }


    public String diagnosticsNext() {
        return jumpDiagnostic(true);
    }


    public String diagnosticsPrev() {
        return jumpDiagnostic(false);
    }


    String jumpDiagnostic(boolean forward) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return "Diagnostics require a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
        syncLspOpen(buffer);
        List<LspClient.Diagnostic> diagnostics = new ArrayList<>(client.getDiagnostics(bufferUri(buffer)));
        if (diagnostics.isEmpty()) {
            return "No diagnostics";
        }
        diagnostics.sort((left, right) -> {
            if (left.getLine() != right.getLine()) {
                return Integer.compare(left.getLine(), right.getLine());
            }
            return Integer.compare(left.getCharacter(), right.getCharacter());
        });
        int caretLine = editor.getCurrentCaretLine();
        LspClient.Diagnostic selected = null;
        if (forward) {
            for (LspClient.Diagnostic diagnostic : diagnostics) {
                if (diagnostic.getLine() > caretLine) {
                    selected = diagnostic;
                    break;
                }
            }
            if (selected == null) {
                selected = diagnostics.get(0);
            }
        } else {
            for (int i = diagnostics.size() - 1; i >= 0; i--) {
                LspClient.Diagnostic diagnostic = diagnostics.get(i);
                if (diagnostic.getLine() < caretLine) {
                    selected = diagnostic;
                    break;
                }
            }
            if (selected == null) {
                selected = diagnostics.get(diagnostics.size() - 1);
            }
        }
        if (selected == null) {
            return "No diagnostics";
        }
        try {
            int line = Math.max(0, Math.min(selected.getLine(), editor.writingArea.getLineCount() - 1));
            int start = editor.writingArea.getLineStartOffset(line);
            int target = Math.min(start + Math.max(0, selected.getCharacter()), editor.writingArea.getText().length());
            editor.writingArea.setCaretPosition(target);
            return diagnosticSeverityLabel(selected.getSeverity()) + ": " + selected.getMessage();
        } catch (BadLocationException e) {
            return "Diagnostic jump failed: " + e.getMessage();
        }
    }


    List<QuickfixService.Entry> diagnosticsToQuickfixEntries(String filePath, List<LspClient.Diagnostic> diagnostics) {
        List<QuickfixService.Entry> entries = new ArrayList<>();
        if (filePath == null || diagnostics == null) {
            return entries;
        }
        for (LspClient.Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null) {
                continue;
            }
            entries.add(new QuickfixService.Entry(
                filePath,
                diagnostic.getLine() + 1,
                diagnostic.getCharacter() + 1,
                diagnostic.getMessage(),
                "diag-" + diagnosticSeverityLabel(diagnostic.getSeverity()).toLowerCase()
            ));
        }
        return entries;
    }


    String diagnosticSeverityLabel(int severity) {
        switch (severity) {
            case 1:
                return "Error";
            case 2:
                return "Warning";
            case 3:
                return "Info";
            case 4:
                return "Hint";
            default:
                return "Diag";
        }
    }


    String openLspLocation(LspClient.Location location, String label) {
        String targetPath = filePathFromUri(location.getUri());
        if (targetPath == null || targetPath.isBlank()) {
            return "LSP " + label + " target has unsupported URI";
        }
        try {
            File targetFile = new File(targetPath);
            if (!targetFile.exists()) {
                return "LSP " + label + " target missing: " + targetPath;
            }
            editor.recordJumpPosition();
            editor.openFile(targetFile);
            String lineResult = editor.gotoLine(location.getLine() + 1);
            if (lineResult.startsWith("Error") || lineResult.startsWith("Invalid")) {
                return lineResult;
            }
            int lineStart = editor.writingArea.getLineStartOffset(Math.max(0, location.getLine()));
            int target = Math.min(lineStart + Math.max(0, location.getCharacter()), editor.writingArea.getText().length());
            editor.writingArea.setCaretPosition(target);
            return "Opened " + label + " location";
        } catch (Exception e) {
            return "LSP " + label + " open failed: " + e.getMessage();
        }
    }

    WorkspaceEditApplyResult applyWorkspaceTextEdits(List<LspClient.TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return new WorkspaceEditApplyResult();
        }
        List<LspClient.WorkspaceEditOperation> operations = new ArrayList<>();
        for (LspClient.TextEdit edit : edits) {
            if (edit != null) {
                operations.add(LspClient.WorkspaceEditOperation.textEdit(edit));
            }
        }
        return applyWorkspaceOperations(operations);
    }


    LspClient.WorkspaceEditResponse applyWorkspaceEditFromServer(String label, List<LspClient.WorkspaceEditOperation> operations) {
        editor.showScratchBuffer("[lsp workspace edit review]", buildWorkspaceEditPreview(label, operations, new WorkspaceEditApplyResult())
            + "\nServer-originated workspace edits require review and were not applied.\n");
        return new LspClient.WorkspaceEditResponse(false, "workspace edit requires user review");
    }


    WorkspaceEditApplyResult applyWorkspaceOperations(List<LspClient.WorkspaceEditOperation> operations) {
        WorkspaceEditApplyResult result = new WorkspaceEditApplyResult();
        if (operations == null || operations.isEmpty()) {
            return result;
        }
        WorkspaceEditPlan plan = buildWorkspaceEditPlan(operations, result);
        if (plan == null || result.failedFiles > 0) {
            if (result.failureReason == null || result.failureReason.isBlank()) {
                result.failureReason = "workspace edit preflight failed";
            }
            return result;
        }
        WorkspaceEditTransaction transaction = new WorkspaceEditTransaction();
        try {
            List<StagedTextWrite> stagedWrites = prepareStagedTextWrites(plan.stagedTextByPath, transaction);
            applyResourceActions(plan.resourceActions, transaction);
            applyStagedTextWrites(stagedWrites, transaction);
            transaction.commit();
            result.appliedEditCount = plan.appliedEditCount;
            result.appliedResourceOperationCount = plan.resourceActions.size();
            result.touchedFiles = plan.touchedPaths.size();
        } catch (IOException | RuntimeException e) {
            transaction.rollbackQuietly();
            result.failedFiles++;
            result.failureReason = e.getMessage();
        } finally {
            transaction.cleanupTempsQuietly();
        }
        return result;
    }


    WorkspaceEditPlan buildWorkspaceEditPlan(List<LspClient.WorkspaceEditOperation> operations, WorkspaceEditApplyResult result) {
        WorkspaceEditPlan plan = new WorkspaceEditPlan();
        for (int i = 0; i < operations.size(); i++) {
            LspClient.WorkspaceEditOperation operation = operations.get(i);
            if (operation == null || operation.getKind() == null) {
                continue;
            }
            switch (operation.getKind()) {
                case TEXT_EDIT: {
                    List<LspClient.TextEdit> group = new ArrayList<>();
                    LspClient.TextEdit first = operation.getTextEdit();
                    if (first == null || first.getUri() == null || first.getUri().isBlank()) {
                        failWorkspacePreflight(result, "text edit missing uri");
                        return null;
                    }
                    group.add(first);
                    while (i + 1 < operations.size() && sameTextEditTarget(first, operations.get(i + 1))) {
                        i++;
                        group.add(operations.get(i).getTextEdit());
                    }
                    Path path = workspacePathFromUri(first.getUri());
                    if (path == null) {
                        failWorkspacePreflight(result, "text edit outside workspace: " + first.getUri());
                        return null;
                    }
                    if (hasWorkspaceEditVersionConflict(first.getUri(), group)) {
                        failWorkspacePreflight(result, "stale document version: " + first.getUri());
                        return null;
                    }
                    String pathKey = path.toString();
                    String currentText = plan.stagedTextByPath.containsKey(pathKey)
                        ? plan.stagedTextByPath.get(pathKey)
                        : readWorkspaceTextForEdit(path);
                    if (currentText == null) {
                        failWorkspacePreflight(result, "text edit target missing: " + path);
                        return null;
                    }
                    List<ResolvedTextEdit> resolved = resolveTextEdits(currentText, group);
                    if (resolved.isEmpty()) {
                        failWorkspacePreflight(result, "text edit target had no applicable edits: " + path);
                        return null;
                    }
                    plan.stagedTextByPath.put(pathKey, applyResolvedTextEdits(currentText, resolved));
                    plan.touchedPaths.add(pathKey);
                    plan.appliedEditCount += resolved.size();
                    break;
                }
                case CREATE: {
                    Path path = workspacePathFromUri(operation.getUri());
                    if (path == null) {
                        failWorkspacePreflight(result, "create outside workspace: " + operation.getUri());
                        return null;
                    }
                    String pathKey = path.toString();
                    boolean exists = Files.exists(path) || plan.stagedTextByPath.containsKey(pathKey);
                    if (Files.isDirectory(path)) {
                        failWorkspacePreflight(result, "create target is directory: " + path);
                        return null;
                    }
                    if (exists && !operation.isOverwrite() && !operation.isIgnoreIfExists()) {
                        failWorkspacePreflight(result, "create target exists: " + path);
                        return null;
                    }
                    if (!exists || operation.isOverwrite()) {
                        plan.stagedTextByPath.put(pathKey, "");
                        plan.resourceActions.add(ResourceAction.create(path, operation.isOverwrite()));
                        plan.touchedPaths.add(pathKey);
                    }
                    break;
                }
                case RENAME: {
                    Path oldPath = workspacePathFromUri(operation.getOldUri());
                    Path newPath = workspacePathFromUri(operation.getNewUri());
                    if (oldPath == null || newPath == null) {
                        failWorkspacePreflight(result, "rename outside workspace");
                        return null;
                    }
                    String oldKey = oldPath.toString();
                    String newKey = newPath.toString();
                    boolean oldExists = Files.exists(oldPath) || plan.stagedTextByPath.containsKey(oldKey);
                    boolean newExists = Files.exists(newPath) || plan.stagedTextByPath.containsKey(newKey);
                    if (!oldExists) {
                        failWorkspacePreflight(result, "rename source missing: " + oldPath);
                        return null;
                    }
                    if (newExists && !operation.isOverwrite() && !operation.isIgnoreIfExists()) {
                        failWorkspacePreflight(result, "rename target exists: " + newPath);
                        return null;
                    }
                    if (!newExists || operation.isOverwrite()) {
                        if (plan.stagedTextByPath.containsKey(oldKey)) {
                            plan.stagedTextByPath.put(newKey, plan.stagedTextByPath.remove(oldKey));
                        } else if (Files.isRegularFile(oldPath)) {
                            try {
                                plan.stagedTextByPath.put(newKey, Files.readString(oldPath, StandardCharsets.UTF_8));
                            } catch (IOException e) {
                                failWorkspacePreflight(result, "rename source unreadable: " + oldPath);
                                return null;
                            }
                        }
                        plan.resourceActions.add(ResourceAction.rename(oldPath, newPath, operation.isOverwrite()));
                        plan.touchedPaths.add(oldKey);
                        plan.touchedPaths.add(newKey);
                    }
                    break;
                }
                case DELETE: {
                    Path path = workspacePathFromUri(operation.getUri());
                    if (path == null) {
                        failWorkspacePreflight(result, "delete outside workspace: " + operation.getUri());
                        return null;
                    }
                    String pathKey = path.toString();
                    boolean exists = Files.exists(path) || plan.stagedTextByPath.containsKey(pathKey);
                    if (!exists && operation.isIgnoreIfNotExists()) {
                        break;
                    }
                    if (!exists) {
                        failWorkspacePreflight(result, "delete target missing: " + path);
                        return null;
                    }
                    if (Files.isDirectory(path) && !operation.isRecursive()) {
                        failWorkspacePreflight(result, "delete target is directory: " + path);
                        return null;
                    }
                    plan.stagedTextByPath.remove(pathKey);
                    plan.resourceActions.add(ResourceAction.delete(path, operation.isRecursive()));
                    plan.touchedPaths.add(pathKey);
                    break;
                }
            }
        }
        return plan;
    }


    private void failWorkspacePreflight(WorkspaceEditApplyResult result, String reason) {
        result.failedFiles++;
        result.failureReason = reason == null ? "workspace edit preflight failed" : reason;
    }


    private boolean sameTextEditTarget(LspClient.TextEdit first, LspClient.WorkspaceEditOperation operation) {
        if (first == null || operation == null || operation.getKind() != LspClient.WorkspaceEditOperation.Kind.TEXT_EDIT) {
            return false;
        }
        LspClient.TextEdit next = operation.getTextEdit();
        if (next == null) {
            return false;
        }
        return Objects.equals(first.getUri(), next.getUri())
            && Objects.equals(first.getDocumentVersion(), next.getDocumentVersion());
    }


    private String readWorkspaceTextForEdit(Path path) {
        if (editor != null) {
            FileBuffer buffer = editor.findBufferByPath(path.toFile());
            if (buffer != null) {
                return buffer == editor.getCurrentBuffer() ? editor.writingArea.getText() : buffer.getContent();
            }
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }


    private void applyResourceActions(List<ResourceAction> actions, WorkspaceEditTransaction transaction) throws IOException {
        for (ResourceAction action : actions) {
            switch (action.kind) {
                case CREATE:
                    createWorkspaceFile(action.path);
                    break;
                case RENAME:
                    renameWorkspaceFile(action.path, action.targetPath, action.overwrite, transaction);
                    break;
                case DELETE:
                    deleteWorkspacePath(action.path, action.recursive, transaction);
                    break;
            }
        }
    }


    private void createWorkspaceFile(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }


    private void renameWorkspaceFile(Path oldPath, Path newPath, boolean overwrite, WorkspaceEditTransaction transaction) throws IOException {
        FileBuffer buffer = editor.findBufferByPath(oldPath.toFile());
        Path parent = newPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path overwrittenBackup = null;
        if (overwrite && Files.exists(newPath)) {
            overwrittenBackup = moveToBackup(newPath);
            transaction.trackBackup(overwrittenBackup);
        }
        Path overwrittenBackupRef = overwrittenBackup;
        if (overwrittenBackupRef != null) {
            transaction.addRollback(() -> {
                if (Files.exists(overwrittenBackupRef)) {
                    movePath(overwrittenBackupRef, newPath, true);
                }
            });
        }
        movePath(oldPath, newPath, false);
        transaction.addRollback(() -> {
            movePath(newPath, oldPath, true);
            if (overwrittenBackupRef != null && Files.exists(overwrittenBackupRef)) {
                movePath(overwrittenBackupRef, newPath, true);
            }
            if (buffer != null) {
                buffer.retargetFile(oldPath.toFile());
            }
        });
        if (buffer != null) {
            buffer.retargetFile(newPath.toFile());
        }
    }


    private void deleteWorkspacePath(Path path, boolean recursive, WorkspaceEditTransaction transaction) throws IOException {
        FileBuffer buffer = editor.findBufferByPath(path.toFile());
        Path backup = moveToBackup(path);
        transaction.trackBackup(backup);
        transaction.addRollback(() -> movePath(backup, path, true));
        if (buffer != null) {
            transaction.addCommit(() -> removeDeletedBuffer(buffer));
        }
    }


    private List<StagedTextWrite> prepareStagedTextWrites(Map<String, String> stagedTextByPath, WorkspaceEditTransaction transaction) throws IOException {
        List<StagedTextWrite> writes = new ArrayList<>();
        for (Map.Entry<String, String> entry : stagedTextByPath.entrySet()) {
            Path path = Path.of(entry.getKey());
            Path temp = writeTempFileFor(path, entry.getValue());
            transaction.trackTemp(temp);
            writes.add(new StagedTextWrite(path, entry.getValue(), temp));
        }
        return writes;
    }


    private void applyStagedTextWrites(List<StagedTextWrite> writes, WorkspaceEditTransaction transaction) throws IOException {
        for (StagedTextWrite write : writes) {
            FileBuffer buffer = editor.findBufferByPath(write.path.toFile());
            if (buffer != null) {
                applyStagedTextToBuffer(buffer, write.text, transaction);
            } else {
                writeFileAtomically(write.path, write.tempPath, transaction);
            }
        }
    }


    private void applyStagedTextToBuffer(FileBuffer buffer, String text, WorkspaceEditTransaction transaction) {
        String oldText = buffer == editor.getCurrentBuffer() ? editor.writingArea.getText() : buffer.getContent();
        boolean oldModified = buffer.isModified();
        transaction.addRollback(() -> restoreBufferText(buffer, oldText, oldModified));
        if (buffer == editor.getCurrentBuffer()) {
            editor.writingArea.setText(text == null ? "" : text);
            editor.markModified();
        } else {
            buffer.setContent(text == null ? "" : text, true);
        }
    }


    private void restoreBufferText(FileBuffer buffer, String text, boolean modified) {
        if (buffer == editor.getCurrentBuffer()) {
            editor.writingArea.setText(text == null ? "" : text);
            buffer.setModified(modified);
            editor.updateStatusBar();
        } else {
            buffer.setContent(text == null ? "" : text, modified);
        }
    }


    private Path writeTempFileFor(Path path, String text) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent, "shed-lsp-", ".tmp");
        Files.writeString(temp, text == null ? "" : text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        return temp;
    }


    private void writeFileAtomically(Path path, Path temp, WorkspaceEditTransaction transaction) throws IOException {
        if (Files.isDirectory(path)) {
            throw new IOException("write target is directory: " + path);
        }
        Path backup = null;
        boolean existed = Files.exists(path);
        if (existed) {
            backup = moveToBackup(path);
            transaction.trackBackup(backup);
        }
        Path backupRef = backup;
        transaction.addRollback(() -> {
            Files.deleteIfExists(path);
            if (backupRef != null && Files.exists(backupRef)) {
                movePath(backupRef, path, true);
            }
        });
        movePath(temp, path, true);
    }


    private Path moveToBackup(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path backup = Files.isDirectory(path)
            ? Files.createTempDirectory(parent == null ? Path.of(".") : parent, "shed-lsp-backup-")
            : Files.createTempFile(parent == null ? Path.of(".") : parent, "shed-lsp-backup-", ".tmp");
        if (Files.exists(backup)) {
            deletePathRecursively(backup);
        }
        movePath(path, backup, true);
        return backup;
    }


    private void movePath(Path from, Path to, boolean replace) throws IOException {
        Path parent = to.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<StandardCopyOption> options = new ArrayList<>();
        if (replace) {
            options.add(StandardCopyOption.REPLACE_EXISTING);
        }
        options.add(StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(from, to, options.toArray(new StandardCopyOption[0]));
        } catch (AtomicMoveNotSupportedException e) {
            options.remove(StandardCopyOption.ATOMIC_MOVE);
            Files.move(from, to, options.toArray(new StandardCopyOption[0]));
        }
    }


    private void deletePathRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
                for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(candidate);
                }
            }
            return;
        }
        Files.deleteIfExists(path);
    }


    private void removeDeletedBuffer(FileBuffer buffer) {
        if (buffer == null) {
            return;
        }
        boolean wasCurrent = buffer == editor.getCurrentBuffer();
        editor.buffers.remove(buffer);
        if (wasCurrent && !editor.buffers.isEmpty()) {
            editor.switchToBuffer(Math.min(editor.currentBufferIndex, editor.buffers.size() - 1));
        } else if (editor.buffers.isEmpty()) {
            FileBuffer scratch = FileBuffer.createScratch("[No Name]", "");
            editor.buffers.add(scratch);
            editor.switchToBuffer(0);
        }
    }


    private Path workspacePathFromUri(String uri) {
        String filePath = filePathFromUri(uri);
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path candidate = Path.of(filePath).toAbsolutePath().normalize();
            Path root = editor == null ? resolveWorkspaceRoot(candidate.getParent()) : workspaceRootPath(editor.getCurrentBuffer());
            if (!candidate.startsWith(root)) {
                return null;
            }
            if (Files.exists(candidate)) {
                Path real = candidate.toRealPath();
                if (!real.startsWith(root)) {
                    return null;
                }
            }
            return candidate;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }


    private Path workspaceRootPath(FileBuffer buffer) throws IOException {
        if (buffer != null && buffer.hasFilePath()) {
            File file = new File(buffer.getFilePath());
            return resolveWorkspaceRoot(file.isDirectory() ? file.toPath() : file.toPath().getParent());
        }
        return resolveWorkspaceRoot(new File(".").getCanonicalFile().toPath());
    }

    Path resolveWorkspaceRoot(Path start) throws IOException {
        Path current = start == null
            ? new File(".").getCanonicalFile().toPath()
            : start.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        Path configured = editor == null || editor.workspaceController == null ? null
            : WorkspaceRootResolver.configuredRoot(current, editor.workspaceController.roots());
        if (configured != null) {
            return configured.toRealPath();
        }
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))
                || Files.exists(candidate.resolve("pom.xml"))
                || Files.exists(candidate.resolve("package.json"))
                || Files.exists(candidate.resolve("Makefile"))) {
                return candidate.toRealPath();
            }
        }
        return Files.exists(current) ? current.toRealPath() : current;
    }


    boolean hasResourceOperation(List<LspClient.WorkspaceEditOperation> operations) {
        if (operations == null) {
            return false;
        }
        for (LspClient.WorkspaceEditOperation operation : operations) {
            if (operation != null && operation.getKind() != LspClient.WorkspaceEditOperation.Kind.TEXT_EDIT) {
                return true;
            }
        }
        return false;
    }


    String buildWorkspaceEditPreview(String label, List<LspClient.WorkspaceEditOperation> operations, WorkspaceEditApplyResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("LSP Workspace Edit\n");
        builder.append("=".repeat(40)).append("\n\n");
        if (label != null && !label.isBlank()) {
            builder.append("Label: ").append(label).append("\n");
        }
        builder.append("Applied text edits: ").append(result == null ? 0 : result.appliedEditCount).append("\n");
        builder.append("Applied resource ops: ").append(result == null ? 0 : result.appliedResourceOperationCount).append("\n");
        if (result != null && result.failureReason != null && !result.failureReason.isBlank()) {
            builder.append("Failure: ").append(result.failureReason).append("\n");
        }
        builder.append("\n");
        if (operations != null) {
            for (LspClient.WorkspaceEditOperation operation : operations) {
                if (operation == null) {
                    continue;
                }
                switch (operation.getKind()) {
                    case CREATE:
                        builder.append("create ").append(filePathFromUri(operation.getUri())).append("\n");
                        break;
                    case RENAME:
                        builder.append("rename ").append(filePathFromUri(operation.getOldUri())).append(" -> ").append(filePathFromUri(operation.getNewUri())).append("\n");
                        break;
                    case DELETE:
                        builder.append("delete ").append(filePathFromUri(operation.getUri())).append("\n");
                        break;
                    case TEXT_EDIT:
                        LspClient.TextEdit edit = operation.getTextEdit();
                        if (edit != null) {
                            builder.append("edit ").append(filePathFromUri(edit.getUri())).append(":")
                                .append(edit.getStartLine() + 1).append(":").append(edit.getStartCharacter() + 1)
                                .append(" -> ").append(editor.safePreviewText(edit.getNewText(), 60)).append("\n");
                        }
                        break;
                }
            }
        }
        return builder.toString();
    }


    boolean hasWorkspaceEditVersionConflict(String uri, List<LspClient.TextEdit> edits) {
        if (uri == null || edits == null || edits.isEmpty()) {
            return false;
        }
        if (editor == null || editor.lspDocumentVersions == null) {
            return false;
        }
        Integer currentVersion = editor.lspDocumentVersions.get(uri);
        if (currentVersion == null) {
            return false;
        }
        for (LspClient.TextEdit edit : edits) {
            Integer expectedVersion = edit == null ? null : edit.getDocumentVersion();
            if (expectedVersion != null && !expectedVersion.equals(currentVersion)) {
                return true;
            }
        }
        return false;
    }


    int applyTextEditsToCurrentArea(List<LspClient.TextEdit> edits) {
        List<ResolvedTextEdit> resolved = resolveTextEdits(editor.writingArea.getText(), edits);
        if (resolved.isEmpty()) {
            return 0;
        }
        for (ResolvedTextEdit edit : resolved) {
            editor.writingArea.replaceRange(edit.newText, edit.startOffset, edit.endOffset);
        }
        editor.markModified();
        return resolved.size();
    }


    int applyTextEditsToBuffer(FileBuffer buffer, List<LspClient.TextEdit> edits) {
        if (buffer == null) {
            return 0;
        }
        String currentText = buffer == editor.getCurrentBuffer() ? editor.writingArea.getText() : buffer.getContent();
        List<ResolvedTextEdit> resolved = resolveTextEdits(currentText, edits);
        if (resolved.isEmpty()) {
            return 0;
        }
        String updated = applyResolvedTextEdits(currentText, resolved);
        if (buffer == editor.getCurrentBuffer()) {
            editor.writingArea.setText(updated);
            editor.markModified();
        } else {
            buffer.setContent(updated, true);
        }
        return resolved.size();
    }


    int applyTextEditsToFile(String filePath, List<LspClient.TextEdit> edits) {
        try {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                return 0;
            }
            String currentText = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            List<ResolvedTextEdit> resolved = resolveTextEdits(currentText, edits);
            if (resolved.isEmpty()) {
                return 0;
            }
            String updated = applyResolvedTextEdits(currentText, resolved);
            Files.writeString(file.toPath(), updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            return resolved.size();
        } catch (IOException e) {
            return 0;
        }
    }


    String applyResolvedTextEdits(String text, List<ResolvedTextEdit> resolvedEdits) {
        StringBuilder builder = new StringBuilder(text == null ? "" : text);
        for (ResolvedTextEdit edit : resolvedEdits) {
            int safeStart = Math.max(0, Math.min(edit.startOffset, builder.length()));
            int safeEnd = Math.max(safeStart, Math.min(edit.endOffset, builder.length()));
            builder.replace(safeStart, safeEnd, edit.newText);
        }
        return builder.toString();
    }


    List<ResolvedTextEdit> resolveTextEdits(String text, List<LspClient.TextEdit> edits) {
        List<ResolvedTextEdit> resolved = new ArrayList<>();
        if (edits == null || edits.isEmpty()) {
            return resolved;
        }
        String source = text == null ? "" : text;
        List<Integer> lineStarts = lineStartOffsets(source);
        for (int i = 0; i < edits.size(); i++) {
            LspClient.TextEdit edit = edits.get(i);
            if (edit == null) {
                continue;
            }
            int startOffset = offsetForLineCharacter(source, lineStarts, edit.getStartLine(), edit.getStartCharacter());
            int endOffset = offsetForLineCharacter(source, lineStarts, edit.getEndLine(), edit.getEndCharacter());
            if (endOffset < startOffset) {
                int swap = startOffset;
                startOffset = endOffset;
                endOffset = swap;
            }
            resolved.add(new ResolvedTextEdit(startOffset, endOffset, edit.getNewText(), i));
        }
        resolved.sort((left, right) -> {
            if (left.startOffset != right.startOffset) {
                return Integer.compare(right.startOffset, left.startOffset);
            }
            if (left.endOffset != right.endOffset) {
                return Integer.compare(right.endOffset, left.endOffset);
            }
            return Integer.compare(right.order, left.order);
        });
        return resolved;
    }


    List<Integer> lineStartOffsets(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts;
    }


    int offsetForLineCharacter(String text, List<Integer> lineStarts, int line, int character) {
        if (lineStarts == null || lineStarts.isEmpty()) {
            return 0;
        }
        int safeLine = Math.max(0, Math.min(line, lineStarts.size() - 1));
        int lineStart = lineStarts.get(safeLine);
        int lineEnd = safeLine + 1 < lineStarts.size() ? lineStarts.get(safeLine + 1) - 1 : text.length();
        int safeCharacter = Math.max(0, character);
        return Math.max(0, Math.min(lineStart + safeCharacter, lineEnd));
    }


    String filePathFromUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        if (editor != null && editor.remoteWorkspaceController != null) {
            Path remotePath = editor.remoteWorkspaceController.localPathForRemoteLanguageServerUri(uri);
            if (remotePath != null) return remotePath.toFile().getAbsolutePath();
        }
        if (!uri.startsWith("file:")) {
            return null;
        }
        try {
            return Path.of(new URI(uri)).toFile().getAbsolutePath();
        } catch (IllegalArgumentException | URISyntaxException e) {
            return uri.substring("file://".length());
        }
    }


    String currentCompletionPrefix() {
        String text = editor.writingArea.getText();
        int caret = Math.min(editor.writingArea.getCaretPosition(), text.length());
        int start = caret;
        while (start > 0 && editor.isWordCharacter(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, caret);
    }


    List<String> collectBufferCompletions(String prefix) {
        List<String> matches = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) {
            return matches;
        }

        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        String text = editor.writingArea.getText();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (editor.isWordCharacter(c)) {
                word.append(c);
            } else if (!word.isEmpty()) {
                addCompletionCandidate(prefix, unique, word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty()) {
            addCompletionCandidate(prefix, unique, word.toString());
        }

        for (String candidate : unique) {
            matches.add(candidate);
            if (matches.size() >= 10) {
                break;
            }
        }
        return matches;
    }


    void addCompletionCandidate(String prefix, java.util.LinkedHashSet<String> unique, String candidate) {
        if (candidate.length() <= prefix.length()) {
            return;
        }
        if (candidate.startsWith(prefix)) {
            unique.add(candidate);
        }
    }


    void applyCompletion(String prefix, String completion) {
        int caret = editor.writingArea.getCaretPosition();
        int start = Math.max(0, caret - (prefix == null ? 0 : prefix.length()));
        editor.writingArea.replaceRange(completion, start, caret);
        editor.writingArea.setCaretPosition(start + completion.length());
        editor.markModified();
    }

    private String lspErrorFor(FileBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        String extension = bufferExtension(buffer);
        try {
            return editor.lspErrors.get(new LspServerKey(extension, workspaceRootPath(buffer)));
        } catch (IOException ignored) {
            return editor.lspErrors.get(new LspServerKey(extension, null));
        }
    }

    String completionUnavailableReason(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return "buffer is not file-backed";
        }
        LspClient client = existingLspClient(buffer);
        if (client != null && client.isAlive() && client.supports(LspCapability.COMPLETION)) {
            return null;
        }
        String error = lspErrorFor(buffer);
        if (error != null && !error.isBlank()) {
            return error;
        }
        return client == null ? "language server is not configured" : client.capabilityUnavailableReason(LspCapability.COMPLETION);
    }

    private int stopServersForExtension(String extension) {
        List<LspServerKey> keys = new ArrayList<>();
        for (LspServerKey key : editor.lspClients.keySet()) {
            if (key.extension().equalsIgnoreCase(extension)) {
                keys.add(key);
            }
        }
        for (LspServerKey key : keys) {
            LspClient client = editor.lspClients.remove(key);
            if (client != null) client.stop();
            remoteLspEndpoints.remove(key);
        }
        return keys.size();
    }

    void stopServersForWorkspace(Path workspace) {
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (root == null) return;
        List<LspServerKey> keys = new ArrayList<>();
        for (LspServerKey key : editor.lspClients.keySet()) {
            Path serverRoot = key.workspaceRoot();
            if (serverRoot != null && serverRoot.startsWith(root)) keys.add(key);
        }
        for (LspServerKey key : keys) {
            LspClient client = editor.lspClients.remove(key);
            if (client != null) client.stop();
            remoteLspEndpoints.remove(key);
        }
        editor.lspErrors.entrySet().removeIf(entry -> entry.getKey().workspaceRoot() != null && entry.getKey().workspaceRoot().startsWith(root));
        for (FileBuffer buffer : editor.buffers) {
            if (buffer == null || !buffer.hasFilePath()) continue;
            Path path = new File(buffer.getFilePath()).toPath().toAbsolutePath().normalize();
            if (!path.startsWith(root)) continue;
            String uri = bufferUri(buffer);
            editor.lspDocumentVersions.remove(uri);
            documentSyncStates.remove(uri);
        }
    }

    private void removeErrorsForExtension(String extension) {
        editor.lspErrors.entrySet().removeIf(entry -> entry.getKey().extension().equalsIgnoreCase(extension));
    }

    private boolean hasExtension(String uri, String extension) {
        int dot = uri == null ? -1 : uri.lastIndexOf('.');
        return dot >= 0 && uri.substring(dot + 1).equalsIgnoreCase(extension);
    }


    LspClient resolveLspClient(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return null;
        }
        String extension = bufferExtension(buffer);
        if (extension.isEmpty()) {
            editor.lspErrors.put(new LspServerKey("", null), "file has no recognized extension");
            return null;
        }

        Path workspaceRoot;
        try {
            workspaceRoot = workspaceRootPath(buffer);
        } catch (IOException e) {
            editor.lspErrors.put(new LspServerKey(extension, null), e.getMessage());
            return null;
        }
        LspServerKey key = new LspServerKey(extension, workspaceRoot);

        LspClient existing = editor.lspClients.get(key);
        if (existing != null && existing.isAlive()) {
            editor.lspErrors.remove(key);
            return existing;
        }
        if (existing != null) {
            existing.stop();
            editor.lspClients.remove(key);
            remoteLspEndpoints.remove(key);
        }

        String command = editor.configManager.getLspCommand(extension);
        String[] args = editor.configManager.getLspArgs(extension);
        boolean userConfigured = command != null && !command.isBlank();
        if (command == null || command.isBlank()) {
            String[] contributed = contributedLspCommand(extension);
            if (contributed != null && contributed.length == 0) {
                editor.lspErrors.put(key, "extension language for ." + extension + " does not provide an LSP command");
                return null;
            }
            String[] builtin = contributed == null ? builtinLspCommand(extension) : contributed;
            if (builtin == null || builtin.length == 0) {
                editor.lspErrors.put(key, "no server configured for ." + extension);
                return null;
            }
            command = builtin[0];
            args = java.util.Arrays.copyOfRange(builtin, 1, builtin.length);
        }

        try {
            List<String> invocation = new ArrayList<>();
            invocation.add(command);
            if (args != null) java.util.Collections.addAll(invocation, args);
            RemoteLspEndpoint remote = null;
            if (editor.configManager.getRemoteLspEnabled() && editor.remoteWorkspaceController != null
                && editor.remoteWorkspaceController.remoteLanguageServerUri(workspaceRoot) != null) {
                if (!userConfigured) {
                    editor.lspErrors.put(key, "remote LSP requires an explicit global lsp." + extension + ".command configured for the remote environment");
                    return null;
                }
                remote = editor.remoteWorkspaceController.languageServerEndpoint(workspaceRoot, invocation);
                if (remote == null) {
                    editor.lspErrors.put(key, "the selected remote workspace does not support remote language servers");
                    return null;
                }
            }
            LspClient client = remote == null
                ? new LspClient(command, args, workspaceRoot, editor.configManager.getLspFeatureSettings())
                : new LspClient(remote.command(), workspaceRoot, remote.rootUri(), editor.configManager.getLspFeatureSettings());
            client.setWorkspaceEditHandler(this::applyWorkspaceEditFromServer);
            client.setDiagnosticsChangedHandler(() -> SwingUtilities.invokeLater(() -> {
                scheduleDiagnosticRefresh();
                editor.problemsController.diagnosticsChanged();
            }));
            editor.lspClients.put(key, client);
            if (remote == null) remoteLspEndpoints.remove(key);
            else remoteLspEndpoints.put(key, remote);
            editor.lspErrors.remove(key);
            return client;
        } catch (IOException e) {
            remoteLspEndpoints.remove(key);
            editor.lspErrors.put(key, e.getMessage());
            return null;
        }
    }


    void syncLspOpen(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return;
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return;
        }
        String uri = bufferUri(buffer);
        if (editor.lspDocumentVersions.containsKey(uri)) {
            return;
        }
        VersionedTextSnapshot content = buffer.textSnapshot();
        client.didOpen(uri, languageId(buffer), content.text());
        editor.lspDocumentVersions.put(uri, 1);
        documentSyncStates.put(uri, new LspDocumentSyncState(content));
        scheduleLspDecorations(buffer);
    }


    void syncLspChange(FileBuffer buffer) {
        syncLspChange(buffer, (FileBuffer.DocumentTextChange) null);
    }

    void syncLspChange(FileBuffer buffer, int offset, int removedLength, String insertedText) {
        syncLspChange(buffer, new FileBuffer.DocumentTextChange(buffer == null ? null : buffer.textSnapshot(),
            buffer == null ? null : buffer.textSnapshot(), offset, removedLength, insertedText == null ? "" : insertedText, false));
    }

    void syncLspChange(FileBuffer buffer, FileBuffer.DocumentTextChange textChange) {
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return;
        }
        String uri = bufferUri(buffer);
        boolean wasOpen = editor.lspDocumentVersions.containsKey(uri);
        syncLspOpen(buffer);
        if (!wasOpen) {
            return;
        }
        LspDocumentSyncState syncState = documentSyncStates.computeIfAbsent(uri, ignored -> new LspDocumentSyncState(buffer.textSnapshot()));
        if (textChange != null) {
            syncState.apply(textChange);
        } else {
            syncState.reconcile(buffer.textSnapshot());
        }
        clearLspDecorations();
        pendingLspChange = buffer;
        if (lspChangeTimer == null) {
            lspChangeTimer = new Timer(CHANGE_DEBOUNCE_MS, event -> flushPendingLspChange(pendingLspChange));
            lspChangeTimer.setRepeats(false);
        }
        lspChangeTimer.restart();
    }

    void flushPendingLspChange(FileBuffer buffer) {
        if (buffer == null || pendingLspChange != buffer) {
            return;
        }
        if (lspChangeTimer != null) {
            lspChangeTimer.stop();
        }
        pendingLspChange = null;
        sendLspChange(buffer);
    }

    void shutdown() {
        if (lspChangeTimer != null) {
            lspChangeTimer.stop();
        }
        if (lspDecorationTimer != null) {
            lspDecorationTimer.stop();
        }
        if (lspDecorationJobId >= 0) {
            editor.asyncJobService.cancel(lspDecorationJobId);
            lspDecorationJobId = -1;
        }
        pendingLspChange = null;
        pendingLspDecoration = null;
    }

    private void sendLspChange(FileBuffer buffer) {
        long started = System.nanoTime();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return;
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return;
        }
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        LspDocumentSyncState syncState = documentSyncStates.computeIfAbsent(uri, ignored -> new LspDocumentSyncState(buffer.textSnapshot()));
        if (!syncState.hasPendingChanges()) {
            scheduleLspDecorations(buffer);
            return;
        }
        int version = editor.lspDocumentVersions.getOrDefault(uri, 1) + 1;
        editor.lspDocumentVersions.put(uri, version);
        List<LspDocumentChange> changes = syncState.drainChanges();
        String content = client.documentSyncKind() == LspClient.DocumentSyncKind.INCREMENTAL && !changes.isEmpty()
            ? null : syncState.text();
        client.didChange(uri, version, changes, content);
        scheduleDiagnosticRefresh();
        scheduleLspDecorations(buffer);
        if (editor.perfService != null) {
            editor.perfService.recordDuration("lsp.change", started, "chars=" + syncState.length());
        }
    }


    void scheduleDiagnosticRefresh() {
        if (editor.diagnosticRefreshTimer == null) {
            editor.diagnosticRefreshTimer = new javax.swing.Timer(500, ev -> editor.refreshDiagnosticRanges());
            editor.diagnosticRefreshTimer.setRepeats(false);
        }
        editor.diagnosticRefreshTimer.restart();
    }

    void refreshLspDecorations() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            clearLspDecorations();
            return;
        }
        if (!editor.configManager.getLspSemanticTokensInline() && !editor.configManager.getLspInlayHintsInline()) {
            clearLspDecorations();
            return;
        }
        scheduleLspDecorations(buffer);
    }

    private void scheduleLspDecorations(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return;
        }
        if (!editor.configManager.getLspSemanticTokensInline() && !editor.configManager.getLspInlayHintsInline()) {
            if (buffer == editor.getCurrentBuffer()) clearLspDecorations();
            return;
        }
        pendingLspDecoration = buffer;
        if (lspDecorationTimer == null) {
            lspDecorationTimer = new Timer(DECORATION_DEBOUNCE_MS, event -> requestLspDecorations(pendingLspDecoration));
            lspDecorationTimer.setRepeats(false);
        }
        lspDecorationTimer.restart();
    }

    private void requestLspDecorations(FileBuffer buffer) {
        pendingLspDecoration = null;
        if (buffer == null || buffer != editor.getCurrentBuffer() || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return;
        }
        LspClient client = existingLspClient(buffer);
        String uri = bufferUri(buffer);
        Integer version = editor.lspDocumentVersions.get(uri);
        if (client == null || version == null || !client.isAlive()) {
            clearLspDecorations();
            return;
        }
        boolean semanticEnabled = editor.configManager.getLspSemanticTokensInline() && client.supports(LspCapability.SEMANTIC_TOKENS);
        boolean inlayEnabled = editor.configManager.getLspInlayHintsInline() && client.supports(LspCapability.INLAY_HINTS);
        if (!semanticEnabled && !inlayEnabled) {
            clearLspDecorations();
            return;
        }
        VersionedTextSnapshot text = buffer.textSnapshot();
        VersionedTextSnapshot.Position textEnd = text.positionAt(text.length());
        LspPosition end = new LspPosition(textEnd.line(), textEnd.character());
        if (lspDecorationJobId >= 0) editor.asyncJobService.cancel(lspDecorationJobId);
        LspDecorationRequest request = new LspDecorationRequest(buffer, client, uri, version, text, semanticEnabled, inlayEnabled, end);
        lspDecorationJobId = editor.asyncJobService.submit("LSP decorations", token -> {
            List<LspClient.SemanticToken> semanticTokens = request.semanticEnabled()
                ? request.client().semanticTokens(request.uri()) : List.of();
            if (token.isCancelled()) return null;
            List<LspClient.InlayHint> inlayHints = request.inlayEnabled()
                ? request.client().inlayHints(request.uri(), request.end().line(), request.end().character()) : List.of();
            return new LspDecorationResult(request, semanticTokens, inlayHints);
        }, (snapshot, result, error) -> applyLspDecorations(snapshot, result, error));
    }

    private void applyLspDecorations(AsyncJobService.JobSnapshot snapshot, LspDecorationResult result, Exception error) {
        if (snapshot == null || snapshot.getStatus() != AsyncJobService.Status.SUCCEEDED || result == null || error != null) {
            return;
        }
        lspDecorationJobId = -1;
        LspDecorationRequest request = result.request();
        if (request.buffer() != editor.getCurrentBuffer() || request.text() != request.buffer().textSnapshot()
            || !Objects.equals(request.version(), editor.lspDocumentVersions.get(request.uri())) || existingLspClient(request.buffer()) != request.client()) {
            return;
        }
        editor.lspSemanticSpans.clear();
        if (request.semanticEnabled()) {
            for (LspClient.SemanticToken token : result.semanticTokens()) {
                Color color = semanticTokenColor(request.client().semanticTokenTypeName(token.type()));
                int start = offsetForPosition(request.text(), token.line(), token.character());
                int end = Math.min(request.text().length(), start + Math.max(0, token.length()));
                if (color != null && end > start) editor.lspSemanticSpans.add(new SyntaxSpan(start, end, color));
            }
            editor.lspSemanticSpans.sort(Comparator.comparingInt(span -> span.start));
        }
        editor.lspInlayHintOverlays.clear();
        if (request.inlayEnabled()) {
            Map<Integer, StringBuilder> labelsByOffset = new TreeMap<>();
            for (LspClient.InlayHint hint : result.inlayHints()) {
                int offset = offsetForPosition(request.text(), hint.line(), hint.character());
                if (offset > request.text().length()) continue;
                String label = hint.label().replace('\n', ' ').replace('\r', ' ').strip();
                if (label.isEmpty()) continue;
                StringBuilder labels = labelsByOffset.computeIfAbsent(offset, ignored -> new StringBuilder());
                if (!labels.isEmpty()) labels.append(' ');
                labels.append(label);
                if (labelsByOffset.size() >= MAX_INLINE_INLAY_HINTS) break;
            }
            for (Map.Entry<Integer, StringBuilder> entry : labelsByOffset.entrySet()) {
                editor.lspInlayHintOverlays.add(new LspInlayHintOverlay(entry.getKey(), entry.getValue().toString()));
            }
        }
        editor.writingArea.repaint();
    }

    private void clearLspDecorations() {
        if (editor.lspSemanticSpans.isEmpty() && editor.lspInlayHintOverlays.isEmpty()) return;
        editor.lspSemanticSpans.clear();
        editor.lspInlayHintOverlays.clear();
        if (editor.writingArea != null) editor.writingArea.repaint();
    }

    private Color semanticTokenColor(String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "namespace", "type", "class", "enum", "interface", "struct", "typeparameter" -> editor.configManager.getSyntaxTypeColor();
            case "function", "method" -> editor.configManager.getSyntaxFunctionColor();
            case "macro", "keyword", "modifier" -> editor.configManager.getSyntaxKeywordColor();
            case "comment" -> editor.configManager.getSyntaxCommentColor();
            case "string", "regexp" -> editor.configManager.getSyntaxStringColor();
            case "number" -> editor.configManager.getSyntaxNumberColor();
            default -> null;
        };
    }

    private static int offsetForPosition(VersionedTextSnapshot text, int line, int character) {
        if (text == null || line < 0 || character < 0) return 0;
        return text.offsetAt(line, character);
    }

    private record LspPosition(int line, int character) { }

    private record LspDecorationRequest(FileBuffer buffer, LspClient client, String uri, Integer version, VersionedTextSnapshot text,
                                        boolean semanticEnabled, boolean inlayEnabled, LspPosition end) { }

    private record LspDecorationResult(LspDecorationRequest request, List<LspClient.SemanticToken> semanticTokens,
                                       List<LspClient.InlayHint> inlayHints) { }


    public void notifyCurrentBufferSaved() {
        notifyBufferSaved(editor.getCurrentBuffer());
    }

    public void notifyBufferSaved(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return;
        }
        editor.invalidateGitBlame(buffer);
        flushPendingLspChange(buffer);
        syncLspOpen(buffer);
        LspClient client = resolveLspClient(buffer);
        if (client != null) {
            client.didSave(bufferUri(buffer));
        }
        editor.firePluginEvent("BufWrite");
        editor.refreshGitGutter();
    }


    void pollLspNotifications(FileBuffer buffer) {
        LspClient client = existingLspClient(buffer);
        if (client != null) {
            client.drainNotifications();
        }
    }


    LspClient existingLspClient(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return null;
        }
        try {
            return editor.lspClients.get(new LspServerKey(bufferExtension(buffer), workspaceRootPath(buffer)));
        } catch (IOException ignored) {
            return null;
        }
    }


    String bufferUri(FileBuffer buffer) {
        Path path = new File(buffer.getFilePath()).toPath().toAbsolutePath().normalize();
        RemoteLspEndpoint endpoint = remoteLspEndpointFor(buffer);
        if (endpoint != null) {
            String remoteUri = endpoint.uriFor(path);
            if (remoteUri != null) return remoteUri;
        }
        return path.toUri().toString();
    }

    private RemoteLspEndpoint remoteLspEndpointFor(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) return null;
        try {
            return remoteLspEndpoints.get(new LspServerKey(bufferExtension(buffer), workspaceRootPath(buffer)));
        } catch (IOException ignored) {
            return null;
        }
    }


    String languageId(FileBuffer buffer) {
        ExtensionRegistry.Owned<shed.api.LanguageContribution> contribution = extensionLanguage(bufferExtension(buffer));
        if (contribution != null) return contribution.value().id();
        return editor.lspService.languageId(buffer.getFileType());
    }


    String bufferExtension(FileBuffer buffer) {
        String path = buffer.getFilePath();
        if (path == null) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot >= path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase();
    }


    String[] builtinLspCommand(String extension) {
        return editor.lspService.builtinCommand(extension);
    }

    private String[] contributedLspCommand(String extension) {
        ExtensionRegistry.Owned<shed.api.LanguageContribution> contribution = extensionLanguage(extension);
        if (contribution == null) return null;
        List<String> command = contribution.value().serverCommand();
        if (command.isEmpty()) return new String[0];
        List<String> parts = new ArrayList<>(command);
        parts.addAll(contribution.value().serverArguments());
        return parts.toArray(String[]::new);
    }

    private ExtensionRegistry.Owned<shed.api.LanguageContribution> extensionLanguage(String extension) {
        return editor.extensionManager == null ? null : editor.extensionManager.languageForExtension(extension);
    }


    static final class WorkspaceEditPlan {
        final Map<String, String> stagedTextByPath = new LinkedHashMap<>();
        final List<ResourceAction> resourceActions = new ArrayList<>();
        final Set<String> touchedPaths = new LinkedHashSet<>();
        int appliedEditCount;
    }


    interface WorkspaceEditIoAction {
        void run() throws IOException;
    }


    static final class StagedTextWrite {
        final Path path;
        final String text;
        final Path tempPath;

        StagedTextWrite(Path path, String text, Path tempPath) {
            this.path = path;
            this.text = text;
            this.tempPath = tempPath;
        }
    }


    final class WorkspaceEditTransaction {
        private final List<WorkspaceEditIoAction> rollbackActions = new ArrayList<>();
        private final List<WorkspaceEditIoAction> commitActions = new ArrayList<>();
        private final List<Path> temps = new ArrayList<>();
        private final List<Path> backups = new ArrayList<>();
        private boolean committed;

        void addRollback(WorkspaceEditIoAction action) {
            if (action != null) {
                rollbackActions.add(action);
            }
        }

        void addCommit(WorkspaceEditIoAction action) {
            if (action != null) {
                commitActions.add(action);
            }
        }

        void trackTemp(Path path) {
            if (path != null) {
                temps.add(path);
            }
        }

        void trackBackup(Path path) {
            if (path != null) {
                backups.add(path);
            }
        }

        void commit() throws IOException {
            for (WorkspaceEditIoAction action : commitActions) {
                action.run();
            }
            committed = true;
        }

        void rollbackQuietly() {
            if (committed) {
                return;
            }
            for (int i = rollbackActions.size() - 1; i >= 0; i--) {
                try {
                    rollbackActions.get(i).run();
                } catch (IOException ignored) {
                }
            }
        }

        void cleanupTempsQuietly() {
            for (Path temp : temps) {
                try {
                    deletePathRecursively(temp);
                } catch (IOException ignored) {
                }
            }
            if (committed) {
                for (Path backup : backups) {
                    try {
                        deletePathRecursively(backup);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }


    static final class ResourceAction {
        enum Kind {
            CREATE,
            RENAME,
            DELETE
        }

        final Kind kind;
        final Path path;
        final Path targetPath;
        final boolean overwrite;
        final boolean recursive;

        private ResourceAction(Kind kind, Path path, Path targetPath, boolean overwrite, boolean recursive) {
            this.kind = kind;
            this.path = path;
            this.targetPath = targetPath;
            this.overwrite = overwrite;
            this.recursive = recursive;
        }

        static ResourceAction create(Path path, boolean overwrite) {
            return new ResourceAction(Kind.CREATE, path, null, overwrite, false);
        }

        static ResourceAction rename(Path oldPath, Path newPath, boolean overwrite) {
            return new ResourceAction(Kind.RENAME, oldPath, newPath, overwrite, false);
        }

        static ResourceAction delete(Path path, boolean recursive) {
            return new ResourceAction(Kind.DELETE, path, null, false, recursive);
        }
    }

}
