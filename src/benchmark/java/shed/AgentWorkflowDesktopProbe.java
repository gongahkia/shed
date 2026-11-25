package shed;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Opt-in actual workbench acceptance; all files and preferences must belong to the disposable fixture. */
public final class AgentWorkflowDesktopProbe {
    private static Texteditor editor;
    private static final java.util.concurrent.atomic.AtomicReference<Throwable> uncaught=new java.util.concurrent.atomic.AtomicReference<>();
    private static Path folder;
    private static final List<Map<String,Object>> events = new ArrayList<>();
    private static <T> T edt(Callable<T> action) throws Exception {
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier ready,int seconds) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!edt(ready::getAsBoolean)){
            if(System.nanoTime()>until)throw new IllegalStateException("Live acceptance phase timed out");Thread.sleep(25);
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static <T extends Component> T find(Container parent,Class<T> kind,String name){
        for(Component child:parent.getComponents()){
            if(kind.isInstance(child)&&name.equals(child.getName()))return kind.cast(child);
            if(child instanceof Container nested){T match=find(nested,kind,name);if(match!=null)return match;}
        }
        return null;
    }
    private static WorkspaceBridgeServer attachment() throws Exception {
        var field=AgentWorkspaceController.class.getDeclaredField("active");field.setAccessible(true);
        var owner=Texteditor.class.getDeclaredField("agentWorkspaceController");owner.setAccessible(true);
        return (WorkspaceBridgeServer)field.get(owner.get(editor));
    }
    private static void capture(String name) throws Exception {
        edt(()->{
            check(editor.isShowing(),"Workbench is not showing");var root=editor.getRootPane();
            check(root.getWidth()>0&&root.getHeight()>0,"Workbench geometry unavailable");
            root.paintImmediately(root.getVisibleRect());
            var image=SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),16_000_000);
            ImageIO.write(image,"png",folder.resolve(name+".png").toFile());return null;
        });
    }
    private static Map<String,Object> control(WorkspaceBridgeClient client,String method,Map<String,Object> extra) throws Exception {
        var context=ShedControlCli.result(client.exchange(client.navigationContextRequest()));
        var params=ShedControlCli.context(context);params.putAll(extra);
        try{return ShedControlCli.result(client.exchange(ShedControlCli.request(method,params)));}
        catch(WorkspaceApiException failure){throw new IllegalStateException(method+": "+failure.code(),failure);}
    }
    private static long[] inputMetric() throws Exception {
        synchronized(editor.perfService){
            var field=PerfService.class.getDeclaredField("metrics");field.setAccessible(true);
            Object row=((Map<?,?>)field.get(editor.perfService)).get("edit.input-to-paint");if(row==null)return new long[]{0,0};
            var count=row.getClass().getDeclaredField("count");count.setAccessible(true);
            var last=row.getClass().getDeclaredField("lastNanos");last.setAccessible(true);return new long[]{count.getLong(row),last.getLong(row)};
        }
    }
    private static void animation() throws Exception {
        var writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(var output=ImageIO.createImageOutputStream(folder.resolve("agent-workflow.gif").toFile())){
            writer.setOutput(output);writer.prepareWriteSequence(null);
            var frames=new ArrayList<Path>();
            for(int i=0;i<8;i++)frames.add(folder.resolve("02-pending-"+i+".png"));
            frames.add(folder.resolve("03-review.png"));
            for(int i=0;i<8;i++)frames.add(folder.resolve("04-applied-"+i+".png"));
            for(Path frame:frames){
                var image=ImageIO.read(frame.toFile());var type=javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image);
                var metadata=writer.getDefaultImageMetadata(type,writer.getDefaultWriteParam());
                var tree=(javax.imageio.metadata.IIOMetadataNode)metadata.getAsTree("javax_imageio_gif_image_1.0");
                var control=(javax.imageio.metadata.IIOMetadataNode)tree.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime",frame.getFileName().toString().equals("03-review.png")?"100":"10");
                control.setAttribute("disposalMethod","none");
                metadata.setFromTree("javax_imageio_gif_image_1.0",tree);
                writer.writeToSequence(new javax.imageio.IIOImage(image,null,metadata),writer.getDefaultWriteParam());
            }
            writer.endWriteSequence();
        } finally {writer.dispose();}
    }
    private static void qualifyMotion(Path project)throws Exception {
        edt(()->{editor.toolWindowHost.watchAgentChanges("stop","Codex");return null;});
        Files.writeString(project.resolve(".gitignore"),"ignored-build/\n");Files.createDirectories(project.resolve(".github/workflows"));Files.writeString(project.resolve(".github/workflows/build.yml"),"name: Before\n");
        Files.writeString(project.resolve(".env"),"synthetic private value");Files.writeString(project.resolve("DeleteFlash.txt"),"before deletion\n");
        Path old=Files.createDirectories(project.resolve("before-folder"));String body="shared first line\nshared second line\nshared third line\nshared fourth line\nshared fifth line\n";
        Files.writeString(old.resolve("one.txt"),body);Files.writeString(old.resolve("two.txt"),body+"second file\n");
        StringBuilder model=new StringBuilder("class ModelBlocks {\n");for(int line=0;line<220;line++){if(line==5)model.append("static String alpha = \"BEFORE_ALPHA\";\n");else if(line==100)model.append("static String beta = \"BEFORE_BETA\";\n");else if(line==200)model.append("static String gamma = \"BEFORE_GAMMA\";\n");else model.append("// checkpoint ").append(line).append('\n');}model.append("}\n");Files.writeString(project.resolve("ModelBlocks.java"),model);
        edt(()->{editor.treeGitController.showFileTree(project.toString());var content=editor.resolveTreeContentPaneForOpen();editor.activateEditorPane(content);content.getTextArea().requestFocusInWindow();editor.openFileAsync(project.resolve("ModelBlocks.java").toFile());return null;});
        await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().getName().equals("ModelBlocks.java"),10);
        var modelPane=edt(editor::getActivePane);
        edt(()->{editor.toolWindowHost.watchAgentChanges("start","Codex");editor.toolWindowHost.watchAgentChanges("pause","Codex");return null;});
        await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")),20);
        Files.writeString(project.resolve(".gitignore"),"ignored-build/\nlocal-output/\n");Files.writeString(project.resolve(".github/workflows/build.yml"),"name: After\n");Files.writeString(project.resolve(".env"),"changed synthetic private value");
        Files.move(old,project.resolve("after-folder"));Files.writeString(project.resolve("after-folder/one.txt"),body+"edited after moving\n");Files.delete(project.resolve("DeleteFlash.txt"));Files.writeString(project.resolve("AddedFlash.txt"),"new file\n");
        try{await(()->((Number)editor.toolWindowHost.watchAgentChanges("state","Codex").get("files")).intValue()==6,15);}catch(Exception failure){System.out.println(edt(()->watchDiagnostics()));throw failure;}
        await(()->{
            var tree=find(editor.getRootPane(),JTree.class,"agent.explorer");if(tree==null)return false;
            var modelTree=(FileTreeModel)tree.getModel();var nodes=modelTree.rootNode().breadthFirstEnumeration();boolean deleted=false,config=false;
            while(nodes.hasMoreElements()){var node=(FileTreeModel.Node)nodes.nextElement();String name=node.file().getName();if(name.equals("DeleteFlash.txt"))deleted=true;if(name.equals(".gitignore"))config=true;check(!name.equals(".env"),"Private file appeared in observed explorer");}
            return deleted&&config;
        },10);
        Files.writeString(project.resolve("after-folder/one.txt"),body+"edited after moving\nlater observed edit\n");
        await(()->{var list=find(editor.getRootPane(),JList.class,"agent.changedFiles");for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i) instanceof ExternalChangeSnapshot.Change row&&row.path().equals("after-folder/one.txt")&&row.after().contains("later observed edit"))return true;return false;},10);
        capture("16-config-folder-moves-tree-pulse");
        edt(()->{var tree=find(editor.getRootPane(),JTree.class,"agent.explorer");var nodes=((FileTreeModel)tree.getModel()).rootNode().breadthFirstEnumeration();while(nodes.hasMoreElements()){var node=(FileTreeModel.Node)nodes.nextElement();if(node.file().getName().equals("DeleteFlash.txt")){tree.setSelectionPath(new javax.swing.tree.TreePath(node.getPath()));tree.getActionMap().get("shed.tree.open").actionPerformed(new java.awt.event.ActionEvent(tree,0,"open"));break;}}return null;});
        await(()->find(editor.getRootPane(),JTextPane.class,"agent.liveDiff").getText().contains("before deletion"),5);check(!Files.exists(project.resolve("DeleteFlash.txt")),"Deleted tree row recreated a source file");
        edt(()->{check(ExternalChangeSnapshot.moveSummary(java.util.Collections.list(((javax.swing.DefaultListModel<ExternalChangeSnapshot.Change>)find(editor.getRootPane(),JList.class,"agent.changedFiles").getModel()).elements())).contains("2 files"),"Folder move summary missing");return null;});
        events.add(Map.of("phase","safe_configs_moves_tree_badges","changes",6,"exact",true));
        // the Git state remains M while its selected patch changes twice
        Files.writeString(project.resolve("Welcome.txt"),"Git page first\n");
        edt(()->{editor.toolWindowHost.showTab(ToolWindowHost.Tab.GIT);return null;});
        await(()->{var table=find(editor.getRootPane(),JTable.class,"git.changeFiles");if(table==null)return false;for(int row=0;row<table.getRowCount();row++)if(String.valueOf(table.getValueAt(row,2)).equals("Welcome.txt")){table.setRowSelectionInterval(row,row);return true;}return false;},15);
        await(()->find(editor.getRootPane(),JTextPane.class,"git.changeDiff").getText().contains("Git page first"),10);
        Files.writeString(project.resolve("Welcome.txt"),"Git page second\n");
        await(()->find(editor.getRootPane(),JTextPane.class,"git.changeDiff").getText().contains("Git page second"),15);
        capture("17-git-summary-refreshed-patch");events.add(Map.of("phase","git_same_status_patch_refresh","exact",true));
        edt(()->{editor.activateEditorPane(modelPane);modelPane.getTextArea().requestFocusInWindow();editor.toolWindowHost.watchAgentChanges("stop","Codex");return null;});
    }
    private static void qualifyEditorFirst(Path project)throws Exception {
        edt(()->{
            editor.setSize(1400,900);check(!editor.toolWindowHost.isVisible(),"Agent tools visible at startup");
            editor.toolWindowHost.restoreWorkbench(Map.of("selected","AGENT","visible",true,"ratio",.5));check(!editor.toolWindowHost.isVisible(),"Saved agent panel reopened without a request");
            var tabs=find(editor.getRootPane(),JTabbedPane.class,"workbench.tools");for(int i=0;i<tabs.getTabCount();i++)check(!tabs.getTitleAt(i).equals("Agents"),"Agent tab exposed before a request");
            editor.workspaceController.addFolder(project,true);editor.handleAgentCommand("watch");check(!editor.toolWindowHost.isVisible(),"Watch opened management tools");return null;
        });
        await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Agent").get("ready")),20);
        String source="class EditorFirst {\n"+"// agent-created source block\n".repeat(240)+"}\n";Files.writeString(project.resolve("EditorFirst.java"),source);
        await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().getName().equals("EditorFirst.java")&&editor.writingArea.getClientProperty("shed.agentTour")!=null,15);
        edt(()->{check(!editor.toolWindowHost.isVisible(),"Observed edit opened management tools");return null;});capture("00-editor-first-agent-change");
        var area=edt(()->editor.writingArea);String before=edt(area::getText);int dot=edt(()->area.getCaret().getDot()),mark=edt(()->area.getCaret().getMark());long revision=edt(()->editor.getCurrentBuffer().documentRevision());
        edt(()->{
            var scroll=editor.getActivePane().getScrollPane();check(scroll.getUI() instanceof SmoothScrollPaneUI,"Source missing shared smooth scroll UI");
            scroll.getVerticalScrollBar().setValue(200);scroll.dispatchEvent(new java.awt.event.MouseWheelEvent(scroll,java.awt.event.MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,20,20,0,false,java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));
            check(Boolean.TRUE.equals(scroll.getClientProperty("shed.smoothScrollActive")),"Native scroll did not animate");return null;
        });
        await(()->editor.getActivePane().getScrollPane().getClientProperty("shed.smoothScrollActive")==null,3);
        edt(()->{check(editor.getActivePane().getScrollPane().getVerticalScrollBar().getValue()>200,"Smooth scroll did not settle");check(area.getText().equals(before)&&area.getCaret().getDot()==dot&&area.getCaret().getMark()==mark&&editor.getCurrentBuffer().documentRevision()==revision,"Scroll altered source or human selection");check(!editor.toolWindowHost.isVisible(),"Manual input exposed agent tools");editor.toolWindowHost.watchAgentChanges("stop","Agent");editor.toolWindowHost.watchAgentChanges("follow","Agent");return null;});
        capture("00-editor-first-smooth-scroll");
        edt(()->{
            editor.handleAgentCommand("changes");check(editor.toolWindowHost.isVisible(),"Explicit change controls did not open");
            editor.configManager.set("agent.fixture_acp.command","fixture-acp-not-executed");editor.toolWindowHost.showAgentAction(AgentAction.ASK);editor.toolWindowHost.refreshActive();
            var providers=find(editor.getRootPane(),JComboBox.class,"agent.session.provider");check(providers!=null,"ACP selector missing after explicit request");var names=new ArrayList<String>();for(int i=0;i<providers.getItemCount();i++)names.add(String.valueOf(providers.getItemAt(i)));
            check(names.contains("opencode")&&names.contains("fixture_acp"),"Configured ACP provider not listed");editor.configManager.set("agent.fixture_acp.command","");editor.toolWindowHost.showAgentChanges();return null;
        });
        int scrolls=edt(()->verifyScrollPanes(editor.getRootPane()));check(scrolls>=20,"Missing standard scroll surfaces in native qualification");
        events.add(Map.of("phase","editor_first_quiet_watch_smooth_scroll_and_opt_in_controls","exact",true,"standard_scrollpanes",scrolls));
    }
    private static int verifyScrollPanes(Container root){
        int count=0;for(Component child:root.getComponents()){
            if(child instanceof JScrollPane scroll){check(scroll.getUI() instanceof SmoothScrollPaneUI,"A standard scroll pane lacks shared motion: "+scroll.getName());count++;}
            if(child instanceof Container nested)count+=verifyScrollPanes(nested);
        }return count;
    }
    private static void qualifyDelayedTourReload(Path project)throws Exception {
        String before=Files.readString(project.resolve("ModelBlocks.java"));String changed=before.replace("BEFORE_ALPHA","DELAYED_ALPHA").replace("BEFORE_BETA","DELAYED_BETA").replace("BEFORE_GAMMA","DELAYED_GAMMA");
        edt(()->{editor.reloadPromptActive=true;editor.toolWindowHost.watchAgentChanges("start","Codex");editor.toolWindowHost.watchAgentChanges("follow","Codex");return null;});
        try{
            await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")),20);Files.writeString(project.resolve("ModelBlocks.java"),changed);
            await(()->{try{var pending=LiveChangesPanel.class.getDeclaredField("pendingTour");pending.setAccessible(true);return pending.get(livePanel())!=null;}catch(Exception failure){throw new IllegalStateException(failure);}},12);
            edt(()->{check(editor.getCurrentBuffer().getContent().equals(before),"Reload barrier did not hold source");editor.reloadPromptActive=false;editor.checkForExternalChanges();return null;});
            await(()->editor.writingArea.getClientProperty("shed.agentTour")!=null,5);
            edt(()->{check(editor.getCurrentBuffer().getContent().equals(changed),"Tour preceded authoritative reload");editor.toolWindowHost.watchAgentChanges("pause","Codex");editor.toolWindowHost.watchAgentChanges("stop","Codex");return null;});
            Files.writeString(project.resolve("ModelBlocks.java"),before);edt(()->{editor.checkForExternalChanges();check(editor.getCurrentBuffer().getContent().equals(before),"Fixture restore failed");return null;});
            events.add(Map.of("phase","observation_before_authoritative_reload","exact",true));
        }finally{edt(()->{editor.reloadPromptActive=false;return null;});}
    }
    private static boolean tourSettled(JTextArea area,String marker){
        try{var bounds=area.modelToView2D(area.getText().indexOf(marker));var viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,area);if(bounds==null||viewport==null)return false;int target=Math.max(0,Math.min(area.getHeight()-viewport.getExtentSize().height,(int)bounds.getY()-viewport.getExtentSize().height/3));return Math.abs(viewport.getViewPosition().y-target)<3;}catch(javax.swing.text.BadLocationException unavailable){return false;}
    }
    private static void qualifyModelTour(Path project)throws Exception {
        var area=edt(()->editor.writingArea);var viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,area);
        try{await(()->Integer.valueOf(1).equals(area.getClientProperty("shed.agentTour")),100);}catch(Exception failure){System.out.println(edt(()->editor.toolWindowHost.watchAgentChanges("state","Codex")+" active="+editor.getCurrentBuffer().getFile()+" loaded="+area.getText().contains("AFTER_GAMMA")+" activity="+area.getClientProperty("shed.agentActivity")));throw failure;}check(edt(()->!editor.toolWindowHost.isVisible()),"Model edits reopened management tools");String expected=edt(area::getText);var buffer=edt(editor::getCurrentBuffer);long revision=edt(buffer::documentRevision);boolean dirty=edt(buffer::isModified),undo=edt(()->buffer.getUndoManager().canUndo());int caret=edt(area::getCaretPosition),selection=edt(area::getSelectionStart);await(()->tourSettled(area,"AFTER_ALPHA"),2);int first=edt(()->viewport.getViewPosition().y);capture("18-model-tour-first-block");
        await(()->Integer.valueOf(2).equals(area.getClientProperty("shed.agentTour")),5);await(()->tourSettled(area,"AFTER_BETA"),2);int middle=edt(()->viewport.getViewPosition().y);check(middle>first+200,"Tour did not scroll to middle block");capture("19-model-tour-middle-block");
        await(()->Integer.valueOf(3).equals(area.getClientProperty("shed.agentTour")),5);await(()->tourSettled(area,"AFTER_GAMMA"),2);int last=edt(()->viewport.getViewPosition().y);check(last>middle+200,"Tour did not scroll to final block");capture("20-model-tour-last-block");await(()->area.getClientProperty("shed.agentTour")==null,5);
        edt(()->{check(editor.getCurrentBuffer()==buffer&&buffer.documentRevision()==revision&&buffer.isModified()==dirty&&buffer.getUndoManager().canUndo()==undo&&area.getText().equals(expected)&&area.getCaretPosition()==caret&&area.getSelectionStart()==selection,"Tour changed source, revision, history or human caret");return null;});
        events.add(Map.of("phase","actual_model_three_block_scroll_tour","caret_preserved",true,"exact",true,"scroll_y",List.of(first,middle,last)));
        awaitFile(folder.resolve("codex-finished"),100);check(Files.readString(folder.resolve("codex-finished")).equals("ok"),"Live Codex execution failed");check(expected.equals(Files.readString(project.resolve("ModelBlocks.java"))),"Tour did not show exact model source");
        Files.writeString(project.resolve("ModelBlocks.java"),expected.replace("AFTER_ALPHA","SECOND_ALPHA").replace("AFTER_BETA","SECOND_BETA").replace("AFTER_GAMMA","SECOND_GAMMA"));
        await(()->area.getClientProperty("shed.agentTour")!=null,12);
        edt(()->{var scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,area);scroll.dispatchEvent(new java.awt.event.MouseWheelEvent(scroll,java.awt.event.MouseWheelEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,20,20,0,false,java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));check(area.getClientProperty("shed.agentTour")==null,"Human wheel did not cancel tour");check(!Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("following")),"Wheel did not pause Follow");return null;});
        events.add(Map.of("phase","manual_scroll_cancels_native_tour","exact",true));
    }
    private static void qualifyEdges(Path project)throws Exception {
        edt(()->{editor.toolWindowHost.watchAgentChanges("stop","Codex");return null;});
        Path bulk=Files.createDirectories(project.resolve("qualification"));
        for(int i=0;i<5000;i++)Files.writeString(bulk.resolve("source-"+i+".txt"),"source "+i+"\n");
        String large="source line\n".repeat(20000);Files.writeString(bulk.resolve("large.txt"),large);
        long started=System.nanoTime();
        edt(()->{find(editor.getRootPane(),JComboBox.class,"agent.changeBaseline").setSelectedIndex(0);editor.toolWindowHost.watchAgentChanges("start","Codex");editor.toolWindowHost.watchAgentChanges("pause","Codex");return null;});
        await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")),20);
        double baselineMs=(System.nanoTime()-started)/1e6;
        started=System.nanoTime();
        for(int i=0;i<40;i++){
            Path temporary=Files.writeString(bulk.resolve(".save.tmp"),"final version "+i+"\n");
            Files.move(temporary,bulk.resolve("source-0.txt"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        for(int i=1;i<=100;i++)Files.writeString(bulk.resolve("source-"+i+".txt"),"changed "+i+"\n");
        for(int i=101;i<=120;i++)Files.delete(bulk.resolve("source-"+i+".txt"));
        for(int i=121;i<=140;i++)Files.move(bulk.resolve("source-"+i+".txt"),bulk.resolve("renamed-"+i+".txt"));
        Files.writeString(bulk.resolve("large.txt"),large+"large file changed\n");
        await(()->((Number)editor.toolWindowHost.watchAgentChanges("state","Codex").get("files")).intValue()==142&&Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")),20);
        double convergeMs=(System.nanoTime()-started)/1e6;
        check(Files.readString(bulk.resolve("source-0.txt")).equals("final version 39\n"),"Atomic save final bytes differ");
        events.add(Map.of("phase","5000_file_atomic_burst","baseline_ms",baselineMs,"converge_ms",convergeMs,"changes",142,"exact",true));
        measureQualifiedTyping();
        // a genuine human/agent collision on the same authoritative buffer
        FileBuffer human=edt(editor::getCurrentBuffer);String original=edt(human::getContent);
        edt(()->{human.getDocument().insertString(0,"// Human unsaved draft\n",null);return null;});
        String draft=edt(human::getContent);long revision=edt(human::documentRevision);
        Files.writeString(human.getFile().toPath(),original+"// External same-file write\n");
        chooseConflict("View Both");
        await(()->editor.getCurrentBuffer().isScratch()&&editor.getCurrentBuffer().getContent().contains("===== DISK VERSION ====="),5);
        edt(()->{
            editor.checkForExternalChanges();
            check(Arrays.stream(editor.getOwnedWindows()).noneMatch(w->w.isShowing()&&w instanceof JDialog d&&"External Change Conflict".equals(d.getTitle())),"Same disk version repeated its conflict prompt");
            check(human.getContent().equals(draft)&&human.documentRevision()==revision&&human.isModified(),"Conflict altered human draft");
            try{human.save();throw new AssertionError("Ordinary save overwrote external edit");}catch(java.io.IOException expected){}
            human.getUndoManager().undo();check(human.getContent().equals(original),"Conflict lost exact human Undo");
            human.getUndoManager().redo();check(human.getContent().equals(draft),"Conflict lost exact human Redo");
            return null;
        });
        check(Files.readString(human.getFile().toPath()).equals(original+"// External same-file write\n"),"Conflict modified disk");
        Files.writeString(human.getFile().toPath(),original+"// External same-file write\n// New disk version\n");
        chooseConflict("View Both");
        edt(()->{check(human.getContent().equals(draft)&&human.isModified(),"New conflict lost human draft");try{human.save();throw new AssertionError("New disk version was silently accepted");}catch(java.io.IOException expected){}return null;});
        capture("10-same-file-conflict-both-versions");
        edt(()->{human.load(editor.configManager);editor.loadBufferIntoEditor(human);return null;});
        events.add(Map.of("phase","same_file_conflict_view_both_save_undo_redo","exact",true));
        delayedFollow(project,human);
        String source=edt(human::getContent);long sourceRevision=edt(human::documentRevision);boolean dirtyBeforeVisuals=edt(human::isModified);
        edt(()->{editor.splitWindow(true);editor.splitWindow(false);return null;});
        for(String theme:List.of("shed-light","dracula")){
            edt(()->{editor.setThemeFromCommand(theme);editor.setSize(theme.equals("dracula")?850:1400,900);editor.configManager.set("ui.density",theme.equals("dracula")?"compact":"comfortable");editor.sessionConfigController.applySetting("ui.density");return null;});
            edt(()->null);capture(theme.equals("dracula")?"12-dark-narrow-splits":"11-light-comfortable-splits");
            edt(()->{check(human.getContent().equals(source)&&human.documentRevision()==sourceRevision&&human.isModified()==dirtyBeforeVisuals,"Visual changes modified source");for(var pane:editor.editorPanes)check(pane.getBuffer()==human,"Split lost document owner");return null;});
        }
        edt(()->{editor.commandHandler.execute("focus on");editor.commandHandler.execute("center on");return null;});
        capture("13-focus-centered-live-changes");
        edt(()->{editor.commandHandler.execute("center off");editor.commandHandler.execute("focus off");editor.setThemeFromCommand("shed-light");editor.setSize(1400,900);return null;});
        events.add(Map.of("phase","light_dark_narrow_density_focus_three_splits","exact",true));
        var watches=new ArrayList<java.lang.ref.WeakReference<Object>>();long heapBefore=usedHeap();
        edt(()->{
            var presentation=editor.getActivePane().agentPresentation();int listeners=editor.writingArea.getHierarchyListeners().length;
            int dot=editor.writingArea.getCaret().getDot(),mark=editor.writingArea.getCaret().getMark();
            for(int cycle=0;cycle<24;cycle++){presentation.showApplied(0,5);presentation.clear();}
            check(editor.writingArea.getHierarchyListeners().length==listeners&&editor.writingArea.getCaret().getDot()==dot&&editor.writingArea.getCaret().getMark()==mark,"Animation cycles changed caret/listeners");
            presentation.showApplied(0,5);return null;
        });
        await(()->editor.writingArea.getClientProperty("shed.agentActivity")==null,5);
        events.add(Map.of("phase","animation_cycles_and_real_expiration","cycles",24,"exact",true));
        var fontKeys=List.of("font.family","font.size","ui.font.family","ui.font.size");var fonts=new HashMap<String,String>();
        edt(()->{fontKeys.forEach(key->fonts.put(key,editor.configManager.get(key)));editor.configManager.set("font.family","Monospaced");editor.configManager.set("font.size","18");editor.configManager.set("ui.font.family","Dialog");editor.configManager.set("ui.font.size","15");editor.sessionConfigController.applySetting("font.family");return null;});
        edt(()->null);capture("15-custom-fonts-and-live-diff");
        edt(()->{
            check(human.getContent().equals(source)&&human.documentRevision()==sourceRevision&&human.isModified()==dirtyBeforeVisuals,"Custom fonts changed document state");
            var area=editor.writingArea;var point=area.modelToView2D(4);check(point!=null&&area.viewToModel2D(new java.awt.geom.Point2D.Double(point.getX(),point.getY()+point.getHeight()/2))==4,"Custom-font source hit test changed");
            fontKeys.forEach(key->editor.configManager.set(key,fonts.get(key)));editor.sessionConfigController.applySetting("font.family");return null;
        });
        events.add(Map.of("phase","custom_ui_editor_fonts_hit_testing","exact",true));
        for(int i=0;i<12;i++){
            edt(()->{editor.toolWindowHost.watchAgentChanges("start","Codex");return null;});
            await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")),20);
            watches.add(edt(()->new java.lang.ref.WeakReference<>(activeWatch())));
            edt(()->{editor.toolWindowHost.watchAgentChanges("stop","Codex");return null;});
        }
        edt(()->null);
        for(int i=0;i<12&&watches.stream().anyMatch(ref->ref.get()!=null);i++){System.gc();Thread.sleep(50);edt(()->null);}
        long retained=watches.stream().filter(ref->ref.get()!=null).count();check(retained==0,"Stopped watcher retained after bounded diagnostic GC: "+retained);
        events.add(Map.of("phase","watch_start_stop_cycles","cycles",12,"retained_watchers",retained,"heap_before",heapBefore,"heap_after_diagnostic_gc",usedHeap(),"exact",true));
        boolean motion="1".equals(System.getenv("SHED_CONFIG_MOTION"));if(motion){qualifyMotion(project);qualifyDelayedTourReload(project);}
        if("1".equals(System.getenv("SHED_LIVE_CODEX"))){
            edt(()->{if(!motion){human.load(editor.configManager);editor.loadBufferIntoEditor(human);}editor.toolWindowHost.watchAgentChanges("start","Codex");editor.toolWindowHost.watchAgentChanges("follow","Codex");return null;});
            await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")),20);
            if(motion)edt(()->{editor.hideToolWindow();return null;});
            Files.writeString(folder.resolve("codex-ready"),"ready");
            if(motion){qualifyModelTour(project);return;}
            awaitFile(folder.resolve("codex-finished"),100);
            check(Files.readString(folder.resolve("codex-finished")).equals("ok"),"Live Codex execution failed; inspect runner evidence");
            await(()->((Number)editor.toolWindowHost.watchAgentChanges("state","Codex").get("files")).intValue()>=4,15);
            check(Files.readString(project.resolve("Demo.java")).contains("LIVE_CODEX_EDIT"),"Live Codex source modification missing");
            check(Files.readString(project.resolve("AgentCreated.txt")).equals("Created by live Codex\n")&&!Files.exists(project.resolve("AgentDelete.txt")),"Live Codex create/delete missing");
            check(!Files.exists(project.resolve("AgentOld.txt"))&&Files.readString(project.resolve("AgentRenamed.txt")).equals("renamed by live Codex\n"),"Live Codex rename missing");
            edt(()->{var list=find(editor.getRootPane(),JList.class,"agent.changedFiles");for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i) instanceof ExternalChangeSnapshot.Change row&&row.path().equals("Demo.java")){list.setSelectedIndex(i);break;}return null;});
            await(()->find(editor.getRootPane(),JTextPane.class,"agent.liveDiff").getText().contains("LIVE_CODEX_EDIT"),10);
            capture("14-real-codex-filesystem-session");
            events.add(Map.of("phase","actual_codex_cli_external_edits","exact",true));
        }
    }
    private static void measureQualifiedTyping()throws Exception {
        var samples=new ArrayList<Double>();String source=edt(()->editor.getCurrentBuffer().getContent());
        for(int i=0;i<21;i++){
            long count=inputMetric()[0];
            edt(()->{editor.setMode(EditorMode.INSERT);editor.writingArea.requestFocusInWindow();editor.writingArea.setCaretPosition(0);editor.editorUiController.noteEditorKey(new java.awt.event.KeyEvent(editor.writingArea,java.awt.event.KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,java.awt.event.KeyEvent.VK_UNDEFINED,'x'));editor.writingArea.getActionMap().get(javax.swing.text.DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new java.awt.event.ActionEvent(editor.writingArea,0,"x"));return null;});
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(inputMetric()[0]<=count){if(System.nanoTime()>until)throw new AssertionError("Qualified input paint unavailable");Thread.sleep(5);}
            samples.add(inputMetric()[1]/1e6);edt(()->{editor.getActivePane().surface().undo();return null;});
        }
        var warm=new ArrayList<>(samples.subList(1,samples.size()));Collections.sort(warm);double p95=warm.get(18);
        check(p95<=16,"5000-file watch typing exceeded 16 ms");check(edt(()->editor.getCurrentBuffer().getContent()).equals(source),"Qualified typing/Undo altered source");
        events.add(Map.of("phase","5000_file_watch_typing","first_ms",samples.getFirst(),"warm_p95_ms",p95,"samples_ms",samples));
    }
    private static void delayedFollow(Path project,FileBuffer human)throws Exception {
        Files.writeString(project.resolve("Delayed.java"),"class Delayed {}\n");
        await(()->{
            var list=find(editor.getRootPane(),JList.class,"agent.changedFiles");
            for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i) instanceof ExternalChangeSnapshot.Change row&&row.path().equals("Delayed.java")){list.setSelectedIndex(i);return true;}return false;
        },10);
        await(()->System.nanoTime()-editor.inputController.lastKeyPressNanos()>1_500_000_000L,4);
        var executor=AsyncJobService.class.getDeclaredField("executor");executor.setAccessible(true);
        int count=((java.util.concurrent.ThreadPoolExecutor)executor.get(editor.asyncJobService)).getCorePoolSize();
        var entered=new CountDownLatch(count);var release=new CountDownLatch(1);
        for(int i=0;i<count;i++)editor.asyncJobService.submit("Bounded delayed-open fixture",token->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Worker fixture deadline");return true;},(job,result,error)->{});
        check(entered.await(5,TimeUnit.SECONDS),"Workers did not reach controlled boundary");
        String before=edt(human::getContent);
        try{
            edt(()->{
                editor.toolWindowHost.watchAgentChanges("follow","Codex");
                int previous=editor.asyncJobService.list().stream().filter(job->job.getDescription().equals("open file")).mapToInt(AsyncJobService.JobSnapshot::getId).max().orElse(-1);
                var method=LiveChangesPanel.class.getDeclaredMethod("openSelection",boolean.class);method.setAccessible(true);method.invoke(livePanel(),true);
                check(editor.asyncJobService.list().stream().anyMatch(job->job.getId()>previous&&job.getDescription().equals("open file")),"Delayed Follow never queued an actual file load");
                human.getDocument().insertString(0,"// Human typed during load\n",null);return null;
            });
        }finally{release.countDown();}
        await(()->editor.asyncJobService.list().stream().filter(job->job.getDescription().equals("open file")).allMatch(job->job.getStatus()==AsyncJobService.Status.SUCCEEDED||job.getStatus()==AsyncJobService.Status.FAILED||job.getStatus()==AsyncJobService.Status.CANCELLED),10);
        edt(()->null);
        edt(()->{check(editor.getCurrentBuffer()==human,"Delayed Follow stole the human's active buffer");check(human.getContent().equals("// Human typed during load\n"+before),"Delayed Follow altered human source");human.getUndoManager().undo();check(human.getContent().equals(before),"Delayed Follow corrupted Undo");editor.toolWindowHost.watchAgentChanges("pause","Codex");return null;});
        events.add(Map.of("phase","human_edit_during_delayed_follow","exact",true));
    }
    private static long usedHeap(){return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();}
    private static String watchDiagnostics()throws Exception {
        var live=livePanel();var result=new StringBuilder(editor.toolWindowHost.watchAgentChanges("state","Codex").toString());
        for(String name:List.of("root","busy","dirty","generation","rootsGeneration","state","original","latest")){
            var field=LiveChangesPanel.class.getDeclaredField(name);field.setAccessible(true);Object value=field.get(live);
            if(value instanceof Map<?,?> map)value="files="+map.size()+" gitignore="+map.get(".gitignore")+" old="+map.containsKey("before-folder/one.txt")+" new="+map.containsKey("after-folder/one.txt");
            else if(value instanceof JLabel label)value=label.getText();result.append("\n").append(name).append("=").append(value);
        }
        Object watch=activeWatch();if(watch!=null)for(String name:List.of("root","directories","thread")){var field=TestWatchService.class.getDeclaredField(name);field.setAccessible(true);Object value=field.get(watch);if(value instanceof Map<?,?> map)value=map.values();if(value instanceof Thread thread)value=thread.getState()+" "+Arrays.toString(thread.getStackTrace());result.append("\nwatch.").append(name).append("=").append(value);}
        for(var entry:Thread.getAllStackTraces().entrySet())if(Arrays.stream(entry.getValue()).anyMatch(frame->frame.getClassName().contains("PollingWatchService")))result.append("\nprovider ").append(entry.getKey().getState()).append(" ").append(Arrays.toString(entry.getValue()));
        return result.toString();
    }
    private static Object activeWatch()throws Exception {
        var watcher=LiveChangesPanel.class.getDeclaredField("watcher");watcher.setAccessible(true);return watcher.get(livePanel());
    }
    private static LiveChangesPanel livePanel()throws Exception {
        var surfaces=ToolWindowHost.class.getDeclaredField("surfaces");surfaces.setAccessible(true);
        Object workbench=((Map<?,?>)surfaces.get(editor.toolWindowHost)).values().stream().filter(value->value instanceof AgentWorkbenchPanel).findFirst().orElseThrow();
        var live=AgentWorkbenchPanel.class.getDeclaredField("liveChanges");live.setAccessible(true);return (LiveChangesPanel)live.get(workbench);
    }
    private static void chooseConflict(String action)throws Exception {
        await(()->Arrays.stream(editor.getOwnedWindows()).anyMatch(w->w.isShowing()&&w instanceof JDialog d&&"External Change Conflict".equals(d.getTitle())),10);
        edt(()->{
            JDialog dialog=(JDialog)Arrays.stream(editor.getOwnedWindows()).filter(w->w.isShowing()&&w instanceof JDialog d&&"External Change Conflict".equals(d.getTitle())).findFirst().orElseThrow();
            JButton button=findByText(dialog,action);check(button!=null,"Existing conflict action missing");button.doClick();return null;
        });
    }
    private static JButton findByText(Container parent,String text){
        for(Component child:parent.getComponents()){if(child instanceof JButton button&&text.equals(button.getText()))return button;if(child instanceof Container nested){JButton found=findByText(nested,text);if(found!=null)return found;}}return null;
    }
    private static void awaitFile(Path path,int seconds)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!Files.exists(path)){if(System.nanoTime()>until)throw new AssertionError("External driver deadline: "+path.getFileName());Thread.sleep(25);}
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,failure)->{uncaught.compareAndSet(null,failure);failure.printStackTrace();});
        int exit=1;folder=Path.of(args[0]).toRealPath();Path project=Path.of(args[1]).toRealPath();
        Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        check(folder.startsWith(fixture.resolve("run"))&&project.startsWith(fixture.resolve("run")),"Unconfined desktop fixture");
        Thread watchdog=new Thread(()->{try{Thread.sleep("1".equals(System.getenv("SHED_WATCH_QUALIFICATION"))?300000:150000);Runtime.getRuntime().halt(124);}catch(InterruptedException done){}} ,"shed-live-test-deadline");
        watchdog.setDaemon(true);watchdog.start();
        try {
            editor=edt(()->new Texteditor(new String[]{project.resolve("Welcome.txt").toString()}));
            await(()->!editor.stagedStartupPending&&editor.getCurrentBuffer()!=null&&editor.isShowing(),30);
            qualifyEditorFirst(project);
            edt(()->{
                editor.setTitle("Shed — Agent workflow verification");editor.setSize(1400,900);
                editor.workspaceController.addFolder(project,true);editor.setMode(EditorMode.INSERT);editor.writingArea.requestFocusInWindow();
                editor.handleAgentCommand("api start control");return null;
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);WorkspaceBridgeServer server;
            while((server=edt(AgentWorkflowDesktopProbe::attachment))==null){if(System.nanoTime()>deadline)throw new IllegalStateException("Control attachment unavailable");Thread.sleep(25);}
            check(server.controlEnabled(),"Wrong attachment scope");
            Path ready=folder.resolve("ready.json");
            Files.writeString(ready,MiniJson.stringify(Map.of("descriptor",server.descriptor().toString(),"credential",server.credential().toString())));
            if(!"1".equals(System.getenv("SHED_WATCH_QUALIFICATION")))edt(()->{editor.hideToolWindow();return null;});
            capture("01-ready");System.out.println("LIVE_READY");System.out.flush();
            if("1".equals(System.getenv("SHED_WATCH_QUALIFICATION"))){
                edt(()->{editor.openFileAsync(project.resolve("Demo.java").toFile());return null;});
                await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().getName().equals("Demo.java"),10);
                qualifyEdges(project);
                edt(()->{editor.toolWindowHost.watchAgentChanges("stop","Codex");editor.handleAgentCommand("api stop");return null;});
                check(uncaught.get()==null,"Uncaught desktop exception: "+uncaught.get());
                events.add(Map.of("phase","save_and_attachment_cleanup","exact",true,"descendants",ProcessHandle.current().descendants().count()));
                Files.writeString(folder.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","events",events,"live_window",true,"capture","owned Swing root pane")));
                System.out.println("LIVE_PASS");exit=0;return;
            }
            await(()->editor.getCurrentBuffer().getFile().getName().equals("Demo.java"),75);
            capture("01-source-before-agent-activity");
Files.writeString(folder.resolve("opened-captured"),"ok");
            await(()->editor.writingArea.getClientProperty("shed.agentActivity")!=null,75);
            Color human=edt(()->editor.writingArea.getCaretColor());Color agent=edt(()->editor.getActivePane().agentPresentation().agentColor());
            check(!human.equals(agent),"Agent marker matches human caret color");
            for(int i=0;i<8;i++){capture("02-pending-"+i);Thread.sleep(100);}
            await(()->{JButton apply=find(editor.getContentPane(),JButton.class,"agent.apply");return apply!=null&&apply.isEnabled();},30);
            edt(()->{check(!editor.toolWindowHost.isVisible(),"Incoming proposal opened review without a request");editor.handleAgentCommand("proposals");check(editor.toolWindowHost.isVisible(),"Explicit proposal review did not open");return null;});
            JTextArea proposed=edt(()->find(editor.getContentPane(),JTextArea.class,"agent.proposed"));
            check(proposed!=null,"No actual review view");capture("03-review");
            Path expectedFile=folder.resolve("expected.txt");check(Files.isRegularFile(expectedFile),"Missing expected agent result");
            String expected=Files.readString(expectedFile);String before=edt(()->editor.getCurrentBuffer().getContent());
            check(!before.equals(expected),"Agent proposal changed source before human apply");
            check(edt(proposed::getText).equals(expected),"Review differs from agent proposal");
            Thread.sleep(1200);
            edt(()->{find(editor.getContentPane(),JButton.class,"agent.apply").doClick();return null;});
            await(()->editor.getCurrentBuffer().getContent().equals(expected),20);
            await(()->"Agent change applied".equals(editor.writingArea.getClientProperty("shed.agentActivity")),5);
            for(int i=0;i<8;i++){capture("04-applied-"+i);Thread.sleep(100);}
            edt(()->{editor.getActivePane().surface().undo();check(editor.getCurrentBuffer().getContent().equals(before),"Undo was not atomic");
                editor.getActivePane().surface().redo();check(editor.getCurrentBuffer().getContent().equals(expected),"Redo differs");return null;});
            events.add(Map.of("phase","review_apply_undo_redo","exact",true,"human_color",human.getRGB(),"agent_color",agent.getRGB()));
            var latencies=new ArrayList<Double>();
            for(int i=0;i<21;i++){
                long count=inputMetric()[0];
                edt(()->{editor.setMode(EditorMode.INSERT);editor.writingArea.requestFocusInWindow();editor.writingArea.setCaretPosition(0);
                    editor.editorUiController.noteEditorKey(new java.awt.event.KeyEvent(editor.writingArea,
                        java.awt.event.KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,java.awt.event.KeyEvent.VK_UNDEFINED,'x'));
                    editor.writingArea.getActionMap().get(javax.swing.text.DefaultEditorKit.defaultKeyTypedAction)
                        .actionPerformed(new java.awt.event.ActionEvent(editor.writingArea,0,"x"));return null;});
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(inputMetric()[0]<=count){if(System.nanoTime()>until)throw new IllegalStateException("Completed input paint unavailable");Thread.sleep(5);}
                latencies.add(inputMetric()[1]/1e6);edt(()->{editor.getActivePane().surface().undo();return null;});
            }
            var warm=new ArrayList<>(latencies.subList(1,latencies.size()));Collections.sort(warm);
            double p95=warm.get((int)Math.ceil(warm.size()*.95)-1);
            events.add(Map.of("phase","actual_window_typing","first_ms",latencies.getFirst(),"warm_p95_ms",p95,"samples_ms",latencies));
            check(p95<=16,"Typing p95 exceeded 16 ms");
            String save=edt(()->{check(editor.getCurrentBuffer().getContent().equals(expected),"Typing/Undo altered source");
                return editor.commandHandler.execute("w");});
            events.add(Map.of("phase","save_acknowledgement","message",Objects.toString(save,"")));
            long saved=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Files.readString(project.resolve("Demo.java")).equals(expected)) {
                if(System.nanoTime()>saved)throw new AssertionError("Save did not complete: "+save);Thread.sleep(20);
            }
            await(()->!editor.getCurrentBuffer().isModified(),5);
            check(Files.readString(project.resolve("Demo.java")).equals(expected),"Saved bytes differ");
            capture("05-saved");
            Files.writeString(project.resolve("Preview.md"),"# Agent preview\n\nA bounded preview in the real Shed workbench.\n");
            edt(()->{editor.writingArea.requestFocusInWindow();return null;});
            try(var client=WorkspaceBridgeClient.control(server.descriptor(),server.credential())) {
                control(client,"editor/diagnostics",Map.of());control(client,"editor/feedback",Map.of());
                control(client,"editor/agent/progress",Map.of("sessionId","live-progress","agent","Codex","phase","thinking","completed",1L,"total",2L,"start",0L,"end",5L));
                control(client,"editor/agent/cancel",Map.of("sessionId","live-progress"));
                if(!"1".equals(System.getenv("SHED_LIVE_VISUAL_ONLY"))){
                control(client,"editor/file/open",Map.of("path","Preview.md"));
                control(client,"editor/preview/open",Map.of());
                await(()->{var p=editor.markdownController.previewForAgent(editor.getActivePane());return p!=null&&p.currentForAgent()&&p.isShowing();},5);
                var observed=control(client,"editor/preview/capture",Map.of());
                var data=MiniJson.asObject(observed.get("observation"));
                Files.write(folder.resolve("06-preview-viewport.png"),Base64.getDecoder().decode((String)data.get("data")));
                events.add(Map.of("phase","scoped_feedback_progress_and_preview","exact",true,"width",data.get("width"),"height",data.get("height")));
                capture("06-preview-workbench");
                }else events.add(Map.of("phase","existing_html_preview_endpoint","status","EXCLUDED_FROM_VISUAL_ONLY"));
            }
            Files.writeString(project.resolve("DeleteMe.txt"),"delete fixture\n");Files.writeString(project.resolve("RenameMe.txt"),"rename fixture\n");
            edt(()->{editor.toolWindowHost.watchAgentChanges("start","Codex");return null;});
            await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")),10);
            Files.writeString(folder.resolve("watch-ready"),"ready");
            await(()->((Number)editor.toolWindowHost.watchAgentChanges("state","Codex").get("files")).intValue()==4,10);
            Files.writeString(folder.resolve("watch-first-batch"),"ready");
            await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().getName().equals("Demo.java")&&editor.getCurrentBuffer().getContent().endsWith("// Latest observed source block\n")&&"Agent change applied".equals(editor.writingArea.getClientProperty("shed.agentActivity")),10);
            edt(()->{editor.handleAgentCommand("changes");return null;});capture("07-live-external-changes");
            await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().getName().equals("Demo.java"),5);
            var watchedLatencies=new ArrayList<Double>();
            for(int i=0;i<20;i++){
                long count=inputMetric()[0];
                edt(()->{editor.setMode(EditorMode.INSERT);editor.writingArea.requestFocusInWindow();editor.writingArea.setCaretPosition(0);
                    editor.editorUiController.noteEditorKey(new java.awt.event.KeyEvent(editor.writingArea,java.awt.event.KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,java.awt.event.KeyEvent.VK_UNDEFINED,'x'));
                    editor.writingArea.getActionMap().get(javax.swing.text.DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new java.awt.event.ActionEvent(editor.writingArea,0,"x"));return null;});
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(inputMetric()[0]<=count){if(System.nanoTime()>until)throw new AssertionError("Watch-active paint unavailable");Thread.sleep(5);}
                watchedLatencies.add(inputMetric()[1]/1e6);edt(()->{editor.getActivePane().surface().undo();return null;});
            }
            var ordered=new ArrayList<>(watchedLatencies);Collections.sort(ordered);double watchedP95=ordered.get(18);check(watchedP95<=16,"Watch-active typing exceeded 16ms");
            events.add(Map.of("phase","watch_active_typing","p95_ms",watchedP95,"samples_ms",watchedLatencies));
            edt(()->{editor.toolWindowHost.watchAgentChanges("pause","Codex");return null;});
            edt(()->{check(!Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("following")),"Manual pause failed");editor.toolWindowHost.watchAgentChanges("follow","Codex");editor.getCurrentBuffer().getDocument().insertString(0,"// Human draft\n",null);return null;});
            var paused=edt(()->editor.getCurrentBuffer());Files.writeString(folder.resolve("watch-paused"),"ready");
            long observed=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.exists(folder.resolve("writer-finished"))){if(System.nanoTime()>observed)throw new AssertionError("External writer did not finish");Thread.sleep(25);}
            await(()->!Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("following")),5);
            edt(()->{check(editor.getCurrentBuffer()==paused,"Paused following stole the active document");check(!Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("following")),"Pause state lost");check(editor.getCurrentBuffer().getContent().startsWith("// Human draft"),"Human draft lost");editor.getActivePane().surface().undo();return null;});
            events.add(Map.of("phase","external_create_modify_delete_rename","exact",true,"files",4,"pause_preserved",true));
            if("1".equals(System.getenv("SHED_VISUAL_GIT_FIXTURE"))){
                edt(()->{var choice=find(editor.getRootPane(),JComboBox.class,"agent.changeBaseline");check(choice!=null,"Baseline selector absent");choice.setSelectedIndex(1);return null;});
                await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")),10);
                edt(()->{var list=find(editor.getRootPane(),JList.class,"agent.changedFiles");check(list!=null,"Changed files absent");for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i) instanceof ExternalChangeSnapshot.Change change&&change.path().equals("Demo.java")){list.setSelectedIndex(i);return null;}throw new AssertionError("Tracked fixture missing from Git changes");});
                await(()->{var view=find(editor.getRootPane(),JTextPane.class,"agent.liveDiff");return view!=null&&view.getText().contains("diff --git");},10);
                capture("09-git-working-tree-diff");events.add(Map.of("phase","real_disposable_git_diff","exact",true));
            }
            edt(()->{editor.toolWindowHost.watchAgentChanges("stop","Codex");return null;});
            edt(()->{editor.toolWindowHost.showAgentAction(AgentAction.ASK);return null;});
            capture("08-provider-logo-and-icons");
            animation();
            edt(()->{editor.handleAgentCommand("api stop");return null;});
            long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(Files.exists(server.descriptor())||Files.exists(server.credential())){if(System.nanoTime()>stop)throw new IllegalStateException("Endpoint cleanup failed");Thread.sleep(20);}
            events.add(Map.of("phase","save_and_attachment_cleanup","exact",true,"descendants",ProcessHandle.current().descendants().count()));
            check(uncaught.get()==null,"Uncaught desktop exception: "+uncaught.get());
            Files.writeString(folder.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","events",events,"live_window",true,"capture","owned Swing root pane")));
            System.out.println("LIVE_PASS");exit=0;
        } catch(Throwable failure){failure.printStackTrace();Files.writeString(folder.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",failure.toString(),"events",events)));}
        finally {
            if(editor!=null)edt(()->{editor.handleAgentCommand("api stop");editor.asyncJobService.shutdownNow();for(Window window:Window.getWindows())window.dispose();return null;});
            watchdog.interrupt();System.exit(exit);
        }
    }
}
