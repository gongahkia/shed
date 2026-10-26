package shed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A model can request a command, but only the panel's explicit human decision authorizes execution. */
record AgentCommandPlan(String reason, List<Command> commands) {
    record Command(List<String> argv, Path directory, int timeoutSeconds) { }
    static String instructions() {
        return "\n\nIf project investigation or validation needs a headless command, REQUEST it through Shed instead of using a shell tool. "
            + "Return only {\"reason\":\"why this check is needed\",\"commands\":[{\"argv\":[\"executable\",\"argument\"],\"directory\":\".\",\"timeoutSeconds\":60}]}. "
            + "Use at most four commands, workspace-relative directories, and 1–600 second timeouts. "
            + "Shed asks the human before execution and returns actual exit codes/output; denial means the command did not run. "
            + "Request background checks only; never request source edits, model downloads, GUI apps, interactive input or persistent services. "
            + "Do not combine commands with an answer or proposal. After receiving results, continue the original request using its original response format. "
            + "Changes still require a reviewed proposal. Never claim checks passed without successful command results.";
    }
    static AgentCommandPlan requested(String response, WorkspaceQueryService workspace) {
        var data = AgentAnswer.json(response);
        if (!data.containsKey("commands")) return null;
        if (!data.keySet().equals(Set.of("reason", "commands")) || !(data.get("reason") instanceof String reason)
            || reason.isBlank() || reason.length() > 2048 || !(data.get("commands") instanceof List<?> values) || values.isEmpty() || values.size() > 4)
            throw new AgentReplyFormatException("Command requests require a reason and one to four commands, without edits or answers");
        var commands = new ArrayList<Command>();
        for (Object value : values) {
            var item = MiniJson.asObject(value);
            if (item == null || !item.keySet().equals(Set.of("argv", "directory", "timeoutSeconds"))
                || !(item.get("argv") instanceof List<?> args) || args.isEmpty() || args.size() > 128
                || args.stream().anyMatch(a -> !(a instanceof String s) || s.length() > 32768 || s.indexOf('\0') >= 0)
                || !(args.getFirst() instanceof String executable) || executable.isBlank()
                || !(item.get("directory") instanceof String directory) || directory.isBlank() || directory.length() > 2048 || Path.of(directory).isAbsolute()
                || !(item.get("timeoutSeconds") instanceof Long timeout) || timeout < 1 || timeout > 600)
                throw new AgentReplyFormatException("Command arguments, directory or timeout are invalid");
            Path cwd = WorkspacePaths.resolve(workspace.root(), directory);
            workspace.policy().checkPath(workspace.root().relativize(cwd), AgentPolicy.Capability.READ);
            if (!Files.isDirectory(cwd)) throw new IllegalArgumentException("Command directory is unavailable");
            commands.add(new Command(args.stream().map(String.class::cast).toList(), cwd, timeout.intValue()));
        }
        return new AgentCommandPlan(reason, List.copyOf(commands));
    }
    String describe() {
        StringBuilder text = new StringBuilder(reason).append("\n\nThese commands run with your account's filesystem and network access. They are not sandboxed and can change files. Approve only checks you trust.\n");
        for (var command : commands) text.append("\nArguments: ").append(MiniJson.stringify(command.argv()))
            .append("\nDirectory: ").append(command.directory()).append("\nDeadline: ").append(command.timeoutSeconds()).append(" seconds\n");
        return text.toString();
    }
    String execute(WorkspaceQueryService workspace, AsyncJobService.JobToken token) throws Exception {
        workspace.policy().require(AgentPolicy.Capability.RUN_HEADLESS);
        if (workspace.visibleDocuments().stream().anyMatch(WorkspaceDocument::dirty))
            throw new IllegalArgumentException("Save the workspace's unsaved buffers before running disk-based checks; no files were saved or commands run");
        var results = new ArrayList<Map<String, Object>>();
        for (Command command : commands) {
            if (token.isCancelled()) throw new java.util.concurrent.CancellationException();
            Path cwd = WorkspacePaths.resolve(workspace.root(), command.directory().toString());
            if (!cwd.equals(command.directory()) || !Files.isDirectory(cwd)) throw new IllegalArgumentException("Approved command directory changed");
            var process = BackgroundTaskProcess.start(command.argv(), cwd.toFile(), Map.of("JAVA_TOOL_OPTIONS", "-Djava.awt.headless=true"), 8192, "");
            CommandResult result;
            try { result = process.awaitCompletion(token, command.timeoutSeconds() * 1000); }
            finally { process.stop(); }
            if (token.isCancelled()) throw new java.util.concurrent.CancellationException();
            String output = result.stdout;
            Map<String, Object> entry;
            while (true) {
                entry = Map.of("command", results.size() + 1, "exitCode", result.exitCode, "output", output, "error", result.stderr);
                if (MiniJson.stringify(entry).length() <= 10000) break;
                output = output.substring(0, GraphemeBoundary.floor(output, output.length() / 2)) + "\n[Output shortened for agent feedback]";
            }
            results.add(entry);
        }
        return MiniJson.stringify(Map.of("approved", true, "results", results));
    }
}
