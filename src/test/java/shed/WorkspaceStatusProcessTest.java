package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** separate production CLI process to production bridge, with a non-desktop status host. */
class WorkspaceStatusProcessTest {
    @TempDir Path temporary;

    @Test void separateCliAttachesAndDisconnectsWithoutChangingSyntheticExternalFiles() throws Exception {
        Path sentinel = Files.writeString(temporary.resolve("external-sentinel"), "synthetic configuration\n");
        byte[] before = Files.readAllBytes(sentinel); var stamp = Files.getLastModifiedTime(sentinel);
        try (var server = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("private"), List.of())) {
            for (int cycle = 0; cycle < 3; cycle++) {
                long started = System.nanoTime();
                Process process = WorkspaceCliTestProcess.start(WorkspaceCliProcessGuardTest.builder("--workspace-api", "--status", "--connect",
                    server.descriptor().toString(), "--credential", server.credential().toString()));
                try {
                    process.getOutputStream().close();
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Bounded CLI did not exit");
                    assertEquals(0, process.exitValue());
                    byte[] output = process.getInputStream().readNBytes(4097); assertTrue(output.length <= 4096);
                    assertEquals(0, process.getErrorStream().readNBytes(4097).length);
                    var response = WorkspaceStatusContractTest.object(new String(output, StandardCharsets.UTF_8));
                    assertEquals("status", response.get("id"));
                    var result = MiniJson.asObject(response.get("result"));
                    assertEquals(WorkspaceStatusContractTest.object(Files.readString(server.descriptor())).get("instanceId"), result.get("instanceId"));
                    assertEquals(List.of("status"), result.get("capabilities"));
                    System.out.println("A1_EXTERNAL_CLIENT " + MiniJson.stringify(Map.of("cycle", cycle, "exit", process.exitValue(),
                        "round_trip_with_jvm_start_ms", (System.nanoTime() - started) / 1e6,
                        "server", "production bridge with status fixture; no desktop", "bytes", output.length)));
                } finally { WorkspaceCliTestProcess.stop(process); }
                WorkspaceStatusTransportTest.await(() -> server.connectionCount() == 0);
            }
        }
        assertArrayEquals(before, Files.readAllBytes(sentinel)); assertEquals(stamp, Files.getLastModifiedTime(sentinel));
    }

    @Test void unauthorizedExternalCliExitsNonzeroWithoutAnIdentityOrPrivatePath() throws Exception {
        try (var server = WorkspaceBridgeServer.status(new ApplicationStatus(true), temporary.resolve("private"), List.of())) {
            byte[] original = Files.readAllBytes(server.credential());
            try {
                var value = WorkspaceStatusContractTest.object(new String(original, StandardCharsets.UTF_8)); value.put("key", "07".repeat(32));
                Files.writeString(server.credential(), MiniJson.stringify(value));
                Process process = WorkspaceCliTestProcess.start(WorkspaceCliProcessGuardTest.builder("--workspace-api", "--status", "--connect",
                    server.descriptor().toString(), "--credential", server.credential().toString()));
                try {
                    process.getOutputStream().close(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(77, process.exitValue());
                    String output = new String(process.getInputStream().readNBytes(4097), StandardCharsets.UTF_8);
                    assertEquals("UNAUTHORIZED", WorkspaceStatusContractTest.code(output));
                    assertFalse(output.contains("instanceId")); assertFalse(output.contains(temporary.toString()));
                    assertEquals(0, process.getErrorStream().readNBytes(4097).length);
                } finally { WorkspaceCliTestProcess.stop(process); }
            } finally { Files.write(server.credential(), original); }
            WorkspaceStatusTransportTest.await(() -> server.connectionCount() == 0);
        }
    }
}
