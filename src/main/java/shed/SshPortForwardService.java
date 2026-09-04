package shed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Owns explicit, loopback-only SSH local forwards for connected SSH workspaces. */
final class SshPortForwardService implements AutoCloseable {
    private static final int STARTUP_WAIT_MILLIS = 350;
    private static final int OUTPUT_LIMIT_BYTES = 16 * 1024;
    private final Map<Integer, Forward> forwards = new LinkedHashMap<>();

    record Spec(int localPort, String remoteHost, int remotePort) {
        Spec {
            requirePort(localPort, "local port");
            requirePort(remotePort, "remote port");
            if (remoteHost == null || !remoteHost.matches("[A-Za-z0-9][A-Za-z0-9.-]*")) {
                throw new IllegalArgumentException("remote host must be a DNS name, IPv4 address, or localhost");
            }
        }
    }

    record ForwardInfo(String connectionId, int localPort, String remoteHost, int remotePort, boolean active, String detail) {
        ForwardInfo {
            connectionId = connectionId == null ? "" : connectionId;
            remoteHost = remoteHost == null ? "" : remoteHost;
            detail = detail == null ? "" : detail;
        }
    }

    synchronized ForwardInfo start(String connectionId, URI endpoint, Spec spec) throws IOException {
        if (connectionId == null || connectionId.isBlank()) throw new IOException("SSH workspace connection is required");
        if (spec == null) throw new IOException("SSH forwarding specification is required");
        Forward existing = forwards.get(spec.localPort());
        if (existing != null && existing.process().isAlive()) {
            throw new IOException("local port " + spec.localPort() + " is already forwarded by " + existing.connectionId());
        }
        if (existing != null) forwards.remove(spec.localPort());

        List<String> command = invocation(endpoint, spec);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (SecurityException error) {
            throw new IOException("SSH forwarding could not start", error);
        }
        OutputCapture output = new OutputCapture(process.getInputStream());
        try {
            if (process.waitFor(STARTUP_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                output.await(STARTUP_WAIT_MILLIS);
                throw new IOException(failureDetail(process.exitValue(), output.text()));
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("SSH forwarding interrupted", error);
        }
        if (!process.isAlive()) {
            output.await(STARTUP_WAIT_MILLIS);
            throw new IOException(failureDetail(process.exitValue(), output.text()));
        }
        Forward forward = new Forward(connectionId.trim(), spec, process, output);
        forwards.put(spec.localPort(), forward);
        return info(forward);
    }

    synchronized boolean close(int localPort) {
        Forward forward = forwards.remove(localPort);
        if (forward == null) return false;
        stop(forward.process());
        return true;
    }

    synchronized void closeForConnection(String connectionId) {
        String normalized = connectionId == null ? "" : connectionId.trim();
        List<Forward> matching = forwards.values().stream()
            .filter(forward -> forward.connectionId().equals(normalized))
            .toList();
        for (Forward forward : matching) forwards.remove(forward.spec().localPort());
        for (Forward forward : matching) stopAsync(forward.process());
    }

    synchronized List<ForwardInfo> list() {
        return forwards.values().stream().map(this::info).sorted(Comparator.comparingInt(ForwardInfo::localPort)).toList();
    }

    @Override
    public synchronized void close() {
        for (Integer port : List.copyOf(forwards.keySet())) close(port);
    }

    static List<String> invocation(URI endpoint, Spec spec) throws IOException {
        if (spec == null) throw new IOException("SSH forwarding specification is required");
        if (endpoint == null || !"ssh".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IOException("SSH forwarding requires an SSH workspace URI");
        }
        String host = endpoint.getHost();
        String user = endpoint.getUserInfo();
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]*")) {
            throw new IOException("SSH forwarding requires a DNS host name");
        }
        if (user != null && !user.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("SSH forwarding requires a simple SSH user name");
        }
        if (endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
            throw new IOException("SSH forwarding URI port is invalid");
        }
        List<String> command = new ArrayList<>(List.of("ssh", "-N", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "ExitOnForwardFailure=yes",
            "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3", "-L",
            "127.0.0.1:" + spec.localPort() + ":" + spec.remoteHost() + ":" + spec.remotePort()));
        if (endpoint.getPort() > 0) {
            command.add("-p");
            command.add(Integer.toString(endpoint.getPort()));
        }
        command.add(user == null || user.isBlank() ? host : user + "@" + host);
        return List.copyOf(command);
    }

    private ForwardInfo info(Forward forward) {
        boolean active = forward.process().isAlive();
        String detail = active ? "running" : failureDetail(exitCode(forward.process()), forward.output().text());
        return new ForwardInfo(forward.connectionId(), forward.spec().localPort(), forward.spec().remoteHost(), forward.spec().remotePort(), active, detail);
    }

    private static void stop(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void stopAsync(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        Thread.ofVirtual().name("shed-ssh-forward-stop").start(() -> stop(process));
    }

    private static int exitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException error) {
            return -1;
        }
    }

    private static String failureDetail(int exitCode, String output) {
        String detail = output == null ? "" : output.strip();
        return detail.isEmpty() ? "SSH forwarding exited " + exitCode : "SSH forwarding exited " + exitCode + ": " + detail;
    }

    private static void requirePort(int port, String label) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException(label + " must be between 1 and 65535");
    }

    private record Forward(String connectionId, Spec spec, Process process, OutputCapture output) {
    }

    private static final class OutputCapture {
        private final StringBuilder text = new StringBuilder();
        private final Thread reader;
        private int bytesCaptured;

        OutputCapture(InputStream input) {
            reader = new Thread(() -> read(input), "shed-ssh-forward-output");
            reader.setDaemon(true);
            reader.start();
        }

        synchronized String text() {
            return text.toString();
        }

        void await(long timeoutMillis) {
            try {
                reader.join(Math.max(1L, timeoutMillis));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        private void read(InputStream input) {
            try (input) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    append(buffer, read);
                }
            } catch (IOException ignored) {
                // The process lifecycle owns this diagnostic stream.
            }
        }

        private synchronized void append(byte[] bytes, int length) {
            int remaining = OUTPUT_LIMIT_BYTES - bytesCaptured;
            if (remaining <= 0) return;
            String value = new String(bytes, 0, Math.min(length, remaining), StandardCharsets.UTF_8);
            text.append(value);
            bytesCaptured += Math.min(length, remaining);
            if (length > remaining) text.append("\n[shed: output truncated]\n");
        }
    }
}
