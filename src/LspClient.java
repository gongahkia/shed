import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LspClient {
    private static final Pattern LABEL_PATTERN = Pattern.compile("\"label\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");

    private final Process process;
    private final BufferedOutputStream stdin;
    private final BlockingQueue<String> messages;
    private int requestId;

    public LspClient(String command, String[] args, Path rootPath) throws IOException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) {
                    commandLine.add(arg);
                }
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
        processBuilder.directory(rootPath.toFile());
        this.process = processBuilder.start();
        this.stdin = new BufferedOutputStream(process.getOutputStream());
        this.messages = new LinkedBlockingQueue<>();
        this.requestId = 0;
        startReaderThread();
        initialize(rootPath);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public void didOpen(String uri, String languageId, String text) {
        sendNotification("textDocument/didOpen",
            "{\"textDocument\":{\"uri\":\"" + escape(uri) + "\",\"languageId\":\"" + escape(languageId) + "\",\"version\":1,\"text\":\"" + escape(text) + "\"}}");
    }

    public void didChange(String uri, int version, String text) {
        sendNotification("textDocument/didChange",
            "{\"textDocument\":{\"uri\":\"" + escape(uri) + "\",\"version\":" + version + "},\"contentChanges\":[{\"text\":\"" + escape(text) + "\"}]}");
    }

    public void didSave(String uri) {
        sendNotification("textDocument/didSave",
            "{\"textDocument\":{\"uri\":\"" + escape(uri) + "\"}}");
    }

    public List<String> completion(String uri, int line, int character) {
        int id = nextRequestId();
        sendRequest(id, "textDocument/completion",
            "{\"textDocument\":{\"uri\":\"" + escape(uri) + "\"},\"position\":{\"line\":" + line + ",\"character\":" + character + "}}");
        String response = waitForResponse(id, 2000L);
        if (response == null) {
            return List.of();
        }
        return parseLabels(response);
    }

    public void stop() {
        try {
            int id = nextRequestId();
            sendRequest(id, "shutdown", "null");
            sendNotification("exit", "null");
        } catch (Exception ignored) {
        }
        process.destroy();
    }

    private void initialize(Path rootPath) throws IOException {
        int id = nextRequestId();
        sendRequest(id, "initialize",
            "{\"processId\":" + ProcessHandle.current().pid()
                + ",\"rootUri\":\"file://" + escape(rootPath.toAbsolutePath().toString()) + "\""
                + ",\"capabilities\":{\"textDocument\":{\"completion\":{\"completionItem\":{\"snippetSupport\":false}}}}}");
        waitForResponse(id, 5000L);
        sendNotification("initialized", "{}");
    }

    private void startReaderThread() {
        Thread reader = new Thread(() -> {
            try (BufferedInputStream stdout = new BufferedInputStream(process.getInputStream())) {
                while (true) {
                    int contentLength = readContentLength(stdout);
                    if (contentLength < 0) {
                        return;
                    }
                    byte[] body = stdout.readNBytes(contentLength);
                    if (body.length != contentLength) {
                        return;
                    }
                    messages.offer(new String(body, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        }, "shed-lsp-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private int readContentLength(BufferedInputStream stream) throws IOException {
        StringBuilder headers = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = stream.read()) != -1) {
            headers.append((char) current);
            if (previous == '\r' && current == '\n' && headers.toString().endsWith("\r\n\r\n")) {
                break;
            }
            previous = current;
        }
        if (headers.length() == 0) {
            return -1;
        }
        for (String line : headers.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        return -1;
    }

    private synchronized int nextRequestId() {
        requestId += 1;
        return requestId;
    }

    private void sendRequest(int id, String method, String params) {
        writeMessage("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
    }

    private void sendNotification(String method, String params) {
        writeMessage("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + "}");
    }

    private synchronized void writeMessage(String body) {
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            stdin.write(("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            stdin.write(payload);
            stdin.flush();
        } catch (IOException ignored) {
        }
    }

    private String waitForResponse(int id, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String message = messages.poll();
            if (message == null) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }
            if (message.contains("\"id\":" + id)) {
                return message;
            }
        }
        return null;
    }

    private List<String> parseLabels(String response) {
        List<String> labels = new ArrayList<>();
        Matcher matcher = LABEL_PATTERN.matcher(response);
        while (matcher.find()) {
            labels.add(unescape(matcher.group(1)));
        }
        return labels;
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
    }

    private static String unescape(String value) {
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }
}
