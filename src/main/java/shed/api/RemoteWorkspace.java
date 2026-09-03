package shed.api;

import java.nio.file.Path;

/** A connected remote workspace represented by a local synchronized working tree. */
public interface RemoteWorkspace extends AutoCloseable {
    String displayName();

    Path localRoot();

    void synchronize() throws Exception;

    @Override
    void close() throws Exception;
}
