package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BreakpointStoreTest {
    @TempDir Path tempDir;

    @Test
    void persistsWorkspaceBreakpointsWithoutChangingSourceFiles() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path source = workspace.resolve("Main.java");
        Files.writeString(source, "one\ntwo\nthree\n");
        String original = Files.readString(source);
        Path storage = tempDir.resolve("state");
        BreakpointStore store = new BreakpointStore(storage);

        assertTrue(store.toggle(workspace, source, 2).added());
        assertEquals(original, Files.readString(source));
        assertEquals(BreakpointStore.State.REQUESTED, store.markers(workspace, source).get(1).state());

        BreakpointStore reopened = new BreakpointStore(storage);
        assertEquals(List.of(2), reopened.sources(workspace).get(source).stream().map(BreakpointStore.Breakpoint::line).toList());
        assertTrue(Files.list(storage).anyMatch(path -> path.getFileName().toString().startsWith("breakpoints-")));
    }

    @Test
    void persistsAdapterAdjustedAndRejectedLocations() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path source = workspace.resolve("Main.java");
        Files.writeString(source, "one\ntwo\nthree\nfour\nfive\nsix\n");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, source, 3);
        store.toggle(workspace, source, 6);
        List<BreakpointStore.Breakpoint> requested = store.sources(workspace).get(source);

        BreakpointStore.SyncResult result = store.apply(workspace, source, requested, Map.of("breakpoints", List.of(
            Map.of("verified", true, "line", 4),
            Map.of("verified", false, "message", "no executable code")
        )));

        assertEquals(List.of(BreakpointStore.State.CHANGED, BreakpointStore.State.REJECTED), result.breakpoints().stream().map(BreakpointStore.Breakpoint::state).toList());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.contains("moved to line 4")));
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.contains("rejected")));
        Map<Integer, BreakpointStore.Marker> markers = new BreakpointStore(tempDir.resolve("state")).markers(workspace, source);
        assertEquals(BreakpointStore.State.CHANGED, markers.get(3).state());
        assertEquals(BreakpointStore.State.REJECTED, markers.get(5).state());
        assertFalse(Files.readString(source).isBlank());
    }

    @Test
    void adapterRepliesDoNotOverwriteLaterBreakpointEdits() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path source = workspace.resolve("Main.java");
        Files.writeString(source, "one\ntwo\nthree\nfour\nfive\n");
        BreakpointStore store = new BreakpointStore(tempDir.resolve("state"));
        store.toggle(workspace, source, 2);
        List<BreakpointStore.Breakpoint> staleRequest = store.sources(workspace).get(source);
        store.toggle(workspace, source, 4);

        store.apply(workspace, source, staleRequest, Map.of("breakpoints", List.of(Map.of("verified", true))));

        List<BreakpointStore.Breakpoint> current = store.sources(workspace).get(source);
        assertEquals(List.of(2, 4), current.stream().map(BreakpointStore.Breakpoint::line).toList());
        assertEquals(BreakpointStore.State.VERIFIED, current.getFirst().state());
        assertEquals(BreakpointStore.State.REQUESTED, current.getLast().state());
    }

    @Test
    void persistsRichBreakpointSettingsAndRendersDisabledMarkers() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path source = workspace.resolve("Main.java");
        Files.writeString(source, "one\ntwo\nthree\n");
        Path storage = tempDir.resolve("state");
        BreakpointStore store = new BreakpointStore(storage);
        store.toggle(workspace, source, 2);

        BreakpointStore.Breakpoint configured = store.configure(workspace, source, 2, false, "count > 2", "5", "count={count}");
        BreakpointStore.Breakpoint restored = new BreakpointStore(storage).sources(workspace).get(source).getFirst();

        assertFalse(configured.enabled());
        assertEquals("count > 2", restored.condition());
        assertEquals("5", restored.hitCondition());
        assertEquals("count={count}", restored.logMessage());
        assertFalse(new BreakpointStore(storage).markers(workspace, source).get(1).enabled());
    }

    @Test
    void migratesVersionOneBreakpointsWithSafeOptionDefaults() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path source = workspace.resolve("Main.java");
        Files.writeString(source, "one\ntwo\n");
        Path storage = tempDir.resolve("state");
        BreakpointStore store = new BreakpointStore(storage);
        store.toggle(workspace, source, 1);
        Path saved = Files.list(storage).findFirst().orElseThrow();
        Map<String, Object> legacyBreakpoint = new LinkedHashMap<>();
        legacyBreakpoint.put("path", source.toString());
        legacyBreakpoint.put("line", 1);
        legacyBreakpoint.put("state", "VERIFIED");
        legacyBreakpoint.put("actualLine", null);
        legacyBreakpoint.put("message", "");
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("version", 1);
        legacy.put("workspace", workspace.toString());
        legacy.put("breakpoints", List.of(legacyBreakpoint));
        Files.writeString(saved, MiniJson.stringify(legacy));

        BreakpointStore.Breakpoint restored = new BreakpointStore(storage).sources(workspace).get(source).getFirst();

        assertTrue(restored.enabled());
        assertEquals("", restored.condition());
        assertEquals("", restored.hitCondition());
        assertEquals("", restored.logMessage());
    }
}
