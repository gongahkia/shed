package shed;

import shed.api.CustomEditorContribution;
import shed.api.DebugAdapterContribution;
import shed.api.ExtensionCommand;
import shed.api.LanguageContribution;
import shed.api.RemoteWorkspaceProvider;
import shed.api.ScmContribution;
import shed.api.TerminalProfile;
import shed.api.TestContribution;
import shed.api.ToolViewContribution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Process-local extension contribution registry. Ownership is explicit so a
 * reload removes every contribution from the previous class loader first.
 */
final class ExtensionRegistry {
    record Owned<T>(String extensionId, T value) { }

    private final Map<String, Owned<ExtensionCommand>> commands = new LinkedHashMap<>();
    private final Map<String, Owned<LanguageContribution>> languages = new LinkedHashMap<>();
    private final Map<String, Owned<DebugAdapterContribution>> debuggers = new LinkedHashMap<>();
    private final Map<String, Owned<TestContribution>> tests = new LinkedHashMap<>();
    private final Map<String, Owned<ScmContribution>> scm = new LinkedHashMap<>();
    private final Map<String, Owned<TerminalProfile>> terminalProfiles = new LinkedHashMap<>();
    private final Map<String, Owned<ToolViewContribution>> toolViews = new LinkedHashMap<>();
    private final Map<String, Owned<CustomEditorContribution>> customEditors = new LinkedHashMap<>();
    private final Map<String, Owned<RemoteWorkspaceProvider>> remoteWorkspaces = new LinkedHashMap<>();

    synchronized void registerCommand(String extensionId, String id, ExtensionCommand command) {
        commands.put(qualified(extensionId, id), new Owned<>(extensionId, require(command, "extension command")));
    }

    synchronized void registerLanguage(String extensionId, LanguageContribution contribution) {
        languages.put(unique(extensionId, require(contribution, "language contribution").id()), new Owned<>(extensionId, contribution));
    }

    synchronized void registerDebugger(String extensionId, DebugAdapterContribution contribution) {
        debuggers.put(unique(extensionId, require(contribution, "debug adapter contribution").id()), new Owned<>(extensionId, contribution));
    }

    synchronized void registerTest(String extensionId, TestContribution contribution) {
        tests.put(unique(extensionId, requiredId(contribution, "test provider")), new Owned<>(extensionId, contribution));
    }

    synchronized void registerScm(String extensionId, ScmContribution contribution) {
        scm.put(unique(extensionId, requiredId(contribution, "SCM provider")), new Owned<>(extensionId, contribution));
    }

    synchronized void registerTerminalProfile(String extensionId, TerminalProfile contribution) {
        terminalProfiles.put(unique(extensionId, require(contribution, "terminal profile").id()), new Owned<>(extensionId, contribution));
    }

    synchronized void registerToolView(String extensionId, ToolViewContribution contribution) {
        toolViews.put(unique(extensionId, requiredId(contribution, "tool view")), new Owned<>(extensionId, contribution));
    }

    synchronized void registerCustomEditor(String extensionId, CustomEditorContribution contribution) {
        customEditors.put(unique(extensionId, requiredId(contribution, "custom editor")), new Owned<>(extensionId, contribution));
    }

    synchronized void registerRemoteWorkspace(String extensionId, RemoteWorkspaceProvider contribution) {
        remoteWorkspaces.put(unique(extensionId, requiredId(contribution, "remote workspace provider")), new Owned<>(extensionId, contribution));
    }

    synchronized String executeCommand(String id, String arguments) throws Exception {
        Owned<ExtensionCommand> command = commands.get(normalize(id));
        return command == null ? null : command.value().execute(arguments == null ? "" : arguments);
    }

    synchronized List<String> commandIds() {
        return sorted(commands);
    }

    synchronized List<Owned<LanguageContribution>> languages() { return sortedValues(languages); }
    synchronized List<Owned<DebugAdapterContribution>> debuggers() { return sortedValues(debuggers); }
    synchronized List<Owned<TestContribution>> tests() { return sortedValues(tests); }
    synchronized List<Owned<ScmContribution>> scmProviders() { return sortedValues(scm); }
    synchronized List<Owned<TerminalProfile>> terminalProfiles() { return sortedValues(terminalProfiles); }
    synchronized List<Owned<ToolViewContribution>> toolViews() { return sortedValues(toolViews); }
    synchronized List<Owned<CustomEditorContribution>> customEditors() { return sortedValues(customEditors); }
    synchronized List<Owned<RemoteWorkspaceProvider>> remoteWorkspaceProviders() { return sortedValues(remoteWorkspaces); }

    synchronized void removeExtension(String extensionId) {
        String normalized = normalizeExtensionId(extensionId);
        commands.values().removeIf(value -> value.extensionId().equals(normalized));
        languages.values().removeIf(value -> value.extensionId().equals(normalized));
        debuggers.values().removeIf(value -> value.extensionId().equals(normalized));
        tests.values().removeIf(value -> value.extensionId().equals(normalized));
        scm.values().removeIf(value -> value.extensionId().equals(normalized));
        terminalProfiles.values().removeIf(value -> value.extensionId().equals(normalized));
        toolViews.values().removeIf(value -> value.extensionId().equals(normalized));
        customEditors.values().removeIf(value -> value.extensionId().equals(normalized));
        remoteWorkspaces.values().removeIf(value -> value.extensionId().equals(normalized));
    }

    private static <T> List<Owned<T>> sortedValues(Map<String, Owned<T>> values) {
        List<Map.Entry<String, Owned<T>>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        return entries.stream().map(Map.Entry::getValue).toList();
    }

    private static List<String> sorted(Map<String, ?> values) {
        return values.keySet().stream().sorted().toList();
    }

    private static String unique(String extensionId, String contributionId) {
        return normalizeExtensionId(extensionId) + ":" + normalizeContributionId(contributionId);
    }

    private static String qualified(String extensionId, String commandId) {
        String normalized = normalizeContributionId(commandId);
        String owner = normalizeExtensionId(extensionId);
        return normalize(normalized.contains(".") ? normalized : owner + "." + normalized);
    }

    private static String normalizeExtensionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("extension id is invalid");
        }
        return normalize(value);
    }

    private static String normalizeContributionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("contribution id is invalid");
        }
        return normalize(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> T require(T value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        return value;
    }

    private static String requiredId(TestContribution value, String label) {
        require(value, label);
        return value.id();
    }

    private static String requiredId(ScmContribution value, String label) {
        require(value, label);
        return value.id();
    }

    private static String requiredId(ToolViewContribution value, String label) {
        require(value, label);
        return value.id();
    }

    private static String requiredId(CustomEditorContribution value, String label) {
        require(value, label);
        return value.id();
    }

    private static String requiredId(RemoteWorkspaceProvider value, String label) {
        require(value, label);
        return value.id();
    }
}
