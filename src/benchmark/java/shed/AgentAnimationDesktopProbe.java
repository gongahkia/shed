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
    static final Set<String> intents=new LinkedHashSet<>();
    static final List<Double> intentMs=new ArrayList<>();
    static int readingFrames;
    static int maximumCursors;
    static String descriptor;
    static String readingAgent="Codex";
    static String deletedBlock;
    static boolean looping,checkTerminalHandoff;
    static long cycles;
    static final List<String> edgeChecks=new ArrayList<>();
    static final java.util.concurrent.atomic.AtomicReference<Throwable> uncaught=new java.util.concurrent.atomic.AtomicReference<>();
    static <T>T ui(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);}
    static void await(BooleanSupplier ready)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!ui(ready::getAsBoolean)){if(System.nanoTime()>until)throw new IllegalStateException("Visible stage timed out");Thread.sleep(50);}}
    static void stage(String name,long pause)throws Exception{if(!looping){System.out.println(name);System.out.flush();}Thread.sleep(pause);}
    static void awaitStableContext()throws Exception{
        var previous=ui(editor::captureEditorNavigation);long stableSince=System.nanoTime(),deadline=stableSince+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()-stableSince<TimeUnit.MILLISECONDS.toNanos(250)){
            if(System.nanoTime()>deadline)throw new IllegalStateException("Demo context did not settle after reset");
            Thread.sleep(50);var current=ui(editor::captureEditorNavigation);
            if(!current.equals(previous)){previous=current;stableSince=System.nanoTime();}
        }
    }
    static void beforeReadInterrupt()throws Exception{
        await(()->editor.writingArea.getClientProperty("shed.agentTour")!=null&&"typing".equals(editor.writingArea.getClientProperty("shed.agentAnimation"))&&editor.writingArea.getClientProperty(AgentChangePlayback.KEY) instanceof AgentChangePlayback playback&&playback.directionalProgress()>0);
        // hold visible writing before demonstrating the competing read
        Thread.sleep(700);
        ui(()->{if(editor.writingArea.getClientProperty("shed.agentTour")==null)throw new AssertionError("Replay finished before the read interruption");return null;});
        picture("interrupt-before-read");
    }
    static Map<String,Object> cli(String...args)throws Exception{
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.awt.headless=true","-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),"shed.Texteditor","ctl","--connect",descriptor));command.addAll(List.of(args));
        var child=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        try{child.getOutputStream().close();if(!child.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("CLI deadline");String response=new String(child.getInputStream().readNBytes(32769),java.nio.charset.StandardCharsets.UTF_8);if(child.exitValue()!=0)throw new IllegalStateException(response);return ShedControlCli.result(response);}
        finally{if(child.isAlive()){child.destroy();if(!child.waitFor(2,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(2,TimeUnit.SECONDS);}}child.getInputStream().close();child.getErrorStream().close();}
    }
    static void picture(String name)throws Exception{var image=ui(()->{var root=editor.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),16_000_000);});javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());}
    static void read(String path,int offset)throws Exception{
        cli("read",path,"--offset",Integer.toString(offset),"--limit","900","--agent",readingAgent);
        ui(()->{directions.add(Objects.toString(editor.writingArea.getClientProperty("shed.agentDirection")));return null;});
        if(readingFrames++<2){picture("reading-"+readingFrames+"-top");Thread.sleep(750);picture("reading-"+readingFrames+"-down");}
        stage("READ: "+path+" at UTF-16 offset "+offset,3000);
    }
    static void frames(String name,int count)throws Exception {
        try{await(()->editor.writingArea.getClientProperty("shed.agentTour")!=null);}catch(IllegalStateException timeout){
            System.err.println("TOUR_TIMEOUT_STATE "+MiniJson.stringify(ui(()->Map.of("watch",editor.toolWindowHost.watchAgentChanges("state","Codex"),"source_length",editor.writingArea.getDocument().getLength(),"revision",editor.getCurrentBuffer().documentRevision(),"dirty",editor.getCurrentBuffer().isModified(),"animation",Objects.toString(editor.writingArea.getClientProperty("shed.agentAnimation")),"activity",Objects.toString(editor.writingArea.getClientProperty("shed.agentActivity")),"native_threads",Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).count()))));throw timeout;
        }
        var buffer=ui(editor::getCurrentBuffer);String source=ui(buffer::getContent);long revision=ui(buffer::documentRevision);int caret=ui(()->editor.writingArea.getCaretPosition()),mark=ui(()->editor.writingArea.getCaret().getMark());boolean undo=ui(()->buffer.getUndoManager().canUndo());
        for(int i=0;i<count;i++){
            final int frame=i;
            var image=ui(()->{String mode=Objects.toString(editor.writingArea.getClientProperty("shed.agentAnimation"),"complete");modes.add(mode);if(editor.writingArea.getClientProperty("shed.agentDirection") instanceof String direction)directions.add(direction);
                if(editor.writingArea.getClientProperty("shed.agentCursorCount") instanceof Integer cursors)maximumCursors=Math.max(maximumCursors,cursors);
                if(!buffer.getContent().equals(source)||buffer.documentRevision()!=revision||buffer.isModified()||editor.writingArea.getCaretPosition()!=caret||editor.writingArea.getCaret().getMark()!=mark||buffer.getUndoManager().canUndo()!=undo)throw new AssertionError("Playback changed source/revision/dirty/caret/history");
                var root=editor.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),16_000_000);});
            javax.imageio.ImageIO.write(image,"png",output.resolve(name+"-"+frame+".png").toFile());
            Thread.sleep(100);
        }
    }
    static void measureTyping()throws Exception{measureTyping(true);}
    static void measureTyping(boolean open)throws Exception{
        ui(()->{editor.toolWindowHost.watchAgentChanges("stop","Codex");return null;});if(open)cli("open","Demo.java");
        var buffer=ui(editor::getCurrentBuffer);String before=ui(buffer::getContent);
        var tour=ui(()->{var area=editor.writingArea;var range=new AgentEditPresentation.Range(0,5);var t=new AgentChangeTour(area,editor.getActivePane().agentPresentation(),List.of(range),before,"Codex",()->true,()->{},()->{},List.of(new AgentChangePlayback.Edit(range,"")));t.start();return t;});
        String input="// human typing demo\n";
        for(int i=0;i<input.length();i++){String key=input.substring(i,i+1);typingMs.add(ui(()->{long start=System.nanoTime();editor.writingArea.replaceSelection(key);editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());return (System.nanoTime()-start)/1e6;}));}
        ui(()->{if(tour.running()||editor.writingArea.getClientProperty(AgentChangePlayback.KEY)!=null||!buffer.getUndoManager().canUndo())throw new AssertionError("Human typing did not retire playback with ordinary Undo intact");tour.close();buffer.load(editor.configManager);editor.loadBufferIntoEditor(buffer);return null;});
        var warm=new ArrayList<>(typingMs.subList(1,typingMs.size()));Collections.sort(warm);double p95=warm.get((int)Math.ceil(warm.size()*.95)-1);
        if(p95>16)throw new AssertionError("Typing paint p95 exceeds 16 ms: "+p95);
        System.out.println("TYPING_PAINT first_ms="+typingMs.getFirst()+" warm_p95_ms="+p95);
    }
    static void intent(String kind,String status,int focus,String agent,AgentEditPresentation.Range...ranges)throws Exception{
        awaitStableContext();
        var buffer=ui(editor::getCurrentBuffer);String source=ui(buffer::getContent);long revision=ui(buffer::documentRevision);boolean dirty=ui(buffer::isModified),undo=ui(()->buffer.getUndoManager().canUndo());int dot=ui(()->editor.writingArea.getCaret().getDot()),mark=ui(()->editor.writingArea.getCaret().getMark());
        String pairs=Arrays.stream(ranges).map(r->r.start()+":"+r.end()).collect(java.util.stream.Collectors.joining(","));
        long started=System.nanoTime();var response=cli("intent","--kind",kind,"--status",status,"--ranges",pairs,"--focus",Integer.toString(focus),"--agent",agent,"--document",ui(()->editor.captureEditorMetadata().documentId()),"--revision",Long.toString(revision));intentMs.add((System.nanoTime()-started)/1e6);
        if(!"shown".equals(response.get("outcome")))throw new AssertionError("Intent not acknowledged");Thread.sleep(400);
        ui(()->{var area=editor.writingArea;
            if(!kind.equals(area.getClientProperty("shed.agentIntent"))||!status.equals(area.getClientProperty("shed.agentIntentStatus")))throw new AssertionError("Intent not visible: "+kind);
            if(buffer!=editor.getCurrentBuffer()||!source.equals(buffer.getContent())||revision!=buffer.documentRevision()||dirty!=buffer.isModified()||undo!=buffer.getUndoManager().canUndo()||dot!=area.getCaret().getDot()||mark!=area.getCaret().getMark())throw new AssertionError("Intent changed source or human state");
            var bounds=area.modelToView2D(ranges[focus].start());if(bounds==null||!area.getVisibleRect().intersects(bounds))throw new AssertionError("Intent target not revealed: "+kind);
            return null;});
        intents.add(kind);picture("intent-"+kind+"-"+status+"-"+focus);stage("INTENT: "+agent+" · "+kind+" · "+status,1200);
    }
    static AgentEditPresentation.Range span(String source,String word){int start=source.indexOf(word);if(start<0)throw new AssertionError("Intent fixture missing "+word);return new AgentEditPresentation.Range(start,start+word.length());}
    static void activeFile(String name,String source)throws Exception{
        await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().toPath().toAbsolutePath().normalize().equals(project.resolve(name))&&editor.getCurrentBuffer().getContent().equals(source));
    }
    static boolean compileExample(String source)throws Exception{
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        var file=new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///Demo.java"),javax.tools.JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return source;}};
        try(var manager=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8)){
            return compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-d",output.toString()),null,List.of(file)).call();
        }
    }
    static void intentDemo()throws Exception{
        cli("open","Demo.java");ui(()->{editor.toolWindowHost.watchAgentChanges("pause","Codex");return null;});
        String original=Files.readString(project.resolve("Demo.java"));var top=span(original,"demonstration checkpoint 010");var middle=span(original,"demonstration checkpoint 150");var greet=span(original,"greet");var method=span(original,"placeholder1");
        for(int focus=0;focus<3;focus++)intent("search","active",focus,"Codex",top,middle,greet);
        intent("navigate","active",0,"Claude Code",greet);intent("target","active",0,"OpenCode",method);
        String formatted=original.replace("public static void placeholder1() {}","public static void placeholder1() {\n    }");
        if(formatted.equals(original))throw new AssertionError("Formatting fixture did not change source");
        replay(formatted,"format-as-edit",24,"deleting","typing");
        stage("FORMAT: ordinary deletion and creation",700);
        replay(original,"revert-as-edit",24,"deleting","typing");
        stage("REVERT: ordinary inverse source edits",700);
        ui(()->{editor.toolWindowHost.watchAgentChanges("pause","Codex");return null;});
        intent("verify","checking",0,"Local compiler",greet);if(!compileExample(original))throw new AssertionError("Valid source did not compile");intent("verify","passed",0,"Local compiler",greet);
        if(compileExample("public class Demo { invalid syntax }"))throw new AssertionError("Invalid compiler control unexpectedly passed");intent("verify","failed",0,"Invalid fixture check",greet);
        // simulate a newer human draft, then report the conflict without overwriting it
        ui(()->{editor.writingArea.setCaretPosition(0);editor.writingArea.replaceSelection("// human draft retained\n");return null;});
        intent("conflict","active",0,"Local agent",new AgentEditPresentation.Range(0,23));
        ui(()->{editor.getCurrentBuffer().getUndoManager().undo();if(!original.equals(editor.getCurrentBuffer().getContent()))throw new AssertionError("Human draft Undo changed source");editor.getCurrentBuffer().load(editor.configManager);editor.loadBufferIntoEditor(editor.getCurrentBuffer());return null;});
        if(!intents.containsAll(AgentBufferIntent.KINDS))throw new AssertionError("Missing intent visuals: "+intents);
    }
    static JOptionPane terminalApproval(java.awt.Component component){
        if(component instanceof JOptionPane option)return option;
        if(component instanceof java.awt.Container container)for(var child:container.getComponents()){var found=terminalApproval(child);if(found!=null)return found;}
        return null;
    }
    static void decideTerminalApproval(String decision)throws Exception{
        await(()->Arrays.stream(java.awt.Window.getWindows()).anyMatch(window->window instanceof JDialog dialog&&dialog.isShowing()&&dialog.getTitle().equals("Codex requests terminal commands")));
        ui(()->{for(var window:java.awt.Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&dialog.getTitle().equals("Codex requests terminal commands")){terminalApproval(dialog).setValue(decision);return null;}throw new AssertionError("Owned fixture approval dialog missing");});
    }
    static void terminalFormatter()throws Exception{
        var sourcePane=ui(()->editor.editorPanes.stream().filter(p->p.getBuffer()!=null&&p.getBuffer().getFile()!=null&&p.getBuffer().getFile().getName().equals("Demo.java")).findFirst().orElseThrow());
        var terminal=ui(editor::getActivePane);int panes=ui(()->editor.editorPanes.size());
        String expected=Files.readString(project.resolve("Demo.java")).replace("String greet(String name) {","String greet( String name ) {");
        Path request=output.resolve("terminal-formatter-command.json");
        var argv=List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),project.resolve("FormatFixture.java").toString());
        Files.writeString(request,MiniJson.stringify(Map.of("reason","Verify formatter output and source replay in the existing terminal and source panes","commands",List.of(Map.of("argv",argv,"directory",".","timeoutSeconds",30)))));
        cli("terminal-run","--commands-file",request.toString(),"--request-id","demo-formatter","--agent","Codex");decideTerminalApproval("Run once");
        await(()->sourcePane.getBuffer().getContent().equals(expected)&&sourcePane.getTextArea().getClientProperty("shed.agentTour")!=null);
        long revision=ui(()->sourcePane.getBuffer().documentRevision());
        var modes=new HashSet<String>();
        for(int i=0;i<24;i++){
            ui(()->{
                if(editor.getActivePane()!=terminal||terminal.getTerminalPane()==null||editor.editorPanes.size()!=panes)throw new AssertionError("Formatter displaced or replaced the normal terminal");
                if(sourcePane.getTextArea().getClientProperty("shed.agentAnimation") instanceof String mode)modes.add(mode);
                if(sourcePane.getBuffer().isModified()||sourcePane.getTextArea().getCaretPosition()!=0||sourcePane.getBuffer().documentRevision()!=revision||!sourcePane.getBuffer().getContent().equals(expected))throw new AssertionError("Formatter replay changed the human source state");
                return null;
            });
            if(i==12)picture("terminal-formatter-source");Thread.sleep(100);
        }
        if(!modes.containsAll(Set.of("deleting","typing")))throw new AssertionError("Formatter did not use ordinary deletion and creation: "+modes);
        AgentAnimationDesktopProbe.modes.addAll(modes);
        Map<String,Object> observation=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
        do{observation=MiniJson.asObject(cli("terminal-status","--request-id","demo-formatter").get("observation"));if(!"running".equals(observation.get("state")))break;Thread.sleep(100);}while(System.nanoTime()<deadline);
        if(!"completed".equals(observation.get("state")))throw new AssertionError("Terminal formatter did not complete: "+observation);
        var result=MiniJson.asObject(MiniJson.asArray(MiniJson.asObject(observation.get("result")).get("results")).getFirst());
        if(!Long.valueOf(0).equals(result.get("exitCode"))||!result.get("output").toString().contains("Formatted Demo.java"))throw new AssertionError("Formatter output was not returned from the visible command");
    }
    static void replay(String source,String label,int count,String...expectedModes)throws Exception{
        cli("follow","--agent","Codex");
        Files.writeString(project.resolve("Demo.java"),source);
        await(()->editor.getCurrentBuffer().getContent().equals(source));
        frames(label,count);
        if(!modes.containsAll(List.of(expectedModes)))throw new AssertionError("Missing animation modes for "+label+": "+modes);
    }
    static String replaceBlock(String source,String replacement){
        int start=source.indexOf("// REMOVE_START"),end=source.indexOf("// REMOVE_END")+"// REMOVE_END\n".length();
        if(start<0||end<start)throw new AssertionError("Missing block markers");
        return source.substring(0,start)+replacement+source.substring(end);
    }
    static boolean terminalReplayReady()throws Exception{
        return ui(()->{
            var terminal=editor.getActivePane().getTerminalPane();
            return terminal!=null&&terminal.reusableForAgent(project,System.getenv("SHELL"));
        });
    }
    static void pauseTerminalDemo(String reason)throws Exception{
        Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PAUSED","animation","terminal","loop",true,"cycles",cycles,"reason",reason)));
        System.out.println("LOOP_PAUSED: "+reason+"; the normal terminal remains open");System.out.flush();
        ui(()->{editor.showMessage("Terminal demo paused · "+reason+" · close the window to finish");if(editor.messageResetTimer!=null)editor.messageResetTimer.stop();return null;});
        while(ui(()->editor.isDisplayable()))Thread.sleep(250);
    }
    static void singleDemo(String name)throws Exception{
        if(name.equals("deletion,insertion")){
            singleDemo("deletion");int initialY=ui(()->editor.editorScrollPane.getViewport().getViewPosition().y);var minimumY=new int[]{initialY};
            var sample=ui(()->{var timer=new javax.swing.Timer(16,e->minimumY[0]=Math.min(minimumY[0],editor.editorScrollPane.getViewport().getViewPosition().y));timer.start();return timer;});
            try{singleDemo("insertion");}finally{ui(()->{sample.stop();return null;});}
            if(minimumY[0]<initialY-50)throw new AssertionError("Delete-to-create returned to the human caret before reaching the destination");
            return;
        }
        String source=Files.readString(project.resolve("Demo.java"));
        switch(name){
            case "terminal" -> {
                if(looping&&cycles>0){
                    if(!terminalReplayReady()){pauseTerminalDemo("Terminal interaction or context changed");break;}
                    var result=new CompletableFuture<CommandResult>();
                    int panes=ui(()->editor.editorPanes.size());
                    String agent=List.of("Codex","Claude Code","OpenCode").get((int)(cycles%3));
                    ui(()->{editor.asyncJobService.submitLongRunning("Terminal replay",token->editor.runAgentCommand(agent,new AgentCommandPlan.Command(List.of("printf","%s"+Character.toString(92)+"n","Shed terminal: command output remains visible"),project,30),token),
                        (job,value,error)->{if(error!=null)result.completeExceptionally(error);else result.complete(value);});return null;});
                    if(cycles<=2){Thread.sleep(900);if(!result.isDone())picture("terminal-provider-"+cycles+"-typing");}
                    CommandResult completed;
                    try{completed=result.get(35,TimeUnit.SECONDS);}
                    catch(ExecutionException|CancellationException failure){
                        Throwable cause=failure instanceof ExecutionException?failure.getCause():failure;
                        if(cause instanceof CancellationException){pauseTerminalDemo("Human took terminal control");break;}
                        throw failure;
                    }
                    if(completed.exitCode<0){pauseTerminalDemo(completed.stderr);break;}
                    if(completed.exitCode!=0)throw new AssertionError("Terminal replay failed");
                    if(ui(()->editor.editorPanes.size())!=panes){pauseTerminalDemo("Terminal reuse changed");break;}
                    picture("terminal-completed");break;
                }
                Path request=output.resolve("terminal-command.json");
                Files.writeString(request,MiniJson.stringify(Map.of("reason","Demonstrate one harmless command in the normal terminal","commands",List.of(Map.of("argv",List.of("printf","%s\\n","Shed terminal: command output remains visible"),"directory",".","timeoutSeconds",30)))));
                var pending=cli("terminal-run","--commands-file",request.toString(),"--request-id","demo-rejected","--agent","Codex");
                if(!"awaiting-approval".equals(MiniJson.asObject(pending.get("observation")).get("state")))throw new AssertionError("Command did not wait for approval");
                cli("terminal-run","--commands-file",request.toString(),"--request-id","demo-rejected","--agent","Codex");
                decideTerminalApproval("Reject");
                if(!"rejected".equals(MiniJson.asObject(cli("terminal-status","--request-id","demo-rejected").get("observation")).get("state")))throw new AssertionError("Rejected command ran");
                if(ui(()->editor.getActivePane().getTerminalPane())!=null)throw new AssertionError("Rejected command opened a terminal");
                cli("terminal-run","--commands-file",request.toString(),"--request-id","demo-approved","--agent","Codex");
                decideTerminalApproval("Run once");
                boolean capturedTerminal=false;
                Map<String,Object> observation=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
                do{
                    observation=MiniJson.asObject(cli("terminal-status","--request-id","demo-approved").get("observation"));
                    if(!capturedTerminal&&ui(()->editor.getActivePane().getTerminalPane()!=null)){Thread.sleep(1000);picture("terminal-typing");capturedTerminal=true;}
                    if(!observation.get("state").equals("running"))break;Thread.sleep(200);
                }while(System.nanoTime()<until);
                if(looping&&"cancelled".equals(observation.get("state"))){pauseTerminalDemo("Human took terminal control");break;}
                if(!"completed".equals(observation.get("state")))throw new AssertionError("Terminal command did not complete: "+observation);
                var results=MiniJson.asArray(MiniJson.asObject(observation.get("result")).get("results"));var completed=MiniJson.asObject(results.getFirst());
                if(!Long.valueOf(0).equals(completed.get("exitCode"))||!completed.get("output").toString().contains("Shed terminal: command output remains visible"))throw new AssertionError("Actual terminal result missing: "+completed);
                if(ui(()->editor.getActivePane().getTerminalPane())==null)throw new AssertionError("Normal terminal disappeared after command completion");
                var replay=MiniJson.asObject(cli("terminal-run","--commands-file",request.toString(),"--request-id","demo-approved","--agent","Codex").get("observation"));
                if(!"completed".equals(replay.get("state")))throw new AssertionError("Repeated request executed again");
                if(!source.equals(Files.readString(project.resolve("Demo.java"))))throw new AssertionError("Terminal demo altered source");
                picture("terminal-completed");
                if(!looping){
                    terminalFormatter();
                    int panes=ui(()->editor.editorPanes.size());
                    ui(()->{var input=editor.getActivePane().getTerminalPane().getInputComponent();input.dispatchEvent(new java.awt.event.MouseEvent(input,java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,5,5,1,false,java.awt.event.MouseEvent.BUTTON1));return null;});
                    if(terminalReplayReady())throw new AssertionError("Human terminal interaction did not revoke replay eligibility");
                    if(ui(()->editor.editorPanes.size())!=panes)throw new AssertionError("Interaction created a terminal pane");
                    picture("terminal-human-handoff");
                }
            }
            case "reading" -> {read("Demo.java",0);read("Demo.java",2200);}
            case "typing" -> {replay(source.replace("name","visitor"),name,26,"deleting","typing");if(maximumCursors<2)throw new AssertionError("Batch modification did not animate multiple cursors");}
            case "insertion" -> {
                String changed;
                if(deletedBlock==null)changed=source.replace("// CHANGE_TOP\n","// CHANGE_TOP\n"+"// incoming line of the new block\n".repeat(12));
                else{int destination=source.indexOf("    public static String greet");if(destination<0)throw new AssertionError("Creation destination missing");changed=source.substring(0,destination)+deletedBlock+source.substring(destination);deletedBlock=null;}
                replay(changed,name,16,"chunks");
            }
            case "deletion" -> {
                String changed=replaceBlock(source,"");int start=source.indexOf("// REMOVE_START");deletedBlock=source.substring(start,start+source.length()-changed.length());
                replay(changed,name,12,"deleting");
            }
            case "rewrite" -> replay(replaceBlock(source,"// replacement block begins\n"+"// rewritten line\n".repeat(20)+"// replacement block ends\n"),name,24,"deleting","chunks");
            case "interrupt" -> {
                cli("follow","--agent","Codex");
                String changed=source.replace("CHANGE_TOP","Interrupt this replay with an explicit read");
                Files.writeString(project.resolve("Demo.java"),changed);
                await(()->editor.getCurrentBuffer().getContent().equals(changed)&&editor.writingArea.getClientProperty("shed.agentTour")!=null);
                beforeReadInterrupt();
                var response=cli("read","Demo.java","--offset","0","--limit","900","--agent","Codex");
                if(!changed.substring(0,900).equals(response.get("content")))throw new AssertionError("Read returned stale source");
                ui(()->{if(editor.writingArea.getClientProperty(AgentChangePlayback.KEY)!=null||!Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("following")))throw new AssertionError("Read did not interrupt replay and retain Follow");return null;});
            }
            case "file-create" -> {
                Files.writeString(project.resolve("Helpers.java"),"public class Helpers {}\n");
                await(()->editor.getCurrentBuffer().getFile()!=null&&editor.getCurrentBuffer().getFile().getName().equals("Helpers.java"));
            }
            case "file-modify", "file-rename", "file-delete" -> {
                String file=name.equals("file-modify")?"Notes.txt":name.equals("file-rename")?"RenameMe.txt":"DeleteMe.txt";
                cli("open",file);cli("follow","--agent","Codex");
                if(name.equals("file-modify")){
                    String changed="Demo notes\n- Updated by the individual animation demo\n";
                    Files.writeString(project.resolve(file),changed);
                    await(()->editor.getCurrentBuffer().getContent().equals(changed));
                }else if(name.equals("file-rename"))Files.move(project.resolve(file),project.resolve("Renamed.txt"));
                else Files.delete(project.resolve(file));
            }
            default -> {
                ui(()->{editor.toolWindowHost.watchAgentChanges("pause","Codex");return null;});
                var top=span(source,"demonstration checkpoint 010");var middle=span(source,"demonstration checkpoint 150");var greet=span(source,"greet");
                switch(name){
                    case "search" -> {for(int focus=0;focus<3;focus++)intent("search","active",focus,"Codex",top,middle,greet);}
                    case "navigate" -> intent(name,"active",0,"Codex",greet);
                    case "target" -> {
                        intent("target","active",0,"Codex",greet);
                        String changed=source.substring(0,greet.start())+"greetVisitor"+source.substring(greet.end());
                        int initialY=ui(()->editor.editorScrollPane.getViewport().getViewPosition().y);var minimumY=new int[]{initialY};var replaySeen=new boolean[1];var prematureReveal=new boolean[1];
                        var sample=ui(()->{var timer=new javax.swing.Timer(16,e->{
                            minimumY[0]=Math.min(minimumY[0],editor.editorScrollPane.getViewport().getViewPosition().y);
                            if(editor.writingArea.getClientProperty(AgentChangePlayback.KEY)!=null)replaySeen[0]=true;
                            else if(!replaySeen[0]&&editor.getCurrentBuffer().getContent().equals(changed)&&editor.writingArea.getClientProperty("shed.agentSourceHandoff")==null)prematureReveal[0]=true;
                        });timer.start();return timer;});
                        try{replay(changed,name,12,"typing");}
                        finally{ui(()->{sample.stop();return null;});}
                        if(minimumY[0]<initialY-50)throw new AssertionError("Examining-to-edit viewport jumped from "+initialY+" to "+minimumY[0]);
                        if(prematureReveal[0])throw new AssertionError("Completed source was revealed before the inline replay");
                    }
                    case "checking", "passed", "failed" -> {
                        if(name.equals("passed")&&!compileExample(source))throw new AssertionError("Valid source did not compile");
                        if(name.equals("failed")&&compileExample("public class Demo { invalid syntax }"))throw new AssertionError("Invalid source compiled");
                        intent("verify",name,0,"Codex",greet);
                    }
                    case "conflict" -> {
                        ui(()->{editor.writingArea.setCaretPosition(0);editor.writingArea.replaceSelection("// human draft retained\n");return null;});
                        intent("conflict","active",0,"Codex",new AgentEditPresentation.Range(0,23));
                        ui(()->{editor.getCurrentBuffer().getUndoManager().undo();if(!source.equals(editor.getCurrentBuffer().getContent()))throw new AssertionError("Human draft was not retained");editor.getCurrentBuffer().load(editor.configManager);editor.loadBufferIntoEditor(editor.getCurrentBuffer());return null;});
                    }
                    default -> throw new IllegalArgumentException("Unknown animation: "+name);
                }
            }
        }
        if(name.startsWith("file-")){
            stage("FILE: "+name+" — watching Explorer and Git",3000);
            ui(()->{editor.toolWindowHost.showTab(ToolWindowHost.Tab.GIT);return null;});
        }
        picture("single-"+name);stage("ANIMATION COMPLETE: "+name+" — closing in 5 seconds",looping?(name.equals("terminal")?2500:1000):5000);
    }
    static void loopDemo(String name)throws Exception{
        var baseline=new LinkedHashMap<Path,String>();
        try(var paths=Files.list(project)){
            for(var path:paths.filter(Files::isRegularFile).toList())baseline.put(path,Files.readString(path));
        }
        System.out.println("LOOP_READY: "+name+" — replaying in this window; close it to stop");System.out.flush();
        while(ui(()->editor.isDisplayable())){
            deletedBlock=null;
            modes.clear();directions.clear();intents.clear();intentMs.clear();typingMs.clear();readingFrames=0;maximumCursors=0;
            if(name.equals("reading"))readingAgent=List.of("Codex","Claude Code","OpenCode").get((int)(cycles%3));
            singleDemo(name);cycles++;
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","animation",name,"loop",true,"cycles",cycles)));
            if(name.equals("terminal")&&cycles==1&&checkTerminalHandoff){
                ui(()->{var input=editor.getActivePane().getTerminalPane().getInputComponent();input.dispatchEvent(new java.awt.event.MouseEvent(input,java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,5,5,1,false,java.awt.event.MouseEvent.BUTTON1));return null;});
            }
            if(name.equals("reading")||name.equals("terminal"))continue;
            if(Set.of("search","navigate","checking","passed","failed").contains(name)){
                // annotations do not change the fixture; reset only their view, avoiding needless document reloads
                ui(()->{editor.clearAgentActivity();editor.editorScrollPane.getViewport().setViewPosition(new java.awt.Point());return null;});
                continue;
            }
            ui(()->{editor.toolWindowHost.watchAgentChanges("stop","Codex");editor.clearAgentActivity();return null;});
            try(var paths=Files.list(project)){
                for(var path:paths.filter(Files::isRegularFile).toList())if(!baseline.containsKey(path))Files.delete(path);
            }
            for(var entry:baseline.entrySet())Files.writeString(entry.getKey(),entry.getValue());
            ui(()->{
                var pane=editor.getActivePane();var viewport=pane.getScrollPane().getViewport().getViewPosition();
                editor.withSuppressedDocumentEvents(()->{
                    try{for(var buffer:editor.buffers)if(buffer.getFile()!=null&&buffer.getFile().isFile())buffer.load(editor.configManager);}
                    catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
                    var target=editor.buffers.stream().filter(buffer->buffer.getFile()!=null&&buffer.getFile().toPath().toAbsolutePath().normalize().equals(project.resolve("Demo.java"))).findFirst().orElseThrow();
                    // fixture maintenance selects the known buffer while retaining the previous agent viewport
                    editor.loadBufferIntoPane(pane,target,0);pane.surface().scrollTo(new EditorSurface.Viewport(viewport.x,viewport.y));
                });
                return null;
            });
            ui(()->{editor.hideToolWindow();editor.toolWindowHost.watchAgentChanges("start","Codex");return null;});
            await(()->Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")));
            cli("follow","--agent","Codex");
        }
    }
    static void desktopEdges()throws Exception{
        ui(()->{editor.toolWindowHost.watchAgentChanges("pause","Codex");return null;});
        var buffer=ui(editor::getCurrentBuffer);String source=ui(buffer::getContent);long revision=ui(buffer::documentRevision);boolean dirty=ui(buffer::isModified),undo=ui(()->buffer.getUndoManager().canUndo());
        ui(()->{editor.splitWindow(true);editor.splitWindow(false);return null;});
        for(int index=0;index<3;index++){
            final int pass=index;var old=ui(()->{var area=editor.writingArea;var range=new AgentEditPresentation.Range(2000,2010);var tour=new AgentChangeTour(area,editor.getActivePane().agentPresentation(),List.of(range),source,"Codex",()->true,()->{},()->{},List.of(new AgentChangePlayback.Edit(range,"")));tour.start();return tour;});
            ui(()->{
                editor.setThemeFromCommand(pass==1?"dracula":"shed-light");editor.configManager.set("ui.density",List.of("compact","default","comfortable").get(pass));editor.sessionConfigController.applySetting("ui.density");
                editor.configManager.set("font.family","Monospaced");editor.configManager.set("font.size",pass==1?"20":"16");editor.sessionConfigController.applySetting("font.family");editor.setSize(pass==1?850:1400,pass==2?650:900);return null;
            });
            Thread.sleep(200);ui(()->{
                old.advance(System.nanoTime()+700_000_000L);var presentation=editor.getActivePane().agentPresentation();presentation.showReading(2000,2040,"Claude Code");
                var location=editor.writingArea.modelToView2D(2000);if(location==null)throw new AssertionError("Reading geometry unavailable");editor.writingArea.scrollRectToVisible(location.getBounds());var viewport=editor.editorScrollPane.getViewport().getViewPosition();
                old.advance(System.nanoTime()+2_000_000_000L);old.close();if(!viewport.equals(editor.editorScrollPane.getViewport().getViewPosition()))throw new AssertionError("Stale callback moved the replacement viewport");
                if(!"Agent reading".equals(editor.writingArea.getClientProperty("shed.agentActivity"))||editor.writingArea.getClientProperty(AgentChangePlayback.KEY)!=null)throw new AssertionError("Stale tour reclaimed reading after geometry change");
                if(!source.equals(buffer.getContent())||revision!=buffer.documentRevision()||dirty!=buffer.isModified()||undo!=buffer.getUndoManager().canUndo())throw new AssertionError("Layout changes modified source/history");
                for(var view:editor.editorPanes)if(view!=editor.treePane&&(view.getBuffer()!=buffer||view.getTextArea().getDocument()!=buffer.getDocument()))throw new AssertionError("Split source ownership changed");return null;
            });
            picture("edges-layout-"+pass);edgeChecks.add("layout-"+List.of("compact","default","comfortable").get(pass));
        }
        ui(()->{
            editor.clearAgentActivity();var area=editor.writingArea;var presentation=editor.getActivePane().agentPresentation();var range=new AgentEditPresentation.Range(2000,2010);
            for(int cycle=0;cycle<24;cycle++){
                try(var first=new AgentChangeTour(area,presentation,List.of(range),source,"Codex",()->true,()->{},()->{},List.of(new AgentChangePlayback.Edit(range,"")));
                    var next=new AgentChangeTour(area,presentation,List.of(range),source,"OpenCode",()->true,()->{},()->{},List.of(new AgentChangePlayback.Edit(range,"")))){
                    first.start();next.start();Object owner=area.getClientProperty(AgentChangePlayback.KEY);first.advance(System.nanoTime());if(area.getClientProperty(AgentChangePlayback.KEY)!=owner||area.getClientProperty("shed.agentTour")==null)throw new AssertionError("Old desktop tour cleared replacement");
                }
                if(area.getClientProperty(AgentChangePlayback.KEY)!=null||area.getClientProperty("shed.agentTour")!=null)throw new AssertionError("Desktop tour survived close");
            }
            var handoff=AgentSourceHandoff.capture(area);if(handoff==null)throw new AssertionError("Owned viewport capture unavailable");handoff.install();area.setBackground(java.awt.Color.PINK);if(area.getClientProperty("shed.agentSourceHandoff")!=null)throw new AssertionError("Old theme image survived");handoff.close();editor.applyThemeColors();return null;
        });edgeChecks.add("24-overlapping-tour-retirements");edgeChecks.add("theme-image-retirement");
        awaitStableContext();var read=cli("read","--offset","2200","--limit","900");if(!source.substring(2200,3100).equals(read.get("content")))throw new AssertionError("Passive read did not return the current source");edgeChecks.add("external-cli-passive-read-after-layout");measureTyping(false);edgeChecks.add("typing-interrupts-playback");
        ui(()->{if(!source.equals(buffer.getContent())||buffer.isModified())throw new AssertionError("Edge fixture source did not restore");return null;});picture("edges-final");stage("EDGE_PASS: geometry, split ownership, supersession and cleanup",1500);
    }
    public static void main(String[] args)throws Exception{
        Thread.setDefaultUncaughtExceptionHandler((thread,failure)->{uncaught.compareAndSet(null,failure);System.err.println("UNCAUGHT_DESKTOP_EXCEPTION");failure.printStackTrace();});
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        if(!output.startsWith(fixture.resolve("run"))||!project.startsWith(fixture.resolve("run"))||!Path.of(System.getProperty("user.home")).toRealPath().startsWith(fixture))throw new IllegalArgumentException("Disposable fixture required");
        looping=Arrays.asList(args).contains("--loop");
        checkTerminalHandoff=Arrays.asList(args).contains("--check-terminal-handoff");
        if(args.length>=3&&args[2].equals("reading"))readingAgent="Codex";
        try{
            editor=ui(()->new Texteditor(new String[]{project.resolve("Demo.java").toString()}));
            await(()->!editor.stagedStartupPending&&editor.getCurrentBuffer()!=null);
            ui(()->{editor.setTitle("Shed — Live agent demonstration");editor.setSize(1400,900);editor.workspaceController.addFolder(project,true);editor.treeGitController.showFileTree(project.toString());editor.activateEditorPane(editor.resolveTreeContentPaneForOpen());editor.setMode(EditorMode.INSERT);editor.hideToolWindow();editor.toFront();editor.writingArea.requestFocusInWindow();editor.handleAgentCommand(args.length>=3&&args[2].equals("terminal")?"api start terminal":"api start control");editor.toolWindowHost.watchAgentChanges("start","Codex");return null;});
            await(()->editor.visualAttachment()!=null&&Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("ready")));
            descriptor=ui(()->editor.visualAttachment().descriptor().toString());stage("READY: watch the Shed window",2500);
            if(Arrays.asList(args).contains("--edge-cases"))desktopEdges();
            else if(looping)loopDemo(args[2]);
            else if(args.length==3)singleDemo(args[2]);
            else{
                read("Demo.java",0);read("Demo.java",2200);read("Demo.java",4400);
                cli("follow","--agent","Codex");
                String before=Files.readString(project.resolve("Demo.java"));String after=before.replace("CHANGE_TOP","TOP: greeting improvements").replace("CHANGE_MIDDLE","MIDDLE: helper methods added").replace("CHANGE_BOTTOM","BOTTOM: demo complete").replace("return \"Hello, \" + name;","return \"Hello, \" + (name == null || name.isBlank() ? \"friend\" : name.trim());");
                int end=after.lastIndexOf('}');String methods="";for(int i=1;i<=5;i++)methods+="    public static void placeholder"+i+"() {}\n";after=after.substring(0,end)+methods+after.substring(end);
                Files.writeString(project.resolve("Demo.java"),after);String expected=after;await(()->editor.getCurrentBuffer().getContent().equals(expected));frames("typing",10);stage("EDIT: quick typing and new functions",4500);
                read("Demo.java",1600);String current=Files.readString(project.resolve("Demo.java"));int deletionStart=current.indexOf("// REMOVE_START"),deletionEnd=current.indexOf("// REMOVE_END")+"// REMOVE_END\n".length();
                if(deletionStart<0||deletionEnd<deletionStart)throw new AssertionError("Deletion fixture markers missing");
                String removedBlock=current.substring(deletionStart,deletionEnd),afterDeletion=current.substring(0,deletionStart)+current.substring(deletionEnd);
                Files.writeString(project.resolve("Demo.java"),afterDeletion);frames("deleting",9);stage("DELETE BLOCK: highlight and right-to-left removal",1800);
                int destination=afterDeletion.indexOf("    public static String greet");if(destination<0)throw new AssertionError("Creation destination missing");
                Files.writeString(project.resolve("Demo.java"),afterDeletion.substring(0,destination)+removedBlock+afterDeletion.substring(destination));frames("insertion",16);stage("CREATE BLOCK: existing insertion replay at the destination",1800);
                read("Demo.java",2000);current=Files.readString(project.resolve("Demo.java"));int rewriteStart=current.indexOf("// REWRITE_START"),rewriteEnd=current.indexOf("// REWRITE_END")+"// REWRITE_END\n".length();
                if(rewriteStart<0||rewriteEnd<rewriteStart)throw new AssertionError("Rewrite fixture markers missing");
                String replacement="// rewritten chunk: begin\n"+"// phased replacement block\n".repeat(25)+"// rewritten chunk: end\n";
                Files.writeString(project.resolve("Demo.java"),current.substring(0,rewriteStart)+replacement+current.substring(rewriteEnd));frames("rewriting",16);stage("REWRITE: old chunk removed; replacement phases in left-to-right",2000);
                String interrupted=Files.readString(project.resolve("Demo.java")).replace("TOP: greeting improvements","TOP: next observed edit");Files.writeString(project.resolve("Demo.java"),interrupted);
                await(()->editor.writingArea.getClientProperty("shed.agentTour")!=null);
                beforeReadInterrupt();
                var nextRead=cli("read","Demo.java","--offset","0","--limit","900","--agent","Codex");if(!interrupted.substring(0,900).equals(nextRead.get("content")))throw new AssertionError("Read superseded to the wrong source");
                ui(()->{if(editor.writingArea.getClientProperty(AgentChangePlayback.KEY)!=null||!"top-to-bottom".equals(editor.writingArea.getClientProperty("shed.agentDirection"))||!Boolean.TRUE.equals(editor.toolWindowHost.watchAgentChanges("state","Codex").get("following")))throw new AssertionError("Explicit read did not retire the tour while retaining Follow");return null;});stage("READ INTERRUPTS REPLAY: explicit reading wins without disabling Follow",2000);
                String helperSource="public class Helpers {\n    public static String farewell(String name) {\n        return \"Goodbye, \" + name;\n    }\n}\n";
                Files.writeString(project.resolve("Helpers.java"),helperSource);activeFile("Helpers.java",helperSource);stage("CREATE: Helpers.java",3500);read("Helpers.java",0);
                read("Notes.txt",0);String notes="Demo notes\n- Greeting handles blank names\n- Five placeholder methods added\n- Helpers.java created\n";
                Files.writeString(project.resolve("Notes.txt"),notes);activeFile("Notes.txt",notes);stage("MODIFY: Notes.txt",3500);
                read("RenameMe.txt",0);String renamed=Files.readString(project.resolve("RenameMe.txt"));Files.move(project.resolve("RenameMe.txt"),project.resolve("Renamed.txt"));activeFile("Renamed.txt",renamed);stage("RENAME: RenameMe.txt to Renamed.txt",4000);
                read("DeleteMe.txt",0);var retained=ui(editor::getCurrentBuffer);String retainedSource=ui(retained::getContent);Files.delete(project.resolve("DeleteMe.txt"));stage("DELETE: DeleteMe.txt — watch the Explorer badge",4000);
                ui(()->{if(editor.getCurrentBuffer()!=retained||!retained.getContent().equals(retainedSource)||Files.exists(project.resolve("DeleteMe.txt")))throw new AssertionError("File deletion did not retain the source buffer");return null;});
                intentDemo();measureTyping();ui(()->{editor.toolWindowHost.showTab(ToolWindowHost.Tab.GIT);return null;});stage("GIT: live status and diffs — leaving this visible for 20 seconds",20000);
                if(!modes.containsAll(Set.of("typing","chunks","deleting")))throw new AssertionError("Missing animation modes: "+modes);
                if(!directions.containsAll(Set.of("left-to-right","right-to-left","top-to-bottom")))throw new AssertionError("Missing motion directions: "+directions);
            }
            if(uncaught.get()!=null)throw new AssertionError("Uncaught desktop exception",uncaught.get());
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("animation",args.length==3?args[2]:"all","state","PASS","modes",modes.stream().toList(),"directions",directions.stream().toList(),"intents",intents.stream().toList(),"intent_client_ms",intentMs,"source_unchanged_by_animation",true,"typing_paint_ms",typingMs,"edge_checks",edgeChecks,"scope","direct Java/Swing, real authenticated CLI, disposable filesystem writes; no actual model")));
            System.out.println("DEMO_PASS: "+(args.length==3?args[2]:"reads, edits, creation, modification, rename, deletion and Git presentation"));
        }catch(Exception|AssertionError failure){failure.printStackTrace();Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",failure.toString(),"edge_checks",edgeChecks)));throw failure;}finally{if(editor!=null)ui(()->{editor.dispatchEvent(new java.awt.event.WindowEvent(editor,java.awt.event.WindowEvent.WINDOW_CLOSING));return null;});}
        ui(()->null);
        if(uncaught.get()!=null){Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",uncaught.get().toString(),"edge_checks",edgeChecks)));System.exit(1);}
        System.exit(0);
    }
}
