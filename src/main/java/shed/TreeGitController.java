package shed;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TreeGitController {
    private final Texteditor editor;
    private File cachedGitRoot;
    private boolean cachedGitRootResolved;

    TreeGitController(Texteditor editor) {
        this.editor = editor;
    }

    public String showFileFinder() {
        FileBuffer buf = editor.getCurrentBuffer();
        File baseDir = buf != null && buf.getFile() != null ? buf.getFile().getParentFile() : new File(".");
        if (baseDir == null) baseDir = new File(".");
        // Walk up to find project root (has .git or is CWD)
        File projectRoot = baseDir;
        File probe = baseDir;
        for (int i = 0; i < 20 && probe != null; i++) {
            if (new File(probe, ".git").exists()) { projectRoot = probe; break; }
            probe = probe.getParentFile();
        }
        List<String> files = new ArrayList<>();
        collectProjectFiles(projectRoot, projectRoot.getAbsolutePath(), files, 5000);
        if (files.isEmpty()) {
            return "No files found";
        }
        String selected = editor.showPaletteDialog("Find File", files);
        if (selected == null) return "File finder cancelled";
        try {
            editor.openFile(new File(projectRoot, selected));
            return "Opened: " + selected;
        } catch (IOException e) {
            return "Error opening file: " + e.getMessage();
        }
    }


    void collectProjectFiles(File dir, String rootPath, List<String> result, int limit) {
        if (result.size() >= limit || dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (result.size() >= limit) return;
            if (child.getName().startsWith(".") || "node_modules".equals(child.getName())
                    || "target".equals(child.getName()) || "build".equals(child.getName())
                    || "__pycache__".equals(child.getName()) || ".git".equals(child.getName())) continue;
            if (child.isFile()) {
                String rel = child.getAbsolutePath().substring(rootPath.length());
                if (rel.startsWith(File.separator)) rel = rel.substring(1);
                result.add(rel);
            } else if (child.isDirectory()) {
                collectProjectFiles(child, rootPath, result, limit);
            }
        }
    }


    public String showFolderFinder() {
        File selection = chooseWithNavigator(JFileChooser.DIRECTORIES_ONLY, null, "Select Folder");
        if (selection == null) {
            return "Folder finder cancelled";
        }
        if (!selection.isDirectory()) {
            return "Not a folder: " + selection.getPath();
        }
        return showFileFinderFromFolder(selection);
    }


    String showFileFinderFromFolder(File folder) {
        File selection = chooseWithNavigator(JFileChooser.FILES_ONLY, folder, "Open File in " + folder.getPath());
        if (selection == null) {
            return "Folder selected: " + folder.getPath();
        }
        if (!selection.isFile()) {
            return "Not a file: " + selection.getPath();
        }

        try {
            editor.openFile(selection);
            return "Opened: " + selection.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening file: " + e.getMessage();
        }
    }


    File chooseWithNavigator(int selectionMode, File startDirectory, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(selectionMode);
        chooser.setDialogTitle(title);
        chooser.setCurrentDirectory(resolveNavigatorStartDirectory(startDirectory));
        int result = chooser.showOpenDialog(editor);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile();
    }


    File resolveNavigatorStartDirectory(File preferred) {
        if (preferred != null && preferred.exists()) {
            return preferred;
        }
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer != null && buffer.hasFilePath()) {
            File parent = new File(buffer.getFilePath()).getParentFile();
            if (parent != null && parent.exists()) {
                return parent;
            }
        }
        return new File(System.getProperty("user.home"));
    }


    public String handleTreeCommand(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            return showFileTree("");
        }

        int split = trimmed.indexOf(' ');
        String subcommand = split < 0 ? trimmed.toLowerCase() : trimmed.substring(0, split).toLowerCase();
        String args = split < 0 ? "" : trimmed.substring(split + 1).trim();
        switch (subcommand) {
            case "refresh":
                if (editor.treeRoot == null) {
                    return showFileTree("");
                }
                return showFileTree(editor.treeRoot.getAbsolutePath());
            case "reveal":
                return revealCurrentInTree();
            case "new":
                return treeCreateFile(args);
            case "mkdir":
                return treeCreateDirectory(args);
            case "rename":
                return treeRename(args);
            case "rm":
            case "delete":
                return treeDelete(args, false);
            case "rm!":
            case "delete!":
                return treeDelete(args, true);
            default:
                return showFileTree(trimmed);
        }
    }


    String revealCurrentInTree() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return "Current buffer is not file-backed";
        }
        File root = editor.treeService.revealRootForPath(new File(buffer.getFilePath()));
        if (root == null || !root.exists()) {
            return "Cannot reveal current buffer";
        }
        return showFileTree(root.getAbsolutePath());
    }


    String treeCreateFile(String pathArgument) {
        File target = editor.treeService.resolveActionPath(pathArgument, editor.treeRoot);
        if (target == null) {
            return "Usage: :tree new <path>";
        }
        try {
            boolean existed = target.exists();
            editor.treeService.createFile(target);
            if (editor.treeRoot == null) {
                editor.treeRoot = editor.treeService.revealRootForPath(target);
            }
            if (editor.treePane != null && editor.editorPanes.contains(editor.treePane) && editor.treeRoot != null) {
                showFileTree(editor.treeRoot.getAbsolutePath());
            }
            return existed ? "File exists: " + target.getAbsolutePath() : "Created file: " + target.getAbsolutePath();
        } catch (IOException e) {
            return "Tree new failed: " + e.getMessage();
        }
    }


    String treeCreateDirectory(String pathArgument) {
        File target = editor.treeService.resolveActionPath(pathArgument, editor.treeRoot);
        if (target == null) {
            return "Usage: :tree mkdir <path>";
        }
        try {
            editor.treeService.createDirectory(target);
            if (editor.treeRoot == null) {
                editor.treeRoot = target;
            }
            if (editor.treePane != null && editor.editorPanes.contains(editor.treePane) && editor.treeRoot != null) {
                showFileTree(editor.treeRoot.getAbsolutePath());
            }
            return "Created directory: " + target.getAbsolutePath();
        } catch (IOException e) {
            return "Tree mkdir failed: " + e.getMessage();
        }
    }


    String treeRename(String argument) {
        if (argument == null || argument.isBlank()) {
            return "Usage: :tree rename <from> <to>";
        }
        List<String> parts = parseQuotedArguments(argument);
        if (parts.size() < 2) {
            return "Usage: :tree rename <from> <to>";
        }
        File from = editor.treeService.resolveActionPath(parts.get(0), editor.treeRoot);
        File to = editor.treeService.resolveActionPath(parts.get(1), editor.treeRoot);
        if (from == null || to == null) {
            return "Usage: :tree rename <from> <to>";
        }
        if (!from.exists()) {
            return "Path not found: " + from.getAbsolutePath();
        }
        try {
            editor.treeService.rename(from, to);
            if (editor.treeRoot != null && editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
                showFileTree(editor.treeRoot.getAbsolutePath());
            }
            return "Renamed: " + from.getAbsolutePath() + " -> " + to.getAbsolutePath();
        } catch (IOException e) {
            return "Tree rename failed: " + e.getMessage();
        }
    }


    String treeDelete(String argument, boolean force) {
        File target = editor.treeService.resolveActionPath(argument, editor.treeRoot);
        if (target == null) {
            return "Usage: :tree rm <path>";
        }
        if (!target.exists()) {
            return "Path not found: " + target.getAbsolutePath();
        }
        String guardError = validateTreeDeleteTarget(target);
        if (guardError != null) {
            return guardError;
        }
        if (!force && target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null && children.length > 0) {
                return "Directory not empty (use :tree rm! <path>)";
            }
        }
        try {
            int removed = editor.treeService.deleteRecursively(target);
            if (editor.treeRoot != null && editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
                showFileTree(editor.treeRoot.getAbsolutePath());
            }
            return "Deleted " + removed + " path(s)";
        } catch (IOException e) {
            return "Tree delete failed: " + e.getMessage();
        }
    }


    String validateTreeDeleteTarget(File target) {
        if (target == null || !editor.configManager.getTreeDeleteProtectCritical()) {
            return null;
        }
        try {
            File canonicalTarget = target.getCanonicalFile();
            java.nio.file.Path targetPath = canonicalTarget.toPath();
            java.nio.file.Path root = targetPath.getRoot();
            if (root != null && root.equals(targetPath)) {
                return "Refusing to delete filesystem root: " + canonicalTarget.getAbsolutePath()
                    + " (set tree.delete.protect.critical=false to override)";
            }

            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                File homeDir = new File(home).getCanonicalFile();
                if (homeDir.toPath().equals(targetPath)) {
                    return "Refusing to delete home directory: " + canonicalTarget.getAbsolutePath()
                        + " (set tree.delete.protect.critical=false to override)";
                }
            }

            File cwd = new File(".").getCanonicalFile();
            if (cwd.toPath().equals(targetPath)) {
                return "Refusing to delete current working directory: " + canonicalTarget.getAbsolutePath()
                    + " (set tree.delete.protect.critical=false to override)";
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }


    public String showFileTree(String pathArgument) {
        String trimmed = pathArgument == null ? "" : pathArgument.trim();
        if (trimmed.isEmpty() && editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
            return closeTreePane();
        }

        File root;
        if (trimmed.isEmpty()) {
            root = editor.treeRoot != null ? editor.treeRoot : editor.treeService.resolveRoot("");
        } else {
            root = editor.treeService.resolveRoot(trimmed);
        }
        if (!root.exists()) {
            return "Path not found: " + root.getPath();
        }
        editor.treeRoot = root.getAbsoluteFile();

        StringBuilder builder = new StringBuilder();
        List<String> lineTargets = new ArrayList<>();
        appendTreeLine(builder, lineTargets, "File tree", null);
        appendTreeLine(builder, lineTargets, "", null);
        appendTreeLine(builder, lineTargets, root.getAbsolutePath(), root.isFile() ? root.getAbsolutePath() : null);
        int[] rendered = new int[] {0};
        if (root.isDirectory()) {
            File[] children = listTreeChildren(root);
            if (children.length == 0) {
                appendTreeLine(builder, lineTargets, "(empty)", null);
            } else {
                for (int i = 0; i < children.length; i++) {
                    appendTreeEntry(builder, lineTargets, children[i], "", i == children.length - 1, rendered, 1200);
                }
            }
        } else {
            appendTreeLine(builder, lineTargets, "\\-- " + root.getName(), root.getAbsolutePath());
        }

        if (rendered[0] >= 1200) {
            appendTreeLine(builder, lineTargets, "", null);
            appendTreeLine(builder, lineTargets, "... output truncated (1200 entries)", null);
        }

        String titleSuffix = treeTitleSuffix(root);
        FileBuffer tree = createOrReplaceTreeBuffer(titleSuffix, builder.toString(), lineTargets);
        EditorPane contentPane = resolveTreeContentPaneForTreeCommand();
        if (contentPane == null) {
            return "No active window";
        }
        EditorPane pane = ensureTreePane(contentPane);
        if (pane == null) {
            return "Unable to open tree pane";
        }
        editor.loadBufferIntoPane(pane, tree, 0);
        editor.activateEditorPane(pane);
        pane.getTextArea().requestFocusInWindow();
        return "Tree pane opened";
    }


    String closeTreePane() {
        if (editor.treePane == null || !editor.editorPanes.contains(editor.treePane)) {
            return "Tree pane already closed";
        }
        String result = editor.closePane(editor.treePane);
        if ("Window closed".equals(result)) {
            editor.animateEditorHostTint(editor.configManager.getCommandColor());
            return "Tree pane closed";
        }
        return result;
    }


    void appendTreeEntry(StringBuilder builder, List<String> lineTargets, File entry, String prefix, boolean last, int[] rendered, int maxEntries) {
        if (rendered[0] >= maxEntries) {
            return;
        }
        StringBuilder lineBuilder = new StringBuilder();
        lineBuilder.append(prefix).append(last ? "\\-- " : "|-- ");
        lineBuilder.append(entry.getName());
        if (entry.isDirectory()) {
            lineBuilder.append("/");
        }
        appendTreeLine(builder, lineTargets, lineBuilder.toString(), entry.isFile() ? entry.getAbsolutePath() : null);
        rendered[0]++;

        if (!entry.isDirectory()) {
            return;
        }

        File[] children = listTreeChildren(entry);
        String childPrefix = prefix + (last ? "    " : "|   ");
        for (int i = 0; i < children.length; i++) {
            appendTreeEntry(builder, lineTargets, children[i], childPrefix, i == children.length - 1, rendered, maxEntries);
            if (rendered[0] >= maxEntries) {
                return;
            }
        }
    }


    void appendTreeLine(StringBuilder builder, List<String> lineTargets, String text, String targetPath) {
        builder.append(text).append("\n");
        lineTargets.add(targetPath);
    }


    File[] listTreeChildren(File directory) {
        File[] children = directory.listFiles(file -> !editor.shouldSkipHiddenPath(file));
        if (children == null || children.length == 0) {
            return new File[0];
        }
        java.util.Arrays.sort(children, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) {
                return left.isDirectory() ? -1 : 1;
            }
            return left.getName().compareToIgnoreCase(right.getName());
        });
        return children;
    }


    String treeTitleSuffix(File root) {
        return editor.treeService.titleSuffix(root);
    }


    FileBuffer createOrReplaceTreeBuffer(String titleSuffix, String content, List<String> lineTargets) {
        FileBuffer replacement = FileBuffer.createScratch("[tree " + titleSuffix + "]", content);
        if (editor.treeBuffer != null) {
            int index = editor.buffers.indexOf(editor.treeBuffer);
            if (index >= 0) {
                editor.buffers.set(index, replacement);
            } else {
                editor.buffers.add(replacement);
            }
            editor.treeLineTargets.remove(editor.treeBuffer);
        } else {
            editor.buffers.add(replacement);
        }
        editor.treeBuffer = replacement;
        editor.treeLineTargets.put(replacement, lineTargets);
        return replacement;
    }


    EditorPane resolveTreeContentPaneForTreeCommand() {
        EditorPane active = editor.getActivePane();
        if (editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
            if (active != null && active != editor.treePane) {
                return active;
            }
            for (EditorPane pane : editor.editorPanes) {
                if (pane != editor.treePane) {
                    return pane;
                }
            }
        }
        return active;
    }


    EditorPane ensureTreePane(EditorPane contentPane) {
        if (editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
            return editor.treePane;
        }
        if (contentPane == null) {
            return null;
        }

        Dimension size = editor.getSize();
        EditorPane newPane = editor.createEditorPane(size);
        editor.editorPanes.add(newPane);
        if (editor.windowLayoutRoot == null) {
            editor.windowLayoutRoot = WindowLayoutNode.leaf(contentPane);
        }
        double treeTargetRatio = 0.24;
        double treeStartRatio = editor.dramaticPanelAnimationsEnabled && editor.dramaticMotionAllowed() ? 0.08 : treeTargetRatio;
        boolean split = editor.windowLayoutRoot.splitLeaf(contentPane, newPane, WindowLayoutNode.Orientation.HORIZONTAL, true, treeStartRatio);
        if (!split) {
            editor.windowLayoutRoot.splitLeaf(contentPane, newPane, WindowLayoutNode.Orientation.HORIZONTAL);
        }
        editor.renderWindowLayout();
        editor.animateSplitForPane(newPane, treeStartRatio, treeTargetRatio);
        editor.animateEditorHostTint(editor.configManager.getVisualColor());
        editor.treePane = newPane;
        return newPane;
    }


    boolean isTreePaneActive() {
        EditorPane active = editor.getActivePane();
        return active != null && active == editor.treePane && isTreeBuffer(editor.getCurrentBuffer());
    }


    boolean isTreeBuffer(FileBuffer buffer) {
        return buffer != null && editor.treeLineTargets.containsKey(buffer);
    }


    String openTreeSelection() {
        FileBuffer current = editor.getCurrentBuffer();
        if (!isTreeBuffer(current)) {
            return "Tree pane not active";
        }

        List<String> targets = editor.treeLineTargets.get(current);
        if (targets == null || targets.isEmpty()) {
            return "No file on this line";
        }

        int line = editor.getCurrentCaretLine();
        if (line < 0 || line >= targets.size()) {
            return "No file on this line";
        }
        String path = targets.get(line);
        if (path == null || path.isBlank()) {
            return "No file on this line";
        }

        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return "File not found: " + path;
        }

        EditorPane contentPane = resolveTreeContentPaneForOpen();
        if (contentPane == null) {
            return "No content pane available";
        }

        try {
            FileBuffer existing = editor.findBufferByPath(file);
            FileBuffer targetBuffer = existing != null ? existing : new FileBuffer(file, editor.configManager);
            if (existing == null) {
                if (editor.shouldReplaceSingleLandingBuffer()) {
                    editor.buffers.set(0, targetBuffer);
                } else {
                    editor.buffers.add(targetBuffer);
                }
            }

            editor.loadBufferIntoPane(contentPane, targetBuffer, 0);
            editor.activateEditorPane(contentPane);
            contentPane.getTextArea().requestFocusInWindow();
            editor.addToRecentFiles(file.getAbsolutePath());
            return "Opened: " + file.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening file: " + e.getMessage();
        }
    }


    EditorPane resolveTreeContentPaneForOpen() {
        if (editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
            for (EditorPane pane : editor.editorPanes) {
                if (pane != editor.treePane) {
                    return pane;
                }
            }
        }
        return editor.getActivePane();
    }


    public String handleGitCommand(String argument) {
        File gitRoot = resolveGitRoot();
        String trimmed = argument == null ? "" : argument.trim();
        if (gitRoot != null && trimmed.toLowerCase(Locale.ROOT).startsWith("hunk")) {
            String rest = trimmed.length() <= 4 ? "" : trimmed.substring(4).trim();
            return runGitHunkCommand(gitRoot, rest);
        }
        return editor.gitService.handle(argument, gitRoot, new GitService.Handler() {
            @Override
            public String status(File root) {
                return showGitStatus(root);
            }

            @Override
            public String diff(File root, String args) {
                return showGitDiff(root, args);
            }

            @Override
            public String log(File root, String args) {
                return showGitLog(root, args);
            }

            @Override
            public String branches(File root) {
                return showGitBranches(root);
            }

            @Override
            public String add(File root, String args) {
                return runGitAdd(root, args);
            }

            @Override
            public String stage(File root, String args) {
                return runGitAdd(root, args);
            }

            @Override
            public String restore(File root, String args) {
                return runGitRestoreStaged(root, args);
            }

            @Override
            public String unstage(File root, String args) {
                return runGitRestoreStaged(root, args);
            }

            @Override
            public String commit(File root, String args) {
                return runGitCommit(root, args);
            }

            @Override
            public String amend(File root, String args) {
                return runGitAmend(root, args);
            }

            @Override
            public String checkout(File root, String args) {
                return runGitCheckout(root, args);
            }

            @Override
            public String switchBranch(File root, String args) {
                return runGitSwitch(root, args);
            }

            @Override
            public String help() {
                return showGitHelp();
            }
        });
    }


    File resolveGitRoot() {
        CommandResult result = runCommand(new File("."), List.of("git", "rev-parse", "--show-toplevel"));
        if (result.exitCode != 0) {
            return null;
        }
        String path = result.stdout.strip();
        if (path.isEmpty()) {
            return null;
        }
        File root = new File(path);
        return root.exists() ? root : null;
    }


    String showGitStatus(File gitRoot) {
        CommandResult result = runCommand(gitRoot, List.of("git", "status", "--short", "--branch"));
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "(clean working tree)";
        }
        editor.showScratchBuffer("[git status]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n");
        return "Showing git status";
    }


    String showGitDiff(File gitRoot, String args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("diff");
        command.addAll(splitWhitespaceArgs(args));
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "(no diff)";
        }
        editor.showScratchBuffer("[git diff]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n");
        return "Showing git diff";
    }


    void refreshGitGutter() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) return;
        String filePath = buffer.getFilePath();
        // run git diff on background thread to avoid blocking EDT
        new Thread(() -> {
            if (!cachedGitRootResolved) { cachedGitRoot = resolveGitRoot(); cachedGitRootResolved = true; }
            File gitRoot = cachedGitRoot;
            if (gitRoot == null) return;
            CommandResult result = runCommand(gitRoot, List.of("git", "diff", "HEAD", "--unified=0", "--", filePath));
            Set<Integer> added = new HashSet<>();
            Set<Integer> modified = new HashSet<>();
            Set<Integer> deletedAfter = new HashSet<>();
            if (result.exitCode == 0 && result.stdout != null) {
                parseUnifiedDiffForGutter(result.stdout, added, modified, deletedAfter);
            }
            SwingUtilities.invokeLater(() -> {
                EditorPane pane = editor.getActivePane();
                if (pane != null && pane.getLineNumberPanel() != null) {
                    pane.getLineNumberPanel().updateGitDiffMarkers(added, modified, deletedAfter);
                }
            });
        }, "shed-git-gutter").start();
    }


    void parseUnifiedDiffForGutter(String diff, Set<Integer> added, Set<Integer> modified, Set<Integer> deletedAfter) {
        // parse @@ -oldStart[,oldCount] +newStart[,newCount] @@ lines
        for (String line : diff.split("\n")) {
            if (!line.startsWith("@@")) continue;
            int plusIdx = line.indexOf('+', 3);
            if (plusIdx < 0) continue;
            int spaceAfter = line.indexOf(' ', plusIdx);
            if (spaceAfter < 0) spaceAfter = line.indexOf('@', plusIdx + 1);
            if (spaceAfter < 0) continue;
            String newRange = line.substring(plusIdx + 1, spaceAfter);
            String[] parts = newRange.split(",");
            int newStart, newCount;
            try {
                newStart = Integer.parseInt(parts[0]);
                newCount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            } catch (NumberFormatException e) { continue; }
            // determine old count
            int minusIdx = line.indexOf('-', 3);
            int oldCount = 0;
            if (minusIdx >= 0) {
                int minusEnd = line.indexOf(' ', minusIdx);
                if (minusEnd < 0) minusEnd = plusIdx;
                String oldRange = line.substring(minusIdx + 1, minusEnd);
                String[] oldParts = oldRange.split(",");
                try { oldCount = oldParts.length > 1 ? Integer.parseInt(oldParts[1]) : 1; } catch (NumberFormatException e) { continue; }
            }
            if (newCount == 0 && oldCount > 0) {
                // pure deletion
                deletedAfter.add(Math.max(0, newStart - 1));
            } else if (oldCount == 0 && newCount > 0) {
                // pure addition
                for (int i = 0; i < newCount; i++) added.add(newStart - 1 + i);
            } else {
                // modification
                for (int i = 0; i < newCount; i++) modified.add(newStart - 1 + i);
            }
        }
    }


    String showGitLog(File gitRoot, String args) {
        int count = 20;
        if (args != null && !args.isBlank()) {
            try {
                count = Math.max(1, Math.min(200, Integer.parseInt(args.trim())));
            } catch (NumberFormatException e) {
                return "Usage: :git log [count]";
            }
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("log");
        command.add("--oneline");
        command.add("--decorate");
        command.add("--graph");
        command.add("-n");
        command.add(String.valueOf(count));
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "(no commits)";
        }
        editor.showScratchBuffer("[git log]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n");
        return "Showing git log";
    }


    String showGitBranches(File gitRoot) {
        CommandResult result = runCommand(gitRoot, List.of("git", "branch", "--all", "--verbose"));
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "(no branches)";
        }
        editor.showScratchBuffer("[git branch]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n");
        return "Showing git branches";
    }


    String runGitAdd(File gitRoot, String args) {
        List<String> pathSpecs = splitWhitespaceArgs(args);
        if (pathSpecs.isEmpty()) {
            return "Usage: :git add <pathspec...>";
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("add");
        command.add("--");
        command.addAll(pathSpecs);
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        return "git add complete";
    }


    String runGitRestoreStaged(File gitRoot, String args) {
        List<String> pathSpecs = splitWhitespaceArgs(args);
        if (pathSpecs.isEmpty()) {
            return "Usage: :git restore <pathspec...>";
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("restore");
        command.add("--staged");
        command.add("--");
        command.addAll(pathSpecs);
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        return "git restore --staged complete";
    }


    String runGitCommit(File gitRoot, String message) {
        if (message == null || message.isBlank()) {
            return "Usage: :git commit <message>";
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("commit");
        command.add("-m");
        command.add(message.trim());
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "commit created";
        }
        editor.showScratchBuffer("[git commit]", body + "\n");
        return "Commit complete";
    }


    String runGitAmend(File gitRoot, String argument) {
        if (!gitHeadExists(gitRoot)) {
            return "Git error: cannot amend before first commit";
        }
        String trimmed = argument == null ? "" : argument.trim();
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("commit");
        command.add("--amend");
        if (trimmed.isEmpty()) {
            return "Usage: :git amend <message> or :git amend --no-edit";
        }
        if ("--no-edit".equals(trimmed)) {
            command.add("--no-edit");
        } else {
            command.add("-m");
            command.add(trimmed);
        }
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        if (body.isEmpty()) {
            body = "commit amended";
        }
        editor.showScratchBuffer("[git amend]", body + "\n");
        return "Amend complete";
    }


    boolean gitHeadExists(File gitRoot) {
        CommandResult result = runCommand(gitRoot, List.of("git", "rev-parse", "--verify", "HEAD"));
        return result.exitCode == 0;
    }


    String runGitCheckout(File gitRoot, String argument) {
        if (argument == null || argument.isBlank()) {
            return "Usage: :git checkout <branch|path>";
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("checkout");
        command.addAll(splitWhitespaceArgs(argument));
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String output = result.stdout.strip();
        if (!output.isEmpty()) {
            editor.showScratchBuffer("[git checkout]", output + "\n");
        }
        return "Checkout complete";
    }


    String runGitSwitch(File gitRoot, String argument) {
        if (argument == null || argument.isBlank()) {
            return "Usage: :git switch <branch>";
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("switch");
        command.addAll(splitWhitespaceArgs(argument));
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String output = result.stdout.strip();
        if (!output.isEmpty()) {
            editor.showScratchBuffer("[git switch]", output + "\n");
        }
        return "Switch complete";
    }


    String runGitHunkCommand(File gitRoot, String argument) {
        List<String> args = splitWhitespaceArgs(argument);
        if (args.isEmpty()) {
            return "Usage: :git hunk stage|unstage|revert [line]";
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        int line = editor.getCurrentLineNumber();
        if (args.size() >= 2) {
            try {
                line = Math.max(1, Integer.parseInt(args.get(1)));
            } catch (NumberFormatException e) {
                return "Invalid line for :git hunk: " + args.get(1);
            }
        }
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) {
            return ":git hunk requires a file-backed buffer";
        }
        String relativePath = relativizeAgainstGitRoot(gitRoot, new File(buffer.getFilePath()));
        if (relativePath == null || relativePath.isBlank()) {
            return "Current file is outside git root";
        }

        boolean useCached = "unstage".equals(action);
        CommandResult diff = runCommand(gitRoot, useCached
            ? List.of("git", "diff", "--cached", "-U0", "--", relativePath)
            : List.of("git", "diff", "-U0", "--", relativePath));
        if (diff.exitCode != 0) {
            return gitError(diff);
        }
        if (diff.stdout == null || diff.stdout.isBlank()) {
            return "No matching diff hunks";
        }

        String selectedPatch = selectGitHunkPatch(diff.stdout, line);
        if (selectedPatch == null || selectedPatch.isBlank()) {
            return "No hunk found for line " + line;
        }

        List<String> applyCommand = new ArrayList<>();
        applyCommand.add("git");
        applyCommand.add("apply");
        if ("stage".equals(action)) {
            applyCommand.add("--cached");
        } else if ("unstage".equals(action)) {
            applyCommand.add("-R");
            applyCommand.add("--cached");
        } else if ("revert".equals(action) || "discard".equals(action)) {
            applyCommand.add("-R");
        } else {
            return "Usage: :git hunk stage|unstage|revert [line]";
        }
        applyCommand.add("--unidiff-zero");
        applyCommand.add("-");

        CommandResult applied = editor.runExternalCommand(
            applyCommand,
            gitRoot,
            selectedPatch,
            null,
            editor.configManager.getProcessTimeoutMs(),
            editor.configManager.getProcessOutputMaxBytes(),
            true
        );
        if (applied.exitCode != 0) {
            return gitError(applied);
        }

        if ("revert".equals(action) || "discard".equals(action)) {
            try {
                buffer.load(editor.configManager);
                if (buffer == editor.getCurrentBuffer()) {
                    editor.loadBufferIntoEditor(buffer);
                    editor.writingArea.setCaretPosition(Math.min(editor.writingArea.getCaretPosition(), editor.writingArea.getText().length()));
                }
            } catch (IOException ignored) {
            }
        }
        refreshGitGutter();
        return "git hunk " + action + " complete (line " + line + ")";
    }


    String selectGitHunkPatch(String diff, int line) {
        if (diff == null || diff.isBlank()) {
            return null;
        }
        String[] lines = diff.split("\n", -1);
        List<String> header = new ArrayList<>();
        int cursor = 0;
        while (cursor < lines.length && !lines[cursor].startsWith("@@")) {
            header.add(lines[cursor]);
            cursor++;
        }
        if (cursor >= lines.length) {
            return null;
        }

        while (cursor < lines.length) {
            int hunkStart = cursor;
            String marker = lines[cursor];
            cursor++;
            while (cursor < lines.length && !lines[cursor].startsWith("@@")) {
                cursor++;
            }
            int hunkEnd = cursor;

            if (gitHunkContainsLine(marker, line)) {
                StringBuilder patch = new StringBuilder();
                for (String headerLine : header) {
                    patch.append(headerLine).append("\n");
                }
                for (int i = hunkStart; i < hunkEnd; i++) {
                    patch.append(lines[i]).append("\n");
                }
                return patch.toString();
            }
        }
        return null;
    }


    boolean gitHunkContainsLine(String marker, int line) {
        if (marker == null || !marker.startsWith("@@")) {
            return false;
        }
        Matcher matcher = Pattern.compile("@@ -\\d+(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@").matcher(marker);
        if (!matcher.find()) {
            return false;
        }
        int newStart;
        int newCount;
        try {
            newStart = Integer.parseInt(matcher.group(2));
            String countRaw = matcher.group(3);
            newCount = countRaw == null || countRaw.isBlank() ? 1 : Integer.parseInt(countRaw);
        } catch (NumberFormatException e) {
            return false;
        }
        if (newCount == 0) {
            return line == newStart || line == Math.max(1, newStart - 1);
        }
        return line >= newStart && line < (newStart + newCount);
    }


    String relativizeAgainstGitRoot(File gitRoot, File file) {
        if (gitRoot == null || file == null) {
            return null;
        }
        try {
            Path rootPath = gitRoot.getCanonicalFile().toPath();
            Path filePath = file.getCanonicalFile().toPath();
            if (!filePath.startsWith(rootPath)) {
                return null;
            }
            return rootPath.relativize(filePath).toString();
        } catch (IOException e) {
            return null;
        }
    }


    String showGitHelp() {
        editor.showScratchBuffer("[git help]",
            "Git commands\n\n"
                + ":git                  Show status\n"
                + ":git status|st        Show status\n"
                + ":git diff [args]      Show diff\n"
                + ":git log [count]      Show compact history\n"
                + ":git branch           Show branch list\n"
                + ":git add|stage <paths...> Stage paths\n"
                + ":git restore|unstage <paths> Unstage paths\n"
                + ":git hunk stage|unstage|revert [line] Hunk action at current/line\n"
                + ":git commit <msg>     Commit staged changes\n"
                + ":git amend <msg>      Amend last commit message/content\n"
                + ":git checkout <arg>   Checkout branch/path\n"
                + ":git switch <branch>  Switch branch\n");
        return "Showing git help";
    }


    List<String> splitWhitespaceArgs(String args) {
        return parseQuotedArguments(args);
    }


    List<String> parseQuotedArguments(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        char quote = '\0';
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (quote != '\0') {
                if (c == quote) {
                    quote = '\0';
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (escaped) {
            current.append('\\');
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }


    CommandResult runCommand(File workingDirectory, List<String> command) {
        return editor.runExternalCommand(
            command,
            workingDirectory,
            null,
            null,
            editor.configManager.getProcessTimeoutMs(),
            editor.configManager.getProcessOutputMaxBytes(),
            true
        );
    }


    String gitError(CommandResult result) {
        String message = result.stderr == null ? "" : result.stderr.strip();
        if (message.isEmpty()) {
            message = result.stdout == null ? "" : result.stdout.strip();
        }
        if (message.isEmpty()) {
            message = "git command failed (exit " + result.exitCode + ")";
        }
        return "Git error: " + message;
    }

}
