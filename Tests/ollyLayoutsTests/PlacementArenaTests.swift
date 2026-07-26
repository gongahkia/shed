import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class PlacementArenaTests: XCTestCase {
    func testCollectsOnlyChangedPlacements() {
        var arena = PlacementArena(reservingCapacity: 4)
        let unchanged = Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        let changed = Placement(windowID: 2, frame: CGRect(x: 10, y: 0, width: 100, height: 100))
        let previous = [
            WindowID(1): unchanged,
            WindowID(2): Placement(windowID: 2, frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        ]

        arena.collectChangedPlacements(from: [unchanged, changed], previousPlacementsByWindowID: previous)

        XCTAssertEqual(arena.toArray(), [changed])
        XCTAssertEqual(arena.count, 1)
        XCTAssertFalse(arena.isEmpty)
    }

    func testCollectKeepsCapacityAndClearsOldValues() {
        var arena = PlacementArena(reservingCapacity: 2)
        let first = Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        let second = Placement(windowID: 2, frame: CGRect(x: 20, y: 0, width: 100, height: 100))

        arena.collectChangedPlacements(from: [first, second], previousPlacementsByWindowID: [:])
        arena.collectChangedPlacements(from: [first], previousPlacementsByWindowID: [1: first])

        XCTAssertTrue(arena.isEmpty)
        XCTAssertEqual(arena.toArray(), [])
    }
}
