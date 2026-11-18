package shed;

import java.awt.Window;
import java.lang.management.*;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in observation only. Never installed in the packaged application. */
public final class MarkdownQualificationProbe {
    private static Texteditor editor;
    private static Path output;
    private static boolean diagnostic;
    private static WeakReference<FileBuffer> closedInitial, closedNext;
    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void quiet() throws Exception {
        await(() -> !editor.stagedStartupPending && editor.asyncJobService.list().stream()
            .noneMatch(j -> j.getStatus() == AsyncJobService.Status.RUNNING), 20);
        Thread.sleep(500); // Fixed observation window, not a correctness synchronization primitive.
        edt(() -> null);
    }
    private static void command(String phase, String tool, String... arguments) throws Exception {
        var args = new ArrayList<String>(); args.add(tool); args.addAll(List.of(arguments));
        var file = output.resolve(phase + ".txt");
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(file.toFile()).start();
        boolean done = process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) { process.destroyForcibly(); process.waitFor(); }
        out("MD_META ", Map.of("operation", phase, "exit", process.exitValue(), "timeout", !done, "path", file.toString()));
    }
    private static void snapshot(String phase, boolean collect, boolean details) throws Exception {
        quiet(); memory(phase + "_no_forced_gc");
        if (collect) { for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); } memory(phase + "_requested_gc"); }
        var heap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        var rows = new ArrayList<Map<String,Object>>();
        for (var pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            rows.add(Map.of("name",pool.getName(),"count",pool.getCount(),"memory",pool.getMemoryUsed(),"capacity",pool.getTotalCapacity()));
        out("MD_META ", Map.of("operation", phase + "_resources", "nonheap_used",heap.getUsed(),"nonheap_committed",heap.getCommitted(),
            "loaded_classes",ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),"buffers",rows,
            "open_buffers",edt(() -> editor.buffers.size()),"panes",edt(() -> editor.editorPanes.size()),
            "semantic_initialized",edt(() -> field(editor.markdownController,"headingAnalysis") != null),
            "link_index_initialized",edt(() -> field(editor,"markdownLinks") != null)));
        String pid = Long.toString(ProcessHandle.current().pid());
        command(phase + "_ps", "/bin/ps", "-o", "rss=,vsz=", "-p", pid);
        if (diagnostic && details) {
            String jcmd = Path.of(System.getProperty("java.home"),"bin/jcmd").toString();
            command(phase + "_nmt", jcmd,pid,"VM.native_memory","summary","scale=KB");
            command(phase + "_heap", jcmd,pid,"GC.heap_info");
            command(phase + "_vmmap", "/usr/bin/vmmap","-summary",pid);
            command(phase + "_classes", jcmd,pid,"GC.class_histogram"); // Explicitly labelled intrusive GC diagnostic.
        }
    }
    private static List<WeakReference<Window>> lifecycle(Path small) throws Exception {
        var dismissed = new ArrayList<WeakReference<Window>>();
        var initial = edt(editor::getCurrentBuffer); var next = new FileBuffer(small.toFile());
        closedInitial=new WeakReference<>(initial);closedNext=new WeakReference<>(next);
        edt(() -> {editor.buffers.add(next);editor.loadBufferIntoEditor(next);return null;});
        edt(() -> {editor.handleMarkdownPreview("preview");return null;}); snapshot("preview_open",true,true);
        edt(() -> {editor.handleMarkdownPreview("close");return null;}); snapshot("preview_closed",true,false);
        for (int i = 0; i < 10; i++) {
            edt(() -> {
                editor.loadBufferIntoEditor(initial);editor.loadBufferIntoEditor(next);
                editor.handleMarkdownPreview("preview");editor.handleMarkdownPreview("close");
                var cancel = new javax.swing.Timer(10,null); cancel.addActionListener(e -> {
                    for (var w : editor.getOwnedWindows()) if (w instanceof JDialog dialog && dialog.isVisible()
                            && dialog.getTitle().equals("Command Palette")) {
                        dismissed.add(new WeakReference<>(w));
                        dialog.getRootPane().getActionMap().get("dismiss").actionPerformed(new java.awt.event.ActionEvent(dialog,0,"cancel"));
                        cancel.stop();
                    }
                });
                var timeout = new javax.swing.Timer(5000,e -> {for(var w:editor.getOwnedWindows()) if(w instanceof JDialog d && d.isVisible())d.dispose();});timeout.setRepeats(false);
                cancel.start();timeout.start();try{editor.showCommandPalette();}finally{cancel.stop();timeout.stop();}
                return null;
            });
        }
        snapshot("after_ten_cycles",true,true);
        edt(() -> {editor.loadBufferIntoEditor(initial);editor.closeActiveBufferWithoutExiting(true);
            editor.loadBufferIntoEditor(next);editor.closeActiveBufferWithoutExiting(true);return null;});
        return dismissed;
    }
    private record Visit(Object object, String path, int depth) { }
    private static String retainedPath(FileBuffer target) {
        if(target==null)return "COLLECTED";
        var queue=new ArrayDeque<Visit>();queue.add(new Visit(editor,"editor",0));
        var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        int inspected=0;
        while(!queue.isEmpty() && inspected++<20000) {
            var visit=queue.remove();Object value=visit.object();
            if(value==target)return visit.path();
            if(value==null || visit.depth()>10 || !seen.add(value) || value instanceof java.lang.ref.Reference<?>)continue;
            if(value instanceof Map<?,?> map) {
                int count=0;for(var entry:map.entrySet()) {if(++count>256)break;
                    if(!(map instanceof WeakHashMap))queue.add(new Visit(entry.getKey(),visit.path()+".key["+count+"]",visit.depth()+1));
                    queue.add(new Visit(entry.getValue(),visit.path()+".value["+count+"]",visit.depth()+1));}
            } else if(value instanceof Collection<?> list) {
                int count=0;for(var element:list){if(++count>256)break;queue.add(new Visit(element,visit.path()+"["+count+"]",visit.depth()+1));}
            } else if(value.getClass().getName().startsWith("shed.")) {
                for(var f:value.getClass().getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) {
                    try{f.setAccessible(true);queue.add(new Visit(f.get(value),visit.path()+"."+f.getName(),visit.depth()+1));}catch(ReflectiveOperationException ignored){}
                }
            }
        }
        return "NOT_FOUND_IN_BOUNDED_READ_ONLY_GRAPH";
    }

    private static void persistence() throws Exception {
        var buffer = edt(editor::getCurrentBuffer);
        var original = edt(buffer::textSnapshot);
        uiText("qualification_typing",() -> typed('x'),true);
        edt(() -> {editor.persistRecoverySnapshots();return null;});
        await(() -> {
            var journal = RecoveryJournal.read(editor.recoveryStoreDir.toPath());
            return journal != null && journal.entries().stream().anyMatch(e -> e.content().equals(buffer.getContent()));
        },20);
        var save = edt(() -> buffer.captureSaveSnapshot(null)); buffer.writeSaveSnapshot(save);
        edt(() -> {buffer.completeSaveSnapshot(save);return null;});
        var reopened = new FileBuffer(Path.of(buffer.getFilePath()).toFile());
        if (!reopened.getContent().equals(edt(buffer::getContent))) throw new AssertionError("Save/reopen mismatch");
        uiText("qualification_undo",() -> buffer.getUndoManager().undo(),true);
        if (!original.text().equals(edt(buffer::getContent))) throw new AssertionError("Undo mismatch");
        out("MD_META ",Map.of("operation","constrained_persistence","save_reopen",true,"recovery",true,"undo",true));
    }
    private static java.util.List<WeakReference<FileBuffer>> retainClosedCopies() throws Exception {
        var refs=new ArrayList<WeakReference<FileBuffer>>();Path original=Path.of(edt(() -> editor.getCurrentBuffer().getFilePath()));
        for(int i=0;i<8;i++) {
            Path copy=output.resolve("closed-"+i+".md");Files.copy(original,copy);var buffer=new FileBuffer(copy.toFile());
            edt(() -> {editor.buffers.add(buffer);editor.loadBufferIntoEditor(buffer);return null;});
            await(() -> ((OpenBufferCompletionIndex)field(editor.inputController,"openBufferCompletionIndex")).hasSnapshot(buffer,buffer.textSnapshot()),20);
            refs.add(new WeakReference<>(buffer));edt(() -> {editor.closeActiveBufferWithoutExiting(true);return null;});
        }
        return refs;
    }
    private static void retention() throws Exception {
        var refs=retainClosedCopies();snapshot("eight_closed_buffers",true,false);
        out("MD_META ",edt(() -> Map.of("operation","closed_before_query","alive",refs.stream().filter(r->r.get()!=null).count(),
            "index_entries",((Map<?,?>)field(field(editor.inputController,"openBufferCompletionIndex"),"snapshots")).size(),"open_buffers",editor.buffers.size())));
        edt(() -> {((OpenBufferCompletionIndex)field(editor.inputController,"openBufferCompletionIndex")).complete(editor.buffers,editor.getCurrentBuffer(),"al",0,20,new FuzzyMatchService());return null;});
        snapshot("after_normal_completion_query",true,false);
        out("MD_META ",edt(() -> Map.of("operation","closed_after_query","alive",refs.stream().filter(r->r.get()!=null).count(),
            "index_entries",((Map<?,?>)field(field(editor.inputController,"openBufferCompletionIndex"),"snapshots")).size(),"open_buffers",editor.buffers.size())));
    }

    private static WeakReference<?> index() throws Exception {
        Path notes=Files.createDirectories(output.resolve("notes"));
        for(int i=0;i<1000;i++)Files.writeString(notes.resolve("note-"+i+".md"),"# Note "+i+"\n[source](../input.md)\n[[note-"+((i+1)%1000)+"]]\n");
        edt(() -> {editor.workspaceRoots.add(output);editor.markdownLinks().request(output);return null;});
        await(() -> editor.markdownLinks().diagnostics().running()==0 && editor.markdownLinks().diagnostics().pending()==0,60);
        out("MD_META ",Map.of("operation","qualified_index","metrics",edt(() -> editor.markdownLinks().metrics(output).orElseThrow().toString()),
            "status",edt(() -> editor.markdownLinks().backlinks(output,Path.of(editor.getCurrentBuffer().getFilePath())).state().name())));
        snapshot("index_built",true,true);
        var weak=edt(() -> {var value=editor.markdownLinks();var ref=new WeakReference<>(value);editor.disposeMarkdownPreviews();return ref;});
        return weak;
    }
    private static void queued(String operation, Runnable task) throws Exception {
        var finished=new java.util.concurrent.CompletableFuture<Void>();long posted=System.nanoTime();
        SwingUtilities.invokeLater(() -> {long start=System.nanoTime();try {task.run();editor.validate();editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());
                out("MD_SAMPLE ",Map.of("operation",operation,"state","OK","edt_ms",(System.nanoTime()-start)/1e6));
            } catch(Throwable failure){finished.completeExceptionally(failure);}});
        SwingUtilities.invokeLater(() -> {try {
                out("MD_SAMPLE ",Map.of("operation",operation+"_queued_source","state","OK","callback_delay_ms",(System.nanoTime()-posted)/1e6));
                var buffer=editor.getCurrentBuffer();typed('x');buffer.getUndoManager().undo();buffer.setModified(false);finished.complete(null);
            } catch(Throwable failure){finished.completeExceptionally(failure);}});
        finished.get(60,java.util.concurrent.TimeUnit.SECONDS);
    }
    private static void preview() throws Exception {
        var before=edt(() -> editor.getCurrentBuffer().getContent());
        queued("production_preview_open",() -> editor.handleMarkdownPreview("preview"));
        for(int i=0;i<5;i++)queued("production_preview_refresh",() -> editor.handleMarkdownPreview("refresh"));
        var size=edt(editor::getSize);
        for(int i=0;i<3;i++) {int delta=i%2==0?120:0;queued("production_preview_resize",() -> editor.setSize(size.width-delta,size.height));}
        queued("production_preview_close",() -> editor.handleMarkdownPreview("close"));
        if(!before.equals(edt(() -> editor.getCurrentBuffer().getContent())))throw new AssertionError("Preview/source mutation");
        snapshot("after_production_preview",true,false);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("input|- mode baseline|lifecycle|persistence small.md output diagnostic");
        String input=args[0],mode=args[1];output=Path.of(args[3]);diagnostic=Boolean.parseBoolean(args[4]);
        if (mode.equals("startup") || mode.equals("unicode")) {
            for (var entry : Map.<String,Object>of("file",input.equals("-")?Path.of(args[2]):Path.of(input),"empty",input.equals("-")).entrySet()) {
                var f=MarkdownPerformanceProbe.class.getDeclaredField(entry.getKey());f.setAccessible(true);f.set(null,entry.getValue());
            }
            var open=MarkdownPerformanceProbe.class.getDeclaredMethod("open");open.setAccessible(true);open.invoke(null);
            var shared=MarkdownPerformanceProbe.class.getDeclaredField("editor");shared.setAccessible(true);editor=(Texteditor)shared.get(null);
        } else {
            editor=edt(() -> new Texteditor(input.equals("-")?new String[0]:new String[]{input}));
            var shared=MarkdownPerformanceProbe.class.getDeclaredField("editor");shared.setAccessible(true);shared.set(null,editor);
            await(() -> !editor.stagedStartupPending && editor.getCurrentBuffer()!=null,30);
            edt(() -> {editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());return null;});
            snapshot("initial_idle",true,true);
        }
        out("MD_META ",edt(() -> Map.of("operation","qualification_appearance","theme",editor.configManager.getThemeId(),"configured_font",editor.configManager.getFontFamily(),"actual_editor_family",editor.writingArea.getFont().getFamily())));
        if(mode.equals("retention"))retention();
        else if(mode.equals("index")) {var ref=index();snapshot("after_index_disposal",true,true);out("MD_META ",Map.of("operation","index_release","collected",ref.get()==null));}
        else if(mode.equals("media") || mode.equals("media-control")) {
            var helper=Class.forName(mode.equals("media")?"shed.MarkdownMediaProbe":"shed.MarkdownMediaControlProbe");
            var run=helper.getDeclaredMethod("run",Texteditor.class,int.class,int.class);run.setAccessible(true);run.invoke(null,editor,50,5);
            snapshot("after_rich_media",true,true);
        }
        else if(mode.equals("preview"))preview();
        else if (mode.equals("unicode")) {
            var buffer=edt(editor::getCurrentBuffer);String expected=edt(buffer::getContent);
            for(int i=0;i<30;i++) {
                edt(() -> {editor.writingArea.setCaretPosition(0);return null;});
                long began=System.nanoTime();uiText("unicode_steady_typing",()->typed('x'),true);
                uiText("unicode_steady_undo",()->buffer.getUndoManager().undo(),true);
                if(!expected.equals(edt(buffer::getContent)))throw new AssertionError("Unicode source/undo mismatch");
                if(System.nanoTime()-began>500_000_000L){out("MD_META ",Map.of("operation","unicode_latency_guard","after_samples",i+1));break;}
            }
            out("MD_META ",Map.of("operation","unicode_integrity","utf16_units",expected.length(),"source_and_undo_exact",true));
        }
        else if(mode.equals("lifecycle")) {var dismissed=lifecycle(Path.of(args[2]));snapshot("after_sources_closed",true,true);
            out("MD_META ",Map.of("operation","picker_release","created",dismissed.size(),"collected",dismissed.stream().filter(w->w.get()==null).count()));
            out("MD_META ",edt(() -> Map.of("operation","closed_source_references","initial_collected",closedInitial.get()==null,"next_collected",closedNext.get()==null,
                "initial_still_open",editor.buffers.contains(closedInitial.get()),"next_still_open",editor.buffers.contains(closedNext.get()),
                "initial_path",retainedPath(closedInitial.get()),"next_path",retainedPath(closedNext.get()))));}
        else if(mode.equals("persistence")) {persistence();snapshot("after_persistence",true,true);}
        else if(!mode.equals("baseline") && !mode.equals("startup"))throw new IllegalArgumentException("Unknown mode");
        edt(() -> {editor.disposeMarkdownPreviews();editor.shutdownSyntaxUi();editor.shutdownLspScheduling();editor.asyncJobService.shutdownNow();
            if(editor.backupScheduler!=null)editor.backupScheduler.close();if(editor.externalChangeTimer!=null)editor.externalChangeTimer.stop();
            if(editor.recoverySnapshotTimer!=null)editor.recoverySnapshotTimer.stop();if(editor.messageResetTimer!=null)editor.messageResetTimer.stop();
            editor.fileWatcherService.stop();editor.dispose();return null;});
        System.out.println("MD_DONE");System.out.flush();System.exit(0);
    }
}
