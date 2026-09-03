package shed.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A resource model passed to a custom editor.
 *
 * <p>Unlike the legacy text callback, this model supports byte-oriented
 * editors. {@link #write(byte[])} is an explicit save operation for this one
 * resource; the host writes it atomically and refreshes its buffer model.</p>
 */
public interface CustomEditorDocument {
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
}
