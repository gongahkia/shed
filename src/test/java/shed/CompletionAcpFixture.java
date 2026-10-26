package shed;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;

/** Deterministic official-SDK peer. No model, inference runtime, network or user files. */
public final class CompletionAcpFixture {
    public static void main(String[] args) {
        var agent = AcpAgent.sync(new StdioAcpAgentTransport())
            .newSessionHandler(request -> new NewSessionResponse(UUID.randomUUID().toString(), null, null))
            .promptHandler((request, context) -> {
                String prompt = ((TextContent) request.prompt().getFirst()).text();
                var data = MiniJson.asObject(MiniJson.parseStrict(prompt.substring(prompt.indexOf('\n') + 1)));
                String prefix = (String) data.get("prefix");
                if (args.length > 1) try {
                    Files.writeString(Path.of(args[1]), MiniJson.stringify(Map.of("session", context.getSessionId(), "context", data,
                        "pid", ProcessHandle.current().pid())) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception error) { throw new RuntimeException(error); }
                if (prefix.equals("stall")) {
                    try { Thread.sleep(60000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                if (prefix.equals("write")) context.writeFile("/not-allowed", "unwanted edit");
                if (prefix.equals("read")) context.readFile("/not-allowed");
                if (prefix.equals("permission")) {
                    var decision = context.client().requestPermission(new RequestPermissionRequest(context.getSessionId(),
                        new ToolCallUpdate("tool", "Read files", ToolKind.READ, ToolCallStatus.PENDING),
                        java.util.List.of(new PermissionOption("allow", "Allow", PermissionOptionKind.ALLOW_ONCE))));
                    if (decision.outcome() instanceof PermissionCancelled) return PromptResponse.cancelled();
                    throw new IllegalStateException("Completion must never grant permissions");
                }
                if (prefix.equals("invalid")) { context.sendMessage("not a valid suggestion"); return PromptResponse.endTurn(); }
                if (prefix.equals("incomplete")) { context.sendMessage("{\"completion\":\"unfinished\"}"); return new PromptResponse(StopReason.MAX_TOKENS); }
                String text = args[0].equals("multiline") ? "a + b\n\t+ anotherValueWithALongName + moreValues + moreValues + moreValues\n\t+ finalValue" : "a + b";
                if (prefix.equals("oversized")) text = "x".repeat(4097);
                context.sendMessage(MiniJson.stringify(Map.of("completion", text))); return PromptResponse.endTurn();
            }).build();
        agent.run();
    }
}
