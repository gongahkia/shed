package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

final class WorkspaceReplaceCoordinator {
    private final Texteditor editor;
    private WorkspaceReplaceService.Plan plan;
    private WorkspaceReplaceService.ApplyResult lastApplyResult;
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
            case "replace" -> replace(args);
            case "status" -> showPlan();
            case "file" -> selectFile(args);
            case "match" -> selectMatch(args);
            case "apply" -> apply(args);
            case "cancel" -> cancel();
            case "settings" -> showSettings();
            case "enable" -> setSetting("project.replace.enabled", "true");
            case "disable" -> setSetting("project.replace.enabled", "false");
            case "preview-required" -> setBooleanSetting("project.replace.preview.required", "preview-required", args);
            case "confirm" -> setBooleanSetting("project.replace.confirm.required", "confirm", args);
            case "backup" -> setBooleanSetting("project.replace.backup.enabled", "backup", args);
            case "scope" -> setScope(args);
            default -> usage();
        };
    }

    WorkspaceReplaceService.Plan planForPanel() { return plan == null ? null : plan.snapshot(); }

    WorkspaceReplaceService.ApplyResult lastApplyResultForPanel() { return lastApplyResult; }

    ProjectReplacePolicy policyForPanel() { return policy(); }

    String previewForPanel(String find, String replacement) {
        if (find == null || find.isEmpty() || find.indexOf('\n') >= 0 || find.indexOf('\r') >= 0
            || replacement == null || replacement.indexOf('\n') >= 0 || replacement.indexOf('\r') >= 0) {
            return "Find must be non-empty and both values must be single-line.";
        }
        String escapedFind = find.replace("\\", "\\\\").replace("/", "\\/");
        String escapedReplacement = replacement.replace("\\", "\\\\").replace("/", "\\/");
        return preview("/" + escapedFind + "/" + escapedReplacement + "/");
    }

    String selectFileForPanel(int id, boolean selected) {
        if (plan == null || !plan.selectFile(id, selected ? WorkspaceReplaceService.Selection.ON : WorkspaceReplaceService.Selection.OFF)) {
            return "No project replace preview or file selection.";
        }
        return "File selection updated";
    }

    String selectMatchForPanel(int id, boolean selected) {
        if (plan == null || !plan.selectMatch(id, selected ? WorkspaceReplaceService.Selection.ON : WorkspaceReplaceService.Selection.OFF)) {
            return "No project replace preview or match selection.";
        }
        return "Match selection updated";
    }

    String applyForPanel() { return apply(policy().confirmRequired() ? "confirm" : ""); }

    String cancelForPanel() { return cancel(); }

    String setForPanel(String key, String value) { return setSetting(key, value); }

    private String preview(String argument) {
        ProjectReplacePolicy policy = policy();
        if (!policy.enabled()) {
            return "Project replacement is disabled; run :projectreplace enable";
        }
        ReplacementSpec spec = ReplacementSpec.parse(argument);
        if (spec == null) {
            return "Usage: :projectreplace preview /find/replacement/";
        }
        Path root = workspaceRoot();
        if (root == null) {
            return "Project replace requires a directory";
        }
        Path scopeFile = scopeFile(policy, root);
        if ("current-file".equals(policy.scope()) && scopeFile == null) {
            return "Current-file scope requires an open workspace file";
        }
        boolean persistentIndexEnabled = editor.configManager.getWorkspaceIndexEnabled();
        long request = ++activeRequest;
        plan = null;
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        int jobId = editor.asyncJobService.submit("project replace preview: " + spec.find(),
            token -> {
                token.onCancel(cancellation::cancel);
                WorkspaceIndexService index = workspaceIndexService();
                return new WorkspaceReplaceService(index).preview(persistentIndexEnabled, root, spec.find(), spec.replacement(), scopeFile, cancellation);
            },
            (snapshot, result, error) -> completePreview(request, snapshot, result, error));
        return "Started project replace preview job " + jobId;
    }

    private String replace(String argument) {
        ProjectReplacePolicy policy = policy();
        if (!policy.enabled()) {
            return "Project replacement is disabled; run :projectreplace enable";
        }
        if (policy.previewRequired()) {
            return "Preview is required; run :projectreplace preview /find/replacement/";
        }
        ReplacementSpec spec = ReplacementSpec.parse(policy.confirmRequired() ? confirmedArgument(argument) : argument);
        if (spec == null) {
            return policy.confirmRequired()
                ? "Usage: :projectreplace replace /find/replacement/ confirm"
                : "Usage: :projectreplace replace /find/replacement/";
        }
        Path root = workspaceRoot();
        if (root == null) {
            return "Project replace requires a directory";
        }
        Path scopeFile = scopeFile(policy, root);
        if ("current-file".equals(policy.scope()) && scopeFile == null) {
            return "Current-file scope requires an open workspace file";
        }
        boolean persistentIndexEnabled = editor.configManager.getWorkspaceIndexEnabled();
        long request = ++activeRequest;
        plan = null;
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        int jobId = editor.asyncJobService.submit("project replace: " + spec.find(),
            token -> {
                token.onCancel(cancellation::cancel);
                WorkspaceIndexService index = workspaceIndexService();
                return new WorkspaceReplaceService(index).preview(persistentIndexEnabled, root, spec.find(), spec.replacement(), scopeFile, cancellation);
            },
            (snapshot, result, error) -> completeDirectReplace(request, policy, snapshot, result, error));
        return "Started project replace job " + jobId;
    }

    private void completeDirectReplace(long request, ProjectReplacePolicy policy, AsyncJobService.JobSnapshot snapshot,
                                       WorkspaceReplaceService.Preview preview, Exception error) {
        if (editor.closingDown || request != activeRequest) {
            return;
        }
        int jobId = snapshot == null ? -1 : snapshot.getId();
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED
            || preview != null && preview.state() == WorkspaceReplaceService.State.CANCELLED) {
            editor.showMessage("Project replace job " + jobId + " cancelled");
            return;
        }
        if (error != null || preview == null || preview.state() != WorkspaceReplaceService.State.READY) {
            String message = error == null ? preview == null ? "unknown error" : preview.message() : error.getMessage();
            editor.showMessage("Project replace job " + jobId + " failed: " + (message == null ? "" : message));
            return;
        }
        if (preview.plan().truncated()) {
            editor.showMessage("Project replace job " + jobId + " requires preview: match limit reached");
            return;
        }
        if (preview.plan().files().isEmpty()) {
            editor.showMessage("Project replace job " + jobId + " found no matches");
            return;
        }
        WorkspaceReplaceService.ApplyOptions options = new WorkspaceReplaceService.ApplyOptions(policy.backupEnabled(),
            policy.backupEnabled() ? policy.backupDirectoryPath() : null);
        WorkspaceReplaceService.Plan applying = preview.plan().snapshot();
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        int applyJobId = editor.asyncJobService.submit("project replace apply",
            token -> {
                token.onCancel(cancellation::cancel);
                WorkspaceIndexService index = workspaceIndexService();
                return new WorkspaceReplaceService(index).apply(applying, cancellation, options);
            },
            (applySnapshot, result, applyError) -> completeApply(applySnapshot, result, applyError));
        editor.showMessage("Started project replace apply job " + applyJobId);
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
        if (editor.toolWindowHost != null && editor.toolWindowHost.isSelected(ToolWindowHost.Tab.REPLACE)) {
            editor.toolWindowHost.refresh(ToolWindowHost.Tab.REPLACE);
        } else {
            showPlan();
        }
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

    private String apply(String argument) {
        ProjectReplacePolicy policy = policy();
        if (!policy.enabled()) {
            return "Project replacement is disabled; run :projectreplace enable";
        }
        if (plan == null) {
            return "No project replace preview";
        }
        if (policy.confirmRequired() && !"confirm".equals(argument)) {
            return "Run :projectreplace apply confirm to apply the preview";
        }
        if (!policy.confirmRequired() && !argument.isEmpty()) {
            return "Usage: :projectreplace apply";
        }
        WorkspaceReplaceService.Plan applying = plan.snapshot();
        WorkspaceReplaceService.ApplyOptions options = new WorkspaceReplaceService.ApplyOptions(policy.backupEnabled(),
            policy.backupEnabled() ? policy.backupDirectoryPath() : null);
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        int jobId = editor.asyncJobService.submit("project replace apply",
            token -> {
                token.onCancel(cancellation::cancel);
                WorkspaceIndexService index = workspaceIndexService();
                return new WorkspaceReplaceService(index).apply(applying, cancellation, options);
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
        lastApplyResult = result;
        if (editor.toolWindowHost == null || !editor.toolWindowHost.isSelected(ToolWindowHost.Tab.REPLACE)) {
            editor.showScratchBuffer("[project replace result]", formatResult(result));
        } else {
            editor.toolWindowHost.refresh(ToolWindowHost.Tab.REPLACE);
        }
        editor.showMessage("Project replace apply complete");
    }

    private WorkspaceIndexService workspaceIndexService() {
        return WorkspaceIndexService.withAdditionalIgnore(Path.of(editor.configManager.getShedDirectoryPath(), "workspace-index"),
            editor.workspaceController.searchExclusionMatcher());
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
        editor.showScratchBuffer("[project replace preview]", formatPlan(plan, policy().confirmRequired()));
        return "Showing project replace preview";
    }

    private String showSettings() {
        ProjectReplacePolicy policy = policy();
        String settings = "Project Replace Settings\n\n"
            + "enabled: " + policy.enabled() + '\n'
            + "preview.required: " + policy.previewRequired()
            + (policy.previewRequired() ? " (preview is mandatory)\n" : " (explicit replace allowed)\n")
            + "confirm.required: " + policy.confirmRequired() + '\n'
            + "backup.enabled: " + policy.backupEnabled() + '\n'
            + "backup.directory: " + policy.backupDirectoryPath() + '\n'
            + "scope: " + policy.scope() + "\n\n"
            + ":projectreplace enable|disable\n"
            + ":projectreplace preview-required on|off\n"
            + ":projectreplace confirm on|off\n"
            + ":projectreplace backup on|off\n"
            + ":projectreplace scope workspace|current-file\n";
        editor.showScratchBuffer("[project replace settings]", settings);
        return "Showing project replace settings";
    }

    private String setBooleanSetting(String key, String command, String value) {
        return switch (value) {
            case "on" -> setSetting(key, "true");
            case "off" -> setSetting(key, "false");
            default -> "Usage: :projectreplace " + command + " on|off";
        };
    }

    private String setScope(String value) {
        if (!"workspace".equals(value) && !"current-file".equals(value)) {
            return "Usage: :projectreplace scope workspace|current-file";
        }
        return setSetting("project.replace.scope", value);
    }

    private String setSetting(String key, String value) {
        try {
            editor.configManager.setAndPersist(key, value);
            if ("project.replace.enabled".equals(key) && !Boolean.parseBoolean(value)) {
                cancel();
            }
            return "Updated " + key;
        } catch (IOException error) {
            return "Unable to update " + key + ": " + error.getMessage();
        }
    }

    private ProjectReplacePolicy policy() {
        try {
            return editor.configManager.getProjectReplacePolicy();
        } catch (RuntimeException error) {
            throw new IllegalStateException("Invalid project replace configuration", error);
        }
    }

    private Path scopeFile(ProjectReplacePolicy policy, Path root) {
        if (!"current-file".equals(policy.scope())) {
            return null;
        }
        FileBuffer current = editor.getCurrentBuffer();
        if (current == null || !current.hasFilePath()) {
            return null;
        }
        Path file = Path.of(current.getFilePath()).toAbsolutePath().normalize();
        return file.startsWith(root) ? file : null;
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

    private static String formatPlan(WorkspaceReplaceService.Plan plan, boolean confirmationRequired) {
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
        result.append(confirmationRequired ? ":projectreplace apply confirm\n" : ":projectreplace apply\n");
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
        return "Usage: :projectreplace settings | enable|disable | preview /find/replacement/ | replace /find/replacement/ [confirm] | status | file <id> [on|off|toggle] | match <id> [on|off|toggle] | apply [confirm] | cancel";
    }

    private static String confirmedArgument(String argument) {
        String suffix = " confirm";
        return argument != null && argument.endsWith(suffix) ? argument.substring(0, argument.length() - suffix.length()).trim() : "";
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
