package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

public class CommandParsingTest {
    @Test
    void routesDiffSubcommandAndPassesArgs() {
        GitService service = new GitService();
        RecordingHandler handler = new RecordingHandler();
        String result = service.handle("diff --cached", new File("."), handler);

        assertEquals("diff", handler.lastCall);
        assertEquals("--cached", handler.lastArgs);
        assertEquals("ok-diff", result);
    }

    @Test
    void routesBranchesAlias() {
        GitService service = new GitService();
        RecordingHandler handler = new RecordingHandler();
        String result = service.handle("branches", new File("."), handler);

        assertEquals("branches", handler.lastCall);
        assertEquals("ok-branches", result);
    }

    @Test
    void reportsUnknownSubcommand() {
        GitService service = new GitService();
        RecordingHandler handler = new RecordingHandler();
        String result = service.handle("wat", new File("."), handler);

        assertTrue(result.startsWith("Unknown git command: wat"));
    }

    private static class RecordingHandler implements GitService.Handler {
        private String lastCall;
        private String lastArgs = "";

        @Override
        public String status(File root) {
            lastCall = "status";
            return "ok-status";
        }

        @Override
        public String diff(File root, String args) {
            lastCall = "diff";
            lastArgs = args == null ? "" : args;
            return "ok-diff";
        }

        @Override
        public String log(File root, String args) {
            lastCall = "log";
            lastArgs = args == null ? "" : args;
            return "ok-log";
        }

        @Override
        public String branches(File root) {
            lastCall = "branches";
            return "ok-branches";
        }

        @Override
        public String add(File root, String args) {
            lastCall = "add";
            lastArgs = args == null ? "" : args;
            return "ok-add";
        }

        @Override
        public String restore(File root, String args) {
            lastCall = "restore";
            lastArgs = args == null ? "" : args;
            return "ok-restore";
        }

        @Override
        public String commit(File root, String args) {
            lastCall = "commit";
            lastArgs = args == null ? "" : args;
            return "ok-commit";
        }

        @Override
        public String help() {
            lastCall = "help";
            return "ok-help";
        }
    }
}
