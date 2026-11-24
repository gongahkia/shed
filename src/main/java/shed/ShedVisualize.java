package shed;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.swing.*;

/** Explicit launch-and-return helper. Starts only the current Java application, never a shell or agent. */
final class ShedVisualize {
    private ShedVisualize() { }
    static boolean handles(String[] args){return args.length>0&&(args[0].equals("visualize")||args[0].equals("--visualize-window"));}
    static void run(String[] args)throws Exception {
        if(args[0].equals("--visualize-window")){window(args);return;}
        String project=".",agent="Agent";boolean path=false;
        for(int i=1;i<args.length;i++){
            if(args[i].equals("--agent")&&i+1<args.length)agent=args[++i];
            else if(!path&&!args[i].startsWith("--")){project=args[i];path=true;}else throw new IllegalArgumentException("Usage: shed visualize [project] [--agent Codex]");
        }
        if(agent.isBlank()||agent.length()>40||agent.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid agent label");
        Path root=Path.of(project).toRealPath();if(!Files.isDirectory(root))throw new IllegalArgumentException("Expected a project directory");
        Path base=PrivateWorkspaceFiles.directory(Path.of(System.getProperty("user.home"),".shed","sessions","visualize"));
        Path directory=PrivateWorkspaceFiles.directory(base.resolve("launch-"+UUID.randomUUID()));Path ready=directory.resolve("ready.json");
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").startsWith("Windows")?"java.exe":"java").toString();
        var command=new ArrayList<String>(List.of(javaExecutable,"-Djava.awt.headless=false","-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir")));
        String prefs=System.getProperty("java.util.prefs.userRoot");if(prefs!=null)command.add("-Djava.util.prefs.userRoot="+prefs);
        command.addAll(List.of("-cp",System.getProperty("java.class.path"),"shed.Texteditor","--visualize-window",root.toString(),agent,directory.toString()));
        Process child=new ProcessBuilder(command).directory(root.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();boolean success=false;
        try{
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
            while(child.isAlive()&&System.nanoTime()<until){
                if(Files.exists(ready,LinkOption.NOFOLLOW_LINKS)){
                    byte[] bytes=PrivateWorkspaceFiles.read(ready,8192);var response=MiniJson.asObject(MiniJson.parseStrict(new String(bytes,StandardCharsets.UTF_8)));
                    if(response==null||!Boolean.TRUE.equals(response.get("ready"))||!(response.get("pid") instanceof Number pid)||pid.longValue()!=child.pid()||!root.toString().equals(response.get("project")))throw new IllegalStateException("Invalid visualization readiness");
                    System.out.println(MiniJson.stringify(response));success=true;PrivateWorkspaceFiles.deleteIfMatching(ready,bytes);return;
                }Thread.sleep(50);
            }throw new IllegalStateException("Visualization did not become ready; check project size and desktop availability");
        }finally{
            if(!success){child.destroy();if(!child.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))child.destroyForcibly();}
            try{Files.deleteIfExists(directory);}catch(java.io.IOException busy){/* child may still be retiring its owned readiness record */}
        }
    }
    private static void window(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Invalid visualization launch");
        Path project=Path.of(args[1]).toRealPath(),directory=PrivateWorkspaceFiles.existingDirectory(Path.of(args[3]));
        Path expected=PrivateWorkspaceFiles.existingDirectory(Path.of(System.getProperty("user.home"),".shed","sessions","visualize"));
        if(!expected.equals(directory.getParent())||!directory.getFileName().toString().matches("launch-[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid private launch directory");
        SwingUtilities.invokeLater(()->{
            var editor=new Texteditor(new String[]{project.toString()});long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
            var ready=new java.util.concurrent.atomic.AtomicBoolean();
            javax.swing.Timer timer=new javax.swing.Timer(100,null);timer.addActionListener(e->{
                if(!editor.isDisplayable()||System.nanoTime()>deadline){timer.stop();return;}
                if(editor.stagedStartupPending)return;
                if(!ready.getAndSet(true)){
                    editor.workspaceController.addFolder(project,true);editor.handleAgentCommand("api start control");editor.toolWindowHost.watchAgentChanges("start",args[2]);
                }
                var attachment=editor.visualAttachment();
                var watch=editor.toolWindowHost.watchAgentChanges("state",args[2]);
                if(attachment==null||!Boolean.TRUE.equals(watch.get("ready")))return;
                var result=Map.of("schemaVersion",1,"ready",true,"project",project.toString(),"descriptor",attachment.descriptor().toString(),"credential",attachment.credential().toString(),"pid",ProcessHandle.current().pid(),"agent",args[2]);
                timer.stop();Thread.ofVirtual().name("shed-visualize-ready").start(()->{try{PrivateWorkspaceFiles.create(directory,"ready.json",MiniJson.stringify(result).getBytes(StandardCharsets.UTF_8));}catch(java.io.IOException failure){System.getLogger("shed.visualize").log(System.Logger.Level.WARNING,"Visualization readiness could not be published");}});
            });timer.start();editor.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){timer.stop();}});
        });
    }
}
