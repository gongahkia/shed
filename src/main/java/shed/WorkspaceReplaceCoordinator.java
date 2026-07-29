package shed;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

final class WorkspaceReplaceCoordinator {
    private final Texteditor editor;
    private WorkspaceReplaceService.Plan plan;
    private long activeRequest;

    WorkspaceReplaceCoordinator(Texteditor editor) {
        this.editor = editor;
    }

    String handle(String argument) {
        String input = argument == null ? "" : argument.trim();
        if (input.isEmpty()) {
            return usage();
        }
        int split = input.indexOf(' ');
        String subcommand = (split < 0 ? input : input.substring(0, split)).toLowerCase(java.util.Locale.ROOT);
        String args = split < 0 ? "" : input.substring(split + 1).trim();
        return switch (subcommand) {
            case "preview" -> preview(args);
            case "status" -> showPlan();
            case "file" -> selectFile(args);
            case "match" -> selectMatch(args);
            case "apply" -> apply();
            case "cancel" -> cancel();
            default -> usage();
        };
    }

    private String preview(String argument) {
        ReplacementSpec spec = ReplacementSpec.parse(argument);
        if (spec == null) {
            return "Usage: :projectreplace preview /find/replacement/";
        }
        Path root = workspaceRoot();
        if (root == null) {
            return "Project replace requires a directory";
        }
        boolean persistentIndexEnabled = editor.configManager.getWorkspaceIndexEnabled();
        long request = ++activeRequest;
        plan = null;
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        int jobId = editor.asyncJobService.submit("project replace preview: " + spec.find(),
            token -> {
                token.onCancel(cancellation::cancel);
                WorkspaceIndexService index = new WorkspaceIndexService(Path.of(editor.configManager.getShedDirectoryPath(), "workspace-index"));
                return new WorkspaceReplaceService(index).preview(persistentIndexEnabled, root, spec.find(), spec.replacement(), cancellation);
            },
            (snapshot, result, error) -> completePreview(request, snapshot, result, error));
        return "Started project replace preview job " + jobId;
    }

