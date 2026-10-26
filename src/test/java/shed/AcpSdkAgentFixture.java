package shed;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.*;
import java.util.List;

/** Independent peer: all wire encoding, request dispatch and cancellation come from the official SDK. */
public final class AcpSdkAgentFixture {
    public static void main(String[] args) {
        if (!Boolean.getBoolean("java.awt.headless")) throw new IllegalStateException("Headless fixture required");
        var agent = AcpAgent.sync(new StdioAcpAgentTransport())
            .newSessionHandler(request -> new NewSessionResponse("sdk-session", null, null))
            .loadSessionHandler(request -> new LoadSessionResponse(null))
            .promptHandler((request, context) -> {
                String prompt = ((TextContent) request.prompt().getFirst()).text();
                if (prompt.startsWith("Edit only the selected code")) {
                    var input = MiniJson.asObject(MiniJson.parseStrict(prompt.substring(prompt.indexOf('\n') + 1)));
                    if ("wait for cancellation".equals(input.get("instruction"))) {
                        var decision = context.client().requestPermission(new RequestPermissionRequest(context.getSessionId(),
                            new ToolCallUpdate("selection-wait", "Read selection context", ToolKind.READ, ToolCallStatus.PENDING),
                            List.of(new PermissionOption("allow", "Allow once", PermissionOptionKind.ALLOW_ONCE),
                                new PermissionOption("deny", "Reject once", PermissionOptionKind.REJECT_ONCE))));
                        if (decision.outcome() instanceof PermissionCancelled) return PromptResponse.cancelled();
                    }
                    context.sendMessage(MiniJson.stringify(java.util.Map.of("replacement", ((String) input.get("selection")).toUpperCase(java.util.Locale.ROOT))));
                }
                else if (prompt.startsWith("Answer the project question below.")) {
                    context.sendMessage("{\"answer\":\"The source handles the request.\",\"locations\":[{\"path\":\"source\",\"line\":1,\"column\":1,\"note\":\"Entry point\"}]}");
                }
                else if (prompt.equals("reply-chunked")) { context.sendMessage("first "); context.sendMessage("second"); }
                else if (prompt.equals("reply-large")) context.sendMessage("x".repeat(65537));
                else if (prompt.equals("read")) context.sendMessage(context.readFile(args[0]));
                else if (prompt.equals("write")) {
                    context.sendMessage("Before write"); context.writeFile(args[0], "agent write\n");
                    context.sendMessage("After write: " + context.readFile(args[0]));
                }
                else if (prompt.equals("permission") || prompt.equals("execute")) {
                    context.sendMessage("Before permission");
                    var decision = context.client().requestPermission(new RequestPermissionRequest(context.getSessionId(),
                        new ToolCallUpdate("tool-1", "Fixture action", prompt.equals("execute") ? ToolKind.EXECUTE : ToolKind.READ, ToolCallStatus.PENDING),
                        List.of(new PermissionOption("allow", "Allow once", PermissionOptionKind.ALLOW_ONCE),
                            new PermissionOption("deny", "Reject once", PermissionOptionKind.REJECT_ONCE))));
                    if (decision.outcome() instanceof PermissionCancelled) return PromptResponse.cancelled();
                    context.sendMessage("Decision: " + ((PermissionSelected) decision.outcome()).optionId());
                } else context.sendMessage("Echo: " + prompt);
                return PromptResponse.endTurn();
            }).build();
        agent.run();
    }
}
