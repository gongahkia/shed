package shed;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

final class ProblemsController {
    private final Texteditor editor;
    private final ProblemsService service;

    ProblemsController(Texteditor editor, ProblemsService service) {
        this.editor = editor;
        this.service = service;
    }

    void recordQuickfixEntries(List<QuickfixService.Entry> entries) {
        service.recordQuickfixEntries(entries);
        refreshVisiblePanel();
    }

    void clearQuickfixSource(String source) {
        service.clearQuickfixSource(source);
        refreshVisiblePanel();
    }

    void diagnosticsChanged() {
        refreshVisiblePanel();
    }

    List<ProblemsService.Problem> snapshot() {
        List<ProblemsService.Problem> live = new ArrayList<>();
        for (Map.Entry<LspServerKey, LspClient> entry : editor.lspClients.entrySet()) {
            LspClient client = entry.getValue();
            if (client == null || !client.isAlive()) continue;
            for (Map.Entry<String, List<LspClient.Diagnostic>> diagnosticEntry : client.diagnosticsSnapshot().entrySet()) {
                String path = filePathFromUri(diagnosticEntry.getKey());
                for (LspClient.Diagnostic diagnostic : diagnosticEntry.getValue()) {
                    live.add(new ProblemsService.Problem(path, diagnostic.getLine() + 1, diagnostic.getCharacter() + 1,
                        diagnostic.getMessage(), "lsp", ProblemsService.Severity.fromLsp(diagnostic.getSeverity())));
                }
            }
        }
        return service.snapshot(live);
    }

    String handleCommand(String argument) {
        String filter = argument == null ? "" : argument.trim().toLowerCase(java.util.Locale.ROOT);
        if ("text".equals(filter)) {
            editor.showScratchBuffer("[problems]", render(snapshot()));
            return "Showing problems";
        }
        if (!filter.isEmpty() && !"ui".equals(filter) && !"all".equals(filter) && !isSeverityFilter(filter)) {
            return "Usage: :problems [ui|text|all|errors|warnings|info|hints|other]";
        }
        if (editor.toolWindowHost != null) editor.showToolWindow(ToolWindowHost.Tab.PROBLEMS);
        if (!filter.isEmpty() && !"ui".equals(filter) && editor.toolWindowHost != null) {
            ProblemsToolPanel panel = editor.toolWindowHost.problemsPanel();
            if (panel != null) panel.selectFilter(filter);
        }
        return "Problems panel opened";
    }

    String open(ProblemsService.Problem problem) {
        if (problem == null) return "No problem selected";
        if (!problem.filePath().isBlank()) {
            try {
                editor.openFile(new File(problem.filePath()));
            } catch (Exception error) {
                return "Problem open failed: " + error.getMessage();
            }
        }
        String lineResult = editor.gotoLine(problem.line());
        if (lineResult.startsWith("Error") || lineResult.startsWith("Invalid")) return lineResult;
        try {
            int lineStart = editor.writingArea.getLineStartOffset(Math.max(0, problem.line() - 1));
            int target = Math.min(lineStart + Math.max(0, problem.column() - 1), editor.writingArea.getText().length());
            editor.writingArea.setCaretPosition(target);
        } catch (BadLocationException ignored) {
        }
        return "Opened problem";
    }

    private void refreshVisiblePanel() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshVisiblePanel);
            return;
        }
        if (editor.toolWindowHost != null && editor.toolWindowHost.isSelected(ToolWindowHost.Tab.PROBLEMS)) {
            editor.toolWindowHost.refresh(ToolWindowHost.Tab.PROBLEMS);
        }
    }

    private static boolean isSeverityFilter(String value) {
        return "errors".equals(value) || "warnings".equals(value) || "info".equals(value)
            || "hints".equals(value) || "other".equals(value);
    }

    private static String render(List<ProblemsService.Problem> problems) {
        if (problems.isEmpty()) return "No problems\n";
        StringBuilder text = new StringBuilder();
        for (ProblemsService.Problem problem : problems) {
            text.append(problem.filePath()).append(':').append(problem.line()).append(':').append(problem.column())
                .append(": ").append(problem.message()).append(" [").append(problem.severity().name().toLowerCase(java.util.Locale.ROOT))
                .append(", ").append(problem.source()).append("]\n");
        }
        return text.toString();
    }

    private static String filePathFromUri(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value);
            if ("file".equalsIgnoreCase(uri.getScheme())) return Path.of(uri).toString();
        } catch (Exception ignored) {
        }
        return value;
    }
}
