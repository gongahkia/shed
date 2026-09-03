package shed.api;

import java.net.URI;
import java.nio.file.Path;

/** A user-supplied remote URI and a directory in which a provider may persist state. */
public record RemoteWorkspaceRequest(URI uri, Path storageDirectory) {
    public RemoteWorkspaceRequest {
        if (uri == null || uri.getScheme() == null || uri.getScheme().isBlank()) {
            throw new IllegalArgumentException("remote workspace URI with a scheme is required");
        }
        if (storageDirectory == null) {
            throw new IllegalArgumentException("remote workspace storage directory is required");
        }
    }
}
