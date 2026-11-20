package shed;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.pty4j.PtyProcessBuilder;
import com.jediterm.core.util.TermSize;

/** Opt-in local synthetic service controls, never discovered or packaged by the application. */
public final class WholeIdeServicesProbe {
    @FunctionalInterface interface Work { void run() throws Exception; }
    static void out(Map<String, Object> fields) {
        var row=new LinkedHashMap<String,Object>(fields);row.putIfAbsent("epoch_ms",System.currentTimeMillis());
        System.out.println("B0 " + MiniJson.stringify(row));System.out.flush();
    }
    static void timed(String operation, int sample, Work work) throws Exception {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocation = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()), start = System.nanoTime();
        work.run();
        out(Map.of("operation", operation, "sample", sample, "ms", (System.nanoTime()-start)/1e6,
            "allocated_bytes", threads.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocation));
    }
    static void state(String phase) {
        var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os=(com.sun.management.UnixOperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        out(Map.of("operation",phase,"heap_used_mib",heap.getUsed()/1048576d,"heap_committed_mib",heap.getCommitted()/1048576d,
            "threads",ManagementFactory.getThreadMXBean().getThreadCount(),"open_fds",os.getOpenFileDescriptorCount(),
            "live_children",ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count()));
    }
    static void check(boolean value, String message) { if(!value)throw new AssertionError(message); }
    static LspClient client(Path root, int delay) throws Exception {
        return client(root,delay,null);
    }
    static LspClient client(Path root, int delay, Path lifecycle) throws Exception {
        var arguments=new ArrayList<>(List.of("-cp",System.getProperty("java.class.path"),WholeIdeServicesProbe.class.getName(),"server",String.valueOf(delay)));
        if(lifecycle!=null)arguments.add(lifecycle.toString());
        return new LspClient(Path.of(System.getProperty("java.home"),"bin/java").toString(),
            arguments.toArray(String[]::new),root);
    }
    static void lsp(Path root) throws Exception {
        for(int cycle=0;cycle<3;cycle++) {
            LspClient[] holder={null};
            timed("lsp_initialize",cycle,()->holder[0]=client(root,0));
            LspClient c=holder[0];String uri=root.resolve("Main.java").toUri().toString();
            try {
                long start=System.nanoTime();c.didOpen(uri,"java","class Main {}\n");
                c.hover(uri,0,0);c.drainNotifications();
                check(c.getDiagnostics(uri).size()==1,"diagnostic missing");
                out(Map.of("operation","lsp_open_to_diagnostic_barrier","sample",cycle,"ms",(System.nanoTime()-start)/1e6));
                if(cycle==0) for(int i=0;i<51;i++) {
                    int n=i;
                    timed("lsp_completion",n,()->check(c.completion(uri,0,0).size()==1,"completion missing"));
                    timed("lsp_definition",n,()->check(c.definition(uri,0,0)!=null,"definition missing"));
                    timed("lsp_references",n,()->check(c.references(uri,0,0,true).size()==1,"references missing"));
                    timed("lsp_hover",n,()->check(!c.hover(uri,0,0).isBlank(),"hover missing"));
                    timed("lsp_workspace_symbols",n,()->check(c.workspaceSymbols("Main").size()==1,"symbol missing"));
                    timed("lsp_format",n,()->check(c.formatting(uri,4,true).size()==1,"format missing"));
                    timed("lsp_code_action",n,()->check(c.codeActions(uri,0,0,List.of()).size()==1,"action missing"));
                }
            } finally {
                timed("lsp_stop",cycle,()->{c.didClose(uri);c.stop();long end=System.nanoTime()+3_000_000_000L;
                    while(c.isAlive()&&System.nanoTime()<end)Thread.sleep(10);check(!c.isAlive(),"server survived stop");});
            }
        }
    }
    static void project(Path root, int files) throws Exception {
        var index=new WorkspaceIndexService(root.resolveSibling("index-"+files));
        try {
            for(int i=0;i<6;i++) {int n=i;
                timed("scan_"+files,n,()->check(new ProjectFileScanner().scan(root,root,files+10,()->false).files().size()==files,"scan count"));
                timed("index_"+files,n,()->check(index.build(true,root,null).index().entries().size()==files,"index count"));
                timed("search_"+files,n,()->check(new WorkspaceTextSearchService(index).search(true,root,"unique-b0-target",()->false,null).matches().size()==1,"search count"));
            }
        } finally {index.close();}
    }
    static void processes(Path root) throws Exception {
        for(int i=0;i<12;i++) {int n=i;
            timed("task_output",n,()->{
                var running=BackgroundTaskProcess.start(List.of("/bin/sh","-c","printf '%s\\n' b0-ready; printf '%s\\n' \"$B0_VALUE\""),root.toFile(),Map.of("B0_VALUE","environment-ok"),8192,"");
                var result=running.awaitCompletion(null);check(result.exitCode==0&&result.stdout.contains("environment-ok"),"task output/environment");
            });
            timed("task_cancel",n,()->{
                var running=BackgroundTaskProcess.start(List.of("/bin/sh","-c","printf b0-ready; exec sleep 10"),root.toFile(),Map.of(),8192,"b0-ready");
                check(running.awaitReadiness(1000,null).isEmpty(),"task readiness");running.stop();running.awaitCompletion(null);
            });
            timed("pty_roundtrip",n,()->{
                var environment=new HashMap<>(System.getenv());
                environment.put("HISTFILE","/dev/null");
                environment.put("ENV","/dev/null");environment.put("BASH_ENV","/dev/null");
                var p=new PtyProcessBuilder(new String[]{"/bin/sh"}).setDirectory(root.toString()).setEnvironment(environment).setInitialColumns(80).setInitialRows(24).start();
                var connector=new PtyTerminalConnector(p,List.of("/bin/sh"),StandardCharsets.UTF_8);
                var reader=Executors.newSingleThreadExecutor();
                try {
                    var reply=reader.submit(()->{StringBuilder text=new StringBuilder();char[] b=new char[256];for(int count;(count=connector.read(b,0,b.length))>=0;)text.append(b,0,count);return text.toString();});
                    connector.resize(new TermSize(100,40));connector.write("stty size; printf b0-pty-ok; exit\n");
                    String text=reply.get(4,TimeUnit.SECONDS);check(text.contains("b0-pty-ok")&&text.contains("40 100"),"pty roundtrip/resize");
                } finally {connector.close();reader.shutdownNow();p.waitFor(2,TimeUnit.SECONDS);check(!p.isAlive(),"pty survived close");}
            });
        }
    }
    static void requireNativeFixture() {
        throw new IllegalStateException("B0.1: native process/PTY confinement unqualified; benchmark blocked");
    }
    public static void main(String[] args) throws Exception {
        requireNativeFixture();
        if(args[0].equals("server")){server(Integer.parseInt(args[1]),args.length>2?Path.of(args[2]):null);return;}
        Path root=Path.of(args[0]);state("initial");
        for(int count:new int[]{100,1000,5000})project(root.resolve("project-"+count),count);
        state("after_projects");lsp(root);state("after_lsp");processes(root);state("after_processes");
        for(int i=0;i<3;i++){System.gc();Thread.sleep(100);}state("disposed_requested_gc");
        check(ProcessHandle.current().descendants().noneMatch(ProcessHandle::isAlive),"owned process remains");
        System.out.println("B0_SERVICES_DONE");
    }
    private static void write(Object id,Object result) throws Exception {writeMessage(Map.of("jsonrpc","2.0","id",id,"result",result));}
    private static void writeMessage(Map<String,Object> message) throws Exception {
        byte[] b=MiniJson.stringify(message).getBytes(StandardCharsets.UTF_8);
        System.out.write(("Content-Length: "+b.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));System.out.write(b);System.out.flush();
    }
    private static void lifecycle(Path file,String event) throws Exception {
        if(file!=null)Files.writeString(file,event+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static void server(int delay, Path record) throws Exception {
        var in=new BufferedInputStream(System.in);String uri="file:///unknown";
        while(true){int length=0;StringBuilder line=new StringBuilder();
            for(;;){int value=in.read();if(value<0){lifecycle(record,"eof");return;}line.append((char)value);
                if(line.toString().endsWith("\r\n")){String h=line.toString().trim();line.setLength(0);if(h.isEmpty())break;
                    if(h.startsWith("Content-Length:"))length=Integer.parseInt(h.substring(15).trim());}}
            check(length>0&&length<1048576,"frame length");var req=MiniJson.asObject(MiniJson.parseStrict(new String(in.readNBytes(length),StandardCharsets.UTF_8)));
            String method=String.valueOf(req.get("method"));Object id=req.get("id");
            lifecycle(record,method);
            var params=MiniJson.asObject(req.get("params"));
            if(method.equals("exit"))return;
            if(method.equals("textDocument/didOpen")){
                uri=String.valueOf(MiniJson.asObject(params.get("textDocument")).get("uri"));
                writeMessage(Map.of("jsonrpc","2.0","method","textDocument/publishDiagnostics","params",Map.of("uri",uri,"diagnostics",List.of(Map.of("range",range(),"message","b0 fixture diagnostic","severity",2)))));continue;}
            if(id==null)continue;
            if(delay>0&&method.equals("textDocument/definition"))Thread.sleep(delay);
            Object value=switch(method){
                case "initialize" -> Map.of("capabilities",Map.of("textDocumentSync",1,"completionProvider",Map.of(),"definitionProvider",true,"referencesProvider",true,
                    "hoverProvider",true,"workspaceSymbolProvider",true,"documentFormattingProvider",true,"codeActionProvider",true));
                case "textDocument/completion" -> List.of(Map.of("label","b0Completion","insertText","b0Completion"));
                case "textDocument/definition" -> Map.of("uri",uri,"range",range());
                case "textDocument/references" -> List.of(Map.of("uri",uri,"range",range()));
                case "textDocument/hover" -> Map.of("contents","b0 hover");
                case "workspace/symbol" -> List.of(Map.of("name","Main","kind",5,"location",Map.of("uri",uri,"range",range())));
                case "textDocument/formatting" -> List.of(Map.of("range",range(),"newText","class"));
                case "textDocument/codeAction" -> List.of(Map.of("title","B0 action","kind","quickfix","edit",Map.of("changes",Map.of(uri,List.of(Map.of("range",range(),"newText","class"))))));
                default -> Map.of();};
            write(id,value);
        }
    }
    private static Map<String,Object> range(){return Map.of("start",Map.of("line",0,"character",0),"end",Map.of("line",0,"character",5));}
}
