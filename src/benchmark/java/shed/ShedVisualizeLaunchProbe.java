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
            Path assets=Files.createDirectory(project.resolve("assets"));byte[] asset=new byte[1024*1024];
            for(int i=0;i<40;i++)Files.write(assets.resolve("fixture-"+i+(i%2==0?".png":".gz")),asset);
            var bytes=new ByteArrayOutputStream();PrintStream original=System.out;
            try {System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));ShedVisualize.run(new String[]{"visualize",project.toString()});}finally{System.setOut(original);}
            children.addAll(ProcessHandle.current().descendants().toList());var response=MiniJson.asObject(MiniJson.parseStrict(bytes.toString(StandardCharsets.UTF_8).strip()));
            long pid=((Number)response.get("pid")).longValue();if(children.stream().noneMatch(p->p.pid()==pid))throw new AssertionError("Readiness PID is not an owned child");
            var reply=new ByteArrayOutputStream();int code=WorkspaceCli.run(new String[]{"ctl","--connect",(String)response.get("descriptor"),"watch-state"},InputStream.nullInputStream(),reply,OutputStream.nullOutputStream());
            var state=ShedControlCli.result(reply.toString(StandardCharsets.UTF_8));var watch=MiniJson.asObject(state.get("observation"));if(code!=0||!Boolean.TRUE.equals(watch.get("watching"))||!Boolean.TRUE.equals(watch.get("ready")))throw new AssertionError("Watch attachment failed: "+reply);
            if(!"Agent".equals(watch.get("agent")))throw new AssertionError("Wrong default display label");
            double launchMs=(System.nanoTime()-started)/1e6;long readStarted=System.nanoTime();
            var reading=new ByteArrayOutputStream();int readCode=WorkspaceCli.run(new String[]{"ctl","--connect",(String)response.get("descriptor"),"read","Demo.java","--agent","Local agent"},InputStream.nullInputStream(),reading,OutputStream.nullOutputStream());
            var source=ShedControlCli.result(reading.toString(StandardCharsets.UTF_8));
            if(readCode!=0||!((String)source.get("content")).contains("public class Demo"))throw new AssertionError("First-file read through actual visualize launcher failed");
            double readMs=(System.nanoTime()-readStarted)/1e6;
            Path tooLarge=Files.createDirectory(project.getParent().resolve("oversized-text"));byte[] text="x".repeat(1024*1024).getBytes(StandardCharsets.UTF_8);
            for(int i=0;i<33;i++)Files.write(tooLarge.resolve("file-"+i+".txt"),text);
            long rejected=System.nanoTime();
            try{ShedVisualize.run(new String[]{"visualize",tooLarge.toString()});throw new AssertionError("Oversized text baseline was accepted");}
            catch(IllegalStateException expected){if(!expected.getMessage().equals("WATCH_START_FAILED: Watch snapshot byte limit reached"))throw expected;}
            double failureMs=(System.nanoTime()-rejected)/1e6;if(failureMs>=10000)throw new AssertionError("Known baseline failure waited for startup timeout");
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","events",List.of(Map.of("phase","launch_and_authenticated_watch_without_open_source","elapsed_ms",launchMs,"ready",true,"opaque_asset_bytes",40*1024*1024),Map.of("phase","first_file_read_through_real_visualize_child","exact",true,"elapsed_ms",readMs),Map.of("phase","text_budget_failure_reported_promptly","elapsed_ms",failureMs,"code","WATCH_START_FAILED")),"native_shell","NOT_USED")));
        }finally {
            for(var child:children)if(child.isAlive())child.destroy();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(children.stream().anyMatch(ProcessHandle::isAlive)&&System.nanoTime()<deadline)Thread.sleep(25);
            for(var child:children)if(child.isAlive())child.destroyForcibly();
        }
    }
}
