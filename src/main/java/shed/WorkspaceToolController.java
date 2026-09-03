package shed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import shed.api.WorkspaceToolAction;
import shed.api.WorkspaceToolContribution;

/** Routes explicit database, deployment, collaboration, and container extension actions. */
final class WorkspaceToolController {
    private final Texteditor editor;
    private final ExtensionRegistry registry;

    WorkspaceToolController(Texteditor editor, ExtensionRegistry registry) {
        this.editor = editor;
        this.registry = registry;
    }

    String handle(String argument) {
        Path workspace = workspace();
        if (workspace == null) return "An active workspace root is required for integrations";
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "list".equalsIgnoreCase(value) || "status".equalsIgnoreCase(value)) return overview(workspace);
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(value);
        } catch (IllegalArgumentException error) {
            return "Integration command invalid: " + error.getMessage();
        }
        String provider = tokens.getFirst();
        if (tokens.size() == 1 || "help".equalsIgnoreCase(tokens.get(1))) return help(workspace, provider);
        String action = tokens.get(1);
        String arguments = tokens.size() > 2 ? String.join(" ", tokens.subList(2, tokens.size())) : "";
        return execute(workspace, provider, action, arguments);
    }

    private String overview(Path workspace) {
        StringBuilder output = new StringBuilder("Workspace Integrations\n\nWorkspace: ").append(workspace).append("\n\n");
        List<ExtensionRegistry.Owned<WorkspaceToolContribution>> tools = supported(workspace);
        if (tools.isEmpty()) {
            output.append("No installed extension integration supports this workspace.\n");
        } else {
            for (ExtensionRegistry.Owned<WorkspaceToolContribution> tool : tools) {
                output.append("  ").append(name(tool)).append("  ").append(tool.value().kind()).append("  ")
                    .append(tool.value().displayName()).append("\n    actions: ").append(actions(tool)).append("\n");
            }
        }
        output.append("\nUse :integration <extension:id> <declared-action> [arguments].\n");
        editor.showScratchBuffer("[workspace integrations]", output.toString());
        return "Showing workspace integrations";
    }

    private String help(Path workspace, String requested) {
        ExtensionRegistry.Owned<WorkspaceToolContribution> tool = find(workspace, requested);
        if (tool == null) return "Integration not found or unsupported: " + requested;
        String output = name(tool) + "\n\n" + tool.value().kind() + "\n" + tool.value().displayName()
            + "\n\nActions: " + actions(tool) + "\n";
        editor.showScratchBuffer("[integration " + name(tool) + "]", output);
        return "Showing integration actions";
    }

    private String execute(Path workspace, String requested, String action, String arguments) {
        ExtensionRegistry.Owned<WorkspaceToolContribution> tool = find(workspace, requested);
        if (tool == null) return "Integration not found or unsupported: " + requested;
        String selected = action == null ? "" : action.trim();
        boolean declared = actionsList(tool).stream().anyMatch(value -> value.id().equalsIgnoreCase(selected));
        if (!declared) return "Action is not declared by " + name(tool) + ": " + selected;
        try {
            String output = tool.value().execute(workspace, selected, arguments == null ? "" : arguments);
            if (output != null && !output.isBlank()) editor.showScratchBuffer("[integration " + name(tool) + "]", output);
            return name(tool) + " " + selected + " complete";
        } catch (Exception error) {
            return "Integration action failed: " + concise(error);
        }
    }

    private List<ExtensionRegistry.Owned<WorkspaceToolContribution>> supported(Path workspace) {
        List<ExtensionRegistry.Owned<WorkspaceToolContribution>> result = new ArrayList<>();
        if (registry == null) return result;
        for (ExtensionRegistry.Owned<WorkspaceToolContribution> tool : registry.workspaceTools()) {
            try {
                if (tool.value().supports(workspace)) result.add(tool);
            } catch (Exception ignored) {
                // A provider unable to inspect this root is unavailable.
            }
        }
        return List.copyOf(result);
    }

    private ExtensionRegistry.Owned<WorkspaceToolContribution> find(Path workspace, String requested) {
        String expected = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        ExtensionRegistry.Owned<WorkspaceToolContribution> candidate = null;
        for (ExtensionRegistry.Owned<WorkspaceToolContribution> tool : supported(workspace)) {
            if (name(tool).equalsIgnoreCase(expected)) return tool;
            if (tool.value().id().equalsIgnoreCase(expected)) {
                if (candidate != null) return null;
                candidate = tool;
            }
        }
        return candidate;
    }

    private Path workspace() {
        Path active = editor.workspaceController == null ? null : editor.workspaceController.activeRoot();
        if (active != null) return active;
        FileBuffer buffer = editor.getCurrentBuffer();
        return buffer == null || buffer.getFile() == null || buffer.getFile().getParentFile() == null ? null
            : buffer.getFile().getParentFile().toPath().toAbsolutePath().normalize();
    }

    private static String name(ExtensionRegistry.Owned<WorkspaceToolContribution> tool) {
        return tool.extensionId() + ":" + tool.value().id();
    }

    private static List<WorkspaceToolAction> actionsList(ExtensionRegistry.Owned<WorkspaceToolContribution> tool) {
        try {
            List<WorkspaceToolAction> actions = tool.value().actions();
            if (actions == null) return List.of();
            return actions.stream().filter(action -> action != null).distinct().toList();
        } catch (Exception error) {
            return List.of();
        }
    }

    private static String actions(ExtensionRegistry.Owned<WorkspaceToolContribution> tool) {
        List<WorkspaceToolAction> values = actionsList(tool);
        return values.isEmpty() ? "(none)" : String.join(", ", values.stream().map(WorkspaceToolAction::id).toList());
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
