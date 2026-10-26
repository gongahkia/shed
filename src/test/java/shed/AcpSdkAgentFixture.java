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
                if (prompt.equals("read")) context.sendMessage(context.readFile(args[0]));
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
