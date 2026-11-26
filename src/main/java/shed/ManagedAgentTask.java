package shed;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One consented CLI process, bounded output and lifetime; no shell, service or implicit permission flags. */
final class ManagedAgentTask {
    private static final java.util.concurrent.atomic.AtomicInteger ioWorkers=new java.util.concurrent.atomic.AtomicInteger();
    static int activeIoWorkers(){return ioWorkers.get();}
    record Result(int exit,String output){}
    private record Child(ProcessHandle handle,Instant started) {
        boolean owned(){return started!=null&&handle.isAlive()&&started.equals(handle.info().startInstant().orElse(null));}
    }
    static List<String> command(String json) throws IOException {
        Object parsed=MiniJson.parseStrict(json);
        if(!(parsed instanceof List<?> values)||values.isEmpty()||values.size()>32)throw new IllegalArgumentException("Use an argument array, such as [\"codex\",\"exec\",\"-\"]");
        var result=new ArrayList<String>();
        for(Object value:values){if(!(value instanceof String s)||s.isBlank()||s.length()>4096||s.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid CLI argument");result.add(s);}
        Path executable=resolve(result.getFirst());String name=executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if(Set.of("sh","bash","zsh","fish","cmd.exe","powershell.exe","pwsh.exe","pwsh").contains(name))throw new IllegalArgumentException("Select a coding CLI, not an interactive shell");
        result.set(0,executable.toString());return List.copyOf(result);
    }
    private static Path resolve(String name)throws IOException {
        Path supplied=Path.of(name);if(supplied.isAbsolute()||supplied.getNameCount()>1)return executable(supplied);
        String path=System.getenv("PATH");if(path!=null)for(String entry:path.split(java.util.regex.Pattern.quote(File.pathSeparator)))if(!entry.isBlank()){
            Path candidate=Path.of(entry).resolve(name);if(Files.isRegularFile(candidate)&&Files.isExecutable(candidate))return executable(candidate);
            if(System.getProperty("os.name","").startsWith("Windows")&&!name.endsWith(".exe")){candidate=Path.of(entry).resolve(name+".exe");if(Files.isRegularFile(candidate)&&Files.isExecutable(candidate))return executable(candidate);}
        }
        throw new IOException("CLI executable not found; select its full path");
    }
    private static Path executable(Path p)throws IOException {Path real=p.toRealPath();if(!Files.isRegularFile(real)||!Files.isExecutable(real))throw new IOException("Expected a working CLI executable");return real;}
    static Result run(List<String> command,Path project,Path descriptor,String id,String prompt,Map<String,String> environment,java.util.function.BooleanSupplier live,long timeoutMillis)throws Exception {
        return run(command,project,descriptor,id,prompt,environment,live,timeoutMillis,ignored->{});
    }
    static Result run(List<String> command,Path project,Path descriptor,String id,String prompt,Map<String,String> environment,java.util.function.BooleanSupplier live,long timeoutMillis,java.util.function.Consumer<String> outputChunk)throws Exception {
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Agent process work belongs off the EDT");
        if(timeoutMillis<100||timeoutMillis>8*60_000||prompt.getBytes(StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("Bounded task required");
        if(!live.getAsBoolean())throw new CancellationException();
        var builder=new ProcessBuilder(command).directory(project.toRealPath().toFile()).redirectErrorStream(true);
        if(environment!=null){builder.environment().clear();builder.environment().putAll(environment);}
        builder.environment().put("SHED_CONTROL",descriptor.toAbsolutePath().toString());builder.environment().put("SHED_DELEGATION",id);
        // Reuse the current runtime even when GUI PATH/JAVA_HOME are stale.
        builder.environment().put("JAVA_HOME",System.getProperty("java.home"));builder.environment().put("JAVA",Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").startsWith("Windows")?"java.exe":"java").toString());
        Process child=builder.start();var descendants=new LinkedHashMap<Long,Child>();var output=new StringBuilder();var ioFailed=new AtomicBoolean();
        ioWorkers.incrementAndGet();
        Thread reader=Thread.ofVirtual().name("shed-agent-task-output").start(()->{
            try(var in=new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)){char[] chars=new char[1024];int n;while((n=in.read(chars))!=-1){synchronized(output){output.append(chars,0,n);if(output.length()>8192)output.delete(0,output.length()-8192);}outputChunk.accept(new String(chars,0,n));}}
            catch(IOException|RuntimeException stopped){if(child.isAlive())ioFailed.set(true);}
            finally{ioWorkers.decrementAndGet();}
        });
        ioWorkers.incrementAndGet();
        Thread writer=Thread.ofVirtual().name("shed-agent-task-input").start(()->{try(var out=child.getOutputStream()){out.write(prompt.getBytes(StandardCharsets.UTF_8));}catch(IOException stopped){if(child.isAlive())ioFailed.set(true);}finally{ioWorkers.decrementAndGet();}});
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);int exit;
        try {
            do {
                track(child,descendants);
                if(!live.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();
                if(System.nanoTime()>=deadline)throw new TimeoutException("Agent task deadline exceeded");
                if(ioFailed.get())throw new IOException("Agent task input/output failed");
            } while(!child.waitFor(25,TimeUnit.MILLISECONDS));
            track(child,descendants);exit=child.exitValue();
        } finally {
            boolean interrupted=Thread.interrupted();
            try {
                try { track(child,descendants); }
                finally {
                    for(var owned:new ArrayList<>(descendants.values()).reversed())if(owned.owned())owned.handle().destroyForcibly();
                    if(child.isAlive())child.destroyForcibly();
                    try {
                        if(!child.waitFor(2,TimeUnit.SECONDS))throw new IOException("Agent process did not retire");
                        long childrenDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                        for(var owned:descendants.values()) {
                            try { owned.handle().onExit().get(Math.max(1,childrenDeadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
                            catch(TimeoutException pending){throw new IOException("Agent child did not retire",pending);}
                        }
                    } finally {
                        child.getOutputStream().close();
                        reader.join(1000);writer.join(1000);
                        child.getInputStream().close();child.getErrorStream().close();
                        if(reader.isAlive()||writer.isAlive())throw new IOException("Agent task pipes did not retire");
                    }
                }
            } finally {if(interrupted)Thread.currentThread().interrupt();}
        }
        return new Result(exit,snapshot(output));
    }
    private static void track(Process child,Map<Long,Child> tracked)throws IOException {
        child.descendants().limit(257).forEach(p->tracked.putIfAbsent(p.pid(),new Child(p,p.info().startInstant().orElse(null))));
        if(tracked.size()>256)throw new IOException("Agent exceeded the child-process budget");
    }
    private static String snapshot(StringBuilder output) {
        String raw;synchronized(output){raw=output.toString();}
        raw=raw.replaceAll("\\x1b\\[[0-?]*[ -/]*[@-~]","");var clean=new StringBuilder();raw.codePoints().filter(c->!Character.isISOControl(c)||c=='\n'||c=='\t').forEach(clean::appendCodePoint);
        return clean.substring(Math.max(0,clean.length()-3800));
    }
}
