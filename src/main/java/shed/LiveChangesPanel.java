package shed;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Passive visualization of external writes. Human buffers are never overwritten by this surface. */
final class LiveChangesPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel=new JPanel(new BorderLayout(6,6));
    private final DefaultListModel<ExternalChangeSnapshot.Change> rows=new DefaultListModel<>();
    private final JList<ExternalChangeSnapshot.Change> files=new JList<>(rows);
    private final ChangeDiffView diff=new ChangeDiffView();
    private final ChangePulse pulse=new ChangePulse(files);
    private final JLabel moves=new JLabel();
    private AgentChangeTour tour;
    private PendingTour pendingTour;
    private record PendingTour(ExternalChangeSnapshot.Change change,long generation,java.lang.ref.WeakReference<EditorPane> pane,java.lang.ref.WeakReference<FileBuffer> buffer,long input,int dot,int mark){}
    private Map<String,ExternalChangeSnapshot.Change> activity=Map.of();
    private Map<Path,FileChangeMarks.Mark> explorerMarks=Map.of();
    private final JLabel state=new JLabel("Watch a project to see external edits here.");
    private final JToggleButton follow=new JToggleButton("Follow",true);
    private final JComboBox<String> baseline=new JComboBox<>(new String[]{"Since watch started","Git changes"});
    private final JTextField agent=new JTextField("Agent",10);
    private final java.util.concurrent.atomic.AtomicReference<Path> observedEvent=new java.util.concurrent.atomic.AtomicReference<>();
    private List<Path> excludedRoots=List.of();
    private String watcherName="Agent";
    private Path root;private long rootsGeneration,generation,selectionGeneration;private int diffJob=-1;
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
        var top=new JPanel(new BorderLayout(4,4));top.add(new JScrollPane(actions,JScrollPane.VERTICAL_SCROLLBAR_NEVER,JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED));var details=new JPanel();details.setLayout(new BoxLayout(details,BoxLayout.Y_AXIS));details.add(state);details.add(moves);var coverage=new JLabel("Source + safe config files");coverage.setToolTipText(ExternalChangeSnapshot.COVERAGE);details.add(coverage);top.add(details,BorderLayout.SOUTH);panel.add(top,BorderLayout.NORTH);
        var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(files),new JScrollPane(diff));split.setResizeWeight(.3);panel.add(split);
        files.setCellRenderer((list,value,index,selected,focus)->{
            var label=(JLabel)new DefaultListCellRenderer().getListCellRendererComponent(list,value,index,selected,focus);label.putClientProperty("html.disable",true);
            if(value!=null){label.setToolTipText(value.previous()==null?"File: "+value.path():(value.kind().equals("R?")?"Possible move (text similarity): ":"Moved: ")+value.previous()+" → "+value.path());
                Color accent=ChangePulse.color(value.kind());label.setBackground(ChangePulse.tint(label.getBackground(),accent,pulse.strength(value.path())));label.setForeground(AccessibilitySupport.readableText(label.getForeground(),label.getBackground()));
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
        moves.putClientProperty("html.disable",true);moves.setVisible(false);
        follow.addItemListener(e->{if(!follow.isSelected())closeTour();else prepareFollow();});
        follow.setToolTipText("Follow completed external edits; pauses while you type or have unsaved changes.");
        AccessibilitySupport.describe(files,"Watched changed files","Added, modified, deleted, moved and explicitly labeled possible moves. Enter opens a surviving file; deleted contents remain in the diff.");
        AccessibilitySupport.describe(follow,"Follow external edits","Toggle automatic file reveal. Human typing or unsaved edits pause following.");
        AccessibilitySupport.describe(agent,"Agent display name","A display label for external writes; filesystem watching cannot identify their author.");
        refreshAppearance();
    }
    private JButton button(String title,Icon icon,Runnable action){var b=new JButton(icon);b.setToolTipText(title);AccessibilitySupport.describe(b,title,title);WorkbenchStyle.quiet(b);b.addActionListener(e->{try{action.run();}catch(WorkspaceApiException invalid){state.setText(invalid.getMessage());}});return b;}
    Map<String,Object> command(String action,String name){
        switch(action){case "start"->start(name);case "stop"->stop();case "follow"->follow.setSelected(true);case "pause"->follow.setSelected(false);case "refresh"->requestRefresh();case "state"->{}default->throw WorkspaceApiException.invalidRequest("Unknown watch action");}
        return Map.of("kind","watch","watching",watcher!=null&&watcher.watching(),"ready",watcher!=null&&!busy,"following",follow.isSelected(),"files",rows.size(),"baseline",Objects.toString(baseline.getSelectedItem()),"agent",watcherName);
    }
    void start(String name){
        if(name==null||name.isBlank()||name.length()>40||name.codePoints().anyMatch(Character::isISOControl))throw WorkspaceApiException.invalidRequest("Use a short agent display name");
        Path active=editor.workspaceController.activeRoot();if(active==null){state.setText("Open a project before watching.");return;}stop();agent.setText(name);watcherName=name;root=active;excludedRoots=editor.workspaceRoots.all().stream().filter(p->!p.equals(active)&&p.startsWith(active)).toList();rootsGeneration=editor.workspaceRoots.generation();long expected=++generation;if(follow.isSelected())prepareFollow();
        var excluded=excludedRoots;busy=true;state.setText("Capturing the watch baseline…");
        editor.asyncJobService.submit("Start project visualization",token->{
            var watch=TestWatchService.start(active,p->ExternalChangeSnapshot.allowed(active,p)&&excluded.stream().noneMatch(p::startsWith),p->changed(p,expected));token.onCancel(watch::close);
            try{return new Started(watch,ExternalChangeSnapshot.capture(active,excluded));}catch(Exception failure){watch.close();throw failure;}
        },(job,result,error)->{
            if(closed||expected!=generation){if(result!=null)result.watcher().close();return;}busy=false;
            if(error!=null||result==null||job.getStatus()!=AsyncJobService.Status.SUCCEEDED){if(result!=null)result.watcher().close();state.setText("Cannot watch project: "+(error==null?job.getStatus():error.getMessage()));return;}
            watcher=result.watcher();original=result.files();latest=original;state.setText("Watching · "+name+" · "+original.size()+" baseline files");
            if(dirty||baseline.getSelectedIndex()==1)requestRefresh();
        });
    }
    private record Started(TestWatchService watcher,Map<String,ExternalChangeSnapshot.File> files) { }
    private void changed(Path path,long expected){SwingUtilities.invokeLater(()->{if(closed||expected!=generation)return;observedEvent.set(path);dirty=true;requestRefresh();});}
    private boolean current(){return !closed&&root!=null&&root.equals(editor.workspaceController.activeRoot())&&rootsGeneration==editor.workspaceRoots.generation();}
    private void requestRefresh(){
        if(!current()){if(root!=null)stop();return;}if(busy){dirty=true;return;}if(watcher==null)return;
        busy=true;dirty=false;Path event=observedEvent.getAndSet(null);long expected=generation;Path capturedRoot=root;var excluded=excludedRoots;var capturedBaseline=original;var capturedLatest=latest;boolean git=baseline.getSelectedIndex()==1;String selected=files.getSelectedValue()==null?selectedPath:files.getSelectedValue().path();
        editor.asyncJobService.submit("Observe project changes",token->{
            var now=ExternalChangeSnapshot.capture(capturedRoot,excluded);var activity=ExternalChangeSnapshot.compare(capturedLatest,now);
            if(git)return new Update(now,null,editor.treeGitController.loadGitChangesWorkbench(),activity);
            return new Update(now,ExternalChangeSnapshot.compare(capturedBaseline,now),null,activity);
        },(job,result,error)->{
            if(closed||expected!=generation)return;busy=false;if(git!=(baseline.getSelectedIndex()==1)){requestRefresh();return;}if(!current()){stop();return;}
            if(error!=null||result==null){state.setText("Refresh failed; previous changes retained. "+(error==null?job.getStatus():error.getMessage()));if(dirty)requestRefresh();return;}
            activity=result.activity().stream().filter(change->change.before().length()<=65536&&change.after().length()<=65536).sorted(java.util.Comparator.comparingInt(change->event!=null&&(root.resolve(change.path()).equals(event)||change.previous()!=null&&root.resolve(change.previous()).equals(event))?0:1)).limit(32).collect(java.util.stream.Collectors.toUnmodifiableMap(ExternalChangeSnapshot.Change::path,change->change));
            var recent=new HashSet<String>();for(var change:result.activity()){recent.add(change.path());if(change.previous()!=null)recent.add(change.previous());}
            pulse.flash(recent);
            if(git){boolean paused=follow.isSelected()&&humanBusy();if(paused)follow.setSelected(false);latest=result.now();if(follow.isSelected()&&event!=null&&event.startsWith(root))selectedPath=root.relativize(event).toString();showGit(result.git());publishRows(recent);if(follow.isSelected()&&files.getSelectedValue()!=null&&activity.containsKey(files.getSelectedValue().path()))openSelection(true);if(paused)state.setText("Follow paused · continue editing, or enable Follow to resume");}else{
                String changed=result.activity().isEmpty()?null:result.activity().getFirst().path();
                if(event!=null&&event.startsWith(root)){
                    String relative=root.relativize(event).toString();
                    for(var row:result.activity())if(row.path().equals(relative)||relative.equals(row.previous())){changed=row.path();break;}
                }
                latest=result.now();
                rows.clear();result.changes().forEach(rows::addElement);publishRows(recent);
                state.setText(watcherName+" · "+rows.size()+" changed files · since watch started");
                if(changed!=null&&follow.isSelected()&&humanBusy()){
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
    private void prepareFollow(){
        if(editor.isTreePaneActive()){var content=editor.resolveTreeContentPaneForOpen();if(content!=null){editor.activateEditorPane(content);content.getTextArea().requestFocusInWindow();}}
    }
    void pauseForHuman(){if(root!=null)follow.setSelected(false);}
    private void publishRows(Set<String> recent){
        var changes=java.util.Collections.list(rows.elements());String summary=ExternalChangeSnapshot.moveSummary(changes);moves.setText(summary);moves.setToolTipText(summary);moves.setVisible(!summary.isEmpty());
        explorerMarks=FileChangeMarks.from(root,changes);editor.toolWindowHost.observeAgentFiles(root,changes,recent);
    }
    boolean selectObserved(Path path){
        if(!current()||!path.startsWith(root))return false;String name=root.relativize(path).toString();
        for(int i=0;i<rows.size();i++){var row=rows.get(i);if(row.path().equals(name)||name.equals(row.previous())){follow.setSelected(false);files.setSelectedIndex(i);files.ensureIndexIsVisible(i);return true;}}return false;
    }
    void decorateExplorer(FileTreePanel tree){tree.observeChanges(current()?explorerMarks:Map.of(),Set.of());}
    private void closeTour(){pendingTour=null;if(tour!=null){var previous=tour;tour=null;previous.close();}}
    private boolean humanBusy(){return editor.getCurrentBuffer()!=null&&editor.getCurrentBuffer().isModified()||System.nanoTime()-editor.inputController.lastKeyPressNanos()<1_500_000_000L;}
    private record Update(Map<String,ExternalChangeSnapshot.File> now,List<ExternalChangeSnapshot.Change> changes,GitChangesWorkbenchModel.Snapshot git,List<ExternalChangeSnapshot.Change> activity) { }
    private GitChangesWorkbenchModel.Snapshot gitSnapshot;
    private List<GitChangesWorkbenchModel.Change> gitRows=List.of();
    private void showGit(GitChangesWorkbenchModel.Snapshot snapshot){
        gitSnapshot=snapshot;gitRows=snapshot.changes().stream().filter(change->{try{Path target=WorkspacePaths.resolve(root,Path.of(snapshot.root()).resolve(change.path()).normalize().toString());if(change.originalPath()!=null)WorkspacePaths.resolve(root,Path.of(snapshot.root()).resolve(change.originalPath()).normalize().toString());return target.startsWith(root)&&ExternalChangeSnapshot.allowed(root,target)&&!Files.isSymbolicLink(target)&&excludedRoots.stream().noneMatch(target::startsWith);}catch(RuntimeException invalid){return false;}}).toList();rows.clear();
        for(var change:gitRows){String status=change.worktreeStatus().isBlank()?change.indexStatus():change.worktreeStatus();String kind=status.contains("Deleted")?"D":status.contains("Renamed")?"R":status.contains("Added")||status.contains("Untracked")?"A":"M";rows.addElement(new ExternalChangeSnapshot.Change(kind,root.relativize(Path.of(snapshot.root()).resolve(change.path()).normalize()).toString(),change.originalPath()==null?null:root.relativize(Path.of(snapshot.root()).resolve(change.originalPath()).normalize()).toString(),"","",List.of()));}
        state.setText(snapshot.available()?"Git changes · "+rows.size()+" files":snapshot.detail());
        int selected=0;for(int i=0;i<rows.size();i++)if(rows.get(i).path().equals(selectedPath))selected=i;
        if(!rows.isEmpty())files.setSelectedIndex(selected);else diff.showPatch(snapshot.detail(),editor.resolveEditorFont());
    }
    private void showSelection(boolean unused){
        var change=files.getSelectedValue();if(change==null){selectionGeneration++;return;}selectedPath=change.path();if(follow.isSelected())files.ensureIndexIsVisible(files.getSelectedIndex());
        if(baseline.getSelectedIndex()==0){showSnapshot(change);return;}
        int index=files.getSelectedIndex();if(index>=gitRows.size())return;
        var file=latest.get(change.path());
        if(file!=null&&(gitRows.get(index).indexStatus().contains("Untracked")||gitRows.get(index).worktreeStatus().contains("Untracked"))){
            showSnapshot(new ExternalChangeSnapshot.Change("A",change.path(),null,"",file.text(),file.textual()?List.of(new WorkspaceTextEdits.Hunk(0,0,"",file.text())):List.of()));return;
        }diff.showPatch("Loading diff for "+change.path()+"…",editor.resolveEditorFont());long expected=generation;var row=gitRows.get(index);var selectedSnapshot=gitSnapshot;
        editor.asyncJobService.submit("Read visual Git diff",token->editor.treeGitController.loadGitChangesWorkbenchDiff(selectedSnapshot,row),(job,result,error)->{
            if(!closed&&generation==expected&&Objects.equals(selectedPath,change.path())&&baseline.getSelectedIndex()==1)diff.showPatch(error==null&&result!=null?result.content():"Git diff unavailable",editor.resolveEditorFont());
        });
    }
    private void showSnapshot(ExternalChangeSnapshot.Change change){
        long expected=generation,selection=++selectionGeneration;int mode=baseline.getSelectedIndex();
        if(diffJob>=0)editor.asyncJobService.cancel(diffJob);
        diff.showPatch("Loading diff for "+change.path()+"…",editor.resolveEditorFont());
        diffJob=editor.asyncJobService.submit("Format visual diff",token->ChangeDiffView.format(change),(job,result,error)->{
            if(closed||expected!=generation||selection!=selectionGeneration||mode!=baseline.getSelectedIndex()||!Objects.equals(selectedPath,change.path()))return;
            diffJob=-1;
            diff.showPatch(error==null&&result!=null?result:"Diff unavailable; refresh changes to try again.",editor.resolveEditorFont());
        });
    }
    private void openSelection(boolean automatic){
        var selected=files.getSelectedValue();if(selected==null||!current())return;
        if(automatic&&(editor.getActivePane().getAlternateComponent()!=null||editor.getActivePane().isMarkdownPreview())){follow.setSelected(false);state.setText("Follow paused while another editor surface is active");return;}
        if(!automatic)prepareFollow();
        var change=automatic?activity.get(selected.path()):selected;if(change==null||change.kind().equals("D"))return;
        if(automatic&&(latest.get(change.path())==null||!latest.get(change.path()).textual()||latest.get(change.path()).text().length()>65536))return;
        if(automatic&&(editor.getCurrentBuffer()!=null&&editor.getCurrentBuffer().isModified()||System.nanoTime()-editor.inputController.lastKeyPressNanos()<1_500_000_000L)){
            follow.setSelected(false);state.setText("Follow paused · continue editing, or enable Follow to resume");return;}
        closeTour();long expected=generation;Path target=root.resolve(change.path());
        var pane=editor.getActivePane();var before=editor.getCurrentBuffer();long revision=before==null?-1:before.documentRevision(),input=editor.inputController.lastKeyPressNanos();
        int dot=editor.writingArea.getCaret().getDot(),mark=editor.writingArea.getCaret().getMark();
        java.util.function.BooleanSupplier valid=()->!closed&&expected==generation&&current()&&Objects.equals(selectedPath,change.path())&&pane==editor.getActivePane()
            &&(!automatic||follow.isSelected()&&!humanBusy()&&before==editor.getCurrentBuffer()&&(before==null||revision==before.documentRevision())
                &&input==editor.inputController.lastKeyPressNanos()&&dot==editor.writingArea.getCaret().getDot()&&mark==editor.writingArea.getCaret().getMark());
        java.util.function.Consumer<java.io.IOException> complete=error->{
            if(error!=null||closed||expected!=generation||!current())return;
            var buffer=editor.getCurrentBuffer();if(buffer==null||buffer.getFile()==null||!buffer.getFile().toPath().toAbsolutePath().normalize().equals(target)||buffer.isModified())return;
            if(automatic){pendingTour=new PendingTour(change,expected,new java.lang.ref.WeakReference<>(editor.getActivePane()),new java.lang.ref.WeakReference<>(buffer),input,editor.writingArea.getCaret().getDot(),editor.writingArea.getCaret().getMark());observedSourceReady();}
            else presentChange(change,false,expected,buffer);
        };
        if(automatic)editor.paneBufferController.openFileCheckedAsync(target.toFile(),valid,b->!b.isModified(),b->true,()->{},complete);
        else editor.openFileAsync(target.toFile(),complete);
    }
    void observedSourceReady(){
        var pending=pendingTour;if(pending==null)return;var source=pending.buffer().get();
        if(closed||pending.generation()!=generation||!current()||!follow.isSelected()||humanBusy()||editor.getActivePane()!=pending.pane().get()||source==null||editor.getCurrentBuffer()!=source
            ||!Objects.equals(selectedPath,pending.change().path())||editor.inputController.lastKeyPressNanos()!=pending.input()
            ||editor.writingArea.getCaret().getDot()!=pending.dot()||editor.writingArea.getCaret().getMark()!=pending.mark()){pendingTour=null;return;}
        if(!source.getContent().equals(pending.change().after()))return;
        pendingTour=null;presentChange(pending.change(),true,pending.generation(),source);
    }
    private void presentChange(ExternalChangeSnapshot.Change change,boolean automatic,long expected,FileBuffer buffer){
        if(!buffer.getContent().equals(change.after())||change.hunks().isEmpty())return;
        var ranges=new ArrayList<AgentEditPresentation.Range>();int shift=0;
        for(var h:change.hunks()){if(ranges.size()==32)break;int start=h.start()+shift;ranges.add(new AgentEditPresentation.Range(start,start+h.replacement().length()));shift+=h.replacement().length()-h.before().length();}
        var presentation=editor.getActivePane().agentPresentation();
        if(automatic){
            long sourceRevision=buffer.documentRevision();var sourcePane=editor.getActivePane();
            tour=new AgentChangeTour(editor.writingArea,presentation,ranges,change.after(),watcherName+" change",
                ()->!closed&&expected==generation&&current()&&follow.isSelected()&&editor.getActivePane()==sourcePane&&editor.getCurrentBuffer()==buffer&&!buffer.isModified()&&buffer.documentRevision()==sourceRevision,
                ()->follow.setSelected(false),()->tour=null);tour.start();
        }else{presentation.showApplied(ranges);presentation.setAgentName(watcherName+" change");if(presentation.available()&&!ranges.isEmpty())try{var rectangle=editor.writingArea.modelToView2D(ranges.getFirst().start());if(rectangle!=null)editor.writingArea.scrollRectToVisible(rectangle.getBounds());}catch(javax.swing.text.BadLocationException unavailable){editor.showMessage("Change revealed in the diff; source location unavailable");}}
    }
    private void hunk(int direction){String text=diff.getText();int caret=diff.getCaretPosition();int next=ChangeDiffView.nextHunk(text,caret,direction);if(next>=0)diff.setCaretPosition(next);}
    void stop(){closeTour();pulse.clear();if(root!=null)editor.toolWindowHost.observeAgentFiles(root,List.of(),Set.of());explorerMarks=Map.of();activity=Map.of();moves.setVisible(false);generation++;selectionGeneration++;if(diffJob>=0)editor.asyncJobService.cancel(diffJob);diffJob=-1;if(watcher!=null)watcher.close();watcher=null;observedEvent.set(null);busy=false;dirty=false;original=Map.of();latest=Map.of();rows.clear();gitRows=List.of();root=null;state.setText("Watch stopped. Start a new watch to capture a new baseline.");}
    @Override public JPanel component(){return panel;}
    @Override public void refresh(){if(root!=null&&!current())stop();}
    @Override public void refreshAppearance(){diff.refreshAppearance(editor.resolveEditorFont());files.setFont(editor.resolveUiFont());}
    @Override public void focus(){files.requestFocusInWindow();}
    @Override public void close(){closed=true;stop();pulse.close();}
}
