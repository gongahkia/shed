import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class ThreeColLayoutEngineTests: XCTestCase {
    func testArrangesCenteredMasterWithBalancedSideStacks() {
        let engine = ThreeColLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3, 4, 5),
            in: CGRect(x: 0, y: 0, width: 1_200, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 300, y: 0, width: 600, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 0, width: 300, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 900, y: 0, width: 300, height: 300), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 0, y: 300, width: 300, height: 300), zOrder: 3),
                Placement(windowID: 5, frame: CGRect(x: 900, y: 300, width: 300, height: 300), zOrder: 4)
            ]
        )
    }

    func testCustomMasterRatioKeepsMasterCentered() {
        let engine = ThreeColLayoutEngine(config: ThreeColLayoutEngine.Config(masterRatio: 0.6))

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 1_000, height: 500),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 200, y: 0, width: 600, height: 500), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 0, width: 200, height: 500), zOrder: 1)
            ]
        )
    }

    func testSingleWindowUsesFullBoundsAndEmptyInputStaysEmpty() {
        let engine = ThreeColLayoutEngine()
        let bounds = CGRect(x: 20, y: 30, width: 700, height: 500)

        XCTAssertEqual(engine.arrange(windows: [], in: bounds, focus: nil), [])
        XCTAssertEqual(engine.arrange(windows: snapshots(1), in: bounds, focus: nil), [
            Placement(windowID: 1, frame: bounds)
        ])
    }

    func testFactoryBuildsEngine() throws {
        let factory = ThreeColLayoutEngineFactory()
        let config = ThreeColLayoutEngine.Config(masterRatio: 0.6)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, ThreeColLayoutEngine.engineID)
        XCTAssertEqual(engine.id, ThreeColLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
