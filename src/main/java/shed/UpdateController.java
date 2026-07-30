package shed;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import javax.swing.JOptionPane;

final class UpdateController {
    private record CheckResult(UpdateMetadataVerifier.Metadata metadata, UpdateMetadataVerifier.Asset asset, boolean available) { }

    private final Texteditor editor;
    private final UpdateMetadataTransport transport;
    private UpdateMetadataVerifier.Metadata lastTrusted;
    private String lastCheck;
    private int activeJobId;

    UpdateController(Texteditor editor) {
        this(editor, new UpdateMetadataTransport());
    }

    UpdateController(Texteditor editor, UpdateMetadataTransport transport) {
        this.editor = editor;
        this.transport = transport;
        this.lastTrusted = null;
        this.lastCheck = "No update metadata check has run.";
        this.activeJobId = -1;
    }

    void startOnLaunch() {
        if (editor.configManager.getUpdatesEnabled()) startCheck(true);
    }

    String handle(String argument) {
        String subcommand = argument == null || argument.isBlank() ? "status" : argument.trim().toLowerCase();
        return switch (subcommand) {
            case "status" -> status();
            case "consent", "enable" -> requestConsent();
            case "disable" -> revokeConsent();
            case "check" -> startCheck(false);
            case "open" -> openVerifiedAsset();
            case "rollback" -> rollback();
            default -> "Usage: :update [status|consent|disable|check|open|rollback]";
        };
    }

    private String requestConsent() {
        if (editor.configManager.getUpdatesEnabled()) return "Automatic update checks are already enabled; use :update disable to revoke consent.";
        String review = "Enable automatic update metadata checks?\n\n"
            + "Shed will request only the configured HTTPS metadata endpoint after this consent.\n"
            + "Metadata must verify against the configured Ed25519 public key before any update is shown.\n"
            + "Shed does not download, install, or replace application files.\n"
            + "You can revoke consent at any time with :update disable or the settings GUI.";
        int choice = JOptionPane.showConfirmDialog(editor, review, "Automatic Update Consent", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return "Automatic update consent not granted.";
        try {
            editor.configManager.setAndPersist("updates.consent.granted", "true");
            editor.configManager.setAndPersist("updates.enabled", "true");
            return startCheck(true);
        } catch (IOException error) {
            return "Unable to persist automatic update consent: " + error.getMessage();
        }
    }

    private String revokeConsent() {
        if (activeJobId > 0) editor.asyncJobService.cancel(activeJobId);
        activeJobId = -1;
        try {
            editor.configManager.setAndPersist("updates.enabled", "false");
            editor.configManager.setAndPersist("updates.consent.granted", "false");
            lastCheck = "Consent revoked; no future update metadata requests are permitted.";
            return "Automatic update checks disabled and consent revoked.";
        } catch (IOException error) {
            return "Unable to revoke automatic update consent: " + error.getMessage();
        }
    }

    private String startCheck(boolean automatic) {
        if (!editor.configManager.getUpdatesEnabled()) return "Automatic update checks require explicit consent; run :update consent first.";
        final URI endpoint;
        final String publicKey;
        final int timeoutMillis;
        final String installedVersion;
        final String platform;
        try {
            endpoint = UpdateMetadataVerifier.endpoint(editor.configManager.getUpdateMetadataUrl());
            publicKey = editor.configManager.getUpdateMetadataPublicKey();
            UpdateMetadataVerifier.validatePublicKey(publicKey);
            timeoutMillis = editor.configManager.getUpdateCheckTimeoutMs();
            installedVersion = installedVersion();
            UpdateMetadataVerifier.isNewer(installedVersion, installedVersion);
            platform = UpdateMetadataVerifier.currentPlatform(System.getProperty("os.name"), System.getProperty("os.arch"));
            if (platform.isBlank()) throw new IllegalArgumentException("this platform has no supported update installer");
        } catch (IllegalArgumentException error) {
            lastCheck = "No request sent: " + error.getMessage();
            return lastCheck;
        }
        activeJobId = editor.asyncJobService.submit("Signed update metadata check", token -> {
            if (!editor.configManager.getUpdatesEnabled()) throw new InterruptedException("consent was revoked before the request");
            UpdateMetadataTransport.Response response = transport.fetch(endpoint, timeoutMillis, token);
            if (!editor.configManager.getUpdatesEnabled()) throw new InterruptedException("consent was revoked during the request");
            UpdateMetadataVerifier.Metadata metadata = UpdateMetadataVerifier.verify(response.body(), response.signature(), publicKey);
            UpdateMetadataVerifier.Asset asset = metadata.asset(platform);
            if (asset == null) throw new IllegalArgumentException("verified metadata has no installer for " + platform);
            return new CheckResult(metadata, asset, UpdateMetadataVerifier.isNewer(metadata.version(), installedVersion));
        }, (job, result, error) -> completeCheck(job, result, error));
        lastCheck = "Update metadata check is running as job " + activeJobId + ".";
        return automatic ? "Automatic update metadata check started as job " + activeJobId + "." : lastCheck;
    }

    private void completeCheck(AsyncJobService.JobSnapshot job, CheckResult result, Exception error) {
        if (job.getId() == activeJobId) activeJobId = -1;
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
            lastCheck = "Update metadata check cancelled.";
            return;
        }
        if (error != null || result == null) {
            lastCheck = "Update metadata check failed; the installed application was not changed: "
                + (error == null ? job.getErrorMessage() : error.getMessage());
            editor.showMessage("Update metadata check failed; run :update status for details");
            return;
        }
        lastTrusted = result.metadata();
        if (result.available()) {
            lastCheck = "Verified update " + result.metadata().version() + " is available for " + result.asset().platform() + ".";
            editor.showMessage("Verified update " + result.metadata().version() + " is available; run :update open");
        } else {
            lastCheck = "Installed version is current after signed metadata verification.";
            editor.showMessage("Update metadata check completed; installed version is current");
        }
    }

