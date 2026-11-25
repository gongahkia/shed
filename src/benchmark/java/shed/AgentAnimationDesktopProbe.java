package shed;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;

public class AgentAnimationDesktopProbe {
    static Texteditor editor;
    static Path project,output;
    static final Set<String> modes=new LinkedHashSet<>();
    static final List<Double> typingMs=new ArrayList<>();
    static final Set<String> directions=new LinkedHashSet<>();
    static int readingFrames;
    static String descriptor;
    static <T>T ui(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);}
    static void await(BooleanSupplier ready)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!ui(ready::getAsBoolean)){if(System.nanoTime()>until)throw new IllegalStateException("Visible stage timed out");Thread.sleep(50);}}
    static void stage(String name,long pause)throws Exception{System.out.println(name);System.out.flush();Thread.sleep(pause);}
    static Map<String,Object> cli(String...args)throws Exception{
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.awt.headless=true","-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),"shed.Texteditor","ctl","--connect",descriptor));command.addAll(List.of(args));
        var child=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        try{child.getOutputStream().close();if(!child.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("CLI deadline");String response=new String(child.getInputStream().readNBytes(32769),java.nio.charset.StandardCharsets.UTF_8);if(child.exitValue()!=0)throw new IllegalStateException(response);return ShedControlCli.result(response);}
        finally{if(child.isAlive()){child.destroy();if(!child.waitFor(2,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(2,TimeUnit.SECONDS);}}child.getInputStream().close();child.getErrorStream().close();}
    }
    static void picture(String name)throws Exception{var image=ui(()->{var root=editor.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),16_000_000);});javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());}
    static void read(String path,int offset)throws Exception{
        cli("read",path,"--offset",Integer.toString(offset),"--limit","900","--agent","Demo agent");
        ui(()->{directions.add(Objects.toString(editor.writingArea.getClientProperty("shed.agentDirection")));return null;});
        if(readingFrames++<2){picture("reading-"+readingFrames+"-top");Thread.sleep(750);picture("reading-"+readingFrames+"-down");}
        stage("READ: "+path+" at UTF-16 offset "+offset,3000);
    }
    static void frames(String name,int count)throws Exception {
        await(()->editor.writingArea.getClientProperty("shed.agentTour")!=null);
        var buffer=ui(editor::getCurrentBuffer);String source=ui(buffer::getContent);long revision=ui(buffer::documentRevision);int caret=ui(()->editor.writingArea.getCaretPosition()),mark=ui(()->editor.writingArea.getCaret().getMark());boolean undo=ui(()->buffer.getUndoManager().canUndo());
        for(int i=0;i<count;i++){
            final int frame=i;
            var image=ui(()->{String mode=Objects.toString(editor.writingArea.getClientProperty("shed.agentAnimation"),"complete");modes.add(mode);if(editor.writingArea.getClientProperty("shed.agentDirection") instanceof String direction)directions.add(direction);
                if(!buffer.getContent().equals(source)||buffer.documentRevision()!=revision||buffer.isModified()||editor.writingArea.getCaretPosition()!=caret||editor.writingArea.getCaret().getMark()!=mark||buffer.getUndoManager().canUndo()!=undo)throw new AssertionError("Playback changed source/revision/dirty/caret/history");
                var root=editor.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),16_000_000);});
            javax.imageio.ImageIO.write(image,"png",output.resolve(name+"-"+frame+".png").toFile());
            Thread.sleep(100);
        }
    }
    static void measureTyping()throws Exception{
        ui(()->{editor.toolWindowHost.watchAgentChanges("stop","Demo agent");return null;});cli("open","Demo.java");
        var buffer=ui(editor::getCurrentBuffer);String before=ui(buffer::getContent);
        var tour=ui(()->{var area=editor.writingArea;var range=new AgentEditPresentation.Range(0,5);var t=new AgentChangeTour(area,editor.getActivePane().agentPresentation(),List.of(range),before,"Demo agent",()->true,()->{},()->{},List.of(new AgentChangePlayback.Edit(range,"")));t.start();return t;});
        String input="// human typing demo\n";
        for(int i=0;i<input.length();i++){String key=input.substring(i,i+1);typingMs.add(ui(()->{long start=System.nanoTime();editor.writingArea.replaceSelection(key);editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());return (System.nanoTime()-start)/1e6;}));}
        ui(()->{if(tour.running()||editor.writingArea.getClientProperty(AgentChangePlayback.KEY)!=null||!buffer.getUndoManager().canUndo())throw new AssertionError("Human typing did not retire playback with ordinary Undo intact");tour.close();buffer.load(editor.configManager);editor.loadBufferIntoEditor(buffer);return null;});
        var warm=new ArrayList<>(typingMs.subList(1,typingMs.size()));Collections.sort(warm);double p95=warm.get((int)Math.ceil(warm.size()*.95)-1);
        if(p95>16)throw new AssertionError("Typing paint p95 exceeds 16 ms: "+p95);
        System.out.println("TYPING_PAINT first_ms="+typingMs.getFirst()+" warm_p95_ms="+p95);
    }
    public static void main(String[] args)throws Exception{
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        if(!output.startsWith(fixture.resolve("run"))||!project.startsWith(fixture.resolve("run"))||!Path.of(System.getProperty("user.home")).toRealPath().startsWith(fixture))throw new IllegalArgumentException("Disposable fixture required");
        try{
            editor=ui(()->new Texteditor(new String[]{project.resolve("Demo.java").toString()}));
            await(()->!editor.stagedStartupPending&&editor.getCurrentBuffer()!=null);
            ui(()->{editor.setTitle("Shed — Live agent demonstration");editor.setSize(1400,900);editor.workspaceController.addFolder(project,true);editor.treeGitController.showFileTree(project.toString());editor.activateEditorPane(editor.resolveTreeContentPaneForOpen());editor.setMode(EditorMode.INSERT);editor.hideToolWindow();editor.toFront();editor.writingArea.requestFocusInWindow();editor.handleAgentCommand("api start control");editor.toolWindowHost.watchAgentChanges("start","Demo agent");return null;});
            await(()->editor.visualAttachment()!=null&&Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Demo agent").get("ready")));
            descriptor=ui(()->editor.visualAttachment().descriptor().toString());stage("READY: watch the Shed window",2500);
            read("Demo.java",0);read("Demo.java",2200);read("Demo.java",4400);
            cli("follow","--agent","Demo agent");
            String before=Files.readString(project.resolve("Demo.java"));String after=before.replace("CHANGE_TOP","TOP: greeting improvements").replace("CHANGE_MIDDLE","MIDDLE: helper methods added").replace("CHANGE_BOTTOM","BOTTOM: demo complete").replace("return \"Hello, \" + name;","return \"Hello, \" + (name == null || name.isBlank() ? \"friend\" : name.trim());");
            int end=after.lastIndexOf('}');String methods="";for(int i=1;i<=5;i++)methods+="    public static void placeholder"+i+"() {}\n";after=after.substring(0,end)+methods+after.substring(end);
            Files.writeString(project.resolve("Demo.java"),after);String expected=after;await(()->editor.getCurrentBuffer().getContent().equals(expected));frames("typing",10);stage("EDIT: quick typing and new functions",4500);
            read("Demo.java",1600);String current=Files.readString(project.resolve("Demo.java"));int deletionStart=current.indexOf("// REMOVE_START"),deletionEnd=current.indexOf("// REMOVE_END")+"// REMOVE_END\n".length();
            if(deletionStart<0||deletionEnd<deletionStart)throw new AssertionError("Deletion fixture markers missing");
            Files.writeString(project.resolve("Demo.java"),current.substring(0,deletionStart)+current.substring(deletionEnd));frames("deleting",9);stage("DELETE BLOCK: highlight and right-to-left removal",1800);
            read("Demo.java",2000);current=Files.readString(project.resolve("Demo.java"));int rewriteStart=current.indexOf("// REWRITE_START"),rewriteEnd=current.indexOf("// REWRITE_END")+"// REWRITE_END\n".length();
            if(rewriteStart<0||rewriteEnd<rewriteStart)throw new AssertionError("Rewrite fixture markers missing");
            String replacement="// rewritten chunk: begin\n"+"// phased replacement block\n".repeat(25)+"// rewritten chunk: end\n";
            Files.writeString(project.resolve("Demo.java"),current.substring(0,rewriteStart)+replacement+current.substring(rewriteEnd));frames("rewriting",16);stage("REWRITE: old chunk removed; replacement phases in left-to-right",2000);
            String interrupted=Files.readString(project.resolve("Demo.java")).replace("TOP: greeting improvements","TOP: next observed edit");Files.writeString(project.resolve("Demo.java"),interrupted);
            await(()->editor.writingArea.getClientProperty("shed.agentTour")!=null);
            var nextRead=cli("read","Demo.java","--offset","0","--limit","900","--agent","Demo agent");if(!interrupted.substring(0,900).equals(nextRead.get("content")))throw new AssertionError("Read superseded to the wrong source");
            ui(()->{if(editor.writingArea.getClientProperty(AgentChangePlayback.KEY)!=null||!"top-to-bottom".equals(editor.writingArea.getClientProperty("shed.agentDirection"))||!Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Demo agent").get("following")))throw new AssertionError("Explicit read did not retire the tour while retaining Follow");return null;});stage("READ INTERRUPTS REPLAY: explicit reading wins without disabling Follow",2000);
            Files.writeString(project.resolve("Helpers.java"),"public class Helpers {\n    public static String farewell(String name) {\n        return \"Goodbye, \" + name;\n    }\n}\n");await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().getName().equals("Helpers.java"));stage("CREATE: Helpers.java",3500);read("Helpers.java",0);
            read("Notes.txt",0);Files.writeString(project.resolve("Notes.txt"),"Demo notes\n- Greeting handles blank names\n- Five placeholder methods added\n- Helpers.java created\n");stage("MODIFY: Notes.txt",3500);
            read("RenameMe.txt",0);Files.move(project.resolve("RenameMe.txt"),project.resolve("Renamed.txt"));stage("RENAME: RenameMe.txt to Renamed.txt",4000);
            read("DeleteMe.txt",0);Files.delete(project.resolve("DeleteMe.txt"));stage("DELETE: DeleteMe.txt — watch the Explorer badge",4000);
            measureTyping();ui(()->{editor.toolWindowHost.showTab(ToolWindowHost.Tab.GIT);return null;});stage("GIT: live status and diffs — leaving this visible for 20 seconds",20000);
            if(!modes.containsAll(Set.of("typing","chunks","deleting")))throw new AssertionError("Missing animation modes: "+modes);
            if(!directions.containsAll(Set.of("left-to-right","right-to-left","top-to-bottom")))throw new AssertionError("Missing motion directions: "+directions);
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","modes",modes.stream().toList(),"directions",directions.stream().toList(),"source_unchanged_by_animation",true,"typing_paint_ms",typingMs,"scope","direct Java/Swing, real authenticated CLI, disposable filesystem writes; no actual model")));
            System.out.println("DEMO_PASS: reads, edits, creation, modification, rename, deletion and Git presentation");
        }finally{if(editor!=null)ui(()->{editor.dispatchEvent(new java.awt.event.WindowEvent(editor,java.awt.event.WindowEvent.WINDOW_CLOSING));return null;});}
        System.exit(0);
    }
}
