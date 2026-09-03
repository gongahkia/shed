package shed;

import java.io.File;
import java.nio.file.Path;

/** UI boundary for extension-provided SCM capabilities. */
final class ScmController {
    private final Texteditor editor;
    private final ScmContributionService providers;

    ScmController(Texteditor editor, ExtensionRegistry registry) {
        this.editor = editor;
        this.providers = new ScmContributionService(registry);
    }

    String handle(String argument) {
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
