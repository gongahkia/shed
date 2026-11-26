package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class AgentTerminalCommandTest {
    @Test void labelsDescribeTheActionAndKeepHumanHandoffMessagesClear(){
        assertEquals("Codex typing",AgentTerminalCommand.activityLabel("Codex","typing"));
        assertEquals("Codex running",AgentTerminalCommand.activityLabel("Codex","running"));
        assertEquals("Codex waiting for prompt",AgentTerminalCommand.activityLabel("Codex","waiting for prompt"));
        assertEquals("Codex finished (exit 0)",AgentTerminalCommand.activityLabel("Codex","exit 0"));
        assertEquals("Human took terminal control",AgentTerminalCommand.activityLabel("Codex","Human took terminal control"));
    }
    static final class Terminal implements AgentTerminalCommand.Terminal {
        boolean ready=true,connected=true;int releases;String phase="";final List<String> input=new ArrayList<>();
        public boolean ready(){return ready;}public boolean connected(){return connected;}public void send(String text){input.add(text);}public void activity(String value){phase=value;}public void release(){releases++;}
    }
    static void type(AgentTerminalCommand command){long now=System.nanoTime();for(int i=1;i<=200;i++)command.advance(now+i*50_000_000L);}
    @Test void quotesLiteralArgumentsAndRejectsTerminalControlCharacters(){
        assertEquals("'printf' '%s\\n' 'a'\"'\"'b $(touch nope)'",AgentTerminalCommand.quotedLine("/bin/bash",List.of("printf","%s\\n","a'b $(touch nope)")));
        assertEquals("& 'tool.exe' 'a''b'",AgentTerminalCommand.quotedLine("pwsh.exe",List.of("tool.exe","a'b")));
        for(String value:List.of("a\nb","a\rb","a\033b","a\tb"))assertThrows(IllegalArgumentException.class,()->AgentTerminalCommand.quotedLine("bash",List.of("tool",value)));
        assertThrows(IllegalArgumentException.class,()->AgentTerminalCommand.quotedLine("cmd.exe",List.of("tool")));
        assertThrows(IllegalArgumentException.class,()->AgentTerminalCommand.quotedLine("bash",List.of("tool","x".repeat(4096))));
    }
    @Test void typesThroughTheTerminalAndEntersOnceThenUsesReportedExitStatus()throws Exception{
        var ref=new java.util.concurrent.atomic.AtomicReference<AgentTerminalCommand>();var terminal=new Terminal();
        SwingUtilities.invokeAndWait(()->{
            var command=new AgentTerminalCommand(terminal,"'tool' 'a😀e\u0301'",60);ref.set(command);command.start();command.output("prompt ignored");type(command);
            assertEquals("'tool' 'a😀e\u0301'\r",String.join("",terminal.input));assertEquals(1,terminal.input.stream().filter("\r"::equals).count());
            command.output("command output\n\033]1341;shed;finished;7\007");
            command.event(new TerminalShellIntegrationTracker.Event(java.time.Instant.now(),"command","tool"));
            command.event(new TerminalShellIntegrationTracker.Event(java.time.Instant.now(),"finished","7"));
        });
        SwingUtilities.invokeAndWait(()->{});
        var result=ref.get().result().get();assertEquals(7,result.exitCode);assertEquals("command output\n",result.stdout);assertEquals(1,terminal.releases);
    }
    @Test void waitsForPromptAndHumanCancellationNeverSubmitsAPartialCommand()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var terminal=new Terminal();terminal.ready=false;var command=new AgentTerminalCommand(terminal,"'tool'",60);command.start();command.advance(System.nanoTime()+1_000_000_000L);assertTrue(terminal.input.isEmpty());
            terminal.ready=true;command.advance(System.nanoTime()+2_000_000_000L);assertEquals(List.of("'"),terminal.input);
            command.cancel("Human took terminal control");type(command);assertFalse(terminal.input.contains("\r"));assertEquals(1,terminal.input.stream().filter("\003"::equals).count());assertEquals(1,terminal.releases);assertEquals(-1,command.result().join().exitCode);
        });
    }
    @Test void boundsFeedbackAndInterruptsCommandsAtTheirDeadline()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var terminal=new Terminal();var command=new AgentTerminalCommand(terminal,"'tool'",1);command.start();
            long now=System.nanoTime();for(int i=1;i<=7;i++)command.advance(now+i*50_000_000L);
            command.output("x".repeat(20000));command.advance(now+3_000_000_000L);
            var result=command.result().join();assertEquals(-1,result.exitCode);assertTrue(result.stderr.contains("timed out"));assertTrue(result.stdout.length()<8300);assertTrue(result.stdout.contains("truncated"));assertEquals(1,terminal.releases);
        });
    }
    private static boolean ticking(AgentTerminalCommand command)throws Exception{var field=AgentTerminalCommand.class.getDeclaredField("timer");field.setAccessible(true);return ((javax.swing.Timer)field.get(command)).isRunning();}
    @Test void humanHandoffAtTheLastCharacterCannotPressEnterAndLateEventsCannotReviveIt()throws Exception{
        var ref=new java.util.concurrent.atomic.AtomicReference<AgentTerminalCommand>();var terminal=new Terminal();
        SwingUtilities.invokeAndWait(()->{
            String line="'tool'";var command=new AgentTerminalCommand(terminal,line,60);ref.set(command);command.start();long now=System.nanoTime();for(int i=1;i<=line.length();i++)command.advance(now+i*50_000_000L);
            assertEquals(line,String.join("",terminal.input));assertFalse(terminal.input.contains("\r"));command.cancel("Human took terminal control");
            command.event(new TerminalShellIntegrationTracker.Event(java.time.Instant.now(),"command","stale"));command.event(new TerminalShellIntegrationTracker.Event(java.time.Instant.now(),"finished","0"));command.advance(now+2_000_000_000L);
            assertFalse(terminal.input.contains("\r"));assertEquals(1,terminal.releases);assertEquals("Human took terminal control",command.result().join().stderr);
        });
        SwingUtilities.invokeAndWait(()->{try{assertFalse(ticking(ref.get()));}catch(Exception e){throw new AssertionError(e);}});
    }
    @Test void closureWhileWaitingTypingOrRunningStopsExactlyOnce()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            for(int phase=0;phase<3;phase++){
                var terminal=new Terminal();terminal.ready=phase!=0;var command=new AgentTerminalCommand(terminal,"'tool'",60);command.start();long now=System.nanoTime();
                if(phase==1)command.advance(now+100_000_000L);else if(phase==2)type(command);
                terminal.connected=false;command.advance(now+2_000_000_000L);command.close();command.advance(now+3_000_000_000L);
                assertEquals(1,terminal.releases);assertEquals("Terminal closed",command.result().join().stderr);if(phase<2)assertFalse(terminal.input.contains("\r"));
                try{assertFalse(ticking(command));}catch(Exception e){throw new AssertionError(e);}
            }
        });
    }
    @Test void cancellationWinsOverAnAlreadyQueuedCompletionAndLaterCommandsStayIndependent()throws Exception{
        var first=new java.util.concurrent.atomic.AtomicReference<AgentTerminalCommand>();var oldTerminal=new Terminal();
        SwingUtilities.invokeAndWait(()->{
            var command=new AgentTerminalCommand(oldTerminal,"'old'",60);first.set(command);command.start();type(command);command.output("old output");
            command.event(new TerminalShellIntegrationTracker.Event(java.time.Instant.now(),"command","old"));command.event(new TerminalShellIntegrationTracker.Event(java.time.Instant.now(),"finished","0"));command.cancel("Human took terminal control");
        });
        SwingUtilities.invokeAndWait(()->{var nextTerminal=new Terminal();var next=new AgentTerminalCommand(nextTerminal,"'next'",60);next.start();assertEquals("Human took terminal control",first.get().result().join().stderr);assertFalse(next.result().isDone());type(next);assertEquals("'next'\r",String.join("",nextTerminal.input));next.close();assertEquals(1,nextTerminal.releases);assertEquals(1,oldTerminal.releases);});
    }
    @Test void repeatedCloseAndStartAfterRetirementDoNotRestartTimersOrTypeAgain()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            for(int cycle=0;cycle<100;cycle++){
                var terminal=new Terminal();var command=new AgentTerminalCommand(terminal,"'tool'",60);command.start();command.close();int sent=terminal.input.size();command.start();command.close();command.advance(System.nanoTime()+30_000_000_000L);
                assertEquals(sent,terminal.input.size());assertEquals(1,terminal.releases);try{assertFalse(ticking(command),"Retired commands must not restart their timer");}catch(Exception e){throw new AssertionError(e);}
            }
        });
    }
}
