package shed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves built-in SCM actions without granting arbitrary shell commands. */
final class ScmContributionService {
    record Result(String message, String document) {
        Result {
            message = message == null ? "" : message;
            document = document == null ? "" : document;
        }
        boolean hasDocument() { return !document.isBlank(); }
    }

    Result handle(Path workspace, String argument) {
        if (workspace == null) return new Result("Workspace root is required for SCM providers", "");
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "list".equalsIgnoreCase(value) || "providers".equalsIgnoreCase(value)) return overview(workspace);
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(value);
        } catch (IllegalArgumentException error) {
            return new Result("SCM command invalid: " + error.getMessage(), "");
        }
        if ("status".equalsIgnoreCase(tokens.getFirst())) {
            return tokens.size() == 1 ? statusAll(workspace) : status(workspace, tokens.get(1));
        }
        String providerName = tokens.getFirst();
        if (tokens.size() == 1 || "help".equalsIgnoreCase(tokens.get(1))) return help(workspace, providerName);
        String action = tokens.get(1);
        String arguments = tokens.size() > 2 ? String.join(" ", tokens.subList(2, tokens.size())) : "";
        return execute(workspace, providerName, action, arguments);
    }

    private Result overview(Path workspace) {
        StringBuilder output = new StringBuilder("SCM Providers\n\nWorkspace: ").append(workspace).append("\n\n");
        List<ScmContribution> providers = supported(workspace);
        if (providers.isEmpty()) {
            output.append("No built-in SCM provider supports this workspace.\n");
        } else {
            for (ScmContribution provider : providers) {
                output.append("  ").append(name(provider)).append("  ").append(provider.displayName()).append("\n");
                List<String> actions = safeActions(provider);
                output.append("    actions: ").append(actions.isEmpty() ? "(none)" : String.join(", ", actions)).append("\n");
            }
        }
        output.append("\nUse :scm status [provider] or :scm <provider> <action> [arguments].\n");
        return new Result("Showing SCM providers", output.toString());
    }

    private Result statusAll(Path workspace) {
        List<ScmContribution> providers = supported(workspace);
        if (providers.isEmpty()) return new Result("No built-in SCM provider supports this workspace", "");
        StringBuilder output = new StringBuilder("SCM Status\n\n");
        for (ScmContribution provider : providers) {
            output.append(name(provider)).append(" — ").append(provider.displayName()).append("\n");
            try {
                output.append(indent(provider.status(workspace))).append("\n");
            } catch (Exception error) {
                output.append("  Error: ").append(concise(error)).append("\n");
            }
        }
        return new Result("Showing SCM status", output.toString());
    }

    private Result status(Path workspace, String requested) {
        ScmContribution provider = find(workspace, requested);
        if (provider == null) return new Result("SCM provider not found or unsupported: " + requested, "");
        try {
            String output = provider.status(workspace);
            return new Result("Showing " + name(provider) + " status", name(provider) + " Status\n\n" + output + "\n");
        } catch (Exception error) {
            return new Result("SCM status failed: " + concise(error), "");
        }
    }

    private Result help(Path workspace, String requested) {
        ScmContribution provider = find(workspace, requested);
        if (provider == null) return new Result("SCM provider not found or unsupported: " + requested, "");
        List<String> actions = safeActions(provider);
        String output = name(provider) + "\n\n" + provider.displayName() + "\n\nActions: "
            + (actions.isEmpty() ? "(none)" : String.join(", ", actions)) + "\n";
        return new Result("Showing " + name(provider) + " actions", output);
    }

    private Result execute(Path workspace, String requested, String action, String arguments) {
        ScmContribution provider = find(workspace, requested);
        if (provider == null) return new Result("SCM provider not found or unsupported: " + requested, "");
        String selected = action == null ? "" : action.trim();
        if (safeActions(provider).stream().noneMatch(value -> value.equalsIgnoreCase(selected))) {
            return new Result("Action is not declared by " + name(provider) + ": " + selected, "");
        }
        try {
            String output = provider.execute(workspace, selected, arguments == null ? "" : arguments);
            return new Result(name(provider) + " " + selected + " complete", output == null || output.isBlank() ? "" : name(provider) + " " + selected + "\n\n" + output + "\n");
        } catch (Exception error) {
            return new Result("SCM action failed: " + concise(error), "");
        }
    }

    private List<ScmContribution> supported(Path workspace) {
        List<ScmContribution> result = new ArrayList<>();
        List<ScmContribution> candidates = BuiltInScmContributions.all();
        for (ScmContribution provider : candidates) {
            try {
                if (provider.supports(workspace)) result.add(provider);
            } catch (Exception ignored) {
                // A provider that cannot inspect the workspace is not available.
            }
        }
        return List.copyOf(result);
    }

    private ScmContribution find(Path workspace, String requested) {
        String expected = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        ScmContribution candidate = null;
        for (ScmContribution provider : supported(workspace)) {
            if (name(provider).equalsIgnoreCase(expected)) return provider;
            if (provider.id().equalsIgnoreCase(expected)) {
                if (candidate != null) return null;
                candidate = provider;
            }
        }
        return candidate;
    }

    private static List<String> safeActions(ScmContribution provider) {
        try {
            return provider.actions() == null ? List.of() : provider.actions().stream()
                .filter(value -> value != null && value.matches("[A-Za-z0-9._-]+")).distinct().toList();
        } catch (Exception error) {
            return List.of();
        }
    }

    private static String name(ScmContribution provider) { return provider.id(); }
    private static String indent(String value) { return (value == null ? "" : value).indent(2); }
    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
