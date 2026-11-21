package shed;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** lazily created by an explicitly enabled attachment; never reads editor components. */
final class ApplicationStatus {
    enum State { STARTING, READY, STOPPING }
    private static final class ProcessIdentity { static final String ID = UUID.randomUUID().toString(); }
    final String instanceId = ProcessIdentity.ID;
    final String windowId = UUID.randomUUID().toString();
    private final AtomicReference<State> state;

    ApplicationStatus(boolean ready) { state = new AtomicReference<>(ready ? State.READY : State.STARTING); }
    void ready() { state.compareAndSet(State.STARTING, State.READY); }
    void stopping() { state.set(State.STOPPING); }

    void requireObservationReady() {
        if (state.get() == State.STOPPING) throw new WorkspaceApiException(WorkspaceApiException.Code.APP_SHUTTING_DOWN, "Application is shutting down");
        if (state.get() != State.READY) throw new WorkspaceApiException(WorkspaceApiException.Code.APP_NOT_READY, "Application is not ready");
    }

    Map<String, Object> snapshot() {
        return switch (state.get()) {
            case STARTING -> throw new WorkspaceApiException(WorkspaceApiException.Code.APP_NOT_READY, "Application is not ready");
            case STOPPING -> throw new WorkspaceApiException(WorkspaceApiException.Code.SESSION_INVALID, "Attachment is stopping");
            case READY -> Map.of("schemaVersion", 1, "protocolVersion", 2, "instanceId", instanceId,
                "windowId", windowId, "readiness", "ready", "authenticated", true, "capabilities", List.of("status"));
        };
    }
}
