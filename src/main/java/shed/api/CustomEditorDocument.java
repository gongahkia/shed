package shed.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A resource model passed to a custom editor.
 *
 * <p>Unlike the legacy text callback, this model supports byte-oriented
 * editors. {@link #write(byte[])} is an explicit save operation for this one
 * resource; the host writes it atomically and refreshes its buffer model.</p>
 */
public interface CustomEditorDocument {
    /** Monotonically increases after a successful host-mediated write. */
    default long revision() { return 0L; }

    Path file();

    /** Returns a defensive snapshot of the current file bytes. */
    byte[] bytes() throws IOException;

    /** True when the current byte snapshot contains binary control data. */
    boolean isBinary() throws IOException;

    /** Convenience UTF-8 view. Binary editor implementations should use {@link #bytes()}. */
    default String text() throws IOException {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    /** Atomically persist replacement bytes for this resource. */
    void write(byte[] replacement) throws IOException;

    /** Whether this installed custom-editor document has a host-managed prior byte snapshot. */
    default boolean canUndo() { return false; }

    /** Whether this installed custom-editor document has a host-managed reverted byte snapshot. */
    default boolean canRedo() { return false; }

    /** Restore the most recent byte snapshot saved through {@link #write(byte[])}. */
    default void undo() throws IOException { throw new IOException("custom editor undo is unavailable"); }

    /** Reapply the most recently reverted byte snapshot. */
    default void redo() throws IOException { throw new IOException("custom editor redo is unavailable"); }

    /**
     * Observes successful writes made through this document while its editor
     * view remains installed. The callback runs on the Swing event thread.
     */
    default Subscription onDidChange(ChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        return Subscription.noop();
    }

    /** Observes disposal when the editor view is replaced, closed, or unloaded. */
    default Subscription onDidDispose(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        return Subscription.noop();
    }

    /** Observes external file replacement, modification, or deletion while this document remains installed. */
    default Subscription onDidExternalChange(ExternalChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        return Subscription.noop();
    }

    record Change(long revision) { }

    record ExternalChange(long revision, boolean exists) { }

    @FunctionalInterface
    interface ChangeListener {
        void changed(Change change);
    }

    @FunctionalInterface
    interface ExternalChangeListener {
        void changed(ExternalChange change);
    }

    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override void close();

        static Subscription noop() { return () -> { }; }
    }
}
