package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

final class GitChangesToolPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final ToolWindowHost host;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final JLabel state = new JLabel("Refresh to load changes.");
    private final DefaultTableModel changes = new DefaultTableModel(new Object[] {"Area", "State", "Path"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable changeTable = new JTable(changes);
    private final ChangeDiffView diff = new ChangeDiffView();
    private final ChangePulse pulse=new ChangePulse(changeTable);
    private boolean observedDiffDirty;
    private final JTextField commit = new JTextField();
    private final List<GitChangesWorkbenchModel.Change> visibleChanges = new ArrayList<>();
    private final JPanel pages=new JPanel(new java.awt.CardLayout());
    private final DefaultTableModel historyRows=new DefaultTableModel(new Object[]{"Commit","Message","Author"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable historyTable=new JTable(historyRows);
    private List<GitHistoryModel.Commit> history=List.of();
    private GitHistoryModel.Snapshot historySnapshot;
    private boolean historyVisible;
    private int historyLimit=100;
    private long historyGeneration;
    private int historyJob=-1;
    private long snapshotWorkspace=-1,historyWorkspace=-1;
    private GitChangesWorkbenchModel.Snapshot snapshot;
    private GitChangesWorkbenchModel.Diff selectedDiff;
    private long statusGeneration;
    private long diffGeneration;
    private final javax.swing.Timer autoRefreshTimer;
    private boolean statusLoading;
    private boolean closed;

    GitChangesToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        WorkbenchStyle.emptyText(changeTable, "No changes to display");
        this.host = host;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(header(), BorderLayout.NORTH);
        var changePage=new JPanel(new BorderLayout(6,6));changePage.add(content());changePage.add(actions(),BorderLayout.SOUTH);pages.add(changePage,"changes");
        var historyPage=new JPanel(new BorderLayout(4,4));historyTable.setName("git.history");historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);historyTable.setDefaultRenderer(Object.class,new javax.swing.table.DefaultTableCellRenderer(){public java.awt.Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){var label=(JLabel)super.getTableCellRendererComponent(t,value,selected,focus,row,column);label.putClientProperty("html.disable",true);return label;}});historyPage.add(new JScrollPane(historyTable));var openCommit=button("Open commit comparison",this::openCommit);var historyActions=new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEADING,4,2)));historyActions.add(openCommit);historyActions.add(button("Graph…",this::openGraph));historyPage.add(historyActions,BorderLayout.SOUTH);pages.add(historyPage,"history");panel.add(pages,BorderLayout.CENTER);
        AccessibilitySupport.describe(historyTable,"Git history","Local commits. Select a commit and choose Open commit comparison.");
        changeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        changeTable.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) loadDiff(); });
        diff.setName("git.changeDiff");changeTable.setName("git.changeFiles");
        changeTable.setDefaultRenderer(Object.class,new javax.swing.table.DefaultTableCellRenderer(){
            @Override public java.awt.Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focused,int row,int column){
                var label=(JLabel)super.getTableCellRendererComponent(table,value,selected,focused,row,column);label.putClientProperty("html.disable",true);
                if(row<visibleChanges.size()){
                    var change=visibleChanges.get(row);String status=change.indexStatus()+" "+change.worktreeStatus();String kind=status.contains("Deleted")?"D":status.contains("Added")||status.contains("Untracked")?"A":"M";
                    var accent=ChangePulse.color(kind);label.setBackground(ChangePulse.tint(label.getBackground(),accent,pulse.strength(change.path())));
                    if(!selected&&accent!=null)label.setForeground(accent);label.setForeground(AccessibilitySupport.readableText(label.getForeground(),label.getBackground()));
                }return label;
            }
        });
        AccessibilitySupport.describe(changeTable, "Git changes", "Staged, unstaged, and untracked repository files.");
        autoRefreshTimer = new javax.swing.Timer(editor.configManager.getGitAutoRefreshIntervalMs(), event -> refreshIfChanged());
        autoRefreshTimer.start();
    }

    @Override public JPanel component() { return panel; }
    @Override public void focus() { changeTable.requestFocusInWindow(); }

    @Override public void close() {
        closed = true; pulse.close();autoRefreshTimer.stop(); statusGeneration++; diffGeneration++;historyGeneration++;if(historyJob>=0)editor.asyncJobService.cancel(historyJob);
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JPanel labels = new JPanel(new java.awt.GridLayout(2, 1));
        labels.add(repository); labels.add(state);
        panel.add(labels, BorderLayout.CENTER);
        var actions=new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEADING,4,2)));actions.add(button("Changes",()->{historyVisible=false;((java.awt.CardLayout)pages.getLayout()).show(pages,"changes");refresh();}));actions.add(button("History",this::showHistory));actions.add(button("Refresh",this::refresh));actions.add(button("Remote actions…",()->editor.treeGitController.showGitRemoteActionsDialog()));panel.add(actions,BorderLayout.SOUTH);
        return panel;
    }

    private java.awt.Component content() {
        JScrollPane files = new JScrollPane(changeTable);
        files.setBorder(BorderFactory.createTitledBorder("Changes"));
        JScrollPane patch = new JScrollPane(diff);
        patch.setBorder(BorderFactory.createTitledBorder("Diff"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, files, patch);
        split.setResizeWeight(0.55);
        return split;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JPanel fileActions = new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEFT, 4, 2)));
        fileActions.add(button("Open", this::open));
        fileActions.add(button("Open Diff", this::openComparison));
        fileActions.add(button("Stage", this::stage));
        fileActions.add(button("Unstage", this::unstage));
        fileActions.add(button("Revert", this::revert));
        fileActions.add(button("Branch…", this::switchBranch));
        fileActions.add(button("Worktrees…", () -> editor.treeGitController.showGitRepositoryToolsDocument()));
        fileActions.add(button("Stashes…", () -> editor.treeGitController.showGitRepositoryToolsDocument()));
        panel.add(fileActions, BorderLayout.NORTH);
        JPanel commitActions = new JPanel(new BorderLayout(4, 0));
        JLabel commitLabel = new JLabel("Commit message"); commitLabel.setLabelFor(commit);
        commitActions.add(commitLabel, BorderLayout.WEST);
        commitActions.add(commit, BorderLayout.CENTER);
        AccessibilitySupport.describe(commit, "Commit message", "Write a message, then choose Commit explicitly. Opening Git does not commit changes.");
        commitActions.add(button("Commit", this::commit), BorderLayout.EAST);
        panel.add(commitActions, BorderLayout.CENTER);
        return panel;
    }

    @Override public void refresh() {
        if(historyVisible){loadHistory();return;}
        requestRefresh(false);
    }

    void observeExternalChanges(java.nio.file.Path root,java.util.Set<String> recent){
        if(closed||snapshot==null||snapshot.root().isBlank()||recent.isEmpty())return;
        var repository=java.nio.file.Path.of(snapshot.root()).toAbsolutePath().normalize();var paths=new java.util.HashSet<String>();
        for(String name:recent){var path=root.resolve(name).normalize();if(path.startsWith(repository))paths.add(repository.relativize(path).toString());}
        pulse.flash(paths);var selected=selected();observedDiffDirty|=selected!=null&&paths.contains(selected.path());
        if(panel.isShowing()&&host.isSelected(ToolWindowHost.Tab.GIT))requestRefresh(true);
    }
    @Override public void refreshAppearance(){diff.refreshAppearance(editor.resolveEditorFont());changeTable.setFont(editor.resolveUiFont());}
    private void refreshIfChanged() {
        int interval = editor.configManager.getGitAutoRefreshIntervalMs();
        if (autoRefreshTimer.getDelay() != interval) {
            autoRefreshTimer.setDelay(interval);
            autoRefreshTimer.setInitialDelay(interval);
        }
        if(historyVisible)return;
        if (!editor.configManager.getGitAutoRefreshEnabled() || !panel.isShowing() || !host.isSelected(ToolWindowHost.Tab.GIT)) return;
        requestRefresh(true);
    }

    private void requestRefresh(boolean automatic) {
        if (closed || statusLoading) return;
        statusLoading = true;
        long request = ++statusGeneration,workspace=editor.workspaceRoots.generation();
        if (!automatic) WorkbenchStyle.message(state, "Refreshing Git status…", null);
        editor.asyncJobService.submit("Git changes refresh", token -> editor.treeGitController.loadGitChangesWorkbench(), (job, result, error) -> {
            if (closed || request != statusGeneration) return;
            statusLoading = false;
            if(workspace!=editor.workspaceRoots.generation()){state.setText("Project changed; refresh Git.");return;}
            snapshotWorkspace=workspace;
            if (!panel.isDisplayable()) return;
            if (error != null || result == null) {
                snapshot = null; diffGeneration++; selectedDiff = null; visibleChanges.clear(); changes.setRowCount(0);
                diff.showPatch("",editor.resolveEditorFont());
                WorkbenchStyle.message(state, "Git refresh failed: " + (error == null ? job.getErrorMessage() : error.getMessage()), "error");
                return;
            }
            if (automatic && Objects.equals(snapshot, result)) {if(observedDiffDirty){observedDiffDirty=false;loadDiff();}return;}
            observedDiffDirty=false;
            render(result);
        });
    }

    private void render(GitChangesWorkbenchModel.Snapshot result) {
        GitChangesWorkbenchModel.Change previous = selected();
        String selectedPath = previous == null ? null : previous.path();
        snapshot = result; diffGeneration++; selectedDiff = null; visibleChanges.clear(); changes.setRowCount(0); diff.showPatch("",editor.resolveEditorFont());
        repository.setText(result.root().isBlank() ? "Repository: unavailable" : "Repository: " + result.root());
        repository.setToolTipText(result.root());
        WorkbenchStyle.message(state, result.detail() + (result.branch().isBlank() ? "" : "  Branch: " + result.branch()), result.state() == GitChangesWorkbenchModel.State.ERROR ? "error" : null);
        WorkbenchStyle.emptyText(changeTable, result.available() ? "Working tree is clean" : result.detail());
        for (GitChangesWorkbenchModel.Change change : result.changes()) {
            if (!change.indexStatus().isBlank()) addChange("Staged", change.indexStatus(), change);
            if (!change.worktreeStatus().isBlank()) addChange("Changes", change.worktreeStatus(), change);
            if (change.indexStatus().isBlank() && change.worktreeStatus().isBlank()) addChange("Other", "", change);
        }
        if (selectedPath != null) {
            for (int row = 0; row < visibleChanges.size(); row++) {
                if (selectedPath.equals(visibleChanges.get(row).path())) {
                    changeTable.setRowSelectionInterval(row, row);
                    break;
                }
            }
        }
    }

    private void addChange(String area, String status, GitChangesWorkbenchModel.Change change) {
        visibleChanges.add(change);
        changes.addRow(new Object[] {area, status, change.displayPath()});
    }

    private void loadDiff() {
        GitChangesWorkbenchModel.Change change = selected();
        long request = ++diffGeneration;
        GitChangesWorkbenchModel.Snapshot captured = snapshot;
        long workspace=snapshotWorkspace;
        selectedDiff=null;
        if (captured == null || change == null || snapshotWorkspace!=editor.workspaceRoots.generation()) return;
        WorkbenchStyle.message(state, "Loading diff…", null);
        editor.asyncJobService.submit("Git diff: " + change.path(), token -> editor.treeGitController.loadGitChangesWorkbenchDiff(captured, change),
            (job, result, error) -> {
                if (closed || request != diffGeneration || snapshot != captured || workspace!=editor.workspaceRoots.generation() || !panel.isDisplayable()) return;
                if (error != null || result == null) { WorkbenchStyle.message(state, "Diff failed: " + (error == null ? job.getErrorMessage() : error.getMessage()), "error"); return; }
                selectedDiff = result; diff.showPatch(result.content(),editor.resolveEditorFont()); WorkbenchStyle.message(state, result.detail(), result.available() ? null : "error");
            });
    }

    void showHistory(int count){historyLimit=Math.max(1,Math.min(200,count));showHistory();}
    void showHistory(){historyVisible=true;((java.awt.CardLayout)pages.getLayout()).show(pages,"history");loadHistory();}
    private void loadHistory(){
        if(closed)return;long request=++historyGeneration,workspace=editor.workspaceRoots.generation();var root=editor.treeRoot;if(historyJob>=0)editor.asyncJobService.cancel(historyJob);state.setText("Loading local history…");
        historyJob=editor.asyncJobService.submit("Git sidebar history",token->editor.treeGitController.loadGitHistory(token,historyLimit),(job,result,error)->{
            if(closed||request!=historyGeneration)return;historyJob=-1;if(workspace!=editor.workspaceRoots.generation()||!Objects.equals(root,editor.treeRoot)){state.setText("Project changed; refresh history.");return;}historyWorkspace=workspace;historyRows.setRowCount(0);historySnapshot=result;history=result==null?List.of():result.commits();
            for(var entry:history)historyRows.addRow(new Object[]{entry.hash().substring(0,Math.min(12,entry.hash().length())),entry.subject(),entry.author()});state.setText(error!=null?"History unavailable: "+error.getMessage():result==null?"History unavailable":result.detail());
        });
    }
    static boolean usableHistory(GitHistoryModel.Snapshot snapshot,long expectedWorkspace,long currentWorkspace){return snapshot!=null&&snapshot.available()&&!snapshot.root().isBlank()&&expectedWorkspace==currentWorkspace;}
    private void openGraph(){if(usableHistory(historySnapshot,historyWorkspace,editor.workspaceRoots.generation()))editor.treeGitController.showGitGraph(new File(historySnapshot.root()),Integer.toString(historyLimit));else state.setText("Refresh available history for this project before opening its graph.");}
    private void openCommit(){
        int row=historyTable.getSelectedRow();if(row<0||row>=history.size()||!usableHistory(historySnapshot,historyWorkspace,editor.workspaceRoots.generation()))return;var entry=history.get(row);var root=historySnapshot.root();var project=editor.treeRoot;var pane=editor.getActivePane();var source=editor.getCurrentBuffer();long revision=source==null?-1:source.documentRevision(),input=editor.inputController.lastKeyPressNanos();long request=++historyGeneration,workspace=editor.workspaceRoots.generation();
        if(historyJob>=0)editor.asyncJobService.cancel(historyJob);
        historyJob=editor.asyncJobService.submit("Git commit comparison",token->editor.treeGitController.loadGitCommitComparison(new File(root),entry.hash(),token),(job,text,error)->{
            if(closed||request!=historyGeneration||workspace!=editor.workspaceRoots.generation()||!Objects.equals(project,editor.treeRoot))return;historyJob=-1;if(error!=null){state.setText("Commit comparison failed: "+error.getMessage());return;}if(editor.getActivePane()!=pane||editor.getCurrentBuffer()!=source||(source!=null&&source.documentRevision()!=revision)||editor.inputController.lastKeyPressNanos()!=input){state.setText("Editor context changed; select the commit again to open its comparison.");return;}editor.showComparison("Commit "+entry.hash().substring(0,Math.min(12,entry.hash().length())),text);
        });
    }
    private void openComparison(){var change=selected();if(change==null||selectedDiff==null||!selectedDiff.available()||snapshotWorkspace!=editor.workspaceRoots.generation()){state.setText("Select a file and wait for its diff.");return;}editor.showComparison("Diff · "+change.path(),selectedDiff.content());}

    private GitChangesWorkbenchModel.Change selected() {
        int row = changeTable.getSelectedRow();
        return row < 0 || row >= visibleChanges.size() ? null : visibleChanges.get(row);
    }

    private File root() { return snapshot == null || snapshotWorkspace!=editor.workspaceRoots.generation() || snapshot.root().isBlank() ? null : new File(snapshot.root()); }

    private void open() {
        GitChangesWorkbenchModel.Change change = selected(); File root = root();
        if (change == null || root == null) { message("Select a changed file."); return; }
        editor.openFileAsync(root.toPath().resolve(change.path()).toFile(),
            error -> message(error == null ? "Opened " + change.path() : "Open failed: " + error.getMessage()));
    }

    private void stage() { runForSelection("Stage", (root, change) -> editor.treeGitController.runGitAdd(root, change.path())); }
    private void unstage() { runForSelection("Unstage", (root, change) -> editor.treeGitController.runGitRestoreStaged(root, change.path())); }
    private void revert() {
        GitChangesWorkbenchModel.Change change = selected();
        if (change == null) { message("Select a changed file."); return; }
        if (JOptionPane.showConfirmDialog(panel, "Discard working-tree changes in '" + change.displayPath() + "'?", "Revert Changes",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
            runForSelection("Revert", (root, selected) -> editor.treeGitController.runGitRestoreWorktree(root, selected.path()));
        }
    }

    private void runForSelection(String label, GitAction action) {
        GitChangesWorkbenchModel.Change change = selected(); File root = root();
        if (change == null || root == null) { message("Select a changed file."); return; }
        message(action.run(root, change));
    }

    private void commit() {
        File root = root();
        if (root == null) { message("Repository is unavailable."); return; }
        message(editor.treeGitController.runGitCommit(root, commit.getText()));
        commit.setText("");
    }

    private void switchBranch() {
        File root = root();
        if (root == null) { message("Repository is unavailable."); return; }
        List<String> branches = editor.treeGitController.localBranchesForPanel(root);
        if (branches.isEmpty()) { message("No local branches."); return; }
        JComboBox<String> picker = new JComboBox<>(branches.toArray(String[]::new));
        if (JOptionPane.showConfirmDialog(panel, picker, "Switch Branch", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            message(editor.treeGitController.runGitSwitch(root, String.valueOf(picker.getSelectedItem())));
        }
    }

    private void message(String result) { editor.showMessage(result); refresh(); }
    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button; }
    @FunctionalInterface private interface GitAction { String run(File root, GitChangesWorkbenchModel.Change change); }
}
