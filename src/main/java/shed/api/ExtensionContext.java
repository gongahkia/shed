package shed.api;

import java.nio.file.Path;

/** Stable extension-facing registration surface for Shed API version 1. */
public interface ExtensionContext {
    String extensionId();

    Path storageDirectory();

    void registerCommand(String id, ExtensionCommand command);

    void registerLanguage(LanguageContribution contribution);

    /** Registers file detection and safe lexical behavior for an extension language. */
    void registerLanguageProfile(LanguageProfile profile);

    /** Registers one language-scoped TextMate-subset snippet. */
    default void registerSnippet(SnippetContribution contribution) {
        throw new UnsupportedOperationException("Snippet contributions require a newer Shed host");
    }

    void registerDebugger(DebugAdapterContribution contribution);

    void registerTestProvider(TestContribution contribution);

    void registerScmProvider(ScmContribution contribution);

    void registerTerminalProfile(TerminalProfile contribution);

    void registerToolView(ToolViewContribution contribution);

    void registerCustomEditor(CustomEditorContribution contribution);

    void registerRemoteWorkspaceProvider(RemoteWorkspaceProvider contribution);

    void registerWorkspaceTool(WorkspaceToolContribution contribution);
}
