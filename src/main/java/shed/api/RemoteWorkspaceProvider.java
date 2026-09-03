package shed.api;

import java.net.URI;

/** Provider for an explicitly requested remote workspace scheme. */
public interface RemoteWorkspaceProvider {
    String id();

    String displayName();

    boolean supports(URI uri);

    RemoteWorkspace connect(RemoteWorkspaceRequest request) throws Exception;
}
