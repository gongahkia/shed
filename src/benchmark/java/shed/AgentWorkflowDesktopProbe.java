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
    public static void main(String[] args) throws Exception {
        int exit=1;folder=Path.of(args[0]).toRealPath();Path project=Path.of(args[1]).toRealPath();
        Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        check(folder.startsWith(fixture.resolve("run"))&&project.startsWith(fixture.resolve("run")),"Unconfined desktop fixture");
        Thread watchdog=new Thread(()->{try{Thread.sleep(150000);Runtime.getRuntime().halt(124);}catch(InterruptedException done){}} ,"shed-live-test-deadline");
        watchdog.setDaemon(true);watchdog.start();
        try {
            editor=edt(()->new Texteditor(new String[]{project.resolve("Welcome.txt").toString()}));
            await(()->!editor.stagedStartupPending&&editor.getCurrentBuffer()!=null&&editor.isShowing(),30);
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
            capture("01-ready");System.out.println("LIVE_READY");System.out.flush();
            await(()->editor.getCurrentBuffer().getFile().getName().equals("Demo.java"),75);
            capture("01-source-before-agent-activity");
Files.writeString(folder.resolve("opened-captured"),"ok");
            await(()->editor.writingArea.getClientProperty("shed.agentActivity")!=null,75);
            Color human=edt(()->editor.writingArea.getCaretColor());Color agent=edt(()->editor.getActivePane().agentPresentation().agentColor());
            check(!human.equals(agent),"Agent marker matches human caret color");
            for(int i=0;i<8;i++){capture("02-pending-"+i);Thread.sleep(100);}
            await(()->{JButton apply=find(editor.getContentPane(),JButton.class,"agent.apply");return apply!=null&&apply.isEnabled();},30);
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
            capture("07-live-external-changes");
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
            Files.writeString(folder.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","events",events,"live_window",true,"capture","owned Swing root pane")));
            System.out.println("LIVE_PASS");exit=0;
        } catch(Throwable failure){failure.printStackTrace();Files.writeString(folder.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",failure.toString(),"events",events)));}
        finally {
            if(editor!=null)edt(()->{editor.handleAgentCommand("api stop");editor.asyncJobService.shutdownNow();for(Window window:Window.getWindows())window.dispose();return null;});
            watchdog.interrupt();System.exit(exit);
        }
    }
}
