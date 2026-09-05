package shed;

final class DebugConsole {
    static final int MAX_CHARACTERS = 64 * 1024;

    enum State { IDLE, CONNECTED, DISCONNECTED, STOPPED, FAILED }

    record Snapshot(State state, String detail, String output, boolean truncated, long events) {
        Snapshot {
            state = state == null ? State.IDLE : state;
            detail = detail == null ? "" : detail;
            output = output == null ? "" : output;
        }
    }

    private final StringBuilder output = new StringBuilder();
    private State state = State.IDLE;
    private String detail = "No debug console session.";
    private boolean truncated;
    private long events;

    void start() {
        output.setLength(0);
        truncated = false;
        events = 0;
        state = State.CONNECTED;
        detail = "Debug adapter connected.";
    }

    void append(String category, String text) {
        if (text == null || text.isEmpty()) return;
        String label = category == null || category.isBlank() ? "console" : category;
        append("[" + label + "] " + text);
        events++;
    }

    void appendEvaluation(DebugSessionService.Evaluation evaluation) {
        if (evaluation == null || evaluation.expression().isBlank()) return;
        StringBuilder entry = new StringBuilder("[repl] > ").append(evaluation.expression()).append('\n').append("[repl] ")
            .append(evaluation.result());
        if (!evaluation.type().isBlank()) entry.append(" : ").append(evaluation.type());
        if (evaluation.variablesReference() > 0) entry.append("  (structured value)");
        entry.append('\n');
        append(entry.toString());
        events++;
    }

    void disconnected(String detail) {
        if (state == State.STOPPED || state == State.FAILED) return;
        state = State.DISCONNECTED;
        this.detail = detail == null || detail.isBlank() ? "Debug adapter disconnected." : detail;
    }

    void stopped() {
        state = State.STOPPED;
        detail = "Debug session stopped.";
    }

    void failed(String detail) {
        state = State.FAILED;
        this.detail = detail == null || detail.isBlank() ? "Debug adapter failed." : detail;
    }

    void clear() {
        output.setLength(0);
        truncated = false;
        events = 0;
    }

    Snapshot snapshot() { return new Snapshot(state, detail, output.toString(), truncated, events); }

    private void append(String value) {
        if (value.length() >= MAX_CHARACTERS) {
            output.setLength(0);
            output.append(value, value.length() - MAX_CHARACTERS, value.length());
            truncated = true;
            return;
        }
        int overflow = output.length() + value.length() - MAX_CHARACTERS;
        if (overflow > 0) {
            output.delete(0, overflow);
            truncated = true;
        }
        output.append(value);
    }
}
