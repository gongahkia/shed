package shed;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Window-free entry point. The initial CLI is explicitly read-only and disk-backed. */
public final class WorkspaceCli {
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    private WorkspaceCli() { }

    public static boolean handles(String[] args) { return args != null && args.length > 0 && "--workspace-api".equals(args[0]); }

    public static int run(String[] args, InputStream input, OutputStream output, OutputStream errors) {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        PrintWriter diagnostics = new PrintWriter(new OutputStreamWriter(errors, StandardCharsets.UTF_8), true);
        try {
            if (!handles(args)) throw new IllegalArgumentException("Expected --workspace-api");
            String root = null, request = null; boolean stdio = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--root" -> { if (root != null || ++i == args.length) throw new IllegalArgumentException("Expected one --root directory"); root = args[i]; }
                    case "--request" -> { if (request != null || ++i == args.length) throw new IllegalArgumentException("Expected one --request JSON value"); request = args[i]; }
                    case "--stdio" -> { if (stdio) throw new IllegalArgumentException("Duplicate --stdio"); stdio = true; }
                    default -> throw new IllegalArgumentException("Unsupported workspace API argument");
                }
            }
            if (root == null || stdio && request != null) throw new IllegalArgumentException(
                "Usage: shed --workspace-api --root <directory> [--stdio | --request <JSON>]");
            WorkspaceRpc rpc = new WorkspaceRpc(new WorkspaceQueryService(Path.of(root), AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()));
            if (request != null) {
                if (request.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES)
                    throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Request exceeds its byte limit");
                String response = rpc.handle(request);
                if (response != null) writer.println(response);
                return writer.checkError() ? 74 : response != null && MiniJson.asObject(MiniJson.parseStrict(response)).containsKey("error") ? 65 : 0;
            }
            BufferedInputStream buffered = new BufferedInputStream(input);
            byte[] frame;
            while ((frame = frame(buffered)) != null) {
                String response;
                try {
                    String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame)).toString();
                    response = rpc.handle(decoded);
                } catch (java.nio.charset.CharacterCodingException invalid) {
                    response = WorkspaceRpc.error(null, -32700, "Request is not valid UTF-8", Map.of("code", "INVALID_REQUEST"));
                }
                if (response != null) writer.println(response);
                if (writer.checkError()) return 74;
            }
            return 0;
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

    private static byte[] frame(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') break;
            if (bytes.size() == MAX_FRAME_BYTES) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Request exceeds its byte limit");
            bytes.write(next);
        }
        if (next == -1 && bytes.size() == 0) return null;
        byte[] frame = bytes.toByteArray();
        if (frame.length > 0 && frame[frame.length - 1] == '\r') return java.util.Arrays.copyOf(frame, frame.length - 1);
        return frame;
    }
}
