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
}
