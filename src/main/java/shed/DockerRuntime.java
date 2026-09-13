package shed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The direct-argv Docker CLI boundary shared by text and graphical Docker controls. */
final class DockerRuntime {
    private DockerRuntime() {
    }

    static String run(List<String> command, AsyncJobService.JobToken token) throws Exception {
        Path output = Files.createTempFile("shed-docker-", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            token.onCancel(process::destroyForcibly);
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Docker command timed out after 15 minutes");
            }
            String text = DevContainerRuntime.readCapped(output);
            if (process.exitValue() != 0) throw new IOException(text.isBlank() ? "docker exited " + process.exitValue() : text.strip());
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker command interrupted", error);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    static String concise(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
