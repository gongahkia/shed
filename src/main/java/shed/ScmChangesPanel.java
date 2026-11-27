package shed;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import static shed.ScmWorkbenchModel.*;
import shed.ScmWorkbenchModel.Action;

/** Shared Mercurial/Subversion presentation; Git retains its existing controller and controls. */
final class ScmChangesPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel=new JPanel(new BorderLayout(6,6)),pages=new JPanel(new CardLayout());
    private final JLabel repository=new JLabel("Open a working copy."),state=new JLabel("Select a provider and refresh.");
    static final class RowsModel extends DefaultTableModel {
        RowsModel(Object... columns){super(columns,0);}
        @Override public boolean isCellEditable(int row,int column){return false;}
        void replaceRows(List<Object[]> rows){
            if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("SCM rows belong to the EDT");
            var replacement=new Vector<Vector>();
            for(var row:rows){if(row.length!=getColumnCount())throw new IllegalArgumentException("Wrong SCM column count");replacement.add(new Vector<>(Arrays.asList(row)));}
            dataVector=replacement;fireTableDataChanged();
        }
    }
    private final RowsModel files=new RowsModel("State","Path");
    private final RowsModel revisions=new RowsModel("Revision","Message","Author","Date");
    private final JTable fileTable=new JTable(files),historyTable=new JTable(revisions);
    private final ChangeDiffView patch=new ChangeDiffView();
    private final JTextArea message=new JTextArea(2,20);
    private final JButton cancel=new JButton("Cancel"),refresh=new JButton("Refresh"),changesButton=new JButton("Changes"),historyButton=new JButton("History"),commit=new JButton("Commit");
    private final JPanel fileActions=new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEADING,4,2))),repoActions=new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEADING,4,2)));
    private final javax.swing.Timer timer;
    private final List<JButton> actions=new ArrayList<>(),persistentActions=new ArrayList<>();
    private ScmWorkbenchService service=new ScmWorkbenchService();
    private Provider provider;
    private Path root,project;
    private long workspace,generation;
    private int job=-1;
    private boolean closed,busy,ready,historyVisible,prompting;
    private Snapshot snapshot;
    private String selectedPatch;
    private List<Revision> history=List.of();
    ScmChangesPanel(Texteditor editor){
        this.editor=editor;panel.setBorder(BorderFactory.createEmptyBorder(5,7,7,7));repository.putClientProperty("html.disable",true);state.putClientProperty("html.disable",true);
        var heading=new JPanel(new BorderLayout(4,4));heading.add(repository,BorderLayout.NORTH);heading.add(state);var buttons=new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEADING,4,2)));buttons.add(changesButton);buttons.add(historyButton);buttons.add(refresh);buttons.add(cancel);heading.add(buttons,BorderLayout.SOUTH);panel.add(heading,BorderLayout.NORTH);
        fileTable.setName("scm.files");historyTable.setName("scm.history");patch.setName("scm.diff");message.setName("scm.commit.message");message.setLineWrap(true);message.setWrapStyleWord(true);
        for(var table:List.of(fileTable,historyTable)){table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setDefaultRenderer(Object.class,plainCellRenderer());}
        AccessibilitySupport.describe(fileTable,"Source control changes","Working-copy files. Select a file to inspect its diff.");AccessibilitySupport.describe(historyTable,"Source control history","Select a revision to compare or update explicitly.");AccessibilitySupport.describe(message,"Commit message","Commits saved tracked or scheduled changes. Untracked files must be added explicitly.");
        var split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(fileTable),new JScrollPane(patch));split.setResizeWeight(.5);
        var changePage=new JPanel(new BorderLayout(4,4));changePage.add(split);var footer=new JPanel(new BorderLayout(4,4));footer.add(fileActions,BorderLayout.NORTH);var commitRow=new JPanel(new BorderLayout(4,4));commitRow.add(new JLabel("Commit message"),BorderLayout.NORTH);commitRow.add(new JScrollPane(message));commitRow.add(commit,BorderLayout.EAST);footer.add(commitRow);footer.add(repoActions,BorderLayout.SOUTH);changePage.add(footer,BorderLayout.SOUTH);pages.add(changePage,"changes");
        var historyPage=new JPanel(new BorderLayout(4,4));historyPage.add(new JScrollPane(historyTable));var historyActions=new JPanel(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEADING,4,2)));historyActions.add(button("Open revision comparison",this::openRevision));var update=button("Update to revision…",()->{var r=selectedRevision();if(r!=null)operation(Action.UPDATE,r.id());});historyActions.add(update);historyPage.add(historyActions,BorderLayout.SOUTH);pages.add(historyPage,"history");panel.add(pages);persistentActions.addAll(actions);
        refresh.addActionListener(e->{if(historyVisible)loadHistory();else refresh();});changesButton.addActionListener(e->{historyVisible=false;((CardLayout)pages.getLayout()).show(pages,"changes");refresh();});historyButton.addActionListener(e->loadHistory());commit.addActionListener(e->operation(Action.COMMIT,message.getText()));cancel.addActionListener(e->cancel());cancel.setEnabled(false);
        fileTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!busy)loadDiff();});
        timer=new javax.swing.Timer(2500,e->{if(!busy&&ready&&!prompting&&!historyVisible&&panel.isShowing())refresh();});
        panel.addHierarchyListener(e->{if((e.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0){if(!closed&&panel.isShowing())timer.start();else timer.stop();}});
        WorkbenchStyle.emptyText(fileTable,"Open a working copy to inspect changes.");
    }
    static javax.swing.table.DefaultTableCellRenderer plainCellRenderer(){return new javax.swing.table.DefaultTableCellRenderer(){{putClientProperty("html.disable",true);}};}
    static JComboBox<Branch> branchPicker(List<Branch> branches){var picker=new JComboBox<>(branches.toArray(Branch[]::new));picker.setRenderer(new DefaultListCellRenderer(){{putClientProperty("html.disable",true);}});return picker;}
    @Override public JPanel component(){return panel;}
    @Override public void focus(){fileTable.requestFocusInWindow();}
    @Override public void refreshAppearance(){patch.refreshAppearance(editor.resolveEditorFont());fileTable.setFont(editor.resolveUiFont());historyTable.setFont(editor.resolveUiFont());}
    boolean busy(){return busy;}
    void context(Provider provider,Path root,Path project,long workspace){
        boolean same=this.provider==provider&&Objects.equals(this.root,root)&&Objects.equals(this.project,project)&&this.workspace==workspace;
        if(same){if(root!=null)refresh();return;}
        cancel();this.provider=provider;this.root=root;this.project=project;this.workspace=workspace;snapshot=null;selectedPatch=null;files.setRowCount(0);revisions.setRowCount(0);history=List.of();message.setText("");patch.showPatch("",editor.resolveEditorFont());
        repository.setText(provider.label+" · "+(root==null?"working copy unavailable":root));repository.setToolTipText(root==null?null:root.toString());((CardLayout)pages.getLayout()).show(pages,"changes");buildActions();
        ready=false;historyVisible=false;controls(false);if(root==null){state.setText("Open a "+provider.label+" working copy, then Refresh.");return;}refresh();
    }
    private void buildActions(){
        fileActions.removeAll();repoActions.removeAll();actions.clear();actions.addAll(persistentActions);fileActions.add(button("Open",this::openFile));fileActions.add(button("Open Diff",this::openDiff));
        for(Action action:actions(provider)){
            if(action==Action.COMMIT)continue;
            boolean file=Set.of(Action.ADD,Action.FORGET,Action.REVERT,Action.REMOVE,Action.RENAME,Action.RESOLVE).contains(action);
            JButton button=button((provider==Provider.SUBVERSION&&action==Action.BRANCH?"Branch / tag":ScmWorkbenchService.actionLabel(action))+(file&&action!=Action.ADD&&action!=Action.FORGET?"…":""),()->choose(action));(file?fileActions:repoActions).add(button);
        }
        fileActions.revalidate();repoActions.revalidate();
    }
    private JButton button(String label,Runnable action){var b=new JButton(label);b.addActionListener(e->action.run());actions.add(b);return b;}
    private boolean current(){return !closed&&root!=null&&workspace==editor.workspaceRoots.generation()&&Objects.equals(project,editor.workspaceController.activeRoot());}
    private boolean sameContext(Provider p,Path r,long w,Path expectedProject){return current()&&provider==p&&Objects.equals(root,r)&&workspace==w&&Objects.equals(project,expectedProject);}
    private int confirm(Object content,String title,int type){boolean previous=prompting;prompting=true;try{return JOptionPane.showConfirmDialog(panel,content,title,JOptionPane.OK_CANCEL_OPTION,type);}finally{prompting=previous;}}
    private String input(String label){boolean previous=prompting;prompting=true;try{return JOptionPane.showInputDialog(panel,label);}finally{prompting=previous;}}
    private void controls(boolean busy){this.busy=busy;cancel.setEnabled(busy);refresh.setEnabled(!busy);changesButton.setEnabled(!busy);historyButton.setEnabled(!busy);commit.setEnabled(!busy&&ready);message.setEnabled(!busy);fileTable.setEnabled(!busy);historyTable.setEnabled(!busy);for(var b:actions)b.setEnabled(!busy&&ready);}
    private void cancel(){generation++;if(job>=0)editor.asyncJobService.cancel(job);job=-1;ready=false;controls(false);state.setText("Cancelled. Refresh to reconcile working-copy state.");}
    private <T> void work(String label,AsyncJobService.JobTask<T> task,java.util.function.Consumer<T> install){
        if(!current()||busy){if(!busy)state.setText("Project changed. Refresh Source Control.");return;}
        long request=++generation;controls(true);state.setText(label+"…");
        job=editor.asyncJobService.submit(label,task,(j,result,error)->{
            if(closed||request!=generation)return;job=-1;
            if(!current()){ready=false;controls(false);state.setText("Project changed; the result was not installed.");return;}
            if(error!=null||j.getStatus()!=AsyncJobService.Status.SUCCEEDED){ready=false;controls(false);state.setText("Operation failed: "+ScmWorkbenchService.cleanError(error==null?j.getErrorMessage():error.getMessage()));return;}
            controls(false);install.accept(result);
        });
    }
    @Override public void refresh(){
        if(!current()||busy)return;Provider p=provider;Path r=root;String selected=selectedChange()==null?null:selectedChange().path();
        work("Refreshing "+p.label+" status",token->service.status(p,r,token),value->{
            snapshot=value;selectedPatch=null;patch.showPatch("",editor.resolveEditorFont());
            files.replaceRows(value.changes().stream().map(c->new Object[]{c.state(),c.path()}).toList());ready=true;controls(false);repository.setText(p.label+" · "+r);repository.setToolTipText(r.toString());state.setText(value.changes().size()+" changes · "+value.location());WorkbenchStyle.emptyText(fileTable,"Working copy is clean");
            if(selected!=null)for(int i=0;i<value.changes().size();i++)if(selected.equals(value.changes().get(i).path())){fileTable.setRowSelectionInterval(i,i);break;}
        });
    }
    private Change selectedChange(){int row=fileTable.getSelectedRow();return snapshot==null||row<0||row>=snapshot.changes().size()?null:snapshot.changes().get(row);}
    private Revision selectedRevision(){int row=historyTable.getSelectedRow();return row<0||row>=history.size()?null:history.get(row);}
    private void loadDiff(){
        var change=selectedChange();if(change==null||!current()||!ready)return;Provider p=provider;Path r=root;selectedPatch=null;
        if(Set.of("Untracked","Ignored","External").contains(change.state())){patch.showPatch("No repository base for this entry. Open the file to inspect it; Add explicitly to schedule it.",editor.resolveEditorFont());return;}
        work("Loading file diff",token->service.diff(p,r,change.path(),token),text->{selectedPatch=text;patch.showPatch(text.isBlank()?"Contents unchanged (metadata-only change).":text,editor.resolveEditorFont());state.setText(change.state()+" · "+change.path());});
    }
    private void openFile(){var change=selectedChange();if(change==null||!current())return;try{Path file=target(root,change.path(),true);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)){state.setText("Select an existing regular file to open.");return;}editor.openFileAsync(file.toFile());}catch(Exception e){state.setText(e.getMessage());}}
    private void openDiff(){if(current()&&selectedPatch!=null&&selectedChange()!=null)editor.showComparison(provider.label+" · "+selectedChange().path(),selectedPatch);else state.setText("Select a versioned file and wait for its diff.");}
    private void loadHistory(){
        if(!current()||busy)return;Provider p=provider;Path r=root;long w=workspace;Path expectedProject=project;
        if(p==Provider.SUBVERSION&&confirm("Retrieve revision history from the Subversion repository server?","Load Subversion History",JOptionPane.QUESTION_MESSAGE)!=JOptionPane.OK_OPTION)return;
        if(!sameContext(p,r,w,expectedProject))return;
        work("Loading "+p.label+" history",token->service.history(p,r,token),rows->{history=rows;historyVisible=true;revisions.replaceRows(rows.stream().map(entry->new Object[]{entry.id(),entry.subject(),entry.author(),entry.date()}).toList());((CardLayout)pages.getLayout()).show(pages,"history");state.setText(rows.size()+" revisions. Comparisons do not edit source.");});
    }
    private void openRevision(){
        var revision=selectedRevision();if(revision==null||!current()||busy)return;
        Provider p=provider;Path r=root;long w=workspace;Path expectedProject=project;
        if(p==Provider.SUBVERSION&&confirm("Retrieve this revision's patch from the repository server?","Revision Comparison",JOptionPane.QUESTION_MESSAGE)!=JOptionPane.OK_OPTION)return;
        if(!sameContext(p,r,w,expectedProject))return;var pane=editor.getActivePane();var source=editor.getCurrentBuffer();long version=source==null?-1:source.documentRevision(),input=editor.inputController.lastKeyPressNanos();
        work("Loading revision comparison",token->service.revision(p,r,revision.id(),token),text->{if(editor.getActivePane()!=pane||source!=editor.getCurrentBuffer()||source!=null&&version!=source.documentRevision()||input!=editor.inputController.lastKeyPressNanos()){state.setText("Editor context changed. Open the revision again.");return;}editor.showComparison(p.label+" revision "+revision.id(),text);});
    }
    private void choose(Action action){
        if(!current()||!ready||busy)return;Provider p=provider;Path expectedRoot=root,expectedProject=project;long w=workspace;
        if(provider==Provider.MERCURIAL&&(action==Action.UPDATE||action==Action.MERGE)){
            Path r=root;work("Loading branches",token->service.branches(r,token),branches->{if(branches.isEmpty()){state.setText("No committed branches to select.");return;}var picker=branchPicker(branches);if(confirm(picker,ScmWorkbenchService.actionLabel(action),JOptionPane.QUESTION_MESSAGE)==JOptionPane.OK_OPTION&&sameContext(p,expectedRoot,w,expectedProject)&&picker.getSelectedItem() instanceof Branch b)operation(action,b.id());});return;
        }
        String value="";
        if(provider==Provider.SUBVERSION&&action==Action.BRANCH){
            var destination=new JTextField();var description=new JTextArea(3,24);var form=new JPanel(new BorderLayout(4,4));var top=new JPanel(new BorderLayout(4,4));top.add(new JLabel("Destination branch/tag URL"),BorderLayout.NORTH);top.add(destination);form.add(top,BorderLayout.NORTH);form.add(new JScrollPane(description));form.add(new JLabel("Commit message · copies the saved working copy to the server"),BorderLayout.SOUTH);
            if(confirm(form,"Create Subversion branch / tag",JOptionPane.QUESTION_MESSAGE)!=JOptionPane.OK_OPTION||!sameContext(p,expectedRoot,w,expectedProject))return;
            operation(action,destination.getText()+"\n"+description.getText());return;
        }
        if(action==Action.RENAME||action==Action.BRANCH||action==Action.SWITCH){value=input(action==Action.RENAME?"New repository-relative path":action==Action.BRANCH?"New named branch (recorded on the next commit)":"Destination repository URL (no embedded credentials)");if(value==null)return;}
        if(sameContext(p,expectedRoot,w,expectedProject))operation(action,value);
    }
    private void operation(Action action,String argument){
        if(!current()||!ready||busy||editor.agentOwnsEditor())return;
        var change=selectedChange();boolean file=Set.of(Action.ADD,Action.FORGET,Action.REVERT,Action.REMOVE,Action.RENAME,Action.RESOLVE).contains(action);
        if(file&&change==null){state.setText("Select a changed file first.");return;}
        if(!editor.canDelegateWindow()){state.setText("Save unsaved buffers and finish pending saves/reload prompts before repository changes.");return;}
        Provider p=provider;Path r=root;long w=workspace;Path expectedProject=project;String path=file?change.path():null;
        String detail=ScmWorkbenchService.actionLabel(action)+" in "+r+"?"+(file?"\nSelected path: "+path:"")+(action==Action.COMMIT?"\nCommits all saved tracked/scheduled changes. Untracked files are not added automatically.":"")+(action==Action.REVERT?"\nDiscard selected working-copy changes. This is not editor Undo.":"")+(action==Action.REMOVE?"\nRemove the selected path from disk and schedule its removal.":"")+(action==Action.RESOLVE?"\nUse the current working file as the resolved result; no merge tool is launched.":"")+(network(p,action)?"\nThis operation contacts the configured repository server.":"");
        if(confirm(detail,p.label+" · "+ScmWorkbenchService.actionLabel(action),JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
        if(!sameContext(p,r,w,expectedProject)||!editor.canDelegateWindow())return;
        try{mutation(p,action,path,argument);}catch(Exception invalid){state.setText(invalid.getMessage());return;}
        work(p.label+" "+ScmWorkbenchService.actionLabel(action),token->{
            var check=new FutureTask<Boolean>(()->sameContext(p,r,w,expectedProject)&&editor.canDelegateWindow()&&!editor.agentOwnsEditor());SwingUtilities.invokeLater(check);if(!check.get(3,TimeUnit.SECONDS))throw new java.util.concurrent.CancellationException("Repository context changed before execution");
            return service.mutate(p,r,action,path,argument,token);
        },output->{if(action==Action.COMMIT)message.setText("");state.setText(ScmWorkbenchService.actionLabel(action)+" completed. Refreshing…");state.setToolTipText(ScmWorkbenchService.cleanError(output));editor.checkForExternalChanges();refresh();});
    }
    void observedChanges(){if(ready&&!busy&&panel.isShowing())refresh();}
    @Override public void close(){closed=true;timer.stop();cancel();snapshot=null;history=List.of();selectedPatch=null;}
}
