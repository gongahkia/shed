package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JFileChooser;

final class WorkspaceController {
    private final Texteditor editor;
    private final WorkspaceRoots roots = new WorkspaceRoots();
    private Path importedManifest;
    private Map<Path, String> importedFolderNames = Map.of();
    private WorkspaceEditorSettings.Snapshot importedEditorSettings = WorkspaceEditorSettings.empty();

    WorkspaceController(Texteditor editor) {
        this.editor = editor;
    }

    List<Path> roots() {
        return roots.all();
    }

    Path activeRoot() {
        return roots.active();
    }

    /** Returns the folder that owns a resource, preferring the deepest matching multi-root folder. */
    Path rootFor(Path resource) {
        return WorkspaceRootResolver.configuredOrActive(resource, roots.all(), roots.active());
    }

    String displayRoot(Path root) {
        if (root == null) return "";
        String name = importedFolderNames.get(root.toAbsolutePath().normalize());
        return name == null ? root.toString() : name + " — " + root;
    }

    WorkspaceEditorSettings.Indentation editorSettingsFor(FileBuffer buffer, String languageId) {
        if (buffer == null || buffer.getFile() == null) return WorkspaceEditorSettings.Indentation.EMPTY;
        try {
            return importedEditorSettings.indentationFor(buffer.getFile().toPath(), languageId);
        } catch (RuntimeException error) {
            return WorkspaceEditorSettings.Indentation.EMPTY;
        }
    }

    boolean isExplorerExcluded(File file) {
        if (file == null) return false;
        try {
            return importedEditorSettings.excluded(file.toPath());
        } catch (RuntimeException error) {
            return false;
        }
    }

    WorkspaceIndexService.IgnoreMatcher searchExclusionMatcher() {
        return (workspaceRoot, relativePath) -> {
            if (workspaceRoot == null || relativePath == null || relativePath.isAbsolute()) return false;
            try {
                return importedEditorSettings.searchExcluded(workspaceRoot.resolve(relativePath));
            } catch (RuntimeException error) {
                return false;
            }
        };
    }

