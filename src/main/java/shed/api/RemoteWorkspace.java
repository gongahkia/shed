package shed.api;

import java.nio.file.Path;
import java.util.List;

/** A connected remote workspace represented by a local synchronized working tree. */
public interface RemoteWorkspace extends AutoCloseable {
    String displayName();

    Path localRoot();

    /**
     * Absolute root used by this provider when it executes a task. A provider
     * that cannot map local workspace variables into its execution environment
     * may retain the empty default.
     */
    default String executionRoot() { return ""; }

    /** Refresh the local mirror from its remote source. */
    void synchronize() throws Exception;

    /** Push local changes when this provider supports an explicit upload operation. */
    default void synchronizeToRemote() throws Exception {
        throw new UnsupportedOperationException("this remote workspace does not support push synchronization");
    }

    /**
     * Runs an explicitly selected direct-argv command in this workspace.
     * Providers that only support file synchronization retain the default.
     */
    default RemoteCommandResult execute(List<String> command) throws Exception {
        throw new UnsupportedOperationException("this remote workspace does not support command execution");
    }

    /**
     * Runs a direct-argv command with an optional workspace-relative directory
     * and declared environment. Providers should override this to support
     * remote tasks; the default preserves API-v1 command-only providers.
     */
    default RemoteCommandResult execute(RemoteCommandRequest request) throws Exception {
        if (request == null) throw new IllegalArgumentException("remote command request is required");
        if (!request.relativeWorkingDirectory().isEmpty() || !request.environment().isEmpty()) {
            throw new UnsupportedOperationException("this remote workspace does not support task execution options");
        }
        return execute(request.command());
    }

    /**
     * Copies one existing regular file or directory from a workspace-relative
     * remote path into an app-owned local directory and returns the resulting
     * local path. Implementations must reject traversal and symbolic-link
     * escapes. The default retains API-v1 provider compatibility.
     */
    default Path fetchWorkspacePath(String relativePath, Path destinationDirectory) throws Exception {
        throw new UnsupportedOperationException("this remote workspace does not support artifact retrieval");
    }

    /**
     * Builds the local direct-argv process used to attach a PTY to an explicitly
     * selected terminal in this workspace. The process inherits no task
     * environment; providers decide how to enter their remote environment.
     */
    default List<String> terminalCommand(RemoteTerminalRequest request) throws Exception {
        throw new UnsupportedOperationException("this remote workspace does not support interactive terminals");
    }

    /**
     * Builds the local direct-argv process that carries one user-configured
     * language server in this workspace's execution environment. Providers
     * must not install a server or leave a background host running.
     */
    default List<String> languageServerCommand(List<String> command) throws Exception {
        throw new UnsupportedOperationException("this remote workspace does not support remote language servers");
    }

    /** Absolute POSIX root used by {@link #languageServerCommand(List)}. */
    default String languageServerRoot() { return ""; }

    /**
     * Builds the local direct-argv process whose standard streams carry one
     * user-configured remote DAP adapter. The provider must not install an
     * adapter or silently fall back to local execution.
     */
    default List<String> debugAdapterCommand(List<String> command) throws Exception {
        throw new UnsupportedOperationException("this remote workspace does not support remote debug adapters");
    }

    /** Absolute POSIX root used by {@link #debugAdapterCommand(List)}. */
    default String debugAdapterRoot() { return ""; }

    @Override
    void close() throws Exception;
}
