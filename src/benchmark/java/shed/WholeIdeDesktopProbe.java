package shed;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import static shed.MarkdownPerformanceProbe.edt;
import static shed.WholeIdeServicesProbe.check;
import static shed.WholeIdeServicesProbe.out;

/** Opt-in production-window diagnostics. Synthetic inputs, isolated homes and finite child lifetime required. */
public final class WholeIdeDesktopProbe {
    private static Texteditor editor;
    private static String phase="startup";
    private static int paintErrors;
    private static Path folder;
    private static void geometry(String operation) {
        List<Map<String,Object>> panes=new ArrayList<>();
        if(editor!=null)for(EditorPane p:editor.editorPanes){var a=p.getTextArea();var v=a.getVisibleRect();
            panes.add(Map.of("id",System.identityHashCode(p),"disposed",p.isDisposed(),"active",p==editor.getActivePane(),
                "document",System.identityHashCode(a.getDocument()),"length",a.getDocument().getLength(),"bounds",a.getBounds().toString(),
                "visible",v.toString(),"start_map",a.viewToModel2D(new Point(0,v.y)),"end_map",a.viewToModel2D(new Point(0,v.y+v.height))));}
        out(Map.of("operation",operation,"phase",phase,"panes",panes));
    }
    private static long[] metric() throws Exception {
        synchronized(editor.perfService){var f=PerfService.class.getDeclaredField("metrics");f.setAccessible(true);
            Object row=((Map<?,?>)f.get(editor.perfService)).get("edit.input-to-paint");if(row==null)return new long[]{0,0};
            var count=row.getClass().getDeclaredField("count");count.setAccessible(true);var last=row.getClass().getDeclaredField("lastNanos");last.setAccessible(true);
            return new long[]{count.getLong(row),last.getLong(row)};}
    }
    private static void typing(String name, AtomicBoolean active) throws Exception {
        var buffer=edt(editor::getCurrentBuffer);
        for(int i=0;i<51;i++){edt(()->{editor.writingArea.setCaretPosition(Math.min(8,editor.writingArea.getDocument().getLength()));return null;});
            long before=edt(()->metric()[0]);boolean concurrent=active!=null&&active.get();long start=System.nanoTime();
            edt(()->{var area=editor.writingArea;var key=new KeyEvent(area,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_X,'x');editor.keyPressed(key);
                if(!key.isConsumed())area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(area,0,"x"));return null;});
            MarkdownPerformanceProbe.await(()->metric()[0]>before,5);
            out(Map.of("operation",name,"sample",i,"dispatch_roundtrip_ms",(System.nanoTime()-start)/1e6,"input_to_paint_ms",edt(()->metric()[1]/1e6),"background_active_at_dispatch",concurrent));
            edt(()->{buffer.getUndoManager().undo();return null;});
        }
    }
    private static void forced(String operation, Runnable action) throws Exception {
        edt(()->{long start=System.nanoTime();action.run();long dispatch=System.nanoTime();editor.validate();long layout=System.nanoTime();
            editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());
            out(Map.of("operation",operation,"dispatch_ms",(dispatch-start)/1e6,"layout_ms",(layout-dispatch)/1e6,"forced_paint_ms",(System.nanoTime()-layout)/1e6));return null;});
    }
    private static void basic() throws Exception {
        FileBuffer b=edt(editor::getCurrentBuffer);String original=edt(b::getContent);
        typing("ordinary_input",null);
        for(int i=0;i<20;i++){
            forced("newline_undo",()->{var a=editor.writingArea;a.setCaretPosition(8);a.replaceSelection("\n");b.getUndoManager().undo();});
            forced("delete_undo",()->{try{b.getDocument().remove(8,1);b.getUndoManager().undo();}catch(Exception e){throw new RuntimeException(e);}});
            forced("selection_caret",()->{editor.writingArea.select(4,12);editor.writingArea.setCaretPosition(8);});
            final int n=i;forced("scroll",()->editor.getActivePane().getScrollPane().getViewport().setViewPosition(new Point(0,n%2*100)));
        }
        check(original.equals(edt(b::getContent)),"basic source differs");
        out(Map.of("operation","basic_source_integrity","exact",true));
    }
    private static void interference() throws Exception {
        String original=edt(()->editor.getCurrentBuffer().getContent());
        Path root=folder.resolve("project-1000");
        for(String kind:List.of("search","git","task","lsp")){
            AtomicBoolean active=new AtomicBoolean(true);CountDownLatch started=new CountDownLatch(1),done=new CountDownLatch(1);
            LspClient c=kind.equals("lsp")?WholeIdeServicesProbe.client(folder,50):null;
            String uri=folder.resolve("Main.java").toUri().toString();if(c!=null)c.didOpen(uri,"java","class Main {}\n");
            int job=editor.asyncJobService.submit("B0 bounded "+kind,token->{started.countDown();long until=System.nanoTime()+600_000_000L;int count=0;
                try(var index=new WorkspaceIndexService(folder.resolve("interference-index"))){
                    while(System.nanoTime()<until&&!token.isCancelled()){
                        if(kind.equals("search"))new WorkspaceTextSearchService(index).search(false,root,"unique-b0-target",()->token.isCancelled(),null);
                        else if(kind.equals("lsp"))c.definition(uri,0,0);
                        else {var command=kind.equals("git")?List.of("/usr/bin/git","status","--porcelain"):List.of("/bin/sh","-c","i=0; while [ $i -lt 200 ]; do printf 'bounded-output-%s\\n' $i; i=$((i+1)); done");
                            var p=BackgroundTaskProcess.start(command,root.toFile(),Map.of(),32768,"");p.awaitCompletion(token);}
                        count++;
                    }
                } finally {active.set(false);}return count;
            },(request,count,error)->{out(Map.of("operation","background_result","kind",kind,"count",count==null?-1:count,"error",String.valueOf(error)));done.countDown();});
            check(started.await(2,TimeUnit.SECONDS),"background did not start");typing("during_"+kind,active);
            check(done.await(5,TimeUnit.SECONDS),"background did not finish");if(c!=null)c.stop();
            check(editor.asyncJobService.get(job).getStatus()==AsyncJobService.Status.SUCCEEDED,"background failed");
        }
        LspClient client=WholeIdeServicesProbe.client(folder,200);FileBuffer b=edt(editor::getCurrentBuffer);
        Path lspRoot=edt(()->editor.lspController.resolveWorkspaceRoot(Path.of(b.getFilePath()).getParent()));
        edt(()->{editor.lspClients.put(new LspServerKey("java",lspRoot),client);return null;});
        try {
            for(int i=0;i<3;i++) {
                CountDownLatch observed=new CountDownLatch(1);long start=System.nanoTime();
                SwingUtilities.invokeLater(()->{String result=editor.lspController.lspGoToDefinition();out(Map.of("operation","definition_command","result",result));});
                SwingUtilities.invokeLater(()->{out(Map.of("operation","input_queue_after_definition","ms",(System.nanoTime()-start)/1e6));observed.countDown();});
                check(observed.await(4,TimeUnit.SECONDS),"definition queue wait");
            }
        } finally {edt(()->{editor.lspClients.remove(new LspServerKey("java",lspRoot));return null;});client.stop();}
        check(original.equals(edt(()->editor.getCurrentBuffer().getContent())),"interference changed source");
        out(Map.of("operation","interference_source_integrity","exact",true));
    }
    private static void capture(String name) throws Exception {
        edt(()->{editor.validate();var root=editor.getRootPane();
            for(int scale:new int[]{1,2}){var image=new BufferedImage(root.getWidth()*scale,root.getHeight()*scale,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
                try{g.scale(scale,scale);root.paint(g);}finally{g.dispose();}ImageIO.write(image,"png",folder.resolve(name+"-component-"+scale+"x.png").toFile());}
            geometry("visual_geometry");return null;});
        if(!Boolean.getBoolean("b0.capture.owned")){out(Map.of("operation","native_capture","state","NOT_REQUESTED"));return;}
        try{OwnedWindowCapture.capture(editor.getTitle(),edt(editor::getWidth),edt(editor::getHeight),folder.resolve(name+"-native.png"));
            out(Map.of("operation","native_capture","phase",name,"state","OK"));}
        catch(Exception unavailable){out(Map.of("operation","native_capture","phase",name,"state","UNAVAILABLE","reason",unavailable.toString()));}
    }
    private static void shutdownActive() throws Exception {
        var b=edt(editor::getCurrentBuffer);
        Path root=edt(()->editor.lspController.resolveWorkspaceRoot(Path.of(b.getFilePath()).getParent()));
        LspClient client=WholeIdeServicesProbe.client(folder,0,folder.resolve("shutdown-server-lifecycle.log"));
        edt(()->{editor.lspClients.put(new LspServerKey("java",root),client);return null;});
        CountDownLatch ready=new CountDownLatch(1);
        editor.asyncJobService.submit("B0 shutdown cancellation",token->{
            var process=BackgroundTaskProcess.start(List.of("/bin/sh","-c","printf ready; exec sleep 10"),folder.toFile(),Map.of(),8192,"ready");
            check(process.awaitReadiness(1000,token).isEmpty(),"shutdown process readiness");ready.countDown();return process.awaitCompletion(token);
        },(request,result,error)->out(Map.of("operation","shutdown_task_callback","error",String.valueOf(error))));
        check(ready.await(2,TimeUnit.SECONDS),"shutdown background did not start");
        out(Map.of("operation","shutdown_background_ready","owned_children",ProcessHandle.current().descendants()
            .map(p->Map.of("pid",p.pid(),"command",p.info().commandLine().orElse(""))).toList()));
    }
    private static void visual() throws Exception {
        FileBuffer b=edt(editor::getCurrentBuffer);var snapshot=edt(b::textSnapshot);long revision=edt(b::documentRevision);
        edt(()->{editor.setTitle("Shed B0 isolated rendering");editor.setSize(1200,800);editor.splitWindow(true);editor.writingArea.select(4,12);return null;});
        Thread.sleep(400);phase="initial";capture(phase);
        for(int i=0;i<4;i++){
            phase="appearance-cycle-"+i;
            edt(()->{editor.configManager.set("ui.density","comfortable");editor.sessionConfigController.applySetting("ui.density");
                editor.commandHandler.execute("center on");editor.commandHandler.execute("focus on");return null;});edt(()->null);
            edt(()->{editor.commandHandler.execute("focus off");editor.commandHandler.execute("center off");editor.setSize(800,600);
                editor.configManager.set("ui.density","compact");editor.sessionConfigController.applySetting("ui.density");return null;});edt(()->null);
            edt(()->{editor.setSize(1200,800);return null;});
        }
        phase="after-appearance";capture(phase);
        edt(()->{var font=editor.writingArea.getFont();out(Map.of("operation","font_coverage","font",font.toString(),"first_unsupported_utf16_offset",font.canDisplayUpTo(b.getContent()),
            "document_i18n",String.valueOf(b.getDocument().getProperty("i18n"))));return null;});
        check(snapshot==edt(b::textSnapshot)&&revision==edt(b::documentRevision),"visual source changed");
        out(Map.of("operation","visual_source_integrity","exact",true,"caught_paint_errors",paintErrors));
        phase="unlaid-out-gutter-control";
        edt(()->{var area=new JTextArea("body\n");var gutter=new LineNumberPanel(area);gutter.setSize(60,100);
            int offset=area.viewToModel2D(new Point(0,0));
            out(Map.of("operation","gutter_unlaid_out_control","offset",offset,"source_length",area.getDocument().getLength(),"bounds",area.getBounds().toString()));
            var image=new BufferedImage(60,100,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
            try{gutter.paint(g);}finally{g.dispose();}return null;});
    }
    public static void main(String[] args) throws Exception {
        WholeIdeServicesProbe.requireNativeFixture();
        folder=Path.of(args[2]);String mode=args[0];String target=args[1];long begin=System.nanoTime();
        PrintStream previous=System.err;
        System.setErr(new PrintStream(previous,true){@Override public void println(Object value){
            if(value instanceof javax.swing.text.BadLocationException&&SwingUtilities.isEventDispatchThread()){
                paintErrors++;geometry("caught_bad_location_geometry");}super.println(value);}});
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            out(Map.of("operation","jvm_shutdown","children_alive",ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count()));
        }));
        try {
            editor=edt(()->new Texteditor(target.equals("none")?new String[0]:new String[]{target}));
            out(Map.of("operation","window_constructed","ms",(System.nanoTime()-begin)/1e6));
            boolean ready=false;long deadline=System.nanoTime()+30_000_000_000L,last=0;
            while(System.nanoTime()<deadline){
                ready=edt(()->!editor.stagedStartupPending&&(target.equals("none")?editor.getCurrentBuffer()!=null:
                    Files.isDirectory(Path.of(target))?!editor.workspaceController.roots().isEmpty():
                    editor.getCurrentBuffer()!=null&&editor.getCurrentBuffer().hasFilePath()&&Files.isSameFile(Path.of(editor.getCurrentBuffer().getFilePath()),Path.of(target))));
                if(ready)break;
                if(System.nanoTime()-last>1_000_000_000L){last=System.nanoTime();out(edt(()->Map.of("operation","load_state","path",String.valueOf(editor.getCurrentBuffer()==null?null:editor.getCurrentBuffer().getFilePath()),"staged",editor.stagedStartupPending,"active_pane",editor.activePaneIndex,"buffers",editor.buffers.size(),"jobs",editor.asyncJobService.list().toString())));}
                Thread.sleep(20);
            }
            if(!ready){out(Map.of("operation","load_timeout","elapsed_ms",(System.nanoTime()-begin)/1e6));
                for(var entry:Thread.getAllStackTraces().entrySet())if(entry.getKey().getName().startsWith("AWT-EventQueue"))out(Map.of("operation","timeout_edt_stack","stack",Arrays.toString(entry.getValue())));
                throw new TimeoutException("B0 loaded-editor wait");}
            out(edt(()->Map.of("operation","loaded","ms",(System.nanoTime()-begin)/1e6,"literal_path_equal",target.equals("none")||editor.getCurrentBuffer()!=null&&target.equals(editor.getCurrentBuffer().getFilePath()),"actual_path",String.valueOf(editor.getCurrentBuffer()==null?null:editor.getCurrentBuffer().getFilePath()))));
            edt(()->{editor.setMode(EditorMode.INSERT);editor.setSize(1100,760);editor.validate();editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());return null;});
            out(Map.of("operation","painted_ready","ms",(System.nanoTime()-begin)/1e6));
            if(mode.equals("visual"))visual();
            else if(target.equals("none"))typing("ordinary_input",null);
            else if(!Files.isDirectory(Path.of(target))){basic();if(mode.equals("interference"))interference();if(mode.equals("shutdown-active"))shutdownActive();}
            WholeIdeServicesProbe.state("before_shutdown");
            int hold=Integer.getInteger("b0.diagnostic.hold.ms",0);
            if(hold>0){check(hold<=5000,"diagnostic hold bound");out(Map.of("operation","diagnostic_hold","ms",hold));Thread.sleep(hold);}
            out(Map.of("operation","shutdown_requested","epoch_ms",System.currentTimeMillis()));
            edt(()->{editor.closeEditor();return null;});
        } catch(Throwable error){error.printStackTrace();edt(()->{if(editor!=null)editor.dispose();return null;});System.exit(1);}
    }
}
