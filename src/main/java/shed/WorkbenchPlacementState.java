package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkbenchPlacementState {
    enum Mode {
        DOCKED,
        DETACHED
    }

    record Bounds(int x, int y, int width, int height) {
        Bounds {
            if (width < 160 || height < 120 || width > 8192 || height > 8192) {
                throw new IllegalArgumentException("invalid detached bounds");
            }
        }

        Map<String, Object> toMap() {
            return Map.of("x", x, "y", y, "width", width, "height", height);
        }

        static Bounds fromMap(Map<String, Object> value) {
            if (value == null) return null;
            Integer x = MiniJson.asInt(value.get("x"));
            Integer y = MiniJson.asInt(value.get("y"));
            Integer width = MiniJson.asInt(value.get("width"));
            Integer height = MiniJson.asInt(value.get("height"));
            if (x == null || y == null || width == null || height == null) return null;
            try {
                return new Bounds(x, y, width, height);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    record Entry(String workspaceId, WorkbenchLayout.SurfaceType surface, String instanceId, Mode mode,
                 String ownerWindowId, WorkbenchLayout.Region region, Bounds bounds) {
        Entry {
            if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspace id required");
            if (surface == null || instanceId == null || instanceId.isBlank() || mode == null || ownerWindowId == null || ownerWindowId.isBlank()) {
                throw new IllegalArgumentException("placement identity required");
            }
            if (region == null) throw new IllegalArgumentException("placement region required");
            if (mode == Mode.DETACHED && (region != WorkbenchLayout.Region.DETACHED || bounds == null)) {
                throw new IllegalArgumentException("detached placement requires detached bounds");
            }
            if (mode == Mode.DOCKED && region == WorkbenchLayout.Region.DETACHED) {
                throw new IllegalArgumentException("docked placement cannot use detached region");
            }
        }

        String key() {
            return workspaceId + "\u0000" + surface.name() + "\u0000" + instanceId;
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workspace", workspaceId);
            result.put("surface", surface.name().toLowerCase());
            result.put("instance", instanceId);
            result.put("mode", mode.name().toLowerCase());
            result.put("ownerWindow", ownerWindowId);
            result.put("region", region.name().toLowerCase());
            if (bounds != null) result.put("bounds", bounds.toMap());
            return result;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    Entry put(Entry entry) {
        if (entry == null) throw new IllegalArgumentException("placement required");
        entries.put(entry.key(), entry);
        return entry;
    }

    Entry get(String workspaceId, WorkbenchLayout.SurfaceType surface, String instanceId) {
        if (workspaceId == null || surface == null || instanceId == null) return null;
        return entries.get(workspaceId + "\u0000" + surface.name() + "\u0000" + instanceId);
    }

    Entry remove(String workspaceId, WorkbenchLayout.SurfaceType surface, String instanceId) {
        if (workspaceId == null || surface == null || instanceId == null) return null;
        return entries.remove(workspaceId + "\u0000" + surface.name() + "\u0000" + instanceId);
    }

    List<Entry> entriesForWorkspace(String workspaceId) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) if (entry.workspaceId().equals(workspaceId)) result.add(entry);
        result.sort(Comparator.comparing((Entry entry) -> entry.surface().name()).thenComparing(Entry::instanceId));
        return List.copyOf(result);
    }

    List<Map<String, Object>> toList() {
        List<Entry> ordered = new ArrayList<>(entries.values());
        ordered.sort(Comparator.comparing(Entry::key));
        return ordered.stream().map(Entry::toMap).toList();
    }

    static WorkbenchPlacementState fromList(Object value) {
        WorkbenchPlacementState state = new WorkbenchPlacementState();
        List<Object> values = MiniJson.asArray(value);
        if (values == null) return state;
        for (Object item : values) {
            Entry entry = parse(MiniJson.asObject(item));
            if (entry != null) state.put(entry);
        }
        return state;
    }

    private static Entry parse(Map<String, Object> value) {
        if (value == null) return null;
        String workspace = MiniJson.asString(value.get("workspace"));
        String surface = MiniJson.asString(value.get("surface"));
        String instance = MiniJson.asString(value.get("instance"));
        String mode = MiniJson.asString(value.get("mode"));
        String ownerWindow = MiniJson.asString(value.get("ownerWindow"));
        String region = MiniJson.asString(value.get("region"));
        try {
            return new Entry(workspace, WorkbenchLayout.SurfaceType.valueOf(surface.toUpperCase()), instance,
                Mode.valueOf(mode.toUpperCase()), ownerWindow, WorkbenchLayout.Region.valueOf(region.toUpperCase()),
                Bounds.fromMap(MiniJson.asObject(value.get("bounds"))));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }
}
