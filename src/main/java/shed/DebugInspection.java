package shed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DebugInspection {
    enum State { IDLE, LOADING, READY, UNAVAILABLE, ERROR }
    enum WatchState { PENDING, READY, UNAVAILABLE, ERROR }

    record ThreadInfo(int id, String name) {
        ThreadInfo { name = name == null ? "" : name; }
    }
    record Frame(int id, String name, String source, int line, int column) {
        Frame { name = name == null ? "" : name; source = source == null ? "" : source; }
    }
    record Variable(String name, String value, String type, int variablesReference) {
        Variable { name = name == null ? "" : name; value = value == null ? "" : value; type = type == null ? "" : type; }
    }
    record Scope(String name, int variablesReference, boolean expensive, List<Variable> variables) {
        Scope { name = name == null ? "" : name; variables = variables == null ? List.of() : List.copyOf(variables); }
    }
    record Watch(String expression, WatchState state, String value, String type, String message) {
        Watch {
            expression = expression == null ? "" : expression;
            state = state == null ? WatchState.PENDING : state;
            value = value == null ? "" : value;
            type = type == null ? "" : type;
            message = message == null ? "" : message;
        }
    }
    record Snapshot(State state, String detail, boolean paused, int threadId, int frameId, List<ThreadInfo> threads, List<Frame> frames,
        List<Scope> scopes, Map<Integer, List<Variable>> expandedVariables, List<Watch> watches) {
        Snapshot {
            state = state == null ? State.IDLE : state;
            detail = detail == null ? "" : detail;
            threads = threads == null ? List.of() : List.copyOf(threads);
            frames = frames == null ? List.of() : List.copyOf(frames);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            Map<Integer, List<Variable>> values = new LinkedHashMap<>();
            if (expandedVariables != null) {
                for (Map.Entry<Integer, List<Variable>> entry : expandedVariables.entrySet()) {
                    if (entry.getKey() != null && entry.getKey() > 0) values.put(entry.getKey(), List.copyOf(entry.getValue() == null ? List.of() : entry.getValue()));
                }
            }
            expandedVariables = Map.copyOf(values);
            watches = watches == null ? List.of() : List.copyOf(watches);
        }

        Snapshot(State state, String detail, boolean paused, int threadId, int frameId, List<ThreadInfo> threads, List<Frame> frames,
                 List<Scope> scopes, List<Watch> watches) {
            this(state, detail, paused, threadId, frameId, threads, frames, scopes, Map.of(), watches);
        }
    }
    record Load(long generation, int threadId, int frameId, List<String> watches) {
        Load { watches = watches == null ? List.of() : List.copyOf(watches); }
    }
    record VariableLoad(long generation, int variablesReference) { }
    record EvaluationLoad(long generation, int frameId) { }
    record VariableMutationLoad(long generation, int frameId, int variablesReference) { }
    record Result(Snapshot snapshot, boolean succeeded) { }

    private long generation;
    private boolean paused;
    private int threadId;
    private int frameId;
    private State state = State.IDLE;
    private String detail = "No paused debug state.";
    private List<ThreadInfo> threads = List.of();
    private List<Frame> frames = List.of();
    private List<Scope> scopes = List.of();
    private final Map<Integer, List<Variable>> expandedVariables = new LinkedHashMap<>();
    private final List<Watch> watches = new ArrayList<>();

    void stopped(int threadId, String reason, String description) {
        generation++;
        paused = true;
        this.threadId = threadId;
        frameId = 0;
        threads = List.of();
        frames = List.of();
        scopes = List.of();
        expandedVariables.clear();
        state = State.IDLE;
        detail = "Paused" + (reason == null || reason.isBlank() ? "." : ": " + reason + ".")
            + (description == null || description.isBlank() ? "" : " " + description);
        resetWatches();
    }

    void invalidated(String reason) {
        generation++;
        paused = false;
        threadId = 0;
        frameId = 0;
        threads = List.of();
        frames = List.of();
        scopes = List.of();
        expandedVariables.clear();
        state = State.IDLE;
        detail = reason == null || reason.isBlank() ? "Debug execution resumed." : reason;
        resetWatches();
    }

    Result addWatch(String expression) {
        String value = expression == null ? "" : expression.trim();
        if (value.isEmpty() || value.length() > 1024 || hasControl(value)) return new Result(snapshot(), false);
        if (watches.stream().anyMatch(watch -> watch.expression().equals(value))) return new Result(snapshot(), false);
        watches.add(new Watch(value, WatchState.PENDING, "", "", ""));
        return new Result(snapshot(), true);
    }

    Result removeWatch(String expression) {
        String value = expression == null ? "" : expression.trim();
        boolean removed = watches.removeIf(watch -> watch.expression().equals(value));
        return new Result(snapshot(), removed);
    }

    Result clearWatches() {
        boolean hadWatches = !watches.isEmpty();
        watches.clear();
        return new Result(snapshot(), hadWatches);
    }

    Result selectFrame(int id) {
        if (!paused || frames.stream().noneMatch(frame -> frame.id() == id)) return new Result(snapshot(), false);
        generation++;
        frameId = id;
        scopes = List.of();
        expandedVariables.clear();
        state = State.IDLE;
        detail = "Selected frame " + id + ". Refresh inspection to load scopes and watches.";
        resetWatches();
        return new Result(snapshot(), true);
    }

    Load beginLoad() {
        if (!paused) return null;
        state = State.LOADING;
        detail = "Loading paused-frame inspection.";
        return new Load(generation, threadId, frameId, watches.stream().map(Watch::expression).toList());
    }

    boolean complete(long generation, List<ThreadInfo> threads, List<Frame> frames, int selectedFrame, List<Scope> scopes, List<Watch> watches,
        String detail, State state) {
        if (!paused || this.generation != generation) return false;
        this.threads = threads == null ? List.of() : List.copyOf(threads);
        this.frames = frames == null ? List.of() : List.copyOf(frames);
        frameId = selectedFrame;
        this.scopes = scopes == null ? List.of() : List.copyOf(scopes);
        expandedVariables.clear();
        this.watches.clear();
        if (watches != null) this.watches.addAll(watches);
        this.detail = detail == null ? "" : detail;
        this.state = state == null ? State.READY : state;
        return true;
    }

    boolean failed(long generation, String detail, State state) {
        if (!paused || this.generation != generation) return false;
        this.detail = detail == null ? "Paused-frame inspection failed." : detail;
        this.state = state == null ? State.ERROR : state;
        return true;
    }

    boolean loading(long generation) { return paused && this.generation == generation && state == State.LOADING; }

    VariableLoad beginVariableLoad(int variablesReference) {
        if (!paused || variablesReference < 1 || !hasVariableReference(variablesReference)) return null;
        return new VariableLoad(generation, variablesReference);
    }

    boolean completeVariableLoad(long generation, int variablesReference, List<Variable> variables) {
        if (!paused || this.generation != generation || variablesReference < 1 || !hasVariableReference(variablesReference)) return false;
        expandedVariables.put(variablesReference, List.copyOf(variables == null ? List.of() : variables));
        detail = "Loaded nested variables for reference " + variablesReference + ".";
        state = State.READY;
        return true;
    }

    EvaluationLoad beginEvaluation() {
        return paused && frameId > 0 ? new EvaluationLoad(generation, frameId) : null;
    }

    VariableMutationLoad beginVariableMutation(int variablesReference, String name) {
        if (!paused || frameId < 1 || variablesReference < 1 || !hasVariableContainerReference(variablesReference)
            || !hasDisplayedVariable(variablesReference, name)) return null;
        return new VariableMutationLoad(generation, frameId, variablesReference);
    }

    boolean updateVariable(long generation, int frameId, int variablesReference, String name, String value, String type, int childReference) {
        if (!current(generation, frameId)) return false;
        for (int index = 0; index < scopes.size(); index++) {
            Scope scope = scopes.get(index);
            if (scope.variablesReference() != variablesReference) continue;
            List<Variable> updated = replaceVariable(scope.variables(), name, value, type, childReference);
            if (updated == scope.variables()) return false;
            List<Scope> copies = new ArrayList<>(scopes);
            copies.set(index, new Scope(scope.name(), scope.variablesReference(), scope.expensive(), updated));
            scopes = List.copyOf(copies);
            detail = "Changed debug variable '" + name + "'.";
            return true;
        }
        List<Variable> values = expandedVariables.get(variablesReference);
        if (values == null) return false;
        List<Variable> updated = replaceVariable(values, name, value, type, childReference);
        if (updated == values) return false;
        expandedVariables.put(variablesReference, updated);
        detail = "Changed debug variable '" + name + "'.";
        return true;
    }

    boolean current(long generation, int frameId) {
        return paused && this.generation == generation && this.frameId == frameId;
    }

    boolean current(long generation) {
        return paused && this.generation == generation;
    }

    Snapshot snapshot() {
        return new Snapshot(state, detail, paused, threadId, frameId, threads, frames, scopes, expandedVariables, watches);
    }

    private boolean hasVariableReference(int reference) {
        for (Scope scope : scopes) if (hasVariableReference(scope.variables(), reference)) return true;
        for (List<Variable> variables : expandedVariables.values()) if (hasVariableReference(variables, reference)) return true;
        return false;
    }

    private boolean hasVariableContainerReference(int reference) {
        for (Scope scope : scopes) if (scope.variablesReference() == reference) return true;
        return hasVariableReference(reference);
    }

    private boolean hasDisplayedVariable(int reference, String name) {
        String requested = name == null ? "" : name;
        for (Scope scope : scopes) if (scope.variablesReference() == reference && hasVariable(scope.variables(), requested)) return true;
        List<Variable> values = expandedVariables.get(reference);
        return hasVariable(values, requested);
    }

    private static boolean hasVariableReference(List<Variable> variables, int reference) {
        if (variables == null) return false;
        return variables.stream().anyMatch(variable -> variable != null && variable.variablesReference() == reference);
    }

    private static boolean hasVariable(List<Variable> variables, String name) {
        if (variables == null) return false;
        return variables.stream().anyMatch(variable -> variable != null && variable.name().equals(name));
    }

    private static List<Variable> replaceVariable(List<Variable> values, String name, String value, String type, int childReference) {
        if (values == null) return List.of();
        List<Variable> result = new ArrayList<>(values.size());
        boolean replaced = false;
        for (Variable variable : values) {
            if (!replaced && variable != null && variable.name().equals(name)) {
                result.add(new Variable(variable.name(), value, type == null || type.isBlank() ? variable.type() : type,
                    childReference > 0 ? childReference : variable.variablesReference()));
                replaced = true;
            } else result.add(variable);
        }
        return replaced ? List.copyOf(result) : values;
    }

    private void resetWatches() {
        for (int index = 0; index < watches.size(); index++) {
            Watch watch = watches.get(index);
            watches.set(index, new Watch(watch.expression(), WatchState.PENDING, "", "", ""));
        }
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }
}
