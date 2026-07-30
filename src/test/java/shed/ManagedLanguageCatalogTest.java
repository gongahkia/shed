package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ManagedLanguageCatalogTest {
    @Test
    void javaEntryCoversDesktopPlatformsAndKnownJavaExtension() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.java();

        assertEquals(entry, ManagedLanguageCatalog.forExtension("java"));
        assertEquals(entry, ManagedLanguageCatalog.forExtension(".JAVA"));
        assertEquals(ManagedLanguageCatalog.python(), ManagedLanguageCatalog.forExtension("py"));
        assertNull(ManagedLanguageCatalog.forExtension("rs"));
        assertEquals("jdtls", entry.commandFor(ManagedLanguageSupportTrust.Platform.MACOS));
        assertEquals("jdtls", entry.commandFor(ManagedLanguageSupportTrust.Platform.LINUX));
        assertEquals("jdtls.bat", entry.commandFor(ManagedLanguageSupportTrust.Platform.WINDOWS));
        assertEquals("java.eclipse-jdtls@1.50.0", entry.installMetadata().coordinate().displayName());
        assertEquals("Eclipse Public License 2.0", entry.installMetadata().licenseName());
        assertEquals("21", entry.installMetadata().minimumRuntimeVersion());
        assertEquals(3, entry.installMetadata().supportedPlatforms().size());
    }

    @Test
    void userManagedJavaStatesIdentifyExecutableAndRuntimeRemediation() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.java();

        ManagedLanguageCatalog.Status missing = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.MACOS, null);
        ManagedLanguageCatalog.Status unknown = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.MACOS,
            new ManagedLanguageCatalog.ToolDetection("jdtls", "unknown"));
        ManagedLanguageCatalog.Status old = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.MACOS,
            new ManagedLanguageCatalog.ToolDetection("jdtls", "17.0.15"));
        ManagedLanguageCatalog.Status valid = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.MACOS,
            new ManagedLanguageCatalog.ToolDetection("jdtls", "openjdk version \"21.0.7\""));

        assertEquals(ManagedLanguageCatalog.Availability.EXECUTABLE_MISSING, missing.availability());
        assertTrue(missing.remediation().contains("lsp.java.command"));
        assertEquals(ManagedLanguageCatalog.Availability.RUNTIME_VERSION_UNKNOWN, unknown.availability());
        assertEquals(ManagedLanguageCatalog.Availability.RUNTIME_VERSION_UNSUPPORTED, old.availability());
        assertTrue(old.detail().contains("detected Java 17"));
        assertEquals(ManagedLanguageCatalog.Availability.AVAILABLE, valid.availability());
        assertTrue(valid.usable());
        assertEquals(8, ManagedLanguageCatalog.javaMajor("1.8.0_402"));
        assertEquals(21, ManagedLanguageCatalog.javaMajor("21.0.7"));
        assertNull(ManagedLanguageCatalog.javaMajor("not a Java version"));
    }

    @Test
    void managedJavaInstallStaysBlockedUntilExplicitConsentAndTrustedArtifact() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.java();
        ManagedLanguageSupportTrust trust = trustWith(entry.installMetadata().coordinate());

        ManagedLanguageCatalog.Status consent = entry.assessManagedInstall(trust,
            ManagedLanguageSupportTrust.Platform.WINDOWS, false);
        ManagedLanguageCatalog.Status ready = entry.assessManagedInstall(trust,
            ManagedLanguageSupportTrust.Platform.WINDOWS, true);
        ManagedLanguageCatalog.Status unavailable = entry.assessManagedInstall(
            new ManagedLanguageSupportTrust(List.of(), Set.of()), ManagedLanguageSupportTrust.Platform.WINDOWS, true);

        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_CONSENT_REQUIRED, consent.availability());
        assertFalse(consent.permitsManagedInstall());
        assertNotNull(consent.trustAssessment());
        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_INSTALL_READY, ready.availability());
        assertTrue(ready.permitsManagedInstall());
        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_ARTIFACT_UNAVAILABLE, unavailable.availability());
        assertFalse(unavailable.permitsManagedInstall());
        assertTrue(unavailable.remediation().contains("lsp.java.command"));
    }

    @Test
    void pythonEntryValidatesNodeRuntimeAndUsesCrossPlatformCommands() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.python();

        ManagedLanguageCatalog.Status missing = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.LINUX, null);
        ManagedLanguageCatalog.Status old = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.LINUX,
            new ManagedLanguageCatalog.ToolDetection("pyright-langserver", "v12.22.12"));
        ManagedLanguageCatalog.Status valid = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.LINUX,
            new ManagedLanguageCatalog.ToolDetection("pyright-langserver", "v20.19.1"));

        assertEquals(entry, ManagedLanguageCatalog.forExtension("py"));
        assertEquals("pyright-langserver", entry.commandFor(ManagedLanguageSupportTrust.Platform.MACOS));
        assertEquals("pyright-langserver.cmd", entry.commandFor(ManagedLanguageSupportTrust.Platform.WINDOWS));
        assertEquals("python.pyright@1.1.411", entry.installMetadata().coordinate().displayName());
        assertEquals("Node.js", entry.installMetadata().runtimeName());
        assertEquals("14", entry.installMetadata().minimumRuntimeVersion());
        assertEquals(ManagedLanguageCatalog.Availability.EXECUTABLE_MISSING, missing.availability());
        assertEquals(ManagedLanguageCatalog.Availability.RUNTIME_VERSION_UNSUPPORTED, old.availability());
        assertTrue(old.detail().contains("Node.js 12"));
        assertEquals(ManagedLanguageCatalog.Availability.AVAILABLE, valid.availability());
        assertTrue(valid.usable());
    }

    @Test
    void managedPythonInstallRequiresConsentAndTrustedArtifact() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.python();
        ManagedLanguageSupportTrust trust = trustWith(entry.installMetadata().coordinate());

        ManagedLanguageCatalog.Status consent = entry.assessManagedInstall(trust,
            ManagedLanguageSupportTrust.Platform.MACOS, false);
        ManagedLanguageCatalog.Status ready = entry.assessManagedInstall(trust,
            ManagedLanguageSupportTrust.Platform.MACOS, true);

        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_CONSENT_REQUIRED, consent.availability());
        assertFalse(consent.permitsManagedInstall());
        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_INSTALL_READY, ready.availability());
        assertTrue(ready.permitsManagedInstall());
        assertTrue(ready.detail().contains("Node.js 14+"));
    }

    @Test
    void typescriptJavaScriptEntryValidatesNodePatchVersionsAndExtensions() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.typescriptJavascript();

        ManagedLanguageCatalog.Status tooOld = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.MACOS,
            new ManagedLanguageCatalog.ToolDetection("typescript-language-server", "v22.22.1"));
        ManagedLanguageCatalog.Status valid = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.MACOS,
            new ManagedLanguageCatalog.ToolDetection("typescript-language-server", "v22.22.2"));

        assertEquals(entry, ManagedLanguageCatalog.forExtension("js"));
        assertEquals(entry, ManagedLanguageCatalog.forExtension("jsx"));
        assertEquals(entry, ManagedLanguageCatalog.forExtension("ts"));
        assertEquals(entry, ManagedLanguageCatalog.forExtension("tsx"));
        assertEquals("typescript-language-server", entry.commandFor(ManagedLanguageSupportTrust.Platform.LINUX));
        assertEquals("typescript-language-server.cmd", entry.commandFor(ManagedLanguageSupportTrust.Platform.WINDOWS));
        assertEquals("typescript.typescript-language-server@5.3.0", entry.installMetadata().coordinate().displayName());
        assertEquals("22.22.2", entry.installMetadata().minimumRuntimeVersion());
        assertEquals(ManagedLanguageCatalog.Availability.RUNTIME_VERSION_UNSUPPORTED, tooOld.availability());
        assertTrue(tooOld.detail().contains("Node.js 22.22.1"));
        assertEquals(ManagedLanguageCatalog.Availability.AVAILABLE, valid.availability());
        assertTrue(valid.usable());
        assertEquals(new ManagedLanguageCatalog.RuntimeVersion(22, 22, 2),
            ManagedLanguageCatalog.parseRuntimeVersion("v22.22.2"));
    }

    @Test
    void managedTypeScriptJavaScriptInstallRequiresConsentAndTrustedArtifact() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.typescriptJavascript();
        ManagedLanguageSupportTrust trust = trustWith(entry.installMetadata().coordinate());

        ManagedLanguageCatalog.Status consent = entry.assessManagedInstall(trust,
            ManagedLanguageSupportTrust.Platform.LINUX, false);
        ManagedLanguageCatalog.Status ready = entry.assessManagedInstall(trust,
            ManagedLanguageSupportTrust.Platform.LINUX, true);

        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_CONSENT_REQUIRED, consent.availability());
        assertFalse(consent.permitsManagedInstall());
        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_INSTALL_READY, ready.availability());
        assertTrue(ready.permitsManagedInstall());
    }

    @Test
    void goEntryUsesGoVersionSemanticsAndCrossPlatformCommands() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.go();

        ManagedLanguageCatalog.Status old = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.LINUX,
            new ManagedLanguageCatalog.ToolDetection("gopls", "go version go1.20.14 linux/amd64"));
        ManagedLanguageCatalog.Status valid = entry.assessUserManaged(ManagedLanguageSupportTrust.Platform.LINUX,
            new ManagedLanguageCatalog.ToolDetection("gopls", "go version go1.21.6 linux/amd64"));

        assertEquals(entry, ManagedLanguageCatalog.forExtension("go"));
        assertEquals("gopls", entry.commandFor(ManagedLanguageSupportTrust.Platform.MACOS));
        assertEquals("gopls.exe", entry.commandFor(ManagedLanguageSupportTrust.Platform.WINDOWS));
        assertEquals("go.gopls@0.23.0", entry.installMetadata().coordinate().displayName());
        assertEquals("Go", entry.installMetadata().runtimeName());
        assertEquals("1.21", entry.installMetadata().minimumRuntimeVersion());
        assertEquals(ManagedLanguageCatalog.RuntimeVersionScheme.STANDARD, entry.installMetadata().runtimeVersionScheme());
        assertEquals(ManagedLanguageCatalog.Availability.RUNTIME_VERSION_UNSUPPORTED, old.availability());
        assertTrue(old.detail().contains("Go 1.20.14"));
        assertEquals(ManagedLanguageCatalog.Availability.AVAILABLE, valid.availability());
        assertEquals(new ManagedLanguageCatalog.RuntimeVersion(1, 21, 6),
            ManagedLanguageCatalog.parseRuntimeVersion("go version go1.21.6 linux/amd64"));
        assertEquals(new ManagedLanguageCatalog.RuntimeVersion(8, 0, 402),
            ManagedLanguageCatalog.parseRuntimeVersion("1.8.0_402", ManagedLanguageCatalog.RuntimeVersionScheme.JAVA_LEGACY));
    }

    @Test
    void managedGoInstallRequiresConsentAndTrustedArtifact() {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.go();
        ManagedLanguageSupportTrust trust = trustWith(entry.installMetadata().coordinate());

        ManagedLanguageCatalog.Status consent = entry.assessManagedInstall(trust,
            ManagedLanguageSupportTrust.Platform.WINDOWS, false);
        ManagedLanguageCatalog.Status ready = entry.assessManagedInstall(trust,
            ManagedLanguageSupportTrust.Platform.WINDOWS, true);

        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_CONSENT_REQUIRED, consent.availability());
        assertFalse(consent.permitsManagedInstall());
        assertEquals(ManagedLanguageCatalog.Availability.MANAGED_INSTALL_READY, ready.availability());
        assertTrue(ready.permitsManagedInstall());
    }

    private ManagedLanguageSupportTrust trustWith(ManagedLanguageSupportTrust.ArtifactCoordinate coordinate) {
        ManagedLanguageSupportTrust.CatalogArtifact artifact = new ManagedLanguageSupportTrust.CatalogArtifact(
            coordinate,
            URI.create("https://downloads.example.invalid/eclipse-jdtls-1.50.0.tar.gz"),
            "a".repeat(64),
            "shed-release-key-2026",
            "base64-detached-signature",
            Set.of(
                ManagedLanguageSupportTrust.Platform.MACOS,
                ManagedLanguageSupportTrust.Platform.WINDOWS,
                ManagedLanguageSupportTrust.Platform.LINUX
            )
        );
        return new ManagedLanguageSupportTrust(List.of(artifact), Set.of());
    }
}
