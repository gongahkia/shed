package shed;

import java.util.List;

/** One picker invocation. Candidates are immutable snapshots; query may run on a worker.
 * accept and close belong to the owning UI thread; acceptance callbacks execute inline there.
 * The view owns debounce, request generations, cancellation, and result delivery. */
public interface PaletteSession extends AutoCloseable {
    List<String> candidates();
    List<String> query(String text);
    String accept(String selected, String query);
    @Override void close();
}
