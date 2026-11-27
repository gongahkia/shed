package shed;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.text.*;

/** Adapts existing workbench owners to the semantic interaction engine. */
final class AgentWorkbenchUi implements AgentUiSession.Host,AutoCloseable {
    private final Texteditor editor;
    private final Path root;
    private final long generation;
    private final BooleanSupplier live;
    private final AgentUiSession session;
    private final AgentUiPresentation presence;
    private AgentUiPermission dialogPermission=AgentUiPermission.NAVIGATE;
    private boolean closed;
    private final Set<FileBuffer> created=Collections.newSetFromMap(new IdentityHashMap<>());
    private final AgentPolicy sourcePolicy=new AgentPolicy(Set.of(AgentPolicy.Capability.READ,AgentPolicy.Capability.PROPOSE,AgentPolicy.Capability.APPLY),List.of("README.md","**/README.md"),List.of(".env","**/*.env","**/*.env.*","**/.ssh/**"),true);

    AgentWorkbenchUi(Texteditor editor,BooleanSupplier live,AgentUiPresentation presence){this.editor=editor;this.live=live;this.presence=presence;root=editor.workspaceController.activeRoot();generation=editor.workspaceRoots.generation();session=new AgentUiSession(this,presence);}
    Map<String,Object> state(int offset){return session.state(offset);}
    Map<String,Object> inspect(String id,int offset){return session.inspect(id,offset);}
    CompletableFuture<Map<String,Object>> execute(AgentUiRequest request,BooleanSupplier connected){return session.execute(request,connected);}
    public boolean current(){return !closed&&live.getAsBoolean()&&editor.isDisplayable()&&Objects.equals(root,editor.workspaceController.activeRoot())&&generation==editor.workspaceRoots.generation();}
    public boolean permitted(AgentUiPermission permission){return editor.agentDelegation().uiAllowed(permission);}
    public boolean motion(){return !Boolean.FALSE.equals(editor.writingArea.getClientProperty("shed.caretAnimation"))&&!"false".equalsIgnoreCase(System.getProperty("shed.caretAnimation"));}
    public JRootPane root(){return editor.getRootPane();}
    public String contextKey(){var key=new StringBuilder().append(generation);for(var pane:editor.editorPanes)if(!pane.isDisposed()&&pane.getBuffer()!=null)key.append(pane.observationId()).append(':').append(pane.getBuffer().documentRevision());return key.toString();}
    private boolean allowed(FileBuffer buffer){
        if(buffer==null)return false;if(buffer.getFile()==null)return created.contains(buffer)||buffer.getContent().isEmpty();
        Path path=buffer.getFile().toPath().toAbsolutePath().normalize();if(root==null||!path.startsWith(root))return false;
        try{sourcePolicy.checkPath(root.relativize(path),AgentPolicy.Capability.READ);return true;}catch(WorkspaceApiException denied){return false;}
    }
    private EditorPane pane(JComponent component){for(var pane:editor.editorPanes)if(!pane.isDisposed()&&pane.getTextArea()==component)return pane;return null;}
    public void check(AgentUiTarget target,String kind){
        var pane=pane(target.component());if(pane!=null){if(!allowed(pane.getBuffer())||pane.getBuffer().isLargeFile()||pane.getTerminalPane()!=null)throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);
            if(Set.of("text","paste").contains(kind)&&!permitted(AgentUiPermission.EDIT))throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);
        }
    }
    public void validate(AgentUiTarget target,AgentUiRequest request){
        var c=target.component();int index=request.args().containsKey("index")?(int)AgentUiRequest.integer(request.args(),"index"):-1;
        if(c instanceof JTree tree&&index>=0&&index<tree.getRowCount()){
            Object selected=tree.getPathForRow(index).getLastPathComponent();if(selected instanceof FileTreeModel.Node node){Path path=node.file().toPath().toAbsolutePath().normalize();if(!path.startsWith(root))throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);sourcePolicy.checkPath(root.relativize(path),AgentPolicy.Capability.READ);}
        }
        if(c instanceof JComboBox<?> combo&&index>=0&&index<combo.getItemCount()){
            String value=String.valueOf(combo.getItemAt(index));if(value.startsWith("/")||value.matches("[A-Za-z]:.*")){Path path=Path.of(value).toAbsolutePath().normalize();if(!path.startsWith(root))throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);}
        }
        if(c instanceof JList<?> list&&index>=0&&index<list.getModel().getSize()){
            Object value=list.getModel().getElementAt(index);if(value instanceof WorkspaceTextSearchService.Match match){Path file=Path.of(match.filePath()).toAbsolutePath().normalize();if(!file.startsWith(root))throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);sourcePolicy.checkPath(root.relativize(file),AgentPolicy.Capability.READ);}
        }
    }
    public void edit(AgentUiTarget target,int start,int end,String text)throws BadLocationException{
        var pane=pane(target.component());
        if(pane==null){AgentUiSession.Host.super.edit(target,start,end,text);return;}
        if(!permitted(AgentUiPermission.EDIT)||!allowed(pane.getBuffer()))throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);
        var buffer=pane.getBuffer();if(buffer.getFile()!=null)sourcePolicy.checkPath(root.relativize(buffer.getFile().toPath().toAbsolutePath().normalize()),AgentPolicy.Capability.APPLY);
        if(buffer.getDocument()!=pane.getTextArea().getDocument()||buffer.isLargeFile())throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
        String before=buffer.getContent();if(before.length()>EditorControl.MAX_DOCUMENT||start<0||end<start||end>before.length()||!GraphemeBoundary.isBoundary(before,start)||!GraphemeBoundary.isBoundary(before,end))throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
        String after=before.substring(0,start)+text+before.substring(end);if(after.length()>EditorControl.MAX_DOCUMENT)throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
        try(var transaction=buffer.beginTextTransaction()){transaction.replace(after);transaction.commit();}
        pane.getTextArea().setCaretPosition(start+text.length());pane.agentPresentation().showApplied(start,start+text.length());
    }
    public void activity(AgentUiTarget target,String kind,int start,int end,String agent){
        var pane=pane(target.component());if(pane==null)return;
        if(kind.equals("reading"))pane.agentPresentation().showReading(start,end,agent);
        else pane.agentPresentation().showApplied(start,end);
    }
    public void invoke(AgentUiTarget target,String value){
        dialogPermission=target.permission();
        if(target.component() instanceof JMenuItem item){
            if(item.getText().equals("Save")||item.getText().equals("Save As…")){save("",item.getText().equals("Save As…"));return;}
            if(item.getText().equals("Undo")||item.getText().equals("Redo")){checkActiveSource(true);editor.inputController.performAgentMenuAction(item.getText().equals("Undo")?PlainKeymap.Action.UNDO:PlainKeymap.Action.REDO);return;}
        }
        if(target.component() instanceof JMenu menu){menu.setPopupMenuVisible(!menu.isPopupMenuVisible());return;}
        AgentUiSession.Host.super.invoke(target,value);
    }
    public List<AgentUiTarget> targets(){
        var targets=new ArrayList<AgentUiTarget>();
        // only windows owned by this workbench; consent and OS file choosers are never agent targets
        for(Window window:Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&owned(dialog)&&!Objects.toString(dialog.getName(),"").startsWith("agent.approval")){
            if(containsFileChooser(dialog))continue;
            AgentUiTargets.collect(dialog.getRootPane(),"dialog."+AgentUiTargets.slug(dialog.getName()==null?dialog.getTitle():dialog.getName()),dialogPermission,targets);
            targets.add(new AgentUiTarget("dialog.dismiss","Dismiss dialog",dialog.getRootPane(),AgentUiPermission.NAVIGATE,value->dialog.dispatchEvent(new WindowEvent(dialog,WindowEvent.WINDOW_CLOSING))));
            if(dialog.isModal())return unique(targets);
        }
        AgentUiTargets.collect(editor.getJMenuBar(),"menu",AgentUiPermission.NAVIGATE,targets);
        AgentUiTargets.collect(editor.inlineFindBar,"find",AgentUiPermission.NAVIGATE,targets);
        if(editor.fileExplorer!=null)AgentUiTargets.collect(editor.fileExplorer,"explorer",AgentUiPermission.NAVIGATE,targets);
        if(editor.outlinePanel!=null){AgentUiTargets.collect(editor.outlinePanel,"outline",AgentUiPermission.NAVIGATE,targets);add(targets,"outline.reveal","Reveal outline symbol",AgentUiPermission.NAVIGATE,v->editor.outlinePanel.revealAgent());}
        if(editor.buffers.stream().allMatch(this::allowed))AgentUiTargets.collect(editor.openEditorsBar,"tabs",AgentUiPermission.NAVIGATE,targets);
        for(int i=0;i<editor.editorPanes.size();i++){
            var pane=editor.editorPanes.get(i);if(pane.isDisposed()||!allowed(pane.getBuffer()))continue;
            String id="pane/"+pane.observationId();
            if(pane.getTerminalPane()==null){var area=pane.getTextArea();area.putClientProperty("shed.agent.source",true);targets.add(new AgentUiTarget(id,"Source editor",area,AgentUiPermission.NAVIGATE,null));
                if(pane==editor.getActivePane())targets.add(new AgentUiTarget("editor.active","Active editor",area,AgentUiPermission.NAVIGATE,null));
                targets.add(new AgentUiTarget(id+"/scrollbar.vertical","Vertical scrollbar",pane.getScrollPane().getVerticalScrollBar(),AgentUiPermission.NAVIGATE,null));
                targets.add(new AgentUiTarget(id+"/scrollbar.horizontal","Horizontal scrollbar",pane.getScrollPane().getHorizontalScrollBar(),AgentUiPermission.NAVIGATE,null));}
            if(pane.getComponent() instanceof JComponent component)AgentUiTargets.collect(component,id+"/surface",pane.getTerminalPane()==null?AgentUiPermission.NAVIGATE:AgentUiPermission.TOOLS,targets);
            targets.add(new AgentUiTarget(id+"/focus","Select editor pane",(JComponent)pane.getComponent(),AgentUiPermission.NAVIGATE,v->editor.activateEditorPane(pane)));
        }
        if(editor.workbenchDocks!=null){targets.add(new AgentUiTarget("layout/right","Right sidebar divider",editor.workbenchDocks,AgentUiPermission.NAVIGATE,null));targets.add(new AgentUiTarget("layout/bottom","Bottom divider",editor.workbenchDocks.bottomSplit(),AgentUiPermission.NAVIGATE,null));}
        if(editor.explorerSidebar!=null)targets.add(new AgentUiTarget("layout/explorer","Explorer divider",editor.explorerSidebar,AgentUiPermission.NAVIGATE,null));
        for(var entry:editor.toolWindowHost.agentSurfaces().entrySet()){
            AgentUiPermission permission=switch(entry.getKey()){case DEBUG,TASKS,TESTS->AgentUiPermission.TOOLS;case GIT->AgentUiPermission.VCS;case REPLACE->AgentUiPermission.EDIT;default->AgentUiPermission.NAVIGATE;};
            AgentUiTargets.collect(entry.getValue(),"tool."+entry.getKey().name().toLowerCase(Locale.ROOT),permission,targets);
        }
        targets.removeIf(t->t.component() instanceof JPasswordField||t.id().contains("agent.chat.executable")||t.id().contains("agent.chat.send")||t.id().contains("agent.chat.new")||t.id().contains("agent.chat.stop")||t.id().contains("agent.chat.provider"));
        actions(targets);return unique(targets);
    }
    private List<AgentUiTarget> unique(List<AgentUiTarget> targets){var map=new LinkedHashMap<String,AgentUiTarget>();for(var target:targets)map.putIfAbsent(target.id(),target);return map.values().stream().limit(4096).toList();}
    private boolean owned(Window window){for(Window owner=window.getOwner();owner!=null;owner=owner.getOwner())if(owner==editor)return true;return false;}
    private static boolean containsFileChooser(Component c){if(c instanceof JFileChooser)return true;if(c instanceof Container p)for(Component child:p.getComponents())if(containsFileChooser(child))return true;return false;}
    private void add(List<AgentUiTarget> targets,String id,String label,AgentUiPermission permission,AgentUiTarget.Action action){targets.add(new AgentUiTarget("action/"+id,label,editor.getRootPane(),permission,action));}
    private void menu(List<AgentUiTarget> targets,String id,String label,AgentUiPermission permission,PlainKeymap.Action action){add(targets,id,label,permission,v->editor.inputController.performAgentMenuAction(action));}
    private void actions(List<AgentUiTarget> targets){
        menu(targets,"find.open","Find",AgentUiPermission.NAVIGATE,PlainKeymap.Action.FIND);
        menu(targets,"find.next","Find next",AgentUiPermission.NAVIGATE,PlainKeymap.Action.FIND_NEXT);
        menu(targets,"find.previous","Find previous",AgentUiPermission.NAVIGATE,PlainKeymap.Action.FIND_PREVIOUS);
        menu(targets,"replace.open","Replace in file",AgentUiPermission.EDIT,PlainKeymap.Action.REPLACE);
        menu(targets,"line.open","Go to Line",AgentUiPermission.NAVIGATE,PlainKeymap.Action.GO_TO_LINE);
        menu(targets,"navigation.back","Go back",AgentUiPermission.NAVIGATE,PlainKeymap.Action.NAVIGATE_BACK);
        menu(targets,"navigation.forward","Go forward",AgentUiPermission.NAVIGATE,PlainKeymap.Action.NAVIGATE_FORWARD);
        menu(targets,"tabs.next","Next buffer",AgentUiPermission.NAVIGATE,PlainKeymap.Action.NEXT_BUFFER);
        menu(targets,"tabs.previous","Previous buffer",AgentUiPermission.NAVIGATE,PlainKeymap.Action.PREVIOUS_BUFFER);
        menu(targets,"tabs.close","Close buffer",AgentUiPermission.EDIT,PlainKeymap.Action.CLOSE);
        menu(targets,"source.undo","Undo",AgentUiPermission.EDIT,PlainKeymap.Action.UNDO);
        menu(targets,"source.redo","Redo",AgentUiPermission.EDIT,PlainKeymap.Action.REDO);
        add(targets,"source.new","New file",AgentUiPermission.EDIT,v->{editor.inputController.performAgentMenuAction(PlainKeymap.Action.NEW);created.add(editor.getCurrentBuffer());});
        add(targets,"source.save","Save",AgentUiPermission.EDIT,v->save("",false));
        add(targets,"files.open","Find project file",AgentUiPermission.NAVIGATE,v->editor.showMessage(editor.showFileFinderFromFolder(root.toFile())));
        add(targets,"buffers.open","Switch buffer",AgentUiPermission.NAVIGATE,v->{if(!editor.buffers.stream().allMatch(this::allowed))throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);editor.showMessage(editor.showBufferFinder());});
        add(targets,"commands.open","Command palette",AgentUiPermission.TOOLS,v->{for(var cap:List.of(AgentUiPermission.EDIT,AgentUiPermission.FILES,AgentUiPermission.VCS,AgentUiPermission.APPEARANCE))if(!permitted(cap))throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);editor.showMessage(editor.showCommandPalette());});
        menu(targets,"symbols.open","Document symbols",AgentUiPermission.NAVIGATE,PlainKeymap.Action.SYMBOLS);
        add(targets,"explorer.toggle","Toggle Explorer",AgentUiPermission.NAVIGATE,v->editor.showMessage(editor.treeGitController.handleTreeCommand("toggle")));
        add(targets,"outline.toggle","Toggle Outline",AgentUiPermission.NAVIGATE,v->editor.toggleOutline());
        add(targets,"pane.split.vertical","Split editor vertically",AgentUiPermission.NAVIGATE,v->editor.showMessage(editor.splitWindow(true)));
        add(targets,"pane.split.horizontal","Split editor horizontally",AgentUiPermission.NAVIGATE,v->editor.showMessage(editor.splitWindow(false)));
        add(targets,"pane.close","Close pane",AgentUiPermission.NAVIGATE,v->editor.showMessage(editor.closeActiveWindow()));
        add(targets,"right.hide","Hide right sidebar",AgentUiPermission.NAVIGATE,v->editor.workbenchDocks.showRight(false));
        add(targets,"bottom.hide","Hide bottom tools",AgentUiPermission.NAVIGATE,v->editor.workbenchDocks.showBottom(false));
        for(var tab:ToolWindowHost.Tab.values())add(targets,"panel."+tab.name().toLowerCase(Locale.ROOT),"Open "+tab, switch(tab){case DEBUG,TASKS,TESTS->AgentUiPermission.TOOLS;case GIT->AgentUiPermission.VCS;case REPLACE->AgentUiPermission.EDIT;default->AgentUiPermission.NAVIGATE;},v->editor.showToolWindow(tab));
        add(targets,"preview.open","Open Markdown preview",AgentUiPermission.NAVIGATE,v->{checkActiveSource(false);editor.showMessage(editor.markdownController.openPreviewForAgent());});
        add(targets,"terminal.new","New project terminal",AgentUiPermission.TOOLS,v->editor.showMessage(editor.openTerminal()));
        add(targets,"terminal.close","Close terminal",AgentUiPermission.TOOLS,v->{if(editor.getActivePane().getTerminalPane()==null)throw fail(WorkspaceApiException.Code.UNSUPPORTED);editor.closeTerminalSession(editor.getCurrentBuffer());});
        add(targets,"settings.open","Settings",AgentUiPermission.APPEARANCE,v->editor.showMessage(editor.showSettingsInspector()));
        add(targets,"themes.open","Theme gallery",AgentUiPermission.APPEARANCE,v->editor.showMessage(editor.showThemes()));
        add(targets,"focus.toggle","Focus mode",AgentUiPermission.NAVIGATE,v->editor.showMessage(editor.focusModeController.handleFocusCommand("toggle")));
        add(targets,"files.save-as","Save to a project path",AgentUiPermission.FILES,v->save(v,true));
        add(targets,"files.change","Checked project file changes",AgentUiPermission.FILES,this::fileChanges);
    }
    public boolean shortcut(AgentUiTarget target,String key){
        var stroke=KeyStroke.getKeyStroke(key);if(stroke==null)return false;
        var event=new KeyEvent(target.component(),KeyEvent.KEY_PRESSED,System.currentTimeMillis(),stroke.getModifiers(),stroke.getKeyCode(),KeyEvent.CHAR_UNDEFINED);
        String custom=editor.configManager.actionKeybinding(editor.modeKey(editor.editorState.mode),event);
        PlainKeymap.Action action=PlainKeymap.Action.NONE;
        if(custom!=null){
            for(var candidate:PlainKeymap.Action.values())if(custom.equals(candidate.exCommand())){action=candidate;break;}
            if(custom.equals("find"))action=PlainKeymap.Action.FIND;
            if(action==PlainKeymap.Action.NONE)throw fail(WorkspaceApiException.Code.UNSUPPORTED);
        }else action=PlainKeymap.actionFor(event);
        if(action==PlainKeymap.Action.NONE)return false;
        switch(action){
            case OPEN,FIND_FILE->{editor.showMessage(editor.showFileFinderFromFolder(root.toFile()));return true;}
            case SAVE->{save("",false);return true;}
            case SAVE_AS->{save("",true);return true;}
            case QUIT,OPEN_FOLDER->throw fail(WorkspaceApiException.Code.UNSUPPORTED);
            case COMMANDS->{if(!permitted(AgentUiPermission.TOOLS))throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);}
            case UNDO,REDO,NEW,CLOSE,REPLACE->{if(!permitted(AgentUiPermission.EDIT))throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);}
            default->{}
        }
        if(Set.of(PlainKeymap.Action.UNDO,PlainKeymap.Action.REDO).contains(action))checkActiveSource(true);
        editor.inputController.performAgentMenuAction(action);if(action==PlainKeymap.Action.NEW)created.add(editor.getCurrentBuffer());return true;
    }
    private void save(String value,boolean saveAs){
        checkActiveSource(true);if(!permitted(AgentUiPermission.EDIT))throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);
        var buffer=editor.getCurrentBuffer();if(!saveAs&&buffer.getFile()!=null){editor.showMessage(editor.documents.save("",false,false));return;}
        if(!permitted(AgentUiPermission.FILES))throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);
        String path=value;if(path.isBlank()){dialogPermission=AgentUiPermission.FILES;path=JOptionPane.showInputDialog(editor,"Project-relative file path",buffer.getFile()==null?"Untitled.txt":root.relativize(buffer.getFile().toPath()).toString());}
        if(path==null)return;Path destination=WorkspacePaths.resolve(root,path);sourcePolicy.checkPath(root.relativize(destination),AgentPolicy.Capability.APPLY);
        editor.showMessage(editor.documents.save(destination.toString(),false,false));
    }
    private void checkActiveSource(boolean write){var buffer=editor.getCurrentBuffer();if(!allowed(buffer))throw fail(WorkspaceApiException.Code.PROJECT_UNAUTHORIZED);if(write&&buffer.getFile()!=null)sourcePolicy.checkPath(root.relativize(buffer.getFile().toPath().toAbsolutePath().normalize()),AgentPolicy.Capability.APPLY);}
    private void fileChanges(String value){
        var inputs=EditorProjectChanges.parse(MiniJson.parseStrict(value));
        for(var input:inputs){sourcePolicy.checkPath(Path.of(input.document()),AgentPolicy.Capability.APPLY);if(input.destination()!=null)sourcePolicy.checkPath(Path.of(input.destination()),AgentPolicy.Capability.APPLY);}
        var documents=editor.workspaceDocuments();var config=editor.configManager;
        editor.asyncJobService.submit("agent project files",token->{
            if(!currentPermission(AgentUiPermission.FILES))throw fail(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
            var workspace=new WorkspaceQueryService(root,sourcePolicy,documents);
            var proposals=new WorkspaceProposalService(workspace,documents,Path.of(config.getSessionDirectory(),"agent-workspace",workspace.workspaceId()),"agent-ui");
            var proposed=proposals.propose(UUID.randomUUID().toString(),"approved project UI","file changes",inputs);
            if(token.isCancelled()||!currentPermission(AgentUiPermission.FILES))throw fail(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
            return proposals.apply((String)proposed.get("id"),((Number)proposed.get("revision")).intValue());
        },(job,result,error)->{if(!current())return;editor.showMessage(error==null?"Agent project files: "+result:"Agent project file operation failed");if(editor.fileExplorer!=null)editor.treeGitController.showFileTree(root.toString());});
    }
    private boolean currentPermission(AgentUiPermission permission){try{var task=new java.util.concurrent.FutureTask<Boolean>(() -> current()&&permitted(permission));SwingUtilities.invokeLater(task);return task.get(1,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception unavailable){return false;}}
    @Override public void close(){closed=true;session.close();created.clear();}
    private static WorkspaceApiException fail(WorkspaceApiException.Code code){return new WorkspaceApiException(code,"Project UI interaction unavailable: "+code);}
}
