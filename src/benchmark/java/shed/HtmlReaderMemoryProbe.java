package shed;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;

/** Opt-in bounded diagnostics; the candidate is not registered in the application by this probe. */
public final class HtmlReaderMemoryProbe {
    static final com.sun.management.ThreadMXBean THREADS=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static final List<Map<String,Object>> rows=new ArrayList<>();
    static final class Counts {long calls,arrayChars,rangeChars,maxArray;}
    static final class SliceKit extends HTMLEditorKit {
        final boolean slice;final Counts counts=new Counts();
        SliceKit(boolean slice){this.slice=slice;}
        @Override public Document createDefaultDocument(){StyleSheet styles=new StyleSheet();styles.addStyleSheet(getStyleSheet());
            HTMLDocument d=new SliceDocument(styles,slice,counts);d.setParser(getParser());d.setAsynchronousLoadPriority(4);d.setTokenThreshold(100);return d;}
    }
    static final class SliceDocument extends HTMLDocument {
        final boolean slice;final Counts counts;
        SliceDocument(StyleSheet styles,boolean slice,Counts counts){super(styles);this.slice=slice;this.counts=counts;}
        void base(){Object value=getProperty(Document.StreamDescriptionProperty);if(value instanceof URL url)setBase(url);}
        @Override public HTMLEditorKit.ParserCallback getReader(int pos){base();return reader(pos,0,0,null);}
        @Override public HTMLEditorKit.ParserCallback getReader(int pos,int pop,int push,HTML.Tag tag){base();return reader(pos,pop,push,tag);}
        HTMLReader reader(int pos,int pop,int push,HTML.Tag tag){return new HTMLReader(pos,pop,push,tag){
            @Override protected void addContent(char[] data,int offset,int length,boolean implied){
                counts.calls++;counts.arrayChars+=data.length;counts.rangeChars+=length;counts.maxArray=Math.max(counts.maxArray,data.length);
                if(slice&&(offset!=0||length!=data.length)){char[] exact=Arrays.copyOfRange(data,offset,offset+length);super.addContent(exact,0,length,implied);}
                else super.addContent(data,offset,length,implied);
            }
        };}
    }
    interface Work<T>{T run()throws Exception;}
    static <T>T timed(String name,Work<T> work)throws Exception {
        long bytes=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()),cpu=THREADS.getCurrentThreadCpuTime(),start=System.nanoTime();
        T result=work.run();Map<String,Object> r=new LinkedHashMap<>();r.put("operation",name);r.put("wall_ms",(System.nanoTime()-start)/1e6);r.put("thread_cpu_ms",(THREADS.getCurrentThreadCpuTime()-cpu)/1e6);r.put("allocated_bytes",THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes);r.put("thread",Thread.currentThread().getName());rows.add(r);emit("HR_STAGE ",r);return result;
    }
    static void emit(String prefix,Object o){System.out.println(prefix+MiniJson.stringify(o));System.out.flush();}
    static <T>T edt(Callable<T> work)throws Exception{FutureTask<T> r=new FutureTask<>(work);SwingUtilities.invokeAndWait(r);return r.get();}
    static String digest(String text)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    static String tree(Document d){StringBuilder s=new StringBuilder();ElementIterator it=new ElementIterator(d);Element e;while((e=it.next())!=null){s.append(e.getName()).append('/').append(e.getStartOffset()).append('/').append(e.getEndOffset()).append(' ');TreeMap<String,String>a=new TreeMap<>();var keys=e.getAttributes().getAttributeNames();while(keys.hasMoreElements()){Object k=keys.nextElement();if(k!=StyleConstants.ResolveAttribute)a.put(k.toString(),String.valueOf(e.getAttributes().getAttribute(k)));}s.append(a).append('\n');}return s.toString();}
    static List<String> code(HTMLDocument d)throws Exception{List<String> result=new ArrayList<>();ElementIterator it=new ElementIterator(d);Element e;while((e=it.next())!=null)if(e.getAttributes().getAttribute(StyleConstants.NameAttribute)==HTML.Tag.PRE)result.add(d.getText(e.getStartOffset(),Math.min(d.getLength(),e.getEndOffset())-e.getStartOffset()));return result;}
    static Object reflected(String name)throws Exception{var f=MarkdownPreviewRenderer.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static String render(String source,Path file)throws Exception{
        // Replays are separately labelled, not additive partitions of the final render.
        try(MarkdownPreviewAssets assets=new MarkdownPreviewAssets()){
            var method=MarkdownPreviewRenderer.class.getDeclaredMethod("prepare",String.class,MarkdownPreviewAssets.class,Color.class);method.setAccessible(true);
            String prepared=timed("replay.preprocess",()->(String)method.invoke(null,source,assets,Color.BLACK));
            var parsed=timed("replay.commonmark_parse",()->((org.commonmark.parser.Parser)reflected("PARSER")).parse(prepared));
            String html=timed("replay.html_generate",()->((org.commonmark.renderer.html.HtmlRenderer)reflected("HTML")).render(parsed));
            timed("replay.sanitize",()->((org.owasp.html.PolicyFactory)reflected("RAW_HTML_POLICY")).sanitize(html));
            return timed("production.markdown_render",()->MarkdownPreviewRenderer.render(source,"input.md",new Font(Font.DIALOG,0,13),Color.WHITE,Color.BLACK,assets,file.toFile()));
        }
    }
    static void peaksReset(){for(MemoryPoolMXBean p:ManagementFactory.getMemoryPoolMXBeans())if(p.getType()==MemoryType.HEAP)p.resetPeakUsage();}
    static long peaks(){return ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();}
    static int[] pixels(JEditorPane p){BufferedImage image=new BufferedImage(600,400,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();try{p.paint(g);}finally{g.dispose();}return image.getRGB(0,0,600,400,null,0,600);}
    public static void main(String[] args)throws Exception {
        Path file=Path.of(args[0]);String route=args[1],mode=args[2];int samples=Integer.parseInt(args[3]);
        String source=timed("source.read",()->Files.readString(file));String html=route.equals("raw")?source:render(source,file);
        emit("HR_META ",Map.of("source_characters",source.length(),"html_characters",html.length(),"source_sha256",digest(source),"route",route,"mode",mode,"runtime",System.getProperty("java.runtime.version"),"jar_origin",MarkdownPreviewPane.class.getProtectionDomain().getCodeSource().getLocation().toString()));
        if(route.equals("production")){
            edt(()->{FileBuffer b=new FileBuffer(file.toFile());JTextArea area=new JTextArea();area.setDocument(b.getDocument());EditorPane pane=new EditorPane(area,null,new JScrollPane(area),null);pane.setBuffer(b);long rev=b.documentRevision();var snap=b.textSnapshot();
                SwingUtilities.invokeLater(()->emit("HR_SOURCE ",Map.of("queue_ms",(System.nanoTime()-posted)/1e6)));posted=System.nanoTime();
                MarkdownPreviewPane preview=timed("production.preview_constructor_no_layout",()->new MarkdownPreviewPane(b,()->new Font(Font.DIALOG,0,13),()->Color.WHITE,()->Color.BLACK,h->{},()->{},()->false,()->false,pane,null));
                if(b.documentRevision()!=rev||b.textSnapshot()!=snap)throw new AssertionError("Preview changed source");
                emit("HR_DOCUMENT ",Map.of("html_matches",preview.getHtml().equals(html),"source_unchanged",true));preview.disposePreview();return null;});
        }else{
            for(int i=0;i<samples;i++){
                System.gc();Thread.sleep(50);peaksReset();long before=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                SliceKit counted=mode.equals("counted")||mode.equals("slice")?new SliceKit(mode.equals("slice")):null;
                HTMLEditorKit kit=counted==null?new HTMLEditorKit():counted;
                HTMLDocument d=(HTMLDocument)kit.createDefaultDocument();
                if(route.equals("setText")){
                    HTMLDocument[] holder={null};edt(()->{JEditorPane p=new JEditorPane();p.setEditorKit(kit);p.setEditable(false);posted=System.nanoTime();SwingUtilities.invokeLater(()->emit("HR_SOURCE ",Map.of("queue_ms",(System.nanoTime()-posted)/1e6)));
                        timed("reader.setText_edt",()->{p.setText(html);return null;});holder[0]=(HTMLDocument)p.getDocument();return null;});d=holder[0];
                }else{
                    HTMLDocument owned=d;timed("reader.detached",()->{kit.read(new StringReader(html),owned,0);return null;});
                }
                List<String> pre=code(d);boolean independent=true;
                if(!route.equals("raw")){
                    var parsed=org.commonmark.parser.Parser.builder().build().parse(source);List<String> expected=new ArrayList<>();for(var n=parsed.getFirstChild();n!=null;n=n.getNext())if(n instanceof org.commonmark.node.FencedCodeBlock f)expected.add(f.getLiteral());
                    if(!expected.isEmpty()){independent=pre.size()==expected.size();for(int n=0;n<Math.min(pre.size(),expected.size());n++)independent &= pre.get(n).equals(expected.get(n));}
                }
                Map<String,Object> result=new LinkedHashMap<>();result.put("sample",i);result.put("document_characters",d.getLength());result.put("text_sha256",digest(d.getText(0,d.getLength())));result.put("tree_sha256",digest(tree(d)));result.put("pre_lengths",pre.stream().map(String::length).toList());result.put("pre_sha256",pre.stream().map(s->{try{return digest(s);}catch(Exception e){throw new RuntimeException(e);}}).toList());result.put("independent_code_equal",independent);result.put("heap_used_before",before);result.put("heap_used_after",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());result.put("heap_peak_pool_sum_upper_bound",peaks());
                if(counted!=null)result.put("copy_counters",Map.of("calls",counted.counts.calls,"incoming_array_chars",counted.counts.arrayChars,"logical_range_chars",counted.counts.rangeChars,"max_array",counted.counts.maxArray));
                emit("HR_DOCUMENT ",result);
                if(!independent)throw new AssertionError("Code text differs from CommonMark literal");
                d=null;System.gc();Thread.sleep(50);emit("HR_MEMORY ",Map.of("after_release_requested_gc",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
            }
        }
        System.out.println("HR_DONE");System.exit(0);
    }
    static long posted;
}
