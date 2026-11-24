package shed;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
/** Fixed direct-Java launch positive control; no shell, model, or personal project. */
public final class ShedVisualizeLaunchProbe {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]).toRealPath(),project=Path.of(args[1]).toRealPath(),fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        if(!output.startsWith(fixture.resolve("run"))||!project.startsWith(fixture.resolve("run")))throw new IllegalArgumentException("Disposable fixture required");
        var children=new ArrayList<ProcessHandle>();long started=System.nanoTime();
        try {
            var bytes=new ByteArrayOutputStream();PrintStream original=System.out;
            try {System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));ShedVisualize.run(new String[]{"visualize",project.toString(),"--agent","Codex"});}finally{System.setOut(original);}
            children.addAll(ProcessHandle.current().descendants().toList());var response=MiniJson.asObject(MiniJson.parseStrict(bytes.toString(StandardCharsets.UTF_8).strip()));
            long pid=((Number)response.get("pid")).longValue();if(children.stream().noneMatch(p->p.pid()==pid))throw new AssertionError("Readiness PID is not an owned child");
            var reply=new ByteArrayOutputStream();int code=WorkspaceCli.run(new String[]{"ctl","--connect",(String)response.get("descriptor"),"watch-state"},InputStream.nullInputStream(),reply,OutputStream.nullOutputStream());
            var state=ShedControlCli.result(reply.toString(StandardCharsets.UTF_8));var watch=MiniJson.asObject(state.get("observation"));if(code!=0||!Boolean.TRUE.equals(watch.get("watching"))||!Boolean.TRUE.equals(watch.get("ready")))throw new AssertionError("Watch attachment failed: "+reply);
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","events",List.of(Map.of("phase","launch_and_authenticated_watch_without_open_source","elapsed_ms",(System.nanoTime()-started)/1e6,"ready",true)),"native_shell","NOT_USED")));
        }finally {
            for(var child:children)if(child.isAlive())child.destroy();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(children.stream().anyMatch(ProcessHandle::isAlive)&&System.nanoTime()<deadline)Thread.sleep(25);
            for(var child:children)if(child.isAlive())child.destroyForcibly();
        }
    }
}
