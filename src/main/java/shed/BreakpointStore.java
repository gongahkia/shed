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

final class BreakpointStore {
    enum State { REQUESTED, VERIFIED, CHANGED, REJECTED }
    record Marker(State state, boolean enabled) {
        Marker { state = state == null ? State.REQUESTED : state; }
    }

    record Breakpoint(Path source, int line, State state, Integer actualLine, String message, boolean enabled, String condition,
        String hitCondition, String logMessage) {
        Breakpoint {
            source = source == null ? null : source.toAbsolutePath().normalize();
            if (source == null || line < 1) throw new IllegalArgumentException("source breakpoint requires an absolute path and positive line");
            state = state == null ? State.REQUESTED : state;
            if (actualLine != null && actualLine < 1) throw new IllegalArgumentException("source breakpoint actual line must be positive");
            message = message == null ? "" : message;
            condition = option(condition, "condition");
            hitCondition = option(hitCondition, "hit condition");
            logMessage = option(logMessage, "log message");
        }

        Breakpoint(Path source, int line, State state, Integer actualLine, String message) {
            this(source, line, state, actualLine, message, true, "", "", "");
        }

        int displayLine() { return actualLine == null ? line : actualLine; }

        @Override public String toString() {
            return (enabled ? "● " : "○ ") + source.getFileName() + ":" + line + "  " + state;
        }
    }

