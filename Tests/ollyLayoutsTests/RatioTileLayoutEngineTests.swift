import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class RatioTileLayoutEngineTests: XCTestCase {
    func testPacksCurrentWindowSizesAndHonorsConstraints() {
        let engine = RatioTileLayoutEngine(config: RatioTileLayoutEngine.Config(
            constraintsByWindowID: [
                1: RatioTileSizeConstraint(minSize: CGSize(width: 150, height: 100)),
                2: RatioTileSizeConstraint(maxSize: CGSize(width: 250, height: 120))
            ]
        ))

        let placements = engine.arrange(
            windows: [
                WindowSnapshot(windowID: 1, frame: CGRect(x: 0, y: 0, width: 200, height: 100)),
                WindowSnapshot(windowID: 2, frame: CGRect(x: 0, y: 0, width: 300, height: 120)),
                WindowSnapshot(windowID: 3, frame: CGRect(x: 0, y: 0, width: 150, height: 100))
            ],
            in: CGRect(x: 0, y: 0, width: 500, height: 300),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 200, height: 100), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 200, y: 0, width: 250, height: 120), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 120, width: 150, height: 100), zOrder: 2)
            ]
        )
    }

    func testFallsBackToGridWhenPackingIsInfeasible() {
        let engine = RatioTileLayoutEngine(config: RatioTileLayoutEngine.Config(
            constraintsByWindowID: [
                1: RatioTileSizeConstraint(minSize: CGSize(width: 600, height: 100))
            ]
        ))

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 500, height: 200),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 250, height: 200), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 250, y: 0, width: 250, height: 200), zOrder: 1)
            ]
        )
    }

    func testZeroWindowSizesUseSquareishFallbackSize() {
        let engine = RatioTileLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3, 4, 5),
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

    func testFactoryBuildsEngine() throws {
        let factory = RatioTileLayoutEngineFactory()
        let config = RatioTileLayoutEngine.Config(
            constraintsByWindowID: [1: RatioTileSizeConstraint(minSize: CGSize(width: 100, height: 50))]
        )
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, RatioTileLayoutEngine.engineID)
        XCTAssertEqual(engine.id, RatioTileLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
