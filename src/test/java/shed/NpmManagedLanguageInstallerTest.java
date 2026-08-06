package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpmManagedLanguageInstallerTest {
    @TempDir Path tempDir;

    @Test
    void installsExactPackagesInTheManagedCacheWithScriptsDisabled() throws Exception {
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.python();
        ManagedLanguageDistributionCatalog.Distribution distribution = ManagedLanguageDistributionCatalog.forEntry(entry);
        int[] commands = {0};
        NpmManagedLanguageInstaller installer = new NpmManagedLanguageInstaller(ManagedLanguageDistributionCatalog.trust(), tempDir,
            (command, directory, environment, cancellation) -> {
                commands[0]++;
                assertEquals(List.of("npm", "install", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund", "--save-exact",
                    "pyright@1.1.411"), command);
                assertEquals("true", environment.get("NPM_CONFIG_IGNORE_SCRIPTS"));
                assertEquals("false", environment.get("NPM_CONFIG_AUDIT"));
                Path launcher = directory.resolve("node_modules/.bin/pyright-langserver");
                Files.createDirectories(launcher.getParent());
                Files.writeString(launcher, "launcher");
                return new NpmManagedLanguageInstaller.CommandResult(0, "installed", false);
            });

        NpmManagedLanguageInstaller.Result result = installer.install(distribution, entry,
            ManagedLanguageSupportTrust.Platform.MACOS, neverCancelled());

        assertTrue(result.installed(), result.detail());
        assertEquals(1, commands[0]);
        assertEquals(tempDir.resolve("managed-languages/python.pyright/1.1.411/runtime/node_modules/.bin/pyright-langserver"),
            result.command());
        assertTrue(Files.exists(result.command()));
        assertFalse(Files.exists(tempDir.resolve("managed-languages/python.pyright/1.1.411/.shed-npm-")));
    }

    @Test
    void managedServiceRejectsMissingOrReusedGuiApprovalBeforeAnInstall() {
        ManagedLanguageSupportService service = new ManagedLanguageSupportService(
            new LanguageServerDetector(command -> null, command -> new LanguageServerDetector.CommandResult(0, "", ""), null),
            ManagedLanguageDistributionCatalog.trust(), tempDir, ManagedLanguageSupportTrust.Platform.MACOS);
        ManagedLanguageCatalog.Entry entry = ManagedLanguageCatalog.python();

        ManagedLanguageSupportService.InstallResult missing = service.install(entry, null, null);
        ManagedLanguageInstallApproval approval = ManagedLanguageInstallApproval.approvedInLanguageServicesPanel(entry);

        assertFalse(missing.installed());
        assertTrue(missing.detail().contains("fresh approval"));
        assertFalse(Files.exists(tempDir.resolve("managed-languages/python.pyright")));
        assertTrue(approval.consumeFor(entry));
        assertFalse(approval.consumeFor(entry));
    }

    @Test
    void catalogOffersOnlyReviewedNpmPlansAlongsidePinnedJdtls() {
        assertTrue(ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.java()).usesPinnedArchive());
        assertEquals(List.of("pyright@1.1.411"), ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.python()).npmPackages());
        assertEquals(List.of("typescript-language-server@5.3.0", "typescript@6.0.3"),
            ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.typescriptJavascript()).npmPackages());
        assertEquals(List.of("@zed-industries/vscode-langservers-extracted@4.10.8"),
            ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.json()).npmPackages());
        assertEquals(List.of("remark-language-server@3.0.0"),
            ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.markdown()).npmPackages());
        assertEquals(null, ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.go()));
        assertEquals(null, ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.rust()));
        assertEquals(null, ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.cCpp()));
    }

    private static NpmManagedLanguageInstaller.Cancellation neverCancelled() {
        return new NpmManagedLanguageInstaller.Cancellation() {
            @Override public boolean isCancelled() { return false; }
            @Override public void onCancel(Runnable action) { }
        };
    }
}
