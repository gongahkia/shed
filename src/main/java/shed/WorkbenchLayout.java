package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkbenchLayout {
    enum SurfaceType {
        TREE,
        TABS,
        EDITOR,
        TERMINAL,
        GIT,
        DEBUGGER,
        TASKS,
        TESTS,
        PROBLEMS,
        REPLACE,
        STATUS
    }

    enum Region {
        CENTER,
        LEADING,
        TRAILING,
        BOTTOM,
        FOOTER,
        DETACHED
    }

    record SurfaceId(String workspaceId, SurfaceType type, String instanceId) {
        SurfaceId {
            if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspace id required");
            if (type == null) throw new IllegalArgumentException("surface type required");
            if (instanceId == null || instanceId.isBlank()) throw new IllegalArgumentException("surface instance id required");
        }
    }

    record HostId(String workspaceId, String hostId) {
        HostId {
            if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspace id required");
            if (hostId == null || hostId.isBlank()) throw new IllegalArgumentException("host id required");
        }
    }

    record Placement(SurfaceId surface, HostId host, Region region) {
        Placement {
            if (surface == null || host == null || region == null) throw new IllegalArgumentException("placement values required");
            if (!surface.workspaceId().equals(host.workspaceId())) throw new IllegalArgumentException("surface and host must share a workspace");
        }
    }

    private final Map<SurfaceId, Placement> bySurface = new LinkedHashMap<>();
    private final Map<HostId, SurfaceId> byHost = new LinkedHashMap<>();

    Placement place(SurfaceId surface, HostId host, Region region) {
        Placement next = new Placement(surface, host, region);
        SurfaceId occupied = byHost.get(host);
        if (occupied != null && !occupied.equals(surface)) {
            throw new IllegalStateException("host already owns " + occupied.type() + ":" + occupied.instanceId());
        }
        Placement previous = bySurface.put(surface, next);
        if (previous != null && !previous.host().equals(host)) byHost.remove(previous.host());
        byHost.put(host, surface);
        return next;
    }

    Placement remove(SurfaceId surface) {
        Placement removed = bySurface.remove(surface);
        if (removed != null) byHost.remove(removed.host());
        return removed;
    }

    Placement placement(SurfaceId surface) {
        return bySurface.get(surface);
    }

    SurfaceId owner(HostId host) {
        return byHost.get(host);
    }

    List<Placement> placements(String workspaceId) {
        List<Placement> placements = new ArrayList<>();
        for (Placement placement : bySurface.values()) {
            if (placement.surface().workspaceId().equals(workspaceId)) placements.add(placement);
        }
        placements.sort(Comparator.comparing((Placement placement) -> placement.surface().type().name())
            .thenComparing(placement -> placement.surface().instanceId()));
        return List.copyOf(placements);
    }
}