    private void completePreview(long request, AsyncJobService.JobSnapshot snapshot, WorkspaceReplaceService.Preview preview, Exception error) {
        if (editor.closingDown || request != activeRequest) {
            return;
        }
        int jobId = snapshot == null ? -1 : snapshot.getId();
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED
            || preview != null && preview.state() == WorkspaceReplaceService.State.CANCELLED) {
            editor.showMessage("Project replace preview job " + jobId + " cancelled");
            return;
        }
        if (error != null || preview == null || preview.state() == WorkspaceReplaceService.State.FAILED) {
            String message = error == null ? preview == null ? "unknown error" : preview.message() : error.getMessage();
            editor.showMessage("Project replace preview job " + jobId + " failed: " + (message == null ? "" : message));
            return;
        }
        plan = preview.plan();
        showPlan();
        editor.showMessage("Project replace preview ready");
    }

    private String selectFile(String argument) {
        SelectionRequest request = SelectionRequest.parse(argument);
        if (plan == null) {
            return "No project replace preview";
        }
        if (request == null || !plan.selectFile(request.id(), request.selection())) {
            return "Usage: :projectreplace file <id> [on|off|toggle]";
        }
        return showPlan();
    }

    private String selectMatch(String argument) {
        SelectionRequest request = SelectionRequest.parse(argument);
        if (plan == null) {
            return "No project replace preview";
        }
        if (request == null || !plan.selectMatch(request.id(), request.selection())) {
            return "Usage: :projectreplace match <id> [on|off|toggle]";
        }
        return showPlan();
    }

    private String apply() {
        if (plan == null) {
            return "No project replace preview";
        }
        WorkspaceReplaceService.Plan applying = plan.snapshot();
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        int jobId = editor.asyncJobService.submit("project replace apply",
            token -> {
                token.onCancel(cancellation::cancel);
                WorkspaceIndexService index = new WorkspaceIndexService(Path.of(editor.configManager.getShedDirectoryPath(), "workspace-index"));
                return new WorkspaceReplaceService(index).apply(applying, cancellation);
            },
            (snapshot, result, error) -> completeApply(snapshot, result, error));
        return "Started project replace apply job " + jobId;
    }

    private void completeApply(AsyncJobService.JobSnapshot snapshot, WorkspaceReplaceService.ApplyResult result, Exception error) {
        int jobId = snapshot == null ? -1 : snapshot.getId();
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED
            || result != null && result.state() == WorkspaceReplaceService.State.CANCELLED) {
            editor.showMessage("Project replace apply job " + jobId + " cancelled");
            return;
        }
        if (error != null || result == null) {
            String message = error == null ? "unknown error" : error.getMessage();
            editor.showMessage("Project replace apply job " + jobId + " failed: " + (message == null ? "" : message));
            return;
        }
        editor.showScratchBuffer("[project replace result]", formatResult(result));
        editor.showMessage("Project replace apply complete");
    }

    private String cancel() {
        if (plan == null) {
            return "No project replace preview";
        }
        plan = null;
        activeRequest++;
        return "Project replace preview discarded";
    }

    private String showPlan() {
        if (plan == null) {
            return "No project replace preview";
        }
        editor.showScratchBuffer("[project replace preview]", formatPlan(plan));
        return "Showing project replace preview";
    }

    private Path workspaceRoot() {
        FileBuffer current = editor.getCurrentBuffer();
        File base = current != null && current.hasFilePath() ? new File(current.getFilePath()).getParentFile() : editor.treeRoot;
        if (base == null || !base.isDirectory()) {
            base = new File(".");
        }
        File projectRoot = base;
        for (File cursor = base; cursor != null; cursor = cursor.getParentFile()) {
            if (new File(cursor, ".git").exists()) {
                projectRoot = cursor;
                break;
            }
        }
        return projectRoot.isDirectory() ? projectRoot.toPath() : null;
    }

    private static String formatPlan(WorkspaceReplaceService.Plan plan) {
        StringBuilder result = new StringBuilder();
        result.append("Project Replace Preview\n\n");
        result.append("Source: ").append(plan.source().name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        result.append("Find: ").append(plan.needle()).append('\n');
        result.append("Replace: ").append(plan.replacement()).append('\n');
        result.append("Selected matches: ").append(plan.selectedMatchCount()).append('\n');
        if (plan.truncated()) {
            result.append("Preview limit reached\n");
        }
        result.append('\n');
        for (WorkspaceReplaceService.FilePlan file : plan.files()) {
            result.append("file ").append(file.fileId()).append(" [")
                .append(file.selectedMatchCount() == 0 ? ' ' : 'x').append("] ")
                .append(file.path()).append('\n');
            for (WorkspaceReplaceService.MatchPlan match : file.matches()) {
                result.append("  match ").append(match.matchId()).append(" [")
                    .append(match.selected() ? 'x' : ' ').append("] ")
                    .append(match.line()).append(':').append(match.column()).append("  ")
                    .append(match.preview()).append('\n');
            }
        }
        result.append("\n:projectreplace file <id> [on|off|toggle]\n");
        result.append(":projectreplace match <id> [on|off|toggle]\n");
        result.append(":projectreplace apply\n");
        result.append(":projectreplace cancel\n");
        return result.toString();
    }

    private static String formatResult(WorkspaceReplaceService.ApplyResult result) {
        StringBuilder output = new StringBuilder("Project Replace Result\n\n");
        for (WorkspaceReplaceService.FileResult file : result.files()) {
            output.append(file.state().name().toLowerCase(java.util.Locale.ROOT)).append("  ")
                .append(file.path()).append("  ").append(file.message()).append('\n');
        }
        output.append('\n').append(result.message()).append('\n');
        return output.toString();
    }

    private static String usage() {
        return "Usage: :projectreplace preview /find/replacement/ | status | file <id> [on|off|toggle] | match <id> [on|off|toggle] | apply | cancel";
    }

    private record ReplacementSpec(String find, String replacement) {
        static ReplacementSpec parse(String argument) {
            if (argument == null || argument.length() < 3) {
                return null;
            }
            char delimiter = argument.charAt(0);
            int second = separator(argument, delimiter, 1);
            int third = second < 0 ? -1 : separator(argument, delimiter, second + 1);
            if (delimiter != '/' || second <= 1 || third < 0 || !argument.substring(third + 1).isBlank()) {
                return null;
            }
            return new ReplacementSpec(unescape(argument.substring(1, second), delimiter), unescape(argument.substring(second + 1, third), delimiter));
        }

        private static int separator(String value, char delimiter, int start) {
            boolean escaped = false;
            for (int index = start; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == delimiter && !escaped) {
                    return index;
                }
                escaped = character == '\\' && !escaped;
                if (character != '\\') {
                    escaped = false;
                }
            }
            return -1;
        }

        private static String unescape(String value, char delimiter) {
            return value.replace("\\" + delimiter, String.valueOf(delimiter)).replace("\\\\", "\\");
        }
    }

    private record SelectionRequest(int id, WorkspaceReplaceService.Selection selection) {
        static SelectionRequest parse(String argument) {
            String[] parts = argument == null ? new String[0] : argument.trim().split("\\s+");
            if (parts.length < 1 || parts.length > 2) {
                return null;
            }
            try {
                int id = Integer.parseInt(parts[0]);
                if (id < 1) {
                    return null;
                }
                WorkspaceReplaceService.Selection selection = parts.length == 1 ? WorkspaceReplaceService.Selection.TOGGLE
                    : WorkspaceReplaceService.Selection.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT));
                return new SelectionRequest(id, selection);
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
    }
}
