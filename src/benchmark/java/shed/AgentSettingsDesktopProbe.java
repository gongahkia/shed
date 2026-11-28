package shed;

import static shed.AgentWorkbenchDesktopProbe.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Real Settings controls and external authenticated clients; all configuration is disposable. */
public final class AgentSettingsDesktopProbe {
    static final List<Map<String,Object>> cycles=new ArrayList<>();
    static final List<Double> rpc=new ArrayList<>(),paint=new ArrayList<>();
    static final List<Map<String,Object>> paintDetails=new ArrayList<>();
    static void samplePaint(String phase,int count)throws Exception{
        for(int i=0;i<count;i++){
            long posted=System.nanoTime();
            var sample=edt(()->{
                long entered=System.nanoTime();
                var dialog=Arrays.stream(Window.getWindows()).filter(w->w instanceof SettingsEditorDialog&&w.isShowing()).map(w->(SettingsEditorDialog)w).findFirst().orElseThrow();
                dialog.getRootPane().paintImmediately(dialog.getRootPane().getVisibleRect());long done=System.nanoTime();
                return Map.<String,Object>of("phase",phase,"queue_ms",(entered-posted)/1e6,"paint_ms",(done-entered)/1e6,"total_ms",(done-posted)/1e6);
            });
            paintDetails.add(sample);if(phase.equals("query"))paint.add((Double)sample.get("total_ms"));Thread.sleep(6);
        }
    }
    static void grant(String id,boolean appearance)throws Exception{
        delegation=null;Path task=output.resolve("settings-task.txt");Files.writeString(task,"Change editor font size through Settings and verify persistence.");
        cli("","session-request","--request-id",id,"--agent","Codex","--task-file",task.toString());await(()->approval()!=null);
        edt(()->{for(String permission:List.of("edit","files","vcs","tools","system_clipboard","appearance"))((JCheckBox)find(approval(),"agent.approval.permission."+permission)).setSelected(permission.equals("appearance")&&appearance);button(approval(),"Allow Control").doClick();return null;});
        delegation=id;await(()->editor.agentDelegation().lease.state().equals("active"));
    }
    static boolean settingsOpen(){return Arrays.stream(Window.getWindows()).anyMatch(w->w instanceof SettingsEditorDialog&&w.isShowing());}
    static String fontField()throws Exception{
        cli("","ui-run","settings.open");await(AgentSettingsDesktopProbe::settingsOpen);
        type(target("dialog.","Search settings"),"font.size");return target("dialog.","Editor font size");
    }
    static String read(String id)throws Exception{
        int length=((Number)observe(id).get("length")).intValue();
        return (String)MiniJson.asObject(cli("","ui-read",id,"--start","0","--end",String.valueOf(length)).get("observation")).get("content");
    }
    static void dismiss()throws Exception{cli("","ui-click","dialog.dismiss");await(()->!settingsOpen());cli("","ui-wait");}
    static void saved(int value)throws Exception{
        await(()->editor.configManager.getFontSize()==value);
        if(new ConfigManager().getFontSize()!=value)throw new AssertionError("Font size was not persisted");
    }
    static void cycle(int value,boolean capture)throws Exception{
        long started=System.nanoTime();String field=fontField();type(field,String.valueOf(value));cli("","ui-key",field,"--key","ENTER");saved(value);
        if(capture){var image=edt(()->{var dialog=Arrays.stream(Window.getWindows()).filter(w->w instanceof SettingsEditorDialog&&w.isShowing()).map(w->(SettingsEditorDialog)w).findFirst().orElseThrow();var root=dialog.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),8_000_000);});try{javax.imageio.ImageIO.write(image,"png",output.resolve("settings-changed.png").toFile());}finally{image.flush();}}dismiss();field=fontField();
        if(!String.valueOf(value).equals(read(field)))throw new AssertionError("Reopened Settings does not show the saved value");
        dismiss();cycles.add(Map.of("value",value,"open_search_edit_persist_reopen_verify_ms",(System.nanoTime()-started)/1e6));
    }
    static void rpcSamples()throws Exception{
        var identity=WorkspaceStatusAttachment.Descriptor.read(descriptor);
        try(var client=WorkspaceBridgeClient.control(descriptor,descriptor.resolveSibling("status-"+identity.attachmentId()+".key"))){
            client.delegation(delegation);
            var params=Map.<String,Object>of("schemaVersion",1,"instanceId",identity.instanceId(),"windowId",identity.windowId(),"projectId",identity.projectId(),"offset",0);
            for(int i=0;i<31;i++){long started=System.nanoTime();ShedControlCli.result(client.exchange(ShedControlCli.request("editor/ui/workbench-state",params)));if(i>0)rpc.add((System.nanoTime()-started)/1e6);}
        }
    }
    static final List<Double> passiveQueue=new ArrayList<>();
    static void paintWorkflow()throws Exception{
        cli("","ui-run","settings.open");await(AgentSettingsDesktopProbe::settingsOpen);String query=target("dialog.","Search settings");
        try(var recording=Boolean.getBoolean("shed.settings.profile")?new jdk.jfr.Recording():null){
            if(recording!=null){recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(5));recording.enable("jdk.NativeMethodSample").withPeriod(java.time.Duration.ofMillis(5));recording.setMaxSize(8_000_000);recording.start();}
            samplePaint("unfiltered-idle",20);
            var typing=new FutureTask<Map<String,Object>>(()->cli("font.size","ui-type",query,"--text-file","-"));new Thread(typing,"settings-benchmark-client").start();
            samplePaint("query",100);typing.get(10,TimeUnit.SECONDS);samplePaint("filtered-idle",20);
            if(recording!=null){recording.stop();recording.dump(output.resolve("settings-paint.jfr"));}
        }
        dismiss();
        cli("","ui-run","settings.open");await(AgentSettingsDesktopProbe::settingsOpen);String passiveQuery=target("dialog.","Search settings");
        var request=new FutureTask<Map<String,Object>>(()->cli("font.size","ui-type",passiveQuery,"--text-file","-"));new Thread(request,"settings-passive-client").start();
        for(int i=0;i<100;i++){long posted=System.nanoTime();passiveQueue.add(edt(()->(System.nanoTime()-posted)/1e6));Thread.sleep(8);}request.get(10,TimeUnit.SECONDS);dismiss();
    }
    public static void main(String[] args)throws Exception{
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        if(!output.startsWith(fixture.resolve("run"))||!project.startsWith(fixture.resolve("run")))throw new AssertionError("Disposable fixture required");
        int exit=1;
        try{
            var initial=new ConfigManager();if(!Path.of(initial.getConfigPath()).toAbsolutePath().normalize().startsWith(fixture.resolve("run")))throw new AssertionError("Configuration escaped fixture");initial.setAndPersist("font.size","20");
            String source="Settings benchmark source must stay unchanged.\n";Path file=project.resolve("Demo.txt");Files.writeString(file,source);
            editor=edt(()->new Texteditor(new String[]{file.toString()}));await(()->!editor.stagedStartupPending&&editor.getCurrentBuffer()!=null&&editor.getCurrentBuffer().getFile()!=null);
            edt(()->{editor.setTitle("Shed — Settings API benchmark");editor.setSize(1400,900);editor.workspaceController.addFolder(project,true);editor.setMode(EditorMode.INSERT);editor.hideToolWindow();editor.handleAgentCommand("api start control");return null;});await(()->editor.visualAttachment()!=null);descriptor=edt(()->editor.visualAttachment().descriptor());
            Path config=Path.of(edt(()->editor.configManager.getConfigPath())).toAbsolutePath().normalize();if(!config.startsWith(fixture.resolve("run")))throw new AssertionError("Configuration escaped fixture");
            int original=edt(()->editor.configManager.getFontSize()),caret=edt(()->editor.writingArea.getCaretPosition());long revision=edt(()->editor.getCurrentBuffer().documentRevision());
            grant("settings-denied",false);expectedError="PERMISSION_DENIED";cli("","ui-run","settings.open");if(expectedError!=null||edt(AgentSettingsDesktopProbe::settingsOpen))throw new AssertionError("Settings permission was bypassed");
            edt(()->{editor.agentDelegation().command("finish",delegation,"Codex","");return null;});grant("settings-allowed",true);rpcSamples();
            if(Boolean.getBoolean("shed.settings.paintOnly")){
                paintWorkflow();Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","scope","Settings paint/queue diagnostic only; no repeated persistence checks","paint_details",paintDetails,"passive_queue_ms",passiveQueue,"cli_timings",timings)));exit=0;return;
            }
            for(int i=0;i<5;i++)cycle(i%2==0?16:18,i==0);
            String input=fontField();type(input,"not-a-number");cli("","ui-key",input,"--key","ENTER");
            String feedback=read(target("dialog.","settings.feedback"));if(!feedback.contains("Error"))throw new AssertionError("Invalid font size had no readable error: "+feedback);saved(16);
            type(target("dialog.","Editor font size"),"16");cli("","ui-key",target("dialog.","Editor font size"),"--key","ENTER");dismiss();
            paintWorkflow();
            cycle(original,false);
            edt(()->{if(!source.equals(editor.getCurrentBuffer().getContent())||editor.getCurrentBuffer().documentRevision()!=revision||editor.getCurrentBuffer().isModified()||editor.writingArea.getCaretPosition()!=caret||editor.getCurrentBuffer().getUndoManager().canUndo())throw new AssertionError("Settings changed source state or Undo");editor.agentDelegation().command("finish",delegation,"Codex","");if(settingsOpen()||editor.getRootPane().getLayeredPane().getComponentsInLayer(JLayeredPane.DRAG_LAYER).length!=0)throw new AssertionError("Settings/pointer retained after handback");return null;});
            if(!source.equals(Files.readString(file)))throw new AssertionError("Settings changed source bytes");
            var result=new LinkedHashMap<String,Object>();result.put("state","PASS");result.put("scope","Real Swing Settings and authenticated scripted external Java CLI; no model or native shell");result.put("cycles",cycles);result.put("cli_timings",timings);result.put("warm_state_rpc_ms",rpc);result.put("queue_to_settings_paint_ms",paint);result.put("paint_details",paintDetails);result.put("passive_queue_ms",passiveQueue);result.put("invalid_input_feedback",feedback);result.put("permission_rejection",true);result.put("original_font_size",original);result.put("restored",true);result.put("source_and_undo_unchanged",true);result.put("calls",calls);result.put("remaining_processes",ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());Files.writeString(output.resolve("result.json"),MiniJson.stringify(result));exit=0;
        }catch(Throwable failure){failure.printStackTrace();Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",failure.toString(),"cycles",cycles,"calls",calls,"cli_timings",timings)));}
        finally{if(editor!=null)edt(()->{editor.agentDelegation().close();editor.handleAgentCommand("api stop");editor.asyncJobService.shutdownNow();for(Window window:Window.getWindows())window.dispose();return null;});System.exit(exit);}
    }
}
