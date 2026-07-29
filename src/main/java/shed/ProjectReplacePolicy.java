package shed;

import java.nio.file.Path;

record ProjectReplacePolicy(boolean enabled, boolean previewRequired, boolean confirmRequired, boolean backupEnabled,
                            String backupDirectory, String scope) {
    ProjectReplacePolicy {
        if (backupDirectory == null || backupDirectory.isBlank()
            || !"workspace".equals(scope) && !"current-file".equals(scope)) {
            throw new IllegalArgumentException("invalid project replace policy");
        }
    }

    Path backupDirectoryPath() {
        return Path.of(backupDirectory).toAbsolutePath().normalize();
    }
}
