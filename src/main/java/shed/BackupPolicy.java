package shed;

import java.nio.file.Path;

record BackupPolicy(boolean enabled, String directory, int retentionCount) {
    static final int DEFAULT_RETENTION_COUNT = 10;
    static final int MAX_RETENTION_COUNT = 100;

    BackupPolicy {
        if (directory == null || directory.isBlank() || retentionCount < 1 || retentionCount > MAX_RETENTION_COUNT) {
            throw new IllegalArgumentException("invalid backup policy");
        }
    }

    Path directoryPath() {
        return Path.of(directory).toAbsolutePath().normalize();
    }
}
