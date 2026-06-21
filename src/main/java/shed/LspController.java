package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
            return "Usage: :lsp completion|definition|hover|references|rename <newName>|renameapply|renamecancel|codeaction [index]";
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
            sb.append("\n");
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
        sb.append("Configured (shedrc):\n");
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
        if (buffer == null || !buffer.hasFilePath()) {
            return "LSP definition requires a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
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
        if (buffer == null || !buffer.hasFilePath()) {
            return "LSP hover requires a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
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


    public String lspReferences() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return "LSP references requires a file-backed buffer";
        }
        LspClient client = resolveLspClient(buffer);
        if (client == null) {
            return "LSP unavailable";
        }
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
                WorkspaceEditApplyResult applyResult = applyWorkspaceTextEdits(action.getEdits());
                boolean executed = false;
                if (action.getCommandId() != null && !action.getCommandId().isBlank()) {
                    executed = client.executeCommand(action.getCommandId(), action.getCommandArguments());
                }
                if (applyResult.appliedEditCount == 0 && !executed) {
                    return "Code action produced no local edit and no executable command";
                }
                StringBuilder message = new StringBuilder();
                message.append("Applied code action ").append(requestedIndex).append(": ").append(action.getTitle());
                if (applyResult.appliedEditCount > 0) {
                    message.append(" (")
                        .append(applyResult.appliedEditCount)
                        .append(" edit")
                        .append(applyResult.appliedEditCount == 1 ? "" : "s")
                        .append(")");
                }
                if (executed) {
                    message.append(" [command executed]");
                } else if (action.getCommandId() != null && !action.getCommandId().isBlank()) {
                    message.append(" [command failed]");
                }
                if (applyResult.failedFiles > 0) {
                    message.append(" [").append(applyResult.failedFiles).append(" file failures]");
                }
                return message.toString();
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
        WorkspaceEditApplyResult result = new WorkspaceEditApplyResult();
        if (edits == null || edits.isEmpty()) {
            return result;
        }
        Map<String, List<LspClient.TextEdit>> groupedByUri = new HashMap<>();
        for (LspClient.TextEdit edit : edits) {
            if (edit == null || edit.getUri() == null || edit.getUri().isBlank()) {
                continue;
            }
            groupedByUri.computeIfAbsent(edit.getUri(), key -> new ArrayList<>()).add(edit);
        }
        if (groupedByUri.isEmpty()) {
            return result;
        }

        FileBuffer current = editor.getCurrentBuffer();
        String currentPath = current == null ? null : current.getFilePath();

        for (Map.Entry<String, List<LspClient.TextEdit>> entry : groupedByUri.entrySet()) {
            String path = filePathFromUri(entry.getKey());
            if (path == null || path.isBlank()) {
                result.failedFiles++;
                continue;
            }

            FileBuffer targetBuffer = editor.findBufferByPath(new File(path));
            if (targetBuffer != null) {
                int applied = applyTextEditsToBuffer(targetBuffer, entry.getValue());
                if (applied > 0) {
                    result.appliedEditCount += applied;
                    result.touchedFiles++;
                } else {
                    result.failedFiles++;
                }
                continue;
            }

            if (currentPath != null && currentPath.equals(path)) {
                int applied = applyTextEditsToCurrentArea(entry.getValue());
                if (applied > 0) {
                    result.appliedEditCount += applied;
                    result.touchedFiles++;
                } else {
                    result.failedFiles++;
                }
                continue;
            }

            int applied = applyTextEditsToFile(path, entry.getValue());
            if (applied > 0) {
                result.appliedEditCount += applied;
                result.touchedFiles++;
            } else {
                result.failedFiles++;
            }
        }
        return result;
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
        for (LspClient.TextEdit edit : edits) {
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
            resolved.add(new ResolvedTextEdit(startOffset, endOffset, edit.getNewText()));
        }
        resolved.sort((left, right) -> {
            if (left.startOffset != right.startOffset) {
                return Integer.compare(right.startOffset, left.startOffset);
            }
            return Integer.compare(right.endOffset, left.endOffset);
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
            return new File(new URI(uri)).getAbsolutePath();
        } catch (URISyntaxException e) {
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

        LspClient existing = editor.lspClients.get(extension);
        if (existing != null && existing.isAlive()) {
            editor.lspErrors.remove(extension);
            return existing;
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
            LspClient client = new LspClient(command, args, new File(".").toPath());
            editor.lspClients.put(extension, client);
            editor.lspErrors.remove(extension);
            return client;
        } catch (IOException e) {
            editor.lspErrors.put(extension, e.getMessage());
            return null;
        }
    }


    void syncLspOpen(FileBuffer buffer) {
        if (buffer == null || !buffer.hasFilePath()) {
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
        if (buffer == null || !buffer.hasFilePath()) {
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
        return "file://" + new File(buffer.getFilePath()).getAbsolutePath();
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

}
