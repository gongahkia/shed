package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ImportedDebugProfileStoreTest {
    @Test
    void persistsAnExplicitLaunchProfileSnapshotPerWorkspace(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        ImportedDebugProfileStore store = new ImportedDebugProfileStore(temporaryDirectory.resolve("state"));
        DebugAdapterRegistry.Configuration profile = new DebugAdapterRegistry.Configuration("imported:vscode:Run app", "python-debugpy",
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "${file}", "", "", "${workspaceFolder}", List.of("--port", "8000"),
            "build", "127.0.0.1", 0, List.of(), Map.of("APP_MODE", "development"), Map.of("type", "python", "justMyCode", true));

        assertEquals(1, store.replace(workspace, Map.of(profile.name(), profile)));
        ImportedDebugProfileStore.Report loaded = new ImportedDebugProfileStore(temporaryDirectory.resolve("state")).profiles(workspace);

        assertTrue(loaded.usable());
        assertEquals(profile, loaded.profiles().get(profile.name()));
        assertTrue(store.clear(workspace));
        assertTrue(store.profiles(workspace).profiles().isEmpty());
    }

    @Test
    void refusesProfilesThatCouldIntroduceNativeDebugInputSemantics(@TempDir Path temporaryDirectory) {
        ImportedDebugProfileStore store = new ImportedDebugProfileStore(temporaryDirectory.resolve("state"));
        DebugAdapterRegistry.Configuration profile = new DebugAdapterRegistry.Configuration("imported:vscode:Run app", "python-debugpy",
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "${file}", "", "", "${workspaceFolder}", List.of("${input:target}"),
            "", "127.0.0.1", 0, List.of(), Map.of(), Map.of(), Map.of("target", new DebugAdapterRegistry.Input("target", "staging", List.of())));

        assertThrows(IllegalArgumentException.class, () -> store.replace(temporaryDirectory.resolve("workspace"), Map.of(profile.name(), profile)));
        assertFalse(store.profiles(temporaryDirectory.resolve("workspace")).profiles().containsKey(profile.name()));
    }

    @Test
    void reportsCorruptPrivateStorageWithoutReturningProfiles(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path state = temporaryDirectory.resolve("state");
        ImportedDebugProfileStore store = new ImportedDebugProfileStore(state);
        DebugAdapterRegistry.Configuration profile = new DebugAdapterRegistry.Configuration("imported:vscode:Run app", "python-debugpy",
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "${file}", "", "", "${workspaceFolder}", List.of(),
            "", "127.0.0.1", 0, List.of(), Map.of(), Map.of());
        store.replace(workspace, Map.of(profile.name(), profile));
        Path storage;
        try (var files = Files.list(state)) { storage = files.findFirst().orElseThrow(); }
        Files.writeString(storage, "{not-json}", StandardCharsets.UTF_8);

        ImportedDebugProfileStore.Report report = new ImportedDebugProfileStore(state).profiles(workspace);

        assertFalse(report.usable());
        assertTrue(report.profiles().isEmpty());
    }
}
