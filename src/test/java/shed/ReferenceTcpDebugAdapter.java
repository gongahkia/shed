package shed;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

/** Small child-process fixture for the spawned loopback DAP transport test. */
public final class ReferenceTcpDebugAdapter {
    private ReferenceTcpDebugAdapter() {
    }

    public static void main(String[] arguments) throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            System.out.println("DAP server listening at: 127.0.0.1:" + server.getLocalPort());
            System.out.flush();
            try (Socket socket = server.accept()) {
                int sequence = 0;
                int launchRequest = 0;
                while (true) {
                    Map<String, Object> request = DebugAdapterTransport.readMessage(socket.getInputStream());
                    if (request == null) return;
                    String command = MiniJson.asString(request.get("command"));
                    int requestSequence = number(request.get("seq"));
                    if (command == null || requestSequence < 1) return;
                    switch (command) {
                        case "initialize" -> sequence = response(socket, ++sequence, requestSequence, command, true,
                            Map.of("supportsConfigurationDoneRequest", true), "");
                        case "launch" -> {
                            launchRequest = requestSequence;
                            sequence = event(socket, ++sequence, "initialized", Map.of());
                        }
                        case "configurationDone" -> {
                            sequence = response(socket, ++sequence, requestSequence, command, true, Map.of(), "");
                            if (launchRequest > 0) {
                                sequence = response(socket, ++sequence, launchRequest, "launch", true, Map.of(), "");
                                launchRequest = 0;
                            }
                        }
                        case "disconnect" -> {
                            response(socket, ++sequence, requestSequence, command, true, Map.of(), "");
                            return;
                        }
                        default -> sequence = response(socket, ++sequence, requestSequence, command, false, Map.of(), "unsupported test command");
                    }
                }
            }
        }
    }

    private static int response(Socket socket, int sequence, int requestSequence, String command, boolean success, Object body, String message)
        throws IOException {
        DebugAdapterTransport.writeMessage(socket.getOutputStream(), Map.of("seq", sequence, "type", "response", "request_seq", requestSequence,
            "success", success, "command", command, "body", body, "message", message));
        return sequence;
    }

    private static int event(Socket socket, int sequence, String event, Object body) throws IOException {
        DebugAdapterTransport.writeMessage(socket.getOutputStream(), Map.of("seq", sequence, "type", "event", "event", event, "body", body));
        return sequence;
    }

    private static int number(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue() || number.longValue() < 1 || number.longValue() > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) number.longValue();
    }
}
