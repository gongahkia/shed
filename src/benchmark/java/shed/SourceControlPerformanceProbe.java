package shed;

import java.awt.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Opt-in owned-window probe; the same common workload runs against both artifacts. */
public final class SourceControlPerformanceProbe {
    private static Texteditor editor;
    private static Path output,project;
    private static final AtomicReference<String> phase=new AtomicReference<>("startup");
    private static final AtomicReference<Throwable> uncaught=new AtomicReference<>();
    private static final List<Map<String,Object>> rows=new ArrayList<>();
    private static final Map<String,List<Double>> edtEvents=new LinkedHashMap<>();
    private static <T>T ui(Callable<T> task)throws Exception{var future=new FutureTask<T>(task);SwingUtilities.invokeLater(future);return future.get(15,TimeUnit.SECONDS);}
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!ui(condition::getAsBoolean)){if(System.nanoTime()>deadline)throw new AssertionError("Timed out in "+phase.get());Thread.sleep(10);}}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static Object field(Object owner,String name)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void field(Object owner,String name,Object value)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Object call(Object owner,String name,Class<?>[] types,Object...args)throws Exception{var method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);}
    private static JButton button(Container root,String name){for(var c:root.getComponents()){if(c instanceof JButton b&&b.getText().equals(name))return b;if(c instanceof Container child){var b=button(child,name);if(b!=null)return b;}}return null;}
    private static double percentile(List<Double> values,double p){var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.get((int)Math.ceil(sorted.size()*p)-1);}
    private static void paint(){editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());}
    private static void record(String name,List<Double> samples,List<Double> queue,double first){var row=new LinkedHashMap<String,Object>();row.put("operation",name);row.put("samples_ms",samples);row.put("p50_ms",percentile(samples,.5));row.put("p95_ms",percentile(samples,.95));if(samples.size()>=100)row.put("p99_ms",percentile(samples,.99));row.put("max_ms",Collections.max(samples));row.put("first_ms",first);row.put("warmups",name.startsWith("typing.")?5:0);if(queue!=null){row.put("queued_ms",queue);row.put("queued_p95_ms",percentile(queue,.95));row.put("queued_max_ms",Collections.max(queue));}rows.add(row);}
    private static void typing(String name)throws Exception{typing(name,()->{});}
    private static void typing(String name,Runnable launch)throws Exception{
        phase.set(name);String before=ui(()->editor.writingArea.getText());var buffer=ui(editor::getCurrentBuffer);
        ui(()->{var area=editor.writingArea;area.setCaretPosition(area.getDocument().getLength());var bounds=area.modelToView2D(area.getDocument().getLength());if(bounds!=null)area.scrollRectToVisible(bounds.getBounds());paint();return null;});
        var samples=new ArrayList<Double>();var queued=new ArrayList<Double>();var warmups=new ArrayList<Double>();double first=0;launch.run();
        for(int i=0;i<106;i++){long submitted=System.nanoTime();double[] result=ui(()->{long entered=System.nanoTime();editor.writingArea.append("x");paint();return new double[]{(System.nanoTime()-submitted)/1e6,(entered-submitted)/1e6};});if(i==0)first=result[0];if(i>0&&i<6)warmups.add(result[0]);if(i>=6){samples.add(result[0]);queued.add(result[1]);}}
        check(ui(()->editor.getCurrentBuffer()==buffer&&buffer.getContent().equals(before+"x".repeat(106))&&buffer.getUndoManager().canUndo()),"Typing lost source or Undo in "+name);
        ui(()->{editor.writingArea.getDocument().remove(before.length(),106);return null;});check(ui(()->buffer.getContent().equals(before)),"Typing cleanup changed source");
        record(name,samples,queued,first);rows.getLast().put("warmup_samples_ms",warmups);if(name.startsWith("typing.installing."))check(Math.max(first,Math.max(Collections.max(samples),Collections.max(warmups)))<=50,"Status installation delayed source input beyond 50 ms");check(percentile(samples,.95)<=16,"Typing p95 exceeded 16 ms in "+name+": "+percentile(samples,.95));
    }
    private static void open(String filename)throws Exception{open(filename,"open."+filename);}
    private static void open(String filename,String name)throws Exception{
        phase.set(name);long started=System.nanoTime();Path file=project.resolve(filename);String expected=Files.readString(file);
        ui(()->{editor.openFileAsync(file.toFile());return null;});await(()->file.toFile().equals(editor.getCurrentBuffer().getFile())&&editor.getCurrentBuffer().getContent().equals(expected));ui(()->{paint();return null;});
        rows.add(Map.of("operation",phase.get(),"ms",(System.nanoTime()-started)/1e6,"utf16_units",expected.length()));
    }
    private static void editorOperations()throws Exception{
        String before=ui(()->editor.writingArea.getText());
        for(String action:List.of("deletion","newline","caret","selection","scroll")){
            phase.set(action);var samples=new ArrayList<Double>();
            for(int i=0;i<50;i++){
                if(action.equals("deletion"))ui(()->{editor.writingArea.append("d");return null;});
                long submitted=System.nanoTime();samples.add(ui(()->{
                    var area=editor.writingArea;int length=area.getDocument().getLength();
                    switch(action){
                        case "deletion"->area.getDocument().remove(length-1,1);
                        case "newline"->area.getDocument().insertString(length,"\n",null);
                        case "caret"->area.setCaretPosition(area.getCaretPosition()==0?length:0);
                        case "selection"->area.select(0,Math.min(60,length));
                        case "scroll"->{var viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,area);viewport.setViewPosition(new Point(0,viewport.getViewPosition().y==0?Math.max(0,area.getHeight()-viewport.getHeight()):0));}
                    }
                    paint();return (System.nanoTime()-submitted)/1e6;
                }));
                if(action.equals("newline"))ui(()->{editor.writingArea.getDocument().remove(before.length(),1);return null;});
            }
            check(ui(()->editor.writingArea.getText().equals(before)),action+" changed unrelated source");record(action,samples,null,samples.getFirst());
        }
    }
    private static Map<String,Object> memory(){var runtime=Runtime.getRuntime();var result=new LinkedHashMap<String,Object>();result.put("used_heap_bytes",runtime.totalMemory()-runtime.freeMemory());result.put("committed_heap_bytes",runtime.totalMemory());result.put("threads",ManagementFactory.getThreadMXBean().getThreadCount());var os=ManagementFactory.getOperatingSystemMXBean();if(os instanceof com.sun.management.UnixOperatingSystemMXBean unix)result.put("open_descriptors",unix.getOpenFileDescriptorCount());result.put("descendants",ProcessHandle.current().descendants().count());return result;}
    private static Map<String,Object> workers(){var value=new LinkedHashMap<String,Object>();value.put("memory",memory());value.put("thread_names",Arrays.stream(ManagementFactory.getThreadMXBean().dumpAllThreads(false,false)).map(t->t.getThreadName()+" · "+t.getThreadState()).sorted().toList());value.put("shed_workers",Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().equals("shed-worker")).count());return value;}
    private static void idleWorkers()throws Exception{
        if(!"1".equals(System.getenv("SHED_SCM_IDLE_PROFILE")))return;
        phase.set("idle_workers");var before=workers();long started=System.nanoTime();Thread.sleep(35000);for(int i=0;i<3;i++){System.gc();Thread.sleep(50);}var after=workers();
        var completion=new CompletableFuture<Long>();long submitted=System.nanoTime();double submit=ui(()->{long t=System.nanoTime();editor.asyncJobService.submit("Idle worker resume",token->System.nanoTime(),(job,result,error)->{if(error!=null)completion.completeExceptionally(error);else completion.complete(System.nanoTime());});return (System.nanoTime()-t)/1e6;});long finished=completion.get(5,TimeUnit.SECONDS);
        rows.add(Map.of("operation","idle_workers","idle_ms",(System.nanoTime()-started)/1e6,"before",before,"after",after,"resume_edt_submit_ms",submit,"resume_completion_ms",(finished-submitted)/1e6));typing("typing.after_idle");
    }
    private static void common()throws Exception{
        for(String file:List.of("Small.txt","100KiB.txt","1MiB.txt")){open(file);typing("typing.hidden."+file);}
        open("Small.txt","return.Small.txt");editorOperations();phase.set("undo_redo");String before=ui(()->editor.writingArea.getText());
        var undo=new ArrayList<Double>();var redo=new ArrayList<Double>();
        for(int i=0;i<30;i++){
            ui(()->{editor.getCurrentBuffer().getUndoManager().discardAllEdits();editor.writingArea.append("undo-fixture");return null;});
            undo.add(ui(()->{long t=System.nanoTime();editor.getCurrentBuffer().getUndoManager().undo();paint();check(editor.writingArea.getText().equals(before),"Undo lost source");return (System.nanoTime()-t)/1e6;}));
            redo.add(ui(()->{long t=System.nanoTime();editor.getCurrentBuffer().getUndoManager().redo();paint();check(editor.writingArea.getText().equals(before+"undo-fixture"),"Redo lost source");return (System.nanoTime()-t)/1e6;}));ui(()->{editor.getCurrentBuffer().getUndoManager().undo();return null;});
        }
        record("undo",undo,null,undo.getFirst());record("redo",redo,null,redo.getFirst());
        phase.set("splits");var splits=new ArrayList<Double>();for(int i=0;i<20;i++)splits.add(ui(()->{var buffer=editor.getCurrentBuffer();long t=System.nanoTime();editor.splitWindow(true);editor.closeActiveWindow();paint();check(editor.editorPanes.size()==1&&editor.getCurrentBuffer()==buffer&&buffer.getContent().equals(before),"Split cycling changed source ownership");return (System.nanoTime()-t)/1e6;}));record("split_cycle",splits,null,splits.getFirst());
        ui(()->{editor.showToolWindow(ToolWindowHost.Tab.GIT);return null;});await(()->editor.asyncJobService.list().stream().noneMatch(j->j.getStatus()==AsyncJobService.Status.RUNNING));typing("typing.source_control_open");ui(()->{editor.hideToolWindow();return null;});
        phase.set("buffer_switch");var buffers=ui(()->editor.buffers.stream().filter(b->b.getFile()!=null&&(b.getFile().getName().equals("Small.txt")||b.getFile().getName().equals("100KiB.txt"))).toList());check(buffers.size()==2,"Missing switching fixtures");var switching=new ArrayList<Double>();for(int i=0;i<50;i++){var target=buffers.get(i%2);switching.add(ui(()->{long started=System.nanoTime();editor.loadBufferIntoEditor(target);paint();double ms=(System.nanoTime()-started)/1e6;check(editor.getCurrentBuffer()==target&&editor.writingArea.getDocument()==target.getDocument(),"Buffer switch changed source ownership");return ms;}));}record("buffer_switch",switching,null,switching.getFirst());open("Small.txt","return.after_switch");
        phase.set("idle");var os=ManagementFactory.getOperatingSystemMXBean();long t=System.nanoTime(),cpu=os instanceof com.sun.management.OperatingSystemMXBean x?x.getProcessCpuTime():-1;Thread.sleep(750);long after=os instanceof com.sun.management.OperatingSystemMXBean x?x.getProcessCpuTime():-1;rows.add(Map.of("operation","idle","wall_ms",(System.nanoTime()-t)/1e6,"cpu_ms",cpu<0?-1:(after-cpu)/1e6));
        for(int i=0;i<3;i++){System.gc();Thread.sleep(50);}rows.add(Map.of("operation","common_settled_memory","diagnostic_gc",true,"values",memory()));
    }
    private static final class Client implements InvocationHandler {
        final AtomicBoolean fail=new AtomicBoolean();final AtomicReference<CountDownLatch> gate=new AtomicReference<>();final AtomicInteger active=new AtomicInteger(),calls=new AtomicInteger(),count=new AtomicInteger(1);final AtomicReference<CountDownLatch> entered=new AtomicReference<>();
        @Override public Object invoke(Object proxy,Method method,Object[] args)throws Exception{
            if(method.getDeclaringClass()==Object.class)return method.getName().equals("toString")?"FixedSourceControlClient":method.getName().equals("hashCode")?System.identityHashCode(proxy):proxy==args[0];
            @SuppressWarnings("unchecked")var argv=(List<String>)args[1];var token=(AsyncJobService.JobToken)args[2];calls.incrementAndGet();
            if(argv.contains("status")){if(fail.get())return new CommandResult(1,"","Synthetic SCM failure");active.incrementAndGet();try{var block=gate.get();if(block!=null){token.onCancel(block::countDown);entered.get().countDown();check(block.await(10,TimeUnit.SECONDS),"Fixture status gate expired");if(token.isCancelled())throw new CancellationException();}boolean hg=argv.getFirst().equals("hg");var text=new StringBuilder(hg?"":"<status><target path='.'>");for(int i=0;i<count.get();i++){String path=i==0?"Small.txt":"file-"+i+".txt";text.append(hg?"M "+path+'\0':"<entry path='"+path+"'><wc-status item='modified' props='none'/></entry>");}if(!hg)text.append("</target></status>");return new CommandResult(0,text.toString(),"");}finally{active.decrementAndGet();}}
            if(argv.contains("branch"))return new CommandResult(0,"default\n","");if(argv.contains("resolve"))return new CommandResult(0,"","");if(argv.contains("info"))return new CommandResult(0,"<info><entry><url>https://example.invalid/trunk</url></entry></info>","");return new CommandResult(0,"@@ -1 +1 @@\n-before\n+after\n","");
        }
        void hold(){gate.set(new CountDownLatch(1));entered.set(new CountDownLatch(1));}
        void release(){var g=gate.getAndSet(null);if(g!=null)g.countDown();}
    }
    private static Object clientService(Client client)throws Exception{var runner=Class.forName("shed.ScmWorkbenchService$Runner");var ctor=Class.forName("shed.ScmWorkbenchService").getDeclaredConstructor(runner);ctor.setAccessible(true);return ctor.newInstance(Proxy.newProxyInstance(runner.getClassLoader(),new Class<?>[]{runner},client));}
    @SuppressWarnings({"rawtypes","unchecked"})private static Object provider(String name)throws Exception{return Enum.valueOf((Class)Class.forName("shed.ScmWorkbenchModel$Provider"),name);}
    private static void context(Object panel,Object provider,Path root)throws Exception{call(panel,"context",new Class<?>[]{provider.getClass(),Path.class,Path.class,long.class},provider,root,project,editor.workspaceRoots.generation());}
    private static List<WeakReference<Object>> disposalCycles(Object provider,Client client)throws Exception{
        var refs=new ArrayList<WeakReference<Object>>();var ctor=Class.forName("shed.ScmChangesPanel").getDeclaredConstructor(Texteditor.class);ctor.setAccessible(true);
        for(int i=0;i<12;i++){
            client.hold();Object panel=ui(()->{var p=ctor.newInstance(editor);field(p,"service",clientService(client));context(p,provider,project);return p;});
            check(client.entered.get().await(3,TimeUnit.SECONDS),"Disposed panel did not begin status");ui(()->{call(panel,"close",new Class<?>[]{});return null;});client.release();await(()->client.active.get()==0);refs.add(new WeakReference<>(panel));
        }return refs;
    }
    private static void providers()throws Exception{
        Object control;try{control=ui(()->call(editor.toolWindowHost,"sourceControlPanel",new Class<?>[]{}));}catch(ExecutionException missing){if(missing.getCause() instanceof NoSuchMethodException){rows.add(Map.of("operation","providers","state","ABSENT_IN_BASELINE"));return;}throw missing;}
        Object nativePanel=field(control,"nativeScm");var client=new Client();Object original=field(nativePanel,"service");field(nativePanel,"service",clientService(client));
        try{for(String name:List.of("MERCURIAL","SUBVERSION")){
            Object p=provider(name);Files.createDirectories(project.resolve(name.equals("MERCURIAL")?".hg":".svn"));client.count.set(1);client.hold();phase.set(name+".pending");
            ui(()->{editor.showToolWindow(ToolWindowHost.Tab.GIT);call(control,"select",new Class<?>[]{p.getClass()},p);return null;});check(client.entered.get().await(5,TimeUnit.SECONDS),"Provider did not enter controlled status");
            typing("typing.pending."+name);check(client.gate.get().getCount()==1&&ui(()->(boolean)field(nativePanel,"busy")),"Status completed before pending-input measurement");
            long cancel=System.nanoTime();ui(()->{button((Container)call(nativePanel,"component",new Class<?>[]{}),"Cancel").doClick(0);return null;});client.release();await(()->client.active.get()==0);rows.add(Map.of("operation","cancel."+name,"ms",(System.nanoTime()-cancel)/1e6));
            phase.set(name+".install5000");client.count.set(5000);client.hold();ui(()->{call(nativePanel,"refresh",new Class<?>[]{});return null;});check(client.entered.get().await(3,TimeUnit.SECONDS),"Bulk status did not begin");var released=new AtomicLong();var installed=new AtomicLong();JTable table=ui(()->(JTable)field(nativePanel,"fileTable"));var modelEvents=new AtomicInteger();javax.swing.event.TableModelListener listener=e->{modelEvents.incrementAndGet();if(table.getRowCount()==5000)SwingUtilities.invokeLater(()->{try{((JPanel)call(nativePanel,"component",new Class<?>[]{})).paintImmediately(((JPanel)call(nativePanel,"component",new Class<?>[]{})).getVisibleRect());installed.compareAndSet(0,System.nanoTime());}catch(Exception error){throw new RuntimeException(error);}});};ui(()->{table.getModel().addTableModelListener(listener);return null;});typing("typing.installing."+name,()->{released.set(System.nanoTime());client.release();});
            await(()->{try{return !(boolean)field(nativePanel,"busy")&&((JTable)field(nativePanel,"fileTable")).getRowCount()==5000;}catch(Exception e){throw new RuntimeException(e);}});await(()->installed.get()>0);ui(()->{table.getModel().removeTableModelListener(listener);return null;});rows.add(Map.of("operation","status_install_5000."+name,"ms",(installed.get()-released.get())/1e6,"rows",5000,"table_model_events",modelEvents.get()));typing("typing.5000_rows."+name);
            client.fail.set(true);ui(()->{call(nativePanel,"refresh",new Class<?>[]{});return null;});await(()->{try{return !(boolean)field(nativePanel,"busy")&&!((JLabel)field(nativePanel,"state")).getText().isBlank()&&((JLabel)field(nativePanel,"state")).getText().startsWith("Operation failed:");}catch(Exception e){throw new RuntimeException(e);}});typing("typing.failed."+name);
            client.fail.set(false);client.count.set(1);ui(()->{call(nativePanel,"refresh",new Class<?>[]{});return null;});await(()->{try{return !(boolean)field(nativePanel,"busy")&&(boolean)field(nativePanel,"ready");}catch(Exception e){throw new RuntimeException(e);}});
            ui(()->{editor.hideToolWindow();return null;});check(!ui(()->((javax.swing.Timer)field(nativePanel,"timer")).isRunning()),"Hidden SCM timer retained");int calls=client.calls.get();Thread.sleep(2700);check(client.calls.get()==calls,"Hidden SCM kept polling");rows.add(Map.of("operation","hidden_polling."+name,"observation_ms",2700,"calls_before",calls,"calls_after",client.calls.get(),"timer_running",false));
        }
        client.count.set(1);phase.set("disposed_panels");var refs=disposalCycles(provider("MERCURIAL"),client);for(int i=0;i<8&&refs.stream().anyMatch(r->r.get()!=null);i++){ui(()->null);System.gc();Thread.sleep(50);}long retained=refs.stream().filter(r->r.get()!=null).count();check(retained==0,"Closed SCM panels retained: "+retained);rows.add(Map.of("operation","scm_disposal","cycles",12,"retained_panels",retained,"active_client_calls",client.active.get(),"diagnostic_gc",true,"memory",memory()));
        }finally{client.release();ui(()->{call(nativePanel,"close",new Class<?>[]{});field(nativePanel,"service",original);return null;});}
    }
    public static void main(String[] args)throws Exception{
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path root=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();check(project.startsWith(root.resolve("run"))&&output.startsWith(root.resolve("run")),"Disposable fixture required");
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{uncaught.compareAndSet(null,error);error.printStackTrace();});
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue(){@Override protected void dispatchEvent(AWTEvent event){String stage=phase.get();long t=System.nanoTime();try{super.dispatchEvent(event);}finally{var samples=edtEvents.computeIfAbsent(stage,k->new ArrayList<>());if(samples.size()<4096)samples.add((System.nanoTime()-t)/1e6);}}});
        var watchdog=Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(140000);System.exit(124);}catch(InterruptedException done){}});var result=new LinkedHashMap<String,Object>();int exit=1;
        try{
            editor=ui(()->new Texteditor(new String[]{project.toString()}));await(()->editor.isShowing()&&!editor.stagedStartupPending);open("Small.txt","startup.open.Small.txt");ui(()->{paint();return null;});result.put("startup_to_completed_source_paint_jvm_ms",ManagementFactory.getRuntimeMXBean().getUptime());
            common();idleWorkers();providers();check(uncaught.get()==null,"Uncaught EDT failure: "+uncaught.get());check(ProcessHandle.current().descendants().findAny().isEmpty(),"Unexpected native child process");
            ui(()->{editor.toolWindowHost.close();editor.asyncJobService.shutdownNow();for(Window w:Window.getWindows())w.dispose();return null;});result.put("state","PASS");result.put("after_close",memory());exit=0;
        }catch(Throwable error){error.printStackTrace();result.put("state","FAIL");result.put("error",error.toString());}
        finally{
            result.put("rows",rows);result.put("edt_events_ms",ui(()->{var copy=new LinkedHashMap<String,List<Double>>();edtEvents.forEach((k,v)->copy.put(k,List.copyOf(v)));return copy;}));result.put("jvm_arguments",ManagementFactory.getRuntimeMXBean().getInputArguments());result.put("jdk",System.getProperty("java.runtime.version"));result.put("native_shell","NOT_USED");result.put("capture_boundary","owned Swing forced source paint; no physical keyboard/compositor timing");Files.writeString(output.resolve("result.json"),MiniJson.stringify(result));if(editor!=null)ui(()->{editor.toolWindowHost.close();editor.asyncJobService.shutdownNow();for(Window w:Window.getWindows())w.dispose();return null;});watchdog.interrupt();System.exit(exit);
        }
    }
}
