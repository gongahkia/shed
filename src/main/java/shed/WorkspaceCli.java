package shed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Window-free entry point. Disk-only by default; live attachment is explicit. */
public final class WorkspaceCli {
    public static final int MAX_FRAME_BYTES = WorkspaceFrames.MAX_REQUEST_BYTES;
    private WorkspaceCli() { }

    public static boolean handles(String[] args) { return args != null && args.length > 0 && "--workspace-api".equals(args[0]); }

    public static int run(String[] args, InputStream input, OutputStream output, OutputStream errors) {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        PrintWriter diagnostics = new PrintWriter(new OutputStreamWriter(errors, StandardCharsets.UTF_8), true);
        try {
            if (!handles(args)) throw new IllegalArgumentException("Expected --workspace-api");
            String root = null, request = null, connection = null; boolean stdio = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--root" -> { if (root != null || ++i == args.length) throw new IllegalArgumentException("Expected one --root directory"); root = args[i]; }
                    case "--request" -> { if (request != null || ++i == args.length) throw new IllegalArgumentException("Expected one --request JSON value"); request = args[i]; }
                    case "--stdio" -> { if (stdio) throw new IllegalArgumentException("Duplicate --stdio"); stdio = true; }
                    case "--connect" -> { if (connection != null || ++i == args.length) throw new IllegalArgumentException("Expected one --connect descriptor"); connection = args[i]; }
                    default -> throw new IllegalArgumentException("Unsupported workspace API argument");
                }
            }
            if (root == null || stdio && request != null) throw new IllegalArgumentException(
                "Usage: shed --workspace-api --root <directory> [--connect <descriptor>] [--stdio | --request <JSON>]");
            try (WorkspaceBridgeClient remote = connection == null ? null : new WorkspaceBridgeClient(Path.of(connection), Path.of(root))) {
                RequestHandler handler = remote == null
                    ? new WorkspaceRpc(new WorkspaceQueryService(Path.of(root), AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()))::handle
                    : remote::exchange;
                return serve(handler, request, input, writer);
            }
        } catch (WorkspaceApiException error) {
            if (error.code() == WorkspaceApiException.Code.LIMIT_EXCEEDED)
                writer.println(WorkspaceRpc.error(null, -32000, error.getMessage(), error.toMap()));
            else diagnostics.println(MiniJson.stringify(Map.of("error", error.toMap())));
            return 65;
        } catch (IllegalArgumentException invalid) {
            diagnostics.println(MiniJson.stringify(Map.of("error", Map.of("code", "INVALID_REQUEST", "message", invalid.getMessage()))));
            return 64;
        } catch (IOException error) {
            diagnostics.println(MiniJson.stringify(Map.of("error", Map.of("code", "IO_ERROR", "message", "Workspace stream failed"))));
            return 74;
        }
    }

    private static int serve(RequestHandler handler, String request, InputStream input, PrintWriter writer) throws IOException {
        if (request != null) {
            if (request.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES)
                throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Request exceeds its byte limit");
            String response = handler.handle(request);
            if (response != null) writer.println(response);
            return writer.checkError() ? 74 : response != null && MiniJson.asObject(MiniJson.parseStrict(response)).containsKey("error") ? 65 : 0;
        }
        BufferedInputStream buffered = new BufferedInputStream(input);
        byte[] frame;
        while ((frame = WorkspaceFrames.read(buffered, MAX_FRAME_BYTES)) != null) {
            String response;
            try { response = handler.handle(WorkspaceFrames.decode(frame)); }
            catch (java.nio.charset.CharacterCodingException invalid) {
                response = WorkspaceRpc.error(null, -32700, "Request is not valid UTF-8", Map.of("code", "INVALID_REQUEST"));
            }
            if (response != null) writer.println(response);
            if (writer.checkError()) return 74;
        }
        return 0;
    }

    @FunctionalInterface private interface RequestHandler { String handle(String request) throws IOException; }
}
