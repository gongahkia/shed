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

    @Test
    void routesStageAndSwitchCommands() {
        GitService service = new GitService();
        RecordingHandler handler = new RecordingHandler();

        String stageResult = service.handle("stage src/main", new File("."), handler);
        assertEquals("stage", handler.lastCall);
        assertEquals("src/main", handler.lastArgs);
        assertEquals("ok-stage", stageResult);

        String switchResult = service.handle("switch feat-x", new File("."), handler);
        assertEquals("switch", handler.lastCall);
        assertEquals("feat-x", handler.lastArgs);
        assertEquals("ok-switch", switchResult);

        String unstageResult = service.handle("unstage src/main", new File("."), handler);
        assertEquals("unstage", handler.lastCall);
        assertEquals("src/main", handler.lastArgs);
        assertEquals("ok-unstage", unstageResult);

        String checkoutResult = service.handle("co main", new File("."), handler);
        assertEquals("checkout", handler.lastCall);
        assertEquals("main", handler.lastArgs);
        assertEquals("ok-checkout", checkoutResult);

        String amendResult = service.handle("amend --no-edit", new File("."), handler);
        assertEquals("amend", handler.lastCall);
        assertEquals("--no-edit", handler.lastArgs);
        assertEquals("ok-amend", amendResult);
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
        public String stage(File root, String args) {
            lastCall = "stage";
            lastArgs = args == null ? "" : args;
            return "ok-stage";
        }

        @Override
        public String restore(File root, String args) {
            lastCall = "restore";
            lastArgs = args == null ? "" : args;
            return "ok-restore";
        }

        @Override
        public String unstage(File root, String args) {
            lastCall = "unstage";
            lastArgs = args == null ? "" : args;
            return "ok-unstage";
        }

        @Override
        public String commit(File root, String args) {
            lastCall = "commit";
            lastArgs = args == null ? "" : args;
            return "ok-commit";
        }

        @Override
        public String amend(File root, String args) {
            lastCall = "amend";
            lastArgs = args == null ? "" : args;
            return "ok-amend";
        }

        @Override
        public String checkout(File root, String args) {
            lastCall = "checkout";
            lastArgs = args == null ? "" : args;
            return "ok-checkout";
        }

        @Override
        public String switchBranch(File root, String args) {
            lastCall = "switch";
            lastArgs = args == null ? "" : args;
            return "ok-switch";
        }

        @Override
        public String help() {
            lastCall = "help";
            return "ok-help";
        }
    }
}
