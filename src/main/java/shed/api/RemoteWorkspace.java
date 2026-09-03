package shed.api;

import java.nio.file.Path;

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

    @Override
    void close() throws Exception;
}
