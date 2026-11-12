package shed;

import java.util.Map;
import java.util.WeakHashMap;

/** UI-thread policy for asynchronous loads: only the latest live request for a target may install. */
final class DocumentRequests<T> {
    private long sequence;
    private final Map<T, Long> requests = new WeakHashMap<>();
    long issue(T target) {
        long ticket = ++sequence;
        if (target != null) requests.put(target, ticket);
        return ticket;
    }
    boolean current(T target, long ticket) { return target == null || Long.valueOf(ticket).equals(requests.get(target)); }
    void invalidate(T target) { requests.remove(target); }
}
