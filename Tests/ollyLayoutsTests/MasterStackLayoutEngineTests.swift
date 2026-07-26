import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class MasterStackLayoutEngineTests: XCTestCase {
    func testArrangesOneMasterAndSlavesOnRight() {
        let engine = MasterStackLayoutEngine(config: MasterStackLayoutEngine.Config(masterRatio: 0.6))
        let windows = snapshots(1, 2, 3)

        let placements = engine.arrange(windows: windows, in: CGRect(x: 0, y: 0, width: 1_000, height: 900), focus: nil)

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 600, height: 900), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 600, y: 0, width: 400, height: 450), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 600, y: 450, width: 400, height: 450), zOrder: 2)
            ]
        )
    }

    func testArrangesMultipleMastersVertically() {
        let engine = MasterStackLayoutEngine(
            config: MasterStackLayoutEngine.Config(masterRatio: 0.5, masterCount: 2)
        )

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 300, width: 400, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 400, y: 0, width: 400, height: 600), zOrder: 2)
            ]
        )
    }

    func testSingleWindowUsesFullBounds() {
        let engine = MasterStackLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1),
            in: CGRect(x: 10, y: 20, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(placements, [Placement(windowID: 1, frame: CGRect(x: 10, y: 20, width: 800, height: 600))])
    }

    func testSwapMasterOrderPromotesFocusedWindowOrRotates() {
        let engine = MasterStackLayoutEngine()
        let windows = snapshots(1, 2, 3)

        XCTAssertEqual(engine.swapMasterOrder(windows: windows, focus: 3), [3, 1, 2])
        XCTAssertEqual(engine.swapMasterOrder(windows: windows, focus: nil), [2, 3, 1])
    }

    func testFactoryBuildsEngine() throws {
        let factory = MasterStackLayoutEngineFactory()
        let engine = try factory.makeEngine(config: MasterStackLayoutEngine.Config(masterRatio: 0.6, masterCount: 2))

        XCTAssertEqual(factory.id, MasterStackLayoutEngine.engineID)
        XCTAssertEqual(engine.config, MasterStackLayoutEngine.Config(masterRatio: 0.6, masterCount: 2))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
