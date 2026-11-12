package shed;

import java.util.function.Predicate;

/** Bounded application history. Historical UTF-16 jump offsets retain their existing semantics;
 * per-pane edit-tracking selections are separately owned by EditorSurface.View. */
final class DocumentNavigation {
    record Location(DocumentWorkspace.Id document, int offset) { }
    private final NavigationHistory<Location> history = new NavigationHistory<>(100);
    void record(Location location) { history.record(location); }
    Location navigate(boolean forward, Location current, Predicate<DocumentWorkspace.Id> available) {
        return forward ? history.forward(current, location -> available.test(location.document()))
            : history.back(current, location -> available.test(location.document()));
    }
}
