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

    @Override
    void close() throws Exception;
}
