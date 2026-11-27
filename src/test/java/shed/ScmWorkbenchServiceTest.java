package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.ScmWorkbenchModel.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScmWorkbenchServiceTest {
    @TempDir Path root;
    @Test void structuredStatusAndDiffUseOnlyDeclaredCommands()throws Exception{
        var commands=new ArrayList<List<String>>();var service=new ScmWorkbenchService((r,command,token)->{assertEquals(root,r);commands.add(command);if(command.contains("status"))return new CommandResult(0,"M Source.java\0","");if(command.contains("resolve"))return new CommandResult(0,"U Source.java\0","");if(command.contains("branch"))return new CommandResult(0,"default\n","");return new CommandResult(0,"@@ -1 +1 @@\n-before\n+after\n","");});
        var snapshot=service.status(Provider.MERCURIAL,root,null);assertEquals("default",snapshot.location());assertTrue(snapshot.changes().getFirst().conflict());assertEquals(3,commands.size());assertTrue(service.diff(Provider.MERCURIAL,root,"Source.java",null).contains("+after"));assertEquals("path:Source.java",commands.getLast().getLast());
    }
    @Test void nonzeroTruncatedAndMalformedRepliesAreNotCleanSuccess()throws Exception{
        var denied=new ScmWorkbenchService((r,c,t)->new CommandResult(1,"","https://user:secret@example.invalid failed"));var error=assertThrows(IOException.class,()->denied.status(Provider.MERCURIAL,root,null));assertFalse(error.getMessage().contains("secret"));
        var truncated=new ScmWorkbenchService((r,c,t)->new CommandResult(0,"M partial\n[shed: output truncated]",""));assertThrows(IOException.class,()->truncated.status(Provider.MERCURIAL,root,null));
        var invalid=new ScmWorkbenchService((r,c,t)->new CommandResult(0,"not XML",""));assertThrows(IOException.class,()->invalid.status(Provider.SUBVERSION,root,null));
    }
    @Test void unsafeMutationsRejectBeforeRunnerAndRenameNeverClobbers()throws Exception{
        var calls=new java.util.concurrent.atomic.AtomicInteger();var service=new ScmWorkbenchService((r,c,t)->{calls.incrementAndGet();return new CommandResult(0,"","");});
        assertThrows(IOException.class,()->service.mutate(Provider.MERCURIAL,root,Action.REMOVE,".","",null));assertThrows(IOException.class,()->service.mutate(Provider.SUBVERSION,root,Action.RENAME,"../outside","target",null));
        Files.writeString(root.resolve("target"),"sentinel");assertThrows(IOException.class,()->service.mutate(Provider.MERCURIAL,root,Action.RENAME,"source","target",null));assertEquals(0,calls.get());assertEquals("sentinel",Files.readString(root.resolve("target")));
    }
    @Test void cancellationRetiresTheExistingWorkerAndDoesNotReportSuccess()throws Exception{
        javax.swing.SwingUtilities.invokeAndWait(()->{});
        var jobs=new AsyncJobService();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);var status=new java.util.concurrent.atomic.AtomicReference<AsyncJobService.Status>();
        var service=new ScmWorkbenchService((r,c,token)->{token.onCancel(release::countDown);started.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return new CommandResult(0,"M Source.java\0","");});
        try{int id=jobs.submit("SCM cancellation fixture",token->service.status(Provider.MERCURIAL,root,token),(j,result,error)->{status.set(j.getStatus());assertNull(result);done.countDown();});assertTrue(started.await(2,TimeUnit.SECONDS));jobs.cancel(id);assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(AsyncJobService.Status.CANCELLED,status.get());}finally{release.countDown();jobs.shutdownNow();}
    }
}
