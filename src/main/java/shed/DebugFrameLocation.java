package shed;

import java.nio.file.Files;
import java.nio.file.Path;

/** Validates the file-backed part of an adapter stack frame before editor navigation. */
final class DebugFrameLocation {
    private DebugFrameLocation() { }

    static Path sourcePath(DebugInspection.Frame frame) {
        if (frame == null || frame.source() == null || frame.source().isBlank()) return null;
        try {
            Path raw = Path.of(frame.source());
            if (!raw.isAbsolute()) return null;
            Path path = raw.normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    static int line(DebugInspection.Frame frame) { return frame == null ? 1 : Math.max(1, frame.line()); }
    static int column(DebugInspection.Frame frame) { return frame == null ? 1 : Math.max(1, frame.column()); }
}
