package shed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small child-process fixture for the LSP document-highlight integration test. */
public final class ReferenceDocumentHighlightLanguageServer {
    private ReferenceDocumentHighlightLanguageServer() {
    }

    public static void main(String[] arguments) throws Exception {
        BufferedInputStream input = new BufferedInputStream(System.in);
        while (true) {
            Map<String, Object> request = readMessage(input);
            if (request == null) return;
            String method = MiniJson.asString(request.get("method"));
            if (method == null) continue;
            switch (method) {
                case "initialize" -> respond(System.out, request.get("id"), Map.of(
                    "capabilities", Map.of("documentHighlightProvider", Boolean.TRUE)
                ));
                case "textDocument/documentHighlight" -> respond(System.out, request.get("id"), highlights(request));
                case "shutdown" -> respond(System.out, request.get("id"), null);
                case "exit" -> {
                    return;
                }
                default -> {
                    // LSP lifecycle notifications need no response in this fixture.
                }
            }
        }
    }

    private static List<Map<String, Object>> highlights(Map<String, Object> request) {
        Map<String, Object> params = MiniJson.asObject(request.get("params"));
        Map<String, Object> position = MiniJson.asObject(params == null ? null : params.get("position"));
        int line = number(position == null ? null : position.get("line"));
        int character = number(position == null ? null : position.get("character"));
        return List.of(
            highlight(line, character, line, character + 4, 2),
            highlight(line + 1, 0, line + 1, 4, 3)
        );
    }

    private static Map<String, Object> highlight(int startLine, int startCharacter, int endLine, int endCharacter, int kind) {
        return Map.of(
            "range", Map.of(
                "start", Map.of("line", startLine, "character", startCharacter),
                "end", Map.of("line", endLine, "character", endCharacter)
            ),
            "kind", kind
        );
    }

    private static void respond(OutputStream output, Object id, Object result) throws IOException {
        if (id == null) return;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        byte[] payload = MiniJson.stringify(response).getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(payload);
        output.flush();
    }

    private static Map<String, Object> readMessage(InputStream input) throws IOException {
        StringBuilder headers = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            headers.append((char) current);
            if (previous == '\r' && current == '\n' && headers.toString().endsWith("\r\n\r\n")) break;
            previous = current;
        }
        if (headers.isEmpty()) return null;
        int length = contentLength(headers.toString());
        if (length < 0) return null;
        byte[] body = input.readNBytes(length);
        return body.length == length ? MiniJson.asObject(MiniJson.parse(new String(body, StandardCharsets.UTF_8))) : null;
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                try {
                    return Integer.parseInt(line.substring("Content-Length:".length()).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static int number(Object value) {
        Integer parsed = MiniJson.asInt(value);
        return parsed == null ? 0 : Math.max(0, parsed);
    }
}
