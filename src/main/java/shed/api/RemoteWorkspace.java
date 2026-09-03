package shed.api;

import java.nio.file.Path;
import java.util.List;

/** A connected remote workspace represented by a local synchronized working tree. */
public interface RemoteWorkspace extends AutoCloseable {
    String displayName();

    Path localRoot();

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

    @Override
    void close() throws Exception;
}
