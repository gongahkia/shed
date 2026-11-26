package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Native CLI wire contracts only. No provider or subprocess is started. */
class CliChatProtocolTest {
    private String executable(){return AgentTaskInstructions.cli().getFirst();}
    @Test void codexUsesExplicitNativeThreadAndNeverAnotherLatestConversation()throws Exception{
        var p=CliChatProtocol.Provider.CODEX;
        var first=p.command(executable(),null);assertTrue(first.contains("--json"));assertFalse(first.contains("resume"));
        var second=p.command(executable(),"thread-one");assertTrue(second.containsAll(List.of("resume","thread-one","workspace-write")));assertFalse(second.contains("--last"));assertEquals("-",second.getLast());
        assertThrows(IllegalArgumentException.class,()->p.command(executable(),"--last /outside"));
    }
    @Test void claudeAndOpenCodeKeepNativeConversationIdsAndPermissionDefaults()throws Exception{
        var claude=CliChatProtocol.Provider.CLAUDE.command(executable(),"session-one");assertTrue(claude.containsAll(List.of("--resume","session-one","--include-partial-messages")));assertFalse(claude.contains("--dangerously-skip-permissions"));
        var open=CliChatProtocol.Provider.OPENCODE.command(executable(),"ses_one");assertTrue(open.containsAll(List.of("--standalone","--session","ses_one","json")));assertFalse(open.contains("--auto"));
    }
    @Test void codexChunkedOutputDeduplicatesMessagesAndRetainsThread() {
        var s=new CliChatProtocol.Stream(CliChatProtocol.Provider.CODEX,null);
        String id="{\"type\":\"thread.started\",\"thread_id\":\"thread-one\"}\n";s.accept(id.substring(0,12));s.accept(id.substring(12));
        s.accept("{\"type\":\"item.updated\",\"item\":{\"id\":\"one\",\"type\":\"agent_message\",\"text\":\"Hello\"}}\n");
        s.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"one\",\"type\":\"agent_message\",\"text\":\"Hello world\"}}\n");
        assertEquals("thread-one",s.snapshot().session());assertEquals("Hello world",s.snapshot().text());assertEquals("",s.snapshot().error());
    }
    @Test void claudePartialAndFinalTextAreNotDuplicatedAndSubagentsCannotRetarget() {
        var s=new CliChatProtocol.Stream(CliChatProtocol.Provider.CLAUDE,null);
        s.accept("{\"type\":\"system\",\"session_id\":\"main\"}\n");
        s.accept("{\"type\":\"stream_event\",\"event\":{\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}}\n");
        s.accept("{\"type\":\"assistant\",\"session_id\":\"child\",\"parent_tool_use_id\":\"tool\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Wrong\"}]}}\n");
        s.accept("{\"type\":\"result\",\"session_id\":\"main\",\"result\":\"Hello world\",\"is_error\":false}");s.finish();
        assertEquals("main",s.snapshot().session());assertEquals("Hello world",s.snapshot().text());assertEquals("",s.snapshot().error());
    }
    @Test void openCodeTextEventsAndFailuresAreExplicit() {
        var s=new CliChatProtocol.Stream(CliChatProtocol.Provider.OPENCODE,null);s.accept("{\"type\":\"text\",\"sessionID\":\"ses_one\",\"part\":{\"text\":\"Reply\"}}\n");
        assertEquals("ses_one",s.snapshot().session());assertEquals("Reply",s.snapshot().text());s.accept("{\"type\":\"error\"}\n");assertFalse(s.snapshot().error().isBlank());
    }
    @Test void changedIdentitiesMalformedAndOversizedOutputFailBoundedly() {
        var s=new CliChatProtocol.Stream(CliChatProtocol.Provider.CODEX,"expected");s.accept("{\"type\":\"thread.started\",\"thread_id\":\"other\"}\n");assertEquals("expected",s.snapshot().session());assertFalse(s.snapshot().error().isBlank());
        var bad=new CliChatProtocol.Stream(CliChatProtocol.Provider.CLAUDE,null);bad.accept("{broken}\n");assertFalse(bad.snapshot().error().isBlank());
        var large=new CliChatProtocol.Stream(CliChatProtocol.Provider.CODEX,null);large.accept("{"+"x".repeat(140000)+"\n");assertFalse(large.snapshot().error().isBlank());assertTrue(large.snapshot().text().length()<=65536);
    }
    @Test void eachPromptGetsFreshVisualizationContextWithoutOneShotFinishInstructions() {
        String one=AgentTaskInstructions.chatPrompt("Change the greeting","Codex"),two=AgentTaskInstructions.chatPrompt("Now add a test","Codex");
        for(String text:List.of(one,two)){assertTrue(text.contains("SHED_CONTROL"));assertTrue(text.contains("SHED_DELEGATION"));assertTrue(text.contains("read <project-relative-path>"));assertTrue(text.contains("sync <relative-path>"));assertTrue(text.contains("Do not call session-finish"));assertFalse(text.contains("launcher returns control"));}
        assertTrue(two.contains("Now add a test"));assertFalse(two.contains("Change the greeting"));
    }
    @Test void userMessageCannotTruncateAutomaticContextByQuotingOldInstructions() {
        String message="Finish with a concise summary, and keep this final sentence.";
        String prompt=AgentTaskInstructions.chatPrompt(message,"Codex");
        assertTrue(prompt.contains(message));assertTrue(prompt.contains("SHED_CONTROL"));assertTrue(prompt.contains("Do not call session-finish"));
        assertTrue(AgentTaskInstructions.prompt(message,"Codex").contains("The launcher returns control"));
    }
}
