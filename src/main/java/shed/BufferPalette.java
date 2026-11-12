package shed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Immutable buffer choices with opaque identities, independent of widgets and buffer models. */
public final class BufferPalette implements PaletteSession {
    public record Item(String id, String name, boolean modified, boolean current) { }
    private final Map<String, String> identities;
    private final List<String> candidates;
    private final FuzzyMatchService matcher;
    private Function<String, String> activate;
    private volatile boolean closed;

    public BufferPalette(List<Item> items, FuzzyMatchService matcher, Function<String, String> activate) {
        Map<String, String> choices = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            String label = (i + 1) + ": " + item.name() + (item.modified() ? " [+]" : "")
                + (item.current() ? " (current)" : "");
            choices.put(label, item.id());
        }
        this.identities = Map.copyOf(choices);
        this.candidates = List.copyOf(choices.keySet());
        this.matcher = matcher;
        this.activate = activate;
    }

    @Override public List<String> candidates() { return candidates; }
    @Override public List<String> query(String text) {
        return closed ? List.of() : matcher.matchStrings(text, candidates, 200);
    }
    @Override public String accept(String selected, String query) {
        if (closed || !identities.containsKey(selected == null ? "" : selected)) return "Buffer finder cancelled";
        return activate.apply(identities.get(selected));
    }
    @Override public void close() { closed = true; activate = null; }
}
