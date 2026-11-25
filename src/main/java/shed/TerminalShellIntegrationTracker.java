package shed;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Accepts the narrow OSC payload emitted by Shed's generated shell scripts. */
final class TerminalShellIntegrationTracker {
    record Event(Instant at, String type, String value) { }
    private static final int MAX_EVENTS = 200;
    private static final int MAX_VALUE_LENGTH = 8 * 1024;
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<Event>> listeners=new CopyOnWriteArrayList<>();
    void addListener(java.util.function.Consumer<Event> listener){listeners.add(listener);}
    void removeListener(java.util.function.Consumer<Event> listener){listeners.remove(listener);}

    void accept(List<String> arguments) {
        if (arguments == null || arguments.size() != 3 || !"shed".equals(arguments.getFirst())) return;
        String type = arguments.get(1);
        String value = arguments.get(2);
        if (!"cwd".equals(type) && !"command".equals(type) && !"finished".equals(type)) return;
        if (value == null || value.length() > MAX_VALUE_LENGTH || value.indexOf('\0') >= 0) return;
        if ("finished".equals(type) && !value.matches("-?\\d{1,5}")) return;
        var event=new Event(Instant.now(),type,value);events.add(event);
        for(var listener:listeners)listener.accept(event);
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    List<Event> events() {
        return List.copyOf(events);
    }

    String currentDirectory() {
        for (int index = events.size() - 1; index >= 0; index--) {
            Event event = events.get(index);
            if ("cwd".equals(event.type())) return event.value();
        }
        return null;
    }
}
