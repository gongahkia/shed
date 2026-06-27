import ollyCore
import ollyKit

struct PlacementArena {
    private var storage: ContiguousArray<Placement> = []

    init(reservingCapacity capacity: Int = 0) {
        storage.reserveCapacity(capacity)
    }

    var count: Int {
        storage.count
    }

    var isEmpty: Bool {
        storage.isEmpty
    }

    var placements: ContiguousArray<Placement> {
        storage
    }

    mutating func collectChangedPlacements(
        from placements: [Placement],
        previousPlacementsByWindowID: [WindowID: Placement]
    ) {
        storage.removeAll(keepingCapacity: true)
        storage.reserveCapacity(placements.count)
        for placement in placements where previousPlacementsByWindowID[placement.windowID] != placement {
            storage.append(placement)
        }
    }

    func toArray() -> [Placement] {
        Array(storage)
    }
}
