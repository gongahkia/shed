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

/** Fixed direct-Java external CLI against one owned workbench; no shell, model or desktop automation. */
public final class AgentUiDesktopProbe {
    static Texteditor editor;
    static Path project,output,descriptor;
    static int calls;
    static final List<Map<String,Object>> samples=new ArrayList<>();
    static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);}
    static void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!edt(ready::getAsBoolean)){if(System.nanoTime()>end)throw new AssertionError("Owned UI deadline");Thread.sleep(16);}}
    static void picture(String name)throws Exception{
        var image=edt(()->{var root=editor.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),8_000_000);});
        try{javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());}finally{image.flush();}
    }
    static Map<String,Object> cli(String body,String capture,String...args)throws Exception{
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.awt.headless=true",
            "-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),
            "-Djava.util.prefs.userRoot="+System.getProperty("java.util.prefs.userRoot"),"-cp",System.getProperty("java.class.path"),
            Client.class.getName(),"ctl","--connect",descriptor.toString()));command.addAll(List.of(args));
        Path log=output.resolve("client-"+(++calls)+".json");
        long started=System.nanoTime();Process process=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            try(var input=process.getOutputStream()){input.write(body.getBytes(StandardCharsets.UTF_8));}
            if(capture!=null){
                await(()->Arrays.stream(editor.getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER)).anyMatch(c->c instanceof AgentUiPresentation));
                if (args[0].equals("ui-text")) await(()->Arrays.stream(editor.getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER)).anyMatch(c->c instanceof AgentUiPresentation p && p.morph()>=.8f));
                else Thread.sleep(190);
                picture(capture);
            }
            if(!process.waitFor(8,TimeUnit.SECONDS))throw new AssertionError("External UI CLI deadline");
            if(Files.size(log)>60_000)throw new AssertionError("Unbounded CLI response");
            String raw=Files.readString(log);if(process.exitValue()!=0)throw new AssertionError(raw);
            samples.add(Map.of("command",args[0],"elapsed_ms",(System.nanoTime()-started)/1e6,"captured",capture!=null));
            return ShedControlCli.result(raw);
        }finally{
            if(process.isAlive())process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);
            process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();
            if(process.isAlive())throw new AssertionError("Owned CLI did not exit");
        }
    }
    static Map<String,Object> action(String id,String body,String capture)throws Exception{
        var observed=MiniJson.asObject(cli("",null,"ui-state").get("observation"));String revision=observed.get("revision").toString();
        var result=id.equals("find.query")?cli(body,capture,"ui-text","--ui-revision",revision,"--agent",capture==null?"Codex":"Claude Code","--text-file","-"):
            cli("",capture,"ui-action",id,"--ui-revision",revision,"--agent","Codex");
        if("1".equals(System.getenv("SHED_DELEGATION_DEMO")))Thread.sleep(1000);
        return MiniJson.asObject(result.get("observation"));
    }
    public static final class Client {
        public static void main(String[] args){System.exit(ShedControlCli.run(args,System.in,System.out,System.err));}
    }
    public static void main(String[] args)throws Exception{
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path root=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        if(!project.startsWith(root.resolve("run"))||!output.startsWith(root.resolve("run"))||!Path.of(System.getProperty("user.home")).toRealPath().startsWith(root))throw new AssertionError("Disposable fixture required");
        int exit=1;
        try{
            StringBuilder source=new StringBuilder();for(int i=1;i<=500;i++)source.append(i==20||i==250||i==480?"demoToken: ":"line: ").append(i).append('\n');
            Path file=project.resolve("FindDemo.txt");Files.writeString(file,source);
            editor=edt(()->new Texteditor(new String[]{file.toString()}));
            await(()->!editor.stagedStartupPending&&editor.getCurrentBuffer()!=null&&editor.getCurrentBuffer().getFile()!=null);
            edt(()->{editor.setTitle("Shed — Shared agent UI actions");editor.setSize(1400,900);editor.workspaceController.addFolder(project,true);editor.setMode(EditorMode.INSERT);editor.hideToolWindow();editor.toFront();editor.handleAgentCommand("api start control");return null;});
            await(()->editor.visualAttachment()!=null);descriptor=edt(()->editor.visualAttachment().descriptor());
            long revision=edt(()->editor.getCurrentBuffer().documentRevision());Object document=edt(()->editor.writingArea.getDocument());
            var catalog=cli("",null,"ui-actions");if(!AgentUiContract.accepts(catalog.get("observation"),true))throw new AssertionError("Invalid catalog");
            action("find.open","",null);var state=action("find.query","demoToken",null);
            if(!"ready".equals(state.get("phase"))||!Long.valueOf(3).equals(state.get("matches")))throw new AssertionError("Find did not settle: "+state);
            action("find.next","","01-pointer-click");action("find.next","",null);picture("02-navigated");
            int agentCaret=edt(()->editor.writingArea.getCaretPosition());
            edt(()->{editor.writingArea.setCaretPosition(0);editor.inlineFindBar.showFind("demoToken");return null;});
            await(()->"ready".equals(editor.inlineFindBar.agentState().get("phase")));
            edt(()->{editor.inputController.performMenuAction(PlainKeymap.Action.FIND_NEXT);editor.inputController.performMenuAction(PlainKeymap.Action.FIND_NEXT);return null;});
            if(agentCaret!=edt(()->editor.writingArea.getCaretPosition()))throw new AssertionError("Human/agent navigation differs");
            action("find.previous","",null);action("find.case","",null);action("find.regex","",null);action("find.word","",null);
            edt(()->{editor.setThemeFromCommand("dracula");editor.setSize(980,720);return null;});
            action("find.query","demoToken",null);action("find.previous","","03-dark-narrow-pointer");
            edt(()->{editor.setThemeFromCommand("shed-light");editor.setSize(1400,900);return null;});
            action("find.query","demoToken.*", "04-query-typing");action("find.close","",null);
            edt(()->{
                if(editor.writingArea.getDocument()!=document||editor.getCurrentBuffer().documentRevision()!=revision||editor.getCurrentBuffer().isModified()
                    ||editor.getCurrentBuffer().getUndoManager().canUndo()||!source.toString().equals(editor.writingArea.getText()))throw new AssertionError("UI actions changed source or history");
                if(Arrays.stream(editor.getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER)).filter(c->c instanceof AgentUiPresentation).count()!=1)throw new AssertionError("Pointer must retain one session location");return null;
            });
            edt(()->{editor.handleAgentCommand("api stop");return null;});
            await(()->Arrays.stream(editor.getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER)).noneMatch(c->c instanceof AgentUiPresentation));
            if(!source.toString().equals(Files.readString(file)))throw new AssertionError("UI actions wrote source file");
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","native_shell","NOT_USED","external_cli",true,"calls",calls,"samples",samples,
                "source_unchanged",true,"undo_unchanged",true,"human_agent_find_parity",true,"remaining_processes",ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count())));exit=0;
        }catch(Throwable error){error.printStackTrace();Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",error.toString(),"calls",calls,"samples",samples)));}
        finally{if(editor!=null)edt(()->{editor.dispatchEvent(new java.awt.event.WindowEvent(editor,java.awt.event.WindowEvent.WINDOW_CLOSING));return null;});}
        System.exit(exit);
    }
}
