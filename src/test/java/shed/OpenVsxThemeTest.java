package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenVsxThemeTest {
    @TempDir Path temporary;
    private final String originalHome = System.getProperty("user.home");
    static final OpenVsxThemeService.Extension EXTENSION = new OpenVsxThemeService.Extension("Example", "palette", "1.0.0", "Example Palette", "A theme");
    private static final String ROOT = "https://open-vsx.org/api/Example/palette/1.0.0";
    private static final String MANIFEST = "{\"publisher\":\"Example\",\"name\":\"palette\",\"version\":\"1.0.0\",\"main\":\"./extension.js\",\"contributes\":{\"themes\":[{\"label\":\"Example Dark\",\"uiTheme\":\"vs-dark\",\"path\":\"./themes/dark.json\"}]}}";
    private static final String THEME = """
        { // the archive keeps source syntax; Shed only reads color data
          "colors": {"editor.background":"#123", "editor.foreground":"#eee", "editorCursor.foreground":"#f80",
                     "editor.selectionBackground":"#ffffff80", "statusBar.background":"#456", "sideBar.background":"#234"},
          "tokenColors":[{"scope":"comment, comment.block", "settings":{"foreground":"#789","fontStyle":"italic"}},
                         {"scope":["string","string.quoted"],"settings":{"foreground":"#abc"}},
                         {"scope":"keyword","settings":{"foreground":"#cde"}}],
          "semanticTokenColors":{"variable":"#fff"},
        }
        """;

    @AfterEach void restoreHome() { System.setProperty("user.home", originalHome); }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static byte[] packageBytes() throws IOException {
        return zip(Map.of("extension/package.json", MANIFEST, "extension/themes/dark.json", THEME,
            "extension/LICENSE", "Synthetic MIT license fixture", "extension/extension.js", "throw new Error('never execute');"));
    }

    private ImportedTheme theme() throws IOException {
        return VsCodeThemeImporter.translate(packageBytes(), EXTENSION, "MIT", "https://example.invalid/repository").getFirst();
    }
    private Path home() throws IOException {
        Path home = temporary.toRealPath().resolve("home");
        Files.createDirectories(home);
        System.setProperty("user.home", home.toString());
        return home;
    }

    @Test void mapsBareConstantAndStorageAndPreservesNumericScope() throws Exception {
        String palette = """
            {"tokenColors":[
              {"scope":"constant","settings":{"foreground":"#123456"}},
              {"scope":"storage","settings":{"foreground":"#ABCDEF"}},
              {"scope":"constant.numeric","settings":{"foreground":"#654321"}}
            ]}
            """;
        ImportedTheme theme = VsCodeThemeImporter.translate(zip(Map.of(
            "extension/package.json", MANIFEST, "extension/themes/dark.json", palette)),
            EXTENSION, "MIT", "").getFirst();
        assertAll(
            () -> assertEquals("#123456", theme.colors().get("ui.syntax.constant")),
            () -> assertEquals("#ABCDEF", theme.colors().get("ui.syntax.keyword")),
            () -> assertEquals("#654321", theme.colors().get("ui.syntax.number")));
    }

    @Test void mapsJsoncPaletteSyntaxAndAlphaWithoutExecutingExtension() throws Exception {
        ImportedTheme theme = theme();
        assertEquals("#112233", theme.colors().get("normal"));
        assertEquals("#EEEEEE", theme.colors().get("foreground"));
        assertEquals("#889199", theme.colors().get("ui.selection"));
        assertEquals("#778899", theme.colors().get("ui.syntax.comment"));
        assertEquals("#AABBCC", theme.colors().get("string"));
        assertEquals("#CCDDEE", theme.colors().get("ui.syntax.keyword"));
        assertEquals("#223344", theme.colors().get("theme.surface.elevated"));
        assertEquals("Synthetic MIT license fixture", theme.licenseText());
        assertTrue(theme.limitations().stream().anyMatch(s -> s.contains("Semantic-token")));
        assertFalse(theme.colors().containsKey("main"));
    }

    @Test void importsMultipleVariantsAndRelativeIncludesWithStableIdsAcrossUpdates() throws Exception {
        String manifest = MANIFEST.replace("}]}}", "},{\"label\":\"Example Light\",\"uiTheme\":\"vs\",\"path\":\"./themes/light.json\"}]}}");
        Map<String, String> files = Map.of("extension/package.json", manifest, "extension/themes/dark.json",
            "{\"include\":\"../base.json\",\"colors\":{\"editor.foreground\":\"#ddd\"}}", "extension/base.json", THEME,
            "extension/themes/light.json", "{\"colors\":{\"editor.background\":\"#fff\"}}");
        List<ImportedTheme> themes = VsCodeThemeImporter.translate(zip(files), EXTENSION, "MIT", "");
        assertEquals(2, themes.size());
        assertEquals(theme().id(), themes.getFirst().id());
        assertEquals("#DDDDDD", themes.getFirst().colors().get("foreground"));
        assertEquals("#112233", themes.getFirst().colors().get("normal"));
        assertEquals("#FFFFFF", themes.getLast().colors().get("normal"));
        assertNotEquals(themes.getFirst().id(), themes.getLast().id());
    }

    @Test void rejectsEscapingPathsIncludesCyclesAndUnsupportedTextmateData() throws Exception {
        for (String path : List.of("../outside.json", "/outside.json", "C:/outside.json", "themes\\evil.json")) {
            String manifest = MANIFEST.replace("./themes/dark.json", path.replace("\\", "\\\\"));
            assertThrows(IOException.class, () -> VsCodeThemeImporter.translate(zip(Map.of("extension/package.json", manifest)), EXTENSION, "MIT", ""));
        }
        for (String source : List.of("{\"include\":\"dark.json\"}", "{\"include\":\"../../../outside.json\"}",
            "{\"tokenColors\":\"./theme.tmTheme\"}")) {
            assertThrows(IOException.class, () -> VsCodeThemeImporter.translate(zip(Map.of("extension/package.json", MANIFEST,
                "extension/themes/dark.json", source)), EXTENSION, "MIT", ""));
        }
    }

    @Test void rejectsManifestMismatchInvalidColorsAndOversizedExpandedData() throws Exception {
        assertThrows(IOException.class, () -> VsCodeThemeImporter.translate(zip(Map.of("extension/package.json", MANIFEST.replace("1.0.0", "2.0.0"))), EXTENSION, "MIT", ""));
        assertThrows(IOException.class, () -> VsCodeThemeImporter.translate(zip(Map.of("extension/package.json", MANIFEST,
            "extension/themes/dark.json", "{\"colors\":{\"editor.background\":\"url(file:///private)\"}}")), EXTENSION, "MIT", ""));
        assertThrows(IOException.class, () -> VsCodeThemeImporter.translate(zip(Map.of("extension/package.json", MANIFEST,
            "extension/bomb.bin", "x".repeat(1024 * 1024 + 1))), EXTENSION, "MIT", ""));
        assertThrows(IOException.class, () -> VsCodeThemeImporter.translate(zip(Map.of("../sentinel", "unchanged")), EXTENSION, "MIT", ""));
    }

    @Test void sanitizesRegistryLabelsAndBoundsSearchWithoutNetwork() throws Exception {
        AtomicReference<URI> requested = new AtomicReference<>();
        OpenVsxThemeService service = new OpenVsxThemeService((uri, limit, token) -> {
            requested.set(uri);
            return "{\"extensions\":[{\"namespace\":\"Example\",\"name\":\"palette\",\"version\":\"1.0.0\",\"displayName\":\"<html>test\",\"description\":\"hello\\nworld\"}]}".getBytes(StandardCharsets.UTF_8);
        });
        var entries = service.search("night & light", null);
        assertEquals("Themes", queryValue(requested.get(), "category"));
        assertTrue(requested.get().toString().contains("night+%26+light"));
        assertFalse(entries.getFirst().displayName().contains("<"));
        assertEquals("hello world", entries.getFirst().description());
        assertThrows(IOException.class, () -> service.search("x".repeat(121), null));
    }

    private static String queryValue(URI uri, String name) {
        for (String part : uri.getQuery().split("&")) if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        return null;
    }

    @Test void validatesDownloadIdentityAndChecksumBeforeTranslation() throws Exception {
        byte[] archive = packageBytes();
        OpenVsxThemeService service = fakeService(archive, OpenVsxThemeService.sha256(archive), ROOT + "/file/theme.vsix", "MIT");
        var downloaded = service.download(EXTENSION, null);
        assertEquals(1, downloaded.themes().size());
        assertEquals(OpenVsxThemeService.sha256(archive), downloaded.sha256());
        assertThrows(IOException.class, () -> fakeService(archive, "0".repeat(64), ROOT + "/file/theme.vsix", "MIT").download(EXTENSION, null));
        assertThrows(IOException.class, () -> fakeService(archive, OpenVsxThemeService.sha256(archive), ROOT + "/file/theme.vsix", "").download(EXTENSION, null));
    }

    private static OpenVsxThemeService fakeService(byte[] archive, String checksum, String location, String license) {
        return new OpenVsxThemeService((uri, limit, token) -> {
            if (uri.toString().endsWith(".vsix")) return archive;
            if (uri.toString().endsWith(".sha256")) return checksum.getBytes(StandardCharsets.UTF_8);
            return MiniJson.stringify(Map.of("namespace", "Example", "name", "palette", "version", "1.0.0", "categories", List.of("Themes"),
                "license", license, "files", Map.of("download", location, "sha256", ROOT + "/file/theme.sha256"))).getBytes(StandardCharsets.UTF_8);
        });
    }

    @Test void rejectsUntrustedHostsWrongPackageLocationsAndCredentials() throws Exception {
        for (String uri : List.of("http://open-vsx.org/a", "https://localhost/a", "https://user@open-vsx.org/a", "https://open-vsx.org:443/a", "https://open-vsx.org/a#fragment"))
            assertThrows(IOException.class, () -> OpenVsxThemeService.validateUri(URI.create(uri)));
        OpenVsxThemeService.validateUri(URI.create("https://openvsx.eclipsecontent.org/Example/palette/theme.vsix"));
        for (String location : List.of("https://example.invalid/a.vsix", "https://open-vsx.org/api/Other/palette/1.0.0/file/theme.vsix",
            ROOT + "/file/../theme.vsix", ROOT + "/file/%2e%2e/theme.vsix")) {
            assertThrows(IOException.class, () -> fakeService(packageBytes(), "0".repeat(64), location, "MIT").download(EXTENSION, null));
        }
    }

    @Test void installsOfflineWithoutSavingOrChangingThemeAndPreservesPreferenceOverrides() throws Exception {
        home();
        ConfigManager config = new ConfigManager();
        String font = config.getFontFamily();
        ImportedTheme theme = theme();
        config.replaceImportedThemes(config.importedThemeStore().install(List.of(theme)));
        assertEquals("shed-light", config.getThemeId());
        assertEquals(font, config.getFontFamily());
        assertFalse(Files.exists(Path.of(config.getConfigPath())));
        assertEquals(57, config.getThemeIds().size());
        assertEquals(theme.id(), config.setTheme(theme.id()));
        assertEquals(Color.decode("#CCDDEE"), config.getSyntaxKeywordColor());
        config.set("ui.syntax.keyword", "#102030");
        assertEquals(Color.decode("#102030"), config.getSyntaxKeywordColor());
        config.persistCurrentConfig();
        ConfigManager reopened = new ConfigManager();
        assertEquals(theme.id(), reopened.getThemeId());
        assertEquals(Color.decode("#102030"), reopened.getSyntaxKeywordColor());
        assertEquals(font, reopened.getFontFamily());
        assertTrue(reopened.usesImportedPackage(theme.packageId()));
    }

    @Test void importedPreviewCancelsAndAcceptsThroughExistingLeaseAndRollsBackOnSaveFailure() throws Exception {
        Path home = home();
        ConfigManager config = new ConfigManager();
        config.replaceImportedThemes(config.importedThemeStore().install(List.of(theme())));
        config.setAndPersist("theme", "shed-light");
        byte[] before = Files.readAllBytes(Path.of(config.getConfigPath()));
        var preview = config.previewTheme(theme().id());
        assertNotNull(preview);
        assertEquals(theme().id(), config.getThemeId());
        assertEquals("shed-light", config.getAuthoritativeThemeId());
        assertArrayEquals(before, Files.readAllBytes(Path.of(config.getConfigPath())));
        preview.close();
        assertEquals("shed-light", config.getThemeId());
        preview = config.previewTheme(theme().id());
        preview.acceptAndPersist();
        assertEquals(theme().id(), new ConfigManager().getThemeId());
        config.setAndPersist("theme", "shed-light");
        Files.delete(Path.of(config.getConfigPath()));
        Files.createDirectory(home.resolve(".shed/config.toml"));
        var failing = config.previewTheme(theme().id());
        assertThrows(IOException.class, failing::acceptAndPersist);
        failing.close();
        assertEquals("shed-light", config.getThemeId());
    }

    @Test void packageRemovalPreservesUnrelatedFilesAndOldPaletteInstances() throws Exception {
        Path home = home();
        ConfigManager config = new ConfigManager();
        ImportedTheme theme = theme();
        config.replaceImportedThemes(config.importedThemeStore().install(List.of(theme)));
        Path sentinel = home.resolve("sentinel.txt");
        Files.writeString(sentinel, "keep");
        config.replaceImportedThemes(config.importedThemeStore().remove(theme.packageId()));
        assertEquals(56, config.getThemeIds().size());
        assertNull(config.setTheme(theme.id()));
        assertEquals("shed-light", config.getThemeId());
        assertEquals("keep", Files.readString(sentinel));
        assertEquals(56, new ConfigManager().getThemeIds().size());
    }

    @Test void corruptOrOversizedCatalogFallsBackWithoutOverwritingIt() throws Exception {
        Path home = home();
        Path catalog = home.resolve(".shed/themes/open-vsx.json");
        Files.createDirectories(catalog.getParent());
        for (String text : List.of("{\"version\":99,\"themes\":[]}", "broken", "x".repeat(ImportedThemeStore.MAX_BYTES + 1))) {
            Files.writeString(catalog, text);
            ConfigManager config = new ConfigManager();
            assertEquals("shed-light", config.getThemeId());
            assertFalse(config.importedThemeFailure().isEmpty());
            assertThrows(IOException.class, () -> config.importedThemeStore().install(List.of(theme())));
            assertEquals(text, Files.readString(catalog));
        }
    }

    @Test void refusesCatalogAndDirectorySymlinksWithoutTouchingExternalSentinels() throws Exception {
        Path root = temporary.toRealPath();
        Path outside = root.resolve("outside");
        Files.createDirectory(outside);
        Path sentinel = outside.resolve("open-vsx.json");
        Files.writeString(sentinel, "keep");
        Path shed = root.resolve("shed");
        Files.createDirectory(shed);
        Files.createSymbolicLink(shed.resolve("themes"), outside);
        assertThrows(IOException.class, () -> new ImportedThemeStore(shed).install(List.of(theme())));
        assertEquals("keep", Files.readString(sentinel));
        Files.delete(shed.resolve("themes"));
        Files.createDirectory(shed.resolve("themes"));
        Files.createSymbolicLink(shed.resolve("themes/open-vsx.json"), sentinel);
        assertThrows(IOException.class, () -> new ImportedThemeStore(shed).load());
        assertThrows(IOException.class, () -> new ImportedThemeStore(shed).remove(theme().packageId()));
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test void stableIdentityReplacesPackageInsteadOfAccumulatingVersionsAndDoesNotAffectOtherConfigs() throws Exception {
        home();
        ConfigManager first = new ConfigManager(), second = new ConfigManager();
        ImportedTheme old = theme();
        first.replaceImportedThemes(first.importedThemeStore().install(List.of(old)));
        ImportedTheme update = new ImportedTheme(old.id(), old.name(), old.publisher(), old.extension(), "2.0.0", old.license(),
            old.licenseText(), old.repository(), old.colors(), old.limitations());
        first.replaceImportedThemes(first.importedThemeStore().install(List.of(update)));
        assertEquals(57, first.getThemeIds().size());
        assertEquals("2.0.0", first.importedTheme(old.id()).version());
        assertEquals(56, second.getThemeIds().size());
        assertEquals("2.0.0", new ConfigManager().importedTheme(old.id()).version());
    }

    @Test void protectsSavedImportedDisplayNameAfterTemporaryRuntimeThemeChange() throws Exception {
        Path home = home();
        ImportedTheme theme = theme();
        ConfigManager config = new ConfigManager();
        config.importedThemeStore().install(List.of(theme));
        Files.writeString(home.resolve(".shed/config.toml"), "schema_version = 1\n\"theme\" = \"" + theme.displayName() + "\"\n");
        ConfigManager restored = new ConfigManager();
        assertEquals(theme.id(), restored.getThemeId());
        restored.setTheme("shed-light");
        assertTrue(restored.usesImportedPackage(theme.packageId()), "Saved alias still needs this package on restart");
    }

    @Test void cancelledBackgroundOperationRetiresCallbacksAndDoesNotHoldEdt() throws Exception {
        AsyncJobService jobs = new AsyncJobService();
        CountDownLatch started = new CountDownLatch(1), input = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        int id = jobs.submit("Theme fixture", token -> {
            worker.set(Thread.currentThread()); started.countDown();
            token.onCancel(done::countDown);
            assertTrue(done.await(2, TimeUnit.SECONDS));
            OpenVsxThemeService.checkCancelled(token);
            return "unreachable";
        }, (snapshot, result, error) -> assertNull(result));
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeLater(input::countDown);
            assertTrue(input.await(1, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread(), worker.get());
            assertTrue(jobs.cancel(id));
            assertTrue(done.await(1, TimeUnit.SECONDS));
        } finally { jobs.shutdownNow(); }
    }
}
