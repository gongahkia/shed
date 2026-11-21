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
import java.util.Set;

/** Window-free entry point. Disk-only by default; live attachment is explicit. */
public final class WorkspaceCli {
    public static final int MAX_FRAME_BYTES = WorkspaceFrames.MAX_REQUEST_BYTES;
    private WorkspaceCli() { }

    public static boolean handles(String[] args) { return args != null && args.length > 0 && "--workspace-api".equals(args[0]); }

    public static int run(String[] args, InputStream input, OutputStream output, OutputStream errors) {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        PrintWriter diagnostics = new PrintWriter(new OutputStreamWriter(errors, StandardCharsets.UTF_8), true);
        boolean status = false, metadata = false, navigation = false;
        try {
            if (!handles(args)) throw new IllegalArgumentException("Expected --workspace-api");
            String root = null, request = null, connection = null, credential = null; boolean stdio = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--root" -> { if (root != null || ++i == args.length) throw new IllegalArgumentException("Expected one --root directory"); root = args[i]; }
                    case "--request" -> { if (request != null || ++i == args.length) throw new IllegalArgumentException("Expected one --request JSON value"); request = args[i]; }
                    case "--stdio" -> { if (stdio) throw new IllegalArgumentException("Duplicate --stdio"); stdio = true; }
                    case "--connect" -> { if (connection != null || ++i == args.length) throw new IllegalArgumentException("Expected one --connect descriptor"); connection = args[i]; }
                    case "--credential" -> { if (credential != null || ++i == args.length) throw new IllegalArgumentException("Expected one --credential file"); credential = args[i]; }
                    case "--metadata" -> { if (metadata) throw new IllegalArgumentException("Duplicate --metadata"); metadata = true; }
                    case "--navigate" -> { if (navigation) throw new IllegalArgumentException("Duplicate --navigate"); navigation = true; }
                    case "--status" -> { if (status) throw new IllegalArgumentException("Duplicate --status"); status = true; }
                    default -> throw new IllegalArgumentException("Unsupported workspace API argument");
                }
            }
            if (navigation) {
                if (status || metadata || root != null || connection == null || credential == null || stdio)
                    throw new IllegalArgumentException("Navigation requires one explicit descriptor and credential");
                return navigation(Path.of(connection), Path.of(credential), request, writer);
            }
            if (status || metadata) {
                if (status && metadata || root != null || connection == null || credential == null || stdio || request != null)
                    throw new IllegalArgumentException("Usage: shed --workspace-api --status|--metadata --connect <descriptor> --credential <file>");
                return status(Path.of(connection), Path.of(credential), writer, metadata);
            }
            if (credential != null) throw new IllegalArgumentException("--credential requires --status or --metadata");
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
            if (status || metadata || navigation) {
                writer.println(WorkspaceRpc.error(navigation ? "navigation" : metadata ? "metadata" : "status", -32602,
                    navigation ? "Invalid navigation arguments" : metadata ? "Invalid metadata arguments" : "Invalid status arguments", Map.of("code", "INVALID_REQUEST")));
                return 64;
            }
            diagnostics.println(MiniJson.stringify(Map.of("error", Map.of("code", "INVALID_REQUEST", "message", invalid.getMessage()))));
            return 64;
        } catch (IOException error) {
            diagnostics.println(MiniJson.stringify(Map.of("error", Map.of("code", "IO_ERROR", "message", "Workspace stream failed"))));
            return 74;
        }
    }

    private static int navigation(Path descriptor, Path credential, String request, PrintWriter output) {
        boolean mutation = false;
        String response;
        try (var client = WorkspaceBridgeClient.navigation(descriptor, credential)) {
            String query = request == null ? client.navigationContextRequest() : request;
            var envelope = MiniJson.asObject(MiniJson.parseStrict(query));
            mutation = envelope != null && envelope.get("method") instanceof String method && EditorNavigation.MUTATIONS.contains(method);
            response = client.exchange(query);
        } catch (WorkspaceApiException failure) { return navigationFailure(output, "navigation", failure.code().name(), failure.code() == WorkspaceApiException.Code.OUTCOME_UNKNOWN
            || mutation && Set.of(WorkspaceApiException.Code.TIMEOUT, WorkspaceApiException.Code.SESSION_INVALID, WorkspaceApiException.Code.SESSION_EXPIRED).contains(failure.code())); }
        catch (java.nio.file.NoSuchFileException | java.net.ConnectException absent) { return navigationFailure(output, "navigation", "INSTANCE_UNAVAILABLE", false); }
        catch (java.nio.file.FileSystemException denied) { return navigationFailure(output, "navigation", "UNAUTHORIZED", false); }
        catch (java.io.IOException failed) { return navigationFailure(output, "navigation", "TRANSPORT_ERROR", mutation); }
        catch (RuntimeException invalid) { return navigationFailure(output, "navigation", "INVALID_REQUEST", false); }
        var value = MiniJson.asObject(MiniJson.parseStrict(response));
        if (value.containsKey("error")) {
            var error = MiniJson.asObject(value.get("error")); var data = error == null ? null : MiniJson.asObject(error.get("data"));
            try {
                String code = WorkspaceApiException.Code.valueOf((String) data.get("code")).name();
                return navigationFailure(output, value.get("id"), code, "unknown".equals(data.get("outcome")) || "OUTCOME_UNKNOWN".equals(code));
            } catch (RuntimeException invalid) { return navigationFailure(output, "navigation", "SESSION_INVALID", mutation); }
        }
        output.println(response); return output.checkError() ? 74 : 0;
    }
    private static int navigationFailure(PrintWriter output, Object id, String code, boolean unknown) {
        output.println(WorkspaceRpc.error(id, -32000, "Navigation request failed", Map.of("code", code, "outcome", unknown ? "unknown" : "rejected")));
        return output.checkError() ? 74 : unknown ? 79 : statusExit(code);
    }

    private static int status(Path descriptor, Path credential, PrintWriter output, boolean metadata) {
        String response;
        try (WorkspaceBridgeClient client = metadata ? WorkspaceBridgeClient.metadata(descriptor, credential) : WorkspaceBridgeClient.status(descriptor, credential)) {
            response = client.exchange(metadata ? client.metadataRequest() : MiniJson.stringify(Map.of("jsonrpc", "2.0", "id", "status", "method", "editor/status",
                "params", Map.of("schemaVersion", 1))));
        } catch (WorkspaceApiException failure) { return statusFailure(output, failure.code().name(), metadata); }
        catch (java.net.SocketTimeoutException timeout) { return statusFailure(output, "TIMEOUT", metadata); }
        catch (java.nio.file.NoSuchFileException | java.net.ConnectException unavailable) { return statusFailure(output, "INSTANCE_UNAVAILABLE", metadata); }
        catch (java.nio.file.FileSystemException denied) { return statusFailure(output, "UNAUTHORIZED", metadata); }
        catch (IOException failure) { return statusFailure(output, "TRANSPORT_ERROR", metadata); }
        catch (RuntimeException invalid) { return statusFailure(output, "SESSION_INVALID", metadata); }
        Map<String, Object> value = MiniJson.asObject(MiniJson.parseStrict(response));
        if (value.containsKey("error")) {
            var error = MiniJson.asObject(value.get("error")); var data = error == null ? null : MiniJson.asObject(error.get("data"));
            try { return statusFailure(output, WorkspaceApiException.Code.valueOf((String) data.get("code")).name(), metadata); }
            catch (RuntimeException invalid) { return statusFailure(output, "SESSION_INVALID", metadata); }
        }
        output.println(response);
        return output.checkError() ? 74 : 0;
    }

    private static int statusFailure(PrintWriter output, String code, boolean metadata) {
        output.println(WorkspaceRpc.error(metadata ? "metadata" : "status", -32000, metadata ? "Metadata attachment failed" : "Status attachment failed", Map.of("code", code)));
        return output.checkError() ? 74 : statusExit(code);
    }
    private static int statusExit(String code) {
        return switch (code) {
            case "INSTANCE_UNAVAILABLE" -> 69;
            case "UNAUTHORIZED", "PROJECT_UNAUTHORIZED", "PERMISSION_DENIED" -> 77;
            case "UNSUPPORTED_PROTOCOL" -> 76;
            case "SESSION_INVALID", "SESSION_EXPIRED", "AUTHORIZATION_REVOKED", "APP_SHUTTING_DOWN" -> 78;
            case "APP_NOT_READY", "NO_ACTIVE_EDITOR", "PANE_UNAVAILABLE", "CONTEXT_CHANGED", "INVALID_CONTEXT", "BUSY",
                "STALE_REVISION", "INVALID_OFFSET", "BOUNDARY_UNAVAILABLE", "GEOMETRY_UNAVAILABLE", "UNSUPPORTED_MODE" -> 75;
            case "INVALID_REQUEST" -> 64;
            case "TIMEOUT" -> 70;
            default -> 74;
        };
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
