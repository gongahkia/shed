package shed.api;

import java.nio.file.Path;

/** Stable extension-facing registration surface for Shed API version 1. */
public interface ExtensionContext {
    String extensionId();

    Path storageDirectory();

    void registerCommand(String id, ExtensionCommand command);

    void registerLanguage(LanguageContribution contribution);

    void registerDebugger(DebugAdapterContribution contribution);

    void registerTestProvider(TestContribution contribution);

    void registerScmProvider(ScmContribution contribution);

    void registerTerminalProfile(TerminalProfile contribution);

    void registerToolView(ToolViewContribution contribution);

    void registerCustomEditor(CustomEditorContribution contribution);

    void registerRemoteWorkspaceProvider(RemoteWorkspaceProvider contribution);

    void registerWorkspaceTool(WorkspaceToolContribution contribution);
}
