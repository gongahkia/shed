package shed;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Real owned workbench and external Java CLI, with explicit project consent. No shell or real model. */
public final class AgentWorkbenchDesktopProbe {
    static Texteditor editor;
    static Path project,output,descriptor;
    static int calls;
    static String delegation,expectedError;
    static final List<String> events=new ArrayList<>();
    static final List<Map<String,Object>> timings=new ArrayList<>();
    static final List<Double> queuedPaint=new ArrayList<>();
    static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);}
    static void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!edt(ready::getAsBoolean)){if(System.nanoTime()>end)throw new AssertionError("Owned workbench deadline");Thread.sleep(20);}}
    static void fileEquals(Path file,String expected)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!Files.exists(file)||!Files.readString(file).equals(expected)){if(System.nanoTime()>end)throw new AssertionError("File update deadline");Thread.sleep(20);}}
    static JButton button(Component c,String label){if(c instanceof JButton b&&label.equals(b.getText()))return b;if(c instanceof Container p)for(Component child:p.getComponents()){var found=button(child,label);if(found!=null)return found;}return null;}
    static JDialog approval(){for(Window window:Window.getWindows())if(window instanceof JDialog d&&d.isShowing()&&"agent.approval".equals(d.getName()))return d;return null;}
    static void picture(String name)throws Exception{var image=edt(()->{var root=editor.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),8_000_000);});try{javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());}finally{image.flush();}}
    public static final class Client{public static void main(String[] args){System.exit(ShedControlCli.run(args,System.in,System.out,System.err));}}
    static Map<String,Object> cli(String body,String...args)throws Exception{
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.awt.headless=true","-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-Djava.util.prefs.userRoot="+System.getProperty("java.util.prefs.userRoot"),"-cp",System.getProperty("java.class.path"),Client.class.getName(),"ctl","--connect",descriptor.toString()));if(delegation!=null)command.addAll(List.of("--delegation",delegation));command.addAll(List.of(args));
        long started=System.nanoTime();
        Path log=output.resolve("client-"+(++calls)+".json");var process=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{try(var input=process.getOutputStream()){input.write(body.getBytes(StandardCharsets.UTF_8));}if(!process.waitFor(8,TimeUnit.SECONDS))throw new AssertionError("CLI deadline");if(Files.size(log)>60_000)throw new AssertionError("CLI output bound");String raw=Files.readString(log);if(process.exitValue()!=0){if(expectedError!=null){var error=MiniJson.asObject(MiniJson.asObject(MiniJson.parseStrict(raw)).get("error"));if(error!=null&&expectedError.equals(MiniJson.asObject(error.get("data")).get("code"))){expectedError=null;return error;}}
        for(var entry:Thread.getAllStackTraces().entrySet())if(entry.getKey().getName().startsWith("AWT-EventQueue")||entry.getKey().getName().contains("workspace")){System.out.println("OWNED THREAD "+entry.getKey().getName()+" "+entry.getKey().getState());for(var frame:entry.getValue())System.out.println("  "+frame);}if(!args[0].equals("ui-targets"))try{System.out.println("State after failure: "+MiniJson.stringify(cli("","ui-targets")));}catch(Exception|AssertionError diagnostic){System.out.println("State unavailable after failure");}throw new AssertionError(args[0]+": "+raw);}var reply=ShedControlCli.result(raw);timings.add(Map.of("command",args[0],"round_trip_including_jvm_and_animation_ms",(System.nanoTime()-started)/1e6));return reply;}
        finally{if(process.isAlive())process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();}
    }
    static Object field(Object owner,String name)throws Exception{var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    static void field(Object owner,String name,Object value)throws Exception{var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
    static void stage(String message)throws Exception{events.add(message);System.out.println(message);if("1".equals(System.getenv("SHED_DELEGATION_DEMO")))Thread.sleep(1100);}
    static void action(String id)throws Exception{cli("","ui-run",id);cli("","ui-wait");stage(id);}
    static String target(String scope,String label)throws Exception{
        int offset=0;do{var state=MiniJson.asObject(cli("","ui-targets","--offset",String.valueOf(offset)).get("observation"));
            for(Object raw:MiniJson.asArray(state.get("targets"))){var item=MiniJson.asObject(raw);String id=(String)item.get("id");if(id.startsWith(scope)&&(label.equals(item.get("label"))||id.endsWith("/"+label))&&Boolean.TRUE.equals(item.get("visible")))return id;}
            offset=((Number)state.get("nextOffset")).intValue();if(offset>=((Number)state.get("total")).intValue())break;
        }while(true);throw new AssertionError("Missing semantic target: "+scope+" / "+label);
    }
    static Map<String,Object> observe(String id)throws Exception{return MiniJson.asObject(MiniJson.asObject(cli("","ui-inspect",id).get("observation")).get("target"));}
    static JDialog picker(){for(Window window:Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&Objects.toString(dialog.getName(),"").startsWith("picker."))return dialog;return null;}
    static void pick(String action,String query)throws Exception {cli("","ui-run",action);await(()->picker()!=null);String filter=target("dialog.","picker.query");type(filter,query);String results=target("dialog.","picker.results");cli("","ui-select",results,"--index","0");cli("","ui-key",filter,"--key","ENTER");await(()->picker()==null);if(action.equals("files.open")){String content=Files.readString(project.resolve(query));await(()->editor.writingArea.getDocument()==editor.getCurrentBuffer().getDocument()&&editor.writingArea.getText().equals(content));}stage("Picker: "+action);}
    static void type(String id,String text)throws Exception{cli(text,"ui-type",id,"--text-file","-","--agent","Codex");stage("Typing into "+id);}
    public static void main(String[] args)throws Exception{
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();if(!output.startsWith(fixture.resolve("run"))||!project.startsWith(fixture.resolve("run")))throw new AssertionError("Disposable fixture required");
        int exit=1;
        try{
            String source="x".repeat(300)+"\n"+"demoToken line\n".repeat(220);Path file=project.resolve("Demo.txt");Files.writeString(file,source);Files.writeString(project.resolve("Notes.md"),"# Demo notes\n\nA small preview.\n");
            editor=edt(()->new Texteditor(new String[]{file.toString()}));await(()->!editor.stagedStartupPending&&editor.getCurrentBuffer()!=null&&editor.getCurrentBuffer().getFile()!=null);
            edt(()->{editor.setTitle("Shed — Whole workbench agent tour");editor.setSize(1400,900);editor.workspaceController.addFolder(project,true);editor.setMode(EditorMode.INSERT);editor.hideToolWindow();editor.toFront();editor.handleAgentCommand("api start control");return null;});await(()->editor.visualAttachment()!=null);descriptor=edt(()->editor.visualAttachment().descriptor());
            Path task=output.resolve("task.txt");Files.writeString(task,"Demonstrate semantic interaction with this disposable project.");cli("","session-request","--request-id","workbench-demo","--agent","Codex","--task-file",task.toString());await(()->approval()!=null);
            edt(()->{var dialog=approval();var option=find(dialog,"agent.approval.permission.system_clipboard");if(!(option instanceof JCheckBox check)||check.isSelected())throw new AssertionError("System clipboard must be opt-in");if(Boolean.getBoolean("shed.workbench.readOnly"))for(String permission:List.of("edit","files","vcs","tools","appearance"))((JCheckBox)find(dialog,"agent.approval.permission."+permission)).setSelected(false);button(dialog,"Allow Control").doClick();return null;});delegation="workbench-demo";await(()->editor.agentDelegation().lease.state().equals("active"));stage("Project consent approved; private clipboard");
            if(Boolean.getBoolean("shed.workbench.readOnly")){
                cli("","ui-do","editor.active","--kind","text","--args-file",payload(Map.of("mode","select","text","","start",0L,"end",5L)).toString());var read=MiniJson.asObject(cli("","ui-read","editor.active","--start","0","--end","5").get("observation"));if(!"xxxxx".equals(read.get("content")))throw new AssertionError("Read-only source excerpt differs");
                expectedError="PERMISSION_DENIED";cli("must not be inserted","ui-type","editor.active","--text-file","-");if(expectedError!=null)throw new AssertionError("Read-only source write was accepted");
                edt(()->{if(!source.equals(editor.writingArea.getText())||editor.getCurrentBuffer().isModified()||editor.writingArea.getSelectionStart()!=0||editor.writingArea.getSelectionEnd()!=5)throw new AssertionError("Read-only navigation changed source or selection");return null;});stage("Read-only source selection and read; write correctly rejected");
            }else if(Boolean.getBoolean("shed.workbench.contextOnly")){
                cli("","ui-do","editor.active","--kind","text","--args-file",payload(Map.of("mode","select","text","","start",0L,"end",5L)).toString());
                for(int cycle=0;cycle<10;cycle++){cli("","ui-context","editor.active","--index","0");cli("","ui-click",target("popup/","Copy"));cli("","ui-wait");stage("Private context copy cycle "+cycle);}
            }else {
            cli("","ui-key","editor.active","--key","mod F");await(()->editor.inlineFindBar.isVisible());type("find/find.query","demoToken");await(()->"ready".equals(editor.inlineFindBar.agentState().get("phase")));cli("","ui-click",target("find/","Regex"));cli("","ui-wait");picture("01-find");stage("Real Find, typing and checkbox");
            int caret=edt(()->editor.writingArea.getCaretPosition()),start=edt(()->editor.writingArea.getSelectionStart()),end=edt(()->editor.writingArea.getSelectionEnd());
            var scroll=new FutureTask<Map<String,Object>>(()->cli("","ui-scroll","editor.active","--amount","3","--unit","pages"));var clientThread=new Thread(scroll,"owned-workbench-client");clientThread.start();
            for(int sample=0;sample<100;sample++){long posted=System.nanoTime();queuedPaint.add(edt(()->{editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());return (System.nanoTime()-posted)/1e6;}));Thread.sleep(4);}
            scroll.get(8,TimeUnit.SECONDS);clientThread.join(1000);if(clientThread.isAlive())throw new AssertionError("Client thread retained");cli("","ui-scroll","editor.active","--axis","horizontal","--amount","80","--unit","pixels");
            edt(()->{if(editor.writingArea.getCaretPosition()!=caret||editor.writingArea.getSelectionStart()!=start||editor.writingArea.getSelectionEnd()!=end||!source.equals(editor.writingArea.getText()))throw new AssertionError("Scrolling changed source/caret/selection");return null;});picture("02-scroll");stage("Smooth vertical and horizontal scrolling");
            cli("","ui-drag","layout/explorer","--start","0","--end","230");stage("Drag the real Explorer divider");
            pick("files.open","Notes.md");await(()->editor.getCurrentBuffer().getDisplayName().equals("Notes.md"));action("preview.open");stage("Open actual Markdown preview");
            pick("buffers.open","Demo.txt");await(()->editor.getCurrentBuffer().getDisplayName().equals("Demo.txt"));
            action("pane.split.vertical");await(()->editor.editorPanes.size()==2);action("pane.close");await(()->editor.editorPanes.size()==1);stage("Split and pane closure");
            action("panel.search");String query=target("tool.search/","Find");type(query,"demoToken");await(()->editor.toolWindowHost.isSelected(ToolWindowHost.Tab.SEARCH));picture("03-project-search");stage("Project search surface");
            action("bottom.hide");action("source.new");await(()->editor.getCurrentBuffer().getFile()==null);type("editor.active","Hello from the agent.\n");
            cli("Draft.txt","ui-run","files.save-as","--value-file","-");cli("","ui-wait");fileEquals(project.resolve("Draft.txt"),"Hello from the agent.\n");stage("Actual source typing and project-relative Save As");
            cli("","ui-key","editor.active","--key","mod Z");cli("","ui-key","editor.active","--key","mod shift Z");
            type("find/find.query","");cli("","ui-do","editor.active","--kind","text","--args-file",payload(Map.of("mode","select","text","","start",0L,"end",5L)).toString());cli("","ui-key","editor.active","--key","mod C");cli("","ui-key","find/find.query","--key","mod V");
            edt(()->{if(!"Hello".equals(editor.inlineFindBar.queryText()))throw new AssertionError("Private clipboard did not paste into Find");return null;});stage("Private copy/paste through shortcuts");
            cli("","ui-context","editor.active","--index","0");String copy=target("popup/","Copy");cli("","ui-click",copy);cli("","ui-wait");stage("Actual editor context menu with private clipboard");
            String menu=target("menu/","View");cli("","ui-click",menu);cli("","ui-wait");stage("Open the real View menu");cli("","ui-key",menu,"--key","ESCAPE");cli("","ui-wait");
            String changes=MiniJson.stringify(List.of(Map.of("kind","create","path","Created.txt","text","Created in the project UI.\n")));
            cli(changes,"ui-run","files.change","--value-file","-");cli("","ui-wait");fileEquals(project.resolve("Created.txt"),"Created in the project UI.\n");stage("Create a file through existing checked project changes");
            var created=cli("","read-file","Created.txt");String version=(String)MiniJson.asObject(created.get("file")).get("version");
            cli(MiniJson.stringify(List.of(Map.of("kind","rename","path","Created.txt","destination","Renamed.txt","version",version))),"ui-run","files.change","--value-file","-");cli("","ui-wait");fileEquals(project.resolve("Renamed.txt"),"Created in the project UI.\n");stage("Checked project rename");
            var renamed=cli("","read-file","Renamed.txt");version=(String)MiniJson.asObject(renamed.get("file")).get("version");
            cli(MiniJson.stringify(List.of(Map.of("kind","delete","path","Renamed.txt","version",version))),"ui-run","files.change","--value-file","-");cli("","ui-wait");await(()->!Files.exists(project.resolve("Renamed.txt")));stage("Checked project deletion");
            cli("{\"kind\":\"create\",\"path\":\"EmptyFolder\"}","ui-run","files.folder","--value-file","-");cli("","ui-wait");await(()->Files.isDirectory(project.resolve("EmptyFolder")));stage("Create an empty project folder");
            cli("{\"kind\":\"rename\",\"path\":\"EmptyFolder\",\"destination\":\"RenamedFolder\"}","ui-run","files.folder","--value-file","-");cli("","ui-wait");await(()->Files.isDirectory(project.resolve("RenamedFolder")));
            cli("{\"kind\":\"delete\",\"path\":\"RenamedFolder\"}","ui-run","files.folder","--value-file","-");cli("","ui-wait");await(()->!Files.exists(project.resolve("RenamedFolder")));stage("Rename and remove the empty project folder");
            action("source.new");type("editor.active","helloWorld\nhel");action("completion.show");await(()->editor.isCompletionPopupVisible());String suggestions=target("completion/","Completion suggestions");var items=MiniJson.asArray(observe(suggestions).get("items"));int chosen=-1;for(var raw:items){var item=MiniJson.asObject(raw);if(((String)item.get("label")).startsWith("helloWorld"))chosen=((Number)item.get("index")).intValue();}if(chosen<0)throw new AssertionError("Expected local completion missing");cli("","ui-select",suggestions,"--index",String.valueOf(chosen));picture("05-completion");cli("","ui-key","editor.active","--key","TAB");await(()->!editor.isCompletionPopupVisible());
            edt(()->{if(!editor.writingArea.getText().equals("helloWorld\nhelloWorld"))throw new AssertionError("Completion not applied");return null;});action("source.undo");edt(()->{if(!editor.writingArea.getText().equals("helloWorld\nhel"))throw new AssertionError("Completion Undo lost the source");return null;});stage("Actual local completion, Tab acceptance and one-step Undo");
            cli("Completion.txt","ui-run","files.save-as","--value-file","-");cli("","ui-wait");fileEquals(project.resolve("Completion.txt"),"helloWorld\nhel");pick("buffers.open","Draft.txt");action("source.save");
            action("density.compact");action("density.default");action("focus.toggle");action("focus.toggle");
            Files.createDirectory(project.resolve(".hg"));var vcsWrites=new java.util.concurrent.atomic.AtomicInteger();
            var scm=edt(()->field(editor.toolWindowHost.sourceControlPanel(),"nativeScm"));
            edt(()->{field(scm,"service",new ScmWorkbenchService((directory,command,token)->{if(!directory.equals(project))throw new AssertionError("SCM fixture root changed");if(command.contains("add"))vcsWrites.incrementAndGet();return new CommandResult(0,command.contains("status")?"M Draft.txt\0":command.contains("branch")?"default\n":command.contains("diff")?"@@ -1 +1 @@\n-old\n+new\n":"","");}));return null;});
            action("panel.git");cli("","ui-select",target("tool.git/","scm.provider"),"--index","1");await(()->{try{return (boolean)field(scm,"ready")&&!(boolean)field(scm,"busy");}catch(Exception failure){throw new RuntimeException(failure);}});stage("Provider dropdown uses the real Source Control view");
            cli("","ui-select",target("tool.git/","Source control changes"),"--index","0");await(()->{try{return !(boolean)field(scm,"busy");}catch(Exception failure){throw new RuntimeException(failure);}});cli("","ui-click",target("tool.git/","Add"));cli("","ui-wait");await(()->vcsWrites.get()==1);stage("Source Control Add under project approval; fixed in-process client, no native VCS");action("panel.agent");cli("","ui-run","settings.open");await(()->Arrays.stream(Window.getWindows()).anyMatch(w->w instanceof JDialog&&w.isShowing()&&w!=approval()));stage("Settings dialog opens through existing controller");
            type(target("dialog.","Search settings"),"ui.density");String density=target("dialog.","Interface density");var choices=MiniJson.asArray(observe(density).get("items"));int compact=-1;for(var raw:choices){var choice=MiniJson.asObject(raw);if("compact".equalsIgnoreCase((String)choice.get("label")))compact=((Number)choice.get("index")).intValue();}if(compact<0)throw new AssertionError("Compact density option missing");cli("","ui-select",density,"--index",String.valueOf(compact));await(()->editor.configManager.getUiDensity()==UiDensity.COMPACT);stage("Change the real typed Settings density control");cli("","ui-click","dialog.dismiss");cli("","ui-wait");action("right.hide");
            picture("04-workbench");
            }
            edt(()->{if(editor.agentDelegation().uiSummary().isEmpty())throw new AssertionError("Session interaction summary missing");return null;});
            if(!Boolean.getBoolean("shed.workbench.readOnly")&&!Boolean.getBoolean("shed.workbench.contextOnly")){cli("","ui-run","settings.open");await(()->Arrays.stream(Window.getWindows()).anyMatch(w->w instanceof SettingsEditorDialog&&w.isShowing()));}
            edt(()->{editor.agentDelegation().command("finish","workbench-demo","Codex","Workbench demo completed.");if(Arrays.stream(Window.getWindows()).anyMatch(w->w instanceof SettingsEditorDialog&&w.isShowing()))throw new AssertionError("Agent-owned dialog retained at handback");if(editor.getRootPane().getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER).length!=0)throw new AssertionError("Agent pointer retained at handback");return null;});
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","native_shell","NOT_USED","external_cli",true,"calls",calls,"events",events,"cli_timings",timings,"queued_paint_while_scrolling_ms",queuedPaint,"source_scroll_integrity",true,"remaining_processes",ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count())));exit=0;
        }catch(Throwable failure){failure.printStackTrace();Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",failure.toString(),"calls",calls,"events",events)));}
        finally{if(editor!=null)edt(()->{editor.agentDelegation().close();editor.handleAgentCommand("api stop");editor.asyncJobService.shutdownNow();for(Window window:Window.getWindows())window.dispose();return null;});System.exit(exit);}
    }
    static Path payload(Object value)throws IOException{Path file=output.resolve("payload-"+calls+".json");Files.writeString(file,MiniJson.stringify(value));return file;}
    static Component find(Component component,String name){if(name.equals(component.getName()))return component;if(component instanceof Container parent)for(Component child:parent.getComponents()){var result=find(child,name);if(result!=null)return result;}return null;}
}
