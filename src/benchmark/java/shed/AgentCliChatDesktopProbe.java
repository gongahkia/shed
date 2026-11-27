package shed;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Controlled real Swing + external Java CLI double; no shell, cloud account or desktop automation. */
public final class AgentCliChatDesktopProbe {
    static Texteditor editor;static Path output,project;
    static <T>T ui(Callable<T> call)throws Exception{var task=new FutureTask<T>(call);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);}
    static void await(BooleanSupplier test)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!ui(test::getAsBoolean)){if(System.nanoTime()>until)throw new AssertionError("Chat condition timed out: "+find(editor.getRootPane(),"agent.chat.status"));Thread.sleep(25);}}
    static Component find(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof JMenu menu)for(Component child:menu.getMenuComponents()){var found=find(child,name);if(found!=null)return found;}if(c instanceof Container p)for(Component child:p.getComponents()){var found=find(child,name);if(found!=null)return found;}return null;}
    static JDialog approval(){for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&"agent.approval".equals(d.getName()))return d;return null;}
    static JButton button(Component c,String title){if(c instanceof JButton b&&title.equals(b.getText()))return b;if(c instanceof Container p)for(Component child:p.getComponents()){var found=button(child,title);if(found!=null)return found;}return null;}
    static javax.swing.text.JTextComponent transcript(){return (javax.swing.text.JTextComponent)find(editor.getRootPane(),"agent.chat.transcript");}
    static JTextArea composer(){return (JTextArea)find(editor.getRootPane(),"agent.chat.composer");}
    static JButton send(){return (JButton)find(editor.getRootPane(),"agent.chat.send");}
    static List<String> javaCommand(int turn){return List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.awt.headless=true","-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),AgentCliChatDesktopProbe.class.getName(),"--fake-agent",Integer.toString(turn));}
    static void picture(String name)throws Exception{picture(name,editor.getRootPane());}
    static void picture(String name,JComponent root)throws Exception{var image=ui(()->SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),8_000_000));try{javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());}finally{image.flush();}}
    static void ctl(String...args)throws Exception{var argv=new ArrayList<String>(List.of("ctl"));argv.addAll(List.of(args));var out=new ByteArrayOutputStream();int exit=ShedControlCli.run(argv.toArray(String[]::new),InputStream.nullInputStream(),out,OutputStream.nullOutputStream());if(exit!=0)throw new AssertionError(out.toString(StandardCharsets.UTF_8));}
    static String sourceHash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    static Map<String,Object> inlineDemo()throws Exception{
        var metrics=new ArrayList<Map<String,Object>>();var original=ui(()->editor.writingArea.getText());int originalArea=ui(()->System.identityHashCode(editor.writingArea));
        var mutations=new ArrayList<Map<String,Object>>();var auditDocument=ui(()->editor.writingArea.getDocument());var audit=new javax.swing.event.DocumentListener(){void record(javax.swing.event.DocumentEvent e){if(mutations.size()<12)mutations.add(Map.of("event",e.getType().toString(),"offset",e.getOffset(),"length",e.getLength(),"stack",Arrays.stream(Thread.currentThread().getStackTrace()).limit(24).map(Object::toString).toList()));}public void insertUpdate(javax.swing.event.DocumentEvent e){record(e);}public void removeUpdate(javax.swing.event.DocumentEvent e){record(e);}public void changedUpdate(javax.swing.event.DocumentEvent e){record(e);}};ui(()->{auditDocument.addDocumentListener(audit);return null;});
        try{
        ui(()->{
            if(Boolean.parseBoolean(editor.configManager.get("ai.completion.enabled","false")))throw new AssertionError("Completion must default off");
            var controllerField=Texteditor.class.getDeclaredField("inlineCompletionController");controllerField.setAccessible(true);var controller=controllerField.get(editor);
            var sessionsField=InlineCompletionController.class.getDeclaredField("sessions");sessionsField.setAccessible(true);var sessions=(Map<?,?>)sessionsField.get(controller);var session=(InlineCompletionSession)sessions.get(editor.writingArea);
            var providerField=InlineCompletionSession.class.getDeclaredField("provider");providerField.setAccessible(true);providerField.set(session,(java.util.function.BiFunction<String,String,CompletableFuture<String>>)(prefix,suffix)->CompletableFuture.completedFuture("nswer();\n"));
            session.onTiming((stage,nanos)->metrics.add(Map.of("stage",stage,"ms",nanos/1e6)));
            var sourceUI=editor.writingArea.getUI();((JCheckBoxMenuItem)find(editor.getJMenuBar(),"editor.inlineSuggestions")).doClick();if(editor.writingArea.getUI()!=sourceUI)throw new AssertionError("Enabling suggestions replaced the source UI");
            editor.setMode(EditorMode.INSERT);((javax.swing.text.JTextComponent)find(editor.getRootPane(),"agent.chat.status")).setText("Completion demo: click the source editor, then watch without typing until it finishes.");editor.requestActivePaneFocus();return null;
        });try{await(()->editor.writingArea.hasFocus());}catch(AssertionError timeout){Files.writeString(output.resolve("focus-state.json"),MiniJson.stringify(ui(()->Map.of("active",editor.isActive(),"focused",editor.isFocused(),"source_showing",editor.writingArea.isShowing(),"source_editable",editor.writingArea.isEditable(),"focus_owner",KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()==null?"none":KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner().getClass().getName()))));throw timeout;}
        Files.writeString(output.resolve("completion-baselines.json"),MiniJson.stringify(ui(()->Map.of("before_focus_sha256",sourceHash(original),"after_focus_sha256",sourceHash(editor.writingArea.getText()),"original_area",originalArea,"focused_area",System.identityHashCode(editor.writingArea),"buffer",editor.getActivePane().getBuffer().getDisplayName()))));
        if(!ui(()->editor.writingArea.getText().equals(original)))throw new AssertionError("Source context changed before completion typing");
        long started=System.nanoTime();ui(()->{editor.writingArea.setCaretPosition(editor.writingArea.getDocument().getLength());editor.writingArea.append("a");return null;});await(()->editor.hasAiCompletion(editor.writingArea));
        ui(()->{if(!editor.writingArea.getText().equals(original+"a")){Files.writeString(output.resolve("completion-source-state.json"),MiniJson.stringify(Map.of("before_sha256",sourceHash(original),"actual_sha256",sourceHash(editor.writingArea.getText()),"buffer",editor.getActivePane().getBuffer().getDisplayName(),"document_matches",editor.writingArea.getDocument()==editor.getActivePane().getBuffer().getDocument(),"mutations",mutations)));throw new AssertionError("Preview changed source");}editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());return null;});double first=(System.nanoTime()-started)/1e6;picture("03-inline-completion");if("1".equals(System.getenv("SHED_DELEGATION_DEMO")))Thread.sleep(1500);
        ui(()->{
            var area=editor.writingArea;var word=new java.awt.event.KeyEvent(area,java.awt.event.KeyEvent.KEY_PRESSED,0,java.awt.event.InputEvent.CTRL_DOWN_MASK,java.awt.event.KeyEvent.VK_RIGHT,java.awt.event.KeyEvent.CHAR_UNDEFINED);
            boolean beforeVisible=editor.hasAiCompletion(area),focused=area.hasFocus(),eligible=editor.allowsAiCompletion(),handled=editor.handleAiCompletionKey(word);if(!handled||!area.getText().equals(original+"answer")||!editor.hasAiCompletion(area)){Files.writeString(output.resolve("completion-accept-state.json"),MiniJson.stringify(Map.of("before_visible",beforeVisible,"focused",focused,"eligible",eligible,"handled",handled,"before_sha256",sourceHash(original),"after_sha256",sourceHash(area.getText()),"mutations",mutations)));throw new AssertionError("Partial acceptance failed");}
            var tab=new java.awt.event.KeyEvent(area,java.awt.event.KeyEvent.KEY_PRESSED,0,0,java.awt.event.KeyEvent.VK_TAB,'\t');if(!editor.handleAiCompletionKey(tab)||!area.getText().equals(original+"answer();\n"))throw new AssertionError("Tab acceptance failed");
            var history=editor.getActivePane().getBuffer().getUndoManager();for(int i=0;i<3;i++)history.undo();if(!area.getText().equals(original))throw new AssertionError("Completion Undo changed unrelated source");for(int i=0;i<3;i++)history.redo();if(!area.getText().equals(original+"answer();\n"))throw new AssertionError("Completion Redo mismatch");for(int i=0;i<3;i++)history.undo();
            ((JCheckBoxMenuItem)find(editor.getJMenuBar(),"editor.inlineSuggestions")).doClick();if(Boolean.parseBoolean(editor.configManager.get("ai.completion.enabled","true"))||editor.hasAiCompletion(area))throw new AssertionError("Completion did not disable");return null;
        });return Map.of("state","PASS","provider","IN_PROCESS_FIXED_RESPONSE","first_type_to_completed_preview_ms",first,"configured_idle_ms",ui(()->Integer.parseInt(editor.configManager.get("ai.completion.delay.ms","250"))),"timings",metrics);
        }finally{ui(()->{auditDocument.removeDocumentListener(audit);return null;});}
    }
    static void syntheticGit()throws Exception{
        ui(()->{
            var git=editor.toolWindowHost.gitPanel();var type=GitChangesToolPanel.class;
            var loading=type.getDeclaredField("statusLoading");loading.setAccessible(true);loading.set(git,true);editor.toolWindowHost.showTab(ToolWindowHost.Tab.GIT);
            var snapshot=new GitChangesWorkbenchModel.Snapshot(GitChangesWorkbenchModel.State.READY,project.toString(),"fixture",List.of(new GitChangesWorkbenchModel.Change("","Modified","Demo.txt","")),"Synthetic Git presentation; no Git process launched");
            var render=type.getDeclaredMethod("render",GitChangesWorkbenchModel.Snapshot.class);render.setAccessible(true);render.invoke(git,snapshot);
            var workspace=type.getDeclaredField("snapshotWorkspace");workspace.setAccessible(true);workspace.set(git,editor.workspaceRoots.generation());
            var table=(JTable)find(git.component(),"git.changeFiles");var model=(DefaultListSelectionModel)table.getSelectionModel();var listeners=model.getListSelectionListeners();for(var listener:listeners)model.removeListSelectionListener(listener);table.setRowSelectionInterval(0,0);for(var listener:listeners)model.addListSelectionListener(listener);
            var diff=type.getDeclaredField("selectedDiff");diff.setAccessible(true);diff.set(git,new GitChangesWorkbenchModel.Diff(GitChangesWorkbenchModel.State.READY,"@@ -1 +1 @@\n-Initial source.\n+Updated source.\n",List.of(),"Synthetic patch",""));
            var preview=type.getDeclaredField("diff");preview.setAccessible(true);((ChangeDiffView)preview.get(git)).showPatch(((GitChangesWorkbenchModel.Diff)diff.get(git)).content(),editor.resolveEditorFont());
            var buffer=editor.getCurrentBuffer();int count=editor.buffers.size();button(git.component(),"Open Diff").doClick();
            if(find(editor.getRootPane(),"comparison.text")==null||editor.getCurrentBuffer()!=buffer||editor.buffers.size()!=count)throw new AssertionError("Git comparison changed document ownership");return null;
        });picture("00c-git-sidebar-comparison");
        String historySetting=ui(()->{String previous=editor.configManager.get("git.history.enabled","true");editor.configManager.set("git.history.enabled","false");editor.toolWindowHost.gitPanel().showHistory();return previous;});
        await(()->{try{var field=GitChangesToolPanel.class.getDeclaredField("historySnapshot");field.setAccessible(true);var snapshot=(GitHistoryModel.Snapshot)field.get(editor.toolWindowHost.gitPanel());return snapshot!=null&&snapshot.detail().contains("disabled");}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}});
        ui(()->{editor.configManager.set("git.history.enabled",historySetting);String diffs=editor.configManager.get("git.diffs.enabled","true");editor.configManager.set("git.diffs.enabled","false");try{editor.treeGitController.loadGitCommitComparison(project.toFile(),"abcdef0",null);throw new AssertionError("Disabled diffs reached the Git backend");}catch(IllegalStateException expected){if(!expected.getMessage().contains("disabled"))throw expected;}finally{editor.configManager.set("git.diffs.enabled",diffs);}return null;});
        ui(()->{
            ((JButton)find(editor.getRootPane(),"comparison.close")).doClick();var git=editor.toolWindowHost.gitPanel();editor.workspaceController.activateFolder(project.resolveSibling("alternate-project"),true);editor.workspaceController.activateFolder(project,true);
            var root=GitChangesToolPanel.class.getDeclaredMethod("root");root.setAccessible(true);if(root.invoke(git)!=null)throw new AssertionError("Old Git action context survived workspace replacement");editor.toolWindowHost.showAgentChat();return null;
        });
    }
    static void fake(int turn)throws Exception{
        Path root=Path.of(".").toRealPath(),fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();if(!root.startsWith(fixture.resolve("run")))throw new AssertionError("Disposable project required");
        String prompt=new String(System.in.readNBytes(32769),StandardCharsets.UTF_8);if(!prompt.contains("SHED_CONTROL")||!prompt.contains("Do not call session-finish"))throw new AssertionError("Missing ongoing visualization context");
        System.out.println("{\"type\":\"thread.started\",\"thread_id\":\"fixture-chat\"}");System.out.flush();
        if(turn==3){Thread.sleep(30000);return;}
        if(turn==2&&(!prompt.contains("Add a follow-up")||!prompt.contains("\"text\":\"Initial\"")||!prompt.contains("\"endOffset\":7")))throw new AssertionError("Missing follow-up selection context");
        ctl("read","Demo.txt","--agent","Codex","--limit","2048");Thread.sleep("1".equals(System.getenv("SHED_DELEGATION_DEMO"))?1800:400);
        Path source=root.resolve("Demo.txt");Files.writeString(source,Files.readString(source)+"Change from turn "+turn+".\n");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));ctl("sync","Demo.txt","--sha256",hash,"--timeout-ms","3000");
        System.out.println(MiniJson.stringify(Map.of("type","item.completed","item",Map.of("id","answer","type","agent_message","text",turn==1?"**First reply**: updated `Demo.txt`.\n\n```java\nString message = \"Hello from Shed\";\n```":"**Follow-up reply**: continued the same conversation and updated `Demo.txt` again.\n\n- Kept the original source.\n- Added the requested follow-up."))));
    }
    private static void qualifyEssentials(Path project, List<String> events) throws Exception {
        var original=ui(editor::getCurrentBuffer);
        Path file=project.resolve("OutlineFixture.java");Files.writeString(file,"class OutlineFixture {\n    void greet() {}\n}\n");
        ui(()->{editor.openFileAsync(file.toFile());return null;});await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().toPath().equals(file));
        ui(()->{editor.toggleOutline();return null;});
        await(()->{var list=(JList<?>)find(editor.getRootPane(),"outline.symbols");return list!=null&&list.getModel().getSize()>=2;});
        var source=ui(editor::getCurrentBuffer);String before=ui(source::getContent);
        ui(()->{var list=(JList<?>)find(editor.getRootPane(),"outline.symbols");list.setSelectedIndex(1);list.getActionMap().get("reveal").actionPerformed(new java.awt.event.ActionEvent(list,0,"reveal"));if(editor.writingArea.getCaretPosition()!=before.indexOf("    void"))throw new AssertionError("Outline did not reveal method line");editor.writingArea.append("// saved revision\n");int caret=editor.writingArea.getCaretPosition();list.getActionMap().get("reveal").actionPerformed(new java.awt.event.ActionEvent(list,0,"reveal"));if(editor.writingArea.getCaretPosition()!=caret)throw new AssertionError("Stale Outline moved the caret");editor.commandHandler.execute("w");return null;});
        await(()->!source.isModified());
        var saved=editor.localHistoryStore().versions(file);if(saved.size()!=2)throw new AssertionError("Normal save did not capture baseline and new version: "+saved.size());
        ui(()->{LocalHistoryDialog.showFor(editor);return null;});
        await(()->{var window=Arrays.stream(editor.getOwnedWindows()).filter(w->w instanceof LocalHistoryDialog&&w.isShowing()).findFirst().orElse(null);return window!=null&&((JList<?>)find((Container)window,"history.versions")).getModel().getSize()==2;});
        var history=ui(()->(LocalHistoryDialog)Arrays.stream(editor.getOwnedWindows()).filter(w->w instanceof LocalHistoryDialog&&w.isShowing()).findFirst().orElseThrow());
        ui(()->{((JList<?>)find(history,"history.versions")).setSelectedIndex(1);return null;});
        await(()->button(history,"Restore to buffer").isEnabled());
        picture("00d-outline-and-history");picture("00d-saved-history-dialog",history.getRootPane());
        String disk=Files.readString(file);long revision=ui(source::documentRevision);
        SwingUtilities.invokeLater(()->button(history,"Restore to buffer").doClick());
        await(()->Arrays.stream(history.getOwnedWindows()).anyMatch(Window::isShowing));
        ui(()->{var confirm=Arrays.stream(history.getOwnedWindows()).filter(Window::isShowing).findFirst().orElseThrow();button((Container)confirm,"OK").doClick();return null;});
        await(()->!history.isDisplayable());
        ui(()->{if(!source.getContent().equals(before)||source.documentRevision()==revision||!source.isModified())throw new AssertionError("History restore did not edit authoritative source");source.getUndoManager().undo();if(!source.getContent().equals(disk))throw new AssertionError("History restore did not form one Undo step");return null;});
        if(!Files.readString(file).equals(disk))throw new AssertionError("History restore wrote disk without Save");
        ui(()->{LocalHistoryDialog.showFor(editor);return null;});
        var comparisonHistory=ui(()->(LocalHistoryDialog)Arrays.stream(editor.getOwnedWindows()).filter(w->w instanceof LocalHistoryDialog&&w.isShowing()).findFirst().orElseThrow());
        await(()->((JList<?>)find(comparisonHistory,"history.versions")).getModel().getSize()==2);
        ui(()->{((JList<?>)find(comparisonHistory,"history.versions")).setSelectedIndex(1);return null;});await(()->button(comparisonHistory,"Compare with buffer").isEnabled());
        ui(()->{button(comparisonHistory,"Compare with buffer").doClick();return null;});await(()->!comparisonHistory.isDisplayable());
        ui(()->{if(find(editor.getRootPane(),"comparison.text")==null||!source.getContent().equals(disk))throw new AssertionError("History comparison changed source or failed to open");((JButton)find(editor.getRootPane(),"comparison.close")).doClick();return null;});
        ui(()->{KeymapInspectorDialog.showFor(editor);return null;});
        var shortcuts=ui(()->(JDialog)Arrays.stream(editor.getOwnedWindows()).filter(w->w instanceof KeymapInspectorDialog&&w.isShowing()).findFirst().orElseThrow());
        ui(()->{var record=(JToggleButton)find(shortcuts,"shortcuts.record");var key=(JTextField)find(shortcuts,"shortcuts.key");record.doClick();var event=new java.awt.event.KeyEvent(key,java.awt.event.KeyEvent.KEY_PRESSED,System.currentTimeMillis(),ShortcutKey.primaryMask()|java.awt.event.InputEvent.SHIFT_DOWN_MASK,java.awt.event.KeyEvent.VK_F11,java.awt.event.KeyEvent.CHAR_UNDEFINED);for(var listener:key.getKeyListeners())listener.keyPressed(event);if(!key.getText().equals("<mod-s-f11>"))throw new AssertionError("Shortcut recorder lost modifiers");((JTextField)find(shortcuts,"shortcuts.mapping")).setText("action:save");button(shortcuts,"Save Overlay").doClick();if(!editor.configManager.get("keybind.plain.<mod-s-f11>","").equals("action:save"))throw new AssertionError("Shortcut overlay did not persist");return null;});
        picture("00e-keyboard-shortcuts",shortcuts.getRootPane());
        ui(()->{shortcuts.dispose();editor.commandHandler.execute("w");return null;});await(()->!source.isModified());
        ui(()->{editor.toggleOutline();editor.loadBufferIntoEditor(original);return null;});
        events.add("Persistent local Outline navigates without source edits; normal Save captures history; explicit confirmed restore is one undoable buffer edit without disk writes; recorded shortcut persists through existing overlays");
    }

    public static void main(String[] args)throws Exception{
        if(args.length==2&&args[0].equals("--fake-agent")){fake(Integer.parseInt(args[1]));return;}
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();if(!project.startsWith(fixture.resolve("run"))||!output.startsWith(fixture.resolve("run")))throw new AssertionError("Disposable fixture required");
        Runtime.getRuntime().addShutdownHook(new Thread(()->ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly)));
        Thread watchdog=Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(100000);System.exit(124);}catch(InterruptedException ended){}});int exit=1;var events=new ArrayList<String>();
        try{
            Files.writeString(project.resolve(".shedtasks"),"schema_version = 1\n[task.inspect]\ncommand = \"not-executed-fixture\"\nshell = \"direct\"\n");
            Files.writeString(project.resolve("Demo.txt"),"Initial source.\n");editor=ui(()->{var instance=new Texteditor(new String[]{project.toString()});instance.showToolReport(ToolWindowHost.Tab.LANGUAGE,"Startup report","Synthetic startup report; no language server launched");return instance;});await(()->!editor.stagedStartupPending&&editor.isShowing());
            ui(()->{if(!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.LANGUAGE))throw new AssertionError("Startup report was dropped");return null;});events.add("Startup report is retained through deferred tool-host initialization");
            ui(()->{
                if(editor.fileExplorer==null||!editor.explorerSidebar.showingExplorer()||editor.editorPanes.size()!=1||editor.buffers.stream().anyMatch(b->b.getDisplayName().startsWith("[tree ")))throw new AssertionError("Explorer must own no document or editor pane");
                var explorer=editor.fileExplorer;var pane=editor.getActivePane();var buffer=editor.getCurrentBuffer();int count=editor.buffers.size();editor.explorerSidebar.setDividerLocation(230);
                var toggle=(JCheckBoxMenuItem)find(editor.getJMenuBar(),"workbench.explorer.toggle");toggle.doClick();if(editor.explorerSidebar.showingExplorer())throw new AssertionError("Explorer did not hide");toggle.doClick();
                if(editor.fileExplorer!=explorer||!editor.explorerSidebar.showingExplorer()||editor.explorerSidebar.getDividerLocation()!=230||editor.getActivePane()!=pane||editor.getCurrentBuffer()!=buffer||editor.buffers.size()!=count)throw new AssertionError("Explorer toggle changed editor ownership or width");
                editor.splitWindow(true);if(editor.editorPanes.size()!=2)throw new AssertionError("Explorer counted as a split");editor.closeActiveWindow();
                if(editor.editorPanes.size()!=1||!editor.explorerSidebar.showingExplorer())throw new AssertionError("Closing an editor closed Explorer");
                editor.focusModeController.handleFocusCommand("on");if(editor.explorerSidebar.showingExplorer())throw new AssertionError("Focus did not hide Explorer");editor.focusModeController.handleFocusCommand("off");
                if(!editor.explorerSidebar.showingExplorer()||editor.explorerSidebar.getDividerLocation()!=230)throw new AssertionError("Focus did not restore Explorer width");
                return null;
            });events.add("Persistent Explorer owns no document/pane; menu toggle and editor closure retain its surface, source context and width; focus restores it");
            Path alternate=Files.createDirectory(project.resolveSibling("alternate-project"));
            ui(()->{
                var pane=editor.getActivePane();var buffer=editor.getCurrentBuffer();int count=editor.buffers.size();
                editor.workspaceController.addFolder(alternate,true);
                if(!editor.treeRoot.toPath().equals(alternate)||!editor.explorerSidebar.showingExplorer())throw new AssertionError("Explorer did not switch project");
                editor.workspaceController.activateFolder(project,true);
                var tree=(JTree)find(editor.fileExplorer,"agent.explorer");
                if(!((FileTreeModel.Node)tree.getModel().getRoot()).file().toPath().equals(project)||editor.getActivePane()!=pane||editor.getCurrentBuffer()!=buffer||editor.buffers.size()!=count)throw new AssertionError("Project switch changed document ownership");
                String theme=editor.configManager.get("theme","shed-light"),density=editor.configManager.get("ui.density","default");
                editor.setThemeFromCommand("dracula");String changed=editor.settingsApplication.update("ui.density","compact");
                if(changed.startsWith("Error")||!editor.fileExplorer.getBackground().equals(editor.configManager.getNormalColor()))throw new AssertionError("Explorer appearance did not update");
                editor.setThemeFromCommand(theme);editor.settingsApplication.update("ui.density",density);
                if(editor.getActivePane()!=pane||editor.getCurrentBuffer()!=buffer||editor.buffers.size()!=count||!editor.explorerSidebar.showingExplorer())throw new AssertionError("Appearance changed Explorer ownership");return null;
            });events.add("Workspace switching and theme/density changes update the sidebar independently of source ownership");
            ui(()->{editor.setSize(1440,960);editor.workspaceController.addFolder(project,true);editor.handleAgentCommand("delegate");return null;});await(()->find(editor.getRootPane(),"agent.chat")!=null);
            ui(()->{
                var tree=(JTree)find(editor.fileExplorer,"agent.explorer");var root=tree.getModel().getRoot();
                for(int i=0;i<tree.getModel().getChildCount(root);i++){var node=tree.getModel().getChild(root,i);if(node instanceof FileTreeModel.Node entry&&entry.file().getName().equals("Demo.txt")){tree.setSelectionPath(new javax.swing.tree.TreePath(new Object[]{root,node}));tree.getActionMap().get("shed.tree.open").actionPerformed(new java.awt.event.ActionEvent(tree,0,"open"));break;}}
                if(editor.getCurrentBuffer().getFile()==null||!editor.getCurrentBuffer().getFile().toPath().equals(project.resolve("Demo.txt"))||!editor.explorerSidebar.showingExplorer()||editor.editorPanes.size()!=1)throw new AssertionError("Explorer opening did not target the content pane");return null;
            });events.add("Explorer Enter action opens the real file in the active source pane while the sidebar remains visible");
            ui(()->{
                var source=editor.getCurrentBuffer();String text=source.getContent();long revision=source.documentRevision();int count=editor.buffers.size();int caret=editor.writingArea.getCaretPosition();boolean dirty=source.isModified(),undo=source.getUndoManager().canUndo();
                editor.lspStatus();if(!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.LANGUAGE))throw new AssertionError("LSP report not in Language tools");
                editor.handleDebugCommand("status");if(!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.DEBUG))throw new AssertionError("Debug report not in Debug tools");
                String dryRun=editor.handleTaskCommand("dry-run inspect");if(!dryRun.equals("Task dry run shown (not started)")||!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.TASKS)||ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive))throw new AssertionError("Task report did not use a process-free dry run: "+dryRun);
                editor.updateQuickfixEntries("Demo locations",List.of(new QuickfixService.Entry(project.resolve("Demo.txt").toString(),1,1,"First line","fixture")));editor.openQuickfixList();
                if(!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.QUICKFIX)||find(editor.toolWindowHost,"quickfix.results")==null)throw new AssertionError("Quickfix not navigable in Results");editor.closeQuickfixList();
                editor.showComparison("Diff · Demo.txt","@@ -1 +1 @@\n-before\n+after\n");var comparison=(javax.swing.text.JTextComponent)find(editor.getRootPane(),"comparison.text");if(comparison==null||comparison.isEditable())throw new AssertionError("Central comparison not read-only");((JButton)find(editor.getRootPane(),"comparison.close")).doClick();
                if(editor.getCurrentBuffer()!=source||!source.getContent().equals(text)||source.documentRevision()!=revision||source.isModified()!=dirty||source.getUndoManager().canUndo()!=undo||editor.buffers.size()!=count||editor.writingArea.getCaretPosition()!=caret)throw new AssertionError("Tool presentation mutated source/Undo/caret or created documents");
                editor.hideToolWindow();editor.toolWindowHost.showAgentChat();return null;
            });events.add("LSP/Debug/Tasks reports, navigable quickfix and a central read-only comparison leave source identity, text, revision, Undo, dirty state, caret and document count unchanged");
            qualifyEssentials(project,events);
            syntheticGit();events.add("Synthetic Git data renders in the right sidebar and opens a central read-only comparison; switching projects invalidates the old repository action context");
            picture("00-chat-layout");ui(()->{
                if(editor.editorToolSplit.getOrientation()!=JSplitPane.VERTICAL_SPLIT||!editor.workbenchDocks.rightVisible())throw new AssertionError("Chat must be in the independent right dock");
                var bottomTabs=(JTabbedPane)find(editor.toolWindowHost,"workbench.tools");if(bottomTabs.indexOfTab("Git")>=0||bottomTabs.indexOfTab("Chat")>=0)throw new AssertionError("Right tools leaked into bottom tabs");
                editor.toolWindowHost.showTab(ToolWindowHost.Tab.SEARCH);if(!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.SEARCH)||!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.AGENT))throw new AssertionError("Bottom tool hid chat");
                editor.validate();editor.workbenchDocks.setDividerLocation(880);editor.editorToolSplit.setDividerLocation(430);editor.validate();var dockState=editor.workbenchDocks.snapshot();
                editor.toolWindowHost.toggleRight(ToolWindowHost.Tab.AGENT);if(editor.workbenchDocks.rightVisible()||!editor.workbenchDocks.bottomVisible())throw new AssertionError("Sidebar toggle changed bottom visibility");editor.toolWindowHost.showAgentChat();
                editor.focusModeController.handleFocusCommand("on");if(editor.workbenchDocks.rightVisible()||editor.workbenchDocks.bottomVisible())throw new AssertionError("Focus mode did not hide both docks");editor.focusModeController.handleFocusCommand("off");editor.validate();
                if(!dockState.equals(editor.workbenchDocks.snapshot()))throw new AssertionError("Focus mode did not restore both dock sizes: "+dockState+" / "+editor.workbenchDocks.snapshot());
                return null;
            });events.add("Git/Chat stay right while bottom tools remain visible; independent toggle and focus-mode size restoration pass");
            ui(()->{editor.setSize(1100,820);((JComboBox<?>)find(editor.getRootPane(),"agent.chat.provider")).setSelectedItem(CliChatProtocol.Provider.CLAUDE);editor.validate();return null;});picture("00b-claude-narrow-chat");
            ui(()->{var dock=find(editor.getRootPane(),"workbench.rightDock");var input=SwingUtilities.convertRectangle(composer().getParent(),composer().getBounds(),dock);if(input.x<0||input.x+input.width>dock.getWidth()||input.y<0||input.y+input.height>dock.getHeight())throw new AssertionError("Narrow chat composer is clipped");((JComboBox<?>)find(editor.getRootPane(),"agent.chat.provider")).setSelectedItem(CliChatProtocol.Provider.CODEX);editor.setSize(1440,960);editor.validate();return null;});events.add("Orange Claude picker and composer fit the narrow right-hand panel");
            var panelComponent=ui(()->find(editor.getRootPane(),"agent.chat"));Object panel=null;
            // Locate only this fixture window's own chat surface; no production test hook is exposed.
            var surfacesField=ToolWindowHost.class.getDeclaredField("surfaces");surfacesField.setAccessible(true);var surfaces=(Map<?,?>)surfacesField.get(editor.toolWindowHost);Object workbench=surfaces.get(ToolWindowHost.Tab.AGENT);
            ui(()->{var detach=ToolWindowHost.class.getDeclaredMethod("detach",ToolWindowHost.Tab.class,ToolWindowHost.ToolSurface.class);detach.setAccessible(true);detach.invoke(editor.toolWindowHost,ToolWindowHost.Tab.SEARCH,surfaces.get(ToolWindowHost.Tab.SEARCH));editor.toolWindowHost.showAgentChat();if(!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.SEARCH)||!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.AGENT))throw new AssertionError("Detached tool refresh lost while chat is docked");for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&"Search".equals(d.getTitle()))d.dispose();return null;});
            await(()->editor.editorToolSplit.getOrientation()==JSplitPane.VERTICAL_SPLIT);ui(()->{editor.toolWindowHost.showAgentChat();return null;});events.add("Detached search stays active beside chat and reattaches to its bottom dock");
            var chatField=AgentWorkbenchPanel.class.getDeclaredField("chat");chatField.setAccessible(true);panel=chatField.get(workbench);
            var agents=(AgentWorkbenchPanel)workbench;ui(()->{for(int i=0;i<2;i++){agents.showReview();((JButton)find(editor.getRootPane(),"agent.backToChat")).doClick();agents.showLiveChanges();((JButton)find(editor.getRootPane(),"agent.backToChat")).doClick();}return null;});
            var turns=new java.util.concurrent.atomic.AtomicInteger();var runnerField=AgentCliChatPanel.class.getDeclaredField("runner");runnerField.setAccessible(true);
            runnerField.set(panel,(AgentCliChatPanel.Runner)(command,a,prompt,live,chunk)->{int turn=turns.incrementAndGet();if(turn==2&&!command.containsAll(List.of("resume","fixture-chat")))throw new AssertionError("Follow-up did not resume native context");var result=ManagedAgentTask.run(javaCommand(turn),a.root(),a.descriptor(),a.id(),prompt,new HashMap<>(System.getenv()),live,15000,chunk);Files.writeString(output.resolve("turn-"+turn+".log"),result.output());return result;});
            ui(()->{((JTextField)find(editor.getRootPane(),"agent.chat.executable")).setText(AgentTaskInstructions.cli().getFirst());composer().setText("Update Demo.txt");send().doClick();return null;});await(()->approval()!=null);
            ui(()->{button(approval(),"Allow Control").doClick();return null;});await(()->"Agent reading".equals(editor.writingArea.getClientProperty("shed.agentActivity")));
            ui(()->{if(!composer().isEditable()||editor.writingArea.isEditable())throw new AssertionError("Chat/source input ownership is incorrect");var shield=editor.getGlassPane();Point point=SwingUtilities.convertPoint(composer(),5,5,shield);if(shield.contains(point))throw new AssertionError("Glass pane blocks chat input");var close=find(editor.getRootPane(),"agent.dock.close");if(shield.contains(SwingUtilities.convertPoint(close,5,5,shield)))throw new AssertionError("Glass pane blocks chat close");return null;});picture("01-chat-and-reading");events.add("Chat input remains available while real editor reads are highlighted");
            await(()->transcript().getText().contains("First reply")&&send().isEnabled());ui(()->{if(!editor.writingArea.isEditable())throw new AssertionError("Human editor was not restored between messages");var lease=editor.agentDelegation().lease;if(!lease.state().equals("active")||editor.agentDelegationAllowed(lease.id()))throw new AssertionError("Bridge must be suspended between chat turns");return null;});
            var server=ui(()->editor.visualAttachment());String leaseId=ui(()->editor.agentDelegation().lease.id());try(var client=WorkspaceBridgeClient.control(server.descriptor(),server.credential())){client.delegation(leaseId);var response=MiniJson.asObject(MiniJson.parseStrict(client.exchange(client.navigationContextRequest())));var error=MiniJson.asObject(response.get("error"));if(error==null||!"AUTHORIZATION_REVOKED".equals(MiniJson.asObject(error.get("data")).get("code")))throw new AssertionError("Idle chat bridge admitted a request");}events.add("Authenticated bridge rejects source access between chat turns");
            ui(()->{
                var area=editor.writingArea;var buffer=editor.getActivePane().getBuffer();String before=area.getText();long revision=buffer.documentRevision();boolean undo=buffer.getUndoManager().canUndo();area.select(0,7);
                var menu=area.getComponentPopupMenu();for(var listener:menu.getPopupMenuListeners())listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
                var add=(JMenuItem)find(menu,"editor.addSelectionToChat");if(add==null||!add.isEnabled())throw new AssertionError("Selection action missing");add.doClick();
                if(find(editor.getRootPane(),"agent.chat.removeContext")==null||turns.get()!=1)throw new AssertionError("Selection must attach without sending");
                if(!area.getText().equals(before)||buffer.documentRevision()!=revision||buffer.getUndoManager().canUndo()!=undo||area.getSelectionStart()!=0||area.getSelectionEnd()!=7)throw new AssertionError("Adding context changed source/history/selection");
                return null;
            });picture("01b-selection-context");events.add("Selected code attaches through the editor menu without sending or source/history mutation");
            ui(()->{composer().setText("Add a follow-up change");send().doClick();return null;});
            await(()->transcript().getText().contains("Follow-up reply")&&send().isEnabled());String expected="Initial source.\nChange from turn 1.\nChange from turn 2.\n";if(!Files.readString(project.resolve("Demo.txt")).equals(expected))throw new AssertionError("Source changed unexpectedly");await(()->editor.writingArea.getText().equals(expected)&&!editor.getActivePane().getBuffer().isModified());
            ui(()->{if(transcript().getText().contains("SHED_CONTROL"))throw new AssertionError("Bootstrap leaked into visible chat");return null;});picture("02-follow-up-and-source");events.add("Two turns use one native conversation with fresh hidden visualization instructions");
            if("1".equals(System.getenv("SHED_DELEGATION_DEMO")))Thread.sleep(1800);
            ui(()->{
                editor.hideToolWindow();if(editor.workbenchDocks.rightVisible()||editor.workbenchDocks.bottomVisible())throw new AssertionError("Hide tools failed");editor.toolWindowHost.showAgentChat();
                var originalDocument=editor.documents.activeDocument().id();editor.openEmptyBuffer();editor.documents.activate(originalDocument);editor.openEditorsBar.refresh();
                var list=(JList<?>)find(editor.getRootPane(),"workbench.openEditors");int selected=list.getSelectedIndex(),index=-1;for(int i=0;i<list.getModel().getSize();i++)if(i!=selected){index=i;break;}if(index<0)throw new AssertionError("Need inactive tab fixture");
                int count=list.getModel().getSize();var active=editor.documents.activeDocument().id();var cell=list.getCellBounds(index,index);
                list.dispatchEvent(new java.awt.event.MouseEvent(list,java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,cell.x+cell.width-12,cell.y+cell.height/2,1,false,java.awt.event.MouseEvent.BUTTON1));
                if(list.getModel().getSize()!=count-1||!editor.documents.activeDocument().id().equals(active))throw new AssertionError("Inline close affected wrong tab");
                return null;
            });events.add("Chat hide/reopen preserves conversation; inline close targets its inactive tab");
            ui(()->{composer().setText("Wait for my next message");send().doClick();return null;});await(()->turns.get()==3&&ManagedAgentTask.activeIoWorkers()>0);
            ui(()->{composer().setText("Retain this next draft");((JButton)find(editor.getRootPane(),"agent.chat.stop")).doClick();return null;});await(()->ManagedAgentTask.activeIoWorkers()==0&&ProcessHandle.current().descendants().noneMatch(ProcessHandle::isAlive));
            ui(()->{if(!composer().getText().equals("Retain this next draft")||!editor.writingArea.isEditable())throw new AssertionError("Stop lost input or editor ownership");((JButton)find(editor.getRootPane(),"agent.chat.new")).doClick();if(!transcript().getText().isBlank())throw new AssertionError("New chat did not reset transcript");return null;});events.add("Stop releases processes and source ownership; New chat resets conversation");
            Map<String,Object> inline=Map.of("state","NOT_REQUESTED");if("1".equals(System.getenv("SHED_INLINE_DEMO"))){inline=inlineDemo();events.add("Opt-in automatic ghost text, partial/full acceptance, exact Undo/Redo and disable pass without a model process");}
            var samples=new ArrayList<Double>();for(int i=0;i<40;i++)samples.add(ui(()->{long start=System.nanoTime();editor.writingArea.append("x");editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());return (System.nanoTime()-start)/1e6;}));var sorted=new ArrayList<>(samples);Collections.sort(sorted);double p95=sorted.get(37);if(p95>16)throw new AssertionError("Typing p95 exceeded 16 ms: "+p95);
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","events",events,"turns",turns.get(),"remaining_task_io_workers",ManagedAgentTask.activeIoWorkers(),"model","FIXED_JAVA_CLI_DOUBLE","native_shell","NOT_USED","inline_completion",inline,"post_chat_completed_paint",Map.of("first_ms",samples.getFirst(),"p95_ms",p95,"samples_ms",samples))));exit=0;
        }catch(Throwable error){error.printStackTrace();Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",error.toString(),"events",events)));}
        finally{if(editor!=null)ui(()->{editor.agentDelegation().close();editor.handleAgentCommand("api stop");editor.asyncJobService.shutdownNow();for(Window w:Window.getWindows())w.dispose();return null;});watchdog.interrupt();System.exit(exit);}
    }
}