    String handle(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty() || "list".equalsIgnoreCase(trimmed) || "roots".equalsIgnoreCase(trimmed)) return showRoots();
        if ("ui".equalsIgnoreCase(trimmed) || "folders".equalsIgnoreCase(trimmed)) {
            WorkspaceFoldersDialog.showFor(editor, this);
            return "Workspace Folders opened";
        }
        int split = trimmed.indexOf(' ');
        String command = (split < 0 ? trimmed : trimmed.substring(0, split)).toLowerCase(java.util.Locale.ROOT);
        String value = split < 0 ? "" : trimmed.substring(split + 1).trim();
        return switch (command) {
            case "add" -> add(value, false);
            case "open" -> add(value, true);
            case "remove", "rm" -> remove(value);
            case "switch", "use" -> activate(value, true);
            case "import" -> importManifest(value);
            case "reload", "refresh" -> reloadManifest();
            case "export" -> exportManifest(value);
            case "symbols", "sym" -> editor.showWorkspaceSymbols(value);
            default -> "Usage: :workspace [list|ui|add <folder>|open <folder>|remove <folder|index>|switch <folder|index>|import <manifest>|reload|export <manifest>|symbols <query>]";
        };
    }

    String add(String value, boolean activate) {
        Path root = existingDirectory(value);
        if (root == null) return "Workspace folder must be an existing directory";
        boolean added = roots.add(root);
        if (added) clearImportedManifest();
        if (activate) roots.activate(root);
        syncTreeRoot(roots.active(), activate);
        return (added ? "Added workspace folder: " : "Workspace folder already added: ") + root;
    }

    String addDirectory(File folder, boolean activate) {
        return add(folder == null ? "" : folder.getPath(), activate);
    }

    String remove(String value) {
        Path root = resolve(value);
        if (root == null) return "Workspace folder not found: " + value;
        boolean wasActive = root.equals(roots.active());
        roots.remove(root);
        clearImportedManifest();
        syncTreeRoot(roots.active(), wasActive);
        return "Removed workspace folder: " + root;
    }

    String activate(String value, boolean showTree) {
        Path root = resolve(value);
        if (root == null) return "Workspace folder not found: " + value;
        roots.activate(root);
        syncTreeRoot(root, showTree);
        return "Workspace folder active: " + root;
    }

    void observeTreeRoot(File root) {
        if (root == null || !root.isDirectory()) return;
        Path path = realDirectory(root.toPath());
        if (path == null) return;
        if (roots.add(path)) clearImportedManifest();
        roots.activate(path);
    }

    void restore(Object serializedRoots, String serializedActive, File legacyTreeRoot) {
        restore(serializedRoots, serializedActive, legacyTreeRoot, null);
    }

    void restore(Object serializedRoots, String serializedActive, File legacyTreeRoot, String serializedManifest) {
        List<Path> restored = new ArrayList<>();
        List<Object> values = MiniJson.asArray(serializedRoots);
        if (values != null) {
            for (Object value : values) {
                String path = MiniJson.asString(value);
                Path root = path == null ? null : existingDirectory(path);
                if (root != null) restored.add(root);
            }
        }
        Path legacy = legacyTreeRoot == null ? null : realDirectory(legacyTreeRoot.toPath());
        if (restored.isEmpty() && legacy != null) restored.add(legacy);
        Path active = serializedActive == null ? legacy : existingDirectory(serializedActive);
        roots.replace(restored, active);
        if (roots.active() != null) {
            syncTreeRoot(roots.active(), false);
        } else if (legacyTreeRoot != null && legacyTreeRoot.exists()) {
            editor.treeGitController.setWorkspaceTreeRoot(legacyTreeRoot, false);
        } else {
            syncTreeRoot(null, false);
        }
        restoreImportedManifest(serializedManifest);
    }

    List<String> serializeRoots() {
        return roots.all().stream().map(Path::toString).toList();
    }

    String serializeManifestSource() {
        return importedManifest == null ? "" : importedManifest.toString();
    }

    /**
     * Returns the live imported manifest configuration only while its folder membership still
     * exactly matches this workspace. A changed manifest must be explicitly imported again.
     */
    WorkspaceManifest.ImportedConfiguration manifestConfiguration() {
        Path source = importedManifest;
        if (source == null) return new WorkspaceManifest.ImportedConfiguration(null, false, null, false, null, "");
        try {
            WorkspaceManifest.Document document = WorkspaceManifest.readDocument(source);
            if (!roots.all().equals(document.folders())) {
                return new WorkspaceManifest.ImportedConfiguration(source, false, null, false, null,
                    "The manifest folder set changed; import it again before using its tasks or launch configurations.");
            }
            if (!document.standardVsCodeWorkspace()) {
                return new WorkspaceManifest.ImportedConfiguration(null, false, null, false, null, "");
            }
            return new WorkspaceManifest.ImportedConfiguration(source, document.hasTasks(), document.tasks(), document.hasLaunch(), document.launch(), "");
        } catch (IOException | RuntimeException error) {
            return new WorkspaceManifest.ImportedConfiguration(source, false, null, false, null,
                "Workspace manifest could not be read: " + concise(error));
        }
    }

    private String importManifest(String value) {
        if (value == null || value.isBlank()) return "Usage: :workspace import <manifest>";
        try {
            WorkspaceManifest.Document document = WorkspaceManifest.readDocument(Path.of(value));
            List<Path> imported = document.folders();
            roots.replace(imported, imported.getFirst());
            importedManifest = document.source();
            importedFolderNames = document.standardVsCodeWorkspace() ? document.folderNames() : Map.of();
            importedEditorSettings = WorkspaceEditorSettings.read(document);
            refreshEditorIndentation();
            syncTreeRoot(roots.active(), true);
            return "Imported " + imported.size() + " workspace folder" + (imported.size() == 1 ? "" : "s");
        } catch (IOException | RuntimeException error) {
            return "Could not import workspace manifest: " + concise(error);
        }
    }

    private String reloadManifest() {
        if (importedManifest == null) return "No imported workspace manifest to reload";
        try {
            WorkspaceManifest.Document document = WorkspaceManifest.readDocument(importedManifest);
            if (!roots.all().equals(document.folders())) {
                return "The manifest folder set changed; import it again before reloading editor settings.";
            }
            importedFolderNames = document.standardVsCodeWorkspace() ? document.folderNames() : Map.of();
            importedEditorSettings = WorkspaceEditorSettings.read(document);
            refreshEditorIndentation();
            return "Reloaded imported workspace editor settings";
        } catch (IOException | RuntimeException error) {
            return "Could not reload workspace manifest: " + concise(error);
        }
    }

    private String exportManifest(String value) {
        if (value == null || value.isBlank()) return "Usage: :workspace export <manifest.shed-workspace>";
        try {
            WorkspaceManifest.write(Path.of(value), roots.all());
            return "Exported " + roots.all().size() + " workspace folder" + (roots.all().size() == 1 ? "" : "s");
        } catch (IOException | RuntimeException error) {
            return "Could not export workspace manifest: " + concise(error);
        }
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private String showRoots() {
        StringBuilder text = new StringBuilder("Workspace Folders\n\n");
        List<Path> values = roots.all();
        if (values.isEmpty()) {
            text.append("(none)\n");
        } else {
            for (int index = 0; index < values.size(); index++) {
                Path root = values.get(index);
                text.append(root.equals(roots.active()) ? "* " : "  ").append(index + 1).append(". ").append(displayRoot(root)).append('\n');
            }
        }
        if (importedManifest != null) text.append("\nManifest: ").append(importedManifest).append('\n');
        if (!importedEditorSettings.diagnostics().isEmpty()) {
            text.append("\nEditor settings diagnostics:\n");
            for (String diagnostic : importedEditorSettings.diagnostics()) text.append("- ").append(diagnostic).append('\n');
        }
        text.append("\nUse :workspace add <folder>, switch <folder|index>, remove <folder|index>, reload, or ui.\n");
        editor.showScratchBuffer("[workspace folders]", text.toString());
        return "Showing workspace folders";
    }

    private Path resolve(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            int index = Integer.parseInt(value) - 1;
            List<Path> values = roots.all();
            return index >= 0 && index < values.size() ? values.get(index) : null;
        } catch (NumberFormatException ignored) {
        }
        Path directory = existingDirectory(value);
        if (directory != null && roots.all().contains(directory)) return directory;
        for (Path root : roots.all()) {
            if (root.toString().equals(value) || root.getFileName() != null && root.getFileName().toString().equals(value)) return root;
        }
        return null;
    }

    private Path existingDirectory(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return realDirectory(Path.of(value));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private Path realDirectory(Path path) {
        try {
            return Files.isDirectory(path) ? path.toRealPath() : null;
        } catch (IOException | SecurityException error) {
            return null;
        }
    }

    private void syncTreeRoot(Path root, boolean showTree) {
        editor.treeGitController.setWorkspaceTreeRoot(root == null ? null : root.toFile(), showTree);
    }

    private void clearImportedManifest() {
        importedManifest = null;
        importedFolderNames = Map.of();
        importedEditorSettings = WorkspaceEditorSettings.empty();
        refreshEditorIndentation();
    }

    private void restoreImportedManifest(String serializedManifest) {
        importedManifest = null;
        importedFolderNames = Map.of();
        importedEditorSettings = WorkspaceEditorSettings.empty();
        refreshEditorIndentation();
        if (serializedManifest == null || serializedManifest.isBlank()) return;
        try {
            WorkspaceManifest.Document document = WorkspaceManifest.readDocument(Path.of(serializedManifest));
            if (roots.all().equals(document.folders())) {
                importedManifest = document.source();
                importedFolderNames = document.standardVsCodeWorkspace() ? document.folderNames() : Map.of();
                importedEditorSettings = WorkspaceEditorSettings.read(document);
                refreshEditorIndentation();
            }
        } catch (IOException | RuntimeException ignored) {
            // A session may restore its folders even when the portable source has moved or changed.
        }
    }

    private void refreshEditorIndentation() {
        for (EditorPane pane : editor.editorPanes) {
            pane.getTextArea().setTabSize(editor.effectiveTabSize(pane.getBuffer()));
        }
    }
}
