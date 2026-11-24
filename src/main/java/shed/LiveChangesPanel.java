package shed;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** Passive visualization of external writes. Human buffers are never overwritten by this surface. */
final class LiveChangesPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel=new JPanel(new BorderLayout(6,6));
    private final DefaultListModel<ExternalChangeSnapshot.Change> rows=new DefaultListModel<>();
    private final JList<ExternalChangeSnapshot.Change> files=new JList<>(rows);
    private final ChangeDiffView diff=new ChangeDiffView();
    private final JLabel state=new JLabel("Watch a project to see external edits here.");
    private final JToggleButton follow=new JToggleButton("Follow",true);
    private final JComboBox<String> baseline=new JComboBox<>(new String[]{"Since watch started","Git changes"});
    private final JTextField agent=new JTextField("Agent",10);
    private final AtomicBoolean queued=new AtomicBoolean();
    private List<Path> excludedRoots=List.of();
    private Path root;private long rootsGeneration,generation;
    private Map<String,ExternalChangeSnapshot.File> original=Map.of(),latest=Map.of();
    private TestWatchService watcher;private boolean closed,busy,dirty;private String selectedPath;
    LiveChangesPanel(Texteditor editor){
        this.editor=editor;state.putClientProperty("html.disable",true);panel.setName("agent.liveChanges");files.setName("agent.changedFiles");follow.setName("agent.follow");baseline.setName("agent.changeBaseline");
        panel.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));
        var actions=new JPanel(new FlowLayout(FlowLayout.LEADING,6,0));
        actions.add(new JLabel("Agent"));actions.add(agent);actions.add(button("Watch project",WorkbenchIcons.PLAY,()->start(agent.getText())));
        actions.add(button("Stop watching",WorkbenchIcons.STOP,this::stop));actions.add(baseline);actions.add(follow);
        actions.add(button("Refresh changes",WorkbenchIcons.REFRESH,this::requestRefresh));
        actions.add(button("Previous change",WorkbenchIcons.PREVIOUS,()->hunk(-1)));actions.add(button("Next change",WorkbenchIcons.NEXT,()->hunk(1)));
        var top=new JPanel(new BorderLayout(4,4));top.add(new JScrollPane(actions,JScrollPane.VERTICAL_SCROLLBAR_NEVER,JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED));top.add(state,BorderLayout.SOUTH);panel.add(top,BorderLayout.NORTH);
        var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(files),new JScrollPane(diff));split.setResizeWeight(.3);panel.add(split);
        files.setCellRenderer((list,value,index,selected,focus)->{
            var label=(JLabel)new DefaultListCellRenderer().getListCellRendererComponent(list,value,index,selected,focus);label.putClientProperty("html.disable",true);
            if(value!=null){label.setToolTipText(value.previous()==null?"File: "+value.path():"Renamed: "+value.previous()+" → "+value.path());
                if(!selected){Color color=UIManager.getColor(value.kind().equals("D")?"shed.error":value.kind().equals("A")?"shed.success":"TextArea.foreground");if(color!=null)label.setForeground(AccessibilitySupport.readableText(color,label.getBackground()));}}
            return label;
        });
        files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);files.addListSelectionListener(e->{if(!e.getValueIsAdjusting())showSelection(false);});
        files.addMouseListener(new java.awt.event.MouseAdapter(){public void mousePressed(java.awt.event.MouseEvent e){follow.setSelected(false);}
            public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2)openSelection(false);}});
        files.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"open");files.getActionMap().put("open",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){openSelection(false);}});
        files.addKeyListener(new java.awt.event.KeyAdapter(){public void keyPressed(java.awt.event.KeyEvent e){follow.setSelected(false);}});
        baseline.setPrototypeDisplayValue("Since watch started   ");
        baseline.addActionListener(e->requestRefresh());
        follow.setToolTipText("Follow completed external edits; pauses while you type or have unsaved changes.");
        AccessibilitySupport.describe(files,"Watched changed files","Added, modified, deleted and exact-content renamed files. Enter opens a surviving file; deleted contents remain in the diff.");
        AccessibilitySupport.describe(follow,"Follow external edits","Toggle automatic file reveal. Human typing or unsaved edits pause following.");
        AccessibilitySupport.describe(agent,"Agent display name","A display label for external writes; filesystem watching cannot identify their author.");
        refreshAppearance();
    }
    private JButton button(String title,Icon icon,Runnable action){var b=new JButton(icon);b.setToolTipText(title);AccessibilitySupport.describe(b,title,title);WorkbenchStyle.quiet(b);b.addActionListener(e->action.run());return b;}
    Map<String,Object> command(String action,String name){
        switch(action){case "start"->start(name);case "stop"->stop();case "follow"->follow.setSelected(true);case "pause"->follow.setSelected(false);case "refresh"->requestRefresh();case "state"->{}default->throw WorkspaceApiException.invalidRequest("Unknown watch action");}
        return Map.of("kind","watch","watching",watcher!=null&&watcher.watching(),"ready",watcher!=null&&!busy,"following",follow.isSelected(),"files",rows.size(),"baseline",Objects.toString(baseline.getSelectedItem()),"agent",agent.getText());
    }
    void start(String name){
        if(name==null||name.isBlank()||name.length()>40||name.codePoints().anyMatch(Character::isISOControl))throw WorkspaceApiException.invalidRequest("Use a short agent display name");
        Path active=editor.workspaceController.activeRoot();if(active==null){state.setText("Open a project before watching.");return;}stop();agent.setText(name);root=active;excludedRoots=editor.workspaceRoots.all().stream().filter(p->!p.equals(active)&&p.startsWith(active)).toList();rootsGeneration=editor.workspaceRoots.generation();long expected=++generation;
        var excluded=excludedRoots;busy=true;state.setText("Capturing the watch baseline…");
        editor.asyncJobService.submit("Start project visualization",token->{
            var watch=TestWatchService.start(active,p->changed());token.onCancel(watch::close);
            try{return new Started(watch,ExternalChangeSnapshot.capture(active,excluded));}catch(Exception failure){watch.close();throw failure;}
        },(job,result,error)->{
            if(closed||expected!=generation){if(result!=null)result.watcher().close();return;}busy=false;
            if(error!=null||result==null||job.getStatus()!=AsyncJobService.Status.SUCCEEDED){if(result!=null)result.watcher().close();state.setText("Cannot watch project: "+(error==null?job.getStatus():error.getMessage()));return;}
            watcher=result.watcher();original=result.files();latest=original;state.setText("Watching · "+name+" · "+original.size()+" baseline files");
            if(dirty||baseline.getSelectedIndex()==1)requestRefresh();
        });
    }
    private record Started(TestWatchService watcher,Map<String,ExternalChangeSnapshot.File> files) { }
    private void changed(){if(queued.compareAndSet(false,true))SwingUtilities.invokeLater(()->{queued.set(false);if(!closed){dirty=true;requestRefresh();}});}
    private boolean current(){return !closed&&root!=null&&root.equals(editor.workspaceController.activeRoot())&&rootsGeneration==editor.workspaceRoots.generation();}
    private void requestRefresh(){
        if(!current()){if(root!=null)stop();return;}if(busy){dirty=true;return;}if(watcher==null)return;
        busy=true;dirty=false;long expected=generation;Path capturedRoot=root;var excluded=excludedRoots;var capturedBaseline=original;boolean git=baseline.getSelectedIndex()==1;String selected=files.getSelectedValue()==null?selectedPath:files.getSelectedValue().path();
        editor.asyncJobService.submit("Observe project changes",token->{
            if(git)return new Update(ExternalChangeSnapshot.capture(capturedRoot,excluded),null,editor.treeGitController.loadGitChangesWorkbench());
            var now=ExternalChangeSnapshot.capture(capturedRoot,excluded);return new Update(now,ExternalChangeSnapshot.compare(capturedBaseline,now),null);
        },(job,result,error)->{
            if(closed||expected!=generation)return;busy=false;if(git!=(baseline.getSelectedIndex()==1)){requestRefresh();return;}if(!current()){stop();return;}
            if(error!=null||result==null){state.setText("Refresh failed; previous changes retained. "+(error==null?job.getStatus():error.getMessage()));return;}
            if(git){latest=result.now();showGit(result.git());}else{
                String changed=result.now().entrySet().stream().filter(e->!Objects.equals(latest.get(e.getKey()),e.getValue())).map(Map.Entry::getKey).sorted().findFirst().orElse(null);
                latest=result.now();for(var pane:editor.editorPanes)if(pane.getAlternateComponent() instanceof FileTreePanel tree)tree.refreshExternalChanges();
                rows.clear();result.changes().forEach(rows::addElement);
                state.setText(agent.getText()+" · "+rows.size()+" changed files · since watch started");
                if(changed!=null&&follow.isSelected()&&((editor.getCurrentBuffer()!=null&&editor.getCurrentBuffer().isModified())||System.nanoTime()-editor.inputController.lastKeyPressNanos()<1_500_000_000L)){
                    follow.setSelected(false);state.setText("Follow paused · continue editing, or enable Follow to resume");
                }
                int index=-1;String target=follow.isSelected()&&changed!=null?changed:selected;
                for(int i=0;i<rows.size();i++)if(rows.get(i).path().equals(target)){index=i;break;}
                if(index<0&&!rows.isEmpty())index=0;if(index>=0)files.setSelectedIndex(index);else diff.showPatch("No changes since this watch started.",editor.resolveEditorFont());
                if(changed!=null&&follow.isSelected())openSelection(true);
            }
            if(dirty)requestRefresh();
        });
    }
    private record Update(Map<String,ExternalChangeSnapshot.File> now,List<ExternalChangeSnapshot.Change> changes,GitChangesWorkbenchModel.Snapshot git) { }
    private GitChangesWorkbenchModel.Snapshot gitSnapshot;
    private List<GitChangesWorkbenchModel.Change> gitRows=List.of();
    private void showGit(GitChangesWorkbenchModel.Snapshot snapshot){
        gitSnapshot=snapshot;gitRows=snapshot.changes().stream().filter(change->{try{Path target=WorkspacePaths.resolve(root,Path.of(snapshot.root()).resolve(change.path()).normalize().toString());return target.startsWith(root)&&ExternalChangeSnapshot.allowed(root,target)&&!Files.isSymbolicLink(target)&&excludedRoots.stream().noneMatch(target::startsWith);}catch(RuntimeException invalid){return false;}}).toList();rows.clear();
        for(var change:gitRows){String status=change.worktreeStatus().isBlank()?change.indexStatus():change.worktreeStatus();String kind=status.contains("Deleted")?"D":status.contains("Renamed")?"R":status.contains("Added")||status.contains("Untracked")?"A":"M";rows.addElement(new ExternalChangeSnapshot.Change(kind,root.relativize(Path.of(snapshot.root()).resolve(change.path()).normalize()).toString(),change.originalPath(),"","",List.of()));}
        state.setText(snapshot.available()?"Git changes · "+rows.size()+" files":snapshot.detail());
        int selected=0;for(int i=0;i<rows.size();i++)if(rows.get(i).path().equals(selectedPath))selected=i;
        if(!rows.isEmpty())files.setSelectedIndex(selected);else diff.showPatch(snapshot.detail(),editor.resolveEditorFont());
    }
    private void showSelection(boolean unused){
        var change=files.getSelectedValue();if(change==null)return;selectedPath=change.path();
        if(baseline.getSelectedIndex()==0){diff.showChange(change,editor.resolveEditorFont());return;}
        int index=files.getSelectedIndex();if(index>=gitRows.size())return;
        var file=latest.get(change.path());
        if(file!=null&&(gitRows.get(index).indexStatus().contains("Untracked")||gitRows.get(index).worktreeStatus().contains("Untracked"))){
            diff.showChange(new ExternalChangeSnapshot.Change("A",change.path(),null,"",file.text(),file.textual()?List.of(new WorkspaceTextEdits.Hunk(0,0,"",file.text())):List.of()),editor.resolveEditorFont());return;
        }long expected=generation;var row=gitRows.get(index);var selectedSnapshot=gitSnapshot;
        editor.asyncJobService.submit("Read visual Git diff",token->editor.treeGitController.loadGitChangesWorkbenchDiff(selectedSnapshot,row),(job,result,error)->{
            if(!closed&&generation==expected&&Objects.equals(selectedPath,change.path())&&baseline.getSelectedIndex()==1)diff.showPatch(error==null&&result!=null?result.content():"Git diff unavailable",editor.resolveEditorFont());
        });
    }
    private void openSelection(boolean automatic){
        var change=files.getSelectedValue();if(change==null||change.kind().equals("D")||!current())return;
        if(automatic&&(latest.get(change.path())==null||!latest.get(change.path()).textual()))return;
        if(automatic&&(editor.getCurrentBuffer()!=null&&editor.getCurrentBuffer().isModified()||System.nanoTime()-editor.inputController.lastKeyPressNanos()<1_500_000_000L)){
            follow.setSelected(false);state.setText("Follow paused · continue editing, or enable Follow to resume");return;}
        long expected=generation;Path target=root.resolve(change.path());
        editor.openFileAsync(target.toFile(),error->{
            if(error!=null||closed||expected!=generation||!current())return;
            var buffer=editor.getCurrentBuffer();if(buffer==null||buffer.getFile()==null||!buffer.getFile().toPath().toAbsolutePath().normalize().equals(target)||buffer.isModified())return;
            if(!buffer.getContent().equals(change.after())||change.hunks().isEmpty())return;
            var ranges=new ArrayList<AgentEditPresentation.Range>();int shift=0;
            for(var h:change.hunks()){if(ranges.size()==32)break;int start=h.start()+shift;ranges.add(new AgentEditPresentation.Range(start,start+h.replacement().length()));shift+=h.replacement().length()-h.before().length();}
            var presentation=editor.getActivePane().agentPresentation();presentation.showApplied(ranges);presentation.setAgentName(agent.getText()+" change");
            if(presentation.available()&&!ranges.isEmpty())try{var rectangle=editor.writingArea.modelToView2D(ranges.getFirst().start());if(rectangle!=null)editor.writingArea.scrollRectToVisible(rectangle.getBounds());}catch(javax.swing.text.BadLocationException unavailable){editor.showMessage("Change revealed in the diff; source location unavailable");}
        });
    }
    private void hunk(int direction){String text=diff.getText();int caret=diff.getCaretPosition();int next=ChangeDiffView.nextHunk(text,caret,direction);if(next>=0)diff.setCaretPosition(next);}
    void stop(){generation++;if(watcher!=null)watcher.close();watcher=null;busy=false;dirty=false;original=Map.of();latest=Map.of();rows.clear();gitRows=List.of();root=null;state.setText("Watch stopped. Start a new watch to capture a new baseline.");}
    @Override public JPanel component(){return panel;}
    @Override public void refresh(){if(root!=null&&!current())stop();}
    @Override public void refreshAppearance(){diff.setFont(editor.resolveEditorFont());files.setFont(editor.resolveUiFont());}
    @Override public void focus(){files.requestFocusInWindow();}
    @Override public void close(){closed=true;stop();}
}
