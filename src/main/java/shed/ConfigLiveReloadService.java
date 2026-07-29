package shed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ConfigLiveReloadService {
    private final ConfigManager configManager;
    private String fingerprint;

    ConfigLiveReloadService(ConfigManager configManager) {
        this.configManager = configManager;
        this.fingerprint = fingerprint();
    }

    boolean reloadIfChanged() {
        String current = fingerprint();
        if (current.equals(fingerprint)) {
            return false;
        }
        fingerprint = current;
        configManager.reload();
        return true;
    }

    private String fingerprint() {
        Path path = Path.of(configManager.getConfigPath());
        if (!Files.isRegularFile(path)) {
            return "missing";
        }
        try {
            return Files.size(path) + ":" + Files.getLastModifiedTime(path);
        } catch (IOException | SecurityException error) {
            return error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
        }
    }
}