    private String status() {
        UpdateConsent.State consent = editor.configManager.getUpdateConsent();
        String endpoint = editor.configManager.getUpdateMetadataUrl().isBlank() ? "not configured" : "configured";
        String key = editor.configManager.getUpdateMetadataPublicKey().isBlank() ? "not configured" : "configured";
        String trusted = lastTrusted == null ? "none" : lastTrusted.version();
        String report = "Update status\n\nConsent: " + consent.detail() + "\nMetadata endpoint: " + endpoint + "\nPublic key: " + key
            + "\nLast trusted metadata: " + trusted + "\nLast check: " + lastCheck
            + "\nInstaller handling: manual browser handoff only; Shed never replaces its running files.";
        editor.showScratchBuffer("[update status]", report);
        return "Showing update status";
    }

    private String openVerifiedAsset() {
        if (!editor.configManager.getUpdatesEnabled()) return "Automatic update checks require explicit consent; run :update consent first.";
        if (lastTrusted == null) return "No verified update metadata is available; run :update check first.";
        String platform = UpdateMetadataVerifier.currentPlatform(System.getProperty("os.name"), System.getProperty("os.arch"));
        UpdateMetadataVerifier.Asset asset = lastTrusted.asset(platform);
        if (asset == null) return "No verified installer is available for this platform.";
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return "Browser handoff is unavailable; verify and open the release URL manually: " + asset.url();
        }
        try {
            Desktop.getDesktop().browse(asset.url());
            return "Opened verified installer URL in the system browser. Verify its SHA-256 before installation: " + asset.sha256();
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            return "Unable to open the verified installer URL: " + error.getMessage();
        }
    }

    private String rollback() {
        return "No updater-managed installation exists to roll back; Shed never downloads or replaces application files. The running version remains "
            + installedVersion() + ".";
    }

    private String installedVersion() {
        String version = BuildInfo.current().version();
        if (version != null) return version;
        throw new IllegalArgumentException("installed build version is unavailable");
    }
}
