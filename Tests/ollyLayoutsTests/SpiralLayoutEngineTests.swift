import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class SpiralLayoutEngineTests: XCTestCase {
    func testHalfRatioRecursivelySplitsLongerAxisInSpiralOrder() {
        let engine = SpiralLayoutEngine(config: SpiralLayoutEngine.Config(splitRatio: 0.5))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3, 4),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 400, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 600, y: 300, width: 200, height: 300), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 400, y: 300, width: 200, height: 300), zOrder: 3)
            ]
        )
    }

    func testDefaultRatioUsesGoldenSplit() {
        let engine = SpiralLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 1_000, height: 600),
            focus: nil
        )

        XCTAssertEqual(engine.config.splitRatio, SpiralLayoutEngine.Config.goldenRatio, accuracy: 0.000_001)
        XCTAssertEqual(placements[0].frame, CGRect(x: 0, y: 0, width: 618, height: 600))
        XCTAssertEqual(placements[1].frame, CGRect(x: 618, y: 0, width: 382, height: 600))
    }

    func testSingleWindowUsesFullBoundsAndEmptyInputStaysEmpty() {
        let engine = SpiralLayoutEngine()
        let bounds = CGRect(x: 20, y: 30, width: 700, height: 500)

        XCTAssertEqual(engine.arrange(windows: [], in: bounds, focus: nil), [])
        XCTAssertEqual(engine.arrange(windows: snapshots(1), in: bounds, focus: nil), [
            Placement(windowID: 1, frame: bounds)
        ])
    }

    func testFactoryBuildsEngine() throws {
        let factory = SpiralLayoutEngineFactory()
        let config = SpiralLayoutEngine.Config(splitRatio: 0.5)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, SpiralLayoutEngine.engineID)
        XCTAssertEqual(engine.id, SpiralLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
