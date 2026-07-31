package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BackupSchedulerTest {
    @TempDir
    Path tempDir;

    @Test
    void writesCapturedBackupBeforeSchedulerShutdown() throws Exception {
        Path home = tempDir.resolve("home");
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            ConfigManager config = new ConfigManager();
            Path backupDirectory = tempDir.resolve("backups");
            config.set("backup.enabled", "true");
            config.set("backup.directory", backupDirectory.toString());
            Path source = tempDir.resolve("note.txt");
            Files.writeString(source, "saved", StandardCharsets.UTF_8);
            FileBuffer buffer = new FileBuffer(source.toFile(), config);
            buffer.setContent("pending");

            BackupScheduler scheduler = new BackupScheduler();
            assertTrue(scheduler.submit(buffer, buffer.captureBackupSnapshot(), null));
            scheduler.close();

            assertNotNull(buffer.getBackupFile());
            assertEquals("pending", Files.readString(buffer.getBackupFile().toPath(), StandardCharsets.UTF_8));
        } finally {
            if (originalHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", originalHome);
        }
    }
}
