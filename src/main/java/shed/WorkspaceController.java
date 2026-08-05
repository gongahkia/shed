package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFileChooser;

final class WorkspaceController {
    private final Texteditor editor;
    private final WorkspaceRoots roots = new WorkspaceRoots();

    WorkspaceController(Texteditor editor) {
        this.editor = editor;
    }

    List<Path> roots() {
        return roots.all();
    }

    Path activeRoot() {
        return roots.active();
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
            case "symbols", "sym" -> editor.showWorkspaceSymbols(value);
            default -> "Usage: :workspace [list|ui|add <folder>|open <folder>|remove <folder|index>|switch <folder|index>|symbols <query>]";
        };
    }

    String add(String value, boolean activate) {
        Path root = existingDirectory(value);
        if (root == null) return "Workspace folder must be an existing directory";
        boolean added = roots.add(root);
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
        roots.add(path);
        roots.activate(path);
    }

    void restore(Object serializedRoots, String serializedActive, File legacyTreeRoot) {
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
    }

    List<String> serializeRoots() {
        return roots.all().stream().map(Path::toString).toList();
    }

    private String showRoots() {
        StringBuilder text = new StringBuilder("Workspace Folders\n\n");
        List<Path> values = roots.all();
        if (values.isEmpty()) {
            text.append("(none)\n");
        } else {
            for (int index = 0; index < values.size(); index++) {
                Path root = values.get(index);
                text.append(root.equals(roots.active()) ? "* " : "  ").append(index + 1).append(". ").append(root).append('\n');
            }
        }
        text.append("\nUse :workspace add <folder>, switch <folder|index>, remove <folder|index>, or ui.\n");
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
}
