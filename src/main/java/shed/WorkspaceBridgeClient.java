package shed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;

/** Headless client. Descriptor paths are explicit; no broadcasts, remote hosts or automatic attach. */
final class WorkspaceBridgeClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedInputStream input;

    WorkspaceBridgeClient(Path descriptor, Path requestedRoot) throws IOException {
        WorkspaceBridgeEndpoint endpoint = WorkspaceBridgeEndpoint.read(descriptor);
        if (!endpoint.root.equals(WorkspacePaths.root(requestedRoot)))
            throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_FOUND, "Connection belongs to a different workspace");
        this.socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(WorkspaceBridgeServer.loopback(), endpoint.port), 3000);
            socket.setSoTimeout(30_000); socket.setTcpNoDelay(true);
            this.input = new BufferedInputStream(socket.getInputStream());
            WorkspaceFrames.write(socket.getOutputStream(), endpoint.authenticationRequest(), 4096);
            byte[] reply = WorkspaceFrames.read(input, 4096);
            if (reply == null || !endpoint.confirms(WorkspaceFrames.decode(reply)))
                throw new WorkspaceApiException(WorkspaceApiException.Code.PERMISSION_DENIED, "Workspace connection authentication failed");
        } catch (IOException | RuntimeException failure) { socket.close(); throw failure; }
    }

    String exchange(String request) throws IOException {
        WorkspaceFrames.write(socket.getOutputStream(), request, WorkspaceFrames.MAX_REQUEST_BYTES);
        if (!WorkspaceRpc.expectsResponse(request)) return null;
        byte[] response = WorkspaceFrames.read(input, WorkspaceFrames.MAX_RESPONSE_BYTES);
        if (response == null) throw new IOException("Workspace connection closed before answering");
        return WorkspaceFrames.decode(response);
    }

    @Override public void close() throws IOException { socket.close(); }
}
