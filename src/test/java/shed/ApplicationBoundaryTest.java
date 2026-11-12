package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real use cases, no window or controller construction. */
class ApplicationBoundaryTest {
    @TempDir Path temporary;

    @Test void settingsPersistAndNotifyOnlyAfterSuccessfulValidation() throws Exception {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.toString());
            ConfigManager config = new ConfigManager();
            AtomicInteger notifications = new AtomicInteger();
            Thread caller = Thread.currentThread();
            SettingsApplication settings = new ConfigSettingsApplication(config, () -> {
                assertSame(caller, Thread.currentThread());
                assertEquals(7, config.getTabSize());
                notifications.incrementAndGet();
            });
            assertEquals("Set and saved tab.size", settings.update("tab.size", "7"));
            assertEquals(1, notifications.get());
            assertEquals("7", settings.search("tab.size").stream().filter(d -> d.key().equals("tab.size"))
                .findFirst().orElseThrow().currentValue());
            assertEquals(7, new ConfigManager().getTabSize());
            assertTrue(settings.update("tab.size", "wrong").startsWith("Error:"));
            assertTrue(settings.update("schema_version", "2").startsWith("Error:"));
            assertTrue(settings.update(null, "7").startsWith("Error:"));
            assertEquals(1, notifications.get());
            assertEquals(7, new ConfigManager().getTabSize());
            assertThrows(UnsupportedOperationException.class, () -> settings.search("").clear());
        } finally { System.setProperty("user.home", home); }
    }

    @Test void settingsWriteFailureDoesNotDeliverSuccessfulApplicationCallback() throws Exception {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.toString());
            ConfigManager config = new ConfigManager();
            Path configPath = Path.of(config.getConfigPath());
            Files.createDirectories(configPath.getParent());
            Files.deleteIfExists(configPath);
            Files.createDirectory(configPath);
            AtomicInteger notifications = new AtomicInteger();
            SettingsApplication settings = new ConfigSettingsApplication(config, notifications::incrementAndGet);
            assertTrue(settings.update("tab.size", "7").startsWith("Error saving config:"));
            assertEquals(0, notifications.get());
        } finally { System.setProperty("user.home", home); }
    }

    @Test void fileQueriesPreserveRecentOrderExactColonsAndCurrentLocation() throws Exception {
        Path first = Files.writeString(temporary.resolve("first.java"), "one");
        Path colon = Files.writeString(temporary.resolve("literal-colon.txt"), "two");
        Map<String,String> files = new LinkedHashMap<>();
        files.put("first.java", first.toString());
        files.put("literal:12", colon.toString());
        List<String> opened = new ArrayList<>();
        try (PaletteSession session = new FilePalette(files, List.of(colon.toString()), first.toString(),
                new FuzzyMatchService(), new FilePalette.Opener() {
                    public boolean isOpen(String path) { return false; }
                    public String open(String path, int line, int column, String label) {
                        opened.add(path + "|" + line + "|" + column); return "opened";
                    }
                })) {
            files.clear(); // Query session owns a snapshot, not a live caller collection.
            assertEquals(List.of("literal:12", "first.java"), session.candidates());
            assertEquals(List.of("literal:12"), session.query("literal:12"));
            assertEquals(List.of("first.java"), session.query(":23:4"));
            assertEquals("opened", session.accept("first.java", ":23:4"));
            assertEquals("opened", session.accept("literal:12", "literal:12"));
            assertEquals(List.of(first + "|23|4", colon + "|0|0"), opened);
            assertEquals("File selection is unavailable", session.accept("forged", ""));
        }
    }

    @Test void deletedFilesAreRejectedUnlessStillOpenAndCloseRevokesAcceptance() throws Exception {
        Path file = Files.writeString(temporary.resolve("gone.java"), "text");
        boolean[] open = {false};
        AtomicInteger calls = new AtomicInteger();
        PaletteSession session = new FilePalette(Map.of("gone.java", file.toString()), List.of(), null,
            new FuzzyMatchService(), new FilePalette.Opener() {
                public boolean isOpen(String path) { return open[0]; }
                public String open(String path, int line, int column, String label) { calls.incrementAndGet(); return "opened"; }
            });
        Files.delete(file);
        assertTrue(session.accept("gone.java", "").startsWith("File is no longer available:"));
        assertEquals(0, calls.get());
        open[0] = true;
        assertEquals("opened", session.accept("gone.java", ""));
        session.close();
        session.close();
        assertEquals(List.of(), session.query("gone"));
        assertEquals("File selection is unavailable", session.accept("gone.java", ""));
        assertEquals(1, calls.get());
    }

    @Test void buffersKeepOpaqueIdentityAndQueriesNeverActivate() {
        List<String> activated = new ArrayList<>();
        PaletteSession session = new BufferPalette(List.of(
            new BufferPalette.Item("buffer-a", "same.txt", true, true),
            new BufferPalette.Item("buffer-b", "same.txt", false, false)), new FuzzyMatchService(), id -> {
                activated.add(id); return "activated";
            });
        assertEquals(List.of("1: same.txt [+] (current)", "2: same.txt"), session.candidates());
        assertEquals(2, session.query("same").size());
        assertTrue(activated.isEmpty());
        assertEquals("activated", session.accept("2: same.txt", "same"));
        assertEquals(List.of("buffer-b"), activated);
        assertEquals("Buffer finder cancelled", session.accept("3: injected", ""));
        session.close();
        assertEquals("Buffer finder cancelled", session.accept("1: same.txt [+] (current)", ""));
        assertEquals(List.of(), session.query("same"));
        assertEquals(List.of("buffer-b"), activated);
    }

    @Test void boundaryContractsDoNotExposePresentationTypes() {
        for (Class<?> contract : List.of(SettingsApplication.class, SettingsApplication.Setting.class,
                PaletteSession.class, BufferPalette.Item.class, FilePalette.Opener.class,
                DocumentWorkspace.class, DocumentWorkspace.Document.class, DocumentWorkspace.Id.class,
                EditorSurface.class, EditorSurface.Selection.class, EditorSurface.Viewport.class, EditorSurface.View.class,
                WorkspaceFoldersApplication.class)) {
            for (var method : contract.getDeclaredMethods()) {
                assertNeutral(method.getGenericReturnType().getTypeName());
                for (var parameter : method.getGenericParameterTypes()) assertNeutral(parameter.getTypeName());
            }
        }
    }
    private static void assertNeutral(String name) {
        for (String forbidden : List.of("javax.swing", "java.awt", "Texteditor", "Controller", "FileBuffer", "ConfigManager"))
            assertFalse(name.contains(forbidden), name);
    }
}
