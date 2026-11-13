package shed;

final class ConfigLiveReloadService {
    private final ConfigManager configManager;
    private String fingerprint;

    ConfigLiveReloadService(ConfigManager configManager) {
        this.configManager = configManager;
        this.fingerprint = configManager.configFileFingerprint();
    }

    boolean reloadIfChanged() {
        String current = configManager.configFileFingerprint();
        if (current.equals(fingerprint)) {
            return false;
        }
        fingerprint = current;
        if (configManager.isOwnConfigFileWrite(current)) return false;
        configManager.reload();
        return true;
    }

}
