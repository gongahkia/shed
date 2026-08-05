package shed;

import java.nio.file.Path;

record LspServerKey(String extension, Path workspaceRoot) {
    LspServerKey {
        extension = extension == null ? "" : extension;
        workspaceRoot = workspaceRoot == null ? null : workspaceRoot.toAbsolutePath().normalize();
    }

    String displayName() {
        return extension.isBlank() ? "(no ext)" : "." + extension;
    }
}
