package shed;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Predicate;

/** Bounded origins of explicit jumps. Merely moving the caret does not create an entry. */
final class NavigationHistory<T> {
    private final int limit;
    private final Deque<T> back = new ArrayDeque<>();
    private final Deque<T> forward = new ArrayDeque<>();

    NavigationHistory(int limit) {
        if (limit < 1) throw new IllegalArgumentException("History limit must be positive");
        this.limit = limit;
    }

    void record(T origin) {
        if (origin == null) return;
        push(back, origin);
        forward.clear();
    }

    T back(T current, Predicate<T> available) { return move(back, forward, current, available); }
    T forward(T current, Predicate<T> available) { return move(forward, back, current, available); }

    private T move(Deque<T> from, Deque<T> to, T current, Predicate<T> available) {
        while (!from.isEmpty()) {
            T target = from.removeLast();
            if (Objects.equals(target, current) || !available.test(target)) continue;
            if (current != null && available.test(current)) push(to, current);
            return target;
        }
        return null;
    }

    private void push(Deque<T> stack, T entry) {
        if (Objects.equals(stack.peekLast(), entry)) return;
        stack.addLast(entry);
        while (stack.size() > limit) stack.removeFirst();
    }
}
