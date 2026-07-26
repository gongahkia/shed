import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class VerticalTileLayoutEngineTests: XCTestCase {
    func testLandscapeUsesFullHeightMasterAndHorizontalSlaves() {
        let engine = VerticalTileLayoutEngine(config: VerticalTileLayoutEngine.Config(masterRatio: 0.4))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 1_000, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 300, height: 600), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 700, y: 0, width: 300, height: 600), zOrder: 2)
            ]
        )
    }

    func testPortraitRotatesToFullWidthMasterAndVerticalSlaves() {
        let engine = VerticalTileLayoutEngine(config: VerticalTileLayoutEngine.Config(masterRatio: 0.4))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 600, height: 1_000),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 600, height: 400), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 400, width: 600, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 700, width: 600, height: 300), zOrder: 2)
            ]
        )
    }

    func testForcedOrientationOverridesBoundsShape() {
        let engine = VerticalTileLayoutEngine(
            config: VerticalTileLayoutEngine.Config(masterRatio: 0.5, orientation: .portrait)
        )

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 900, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 300, width: 900, height: 300), zOrder: 1)
            ]
        )
    }

    func testSingleWindowUsesFullBoundsAndEmptyInputStaysEmpty() {
        let engine = VerticalTileLayoutEngine()
        let bounds = CGRect(x: 20, y: 30, width: 700, height: 500)

        XCTAssertEqual(engine.arrange(windows: [], in: bounds, focus: nil), [])
        XCTAssertEqual(engine.arrange(windows: snapshots(1), in: bounds, focus: nil), [
            Placement(windowID: 1, frame: bounds)
        ])
    }

    func testFactoryBuildsEngine() throws {
        let factory = VerticalTileLayoutEngineFactory()
        let config = VerticalTileLayoutEngine.Config(masterRatio: 0.4, orientation: .portrait)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, VerticalTileLayoutEngine.engineID)
        XCTAssertEqual(engine.id, VerticalTileLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
