package shed;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;

/** Isolated opt-in experiments; no production view factory, cache, worker or setting is changed. */
public final class SwingHtmlFeasibilityProbe {
    static Texteditor editor;
    static EditorPane sourcePane;
    static FileBuffer buffer;
    static JScrollPane live;
    static Font font;
    static Color background, foreground;
    static int actions, sequence;
    static String original;
    static String phase="startup";
    static String dispatchTag;
    static final class ResourceGuard extends RuntimeException { }
    static volatile Throwable asynchronousFailure;
    static final Map<String,Double> dispatchMax=new LinkedHashMap<>();
    static final class DispatchQueue extends EventQueue {
        @Override protected void dispatchEvent(AWTEvent event) {
            long start=System.nanoTime();dispatchTag=null;
            try {super.dispatchEvent(event);} finally {
                double ms=(System.nanoTime()-start)/1e6;String label=dispatchTag==null?"ambient.after."+phase:dispatchTag;
                dispatchMax.merge(label,ms,Math::max);
                if(ms>=16)emit("HT_SAMPLE ",Map.of("operation","dispatch_event","state","OK","wall_ms",ms,"phase",label,"event",event.getClass().getName()));
            }
        }
    }
    static final com.sun.management.ThreadMXBean THREADS=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static <T>T edt(Callable<T> work)throws Exception {
        if(SwingUtilities.isEventDispatchThread())return work.call();
        FutureTask<T> result=new FutureTask<>(work);SwingUtilities.invokeAndWait(result);return result.get();
    }
    static void await(Callable<Boolean> ready)throws Exception {
        long deadline=System.nanoTime()+30_000_000_000L;
        while(!edt(ready)){if(System.nanoTime()>deadline)throw new TimeoutException("State did not settle");Thread.sleep(10);}
    }
    static void emit(String prefix,Map<String,?> values){System.out.println(prefix+MiniJson.stringify(values));System.out.flush();}
    static void meta(String name,Object... pairs){Map<String,Object> m=new LinkedHashMap<>();m.put("operation",name);for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);emit("HT_META ",m);}
    interface Work {void run()throws Exception;}
    static double timed(String name,Work work)throws Exception {
        long allocated=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()),cpu=THREADS.getCurrentThreadCpuTime(),start=System.nanoTime();
        work.run();double ms=(System.nanoTime()-start)/1e6;
        emit("HT_SAMPLE ",Map.of("operation",name,"state","OK","wall_ms",ms,"thread_cpu_ms",(THREADS.getCurrentThreadCpuTime()-cpu)/1e6,"thread_allocated_bytes",Math.max(0,THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated),"thread",Thread.currentThread().getName()));return ms;
    }
    static void sourcePaint(){sourcePane.getTextArea().paintImmediately(sourcePane.getTextArea().getVisibleRect());}
    static void sourceAction(String phase,long posted,boolean mutate)throws Exception {
        phase="input."+phase;SwingHtmlFeasibilityProbe.phase=phase;dispatchTag=phase;long dispatch=System.nanoTime();int at=Math.min(16,buffer.getDocument().getLength());
        double insert=0;
        if(mutate){
            long begin=System.nanoTime();buffer.getDocument().insertString(at,"x",null);sourcePaint();insert=(System.nanoTime()-begin)/1e6;
            timed(phase+".source_undo",()->{buffer.getUndoManager().undo();sourcePaint();});
            timed(phase+".source_redo",()->{buffer.getUndoManager().redo();sourcePaint();});
            buffer.getUndoManager().undo();buffer.setModified(false);
        }
        int n=sequence++;timed(phase+".source_caret",()->{sourcePane.getTextArea().setCaretPosition((n&1)==0?0:buffer.getDocument().getLength());sourcePaint();});
        timed(phase+".source_scroll",()->{JScrollBar bar=sourcePane.getScrollPane().getVerticalScrollBar();bar.setValue((n&1)==0?0:bar.getMaximum()-bar.getVisibleAmount());sourcePaint();});
        if(mutate && n%10==0)timed(phase+".source_burst",()->{for(int i=0;i<5;i++)buffer.getDocument().insertString(at,"z",null);for(int i=0;i<5;i++)buffer.getUndoManager().undo();buffer.setModified(false);sourcePaint();});
        emit("HT_SAMPLE ",Map.of("operation",phase+(mutate?".source_dispatch":".navigation_dispatch"),"state","OK","queue_ms",(dispatch-posted)/1e6,"insertion_and_paint_ms",insert,"posted_to_insert_paint_ms",(dispatch-posted)/1e6+insert,"mutated",mutate));
    }
    static double task(String name,Work work,boolean mutate)throws Exception {
        double ms=edt(()->{
            phase=name;dispatchTag=name;long posted=System.nanoTime();SwingUtilities.invokeLater(()->{try{sourceAction(name,posted,mutate);}catch(Exception e){asynchronousFailure=e;}});
            long revision=buffer.documentRevision();VersionedTextSnapshot snapshot=buffer.textSnapshot();boolean dirty=buffer.isModified(),undo=buffer.getUndoManager().canUndo(),redo=buffer.getUndoManager().canRedo();String saved=buffer.getSavedContent();
            double elapsed=timed(name+".edt_task",work);
            if(buffer.documentRevision()!=revision||buffer.textSnapshot()!=snapshot||buffer.isModified()!=dirty||buffer.getSavedContent()!=saved||buffer.getUndoManager().canUndo()!=undo||buffer.getUndoManager().canRedo()!=redo)
                throw new AssertionError("Preview task changed authoritative source/history: "+name);
            return elapsed;
        });
        edt(()->null);if(asynchronousFailure!=null)throw new AssertionError(asynchronousFailure);return ms; // Explicit barrier after the source callback, not an arbitrary sleep.
    }
    static HTMLDocument read(String html)throws Exception {
        var pre=java.util.regex.Pattern.compile("(?s)<pre[^>]*>(.*?)</pre>").matcher(html);
        while(pre.find())if(pre.group(1).length()>131072 && pre.group(1).chars().filter(c->c=='\n').count()>=1000){
            meta("resource_guard","reason","Known HTMLReader preformatted-text array-copy amplification; do not repeat the observed 512 MiB OOM","pre_chars",pre.group(1).length());throw new ResourceGuard();
        }
        HTMLEditorKit kit=new HTMLEditorKit();HTMLDocument document=(HTMLDocument)kit.createDefaultDocument();
        timed("worker.html_read",()->kit.read(new StringReader(html),document,0));return document;
    }
    static JEditorPane pane() {
        JEditorPane p=new JEditorPane();p.setContentType("text/html");p.setEditable(false);
        p.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,Boolean.TRUE);p.setFont(font);p.setBackground(background);p.setForeground(foreground);return p;
    }
    static JScrollPane shell(JEditorPane pane) {
        JScrollPane scroll=new JScrollPane(pane);scroll.setSize(live.getSize());return scroll;
    }
    static void layout(JEditorPane p,JScrollPane scroll) {
        scroll.doLayout();
        // Do not repeatedly force a wide view back to viewport width: that manufactures reflows.
        // Use the same extent/preferred/Scrollable negotiation as the containing viewport.
        for(int pass=0;pass<3;pass++) {
            Dimension extent=scroll.getViewport().getExtentSize();
            if(p.getWidth()==0)p.setSize(Math.max(1,extent.width),Math.max(1,extent.height));
            Dimension preferred=p.getPreferredSize();
            Dimension wanted=new Dimension(p.getScrollableTracksViewportWidth()?extent.width:Math.max(extent.width,preferred.width),Math.max(extent.height,preferred.height));
            boolean stable=p.getSize().equals(wanted);p.setSize(wanted);scroll.doLayout();if(stable)break;
        }
    }
    static final BufferedImage paintBuffer=new BufferedImage(600,400,BufferedImage.TYPE_INT_ARGB);
    static void paint(JEditorPane pane) {
        Graphics2D graphics=paintBuffer.createGraphics();
        try {graphics.setClip(0,0,600,400);pane.paint(graphics);}finally{graphics.dispose();}
    }
    static int[] pixels(JEditorPane p,int y) {
        BufferedImage image=new BufferedImage(600,400,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        try{g.translate(0,-y);g.setClip(0,y,600,400);p.paint(g);}finally{g.dispose();}
        return image.getRGB(0,0,600,400,null,0,600);
    }
    static boolean imagesReady(View view) {
        if(view instanceof ImageView image){Image value=image.getImage();if(value==null||(Toolkit.getDefaultToolkit().checkImage(value,-1,-1,null)&java.awt.image.ImageObserver.ALLBITS)==0)return false;}
        for(int i=0;i<view.getViewCount();i++)if(!imagesReady(view.getView(i)))return false;return true;
    }
    static int imageOffset(JEditorPane pane)throws BadLocationException {
        ElementIterator it=new ElementIterator(pane.getDocument());Element e;
        while((e=it.next())!=null){Object src=e.getAttributes().getAttribute(HTML.Attribute.SRC);if(src!=null&&src.toString().contains("swatch.png"))return (int)pane.modelToView2D(e.getStartOffset()).getY();}
        return 0;
    }
    static Set<View> views(View v){Set<View> found=Collections.newSetFromMap(new IdentityHashMap<>());collect(v,found);return found;}
    static void collect(View v,Set<View> set){set.add(v);for(int i=0;i<v.getViewCount();i++)collect(v.getView(i),set);}
    static String text(JEditorPane p)throws Exception{return p.getDocument().getText(0,p.getDocument().getLength());}
    static List<String> links(JEditorPane p) {
        List<String> found=new ArrayList<>();ElementIterator iterator=new ElementIterator(p.getDocument());Element e;
        while((e=iterator.next())!=null){AttributeSet a=e.getAttributes();Object id=a.getAttribute(HTML.Attribute.ID),src=a.getAttribute(HTML.Attribute.SRC),link=a.getAttribute(HTML.Tag.A);
            if(id!=null||src!=null||link!=null)found.add(e.getStartOffset()+"/"+id+"/"+src+"/"+link);}
        return found;
    }
    static boolean equivalent(String label,JEditorPane reference,JEditorPane candidate)throws Exception {
        boolean sameText=text(reference).equals(text(candidate)),sameSize=reference.getPreferredSize().equals(candidate.getPreferredSize()),sameLinks=links(reference).equals(links(candidate)),samePixels=true,samePositions=true;
        int length=reference.getDocument().getLength();
        if(sameText)for(int pos:new int[]{0,length/3,length/2,Math.max(0,length-1)}){
            var expected=reference.modelToView2D(pos);var actual=candidate.modelToView2D(pos);
            samePositions &= Objects.equals(expected,actual);
            if(expected!=null)samePositions &= reference.viewToModel2D(new Point((int)expected.getX()+1,(int)expected.getY()+1))==candidate.viewToModel2D(new Point((int)expected.getX()+1,(int)expected.getY()+1));
        }
        if(sameSize)for(int y:new int[]{0,reference.getHeight()/2,Math.max(0,reference.getHeight()-400)})samePixels &= Arrays.equals(pixels(reference,y),pixels(candidate,y));else samePixels=false;
        meta(label,"text_equal",sameText,"preferred_size_equal",sameSize,"links_ids_images_equal",sameLinks,"positions_equal",samePositions,"pixels_equal",samePixels,"reference_size",reference.getPreferredSize().toString(),"candidate_size",candidate.getPreferredSize().toString());
        return sameText&&sameSize&&sameLinks&&samePixels&&samePositions;
    }
    static Element body(Document document){ElementIterator i=new ElementIterator(document);Element e;while((e=i.next())!=null)if(e.getAttributes().getAttribute(StyleConstants.NameAttribute)==HTML.Tag.BODY)return e;throw new IllegalStateException("No body");}
    static List<String> blocks(String body)throws Exception {
        List<String> blocks=new ArrayList<>();int[] depth={0},last={0};
        new ParserDelegator().parse(new StringReader(body),new HTMLEditorKit.ParserCallback(){
            boolean wrapper(HTML.Tag t,MutableAttributeSet a){return (t==HTML.Tag.HTML||t==HTML.Tag.HEAD||t==HTML.Tag.BODY)||a.isDefined(IMPLIED);}
            void end(int pos){int end=body.indexOf('>',pos);if(end<0)throw new IllegalStateException("No closing tag");blocks.add(body.substring(last[0],end+1));last[0]=end+1;}
            @Override public void handleStartTag(HTML.Tag t,MutableAttributeSet a,int pos){if(!wrapper(t,a))depth[0]++;}
            @Override public void handleEndTag(HTML.Tag t,int pos){if(t==HTML.Tag.HTML||t==HTML.Tag.HEAD||t==HTML.Tag.BODY)return;if(depth[0]>0&&--depth[0]==0)end(pos);}
            @Override public void handleSimpleTag(HTML.Tag t,MutableAttributeSet a,int pos){
                if(wrapper(t,a))return;
                if(t instanceof HTML.UnknownTag){if(Boolean.TRUE.equals(a.getAttribute(HTML.Attribute.ENDTAG))){if(depth[0]>0&&--depth[0]==0)end(pos);}else depth[0]++;}
                else if(depth[0]==0)end(pos);
            }
        },true);
        if(depth[0]!=0)throw new IllegalStateException("Unsupported body nesting");
        if(last[0]<body.length()){if(blocks.isEmpty())blocks.add(body);else blocks.set(blocks.size()-1,blocks.getLast()+body.substring(last[0]));}
        if(!String.join("",blocks).equals(body))throw new IllegalStateException("Fragment source changed");return blocks;
    }
    static void memory(String name,boolean gc)throws Exception {
        if(gc){edt(()->{phase="memory_gc";return null;});System.gc();Thread.sleep(100);edt(()->null);} // Outside timing; diagnostic retained-heap approximation, not a leak census.
        var m=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();meta(name,"heap_used_mib",m.getUsed()/1048576d,"heap_committed_mib",m.getCommitted()/1048576d,"threads",ManagementFactory.getThreadMXBean().getThreadCount(),"requested_gc",gc);
    }
    static void checkSource(String phase)throws Exception {if(!edt(()->buffer.textSnapshot()).text().equals(original))throw new AssertionError("Source changed: "+phase);meta(phase+".source_integrity","exact_text",true,"revision",edt(buffer::documentRevision));}
    public static void main(String[] args)throws Exception {
        Path file=Path.of(args[0]).toRealPath();actions=Integer.parseInt(args[1]);
        try {
            edt(()->{Toolkit.getDefaultToolkit().getSystemEventQueue().push(new DispatchQueue());return null;});
            editor=edt(()->new Texteditor(new String[]{file.toString()}));
            await(()->editor.getCurrentBuffer()!=null&&file.toString().equals(editor.getCurrentBuffer().getFilePath())&&!editor.stagedStartupPending);
            edt(()->{editor.showMessage("HTML feasibility: stable notification row");if(editor.messageResetTimer!=null)editor.messageResetTimer.stop();editor.validate();return null;});
            buffer=edt(editor::getCurrentBuffer);sourcePane=edt(editor::getActivePane);font=edt(editor::resolveUiFont);background=edt(editor.configManager::getNormalColor);foreground=edt(editor.configManager::getEditorForeground);
            original=edt(buffer::textSnapshot).text();
            edt(()->{editor.splitWindow(true);EditorPane other=editor.editorPanes.stream().filter(p->p!=sourcePane).findFirst().orElseThrow();live=new JScrollPane(pane());other.setAlternateComponent(live);editor.activateEditorPane(sourcePane);editor.renderWindowLayout();editor.validate();return null;});
            meta("environment","font",font.toString(),"source_font",edt(()->sourcePane.getTextArea().getFont().toString()),"display_scaling",edt(()->editor.getGraphicsConfiguration().getDefaultTransform().toString()),"notification_row_fixed",true,"viewport",edt(()->live.getViewport().getExtentSize().toString()));
            try(MarkdownPreviewAssets assets=new MarkdownPreviewAssets()) {
                String[] rendered={null};timed("worker.markdown_render",()->rendered[0]=MarkdownPreviewRenderer.render(original,"input.md",font,background,foreground,assets,file.toFile()));String html=rendered[0];
                memory("before_preview",true);
                HTMLDocument first=read(html);JEditorPane stock=edt(SwingHtmlFeasibilityProbe::pane);JScrollPane stockShell=edt(()->shell(stock));
                task("stock.install_layout",()->{timed("stock.install",()->stock.setDocument(first));timed("stock.layout",()->layout(stock,stockShell));timed("stock.paint",()->paint(stock));},true);
                task("stock.publish",()->{live.setViewportView(stock);editor.validate();layout(stock,live);stock.paintImmediately(stock.getVisibleRect());},true);
                meta("stock.geometry","size",edt(()->stock.getPreferredSize().toString()),"viewport",edt(()->live.getViewport().getExtentSize().toString()),"views",edt(()->views(stock.getUI().getRootView(stock)).size()));
                await(()->imagesReady(stock.getUI().getRootView(stock)));
                memory("full_preview",true);checkSource("stock");
                for(int i=0;i<actions;i++)task("control",()->{},true);
                for(int reload=0;reload<3;reload++) {
                    HTMLDocument replacement=read(html);
                    task("stock.same_html_replace",()->{stock.setDocument(replacement);layout(stock,live);paint(stock);},true);
                }
                Document retainedDocument=edt(stock::getDocument);
                // A: retain the installed model/view when HTML is unchanged; compare new installation.
                for(int i=0;i<actions;i++)task("A.retained",()->{timed("A.preferred",()->stock.getPreferredSize());timed("A.layout",()->layout(stock,live));timed("A.paint",()->paint(stock));},true);
                meta("A.invariants","same_document",edt(()->stock.getDocument()==retainedDocument),"full_initial_layout_avoided",false);
                // B: minimal text-only incremental change with exact attributes. Structural Markdown mapping is not implemented.
                String docText=edt(()->text(stock));String needle=docText.contains("alpha")?"alpha":docText.contains("item 0")?"item 0":"code";
                int offset=docText.indexOf(needle),htmlOffset=html.indexOf(needle,html.indexOf("<body>"));
                if(offset>=0&&htmlOffset>=0){
                    String changed=html.substring(0,htmlOffset)+"X"+html.substring(htmlOffset);HTMLDocument expected=read(changed);
                    JEditorPane reference=edt(SwingHtmlFeasibilityProbe::pane);JScrollPane refShell=edt(()->shell(reference));
                    edt(()->{reference.setDocument(expected);layout(reference,refShell);return null;});
                    Set<View> before=edt(()->views(stock.getUI().getRootView(stock)));
                    for(int i=0;i<Math.min(actions,20);i++){
                        task("B.local_insert",()->{var d=(HTMLDocument)stock.getDocument();d.insertString(offset,"X",d.getCharacterElement(offset).getAttributes());layout(stock,live);paint(stock);},true);
                        if(i==0)edt(()->{phase="verification";equivalent("B.equivalence",reference,stock);return null;});
                        task("B.local_remove",()->{stock.getDocument().remove(offset,1);layout(stock,live);paint(stock);},true);
                    }
                    meta("B.view_identity","before",before.size(),"retained",edt(()->{Set<View> after=views(stock.getUI().getRootView(stock));return before.stream().filter(after::contains).count();}));
                }else meta("B.unsupported","reason","No text-only target in this fixture");
                checkSource("B");
                // C: progressive population of an independent staging component; publish only a complete matching revision.
                int bodyStart=html.indexOf("<body>")+6,bodyEnd=html.lastIndexOf("</body>");List<String> fragments=blocks(html.substring(bodyStart,bodyEnd));
                meta("C.fragments","count",fragments.size(),"max_characters",fragments.stream().mapToInt(String::length).max().orElse(0));
                boolean stopped=false;
                for(int pass=0;pass<2;pass++){
                    long revision=edt(buffer::documentRevision);JEditorPane staging=edt(SwingHtmlFeasibilityProbe::pane);JScrollPane stageShell=edt(()->shell(staging));
                    edt(()->{staging.setText(html.substring(0,bodyStart)+"</body></html>");return null;});
                    long start=System.nanoTime();int done=0;double maximum=0;
                    for(String fragment:fragments){final boolean initial=done==0;final int step=done;
                        double ms=task("C.pass"+pass,()->{HTMLDocument d=(HTMLDocument)staging.getDocument();if(initial)d.setInnerHTML(body(d),fragment);else d.insertBeforeEnd(body(d),fragment);layout(staging,stageShell);},pass==0&&step<actions);
                        done++;maximum=Math.max(maximum,ms);
                        if(ms>100){stopped=true;break;} // A proven unbounded chunk is NO-GO; no truncated result is published.
                    }
                    boolean complete=done==fragments.size(),current=edt(buffer::documentRevision)==revision;
                    meta("C.pass"+pass+".completion","complete",complete,"current_revision",current,"blocks_done",done,"max_task_ms",maximum,"wall_ms",(System.nanoTime()-start)/1e6,"published",false,"early_stop",stopped);
                    if(complete&&current&&!stopped){
                        await(()->imagesReady(staging.getUI().getRootView(staging)));
                        task("C.final_layout",()->layout(staging,stageShell),false);
                        edt(()->{phase="verification";equivalent("C.equivalence",stock,staging);return null;});
                        task("C.publish",()->{live.setViewportView(staging);editor.validate();layout(staging,live);staging.paintImmediately(staging.getVisibleRect());},false);
                        meta("C.published","revision",revision,"viewport",edt(()->live.getViewport().getExtentSize().toString()));
                        task("C.resize",()->{Dimension old=live.getSize();live.setSize(Math.max(300,old.width-120),old.height);layout(staging,live);paint(staging);live.setSize(old);layout(staging,live);},false);
                        edt(()->{phase="verification";dispatchTag="verification";
                            JScrollBar source=sourcePane.getScrollPane().getVerticalScrollBar(),target=live.getVerticalScrollBar();
                            int maxSource=Math.max(0,source.getMaximum()-source.getVisibleAmount()),maxTarget=Math.max(0,target.getMaximum()-target.getVisibleAmount());
                            boolean mapping=true;for(int at:new int[]{0,maxSource/2,maxSource}){source.setValue(at);int expected=maxSource==0?0:(int)Math.round((double)source.getValue()/maxSource*maxTarget);target.setValue(expected);mapping &= target.getValue()==expected;}
                            int held=target.getValue();source.setValue(0);boolean disabled=target.getValue()==held;
                            meta("C.scroll_formula","exact_ratio",mapping,"disabled_source_does_not_move_target",disabled,"target_max",maxTarget);
                            return null;});
                    }
                    if(stopped)break;
                }
                checkSource("C");memory("after_candidates",true);
                if(html.contains("swatch.png")){
                    Path image=file.getParent().resolve("swatch.png");byte[] saved=Files.readAllBytes(image);
                    try {
                        BufferedImage changedImage=new BufferedImage(4,4,BufferedImage.TYPE_INT_ARGB);var g=changedImage.createGraphics();g.setColor(Color.GREEN);g.fillRect(0,0,4,4);g.dispose();javax.imageio.ImageIO.write(changedImage,"png",image.toFile());
                        HTMLDocument fresh=read(html);JEditorPane imageReference=edt(SwingHtmlFeasibilityProbe::pane);JScrollPane imageShell=edt(()->shell(imageReference));
                        edt(()->{phase="verification";imageReference.setDocument(fresh);layout(imageReference,imageShell);return null;});await(()->imagesReady(imageReference.getUI().getRootView(imageReference)));
                        edt(()->{phase="verification";meta("A.local_image_same_html","pixels_still_equal",Arrays.equals(pixels(stock,imageOffset(stock)),pixels(imageReference,imageOffset(imageReference))),"html_unchanged",true);return null;});
                    } finally {Files.write(image,saved);}
                }
                meta("dispatch_maxima","by_last_active_context",edt(()->new LinkedHashMap<>(dispatchMax)));
                // Closed/replaced prototype admission, with an already queued old callback.
                edt(()->{int[] generation={1},applied={0};SwingUtilities.invokeLater(()->{if(generation[0]==1)applied[0]++;});generation[0]=2;
                    SwingUtilities.invokeLater(()->meta("prototype.stale_callback","applied",applied[0],"expected",0));return null;});
                edt(()->null);
                // Resize and anchor/scroll geometry are explicit observations, not a relaxed pixel tolerance.
                task("stock.resize",()->{stockShell.setSize(420,600);layout(stock,stockShell);paint(stock);},false);
                meta("rendering","html_characters",html.length(),"mermaid_error_fallback",html.contains("Mermaid preview error"),"local_image",html.contains("swatch.png"));
            }
            edt(()->{live.setViewportView(new JPanel());return null;});memory("after_disposal",true);
        } catch(ResourceGuard guard) {
            meta("resource_guard_exit","completed",false);
        } finally {
            if(editor!=null)edt(()->{editor.disposeMarkdownPreviews();editor.shutdownSyntaxUi();editor.shutdownLspScheduling();editor.asyncJobService.shutdownNow();if(editor.backupScheduler!=null)editor.backupScheduler.close();if(editor.externalChangeTimer!=null)editor.externalChangeTimer.stop();if(editor.recoverySnapshotTimer!=null)editor.recoverySnapshotTimer.stop();if(editor.messageResetTimer!=null)editor.messageResetTimer.stop();editor.fileWatcherService.stop();editor.dispose();return null;});
        }
        System.out.println("HT_DONE");System.exit(0);
    }
}
