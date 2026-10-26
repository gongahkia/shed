package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** One explicitly owned ACP connection/session, independent of Swing and the active editor tab. */
final class AgentSessionService implements AcpConnection.Handler, AutoCloseable {
    enum State { NEW, INITIALIZING, READY, AUTHENTICATING, CREATING, LOADING, IDLE, PROMPTING, CANCELLING, FAILED, CLOSED }
    record Event(long sequence, String type, Map<String, Object> data, int characters) { }
    record Permission(String id, Map<String, Object> tool, List<Map<String, Object>> options) { }
    record Snapshot(String provider, State state, String sessionId, Map<String, Object> capabilities, List<Map<String, Object>> authMethods,
                    List<Event> events, long droppedEvents, List<Permission> permissions, String error, AcpConnection.Diagnostics diagnostics) { }
    private record Decision(Permission permission, CompletableFuture<Map<String, Object>> result) { }
    private final AgentProvider provider;
    private final WorkspaceQueryService workspace;
    private final AcpConnection connection;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Map<String, Decision> decisions = new LinkedHashMap<>();
    private State state = State.NEW;
    private String sessionId, turnId, error = "";
    private Map<String, Object> capabilities = Map.of();
    private List<Map<String, Object>> authMethods = List.of();
    private long sequence, droppedEvents;
    private int eventCharacters;

    AgentSessionService(AgentProvider provider, WorkspaceQueryService workspace) throws IOException {
        workspace.policy().require(AgentPolicy.Capability.START_AGENT);
        this.provider = provider; this.workspace = workspace;
        connection = AcpConnection.start(provider.command(), workspace.root(), provider.environment(), this);
        connection.termination().thenRun(() -> {
            synchronized (this) {
                if (state != State.CLOSED) { state = State.FAILED; error = connection.diagnostics().failure(); }
                cancelPermissions(); record("connection_closed", Map.of("reason", connection.diagnostics().failure()));
            }
        });
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(provider.id(), state, sessionId, capabilities, authMethods, List.copyOf(events), droppedEvents,
            decisions.values().stream().map(Decision::permission).toList(), error, connection.diagnostics());
    }

    synchronized CompletableFuture<Map<String, Object>> initialize() {
        transition(State.NEW, State.INITIALIZING);
        boolean read = workspace.policy().capabilities().contains(AgentPolicy.Capability.READ);
        return finish(connection.request("initialize", Map.of("protocolVersion", 1,
            "clientInfo", Map.of("name", "shed", "title", "Shed", "version", "2.0.0"),
            "clientCapabilities", Map.of("fs", Map.of("readTextFile", read, "writeTextFile", false), "terminal", false)), provider.startupTimeout()), State.READY,
            response -> {
                if (!Long.valueOf(1).equals(response.get("protocolVersion"))) {
                    connection.abort("Agent negotiated an unsupported protocol version"); throw new AcpException(-32600, "Agent negotiated an unsupported protocol version");
                }
                capabilities = frozen(object(response.getOrDefault("agentCapabilities", Map.of())));
                authMethods = objects(response.getOrDefault("authMethods", List.of()), 32);
                record("initialized", Map.of("capabilities", capabilities, "authMethods", authMethods));
            });
    }

    synchronized CompletableFuture<Map<String, Object>> authenticate(String methodId) {
        workspace.policy().require(AgentPolicy.Capability.OPEN_UI);
        var method = authMethods.stream().filter(value -> methodId.equals(value.get("id"))).findFirst()
            .orElseThrow(() -> invalid("Unknown authentication method"));
        if (!"agent".equals(method.getOrDefault("type", "agent"))) throw invalid("This authentication method requires an unsupported interactive flow");
        transition(State.READY, State.AUTHENTICATING);
        return finish(connection.request("authenticate", Map.of("methodId", methodId), provider.startupTimeout()), State.READY,
            response -> record("authenticated", Map.of("methodId", methodId)));
    }

