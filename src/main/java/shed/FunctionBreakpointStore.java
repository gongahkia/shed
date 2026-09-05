package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Persists workspace-scoped DAP function breakpoints independently from source breakpoints. */
final class FunctionBreakpointStore {
    enum State { REQUESTED, VERIFIED, REJECTED }

    record Breakpoint(String name, State state, String message, boolean enabled, String condition, String hitCondition) {
        Breakpoint {
            name = name == null ? "" : name.trim();
            if (name.isEmpty() || name.length() > 512 || containsUnsafeControl(name)) {
                throw new IllegalArgumentException("function breakpoint name is invalid");
            }
            state = state == null ? State.REQUESTED : state;
            message = message == null ? "" : message;
            condition = option(condition, "condition");
            hitCondition = option(hitCondition, "hit condition");
        }

        Breakpoint(String name) { this(name, State.REQUESTED, "", true, "", ""); }

        @Override public String toString() { return (enabled ? "● " : "○ ") + name + "  " + state; }
    }

    record SyncResult(List<Breakpoint> breakpoints, List<String> diagnostics) {
        SyncResult {
            breakpoints = breakpoints == null ? List.of() : List.copyOf(breakpoints);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private static final int VERSION = 1;
    private final Path directory;
    private final Map<Path, Map<String, Breakpoint>> workspaces = new LinkedHashMap<>();

    FunctionBreakpointStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    synchronized List<Breakpoint> breakpoints(Path workspace) throws IOException {
        List<Breakpoint> values = new ArrayList<>(loaded(root(workspace)).values());
        values.sort(Comparator.comparing(Breakpoint::name));
        return List.copyOf(values);
    }

    synchronized Breakpoint add(Path workspace, String name) throws IOException {
        Path root = root(workspace);
        Breakpoint breakpoint = new Breakpoint(name);
        Map<String, Breakpoint> values = loaded(root);
        if (values.containsKey(breakpoint.name())) throw new IllegalArgumentException("function breakpoint already exists");
        values.put(breakpoint.name(), breakpoint);
        save(root, values);
        return breakpoint;
    }

    synchronized Breakpoint configure(Path workspace, String name, boolean enabled, String condition, String hitCondition) throws IOException {
        Path root = root(workspace);
        String key = new Breakpoint(name).name();
        Map<String, Breakpoint> values = loaded(root);
        Breakpoint existing = values.get(key);
        if (existing == null) throw new IllegalArgumentException("function breakpoint is unavailable");
        Breakpoint updated = new Breakpoint(existing.name(), State.REQUESTED, "", enabled, condition, hitCondition);
        values.put(updated.name(), updated);
        save(root, values);
        return updated;
    }

    synchronized boolean remove(Path workspace, String name) throws IOException {
        Path root = root(workspace);
        String key = new Breakpoint(name).name();
        Map<String, Breakpoint> values = loaded(root);
        if (values.remove(key) == null) return false;
        save(root, values);
        return true;
    }

    synchronized void reject(Path workspace, Breakpoint requested, String message) throws IOException {
        if (requested == null) return;
        Path root = root(workspace);
        Map<String, Breakpoint> values = loaded(root);
        Breakpoint current = values.get(requested.name());
        if (current == null || !current.equals(requested)) return;
        values.put(current.name(), new Breakpoint(current.name(), State.REJECTED, message, current.enabled(), current.condition(), current.hitCondition()));
        save(root, values);
    }

    synchronized SyncResult apply(Path workspace, List<Breakpoint> requested, Object responseBody) throws IOException {
        Path root = root(workspace);
        List<Breakpoint> original = requested == null ? List.of() : List.copyOf(requested);
        Map<String, Object> body = MiniJson.asObject(responseBody);
        List<Object> returned = MiniJson.asArray(body == null ? null : body.get("breakpoints"));
        if (returned == null) return new SyncResult(List.of(), List.of("DAP setFunctionBreakpoints returned no breakpoint results."));
        Map<String, Breakpoint> values = loaded(root);
        List<Breakpoint> updated = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (int index = 0; index < original.size(); index++) {
            Breakpoint current = original.get(index);
            Map<String, Object> result = index < returned.size() ? MiniJson.asObject(returned.get(index)) : null;
            Breakpoint applied = fromAdapter(current, result);
            updated.add(applied);
            if (original.get(index).equals(values.get(applied.name()))) values.put(applied.name(), applied);
            if (applied.state() == State.REJECTED) {
                diagnostics.add("Function breakpoint '" + applied.name() + "' was rejected" + (applied.message().isBlank() ? "." : ": " + applied.message()));
            }
        }
        save(root, values);
        return new SyncResult(updated, diagnostics);
    }

    private Map<String, Breakpoint> loaded(Path workspace) throws IOException {
        Map<String, Breakpoint> loaded = workspaces.get(workspace);
        if (loaded != null) return loaded;
        Map<String, Breakpoint> parsed = read(workspace);
        workspaces.put(workspace, parsed);
        return parsed;
    }

    private Map<String, Breakpoint> read(Path workspace) throws IOException {
        Path target = target(workspace);
        if (!Files.exists(target)) return new LinkedHashMap<>();
        try {
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(Files.readString(target, StandardCharsets.UTF_8)));
            if (root == null || root.size() != 3 || !root.containsKey("version") || !root.containsKey("workspace") || !root.containsKey("breakpoints")) {
                throw new IllegalArgumentException("root fields are invalid");
            }
            Object version = root.get("version");
            if (!(version instanceof Number number) || number.intValue() != VERSION || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException("version is unsupported");
            }
            String persistedWorkspace = MiniJson.asString(root.get("workspace"));
            if (persistedWorkspace == null || !workspace.equals(Path.of(persistedWorkspace).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("workspace does not match its storage target");
            }
            List<Object> entries = MiniJson.asArray(root.get("breakpoints"));
            if (entries == null) throw new IllegalArgumentException("breakpoints is not an array");
            Map<String, Breakpoint> values = new LinkedHashMap<>();
            for (Object entry : entries) {
                Map<String, Object> fields = MiniJson.asObject(entry);
                if (fields == null || fields.size() != 6 || !fields.containsKey("name") || !fields.containsKey("state") || !fields.containsKey("message")
                    || !fields.containsKey("enabled") || !fields.containsKey("condition") || !fields.containsKey("hitCondition")) {
                    throw new IllegalArgumentException("breakpoint fields are invalid");
                }
                String name = MiniJson.asString(fields.get("name"));
                String state = MiniJson.asString(fields.get("state"));
                String message = MiniJson.asString(fields.get("message"));
                String condition = MiniJson.asString(fields.get("condition"));
                String hitCondition = MiniJson.asString(fields.get("hitCondition"));
                if (!(fields.get("enabled") instanceof Boolean enabled) || name == null || state == null || message == null || condition == null || hitCondition == null) {
                    throw new IllegalArgumentException("breakpoint values are invalid");
                }
                Breakpoint breakpoint = new Breakpoint(name, State.valueOf(state), message, enabled, condition, hitCondition);
                if (values.put(breakpoint.name(), breakpoint) != null) throw new IllegalArgumentException("function breakpoint names are duplicated");
            }
            return values;
        } catch (RuntimeException error) {
            throw new IOException("Function breakpoint storage is invalid: " + error.getMessage(), error);
        }
    }

    private void save(Path workspace, Map<String, Breakpoint> values) throws IOException {
        Files.createDirectories(directory);
        List<Object> entries = new ArrayList<>();
        for (Breakpoint breakpoint : new TreeMap<>(values).values()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", breakpoint.name());
            value.put("state", breakpoint.state().name());
            value.put("message", breakpoint.message());
            value.put("enabled", breakpoint.enabled());
            value.put("condition", breakpoint.condition());
            value.put("hitCondition", breakpoint.hitCondition());
            entries.add(value);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("workspace", workspace.toString());
        root.put("breakpoints", entries);
        AtomicFileWriter.write(target(workspace), MiniJson.stringify(root).getBytes(StandardCharsets.UTF_8));
    }

    private static Breakpoint fromAdapter(Breakpoint requested, Map<String, Object> result) {
        if (requested == null) throw new IllegalArgumentException("requested function breakpoint is required");
        if (result == null) return new Breakpoint(requested.name(), State.REJECTED, "Adapter omitted the breakpoint result", requested.enabled(),
            requested.condition(), requested.hitCondition());
        boolean verified = !Boolean.FALSE.equals(result.get("verified"));
        String message = MiniJson.asString(result.get("message"));
        return new Breakpoint(requested.name(), verified ? State.VERIFIED : State.REJECTED, message == null ? "" : message, requested.enabled(),
            requested.condition(), requested.hitCondition());
    }

    private Path target(Path workspace) { return directory.resolve("function-breakpoints-" + hash(workspace.toString()).substring(0, 16) + ".json"); }

    private static Path root(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        return workspace.toAbsolutePath().normalize();
    }

    private static String option(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 8 * 1024 || containsUnsafeControl(normalized)) throw new IllegalArgumentException("function breakpoint " + label + " is invalid");
        return normalized;
    }

    private static boolean containsUnsafeControl(String value) {
        return value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) result.append(String.format("%02x", valueByte));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
