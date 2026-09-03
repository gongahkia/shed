package shed;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TreeGitController {
    private enum InteractiveGitView { BRANCHES, LOG }

    private final Texteditor editor;
    private File cachedGitRoot;
    private File cachedGitWorkingDirectory;
    private boolean cachedGitRootResolved;
    private final ProjectFileScanner projectFileScanner;
    private FileBuffer interactiveGitBuffer;
    private File interactiveGitRoot;
    private InteractiveGitView interactiveGitView;
    private int gitGutterJobId = -1;
    private long gitGutterGeneration;

    TreeGitController(Texteditor editor) {
        this.editor = editor;
        this.projectFileScanner = new ProjectFileScanner();
        this.interactiveGitBuffer = null;
        this.interactiveGitRoot = null;
        this.interactiveGitView = null;
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
        if (dir == null || rootPath == null || result.size() >= limit) {
            return;
        }
        int remaining = limit - result.size();
        ProjectFileScanner.ScanResult scan = projectFileScanner.scan(dir.toPath(), Path.of(rootPath), remaining, ProjectFileScanner.Cancellation.NONE);
        result.addAll(scan.files());
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
        if (editor.goyoModeEnabled) {
            return "Tree hidden in Goyo mode";
        }
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
        setWorkspaceTreeRoot(root.getAbsoluteFile(), false);

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

    void setWorkspaceTreeRoot(File root, boolean showTree) {
        editor.treeRoot = root == null ? null : root.getAbsoluteFile();
        cachedGitRoot = null;
        cachedGitWorkingDirectory = null;
        cachedGitRootResolved = false;
        if (editor.treeRoot != null) editor.workspaceController.observeTreeRoot(editor.treeRoot);
        File treeGitRoot = resolveGitRoot();
        editor.gitBranch = treeGitRoot == null ? "" : resolveBranchName(treeGitRoot);
        editor.updateStatusBar();
        editor.refreshGitGutter();
        if (showTree && editor.treeRoot != null && editor.treePane != null && editor.editorPanes.contains(editor.treePane)) {
            showFileTree(editor.treeRoot.getAbsolutePath());
        }
    }


    String closeTreePane() {
        if (editor.treePane == null || !editor.editorPanes.contains(editor.treePane)) {
            return "Tree pane already closed";
        }
        String result = editor.closePane(editor.treePane);
        if ("Window closed".equals(result)) return "Tree pane closed";
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
        boolean split = editor.windowLayoutRoot.splitLeaf(contentPane, newPane, WindowLayoutNode.Orientation.HORIZONTAL, true, treeTargetRatio);
        if (!split) {
            editor.windowLayoutRoot.splitLeaf(contentPane, newPane, WindowLayoutNode.Orientation.HORIZONTAL);
        }
        editor.renderWindowLayout();
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
            editor.showCustomEditorIfAvailable(contentPane, targetBuffer);
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
        String trimmed = argument == null ? "" : argument.trim();
        if ("conflict".equalsIgnoreCase(trimmed) || "conflicts".equalsIgnoreCase(trimmed)) {
            return showGitConflictResolutionDocument();
        }
        if ("history".equalsIgnoreCase(trimmed) || "remote".equalsIgnoreCase(trimmed)) {
            return showGitHistoryRemoteDocument();
        }
        if ("worktree".equalsIgnoreCase(trimmed) || "worktrees".equalsIgnoreCase(trimmed)
            || "stash".equalsIgnoreCase(trimmed) || "stashes".equalsIgnoreCase(trimmed)) {
            return showGitRepositoryToolsDocument();
        }
        if ("workbench".equalsIgnoreCase(trimmed) || "changes".equalsIgnoreCase(trimmed) || "ui".equalsIgnoreCase(trimmed)) {
            editor.showToolWindow(ToolWindowHost.Tab.GIT);
            return "Git Changes panel opened";
        }
        if (trimmed.equalsIgnoreCase("text")) {
            File root = resolveGitRoot();
            return root == null ? "Not inside a git repository" : showGitStatus(root);
        }
        if (trimmed.regionMatches(true, 0, "text ", 0, 5)) {
            argument = trimmed.substring(5).trim();
            trimmed = argument;
        }
        File gitRoot = resolveGitRoot();
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

    String showGitChangesWorkbench() {
        if (!editor.configManager.getGitWorkbenchEnabled()) {
            return "Git workbench disabled by git.workbench.enabled=false";
        }
        if (!editor.configManager.getGitChangesEnabled()) {
            return "Git changes disabled by git.changes.enabled=false";
        }
        if (!editor.configManager.getGitPanelPresentationEnabled()) {
            return "Git documents disabled by git.panel.presentation.enabled=false";
        }
        GitChangesWorkbenchDialog.showFor(editor, new GitChangesWorkbenchDialog.Loader() {
            @Override
            public GitChangesWorkbenchModel.Snapshot status() {
                return loadGitChangesWorkbench();
            }

            @Override
            public GitChangesWorkbenchModel.Diff diff(GitChangesWorkbenchModel.Snapshot snapshot, GitChangesWorkbenchModel.Change change) {
                return loadGitChangesWorkbenchDiff(snapshot, change);
            }

            @Override
            public String open(GitChangesWorkbenchModel.Snapshot snapshot, GitChangesWorkbenchModel.Change change,
                GitChangesWorkbenchModel.Diff diff, GitHunkNavigation.Hunk hunk) {
                return openGitChangesWorkbenchTarget(snapshot, change, diff, hunk);
            }
        });
        return "Git workbench opened";
    }

    String showGitConflictResolutionDocument() {
        if (!editor.configManager.getGitConflictResolutionEnabled()) {
            return "Git conflict resolution disabled by git.conflict.resolution.enabled=false";
        }
        if (!editor.configManager.getGitPanelPresentationEnabled()) {
            return "Git documents disabled by git.panel.presentation.enabled=false";
        }
        GitConflictResolutionDialog.showFor(editor, new GitConflictResolutionDialog.Loader() {
            @Override
            public GitConflictResolutionDialog.Load load() {
                return loadGitConflicts();
            }

            @Override
            public String apply(GitConflictResolutionModel.Conflict conflict, String result) {
                return applyGitConflictResolution(conflict, result);
            }
        });
        return "Git conflict resolution opened";
    }

    String showGitHistoryRemoteDocument() {
        if (!editor.configManager.getGitHistoryEnabled()) {
            return "Git history disabled by git.history.enabled=false";
        }
        if (!editor.configManager.getGitPanelPresentationEnabled()) {
            return "Git documents disabled by git.panel.presentation.enabled=false";
        }
        GitHistoryRemoteDialog.showFor(editor, new GitHistoryRemoteDialog.Loader() {
            @Override
            public GitHistoryModel.Snapshot load(AsyncJobService.JobToken token) {
                return loadGitHistory(token);
            }

            @Override
            public GitHistoryModel.RemoteResult run(GitHistoryModel.RemoteAction action, AsyncJobService.JobToken token) {
                return runGitRemoteOperation(action, token);
            }
        });
        return "Git history opened";
    }

    String showGitRepositoryToolsDocument() {
        GitRepositoryDialog.showFor(editor, new GitRepositoryDialog.Loader() {
            @Override public GitRepositoryModel.Snapshot load(AsyncJobService.JobToken token) { return loadGitRepositoryTools(token); }
            @Override public String createWorktree(String path, String branch, boolean createBranch, AsyncJobService.JobToken token) {
                return createGitWorktree(path, branch, createBranch, token);
            }
            @Override public String removeWorktree(GitRepositoryModel.Worktree worktree, AsyncJobService.JobToken token) {
                return removeGitWorktree(worktree, token);
            }
            @Override public String stashPush(String message, AsyncJobService.JobToken token) { return pushGitStash(message, token); }
            @Override public String stashApply(GitRepositoryModel.Stash stash, boolean pop, AsyncJobService.JobToken token) {
                return applyGitStash(stash, pop, token);
            }
            @Override public String stashDrop(GitRepositoryModel.Stash stash, AsyncJobService.JobToken token) { return dropGitStash(stash, token); }
            @Override public String openWorktree(GitRepositoryModel.Worktree worktree) { return openGitWorktree(worktree); }
        });
        return "Git Worktrees and Stashes opened";
    }

    private GitRepositoryModel.Snapshot loadGitRepositoryTools(AsyncJobService.JobToken token) {
        File root = resolveGitRoot();
        if (root == null) return GitRepositoryModel.unavailable("Not inside a Git repository.");
        CommandResult worktrees = runCommand(root, List.of("git", "worktree", "list", "--porcelain", "-z"), token);
        if (token != null && token.isCancelled()) return GitRepositoryModel.unavailable("Git worktree refresh cancelled.");
        CommandResult stashes = runCommand(root, List.of("git", "stash", "list", "-z", "--format=%gd%x1f%H%x1f%gs%x1f%ci"), token);
        return GitRepositoryModel.fromCommands(root, worktrees, stashes);
    }

    private String createGitWorktree(String pathArgument, String branchArgument, boolean createBranch, AsyncJobService.JobToken token) {
        File root = resolveGitRoot();
        if (root == null) return "Not inside a Git repository";
        if (pathArgument == null || pathArgument.isBlank() || branchArgument == null || branchArgument.isBlank()) {
            return "Worktree path and branch are required";
        }
        Path target;
        try {
            target = Path.of(pathArgument).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return "Invalid worktree path";
        }
        if (Files.exists(target)) return "Worktree destination already exists: " + target;
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) return "Worktree destination parent does not exist: " + target;
        List<String> command = new ArrayList<>(List.of("git", "worktree", "add"));
        if (createBranch) command.addAll(List.of("-b", branchArgument.trim()));
        command.add(target.toString());
        if (!createBranch) command.add(branchArgument.trim());
        CommandResult result = runCommand(root, command, token);
        if (result.exitCode != 0) return gitError(result);
        return "Created worktree: " + target;
    }

    private String removeGitWorktree(GitRepositoryModel.Worktree worktree, AsyncJobService.JobToken token) {
        File root = resolveGitRoot();
        if (root == null) return "Not inside a Git repository";
        if (worktree == null || worktree.path().isBlank()) return "Select a worktree";
        if (worktree.main()) return "The main worktree cannot be removed";
        CommandResult result = runCommand(root, List.of("git", "worktree", "remove", worktree.path()), token);
        return result.exitCode == 0 ? "Removed worktree: " + worktree.path() : gitError(result);
    }

    private String openGitWorktree(GitRepositoryModel.Worktree worktree) {
        if (worktree == null || worktree.path().isBlank()) return "Select a worktree";
        File root = new File(worktree.path());
        return root.isDirectory() ? editor.workspaceController.addDirectory(root, true) : "Worktree directory is unavailable: " + worktree.path();
    }

    private String pushGitStash(String message, AsyncJobService.JobToken token) {
        File root = resolveGitRoot();
        if (root == null) return "Not inside a Git repository";
        List<String> command = new ArrayList<>(List.of("git", "stash", "push", "--include-untracked"));
        if (message != null && !message.isBlank()) command.addAll(List.of("--message", message.strip()));
        CommandResult result = runCommand(root, command, token);
        return result.exitCode == 0 ? outputOr(result, "Stash created") : gitError(result);
    }

    private String applyGitStash(GitRepositoryModel.Stash stash, boolean pop, AsyncJobService.JobToken token) {
        File root = resolveGitRoot();
        if (root == null) return "Not inside a Git repository";
        if (stash == null || !stash.reference().matches("stash@\\{\\d+}")) return "Select a valid stash";
        CommandResult result = runCommand(root, List.of("git", "stash", pop ? "pop" : "apply", "--index", stash.reference()), token);
        return result.exitCode == 0 ? outputOr(result, pop ? "Stash applied and removed" : "Stash applied") : gitError(result);
    }

    private String dropGitStash(GitRepositoryModel.Stash stash, AsyncJobService.JobToken token) {
        File root = resolveGitRoot();
        if (root == null) return "Not inside a Git repository";
        if (stash == null || !stash.reference().matches("stash@\\{\\d+}")) return "Select a valid stash";
        CommandResult result = runCommand(root, List.of("git", "stash", "drop", stash.reference()), token);
        return result.exitCode == 0 ? outputOr(result, "Stash dropped") : gitError(result);
    }

    private static String outputOr(CommandResult result, String fallback) {
        String output = result.stdout.strip();
        return output.isBlank() ? fallback : output;
    }

    private GitHistoryModel.Snapshot loadGitHistory(AsyncJobService.JobToken token) {
        File root = resolveGitRoot();
        if (root == null) return GitHistoryModel.unavailable("Not inside a Git repository.");
        CommandResult history = runCommand(root,
            List.of("git", "log", "-z", "--format=%H%x00%D%x00%s%x00%an%x00%aI", "-n", "100"), token);
        if (token.isCancelled()) return GitHistoryModel.unavailable("Git history refresh cancelled.");
        CommandResult remotes = runCommand(root, List.of("git", "remote"), token);
        return GitHistoryModel.fromCommands(root, history, remotes);
    }

    private GitHistoryModel.RemoteResult runGitRemoteOperation(GitHistoryModel.RemoteAction action, AsyncJobService.JobToken token) {
        if (action == null) return new GitHistoryModel.RemoteResult(null, false, "No Git remote action was selected.");
        File root = resolveGitRoot();
        if (root == null) return new GitHistoryModel.RemoteResult(action, false, "Not inside a Git repository.");
        CommandResult result = runCommand(root, action.command(), token);
        if (token.isCancelled()) return new GitHistoryModel.RemoteResult(action, false, action.label() + " cancelled.");
        if (result.exitCode != 0) return new GitHistoryModel.RemoteResult(action, false, gitError(result));
        String output = result.stdout.strip();
        return new GitHistoryModel.RemoteResult(action, true, output.isEmpty() ? action.label() + " completed." : output);
    }

    private GitConflictResolutionDialog.Load loadGitConflicts() {
        File root = resolveGitRoot();
        if (root == null) return new GitConflictResolutionDialog.Load(List.of(), "Not inside a Git repository.");
        CommandResult status = runCommand(root, List.of("git", "diff", "--name-only", "--diff-filter=U", "-z"));
        if (status.exitCode != 0) return new GitConflictResolutionDialog.Load(List.of(), gitError(status));
        if (outputTruncated(status)) {
            return new GitConflictResolutionDialog.Load(List.of(), "Git conflict list was truncated; increase process.output.max.bytes before resolving.");
        }
        List<GitConflictResolutionModel.Conflict> conflicts = new ArrayList<>();
        for (String path : nulSeparated(status.stdout)) {
            if (path.isEmpty()) continue;
            Path target = conflictPath(root, path);
            if (target == null || !Files.isRegularFile(target)) {
                return new GitConflictResolutionDialog.Load(List.of(), "Conflict file is missing or escapes the repository: " + path);
            }
            try {
                String content = Files.readString(target, StandardCharsets.UTF_8);
                GitConflictResolutionModel.Side base = readConflictSide(root, path, 1);
                GitConflictResolutionModel.Side ours = readConflictSide(root, path, 2);
                GitConflictResolutionModel.Side theirs = readConflictSide(root, path, 3);
                if (base == null || ours == null || theirs == null) {
                    return new GitConflictResolutionDialog.Load(List.of(), "Git conflict stage output was truncated; increase process.output.max.bytes before resolving.");
                }
                conflicts.add(new GitConflictResolutionModel.Conflict(path, workbenchDigest(target), content, base, ours, theirs));
            } catch (IOException e) {
                return new GitConflictResolutionDialog.Load(List.of(), "Could not load conflict file " + path + ": " + e.getMessage());
            }
        }
        String detail = conflicts.isEmpty() ? "No unresolved Git conflicts." : conflicts.size() + " unresolved conflict" + (conflicts.size() == 1 ? "" : "s") + ".";
        return new GitConflictResolutionDialog.Load(conflicts, detail);
    }

    private GitConflictResolutionModel.Side readConflictSide(File root, String path, int stage) {
        CommandResult result = runCommand(root, List.of("git", "show", ":" + stage + ":" + path));
        if (outputTruncated(result)) return null;
        return result.exitCode == 0 ? new GitConflictResolutionModel.Side(true, result.stdout) : new GitConflictResolutionModel.Side(false, "");
    }

    private boolean outputTruncated(CommandResult result) {
        return result != null && result.stdout.endsWith("\n[shed: output truncated]");
    }

    private String applyGitConflictResolution(GitConflictResolutionModel.Conflict conflict, String resolution) {
        String validation = GitConflictResolutionModel.validateResult(resolution);
        if (validation != null) return validation;
        File root = resolveGitRoot();
        if (root == null || conflict == null) return "Git conflict resolution unavailable: repository or conflict is missing.";
        Path target = conflictPath(root, conflict.path());
        if (target == null || !Files.isRegularFile(target)) return "Git conflict resolution unavailable: conflict file is missing or unsafe.";
        if (!Objects.equals(conflict.sourceDigest(), workbenchDigest(target))) return "Conflict source changed; refresh before applying a resolution.";
        FileBuffer buffer = editor.findBufferByPath(target.toFile());
        if (buffer != null && buffer.isModified()) return "Conflict source has unsaved editor changes; save or discard them before applying a resolution.";
        try {
            AtomicFileWriter.write(target, resolution.getBytes(StandardCharsets.UTF_8));
            if (buffer != null) {
                buffer.load(editor.configManager);
                if (buffer == editor.getCurrentBuffer()) editor.loadBufferIntoEditor(buffer);
            }
            editor.refreshGitGutter();
            return editor.configManager.getGitStagingEnabled()
                ? "Resolution written to working tree; explicitly stage it with :git add " + conflict.path()
                : "Resolution written to working tree; staging is disabled by git.staging.enabled=false";
        } catch (IOException e) {
            return "Conflict resolution apply failed; working file was retained: " + e.getMessage();
        }
    }

    private Path conflictPath(File root, String path) {
        try {
            Path rootPath = root.toPath().toRealPath();
            Path target = rootPath.resolve(path).normalize();
            return target.startsWith(rootPath) ? target : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private List<String> nulSeparated(String value) {
        List<String> paths = new ArrayList<>();
        String source = value == null ? "" : value;
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\u0000') {
                paths.add(source.substring(start, index));
                start = index + 1;
            }
        }
        paths.add(source.substring(start));
        return paths;
    }

    GitChangesWorkbenchModel.Snapshot loadGitChangesWorkbench() {
        File root = resolveGitRoot();
        if (root == null) return GitChangesWorkbenchModel.unavailable("Not inside a Git repository.");
        CommandResult result = runCommand(root, List.of("git", "status", "--porcelain=v1", "-z", "--branch"));
        return GitChangesWorkbenchModel.fromStatus(root, result);
    }

    GitChangesWorkbenchModel.Diff loadGitChangesWorkbenchDiff(GitChangesWorkbenchModel.Snapshot snapshot,
        GitChangesWorkbenchModel.Change change) {
        if (!editor.configManager.getGitDiffsEnabled()) {
            return workbenchDiffFailure("Git diff navigation disabled by git.diffs.enabled=false");
        }
        if (snapshot == null || !snapshot.available() || change == null) {
            return workbenchDiffFailure("Git status is unavailable; refresh the workbench.");
        }
        Path target = workbenchPath(snapshot, change);
        if (target == null) return workbenchDiffFailure("Changed path escapes the current repository.");
        File root = new File(snapshot.root());
        if (gitHeadExists(root)) {
            CommandResult result = runCommand(root, List.of("git", "diff", "HEAD", "--no-ext-diff", "--unified=3", "--", change.path()));
            if (result.exitCode != 0) return workbenchDiffFailure(gitError(result));
            if (result.stdout.isBlank()) {
                return new GitChangesWorkbenchModel.Diff(GitChangesWorkbenchModel.State.UNAVAILABLE, "", List.of(),
                    "No textual Git diff is available for this file; untracked files can be opened directly.", workbenchDigest(target));
            }
            String content = "# Current changes from HEAD\n\n" + result.stdout.strip() + "\n";
            List<GitHunkNavigation.Hunk> hunks = GitHunkNavigation.parse(content);
            String detail = hunks.isEmpty() ? "Diff loaded; no navigable text hunks." : hunks.size() + " hunk" + (hunks.size() == 1 ? "" : "s") + " loaded.";
            return new GitChangesWorkbenchModel.Diff(GitChangesWorkbenchModel.State.READY, content, hunks, detail, workbenchDigest(target));
        }
        StringBuilder content = new StringBuilder();
        String failure = appendGitDiff(content, root, change.path(), change.indexStatus(), true);
        if (failure != null) return workbenchDiffFailure(failure);
        failure = appendGitDiff(content, root, change.path(), change.worktreeStatus(), false);
        if (failure != null) return workbenchDiffFailure(failure);
        if (content.isEmpty()) {
            return new GitChangesWorkbenchModel.Diff(GitChangesWorkbenchModel.State.UNAVAILABLE, "", List.of(),
                "No textual Git diff is available for this file; untracked files can be opened directly.", workbenchDigest(target));
        }
        List<GitHunkNavigation.Hunk> hunks = GitHunkNavigation.parse(content.toString());
        String detail = hunks.isEmpty() ? "Diff loaded; no navigable text hunks." : hunks.size() + " hunk" + (hunks.size() == 1 ? "" : "s") + " loaded.";
        boolean precise = change.indexStatus().isBlank() || change.worktreeStatus().isBlank();
        String digest = precise ? workbenchDigest(target) : null;
        if (!precise) detail += " Save/commit the initial repository state before hunk navigation.";
        return new GitChangesWorkbenchModel.Diff(GitChangesWorkbenchModel.State.READY, content.toString(), hunks, detail, digest);
    }

    private String appendGitDiff(StringBuilder content, File root, String path, String status, boolean cached) {
        if (status == null || status.isBlank() || "Untracked".equals(status)) return null;
        List<String> command = cached
            ? List.of("git", "diff", "--cached", "--no-ext-diff", "--unified=3", "--", path)
            : List.of("git", "diff", "--no-ext-diff", "--unified=3", "--", path);
        CommandResult result = runCommand(root, command);
        if (result.exitCode != 0) return gitError(result);
        if (!result.stdout.isBlank()) {
            if (!content.isEmpty()) content.append("\n\n");
            content.append(cached ? "# Staged changes\n\n" : "# Working tree changes\n\n");
            content.append(result.stdout.strip()).append("\n");
        }
        return null;
    }

    private String openGitChangesWorkbenchTarget(GitChangesWorkbenchModel.Snapshot snapshot, GitChangesWorkbenchModel.Change change,
        GitChangesWorkbenchModel.Diff diff, GitHunkNavigation.Hunk hunk) {
        if (snapshot == null || change == null) return "Select a changed file first.";
        Path target = workbenchPath(snapshot, change);
        if (target == null) return "Changed path escapes the current repository.";
        if (!Files.isRegularFile(target)) return "Diff navigation unavailable: file is missing or no longer regular; refresh Git status.";
        try {
            Path root = Path.of(snapshot.root()).toRealPath();
            if (!target.toRealPath().startsWith(root)) return "Diff navigation unavailable: file resolves outside the repository.";
        } catch (IOException e) {
            return "Diff navigation unavailable: " + e.getMessage();
        }
        if (hunk != null) {
            if (!editor.configManager.getGitDiffsEnabled()) {
                return "Diff navigation disabled by git.diffs.enabled=false";
            }
            if (diff == null || !diff.available() || diff.sourceDigest() == null) {
                return "Diff navigation unavailable: load a current text diff first.";
            }
            FileBuffer buffer = editor.findBufferByPath(target.toFile());
            if (buffer != null && buffer.isModified()) return "Diff navigation unavailable: file has unsaved edits; save and refresh the diff.";
            String currentDigest = workbenchDigest(target);
            if (!Objects.equals(diff.sourceDigest(), currentDigest)) return "Diff navigation stale: file changed; refresh the diff.";
        }
        try {
            editor.openFile(target.toFile());
            return hunk == null ? "Opened " + target : editor.gotoLine(hunk.targetLine());
        } catch (IOException e) {
            return "Diff navigation unavailable: " + e.getMessage();
        }
    }

    private Path workbenchPath(GitChangesWorkbenchModel.Snapshot snapshot, GitChangesWorkbenchModel.Change change) {
        try {
            Path root = Path.of(snapshot.root()).toAbsolutePath().normalize();
            Path target = root.resolve(change.path()).normalize();
            return target.startsWith(root) ? target : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private GitChangesWorkbenchModel.Diff workbenchDiffFailure(String detail) {
        return new GitChangesWorkbenchModel.Diff(GitChangesWorkbenchModel.State.ERROR, "", List.of(), detail, null);
    }

    private String workbenchDigest(Path target) {
        if (target == null || !Files.isRegularFile(target)) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(target)) {
                byte[] bytes = new byte[8192];
                int read;
                while ((read = input.read(bytes)) >= 0) digest.update(bytes, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }


    File resolveGitRoot() {
        CommandResult result = runCommand(gitWorkingDirectory(), List.of("git", "rev-parse", "--show-toplevel"));
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

    private File gitWorkingDirectory() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer != null && buffer.hasFilePath()) {
            File parent = new File(buffer.getFilePath()).getParentFile();
            if (parent != null) return parent;
        }
        if (editor.treeRoot != null) {
            if (editor.treeRoot.isDirectory()) return editor.treeRoot;
            File parent = editor.treeRoot.getParentFile();
            if (parent != null) return parent;
        }
        return new File(".");
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
        editor.clearGitBlameCache();
        String filePath = buffer.getFilePath();
        File workingDirectory = gitWorkingDirectory().getAbsoluteFile();
        if (!workingDirectory.equals(cachedGitWorkingDirectory)) {
            cachedGitWorkingDirectory = workingDirectory;
            cachedGitRoot = null;
            cachedGitRootResolved = false;
        }
        if (!cachedGitRootResolved) {
            cachedGitRoot = resolveGitRoot();
            cachedGitRootResolved = true;
        }
        File gitRoot = cachedGitRoot;
        if (gitRoot == null) return;
        if (gitGutterJobId >= 0) {
            editor.asyncJobService.cancel(gitGutterJobId);
        }
        long generation = ++gitGutterGeneration;
        gitGutterJobId = editor.asyncJobService.submit("Git gutter", token -> {
            long started = System.nanoTime();
            CommandResult result = runCommand(gitRoot, List.of("git", "diff", "HEAD", "--unified=0", "--", filePath));
            Set<Integer> added = new HashSet<>();
            Set<Integer> modified = new HashSet<>();
            Set<Integer> deletedAfter = new HashSet<>();
            if (result.exitCode == 0 && result.stdout != null) {
                parseUnifiedDiffForGutter(result.stdout, added, modified, deletedAfter);
            }
            if (editor.perfService != null) {
                editor.perfService.recordDuration("git.gutter", started, filePath);
            }
            return new GitGutterUpdate(filePath, added, modified, deletedAfter);
        }, (snapshot, update, error) -> {
            if (generation != gitGutterGeneration || error != null || update == null) {
                return;
            }
            gitGutterJobId = -1;
            FileBuffer current = editor.getCurrentBuffer();
            EditorPane pane = editor.getActivePane();
            if (current != null && update.filePath().equals(current.getFilePath()) && pane != null) {
                pane.getLineNumberPanel().updateGitDiffMarkers(update.added(), update.modified(), update.deletedAfter());
            }
        });
    }

    private record GitGutterUpdate(String filePath, Set<Integer> added, Set<Integer> modified, Set<Integer> deletedAfter) {
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
        if (editor.configManager.getGitHistoryEnabled() && editor.configManager.getGitPanelPresentationEnabled()) {
            int historyCount = count;
            GitGraphDialog.showFor(editor, token -> loadGitGraph(gitRoot, historyCount, token));
            return "Git graph opened";
        }
        return showAsciiGitLog(gitRoot, count);
    }

    private GitGraphModel.Snapshot loadGitGraph(File root, int count, AsyncJobService.JobToken token) {
        if (root == null) return GitGraphModel.unavailable("Not inside a Git repository.");
        CommandResult result = runCommand(root, List.of("git", "log", "--topo-order", "--date=relative", "-z",
            "--format=%H%x00%P%x00%D%x00%s%x00%an%x00%ad", "-n", String.valueOf(count)), token);
        if (token.isCancelled()) return GitGraphModel.unavailable("Git graph refresh cancelled.");
        return GitGraphModel.fromCommand(root, result);
    }

    private String showAsciiGitLog(File gitRoot, int count) {
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
        showInteractiveGitBuffer("[git log]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n", gitRoot, InteractiveGitView.LOG);
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
        showInteractiveGitBuffer("[git branch]", "repo: " + gitRoot.getAbsolutePath() + "\n\n" + body + "\n", gitRoot, InteractiveGitView.BRANCHES);
        return "Showing git branches";
    }

    private void showInteractiveGitBuffer(String title, String content, File gitRoot, InteractiveGitView view) {
        interactiveGitBuffer = null;
        interactiveGitRoot = null;
        interactiveGitView = null;
        editor.showScratchBuffer(title, content);
        interactiveGitBuffer = editor.getCurrentBuffer();
        interactiveGitRoot = gitRoot;
        interactiveGitView = view;
    }

    boolean isInteractiveGitBufferActive() {
        return interactiveGitBuffer != null && interactiveGitBuffer == editor.getCurrentBuffer()
            && interactiveGitRoot != null && interactiveGitView != null;
    }

    String openInteractiveGitSelection() {
        if (!isInteractiveGitBufferActive()) {
            return "No interactive Git selection";
        }
        String line = lineAtCaret();
        if (interactiveGitView == InteractiveGitView.BRANCHES) {
            String branch = localBranchName(line);
            if (branch == null) {
                return "Select a local branch";
            }
            String result = runGitSwitch(interactiveGitRoot, branch);
            if (!"Switch complete".equals(result)) {
                return result;
            }
            editor.gitBranch = resolveBranchName(interactiveGitRoot);
            refreshGitGutter();
            return showGitBranches(interactiveGitRoot);
        }
        String hash = commitHash(line);
        if (hash == null) {
            return "Select a commit";
        }
        return showGitCommitDetails(interactiveGitRoot, hash);
    }

    String openGitLogSelectionAtCaret() {
        return isInteractiveGitBufferActive() && interactiveGitView == InteractiveGitView.LOG
            ? openInteractiveGitSelection() : "";
    }

    private String lineAtCaret() {
        try {
            int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
            int start = editor.writingArea.getLineStartOffset(line);
            int end = editor.writingArea.getLineEndOffset(line);
            return editor.writingArea.getText(start, end - start);
        } catch (javax.swing.text.BadLocationException ignored) {
            return "";
        }
    }

    static String localBranchName(String line) {
        String value = line == null ? "" : line.strip();
        if (value.startsWith("* ")) {
            value = value.substring(2).stripLeading();
        }
        if (value.isEmpty() || value.startsWith("remotes/")) {
            return null;
        }
        int separator = value.indexOf(' ');
        if (separator <= 0) {
            return null;
        }
        String branch = value.substring(0, separator);
        return branch.startsWith("(") ? null : branch;
    }

    static String commitHash(String line) {
        Matcher matcher = Pattern.compile("\\b[0-9a-f]{7,64}\\b").matcher(line == null ? "" : line);
        return matcher.find() ? matcher.group() : null;
    }

    private String showGitCommitDetails(File gitRoot, String hash) {
        CommandResult result = runCommand(gitRoot, List.of("git", "show", "--decorate", "--format=fuller", "--stat", hash));
        if (result.exitCode != 0) {
            return gitError(result);
        }
        String body = result.stdout.strip();
        editor.showScratchBuffer("[git commit " + hash + "]", body.isEmpty() ? "(no commit details)\n" : body + "\n");
        interactiveGitBuffer = null;
        interactiveGitRoot = null;
        interactiveGitView = null;
        return "Showing commit " + hash;
    }

    private String resolveBranchName(File gitRoot) {
        CommandResult result = runCommand(gitRoot, List.of("git", "rev-parse", "--abbrev-ref", "HEAD"));
        return result.exitCode == 0 ? result.stdout.strip() : "";
    }


    String runGitAdd(File gitRoot, String args) {
        if (!editor.configManager.getGitStagingEnabled()) {
            return "Git staging disabled by git.staging.enabled=false";
        }
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
        if (!editor.configManager.getGitStagingEnabled()) {
            return "Git staging disabled by git.staging.enabled=false";
        }
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

    String runGitRestoreWorktree(File gitRoot, String args) {
        List<String> pathSpecs = splitWhitespaceArgs(args);
        if (pathSpecs.isEmpty()) return "Usage: Git restore requires a path";
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("restore");
        command.add("--worktree");
        command.add("--");
        command.addAll(pathSpecs);
        CommandResult result = runCommand(gitRoot, command);
        if (result.exitCode != 0) return gitError(result);
        editor.refreshGitGutter();
        return "git restore complete";
    }

    List<String> localBranchesForPanel(File gitRoot) {
        if (gitRoot == null) return List.of();
        CommandResult result = runCommand(gitRoot, List.of("git", "for-each-ref", "--format=%(refname:short)", "refs/heads"));
        if (result.exitCode != 0) return List.of();
        return result.stdout.lines().map(String::strip).filter(value -> !value.isEmpty()).sorted().toList();
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
        if (editor.toolWindowHost == null || !editor.toolWindowHost.isSelected(ToolWindowHost.Tab.GIT)) {
            editor.showScratchBuffer("[git commit]", body + "\n");
        }
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
        if (editor.toolWindowHost == null || !editor.toolWindowHost.isSelected(ToolWindowHost.Tab.GIT)) {
            editor.showScratchBuffer("[git amend]", body + "\n");
        }
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
        if (!output.isEmpty() && (editor.toolWindowHost == null || !editor.toolWindowHost.isSelected(ToolWindowHost.Tab.GIT))) {
            editor.showScratchBuffer("[git checkout]", output + "\n");
        }
        editor.gitBranch = resolveBranchName(gitRoot);
        editor.clearGitBlameCache();
        editor.updateStatusBar();
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
        if (!output.isEmpty() && (editor.toolWindowHost == null || !editor.toolWindowHost.isSelected(ToolWindowHost.Tab.GIT))) {
            editor.showScratchBuffer("[git switch]", output + "\n");
        }
        editor.gitBranch = resolveBranchName(gitRoot);
        editor.clearGitBlameCache();
        editor.updateStatusBar();
        return "Switch complete";
    }


    String runGitHunkCommand(File gitRoot, String argument) {
        List<String> args = splitWhitespaceArgs(argument);
        if (args.isEmpty()) {
            return "Usage: :git hunk stage|unstage|revert [line]";
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if (("stage".equals(action) || "unstage".equals(action)) && !editor.configManager.getGitStagingEnabled()) {
            return "Git staging disabled by git.staging.enabled=false";
        }
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
                + ":git workbench        Open graphical read-only changes/diff/hunk workbench\n"
                + ":git conflict         Open graphical conflict-resolution document\n"
                + ":git history          Open graphical local history and explicit remote controls\n"
                + ":git worktrees|stash  Open graphical worktree and stash controls\n"
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
        return runCommand(workingDirectory, command, null);
    }

    CommandResult runCommand(File workingDirectory, List<String> command, AsyncJobService.JobToken token) {
        return editor.runExternalCommand(
            command,
            workingDirectory,
            null,
            token,
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