    synchronized CompletableFuture<Map<String, Object>> newSession() {
        transition(State.READY, State.CREATING);
        return finish(connection.request("session/new", Map.of("cwd", workspace.root().toString(), "mcpServers", List.of()), provider.startupTimeout()), State.IDLE,
            response -> { sessionId = text(response, "sessionId", 1024); record("session_created", Map.of("sessionId", sessionId)); });
    }

    synchronized CompletableFuture<Map<String, Object>> loadSession(String id) {
        if (!Boolean.TRUE.equals(capabilities.get("loadSession"))) throw invalid("Agent does not advertise session loading");
        if (id == null || id.isBlank() || id.length() > 1024) throw invalid("Invalid session ID");
        transition(State.READY, State.LOADING); sessionId = id;
        return finish(connection.request("session/load", Map.of("sessionId", id, "cwd", workspace.root().toString(), "mcpServers", List.of()), provider.startupTimeout()), State.IDLE,
            response -> record("session_loaded", Map.of("sessionId", id)));
    }

    synchronized CompletableFuture<Map<String, Object>> prompt(String text) {
        if (text == null || text.isBlank() || text.length() > 65536) throw invalid("A prompt must contain 1–65,536 characters");
        transition(State.IDLE, State.PROMPTING); turnId = UUID.randomUUID().toString();
        record("user_prompt", Map.of("sessionId", sessionId, "turnId", turnId, "text", text));
        return finish(connection.request("session/prompt", Map.of("sessionId", sessionId, "prompt", List.of(Map.of("type", "text", "text", text))), provider.turnTimeout()), State.IDLE,
            response -> {
                String reason = text(response, "stopReason", 64);
                if (!Set.of("end_turn", "max_tokens", "max_turn_requests", "refusal", "cancelled").contains(reason)) throw invalid("Unknown agent stop reason");
                cancelPermissions(); record("turn_finished", Map.of("sessionId", sessionId, "turnId", turnId, "stopReason", reason));
            });
    }

