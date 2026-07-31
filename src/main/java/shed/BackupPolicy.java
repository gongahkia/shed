package shed;

import java.nio.file.Path;

record BackupPolicy(boolean enabled, String directory, int retentionCount, BackupMode mode) {
    static final int DEFAULT_RETENTION_COUNT = 10;
    static final int MAX_RETENTION_COUNT = 100;

    BackupPolicy {
        if (directory == null || directory.isBlank() || retentionCount < 1 || retentionCount > MAX_RETENTION_COUNT || mode == null) {
            throw new IllegalArgumentException("invalid backup policy");
        }
    }

    BackupPolicy(boolean enabled, String directory, int retentionCount) {
        this(enabled, directory, retentionCount, BackupMode.IDLE);
    }

    Path directoryPath() {
        return Path.of(directory).toAbsolutePath().normalize();
    }

    enum BackupMode {
        IDLE,
        SAVE_ONLY;

        static BackupMode parse(String value) {
            if (value == null || value.isBlank()) {
                return IDLE;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "idle" -> IDLE;
                case "save-only", "save_only" -> SAVE_ONLY;
                default -> throw new IllegalArgumentException("backup.mode must be idle or save-only");
            };
        }

        String configValue() {
            return this == SAVE_ONLY ? "save-only" : "idle";
        }
    }
}
