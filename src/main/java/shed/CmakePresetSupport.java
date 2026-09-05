package shed;

import java.nio.file.Files;
import java.nio.file.Path;

/** Small, dependency-free validation shared by task and test-preset entry points. */
final class CmakePresetSupport {
    private CmakePresetSupport() {
    }

    static boolean hasPresetFile(Path root) {
        if (root == null || !Files.isDirectory(root)) return false;
        return Files.isRegularFile(root.resolve("CMakePresets.json"))
            || Files.isRegularFile(root.resolve("CMakeUserPresets.json"));
    }

    static boolean isSafeName(String name) {
        if (name == null || name.isBlank() || name.length() > 256) return false;
        return name.indexOf('\u0000') < 0 && name.indexOf('\n') < 0 && name.indexOf('\r') < 0;
    }
}
