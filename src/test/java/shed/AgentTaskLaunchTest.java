package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Static launch contracts and in-process authenticated CLI; no native process or configured CLI runs. */
class AgentTaskLaunchTest {
    @TempDir Path temporary;
    @Test void directArgvPreservesBoundariesAndRejectsMissingShellAndUnboundedCommands() throws Exception {
        String java=AgentTaskInstructions.cli().getFirst();
        var argv=ManagedAgentTask.command(MiniJson.stringify(List.of(java,"spaces remain one argument","$(not a shell)")));
        assertEquals(Path.of(java).toRealPath().toString(),argv.getFirst());assertEquals(3,argv.size());assertEquals("$(not a shell)",argv.getLast());
        assertThrows(IllegalArgumentException.class,()->ManagedAgentTask.command("[]"));
        assertThrows(IllegalArgumentException.class,()->ManagedAgentTask.command("[1]"));
        assertThrows(IOException.class,()->ManagedAgentTask.command("[\"/missing/cli\"]"));
        if(Files.isExecutable(Path.of("/bin/sh")))assertThrows(IllegalArgumentException.class,()->ManagedAgentTask.command("[\"/bin/sh\",\"-c\",\"ignored\"]"));
    }
    @Test void neutralInstructionsSpecifyVisibleReadsFreshnessAndRevocationWithoutEmbeddingCredentials() {
        var prompt=AgentTaskInstructions.prompt("Update the application","Any CLI");
        for(String text:List.of("SHED_CONTROL","SHED_DELEGATION","read <project-relative-path>","UTF-16","SHA-256","sync <relative-path>","AUTHORIZATION_REVOKED","session-finish","ordinary filesystem"))assertTrue(prompt.toLowerCase().contains(text.toLowerCase()),text);
        assertFalse(prompt.contains(".key"));assertFalse(prompt.contains("--yolo"));assertFalse(prompt.contains("danger-full-access"));assertTrue(AgentTaskInstructions.cli().contains("shed.Texteditor"));
    }
    @Test void actualCliRequestStatusAndFinishUseSameAuthenticatedBridge() throws Exception {
        try(var f=new AgentDelegationTest.DelegatedFixture(temporary)) {
            Path task=temporary.resolve("task.txt");Files.writeString(task,"A real task with no provider dependency");
            var out=new ByteArrayOutputStream();
            int code=ShedControlCli.run(new String[]{"ctl","--connect",f.server.descriptor().toString(),"session-request","--agent","Any CLI","--request-id","external","--task-file",task.toString()},InputStream.nullInputStream(),out,OutputStream.nullOutputStream());
            assertEquals(0,code,out.toString());assertEquals("awaiting-approval",f.lease.state());
            f.lease.approve("external");f.lease.activate("external");out.reset();
            code=ShedControlCli.run(new String[]{"ctl","--connect",f.server.descriptor().toString(),"state","--delegation","external"},InputStream.nullInputStream(),out,OutputStream.nullOutputStream());assertEquals(0,code,out.toString());
            out.reset();code=ShedControlCli.run(new String[]{"ctl","--connect",f.server.descriptor().toString(),"session-finish","--request-id","external","--task-file","-"},new ByteArrayInputStream("Changed nothing. No tests run.".getBytes(java.nio.charset.StandardCharsets.UTF_8)),out,OutputStream.nullOutputStream());
            assertEquals(0,code,out.toString());assertEquals("completed",f.lease.state());assertFalse(f.lease.allowed("external"));
            assertEquals("Changed nothing. No tests run.",f.lease.status("external").get("summary"));
        }
    }
}
