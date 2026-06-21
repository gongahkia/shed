package shed;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class TerminalSession {
    final FileBuffer buffer;
    File workingDirectory;
    final List<String> history;
    int historyIndex;
    String historyDraft;
    int promptOffset;
    int runningJobId;

    TerminalSession(FileBuffer buffer, File workingDirectory) {
        this.buffer = buffer;
        this.workingDirectory = normalizeWorkingDirectory(workingDirectory);
        this.history = new ArrayList<>();
        this.historyIndex = 0;
        this.historyDraft = "";
        this.promptOffset = 0;
        this.runningJobId = -1;
    }

    private static File normalizeWorkingDirectory(File directory) {
        File candidate = directory == null ? new File(".") : directory;
        try {
            return candidate.getCanonicalFile();
        } catch (IOException ignored) {
            return candidate.getAbsoluteFile();
        }
    }
}
