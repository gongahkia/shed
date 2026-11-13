package shed;

import java.awt.*;
import java.awt.event.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** Independent, explicitly invoked probe, compiled against the measured JAR (not shipped). */
public final class MarkdownPerformanceProbe {
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long PROCESS_START = System.currentTimeMillis();
    private static volatile Object sink;
    private static Texteditor editor;
    private static int samples, warmups;
    private static Path file;
    private static boolean wrap, withPreview, empty, failed;
    private static Object field(Object owner, String name) throws Exception {
        Field f = (owner instanceof Class<?> c ? c : owner.getClass()).getDeclaredField(name);
        f.setAccessible(true); return f.get(owner instanceof Class<?> ? null : owner);
    }
    private static Object invoke(Class<?> type, String name, Class<?>[] signature, Object... args) throws Exception {
        Method m = type.getDeclaredMethod(name, signature); m.setAccessible(true); return m.invoke(null, args);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void await(Callable<Boolean> ready, int seconds) throws Exception {
        long until = System.nanoTime() + seconds * 1_000_000_000L;
        while (!edt(ready)) {
            if (System.nanoTime() > until) throw new TimeoutException("UI state did not converge");
            Thread.sleep(10); // Poll state, not a timing sample; production paint duration comes from PerfService.
        }
    }
    private static void out(String prefix, Map<String, Object> row) { Map<String,Object> value=new LinkedHashMap<>(row);value.putIfAbsent("epoch_ms",System.currentTimeMillis()); System.out.println(prefix + MiniJson.stringify(value)); System.out.flush(); }
    private static Map<String, Object> row(String name) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("operation", name); row.put("state", "OK"); return row;
    }
    private static long allocated() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static void memory(String phase) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var gc = ManagementFactory.getGarbageCollectorMXBeans();
        Map<String,Object> value = row(phase); value.put("heap_used_mib", heap.getUsed()/1048576d);
        value.put("heap_committed_mib",heap.getCommitted()/1048576d);
        value.put("gc_count",gc.stream().mapToLong(b -> Math.max(0,b.getCollectionCount())).sum());
        value.put("gc_total_ms",gc.stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum());
        value.put("thread_count",ManagementFactory.getThreadMXBean().getThreadCount());
        out("MD_META ",value);
    }
    @FunctionalInterface interface Work { void run() throws Exception; }
    private static Map<String,Object> timed(String name, Work work) throws Exception {
        long bytes=allocated(), cpu=THREADS.getCurrentThreadCpuTime(), start=System.nanoTime();
        work.run(); long end=System.nanoTime();
        var value=row(name); value.put("wall_ms",(end-start)/1e6); value.put("thread_cpu_ms",(THREADS.getCurrentThreadCpuTime()-cpu)/1e6);
        value.put("thread_allocated_bytes",Math.max(0,allocated()-bytes)); value.put("thread",Thread.currentThread().getName());
        return value;
    }
    private static String hash(Path path) throws Exception {
        MessageDigest h=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)h.update(b,0,n);}
        return HexFormat.of().formatHex(h.digest());
    }
    private static String documentHash(Document doc) throws Exception {
        MessageDigest h=MessageDigest.getInstance("SHA-256");
        for(int i=0;i<doc.getLength();i+=8192) h.update(doc.getText(i,Math.min(8192,doc.getLength()-i)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h.digest());
    }
    private static long[] metric(String name) throws Exception {
        synchronized(editor.perfService) {
        Map<?,?> metrics=(Map<?,?>)field(editor.perfService,"metrics"); Object metric=metrics.get(name);
        return metric==null?new long[]{0,0}:new long[]{(long)field(metric,"count"),(long)field(metric,"lastNanos")};
        }
    }
    private static void open() throws Exception {
        editor=edt(() -> new Texteditor(empty ? new String[0] : new String[]{file.toString()}));
        var visible=row("window_visible");visible.put("epoch_ms",System.currentTimeMillis());out("MD_META ",visible);
        await(() -> editor.getCurrentBuffer()!=null && (empty || file.toString().equals(editor.getCurrentBuffer().getFilePath())),30);
        edt(() -> {editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());return null;});
        var source=row("source_installed_forced_paint");source.put("epoch_ms",System.currentTimeMillis());out("MD_META ",source);
        if(!edt(() -> editor.getCurrentBuffer().isLargeFile())) {
            // A real controller + Swing typed action, delivered through the EDT, before full workbench readiness.
            uiText("startup_input_acceptance",() -> typed('x'),true);
            edt(() -> {editor.getCurrentBuffer().getUndoManager().undo();return null;});
            var input=row("input_accepted");input.put("epoch_ms",System.currentTimeMillis());out("MD_META ",input);
        }
        await(() -> !editor.stagedStartupPending,30);
        edt(() -> {editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect());
            editor.showMessage("Markdown benchmark: stable viewport"); if(editor.messageResetTimer!=null)editor.messageResetTimer.stop();
            editor.setWrap(wrap);editor.validate();return null;});
        var full=row("full_ui_forced_paint"); full.put("epoch_ms",System.currentTimeMillis());
        full.put("java_process_probe_entry_epoch_ms",PROCESS_START);full.put("display_transform",edt(() -> editor.getGraphicsConfiguration().getDefaultTransform().toString()));
        full.put("editor_font",edt(() -> editor.writingArea.getFont().toString()));
        full.put("viewport",edt(() -> editor.getActivePane().getScrollPane().getViewport().getExtentSize().toString()));
        full.put("lsp_clients",edt(() -> editor.lspClients.size()));full.put("syntax_enabled",edt(() -> editor.getCurrentBuffer().isSyntaxHighlightingEnabled()));out("MD_META ",full);
        memory("full_ui_no_forced_gc");
    }
    private static void typed(char c) {
        KeyEvent key=new KeyEvent(editor.writingArea,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,
            c=='\n'?KeyEvent.VK_ENTER:KeyEvent.getExtendedKeyCodeForChar(c),c);
        editor.keyPressed(key);
        if(!key.isConsumed()) {
            String action=c=='\n'?DefaultEditorKit.insertBreakAction:DefaultEditorKit.defaultKeyTypedAction;
            editor.writingArea.getActionMap().get(action).actionPerformed(new ActionEvent(editor.writingArea,ActionEvent.ACTION_PERFORMED,String.valueOf(c)));
        }
    }
    private static void uiText(String name, Work work, boolean measured) throws Exception {
        long[] before=edt(() -> metric("edit.input-to-paint")); long posted=System.nanoTime();
        Map<String,Object> value=edt(() -> {
            long entered=System.nanoTime();
            editor.editorUiController.noteEditorKey(new KeyEvent(editor.writingArea,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_X,'x'));
            var v=timed(name,work);v.put("queue_ms",(entered-posted)/1e6);return v;
        });
        try {
            await(() -> metric("edit.input-to-paint")[0]>before[0],5);
            edt(() -> {value.put("input_to_real_paint_ms",metric("edit.input-to-paint")[1]/1e6);
                value.put("paint_duration_ms",metric("edit.paint-duration")[1]/1e6);
                value.put("document_to_paint_start_ms",metric("edit.document-to-paint-start")[1]/1e6);return null;});
        } catch(TimeoutException noPaint) {value.put("paint_state","UNAVAILABLE: no attributed paint within 5s");}
        if(measured)out("MD_SAMPLE ",value);
    }
    private static void uiOther(String name, Work work, boolean measured) throws Exception {
        var v=edt(() -> {
            var r=timed(name,work);
            long start=System.nanoTime();editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());
            r.put("forced_visible_paint_ms",(System.nanoTime()-start)/1e6); return r;
        });if(measured)out("MD_SAMPLE ",v);
    }
    private static void ui() throws Exception {
        open(); FileBuffer buffer=edt(editor::getCurrentBuffer);
        if(buffer.isLargeFile()){var r=row("ui_edit");r.put("state","UNSUPPORTED");out("MD_SAMPLE ",r);return;}
        String original=hash(file);
        if(withPreview) {
            out("MD_SAMPLE ",edt(() -> timed("preview_open_end_to_end_edt",() -> editor.commandHandler.execute("markdown preview"))));
            edt(() -> {editor.validate();return null;});memory("preview_open");
        }
        for(int i=-warmups;i<samples;i++) {
            final int position=Math.floorMod(i,3); boolean measured=i>=0;
            int offset=edt(() -> {
                int length=editor.writingArea.getDocument().getLength();int at=position==0?16:position==1?length/2:Math.max(0,length-16);
                editor.writingArea.setCaretPosition(Math.min(at,length));
                var rect=editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition());
                if(rect!=null)editor.writingArea.scrollRectToVisible(rect.getBounds());return editor.writingArea.getCaretPosition();
            });
            uiText("insert_"+position,() -> typed('x'),measured);
            uiText("undo_"+position,() -> buffer.getUndoManager().undo(),measured);
            uiText("redo_"+position,() -> buffer.getUndoManager().redo(),measured);
            uiText("delete_"+position,() -> editor.writingArea.replaceRange("",offset,offset+1),measured);
            edt(() -> {editor.writingArea.setCaretPosition(offset);return null;});
            uiText("newline_"+position,() -> typed('\n'),measured);
            uiText("undo_newline_"+position,() -> buffer.getUndoManager().undo(),measured);
            if(edt(() -> editor.writingArea.getDocument().getLength())!=Files.size(file))throw new AssertionError("Edit cycle changed length");
        }
        for(int i=-warmups;i<samples;i++) {
            final int n=Math.floorMod(i,3);boolean measured=i>=0;
            uiOther("caret_distant",() -> editor.writingArea.setCaretPosition(n==0?0:n==1?editor.writingArea.getDocument().getLength()/2:editor.writingArea.getDocument().getLength()),measured);
            uiOther("selection",() -> {int p=editor.writingArea.getCaretPosition();editor.writingArea.select(p,Math.min(p+32,editor.writingArea.getDocument().getLength()));},measured);
            uiOther("scroll",() -> {var b=editor.getActivePane().getScrollPane().getVerticalScrollBar();b.setValue(n*(b.getMaximum()-b.getVisibleAmount())/2);},measured);
        }
        int[] longest=edt(() -> {var root=buffer.getDocument().getDefaultRootElement();int a=0,b=0;
            for(int i=0;i<root.getElementCount();i++){var e=root.getElement(i);int end=Math.min(e.getEndOffset(),buffer.getDocument().getLength());if(end-e.getStartOffset()>b-a){a=e.getStartOffset();b=end;}}
            return new int[]{a,b};});
        for(int i=0;i<Math.min(samples,10);i++) {
            edt(() -> {editor.writingArea.setCaretPosition(longest[0]);return null;});
            uiText("delete_longest_line",() -> editor.writingArea.replaceRange("",longest[0],longest[1]),true);
            uiText("undo_longest_line",() -> buffer.getUndoManager().undo(),true);
        }
        uiOther("heading_navigation",() -> editor.navigateHeading(false),true);
        out("MD_SAMPLE ",edt(() -> timed("split_create",() -> editor.splitWindow(true))));
        for(int i=0;i<Math.min(samples,20);i++){final int n=i;uiOther("split_switch",() -> editor.activateEditorPane(editor.editorPanes.get(n%2)),true);}
        if(!original.equals(edt(() -> documentHash(buffer.getDocument()))))throw new AssertionError("Source bytes changed");
        memory("after_edits");
        if(withPreview){edt(() -> {editor.disposeMarkdownPreviews();return null;});memory("after_preview_dispose_without_gc");}
        for(int position=0;position<3;position++) {
            final int p=position;
            edt(() -> {
                int length=editor.writingArea.getDocument().getLength();
                editor.writingArea.setCaretPosition(p==0?0:p==1?length/2:Math.max(0,length-1));
                var bounds=editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition());
                if(bounds!=null)editor.writingArea.scrollRectToVisible(bounds.getBounds());
                editor.validate();return null;
            });
            long began=System.nanoTime();
            long[] request=edt(() -> {editor.applySyntaxHighlighting();return new long[]{(long)field(editor.syntaxUiController,"syntaxGeneration"),(int)field(editor.syntaxUiController,"syntaxJobId")};});
            await(() -> {var job=editor.asyncJobService.get((int)request[1]);return job!=null && job.getStatus()!=AsyncJobService.Status.RUNNING && (int)field(editor.syntaxUiController,"syntaxJobId")!=(int)request[1];},10);
            var v=row("production_syntax_"+p);v.put("convergence_observed_ms",(System.nanoTime()-began)/1e6);
            edt(() -> {var job=editor.asyncJobService.get((int)request[1]);
                v.put("request_status",job.getStatus().toString());v.put("generation",request[0]);v.put("current_generation",field(editor.syntaxUiController,"syntaxGeneration"));
                if(job.getStatus()!=AsyncJobService.Status.SUCCEEDED || request[0]!=(long)field(editor.syntaxUiController,"syntaxGeneration"))v.put("state","STALE_OR_CANCELLED");
                v.put("worker_ms",metric("syntax.highlight")[1]/1e6);v.put("perf_report",editor.perfService.report());
                v.put("foreground_spans",editor.syntaxForegroundSpans.size());return null;});out("MD_SAMPLE ",v);
        }
        edt(() -> {editor.disposeMarkdownPreviews(); editor.buffers.add(FileBuffer.createPlainScratch("[benchmark remainder]","")); editor.commandHandler.execute("bd!");return null;});memory("after_document_close_without_gc");
    }
    private static void model() throws Exception {
        FileBuffer[] box=new FileBuffer[1];out("MD_SAMPLE ",timed("file_open_worker_no_ui",() -> box[0]=new FileBuffer(file.toFile())));
        FileBuffer buffer=box[0];memory("loaded_no_ui");
        out("MD_META ",Map.of("large_file",buffer.isLargeFile(),"lines",buffer.getLineCount(),"bytes",Files.size(file)));
        if(buffer.isLargeFile()) {
            var unsupported=row("editing");unsupported.put("state","UNSUPPORTED");out("MD_SAMPLE ",unsupported);
            LargeFileProjection projection=new LargeFileProjection(buffer);projection.render(40);
            for(int i=-warmups;i<samples;i++){var v=timed("projection_move_not_rendered_scroll",() -> {if(!projection.moveForward(40))projection.moveBackward(40);});if(i>=0)out("MD_SAMPLE ",v);}
            out("MD_SAMPLE ",timed("clean_large_noop_save",buffer::save));
            LargeFileStore store=(LargeFileStore)field(buffer,"largeFileStore");Path target=file.resolveSibling("streamed.md");
            out("MD_SAMPLE ",timed("genuine_streamed_atomic_copy",() -> AtomicFileWriter.writeStream(target,store::writeTo)));
            if(Files.mismatch(file,target)!=-1)throw new AssertionError("Streamed content mismatch");
            memory("after_streamed_copy");return;
        }
        edt(() -> {buffer.getDocument().addDocumentListener(new DocumentListener(){
            private void update(DocumentEvent event){buffer.applyDocumentChange(event);buffer.setModified(true);}
            public void insertUpdate(DocumentEvent e){update(e);}public void removeUpdate(DocumentEvent e){update(e);}public void changedUpdate(DocumentEvent e){update(e);}
        });return null;});
        String original=hash(file);VersionedTextSnapshot snapshot=buffer.textSnapshot();
        for(int i=-warmups;i<samples;i++) {
            final int n=Math.floorMod(i,3);final int at=n==0?0:n==1?snapshot.length()/2:Math.max(0,snapshot.length()-1);
            var v=timed("snapshot_model_replace_"+n,() -> sink=snapshot.replace(at,0,"x"));if(i>=0)out("MD_SAMPLE ",v);
            var edit=edt(() -> timed("plain_document_snapshot_listener_insert_remove_"+n,() -> {buffer.getDocument().insertString(at,"x",null);buffer.getDocument().remove(at,1);}));if(i>=0)out("MD_SAMPLE ",edit);
        }
        if(!original.equals(edt(() -> documentHash(buffer.getDocument()))))throw new AssertionError("Model edits changed source");
        modelGrammarAndSearch(buffer);
        sink=null; // Do not retain a full replacement result into the independent save measurement.
        edt(() -> {buffer.getDocument().insertString(buffer.getDocument().getLength(),"\n# saved\n",null);return null;});
        String expected=edt(() -> documentHash(buffer.getDocument()));out("MD_SAMPLE ",timed("dirty_save_real_write",buffer::save));
        if(!expected.equals(hash(file)))throw new AssertionError("Saved content mismatch");memory("after_model_work");
    }
    private static void modelGrammarAndSearch(FileBuffer buffer) throws Exception {
        String text=buffer.getContent();GrammarHighlightService grammar=new GrammarHighlightService();
        for(int p=0;p<3;p++)for(int i=-Math.min(warmups,2);i<Math.min(samples,5);i++){
            final int start=p==0?0:p==1?Math.max(0,text.length()/2-4096):Math.max(0,text.length()-8192);
            var v=timed("lexer_viewport_isolated_"+p,() -> sink=grammar.highlightViewport(text.substring(0,Math.min(text.length(),start+8192)),FileType.MARKDOWN,start,Math.min(text.length(),start+8192)));
            v.put("tokens",((List<?>)sink).size());v.put("prefix_chars",Math.min(text.length(),start+8192));if(i>=0)out("MD_SAMPLE ",v);
        }
        for(int i=0;i<Math.min(samples,5);i++) {
            out("MD_SAMPLE ",timed("literal_scan_model",() -> sink=SearchPattern.literal("alpha").scan(text,200000,() -> false)));
            out("MD_SAMPLE ",timed("regex_many_matches_model",() -> sink=new SearchPattern("[a-z]+",true,true,false).scan(text,200000,() -> false)));
            out("MD_SAMPLE ",timed("replace_all_compute_only",() -> sink=SearchPattern.literal("alpha").replaceAll(text,"ALPHA",() -> false)));
        }
        final int[] reads={0};out("MD_SAMPLE ",timed("search_cooperative_cancellation",() -> {try{new SearchPattern("[a-z]+",true,true,false).scan(text,200000,() -> ++reads[0]>20);}catch(CancellationException expected){return;}throw new AssertionError("Did not cancel");}));
    }
    private static void assets() throws Exception {
        memory("before_assets");
        try(MarkdownPreviewAssets assets=new MarkdownPreviewAssets()) {
            for(String kind:List.of("math","mermaid")) {
                for(int i=0;i<Math.min(samples,6);i++) {
                    final int n=i;String name=kind+"_"+(i==0?"first_use":i<=3?"identical_cached":"changed");
                    Object[] error={null};
                    var value=edt(() -> timed(name,() -> {
                        try{sink=kind.equals("math")?assets.renderMath("x^2 + "+(n<=3?1:n),false,Color.BLACK):assets.renderMermaid("flowchart LR\n A --> B"+(n<=3?1:n));}
                        catch(Exception exception){error[0]=exception.toString();}
                    }));
                    if(error[0]!=null){value.put("state","ERROR");value.put("error",error[0]);failed=true;}
                    out("MD_SAMPLE ",value);
                    memory(name);
                }
                var v=edt(() -> timed(kind+"_invalid",() -> {try{sink=kind.equals("math")?assets.renderMath("\\notARealCommand{",true,Color.BLACK):assets.renderMermaid("not a valid diagram");}catch(Exception expected){sink=expected.getClass().getName();}}));v.put("outcome",String.valueOf(sink));out("MD_SAMPLE ",v);
            }
        }memory("after_assets_close_without_gc");
    }
    private static void preview() throws Exception {
        open();memory("before_preview");
        for(int i=0;i<Math.min(samples,3);i++) {
            out("MD_SAMPLE ",edt(() -> timed("preview_open_end_to_end_edt",() -> editor.commandHandler.execute("markdown preview"))));
            out("MD_SAMPLE ",edt(() -> timed("preview_layout_and_forced_paint",() -> {editor.validate();var pane=editor.editorPanes.stream().filter(EditorPane::isMarkdownPreview).findFirst().orElseThrow();var component=(JComponent)pane.getComponent();component.paintImmediately(component.getVisibleRect());})));
            var previewPane=edt(() -> (JEditorPane)((JScrollPane)((MarkdownPreviewPane)editor.editorPanes.stream().filter(EditorPane::isMarkdownPreview).findFirst().orElseThrow().getComponent()).getComponent(1)).getViewport().getView());
            long imageStart=System.nanoTime();await(() -> imagesReady(previewPane),5);
            var image=row("preview_images_ready");image.put("wall_ms",(System.nanoTime()-imageStart)/1e6);out("MD_SAMPLE ",image);
            String htmlText=edt(() -> ((MarkdownPreviewPane)editor.editorPanes.stream().filter(EditorPane::isMarkdownPreview).findFirst().orElseThrow().getComponent()).getHtml());
            out("MD_META ",Map.of("mermaid_error_fallback",htmlText.contains("Mermaid preview error"),"html_characters",htmlText.length()));
            memory("preview_active");
            for(int j=0;j<Math.min(samples,5);j++) out("MD_SAMPLE ",edt(() -> timed("preview_refresh_end_to_end_edt",() -> editor.commandHandler.execute("markdown refresh"))));
            out("MD_SAMPLE ",edt(() -> timed("preview_close_edt",() -> editor.commandHandler.execute("markdown close"))));memory("preview_closed_no_forced_gc");
        }
        // Warm, non-additive replay of actual production stages; excludes HTML installation.
        try(MarkdownPreviewAssets assets=new MarkdownPreviewAssets()) {
            String source=edt(() -> editor.getCurrentBuffer().getFullContent());
            Object[] prepared={source},tree={null},html={null};
            out("MD_SAMPLE ",edt(() -> timed("stage_replay_preprocessing",() -> prepared[0]=invoke(MarkdownPreviewRenderer.class,"prepare",new Class<?>[]{String.class,MarkdownPreviewAssets.class,Color.class},source,assets,Color.BLACK))));
            var parser=(org.commonmark.parser.Parser)field(MarkdownPreviewRenderer.class,"PARSER");
            var renderer=(org.commonmark.renderer.html.HtmlRenderer)field(MarkdownPreviewRenderer.class,"HTML");
            var policy=(org.owasp.html.PolicyFactory)field(MarkdownPreviewRenderer.class,"RAW_HTML_POLICY");
            out("MD_SAMPLE ",edt(() -> timed("stage_replay_parse",() -> tree[0]=parser.parse((String)prepared[0]))));
            out("MD_SAMPLE ",edt(() -> timed("stage_replay_html",() -> html[0]=renderer.render((org.commonmark.node.Node)tree[0]))));
            out("MD_SAMPLE ",edt(() -> timed("stage_replay_sanitize",() -> html[0]=policy.sanitize((String)html[0]))));
            out("MD_SAMPLE ",edt(() -> timed("stage_replay_asset_resolution",() -> sink=invoke(MarkdownPreviewRenderer.class,"restrictImageSources",new Class<?>[]{String.class,java.io.File.class,MarkdownPreviewAssets.class},html[0],file.toFile(),assets))));
            JEditorPane replay=edt(() -> {JEditorPane p=new JEditorPane();p.setContentType("text/html");return p;});
            out("MD_SAMPLE ",edt(() -> timed("stage_replay_setText_offscreen",() -> replay.setText((String)html[0]))));
            out("MD_SAMPLE ",edt(() -> timed("stage_replay_preferred_size_layout_offscreen",() -> {replay.setSize(600,600);sink=replay.getPreferredSize();})));
        }
    }
    private static String iLabel(String name,int i){return name+"_"+i;}
    private static void search() throws Exception {
        open();FileBuffer buffer=edt(editor::getCurrentBuffer);String original=hash(file);
        if(buffer.isLargeFile()){var v=row("find_bar");v.put("state","UNSUPPORTED");out("MD_SAMPLE ",v);return;}
        for(SearchPattern pattern:List.of(SearchPattern.literal("alpha"),new SearchPattern("[a-z]+",true,true,false))) {
            for(int i=0;i<Math.min(samples,5);i++) {
                long began=System.nanoTime();edt(() -> {editor.inlineFindBar.showFind(pattern);return null;});
                await(() -> {Object scan=field(editor.inlineFindBar,"scan");return scan!=null && pattern.equals(field(scan,"pattern"));},10);
                var v=row(pattern.regex()?"find_bar_regex_convergence":"find_bar_literal_convergence");
                v.put("wall_ms",(System.nanoTime()-began)/1e6);
                v.put("matches",edt(() -> ((List<?>)field(field(editor.inlineFindBar,"scan"),"ranges")).size()));out("MD_SAMPLE ",v);
                // Invalidate this result so the next repetition cannot accept the previous scan.
                edt(() -> {editor.inlineFindBar.showFind("no-such-benchmark-query");return null;});
            }
        }
        edt(() -> {editor.inlineFindBar.showReplace("alpha");((JTextField)field(editor.inlineFindBar,"replacement")).setText("ALPHA");return null;});
        await(() -> {Object scan=field(editor.inlineFindBar,"scan");return scan!=null && ((SearchPattern)field(scan,"pattern")).query().equals("alpha");},10);
        long version=edt(() -> buffer.textSnapshot().version());long began=System.nanoTime();
        edt(() -> {Method m=InlineFindBar.class.getDeclaredMethod("replaceAll");m.setAccessible(true);m.invoke(editor.inlineFindBar);return null;});
        await(() -> buffer.textSnapshot().version()>version,15);
        var replacement=row("find_bar_replace_all_install_convergence");replacement.put("wall_ms",(System.nanoTime()-began)/1e6);out("MD_SAMPLE ",replacement);
        String changed=edt(() -> buffer.getContent());if(changed.contains("alpha"))throw new AssertionError("Replace All incomplete");
        edt(() -> {buffer.getUndoManager().undo();return null;});
        if(!original.equals(edt(() -> documentHash(buffer.getDocument()))))throw new AssertionError("Replace All undo mismatch");
        memory("before_file_switches");
        Path second=file.resolveSibling("second.md");Files.copy(file,second);FileBuffer other=new FileBuffer(second.toFile());
        edt(() -> {editor.buffers.add(other);return null;});
        for(int i=0;i<20;i++){final int n=i;out("MD_SAMPLE ",edt(() -> timed("file_buffer_switch",() -> editor.loadBufferIntoEditor(n%2==0?other:buffer))));}
        memory("after_20_switches_no_forced_gc");
        edt(() -> {editor.buffers.add(FileBuffer.createPlainScratch("[benchmark remainder]",""));editor.commandHandler.execute("bd!");editor.commandHandler.execute("bd!");return null;});memory("after_document_closes_no_forced_gc");
    }
    private static void syntax() throws Exception {
        open();
        for(int position=0;position<3;position++) for(int repeat=0;repeat<3;repeat++) {
            final int p=position;long before=edt(() -> (long)field(editor.syntaxUiController,"syntaxGeneration"));
            long began=System.nanoTime();
            edt(() -> {
                int length=editor.writingArea.getDocument().getLength();
                editor.writingArea.setCaretPosition(p==0?0:p==1?length/2:Math.max(0,length-1));
                var bounds=editor.writingArea.modelToView2D(editor.writingArea.getCaretPosition());
                if(bounds!=null)editor.writingArea.scrollRectToVisible(bounds.getBounds());
                editor.validate();editor.scheduleSyntaxHighlighting();return null;
            });
            await(() -> {
                var job=editor.asyncJobService.list().stream().filter(j -> j.getDescription().equals("Syntax highlighting")).findFirst().orElse(null);
                return (long)field(editor.syntaxUiController,"syntaxGeneration")>before
                    && (int)field(editor.syntaxUiController,"syntaxJobId")==-1
                    && job!=null && job.getStatus()==AsyncJobService.Status.SUCCEEDED;
            },15);
            var v=row("production_viewport_highlight_"+p);v.put("debounce_and_convergence_ms",(System.nanoTime()-began)/1e6);
            edt(() -> {v.put("worker_ms",metric("syntax.highlight")[1]/1e6);v.put("foreground_spans",editor.syntaxForegroundSpans.size());
                v.put("perf_report",editor.perfService.report());v.put("generation",field(editor.syntaxUiController,"syntaxGeneration"));return null;});out("MD_SAMPLE ",v);
        }
    }
    private static boolean imagesReady(JEditorPane pane) {
        return imagesReady(pane.getUI().getRootView(pane));
    }
    private static boolean imagesReady(View view) {
        if(view instanceof javax.swing.text.html.ImageView image) {
            Image value=image.getImage();if(value!=null && (Toolkit.getDefaultToolkit().checkImage(value,-1,-1,null)&java.awt.image.ImageObserver.ALLBITS)==0)return false;
        }
        for(int i=0;i<view.getViewCount();i++)if(!imagesReady(view.getView(i)))return false;return true;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("mode disposable.md samples warmups wrap preview");
        file=Path.of(args[1]).toRealPath();samples=Integer.parseInt(args[2]);warmups=Integer.parseInt(args[3]);empty=args[0].equals("startup-empty");wrap=Boolean.parseBoolean(args[4]);withPreview=Boolean.parseBoolean(args[5]);
        try {
            switch(args[0]){case "model"->model();case "ui"->ui();case "startup", "startup-empty"->open();case "assets"->assets();case "preview"->preview();case "search"->search();case "syntax"->syntax();default->throw new IllegalArgumentException("Unknown mode");}
            if(editor!=null)out("MD_META ",edt(() -> Map.of("perf",editor.perfService.report(),"jobs",editor.asyncJobService.list().stream().map(j -> j.getDescription()+":"+j.getStatus()).toList())));
        } finally {
            if(editor!=null)edt(() -> {editor.disposeMarkdownPreviews();editor.shutdownSyntaxUi();editor.shutdownLspScheduling();editor.asyncJobService.shutdownNow();if(editor.backupScheduler!=null)editor.backupScheduler.close();if(editor.externalChangeTimer!=null)editor.externalChangeTimer.stop();if(editor.recoverySnapshotTimer!=null)editor.recoverySnapshotTimer.stop();if(editor.messageResetTimer!=null)editor.messageResetTimer.stop();editor.fileWatcherService.stop();editor.dispose();return null;});
        }
        System.out.println("MD_DONE");System.out.flush();
        System.exit(failed?2:0);
    }
}
