package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
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
    private final Texteditor editor;

    LspController(Texteditor editor) {
        this.editor = editor;
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
            String extension = bufferExtension(buffer);
            fallbackReason = editor.lspErrors.get(extension);
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
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "help".equals(trimmed)) {
            return "Usage: :lsp completion|definition|hover|semantic|inlay|references|rename <newName>|renameapply|renamecancel|codeaction [index]";
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
            case "hover":
                return lspHover();
            case "semantic":
            case "semantictokens":
                return lspSemanticTokens();
            case "inlay":
            case "inlayhints":
                return lspInlayHints();
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
        for (Map.Entry<String, LspClient> entry : editor.lspClients.entrySet()) {
            String ext = entry.getKey();
            LspClient client = entry.getValue();
            sb.append("  .").append(ext).append("  ");
            sb.append(client.isAlive() ? "running" : "stopped");
            Path root = editor.lspClientRoots.get(ext);
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
            for (Map.Entry<String, String> entry : editor.lspErrors.entrySet()) {
                String ext = entry.getKey().isEmpty() ? "(no ext)" : "." + entry.getKey();
                sb.append("  ").append(ext).append(": ").append(entry.getValue()).append("\n");
            }
        }
        editor.showScratchBuffer("[lsp status]", sb.toString());
        return "Showing LSP status";
    }


    public String lspRestart(String ext) {
        String extension = ext.isEmpty() ? currentBufferExtension() : ext.replace(".", "").toLowerCase();
        if (extension.isEmpty()) return "No extension specified and no file open";
        LspClient existing = editor.lspClients.remove(extension);
        editor.lspClientRoots.remove(extension);
        if (existing != null) existing.stop();
        editor.lspErrors.remove(extension);
        // remove document versions for this extension so didOpen fires again
        editor.lspDocumentVersions.entrySet().removeIf(e -> {
            String uri = e.getKey();
            int dot = uri.lastIndexOf('.');
            return dot >= 0 && uri.substring(dot + 1).equalsIgnoreCase(extension);
        });
        FileBuffer buf = editor.getCurrentBuffer();
        if (buf != null && bufferExtension(buf).equals(extension)) {
            LspClient client = resolveLspClient(buf);
            if (client != null) return "Restarted LSP for ." + extension;
            return "Failed to restart LSP for ." + extension;
        }
        return "Stopped LSP for ." + extension + " (will restart on next use)";
    }


    public String lspStop(String ext) {
        String extension = ext.isEmpty() ? currentBufferExtension() : ext.replace(".", "").toLowerCase();
        if (extension.isEmpty()) return "No extension specified and no file open";
        LspClient existing = editor.lspClients.remove(extension);
        editor.lspClientRoots.remove(extension);
        if (existing == null) return "No LSP server running for ." + extension;
        existing.stop();
        editor.lspErrors.remove(extension);
        return "Stopped LSP for ." + extension;
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
            String[] cmd = editor.lspService.builtinCommand(ext);
            if (cmd != null) {
                sb.append("  .").append(ext).append(" -> ").append(String.join(" ", cmd)).append("\n");
            }
        }
        editor.showScratchBuffer("[lsp servers]", sb.toString());
        return "Showing LSP servers";
    }


    public String lspLog() {
        if (editor.lspErrors.isEmpty()) return "No LSP errors";
        StringBuilder sb = new StringBuilder();
        sb.append("LSP Error Log\n");
        sb.append("=".repeat(40)).append("\n\n");
        for (Map.Entry<String, String> entry : editor.lspErrors.entrySet()) {
            String ext = entry.getKey().isEmpty() ? "(no ext)" : "." + entry.getKey();
            sb.append(ext).append(": ").append(entry.getValue()).append("\n");
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
                return "No file references found";
            }
            editor.updateQuickfixEntries("lsp references", entries);
            return editor.openQuickfixList();
        } catch (BadLocationException e) {
            return "LSP references failed: " + e.getMessage();
        }
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
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int column = editor.writingArea.getCaretPosition() - editor.writingArea.getLineStartOffset(line);
            List<LspClient.CodeAction> actions = collectCursorCodeActions(client, uri, line, column);
            if (actions.isEmpty()) {
                return "No code actions";
            }

            int requestedIndex = parseOneBasedIndex(selectionArgument);
            if (selectionArgument != null && !selectionArgument.isBlank() && requestedIndex < 1) {
                return "Usage: :lsp codeaction [index]";
            }
            if (requestedIndex > 0) {
                if (requestedIndex > actions.size()) {
                    return "Code action index out of range: " + requestedIndex;
                }
                LspClient.CodeAction action = actions.get(requestedIndex - 1);
                editor.pendingLspCodeAction = action;
                editor.showScratchBuffer("[lsp code action preview]", buildWorkspaceEditPreview(action.getTitle(), action.getOperations(), new WorkspaceEditApplyResult())
                    + "\nRun :lsp codeaction apply to confirm, or choose another action to replace this preview.\n");
                return "Prepared code action preview. Run :lsp codeaction apply to confirm.";
            }

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < actions.size(); i++) {
                LspClient.CodeAction action = actions.get(i);
                builder.append(i + 1).append(". ").append(action.getTitle());
                if (!action.getKind().isBlank()) {
                    builder.append(" (").append(action.getKind()).append(")");
                }
                if (action.isPreferred()) {
                    builder.append(" [preferred]");
                }
                if (action.getCommandId() != null && !action.getCommandId().isBlank()) {
                    builder.append(" [command]");
                }
                if (!action.getEdits().isEmpty()) {
                    builder.append(" [edit]");
                }
                if (hasResourceOperation(action.getOperations())) {
                    builder.append(" [resource]");
                }
                if (i < actions.size() - 1) {
                    builder.append("\n");
                }
            }
            editor.showScratchBuffer("[lsp code actions]", builder.toString() + "\n\nRun :lsp codeaction <index> to apply.");
            return "Showing code actions (use :lsp codeaction <index>)";
        } catch (BadLocationException e) {
            return "LSP code actions failed: " + e.getMessage();
        }
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
        List<LspClient.Diagnostic> diagnostics = client.getDiagnostics(uri);
        List<LspClient.Diagnostic> scoped = new ArrayList<>();
        for (LspClient.Diagnostic diagnostic : diagnostics) {
            if (diagnostic.getLine() == line) {
                scoped.add(diagnostic);
            }
        }
        return client.codeActions(uri, line, column, scoped);
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
            editor.pulseCaretLine(editor.blendColor(editor.configManager.getVisualColor(), editor.configManager.getCaretColor(), 0.35));
            editor.playCue(CueType.NAVIGATE);
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
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))
                || Files.exists(candidate.resolve("pom.xml"))
                || Files.exists(candidate.resolve("package.json"))
                || Files.exists(candidate.resolve("Makefile"))) {
                return candidate.toRealPath();
            }
        }
        return new File(".").getCanonicalFile().toPath().toAbsolutePath().normalize();
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


    LspClient resolveLspClient(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
            return null;
        }
        String extension = bufferExtension(buffer);
        if (extension.isEmpty()) {
            editor.lspErrors.put("", "file has no recognized extension");
            return null;
        }

        Path workspaceRoot;
        try {
            workspaceRoot = workspaceRootPath(buffer);
        } catch (IOException e) {
            editor.lspErrors.put(extension, e.getMessage());
            return null;
        }

        LspClient existing = editor.lspClients.get(extension);
        if (existing != null && existing.isAlive()) {
            Path existingRoot = editor.lspClientRoots.get(extension);
            if (workspaceRoot.equals(existingRoot)) {
                editor.lspErrors.remove(extension);
                return existing;
            }
            existing.stop();
            editor.lspClients.remove(extension);
            editor.lspClientRoots.remove(extension);
        }

        String command = editor.configManager.getLspCommand(extension);
        String[] args = editor.configManager.getLspArgs(extension);
        if (command == null || command.isBlank()) {
            String[] builtin = builtinLspCommand(extension);
            if (builtin == null) {
                editor.lspErrors.put(extension, "no server configured for ." + extension);
                return null;
            }
            command = builtin[0];
            args = java.util.Arrays.copyOfRange(builtin, 1, builtin.length);
        }

        try {
            LspClient client = new LspClient(command, args, workspaceRoot, editor.configManager.getLspFeatureSettings());
            client.setWorkspaceEditHandler(this::applyWorkspaceEditFromServer);
            editor.lspClients.put(extension, client);
            editor.lspClientRoots.put(extension, workspaceRoot);
            editor.lspErrors.remove(extension);
            return client;
        } catch (IOException e) {
            editor.lspErrors.put(extension, e.getMessage());
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
        client.didOpen(uri, languageId(buffer), buffer.getFullContent());
        editor.lspDocumentVersions.put(uri, 1);
    }


    void syncLspChange(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            return;
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return;
        }
        syncLspOpen(buffer);
        String uri = bufferUri(buffer);
        int version = editor.lspDocumentVersions.getOrDefault(uri, 1) + 1;
        editor.lspDocumentVersions.put(uri, version);
        client.didChange(uri, version, buffer.getFullContent());
        scheduleDiagnosticRefresh();
    }


    void scheduleDiagnosticRefresh() {
        if (editor.diagnosticRefreshTimer == null) {
            editor.diagnosticRefreshTimer = new javax.swing.Timer(500, ev -> editor.refreshDiagnosticRanges());
            editor.diagnosticRefreshTimer.setRepeats(false);
        }
        editor.diagnosticRefreshTimer.restart();
    }


    public void notifyCurrentBufferSaved() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return;
        }
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
        return editor.lspClients.get(bufferExtension(buffer));
    }


    String bufferUri(FileBuffer buffer) {
        return new File(buffer.getFilePath()).toPath().toAbsolutePath().normalize().toUri().toString();
    }


    String languageId(FileBuffer buffer) {
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
