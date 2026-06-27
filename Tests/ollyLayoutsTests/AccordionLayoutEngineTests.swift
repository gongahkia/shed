import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class AccordionLayoutEngineTests: XCTestCase {
    func testFocusedWindowExpandsBetweenTopAndBottomStrips() {
        let engine = AccordionLayoutEngine(config: AccordionLayoutEngine.Config(stripHeight: 50))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3, 4, 5),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: 3
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 800, height: 50), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 50, width: 800, height: 50), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 100, width: 800, height: 400), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 0, y: 500, width: 800, height: 50), zOrder: 3),
                Placement(windowID: 5, frame: CGRect(x: 0, y: 550, width: 800, height: 50), zOrder: 4)
            ]
        )
    }

    func testMissingFocusFallsBackToFirstWindowExpanded() {
        let engine = AccordionLayoutEngine(config: AccordionLayoutEngine.Config(stripHeight: 50))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: 99
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 800, height: 500), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 500, width: 800, height: 50), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 550, width: 800, height: 50), zOrder: 2)
            ]
        )
    }

    func testStripHeightClampsToKeepExpandedWindowVisible() {
        let engine = AccordionLayoutEngine(config: AccordionLayoutEngine.Config(stripHeight: 400))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: 2
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 900, height: 200), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 200, width: 900, height: 200), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 400, width: 900, height: 200), zOrder: 2)
            ]
        )
    }

    func testSingleWindowUsesFullBoundsAndEmptyInputStaysEmpty() {
        let engine = AccordionLayoutEngine()
        let bounds = CGRect(x: 20, y: 30, width: 700, height: 500)

        XCTAssertEqual(engine.arrange(windows: [], in: bounds, focus: nil), [])
        XCTAssertEqual(engine.arrange(windows: snapshots(1), in: bounds, focus: nil), [
            Placement(windowID: 1, frame: bounds)
        ])
    }

    func testFactoryBuildsEngine() throws {
        let factory = AccordionLayoutEngineFactory()
        let config = AccordionLayoutEngine.Config(stripHeight: 36)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, AccordionLayoutEngine.engineID)
        XCTAssertEqual(engine.id, AccordionLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