    record Toggle(Breakpoint breakpoint, boolean added) { }
    record SyncResult(List<Breakpoint> breakpoints, List<String> diagnostics) {
        SyncResult {
            breakpoints = breakpoints == null ? List.of() : List.copyOf(breakpoints);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private static final int VERSION = 2;
    private final Path directory;
    private final Map<Path, List<Breakpoint>> workspaces = new LinkedHashMap<>();

    BreakpointStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    synchronized Toggle toggle(Path workspace, Path source, int line) throws IOException {
        Path root = workspace(workspace);
        Path file = source(root, source);
        List<Breakpoint> breakpoints = loaded(root);
        for (int index = 0; index < breakpoints.size(); index++) {
            Breakpoint existing = breakpoints.get(index);
            if (existing.source().equals(file) && (existing.line() == line || existing.displayLine() == line)) {
                breakpoints.remove(index);
                save(root, breakpoints);
                return new Toggle(existing, false);
            }
        }
        Breakpoint added = new Breakpoint(file, line, State.REQUESTED, null, "");
        breakpoints.add(added);
        sort(breakpoints);
        save(root, breakpoints);
        return new Toggle(added, true);
    }

    synchronized Map<Path, List<Breakpoint>> sources(Path workspace) throws IOException {
        Path root = workspace(workspace);
        Map<Path, List<Breakpoint>> sources = new LinkedHashMap<>();
        for (Breakpoint breakpoint : loaded(root)) {
            sources.computeIfAbsent(breakpoint.source(), ignored -> new ArrayList<>()).add(breakpoint);
        }
        for (Map.Entry<Path, List<Breakpoint>> entry : sources.entrySet()) entry.setValue(List.copyOf(entry.getValue()));
        return Map.copyOf(sources);
    }

    synchronized Map<Integer, Marker> markers(Path workspace, Path source) throws IOException {
        Path root = workspace(workspace);
        Path file = source(root, source);
        Map<Integer, Marker> markers = new LinkedHashMap<>();
        for (Breakpoint breakpoint : loaded(root)) {
            if (breakpoint.source().equals(file)) markers.put(breakpoint.displayLine() - 1, new Marker(breakpoint.state(), breakpoint.enabled()));
        }
        return Map.copyOf(markers);
    }

    synchronized Breakpoint configure(Path workspace, Path source, int line, boolean enabled, String condition, String hitCondition,
        String logMessage) throws IOException {
        Path root = workspace(workspace);
        Path file = source(root, source);
        List<Breakpoint> breakpoints = loaded(root);
        for (int index = 0; index < breakpoints.size(); index++) {
            Breakpoint current = breakpoints.get(index);
            if (!current.source().equals(file) || current.line() != line) continue;
            Breakpoint updated = new Breakpoint(file, current.line(), State.REQUESTED, null, "", enabled, condition, hitCondition, logMessage);
            breakpoints.set(index, updated);
            save(root, breakpoints);
            return updated;
        }
        throw new IllegalArgumentException("source breakpoint is unavailable");
    }

    synchronized boolean remove(Path workspace, Path source, int line) throws IOException {
        Path root = workspace(workspace);
        Path file = source(root, source);
        List<Breakpoint> breakpoints = loaded(root);
        for (int index = 0; index < breakpoints.size(); index++) {
            Breakpoint current = breakpoints.get(index);
            if (!current.source().equals(file) || (current.line() != line && current.displayLine() != line)) continue;
            breakpoints.remove(index);
            save(root, breakpoints);
            return true;
        }
        return false;
    }

    synchronized void reject(Path workspace, Path source, Breakpoint requested, String message) throws IOException {
        if (requested == null) return;
        Path root = workspace(workspace);
        Path file = source(root, source);
        List<Breakpoint> breakpoints = loaded(root);
        for (int index = 0; index < breakpoints.size(); index++) {
            Breakpoint current = breakpoints.get(index);
            if (!current.source().equals(file) || current.line() != requested.line()) continue;
            breakpoints.set(index, copy(current, file, State.REJECTED, null, message));
            save(root, breakpoints);
            return;
        }
    }

    synchronized SyncResult apply(Path workspace, Path source, List<Breakpoint> requested, Object responseBody) throws IOException {
        Path root = workspace(workspace);
        Path file = source(root, source);
        List<Breakpoint> original = requested == null ? List.of() : List.copyOf(requested);
        Map<String, Object> body = MiniJson.asObject(responseBody);
        List<Object> returned = body == null ? null : MiniJson.asArray(body.get("breakpoints"));
        if (returned == null) return new SyncResult(List.of(), List.of("DAP setBreakpoints returned no breakpoint results for " + file + "."));
        List<Breakpoint> updated = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (int index = 0; index < original.size(); index++) {
            Breakpoint requestedBreakpoint = original.get(index);
            Map<String, Object> result = index < returned.size() ? MiniJson.asObject(returned.get(index)) : null;
            Breakpoint breakpoint = fromAdapter(file, requestedBreakpoint, result);
            updated.add(breakpoint);
            if (breakpoint.state() == State.REJECTED) {
                diagnostics.add("Breakpoint " + file + ":" + breakpoint.line() + " rejected"
                    + (breakpoint.message().isBlank() ? "." : ": " + breakpoint.message()));
            } else if (breakpoint.state() == State.CHANGED) {
                diagnostics.add("Breakpoint " + file + ":" + breakpoint.line() + " moved to line " + breakpoint.actualLine() + ".");
            }
        }
        replace(root, file, updated);
        return new SyncResult(updated, diagnostics);
    }

    private static Breakpoint fromAdapter(Path source, Breakpoint requested, Map<String, Object> result) {
        if (result == null) return copy(requested, source, State.REJECTED, null, "Adapter omitted the breakpoint result");
        boolean verified = !Boolean.FALSE.equals(result.get("verified"));
        Integer actualLine = positiveInteger(result.get("line"));
        String message = MiniJson.asString(result.get("message"));
        if (!verified) return copy(requested, source, State.REJECTED, actualLine, message);
        if (actualLine != null && actualLine != requested.line()) return copy(requested, source, State.CHANGED, actualLine, message);
        return copy(requested, source, State.VERIFIED, actualLine, message);
    }

    private void replace(Path workspace, Path source, List<Breakpoint> updated) throws IOException {
        List<Breakpoint> breakpoints = loaded(workspace);
        for (Breakpoint replacement : updated) {
            for (int index = 0; index < breakpoints.size(); index++) {
                Breakpoint current = breakpoints.get(index);
                if (current.source().equals(source) && current.line() == replacement.line()) {
                    breakpoints.set(index, replacement);
                    break;
                }
            }
        }
        sort(breakpoints);
        save(workspace, breakpoints);
    }

    private List<Breakpoint> loaded(Path workspace) throws IOException {
        List<Breakpoint> loaded = workspaces.get(workspace);
        if (loaded != null) return loaded;
        List<Breakpoint> parsed = read(workspace);
        workspaces.put(workspace, parsed);
        return parsed;
    }

    private List<Breakpoint> read(Path workspace) throws IOException {
        Path target = target(workspace);
        if (!Files.exists(target)) return new ArrayList<>();
        try {
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(Files.readString(target, StandardCharsets.UTF_8)));
            if (root == null || root.size() != 3 || !root.containsKey("version") || !root.containsKey("workspace") || !root.containsKey("breakpoints")) {
                throw new IllegalArgumentException("root fields are invalid");
            }
            if (!(root.get("version") instanceof Number number) || (number.intValue() != 1 && number.intValue() != VERSION)
                || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException("version is unsupported");
            }
            String persistedWorkspace = MiniJson.asString(root.get("workspace"));
            if (persistedWorkspace == null || !workspace.equals(Path.of(persistedWorkspace).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("workspace does not match its storage target");
            }
            List<Object> values = MiniJson.asArray(root.get("breakpoints"));
            if (values == null) throw new IllegalArgumentException("breakpoints is not an array");
            List<Breakpoint> result = new ArrayList<>();
            for (Object value : values) result.add(parse(workspace, value, number.intValue()));
            sort(result);
            return result;
        } catch (RuntimeException error) {
            throw new IOException("Breakpoint storage is invalid: " + error.getMessage(), error);
        }
    }

    private static Breakpoint parse(Path workspace, Object value, int version) {
        Map<String, Object> fields = MiniJson.asObject(value);
        int fieldCount = version == 1 ? 5 : 9;
        if (fields == null || fields.size() != fieldCount || !fields.containsKey("path") || !fields.containsKey("line") || !fields.containsKey("state")
            || !fields.containsKey("actualLine") || !fields.containsKey("message")) throw new IllegalArgumentException("breakpoint fields are invalid");
        String path = MiniJson.asString(fields.get("path"));
        String state = MiniJson.asString(fields.get("state"));
        String message = MiniJson.asString(fields.get("message"));
        Integer line = positiveInteger(fields.get("line"));
        Integer actualLine = fields.get("actualLine") == null ? null : positiveInteger(fields.get("actualLine"));
        if (path == null || state == null || message == null || line == null || (fields.get("actualLine") != null && actualLine == null)) {
            throw new IllegalArgumentException("breakpoint values are invalid");
        }
        if (version == 1) return new Breakpoint(source(workspace, Path.of(path)), line, State.valueOf(state), actualLine, message);
        if (!(fields.get("enabled") instanceof Boolean enabled)) throw new IllegalArgumentException("breakpoint enabled is invalid");
        String condition = MiniJson.asString(fields.get("condition"));
        String hitCondition = MiniJson.asString(fields.get("hitCondition"));
        String logMessage = MiniJson.asString(fields.get("logMessage"));
        if (condition == null || hitCondition == null || logMessage == null) throw new IllegalArgumentException("breakpoint options are invalid");
        return new Breakpoint(source(workspace, Path.of(path)), line, State.valueOf(state), actualLine, message, enabled, condition, hitCondition, logMessage);
    }

    private void save(Path workspace, List<Breakpoint> breakpoints) throws IOException {
        Files.createDirectories(directory);
        List<Object> values = new ArrayList<>();
        for (Breakpoint breakpoint : breakpoints) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", breakpoint.source().toString());
            value.put("line", breakpoint.line());
            value.put("state", breakpoint.state().name());
            value.put("actualLine", breakpoint.actualLine());
            value.put("message", breakpoint.message());
            value.put("enabled", breakpoint.enabled());
            value.put("condition", breakpoint.condition());
            value.put("hitCondition", breakpoint.hitCondition());
            value.put("logMessage", breakpoint.logMessage());
            values.add(value);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("workspace", workspace.toString());
        root.put("breakpoints", values);
        AtomicFileWriter.write(target(workspace), MiniJson.stringify(root).getBytes(StandardCharsets.UTF_8));
    }

    private Path target(Path workspace) { return directory.resolve("breakpoints-" + hash(workspace.toString()).substring(0, 16) + ".json"); }
    private static Path workspace(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        return workspace.toAbsolutePath().normalize();
    }
    private static Path source(Path workspace, Path source) {
        if (source == null) throw new IllegalArgumentException("source file is required");
        Path file = source.toAbsolutePath().normalize();
        if (!file.startsWith(workspace)) throw new IllegalArgumentException("source breakpoint escapes the workspace");
        return file;
    }
    private static Integer positiveInteger(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue() || number.longValue() < 1 || number.longValue() > Integer.MAX_VALUE) return null;
        return (int) number.longValue();
    }
    private static void sort(List<Breakpoint> breakpoints) {
        breakpoints.sort(Comparator.comparing((Breakpoint value) -> value.source().toString()).thenComparingInt(Breakpoint::line));
    }
    private static Breakpoint copy(Breakpoint source, Path path, State state, Integer actualLine, String message) {
        return new Breakpoint(path, source.line(), state, actualLine, message, source.enabled(), source.condition(), source.hitCondition(), source.logMessage());
    }
    private static String option(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 4096 || normalized.indexOf('\0') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("breakpoint " + name + " is invalid");
        }
        return normalized;
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
