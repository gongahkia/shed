package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.swing.JList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiDensityTest {
    @TempDir Path directory;
    private String previousHome;

    @BeforeEach void useIsolatedHome() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
    }
    @AfterEach void restoreHome() {
        System.setProperty("user.home", previousHome);
    }

    @Test void defaultPreservesLegacyDimensionsAcrossFontsAndZoom() {
        JList<String> component = new JList<>();
        for (int size : new int[]{9, 13, 24, 48}) for (double zoom : new double[]{0.5, 1, 1.5, 2, 4}) {
            component.setFont(new Font(Font.DIALOG, Font.PLAIN, size));
            var metrics = component.getFontMetrics(component.getFont());
            assertEquals(Math.max(UiZoom.scale(40, zoom), metrics.getHeight() + UiZoom.scale(16, zoom)), UiDensity.DEFAULT.controlHeight(metrics, zoom));
            assertEquals(Math.max(UiZoom.scale(22, zoom), size + UiZoom.scale(8, zoom)), UiDensity.DEFAULT.treeHeight(metrics, zoom));
            assertEquals(Math.max(UiZoom.scale(40, zoom), metrics.getHeight() + UiZoom.scale(12, zoom)), UiDensity.DEFAULT.pickerHeight(component, zoom));
        }
        assertEquals(8, UiDensity.DEFAULT.tabVertical); assertEquals(14, UiDensity.DEFAULT.tabHorizontal);
        assertEquals(220, UiDensity.DEFAULT.editorTabWidth); assertEquals(5, UiDensity.DEFAULT.statusPadding);
        assertEquals(4, UiDensity.DEFAULT.commandPadding);
    }

    @Test void nonDefaultPresetsRespectFontAndExistingInsetFloors() {
        JList<String> component = new JList<>();
        component.setBorder(javax.swing.BorderFactory.createEmptyBorder(9, 0, 9, 0));
        for (int size : new int[]{13, 32, 64}) for (double zoom : new double[]{0.5, 1, 2}) {
            component.setFont(new Font(Font.DIALOG, Font.PLAIN, size));
            var metrics = component.getFontMetrics(component.getFont());
            for (UiDensity density : new UiDensity[]{UiDensity.COMPACT, UiDensity.COMFORTABLE}) {
                assertTrue(density.treeHeight(metrics, zoom) >= metrics.getHeight() + 2);
                assertTrue(density.pickerHeight(component, zoom) >= metrics.getHeight() + 18);
                assertTrue(density.controlHeight(metrics, zoom) >= metrics.getHeight() + UiZoom.scale(16, zoom));
            }
        }
        assertNull(UiDensity.parse("dense")); assertNull(UiDensity.parse(null));
    }

    @Test void preferencePersistsAndResetsWithoutChangingOtherAppearanceChoices() throws Exception {
        ConfigManager config = new ConfigManager();
        assertEquals(UiDensity.DEFAULT, config.getUiDensity());
        config.setAndPersist("theme", "dracula"); config.setAndPersist("font.size", "22");
        config.setAndPersist("ui.font.size", "18"); config.setAndPersist("ui.zoom", "1.3");
        for (String value : new String[]{"compact", "comfortable", "default"}) {
            config.setAndPersist("ui.density", value);
            ConfigManager reopened = new ConfigManager();
            assertEquals(UiDensity.parse(value), reopened.getUiDensity());
            assertEquals("dracula", reopened.getThemeId()); assertEquals(22, reopened.getFontSize());
            assertEquals(18, reopened.getUiFontSize()); assertEquals(1.3, reopened.getUiZoom());
        }
        config.resetAndPersist("ui.density");
        assertEquals(UiDensity.DEFAULT, new ConfigManager().getUiDensity());
        assertFalse(Files.readString(directory.resolve(".shed/config.toml")).contains("ui.density"));
        var descriptor = config.searchTypedSettings("ui.density").getFirst();
        assertEquals("Appearance", descriptor.category()); assertEquals("compact | default | comfortable", descriptor.allowedValues());
        assertNotNull(config.validateSettingValue("ui.density", "dense"));
    }

    @Test void invalidSavedDensityFallsBackWithoutDiscardingValidPreferencesOrRewritingFile() throws Exception {
        Files.createDirectories(directory.resolve(".shed")); Path file = directory.resolve(".shed/config.toml");
        for (String invalid : new String[]{"\"dense\"", "42", "true", "[1, 2]"}) {
            String text = "schema_version = 1\n\"ui.density\" = " + invalid + "\n\"theme\" = \"dracula\"\n\"font.size\" = 22\n";
            Files.writeString(file, text); ConfigManager config = new ConfigManager();
            assertEquals(UiDensity.DEFAULT, config.getUiDensity()); assertEquals("dracula", config.getThemeId());
            assertEquals(22, config.getFontSize()); assertFalse(config.hasConfigLoadFailure());
            assertTrue(config.getConfigLoadReport().contains("ui.density")); assertEquals(text, Files.readString(file));
        }
    }

    @Test void densityCannotBecomeProjectOwnedEvenWithUnsafeProjectSettingsEnabled() {
        ConfigManager config = new ConfigManager();
        assertFalse(config.isProjectConfigKeyAllowed("ui.density"));
        config.set("project.config.allow.unsafe", "true");
        assertFalse(config.isProjectConfigKeyAllowed("ui.density"));
        assertTrue(config.isProjectConfigKeyAllowed("ui.font.size"));
    }

    @Test void failedDensityPersistenceLeavesRuntimeAndCallbacksUnchanged() throws Exception {
        ConfigManager config = new ConfigManager(); config.setAndPersist("ui.density", "comfortable");
        Path file = directory.resolve(".shed/config.toml"); Files.delete(file); Files.createDirectory(file);
        ArrayList<String> callbacks = new ArrayList<>();
        SettingsApplication application = new ConfigSettingsApplication(config, (String key) -> callbacks.add(key));
        assertTrue(application.update("ui.density", "compact").startsWith("Error saving config:"));
        assertEquals(UiDensity.COMFORTABLE, config.getUiDensity()); assertTrue(callbacks.isEmpty());
        assertThrows(java.io.IOException.class, () -> config.resetAndPersist("ui.density"));
        assertEquals(UiDensity.COMFORTABLE, config.getUiDensity());
        Files.delete(file); config.persistCurrentConfig();
        assertEquals(UiDensity.COMFORTABLE, new ConfigManager().getUiDensity());
        assertTrue(application.update("ui.density", "compact").startsWith("Set and saved"));
        assertEquals(java.util.List.of("ui.density"), callbacks);
    }
    @Test void sharedDensityAcknowledgementDoesNotSuppressUnrelatedExternalChanges() throws Exception {
        ConfigManager writer = new ConfigManager(), follower = new ConfigManager();
        ConfigLiveReloadService reload = new ConfigLiveReloadService(follower);
        follower.set("font.size", "19");
        writer.setAndPersist("ui.density", "compact");
        Path file = directory.resolve(".shed/config.toml");
        Files.writeString(file, Files.readString(file) + "\n\"font.size\" = 18\n");
        follower.applyUiDensityFrom(writer);
        assertTrue(reload.reloadIfChanged());
        assertEquals(18, follower.getFontSize());
        assertEquals(UiDensity.COMPACT, follower.getUiDensity());
    }

}
