package shed;

import java.io.IOException;

final class LargeFileProjection {
    static final int MINIMUM_WINDOW_LINES = 64;
    static final int MARGIN_LINES = 16;

    private final FileBuffer buffer;
    private long firstLine = 1L;
    private int windowLines = MINIMUM_WINDOW_LINES;
    private boolean rendering;

    LargeFileProjection(FileBuffer buffer) {
        if (buffer == null || !buffer.isLargeFile() || buffer.isLargeFileUnavailable()) {
            throw new IllegalArgumentException("projection requires an available large-file buffer");
        }
        this.buffer = buffer;
    }

    void render(int visibleLines) throws IOException {
        windowLines = Math.max(MINIMUM_WINDOW_LINES, visibleLines + MARGIN_LINES * 2);
        firstLine = Math.min(firstLine, Math.max(1L, buffer.getLargeFileLineCount() - windowLines + 1L));
        rendering = true;
        try {
            buffer.showLargeFileWindow(firstLine, windowLines);
        } finally {
            rendering = false;
        }
    }

    boolean moveForward(int visibleLines) throws IOException {
        if (firstLine + windowLines > buffer.getLargeFileLineCount()) {
            return false;
        }
        firstLine = Math.min(buffer.getLargeFileLineCount(), firstLine + Math.max(1, windowLines - MARGIN_LINES * 2));
        render(visibleLines);
        return true;
    }

    boolean moveBackward(int visibleLines) throws IOException {
        if (firstLine <= 1L) {
            return false;
        }
        firstLine = Math.max(1L, firstLine - Math.max(1, windowLines - MARGIN_LINES * 2));
        render(visibleLines);
        return true;
    }

    boolean ensureCaretMargin(int localLine, int localLineCount, int visibleLines) throws IOException {
        if (rendering || localLineCount <= 0) {
            return false;
        }
        if (localLine <= MARGIN_LINES) {
            return moveBackward(visibleLines);
        }
        if (localLine >= Math.max(0, localLineCount - MARGIN_LINES - 1)) {
            return moveForward(visibleLines);
        }
        return false;
    }

    long firstLine() {
        return firstLine;
    }

    boolean rendering() {
        return rendering;
    }
}
