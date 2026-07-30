package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ManagedLanguageSupportServiceTest {
    @TempDir Path tempDir;

    @Test
    void statusIsInertAndEveryUnavailableToolShowsExplicitActions() {
        int[] probes = {0};
        ManagedLanguageSupportService service = service(command -> null, command -> {
            probes[0]++;
            return new LanguageServerDetector.CommandResult(0, "", "");
        });

        String overview = service.overview();

        assertEquals(0, probes[0]);
        assertTrue(overview.contains("performs no detection, download, update, or network request"));
        assertTrue(overview.contains(":lsp manage detect <ext>"));
        assertTrue(overview.contains(":lsp manage install <ext>"));
        assertTrue(overview.contains(":lsp manage update <ext>"));
        assertTrue(overview.contains(":lsp manage remove <ext>"));
        assertTrue(overview.contains(":lsp manage retry <ext>"));
        assertTrue(overview.contains(":lsp manage manual <ext>"));
        assertTrue(overview.contains("MANAGED_ARTIFACT_UNAVAILABLE"));
    }

    @Test
    void explicitDetectionAndUnavailableManagedInstallProvideManualRemediation() {
        ManagedLanguageSupportService service = service(command -> null,
            command -> new LanguageServerDetector.CommandResult(0, "", ""));
        ManagedLanguageCatalog.Entry pyright = service.entryFor(".py");

        LanguageServerDetector.Result detection = service.detect(pyright);
        String install = service.managedAvailability(pyright, "install");
        String manual = service.manualInstructions(pyright);

        assertEquals(ManagedLanguageCatalog.Availability.EXECUTABLE_MISSING, detection.status().availability());
        assertTrue(service.detectionReport(detection).contains("lsp.py.command"));
        assertTrue(install.contains("No download or update was started."));
        assertTrue(install.contains("lsp.py.command"));
        assertTrue(manual.contains("pyright-langserver"));
        assertTrue(manual.contains(":lsp restart py"));
    }

    @Test
    void explicitRemovalOnlyTouchesManagedCache() throws Exception {
        ManagedLanguageSupportService service = service(command -> null,
            command -> new LanguageServerDetector.CommandResult(0, "", ""));
        Path managed = tempDir.resolve(".shed/managed-languages/python.pyright/1.1.411");
        Path userManaged = tempDir.resolve("user-tools/pyright-langserver");
        Files.createDirectories(managed);
        Files.writeString(managed.resolve("tool.tar.gz"), "managed");
        Files.createDirectories(userManaged.getParent());
        Files.writeString(userManaged, "user-managed");

        ManagedLanguageArtifactStore.Result result = service.remove(service.entryFor("python"));

        assertEquals(ManagedLanguageArtifactStore.Outcome.REMOVED, result.outcome());
        assertFalse(Files.exists(tempDir.resolve(".shed/managed-languages/python.pyright")));
        assertTrue(Files.exists(userManaged));
    }

    @Test
    void mapsSupportedDesktopPlatformNames() {
        assertEquals(ManagedLanguageSupportTrust.Platform.MACOS, ManagedLanguageSupportService.platformFor("Mac OS X"));
        assertEquals(ManagedLanguageSupportTrust.Platform.WINDOWS, ManagedLanguageSupportService.platformFor("Windows 11"));
        assertEquals(ManagedLanguageSupportTrust.Platform.LINUX, ManagedLanguageSupportService.platformFor("Linux"));
        assertEquals(null, ManagedLanguageSupportService.platformFor("Plan 9"));
    }

    private ManagedLanguageSupportService service(LanguageServerDetector.ExecutableResolver resolver,
        LanguageServerDetector.CommandRunner runner) {
        return new ManagedLanguageSupportService(new LanguageServerDetector(resolver, runner, null),
            new ManagedLanguageSupportTrust(List.of(), Set.of()), tempDir.resolve(".shed"), ManagedLanguageSupportTrust.Platform.LINUX);
    }
}
