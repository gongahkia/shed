package shed;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Types one approved argv into a normal shell; completion comes from its integration events. */
final class AgentTerminalCommand implements AutoCloseable {
    interface Terminal {
        boolean ready();
        boolean connected();
        void send(String text);
        void activity(String phase);
        void release();
        default void pulse() { }
    }
    private final Terminal terminal;
    private final String line;
    private final int timeoutSeconds;
    private final Timer timer;
    private final CompletableFuture<CommandResult> result=new CompletableFuture<>();
    private final StringBuilder output=new StringBuilder();
    private final long opened=System.nanoTime();
    private long entered,lastTyped;
    private int offset;
    private volatile boolean executing,finished;
    private boolean commandSeen,truncated;

    static String activityLabel(String agent,String phase){
        return switch(phase){
            case "typing","running","waiting for prompt"->agent+" "+phase;
            default->phase.startsWith("exit ")?agent+" finished ("+phase+")":phase;
        };
    }

    static String quotedLine(String shell,List<String> argv){
        String name=java.nio.file.Path.of(shell.replace('\\','/')).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        boolean powershell=name.equals("pwsh")||name.equals("pwsh.exe")||name.equals("powershell")||name.equals("powershell.exe");
        if(!powershell&&!List.of("bash","zsh","fish").contains(name))throw new IllegalArgumentException("Agent terminal commands require Bash, Zsh, Fish or PowerShell shell integration");
        if(argv==null||argv.isEmpty()||argv.getFirst().isBlank())throw new IllegalArgumentException("Command argv is required");
        for(String value:argv)if(value==null||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Terminal command arguments cannot contain control characters");
        String line=powershell?"& "+argv.stream().map(value->"'"+value.replace("'","''")+"'").collect(java.util.stream.Collectors.joining(" ")):ShellCommand.posixQuotedCommand(argv);
        if(line.length()>4096)throw new IllegalArgumentException("Terminal command exceeds 4096 characters");
        return line;
    }
    AgentTerminalCommand(Terminal terminal,String line,int timeoutSeconds){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Terminal command belongs on the EDT");
        if(timeoutSeconds<1||timeoutSeconds>600)throw new IllegalArgumentException("Invalid command deadline");
        this.terminal=terminal;this.line=line;this.timeoutSeconds=timeoutSeconds;
        timer=new Timer(16,event->advance(System.nanoTime()));timer.setCoalesce(true);
    }
    CompletableFuture<CommandResult> start(){if(!finished){terminal.activity("waiting for prompt");timer.start();}return result;}
    CompletableFuture<CommandResult> result(){return result;}
    void advance(long now){
        if(finished)return;terminal.pulse();
        if(!terminal.connected()){finish(-1,"Terminal closed");return;}
        if(executing){if(now-entered>timeoutSeconds*1_000_000_000L)cancel("Command timed out");return;}
        if(now-opened>15_000_000_000L){cancel("Shell did not become ready for input");return;}
        if(!terminal.ready())return;
        if(offset<line.length()){
            if(now-lastTyped<Math.max(5,Math.min(45,2000/Math.max(1,line.length())))*1_000_000L)return;
            terminal.activity("typing");int end=GraphemeBoundary.next(line,offset);terminal.send(line.substring(offset,end));offset=end;lastTyped=now;
        }else{executing=true;entered=now;terminal.activity("running");terminal.send("\r");}
    }
    synchronized void output(String text){
        if(!executing||finished)return;
        int remaining=8192-output.length();if(remaining<=0){truncated=true;return;}
        int end=GraphemeBoundary.floor(text,Math.min(text.length(),remaining));output.append(text,0,end);if(end<text.length())truncated=true;
    }
    void event(TerminalShellIntegrationTracker.Event event){
        if(!executing||finished)return;
        SwingUtilities.invokeLater(()->{
            if(!executing||finished)return;
            if(event.type().equals("command"))commandSeen=true;
            else if(commandSeen&&event.type().equals("finished"))finish(Integer.parseInt(event.value()),"");
        });
    }
    void cancel(String reason){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->cancel(reason));return;}
        if(finished)return;
        terminal.send("\003");finish(-1,reason);
    }
    private void finish(int code,String error){
        if(finished)return;finished=true;timer.stop();terminal.activity(error.isEmpty()?"exit "+code:error);terminal.release();
        String text;synchronized(this){text=output.toString();if(truncated)text+="\n[shed: output truncated]";}
        text=text.replaceAll("\\x1b\\]1341;shed;[^\\x07]*\\x07","");
        result.complete(new CommandResult(code,text,error));
    }
    @Override public void close(){cancel("Agent terminal command cancelled");}
}
