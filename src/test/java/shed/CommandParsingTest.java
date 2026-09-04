package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.List;
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

    @Test
    void routesStatusByDefaultAndAlias() {
        GitService service = new GitService();
        RecordingHandler handler = new RecordingHandler();

        String emptyResult = service.handle("   ", new File("."), handler);
        assertEquals("status", handler.lastCall);
        assertEquals("ok-status", emptyResult);

        String aliasResult = service.handle("st", new File("."), handler);
        assertEquals("status", handler.lastCall);
        assertEquals("ok-status", aliasResult);
    }

    @Test
    void routesRestoreHelpAndShortSwitchAlias() {
        GitService service = new GitService();
        RecordingHandler handler = new RecordingHandler();

        String restoreResult = service.handle("restore src/main", new File("."), handler);
        assertEquals("restore", handler.lastCall);
        assertEquals("src/main", handler.lastArgs);
        assertEquals("ok-restore", restoreResult);

        String shortSwitchResult = service.handle("sw feat-y", new File("."), handler);
        assertEquals("switch", handler.lastCall);
        assertEquals("feat-y", handler.lastArgs);
        assertEquals("ok-switch", shortSwitchResult);

        String helpResult = service.handle("help", new File("."), handler);
        assertEquals("help", handler.lastCall);
        assertEquals("ok-help", helpResult);
    }

    @Test
    void routesPermalinkAndLinkAlias() {
        GitService service = new GitService();
        RecordingHandler handler = new RecordingHandler();

        assertEquals("ok-permalink", service.handle("permalink 12", new File("."), handler));
        assertEquals("permalink", handler.lastCall);
        assertEquals("12", handler.lastArgs);
        assertEquals("ok-permalink", service.handle("link", new File("."), handler));
        assertEquals("permalink", handler.lastCall);
    }

    @Test
    void returnsNotInsideRepositoryWhenRootMissing() {
        GitService service = new GitService();
        RecordingHandler handler = new RecordingHandler();

        String result = service.handle("status", null, handler);
        assertEquals("Not inside a git repository", result);
        assertNull(handler.lastCall);
    }

    @Test
    void treeGitArgsKeepQuotedPathsWithSpaces() {
        TreeGitController controller = new TreeGitController(null);

        List<String> args = controller.splitWhitespaceArgs("\"dir with spaces/file.txt\" plain\\ path");

        assertEquals(List.of("dir with spaces/file.txt", "plain path"), args);
    }

    @Test
    void exposesSemanticSurfaceActionsInTheCommandPalette() {
        CommandHandler handler = new CommandHandler(null);
        FuzzyMatchService matcher = new FuzzyMatchService();

        assertTrue(handler.getCommandNames().contains("languageservices"));
        assertTrue(handler.getCommandNames().contains("language-services"));
        List<String> actions = PaletteController.surfaceActionNames();
        assertTrue(matcher.matchStrings("Language Services", actions, 0)
            .contains(PaletteController.LANGUAGE_SERVICES_ACTION));
        assertTrue(matcher.matchStrings("Workspace Folders", actions, 0).contains("Workspace Folders"));
        assertTrue(matcher.matchStrings("Code Actions", actions, 0).contains("Code Actions"));
        assertTrue(matcher.matchStrings("Import Coverage Report", actions, 0).contains("Import Coverage Report"));
        assertTrue(matcher.matchStrings("Remote Workspaces", actions, 0).contains("Remote Workspaces"));
        assertTrue(matcher.matchStrings("Dev Container", actions, 0).contains("Dev Container"));
        assertEquals("workspace ui", PaletteController.surfaceActionCommand("Workspace Folders"));
        assertEquals("lsp codeaction", PaletteController.surfaceActionCommand("Code Actions"));
        assertEquals("snippets edit", PaletteController.surfaceActionCommand("Edit Snippets"));
        assertEquals("remote list", PaletteController.surfaceActionCommand("Remote Workspaces"));
        assertEquals("container status", PaletteController.surfaceActionCommand("Dev Container"));
        assertEquals(actions.size(), new java.util.HashSet<>(actions).size());
        for (String action : actions) {
            String command = PaletteController.surfaceActionCommand(action);
            String topLevel = command.substring(0, command.indexOf(' ') < 0 ? command.length() : command.indexOf(' '));
            assertTrue(handler.getCommandNames().contains(topLevel), action + " must route through a registered command");
        }
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
        public String permalink(File root, String args) {
            lastCall = "permalink";
            lastArgs = args == null ? "" : args;
            return "ok-permalink";
        }

        @Override
        public String help() {
            lastCall = "help";
            return "ok-help";
        }
    }
}
