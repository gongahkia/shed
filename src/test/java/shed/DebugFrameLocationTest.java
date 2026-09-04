package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugFrameLocationTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsOnlyAnExistingFileBackedFrameSource() throws Exception {
        Path source = Files.writeString(tempDir.resolve("app.py"), "print(1)\n");
        DebugInspection.Frame frame = new DebugInspection.Frame(1, "main", source.toString(), 4, 3);

        assertEquals(source.toAbsolutePath().normalize(), DebugFrameLocation.sourcePath(frame));
        assertEquals(4, DebugFrameLocation.line(frame));
        assertEquals(3, DebugFrameLocation.column(frame));
        assertNull(DebugFrameLocation.sourcePath(new DebugInspection.Frame(2, "native", "", 0, 0)));
    }
}
