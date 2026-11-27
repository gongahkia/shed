package shed;

import java.io.File;
import java.nio.file.Path;

/** UI boundary for built-in SCM capabilities. */
final class ScmController {
    private final Texteditor editor;
    private final ScmContributionService providers;

    ScmController(Texteditor editor) {
        this.editor = editor;
        this.providers = new ScmContributionService();
    }

    String handle(String argument) {
        String value=argument==null?"":argument.trim().toLowerCase(java.util.Locale.ROOT);
        if(value.isEmpty()||value.equals("gui")||value.equals("open")||value.equals("mercurial gui")||value.equals("subversion gui")||value.equals("git gui")){
            editor.showToolWindow(ToolWindowHost.Tab.GIT);
            if(editor.toolWindowHost!=null){var panel=editor.toolWindowHost.sourceControlPanel();if(value.startsWith("mercurial"))panel.select(ScmWorkbenchModel.Provider.MERCURIAL);else if(value.startsWith("subversion"))panel.select(ScmWorkbenchModel.Provider.SUBVERSION);else if(value.startsWith("git"))panel.select(ScmWorkbenchModel.Provider.GIT);}
            return "Source Control sidebar opened";
        }
        Path root = workspace();
        ScmContributionService.Result result = providers.handle(root, argument);
        if (result.hasDocument()) editor.showScratchBuffer("[scm]", result.document());
        return result.message();
    }

    private Path workspace() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (editor.workspaceController != null && buffer != null && buffer.getFile() != null) {
            Path root = editor.workspaceController.rootFor(buffer.getFile().toPath());
            if (root != null) return root;
        }
        Path active = editor.workspaceController == null ? null : editor.workspaceController.activeRoot();
        if (active != null) return active;
        File fallback = editor.resolveTaskProjectRoot();
        return fallback == null ? null : fallback.toPath().toAbsolutePath().normalize();
    }
}
