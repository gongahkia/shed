package shed;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Application ownership survives hidden panels and races with connection startup. */
final class AgentSessionRegistry implements AutoCloseable {
    private final Set<AgentSessionService> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    void own(AgentSessionService session) {
        sessions.add(session);
        session.termination().whenComplete((ignored, error) -> sessions.remove(session));
        if (closed.get()) {
            session.close(); sessions.remove(session);
            throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED, "Editor closed during agent startup");
        }
    }
    @Override public void close() {
        closed.set(true);
        for (var session : sessions) Thread.ofVirtual().name("shed-agent-stop").start(session::close);
    }
}
