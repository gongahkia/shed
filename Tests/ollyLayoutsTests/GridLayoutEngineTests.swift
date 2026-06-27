import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class GridLayoutEngineTests: XCTestCase {
    func testSquareishPolicyPacksSortedWindowIDsRowMajor() {
        let engine = GridLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(3, 1, 5, 2, 4),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 300, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 300, y: 0, width: 300, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 600, y: 0, width: 300, height: 300), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 0, y: 300, width: 300, height: 300), zOrder: 3),
                Placement(windowID: 5, frame: CGRect(x: 300, y: 300, width: 300, height: 300), zOrder: 4)
            ]
        )
    }

    func testFixedRowsPolicyKeepsConfiguredRowCount() {
        let engine = GridLayoutEngine(config: GridLayoutEngine.Config(policy: .fixedRows(3)))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3, 4),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 200), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 400, height: 200), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 200, width: 400, height: 200), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 400, y: 200, width: 400, height: 200), zOrder: 3)
            ]
        )
    }

    func testFixedColumnsPolicyKeepsConfiguredColumnCount() {
        let engine = GridLayoutEngine(config: GridLayoutEngine.Config(policy: .fixedCols(2)))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 400, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 300, width: 400, height: 300), zOrder: 2)
            ]
        )
    }

    func testEmptyInputProducesNoPlacements() {
        let placements = GridLayoutEngine().arrange(
            windows: [],
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(placements, [])
    }

    func testFactoryBuildsEngine() throws {
        let factory = GridLayoutEngineFactory()
        let config = GridLayoutEngine.Config(policy: .fixedCols(2))
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, GridLayoutEngine.engineID)
        XCTAssertEqual(engine.id, GridLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