    synchronized void cancel() {
        if (state == State.CANCELLING) return;
        if (state != State.PROMPTING) throw invalid("No prompt is running");
        state = State.CANCELLING; cancelPermissions();
        record("cancel_requested", Map.of("sessionId", sessionId, "turnId", turnId));
        connection.notify("session/cancel", Map.of("sessionId", sessionId));
        String cancelledTurn = turnId;
        CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() -> {
            synchronized (this) {
                if (state == State.CANCELLING && Objects.equals(turnId, cancelledTurn)) {
                    error = "Agent did not acknowledge cancellation; its connection was stopped"; connection.abort(error);
                }
            }
        });
    }

    synchronized void decide(String id, String optionId) {
        Decision decision = decisions.get(id);
        if (decision == null || state != State.PROMPTING) throw invalid("Permission request is no longer pending");
        var selected = decision.permission().options().stream().filter(option -> optionId.equals(option.get("optionId"))).findFirst()
            .orElseThrow(() -> invalid("Unknown permission option"));
        String kind = text(selected, "kind", 32);
        if (kind.equals("allow_always")) throw invalid("Persistent grants require task policy support; choose allow once");
        if (kind.equals("allow_once")) authorizeTool(decision.permission().tool());
        decisions.remove(id); decision.result().complete(Map.of("outcome", Map.of("outcome", "selected", "optionId", optionId)));
        record("permission_decided", Map.of("id", id, "optionId", optionId, "kind", kind));
    }

    @Override public CompletionStage<Map<String, Object>> request(String method, Map<String, Object> params) {
        try {
            synchronized (this) { requireSession(params); }
            return switch (method) {
                case "fs/read_text_file" -> CompletableFuture.completedFuture(read(params));
                case "session/request_permission" -> permission(params);
                default -> CompletableFuture.failedFuture(new AcpException(-32601, "Client method is unavailable: " + method));
            };
        } catch (WorkspaceApiException domain) {
            return CompletableFuture.failedFuture(new AcpException(domain.code() == WorkspaceApiException.Code.NOT_FOUND ? -32002 : -32602,
                domain.getMessage(), domain.toMap()));
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    @Override public synchronized void notification(String method, Map<String, Object> params) {
        if (!method.equals("session/update")) return;
        String id = text(params, "sessionId", 1024);
        // Session creation may publish commands/modes before returning its new ID. Keep the actual
        // ID on those events; never retarget them to a later session or an active editor tab.
        if (state != State.CREATING && !Objects.equals(id, sessionId)) throw invalid("Update belongs to another session");
        var update = object(params.get("update")); text(update, "sessionUpdate", 128);
        record("session_update", params);
    }

    private Map<String, Object> read(Map<String, Object> params) {
        String requested = text(params, "path", 8192);
        if (!Path.of(requested).isAbsolute()) throw invalid("ACP file paths must be absolute");
        var document = workspace.snapshot(workspace.workspaceId(), requested);
        if (!document.complete()) throw invalid("A partial editor projection cannot be read as a complete file");
        int line = integer(params, "line", 1, 1), limit = integer(params, "limit", Integer.MAX_VALUE, 0);
        String content = document.content(); int start = 0, end;
        for (int current = 1; current < line && start < content.length(); current++) {
            int next = content.indexOf('\n', start); start = next < 0 ? content.length() : next + 1;
        }
        end = start;
        for (int count = 0; count < limit && end < content.length(); count++) {
            int next = content.indexOf('\n', end); end = next < 0 ? content.length() : next + 1;
            if (end - start > 65536) throw invalid("File response exceeds its bound; request fewer lines");
        }
        synchronized (this) { requireSession(params); record("file_read", Map.of("sessionId", sessionId, "document", document.documentId(), "version", document.version(), "line", line)); }
        return Map.of("content", content.substring(start, end));
    }

    private synchronized CompletableFuture<Map<String, Object>> permission(Map<String, Object> params) {
        requireSession(params);
        if (state == State.CANCELLING) return CompletableFuture.completedFuture(cancelled());
        if (state != State.PROMPTING || decisions.size() >= 32) throw invalid("Permission request is unavailable in this state");
        var tool = frozen(object(params.get("toolCall"))); text(tool, "toolCallId", 1024);
        var options = objects(params.get("options"), 32); if (options.isEmpty()) throw invalid("Permission options are required");
        var seen = new java.util.HashSet<String>();
        for (var option : options) {
            if (!seen.add(text(option, "optionId", 1024))) throw invalid("Duplicate permission option");
            text(option, "name", 1024);
            if (!Set.of("allow_once", "allow_always", "reject_once", "reject_always").contains(text(option, "kind", 32))) throw invalid("Invalid permission kind");
        }
        String id = UUID.randomUUID().toString(); var future = new CompletableFuture<Map<String, Object>>();
        decisions.put(id, new Decision(new Permission(id, tool, options), future));
        future.whenComplete((result, failure) -> { synchronized (this) { decisions.remove(id); } });
        record("permission_requested", Map.of("id", id, "tool", tool, "options", options)); return future;
    }

    private void authorizeTool(Map<String, Object> tool) {
        AgentPolicy.Capability capability = switch (Objects.toString(tool.get("kind"), "")) {
            case "read", "search" -> AgentPolicy.Capability.READ;
            case "edit", "delete", "move" -> AgentPolicy.Capability.APPLY;
            case "execute" -> AgentPolicy.Capability.RUN_HEADLESS;
            case "fetch" -> AgentPolicy.Capability.NETWORK;
            default -> throw invalid("This tool kind has no supported permission policy");
        };
        workspace.policy().require(capability);
        for (var location : objects(tool.getOrDefault("locations", List.of()), 128)) {
            Path path = WorkspacePaths.resolve(workspace.root(), text(location, "path", 8192));
            workspace.policy().checkPath(workspace.root().relativize(path), capability);
        }
    }

    private CompletableFuture<Map<String, Object>> finish(CompletableFuture<Map<String, Object>> request, State success,
                                                         java.util.function.Consumer<Map<String, Object>> accept) {
        State operation = state;
        return request.handle((result, failure) -> {
            synchronized (this) {
                if (state == State.CLOSED || state == State.FAILED) throw new AcpException(-32800, "Agent connection is no longer active");
                if (failure == null) try { accept.accept(result); state = success; error = ""; return result; }
                catch (RuntimeException malformed) { state = State.FAILED; error = malformed.getMessage(); connection.abort(error); throw malformed; }
                Throwable cause = failure instanceof java.util.concurrent.CompletionException ? failure.getCause() : failure;
                error = cause.getMessage(); cancelPermissions();
                if (operation == State.INITIALIZING) { state = State.FAILED; connection.close(); }
                else if (!connection.diagnostics().running()) state = State.FAILED;
                else if (operation == State.CREATING || operation == State.LOADING || operation == State.AUTHENTICATING) {
                    sessionId = null; state = State.READY;
                } else state = State.IDLE;
                record("request_failed", Map.of("message", Objects.toString(error, "Agent request failed"), "code", cause instanceof AcpException domain ? domain.code() : -32603));
                throw new java.util.concurrent.CompletionException(cause);
            }
        });
    }
    private void requireSession(Map<String, Object> params) {
        if (sessionId == null || !sessionId.equals(text(params, "sessionId", 1024)) || !Set.of(State.IDLE, State.PROMPTING, State.CANCELLING, State.LOADING).contains(state))
            throw invalid("Request does not name the active session");
    }
    private void transition(State expected, State next) { if (state != expected) throw invalid("Agent is " + state + "; expected " + expected); state = next; }
    private void cancelPermissions() { for (Decision decision : new ArrayList<>(decisions.values())) decision.result().complete(cancelled()); decisions.clear(); }
    private static Map<String, Object> cancelled() { return Map.of("outcome", Map.of("outcome", "cancelled")); }
    private void record(String type, Map<String, Object> data) {
        var value = frozen(data); int size = MiniJson.stringify(value).length();
        events.addLast(new Event(++sequence, type, value, size)); eventCharacters += size;
        while (events.size() > 1000 || eventCharacters > 2 * 1024 * 1024) { eventCharacters -= events.removeFirst().characters(); droppedEvents++; }
    }
    @Override public synchronized void close() { if (state == State.CLOSED) return; state = State.CLOSED; cancelPermissions(); connection.close(); }
    private static AcpException invalid(String message) { return new AcpException(-32602, message); }
    private static String text(Map<String, Object> value, String key, int max) {
        if (!(value.get(key) instanceof String text) || text.isBlank() || text.length() > max) throw invalid("Invalid " + key); return text;
    }
    private static int integer(Map<String, Object> value, String key, int fallback, int minimum) {
        if (!value.containsKey(key)) return fallback;
        if (!(value.get(key) instanceof Long number) || number < minimum || number > Integer.MAX_VALUE) throw invalid("Invalid " + key); return number.intValue();
    }
    private static Map<String, Object> object(Object value) { var object = MiniJson.asObject(value); if (object == null) throw invalid("Expected an object"); return object; }
    private static List<Map<String, Object>> objects(Object value, int maximum) {
        var list = MiniJson.asArray(value); if (list == null || list.size() > maximum) throw invalid("Invalid bounded list");
        return list.stream().map(AgentSessionService::object).map(AgentSessionService::frozen).toList();
    }
    private static Map<String, Object> frozen(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>(); value.forEach((key, item) -> result.put(key, freeze(item)));
        return java.util.Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?>) return frozen(object(value));
        if (value instanceof List<?> list) return list.stream().map(AgentSessionService::freeze).toList();
        return value;
    }
}
