package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** In-process Swing/chat doubles only: no CLI, shell, real account or native window. */
class AgentCliChatPanelTest {
    @Test void emptyChatHasNoWelcomeInstructions()throws Exception{
        var jobs=new AsyncJobService(10);var host=new Host(temporary);var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{throw new AssertionError("No turn requested");},()->new Font(Font.DIALOG,0,13)));
        try{ui(()->{var empty=(JPanel)find(panel.component(),"agent.chat.empty");assertNotNull(empty);assertEquals(0,empty.getComponentCount());return null;});}
        finally{ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
    @TempDir Path temporary;
    static class Host implements AgentCliChatPanel.Host {
        Path root;boolean live;Runnable ended=()->{};int done;
        Host(Path root){this.root=root;}
        public Path root(){return root;}
        public void prepare(String name,String message,JComponent surface,java.util.function.Consumer<AgentCliChatPanel.Attachment> ready,Runnable end){live=true;ended=end;ready.accept(new AgentCliChatPanel.Attachment(root,root.resolve("descriptor"),"grant"));}
        public boolean current(AgentCliChatPanel.Attachment a){return live;}
        public void done(AgentCliChatPanel.Attachment a){done++;}
        public void stop(){if(live){live=false;ended.run();}}
        public void changes(){}
    }
    static Component find(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container p)for(Component child:p.getComponents()){var v=find(child,name);if(v!=null)return v;}return null;}
    static <T>T ui(Callable<T> call)throws Exception{return EditorControlTest.edt(call);}
    static void await(java.util.function.BooleanSupplier ready)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!ui(ready::getAsBoolean)){if(System.nanoTime()>until)throw new AssertionError("Chat condition timed out");Thread.sleep(20);}}
    static javax.swing.text.JTextComponent text(AgentCliChatPanel panel,String name){return (javax.swing.text.JTextComponent)find(panel.component(),"agent.chat."+name);}
    static JButton button(AgentCliChatPanel panel,String name){return (JButton)find(panel.component(),"agent.chat."+name);}
    static void send(AgentCliChatPanel panel,String message)throws Exception{ui(()->{text(panel,"composer").setText(message);button(panel,"send").doClick();return null;});}
    static void javaExecutable(AgentCliChatPanel panel)throws Exception{ui(()->{((JTextField)find(panel.component(),"agent.chat.executable")).setText(AgentTaskInstructions.cli().getFirst());return null;});}
    @Test void selectedCodeIsRemovableBoundedSnapshotContextForOneTurnOnly()throws Exception{
        var jobs=new AsyncJobService(20);var host=new Host(temporary);var prompts=new CopyOnWriteArrayList<String>();
        var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{prompts.add(prompt);chunk.accept("{\"type\":\"thread.started\",\"thread_id\":\"native\"}\n{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Done\"}}\n");return new ManagedAgentTask.Result(0,"");},()->new Font(Font.DIALOG,Font.PLAIN,13)));
        var context=new AgentCliChatPanel.SelectionContext(temporary,"Demo.java",3,2,9,"a\n👩‍💻");
        try{javaExecutable(panel);ui(()->{assertTrue(panel.addSelection(context));assertTrue(prompts.isEmpty());((JButton)find(panel.component(),"agent.chat.removeContext")).doClick();assertNull(find(panel.component(),"agent.chat.removeContext"));assertTrue(panel.addSelection(context));assertFalse(panel.addSelection(new AgentCliChatPanel.SelectionContext(temporary.resolve("other"),"Other.java",0,0,1,"x")));return null;});
            send(panel,"Explain this");await(()->button(panel,"send").isEnabled());assertTrue(prompts.getFirst().contains("\"documentRevision\":3"));assertTrue(prompts.getFirst().contains("\"text\":\"a\\n👩‍💻\""));assertTrue(prompts.getFirst().contains("\"endOffset\":9"));
            ui(()->{assertNull(find(panel.component(),"agent.chat.removeContext"));return null;});send(panel,"Continue");await(()->host.done==2);assertFalse(prompts.getLast().contains("Human-attached source"));
            assertThrows(IllegalArgumentException.class,()->new AgentCliChatPanel.SelectionContext(temporary,"Demo.java",0,0,8193,"x".repeat(8193)));
        }finally{ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
    @Test void twoMessagesResumeNativeSessionAndInjectVisualizationEveryTime()throws Exception{
        var jobs=new AsyncJobService(20);var host=new Host(temporary);var commands=new CopyOnWriteArrayList<List<String>>();var prompts=new CopyOnWriteArrayList<String>();
        var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{
            assertFalse(SwingUtilities.isEventDispatchThread());commands.add(command);prompts.add(prompt);
            chunk.accept("{\"type\":\"thread.started\",\"thread_id\":\"native-one\"}\n");chunk.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"reply\",\"type\":\"agent_message\",\"text\":\"Reply "+commands.size()+"\"}}\n");return new ManagedAgentTask.Result(0,"");
        },()->new Font(Font.DIALOG,Font.PLAIN,13)));
        try{
            javaExecutable(panel);send(panel,"First task");await(()->text(panel,"transcript").getText().contains("Reply 1")&&button(panel,"send").isEnabled());
            send(panel,"Follow up");await(()->text(panel,"transcript").getText().contains("Reply 2")&&button(panel,"send").isEnabled());
            assertFalse(commands.getFirst().contains("resume"));assertTrue(commands.getLast().containsAll(List.of("resume","native-one")));
            assertTrue(prompts.stream().allMatch(p->p.contains("read <project-relative-path>")&&p.contains("SHED_CONTROL")));assertTrue(prompts.getLast().contains("Follow up"));assertFalse(prompts.getLast().contains("First task"));
            ui(()->{String transcript=text(panel,"transcript").getText();assertTrue(transcript.contains("First task"));assertTrue(transcript.contains("Follow up"));assertFalse(transcript.contains("SHED_CONTROL"));assertEquals(2,host.done);button(panel,"new").doClick();assertTrue(text(panel,"transcript").getText().isEmpty());return null;});
        }finally{ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
    @Test void stopKeepsDraftDropsLateReplyAndReleasesPendingTurn()throws Exception{
        var jobs=new AsyncJobService(20);var host=new Host(temporary);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{started.countDown();release.await(3,TimeUnit.SECONDS);chunk.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Late reply\"}}\n");return new ManagedAgentTask.Result(0,"");},()->new Font(Font.DIALOG,Font.PLAIN,13)));
        try{javaExecutable(panel);send(panel,"Wait");assertTrue(started.await(2,TimeUnit.SECONDS));ui(()->{text(panel,"composer").setText("Next draft");assertTrue(text(panel,"composer").isEditable());button(panel,"stop").doClick();return null;});release.countDown();await(()->button(panel,"send").isEnabled());
            ui(()->{assertEquals("Next draft",text(panel,"composer").getText());assertFalse(text(panel,"transcript").getText().contains("Late reply"));return null;});
        }finally{release.countDown();ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
    @Test void draftTypedWhileWaitingForApprovalSurvivesTurnStart()throws Exception{
        var jobs=new AsyncJobService(20);var ready=new AtomicReference<java.util.function.Consumer<AgentCliChatPanel.Attachment>>();
        var host=new Host(temporary){@Override public void prepare(String name,String message,JComponent surface,java.util.function.Consumer<AgentCliChatPanel.Attachment> callback,Runnable end){live=true;ended=end;ready.set(callback);}};
        var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{chunk.accept("{\"type\":\"thread.started\",\"thread_id\":\"native\"}\n{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Done\"}}\n");return new ManagedAgentTask.Result(0,"");},()->new Font(Font.DIALOG,Font.PLAIN,13)));
        try{javaExecutable(panel);send(panel,"First message");ui(()->{text(panel,"composer").setText("Next draft");ready.get().accept(new AgentCliChatPanel.Attachment(temporary,temporary.resolve("descriptor"),"grant"));return null;});await(()->button(panel,"send").isEnabled());ui(()->{assertEquals("Next draft",text(panel,"composer").getText());return null;});}
        finally{ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
    @Test void missingNativeIdentityCannotSilentlyStartUnrelatedFollowup()throws Exception{
        var jobs=new AsyncJobService(20);var host=new Host(temporary);
        var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{chunk.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"No ID\"}}\n");return new ManagedAgentTask.Result(0,"");},()->new Font(Font.DIALOG,Font.PLAIN,13)));
        try{javaExecutable(panel);send(panel,"Task");await(()->text(panel,"transcript").getText().contains("No ID")&&!button(panel,"stop").isEnabled());ui(()->{assertFalse(button(panel,"send").isEnabled());button(panel,"new").doClick();assertTrue(button(panel,"send").isEnabled());return null;});}
        finally{ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
    @Test void changingProjectsDoesNotResumeOldConversationInAnotherRoot()throws Exception{
        var jobs=new AsyncJobService(20);var host=new Host(temporary);var count=new AtomicInteger();
        var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{count.incrementAndGet();chunk.accept("{\"type\":\"thread.started\",\"thread_id\":\"native\"}\n{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Done\"}}\n");return new ManagedAgentTask.Result(0,"");},()->new Font(Font.DIALOG,Font.PLAIN,13)));
        try{javaExecutable(panel);send(panel,"Task");await(()->button(panel,"send").isEnabled());ui(()->{host.root=temporary.resolve("other");return null;});send(panel,"Wrong project");assertEquals(1,count.get());ui(()->{assertEquals("Wrong project",text(panel,"composer").getText());return null;});}
        finally{ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
    @Test void failedPreparationReleasesOwnershipWithoutDiscardingUnsentMessage()throws Exception{
        var jobs=new AsyncJobService(20);var host=new Host(temporary){@Override public void prepare(String name,String message,JComponent surface,java.util.function.Consumer<AgentCliChatPanel.Attachment> ready,Runnable end){live=true;ended=end;throw new IllegalStateException("Permission unavailable");}};
        var runs=new AtomicInteger();var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{runs.incrementAndGet();return new ManagedAgentTask.Result(0,"");},()->new Font(Font.DIALOG,Font.PLAIN,13)));
        try{javaExecutable(panel);send(panel,"Keep this request");ui(()->{assertFalse(host.live);assertTrue(button(panel,"send").isEnabled());assertEquals("Keep this request",text(panel,"composer").getText());assertEquals("Permission unavailable",((JTextArea)find(panel.component(),"agent.chat.status")).getText());return null;});assertEquals(0,runs.get());}
        finally{ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
    @Test void malformedReplyReportsProtocolFailureAndStillReturnsSourceOwnership()throws Exception{
        var jobs=new AsyncJobService(20);var host=new Host(temporary);
        var panel=ui(()->new AgentCliChatPanel(jobs,host,(command,a,prompt,live,chunk)->{chunk.accept("{broken}\n");throw new CancellationException();},()->new Font(Font.DIALOG,Font.PLAIN,13)));
        try{javaExecutable(panel);send(panel,"Task");await(()->host.done==1);ui(()->{assertTrue(((JTextArea)find(panel.component(),"agent.chat.status")).getText().contains("malformed JSON"));assertFalse(button(panel,"stop").isEnabled());return null;});}
        finally{ui(()->{panel.close();return null;});jobs.shutdownNow();}
    }
}
