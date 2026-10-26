package shed;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Deliberately adversarial headless peer. SDK interoperability has a separate implementation. */
public final class AcpFixtureProcess {
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("java.awt.headless")) throw new IllegalStateException("Headless fixture required");
        String mode = args[0];
        if (mode.equals("child")) { Thread.sleep(60000); return; }
        if (mode.equals("malformed")) { System.out.println("{bad"); Thread.sleep(60000); return; }
        if (mode.equals("utf8")) { System.out.write(new byte[]{(byte) 0xc3, 10}); System.out.flush(); Thread.sleep(60000); return; }
        if (mode.equals("oversized")) { System.out.print("x".repeat(AcpConnection.MAX_FRAME + 1)); System.out.flush(); Thread.sleep(60000); return; }
        if (mode.equals("stderr")) { System.err.print("diagnostic ".repeat(20000)); System.err.flush(); }
        if (mode.equals("descendant")) {
            var command = AcpConnectionTest.command("child"); Process child = new ProcessBuilder(command).start();
            send(Map.of("jsonrpc", "2.0", "method", "child", "params", Map.of("pid", child.pid())));
        }
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Object prompt = null;
        boolean authenticated = false;
        for (String line; (line = reader.readLine()) != null;) {
            var request = MiniJson.asObject(MiniJson.parseStrict(line)); String method = MiniJson.asString(request.get("method"));
            if (mode.equals("hang")) continue;
            if (method == null) {
                if (mode.equals("multiplex") && request.get("id").equals("permission")) reply(prompt, Map.of("permission", request.get("result")));
                continue;
            }
            if (method.equals("storm")) {
                for (int i = 0; i < 128; i++) {
                    send(Map.of("jsonrpc", "2.0", "id", "blocked-" + i, "method", "permission", "params", Map.of()));
                    Thread.sleep(2);
                    send(Map.of("jsonrpc", "2.0", "method", "$/cancel_request", "params", Map.of("requestId", "blocked-" + i)));
                }
                reply(request.get("id"), Map.of());
            } else if (method.equals("pending")) {
                prompt = request.get("id"); send(Map.of("jsonrpc", "2.0", "id", "permission", "method", "permission", "params", Map.of()));
            } else if (method.equals("cancel-peer-request")) {
                send(Map.of("jsonrpc", "2.0", "method", "$/cancel_request", "params", Map.of("requestId", "permission")));
                reply(request.get("id"), Map.of());
            } else if (method.equals("null-result")) {
                var response = new java.util.LinkedHashMap<String, Object>();
                response.put("jsonrpc", "2.0"); response.put("id", request.get("id")); response.put("result", null); send(response);
            } else if (method.equals("initialize")) {
                reply(request.get("id"), Map.of("protocolVersion", mode.equals("version") ? 99 : 1,
                    "agentCapabilities", Map.of("loadSession", mode.equals("load")), "authMethods", mode.equals("auth")
                        ? List.of(Map.of("id", "fixture-login", "name", "Fixture sign-in")) : List.of()));
            } else if (method.equals("authenticate")) {
                authenticated = true; reply(request.get("id"), Map.of());
            } else if (method.equals("session/new")) {
                if (mode.equals("auth") && !authenticated) send(Map.of("jsonrpc", "2.0", "id", request.get("id"),
                    "error", Map.of("code", -32000, "message", "Authentication required")));
                else reply(request.get("id"), Map.of("sessionId", "fixture-session"));
            }
            else if (method.equals("session/load")) reply(request.get("id"), Map.of());
            else if (method.equals("session/prompt")) {
                prompt = request.get("id");
                if (!mode.equals("ignore-cancel")) {
                    send(Map.of("jsonrpc", "2.0", "method", "session/update", "params", Map.of("sessionId", "fixture-session",
                        "update", Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text", "text", "streamed")))));
                }
            } else if (method.equals("session/cancel")) {
                if (!mode.equals("ignore-cancel")) reply(prompt, Map.of("stopReason", "cancelled"));
            } else if (method.equals("error")) send(Map.of("jsonrpc", "2.0", "id", request.get("id"), "error", Map.of("code", -32000, "message", "Authentication required", "data", Map.of("method", "login"))));
            else if (request.containsKey("id")) reply(request.get("id"), Map.of("method", method));
        }
    }
    private static void reply(Object id, Map<String, Object> value) { send(Map.of("jsonrpc", "2.0", "id", id, "result", value)); }
    private static void send(Map<String, Object> value) { System.out.println(MiniJson.stringify(value)); System.out.flush(); }
}
