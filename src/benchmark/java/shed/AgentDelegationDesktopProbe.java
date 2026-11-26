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

/** Opt-in, fixed Java client/workbench positive control. No model, shell or personal project. */
public final class AgentDelegationDesktopProbe {
    static Texteditor editor;static Path project,output;static final List<Map<String,Object>> events=new ArrayList<>();
    static <T>T ui(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);}
    static void await(BooleanSupplier ready)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!ui(ready::getAsBoolean)){if(System.nanoTime()>until)throw new AssertionError("Desktop condition timed out: "+(editor==null?"no window":editor.agentDelegation().lease.state()));Thread.sleep(25);}}
    static <T extends Component>T find(Component c,Class<T> type,String name){if(type.isInstance(c)&&Objects.equals(name,c.getName()))return type.cast(c);if(c instanceof Container parent)for(Component child:parent.getComponents()){T result=find(child,type,name);if(result!=null)return result;}return null;}
    static JDialog dialog(String title){for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().equals(title))return d;return null;}
    static JButton button(Component c,String text){if(c instanceof JButton b&&b.getText().equals(text))return b;if(c instanceof Container parent)for(Component child:parent.getComponents()){var b=button(child,text);if(b!=null)return b;}return null;}
    static void dialogPicture(JDialog dialog,String name)throws Exception{var image=ui(()->{var root=dialog.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),4_000_000);});try{javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());}finally{image.flush();}}
    static void picture(String name)throws Exception{var image=ui(()->{var root=editor.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),4_000_000);});try{javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());}finally{image.flush();}}
    static List<String> javaCommand(String mode){var c=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.awt.headless=true","-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),AgentDelegationDesktopProbe.class.getName(),mode));return c;}
    static void pauseDemo()throws Exception{if("1".equals(System.getenv("SHED_DELEGATION_DEMO")))Thread.sleep(1800);}
    static void launch(String mode)throws Exception{
        SwingUtilities.invokeLater(()->AgentTaskLauncher.show(editor));await(()->dialog("Delegate Task")!=null);dialogPicture(ui(()->dialog("Delegate Task")),"00-task-setup");
        ui(()->{var d=dialog("Delegate Task");find(d,JTextField.class,"agent.launchName").setText("Fixture CLI");find(d,JTextField.class,"agent.launchCommand").setText(MiniJson.stringify(javaCommand(mode)));find(d,JTextArea.class,"agent.task").setText("Verify the real delegated editor with a fixed Java fixture.");button(d,"OK").doClick();return null;});
        await(()->dialog("Delegate control to Fixture CLI?")!=null);
        dialogPicture(ui(()->dialog("Delegate control to Fixture CLI?")),"00-approval");
        pauseDemo();
        if(ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive))throw new AssertionError("Agent process started before consent");
        ui(()->{button(dialog("Delegate control to Fixture CLI?"),"Allow Control").doClick();return null;});
    }
    static void bootstrapAttach()throws Exception {
        Path task=output.resolve("bootstrap-task.txt");Files.writeString(task,"Check the externally initiated attachment.");
        var command=new ArrayList<>(javaCommand("unused"));command.removeLast();command.removeLast();command.addAll(List.of("shed.Texteditor","delegate","--connect",ui(()->editor.visualAttachment().descriptor().toString()),"--agent","External CLI","--task-file",task.toString()));
        Process child=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        try {
            child.getOutputStream().close();await(()->dialog("Delegate control to External CLI?")!=null);
            ui(()->{button(dialog("Delegate control to External CLI?"),"Allow Control").doClick();return null;});
            if(!child.waitFor(15,TimeUnit.SECONDS))throw new AssertionError("Bootstrap CLI deadline");
            String raw=new String(child.getInputStream().readNBytes(20000),StandardCharsets.UTF_8);if(child.exitValue()!=0)throw new AssertionError(raw);
            var result=MiniJson.asObject(MiniJson.parseStrict(raw));String id=(String)result.get("delegationId");if(!"active".equals(result.get("state"))||!editor.agentDelegation().lease.allowed(id))throw new AssertionError("Wrong bootstrap lease");
            ui(()->{editor.agentDelegation().command("finish",id,"","");return null;});events.add(Map.of("phase","external_cli_bootstrap_attach_wait_for_real_approval_and_disconnect","status","PASS"));
        }finally{if(child.isAlive())child.destroyForcibly();child.waitFor(2,TimeUnit.SECONDS);child.getInputStream().close();child.getErrorStream().close();}
    }
    static void closeSummary()throws Exception{pauseDemo();ui(()->{var d=dialog("Agent-reported session summary");if(d!=null)button(d,"OK").doClick();return null;});}
    static Map<String,Object> ctl(String...argv)throws Exception{
        var args=new ArrayList<String>(List.of("ctl"));args.addAll(List.of(argv));var bytes=new ByteArrayOutputStream();int result=ShedControlCli.run(args.toArray(String[]::new),InputStream.nullInputStream(),bytes,OutputStream.nullOutputStream());
        if(result!=0)throw new AssertionError("Fixture CLI rejected: "+bytes.toString(StandardCharsets.UTF_8));return ShedControlCli.result(bytes.toString(StandardCharsets.UTF_8));
    }
    static void fake(String mode)throws Exception {
        Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();Path cwd=Path.of(".").toRealPath();if(!cwd.startsWith(fixture.resolve("run")))throw new AssertionError("Disposable project required");
        if(mode.equals("--pipe-child")){Thread.sleep(15000);return;}
        String prompt=new String(System.in.readNBytes(32769),StandardCharsets.UTF_8);if(!prompt.contains("SHED_DELEGATION"))throw new AssertionError("Missing task instructions");
        if(mode.equals("--exit-seven")){System.out.println("Fixture failure");System.exit(7);}
        if(mode.equals("--hang")){Thread.sleep(30000);return;}
        if(mode.equals("--pipes")){var child=new ProcessBuilder(javaCommand("--pipe-child")).start();Files.writeString(cwd.resolve("pipe-child.pid"),Long.toString(child.pid()));Thread.sleep(200);return;}
        ctl("read","Demo.txt","--agent","Fixture CLI","--limit","2048");Thread.sleep("1".equals(System.getenv("SHED_DELEGATION_DEMO"))?2400:900);
        Path source=cwd.resolve("Demo.txt");Files.writeString(source,Files.readString(source)+"Written by the delegated fixture.\n");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        ctl("sync","Demo.txt","--sha256",hash,"--timeout-ms","3000");
        Files.writeString(cwd.resolve("Created.txt"),"Created by the fixture.\n");Files.move(cwd.resolve("MoveMe.txt"),cwd.resolve("Moved.txt"));Files.delete(cwd.resolve("DeleteMe.txt"));
        Thread.sleep(600);System.out.println("Modified Demo.txt; created Created.txt; moved MoveMe.txt to Moved.txt; deleted DeleteMe.txt. Exact freshness acknowledged. No tests claimed.");
    }
    public static void main(String[] args)throws Exception{
        if(args.length==1){fake(args[0]);return;}
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        if(!output.startsWith(fixture.resolve("run"))||!project.startsWith(fixture.resolve("run")))throw new AssertionError("Disposable fixture required");
        Runtime.getRuntime().addShutdownHook(new Thread(()->ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly),"fixture-owned-children"));
        Thread watchdog=Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(100000);System.exit(124);}catch(InterruptedException done){}});int exit=1;
        try {
            Files.writeString(project.resolve("Demo.txt"),"Real Shed editor source.\n");Files.writeString(project.resolve("MoveMe.txt"),"A moved file.\n");Files.writeString(project.resolve("DeleteMe.txt"),"A deleted file.\n");
            editor=ui(()->new Texteditor(new String[]{project.toString()}));await(()->!editor.stagedStartupPending&&editor.isShowing());
            ui(()->{editor.setSize(1250,820);editor.workspaceController.addFolder(project,true);return null;});
            launch("--success");await(()->editor.agentDelegation().lease.state().equals("active")&&editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().getName().equals("Demo.txt"));
            await(()->"Agent reading".equals(editor.writingArea.getClientProperty("shed.agentActivity"))&&"top-to-bottom".equals(editor.writingArea.getClientProperty("shed.agentDirection")));Thread.sleep(120);
            ui(()->{if(editor.writingArea.isEditable()||!editor.agentOwnsEditor())throw new AssertionError("Human editing not suspended");return null;});picture("01-agent-reading");
            await(()->editor.agentDelegation().lease.state().equals("completed"));await(()->dialog("Agent-reported session summary")!=null);closeSummary();
            await(()->((Number)editor.toolWindowHost.watchAgentChanges("state","Fixture CLI").get("files")).intValue()==4);
            ui(()->{if(editor.agentOwnsEditor()||!editor.writingArea.isEditable()||editor.getCurrentBuffer().isModified())throw new AssertionError("Ownership did not restore cleanly");var demo=editor.buffers.stream().filter(b->b.hasFilePath()&&b.getFile().getName().equals("Demo.txt")).findFirst().orElseThrow();if(!demo.getContent().equals("Real Shed editor source.\nWritten by the delegated fixture.\n"))throw new AssertionError("Exact source mismatch: "+demo.getContent());return null;});
            await(()->ProcessHandle.current().descendants().noneMatch(ProcessHandle::isAlive));picture("02-handback-changes");events.add(Map.of("phase","gui_approval_external_java_read_write_sync_create_move_delete_handback","status","PASS"));
            ui(()->{editor.formatOnSaveController.requestCurrent(null,false,false);if(editor.canDelegateWindow())throw new AssertionError("Pending save allowed delegation");return null;});await(editor::canDelegateWindow);events.add(Map.of("phase","pending_save_rejects_control_transfer","status","PASS"));
            launch("--hang");await(()->editor.agentDelegation().lease.state().equals("active"));String revoked=editor.agentDelegation().lease.id();
            ui(()->{var stop=find(editor.getRootPane().getGlassPane(),JButton.class,"agent.takeBack");if(stop==null)throw new AssertionError("Take back button unavailable");stop.doClick();return null;});await(()->ProcessHandle.current().descendants().noneMatch(ProcessHandle::isAlive));
            if(editor.agentDelegation().lease.allowed(revoked))throw new AssertionError("Revoked token remained live");events.add(Map.of("phase","takeback_cancels_owned_process_and_restores_input","status","PASS"));
            bootstrapAttach();
            var samples=new ArrayList<Double>();for(int i=0;i<40;i++)samples.add(ui(()->{long start=System.nanoTime();editor.writingArea.append("x");editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());return (System.nanoTime()-start)/1e6;}));
            var sorted=new ArrayList<>(samples);Collections.sort(sorted);double p95=sorted.get(37);if(p95>16)throw new AssertionError("Typing p95 exceeded 16 ms: "+p95);events.add(Map.of("phase","post_handback_completed_paint","first_ms",samples.getFirst(),"p95_ms",p95,"samples_ms",samples));
            // Direct Java lifecycle control also checks a background child retaining the output pipe.
            Path pipeDir=Files.createDirectory(project.getParent().resolve("pipes"));Path descriptor=ui(()->editor.visualAttachment().descriptor());
            var env=new HashMap<>(System.getenv());long start=System.nanoTime();var result=ManagedAgentTask.run(javaCommand("--pipes"),pipeDir,descriptor,"fixture",AgentTaskInstructions.prompt("Fixture task","Fixture"),env,()->true,5000);
            long childPid=Long.parseLong(Files.readString(pipeDir.resolve("pipe-child.pid")));if(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))throw new AssertionError("Pipe child retained");events.add(Map.of("phase","background_child_and_inherited_pipe_cleanup","exit",result.exit(),"elapsed_ms",(System.nanoTime()-start)/1e6));
            try{ManagedAgentTask.run(javaCommand("--hang"),pipeDir,descriptor,"fixture",AgentTaskInstructions.prompt("Fixture task","Fixture"),env,()->true,200);throw new AssertionError("Timeout accepted");}catch(TimeoutException expected){}
            var failed=ManagedAgentTask.run(javaCommand("--exit-seven"),pipeDir,descriptor,"fixture",AgentTaskInstructions.prompt("Fixture task","Fixture"),env,()->true,5000);if(failed.exit()!=7)throw new AssertionError("Failed CLI treated as success");
            if(ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive))throw new AssertionError("Lifecycle child retained");events.add(Map.of("phase","deadline_and_failed_exit_cleanup","status","PASS"));
            await(()->ManagedAgentTask.activeIoWorkers()==0);long threads=ManagedAgentTask.activeIoWorkers();if(threads!=0)throw new AssertionError("Task pipe workers retained");
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","events",events,"remaining_task_io_workers",threads,"native_shell","NOT_USED","model","FIXED_JAVA_TEST_DOUBLE")));exit=0;
        }catch(Throwable failed){failed.printStackTrace();Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",failed.toString(),"events",events)));}
        finally{if(editor!=null)ui(()->{editor.agentDelegation().close();editor.handleAgentCommand("api stop");editor.asyncJobService.shutdownNow();for(Window w:Window.getWindows())w.dispose();return null;});watchdog.interrupt();System.exit(exit);}
    }
}
