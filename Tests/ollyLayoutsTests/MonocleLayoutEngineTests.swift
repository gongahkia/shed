import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class MonocleLayoutEngineTests: XCTestCase {
    func testFocusedWindowFillsBoundsAndSiblingsHideOffscreen() {
        let offscreen = CGPoint(x: -9_000, y: -8_000)
        let engine = MonocleLayoutEngine(config: MonocleLayoutEngine.Config(offscreenOrigin: offscreen))
        let windows = [
            WindowSnapshot(windowID: 1, frame: CGRect(x: 10, y: 10, width: 320, height: 200)),
            WindowSnapshot(windowID: 2, frame: CGRect(x: 20, y: 20, width: 480, height: 300)),
            WindowSnapshot(windowID: 3, frame: CGRect(x: 30, y: 30, width: 640, height: 400))
        ]
        let bounds = CGRect(x: 100, y: 50, width: 1_200, height: 700)

        let placements = engine.arrange(windows: windows, in: bounds, focus: 2)

        XCTAssertEqual(
            placements,
            [
                Placement(
                    windowID: 1,
                    frame: CGRect(origin: offscreen, size: CGSize(width: 320, height: 200)),
                    zOrder: 0,
                    hidden: true
                ),
                Placement(windowID: 2, frame: bounds, zOrder: 1, hidden: false),
                Placement(
                    windowID: 3,
                    frame: CGRect(origin: offscreen, size: CGSize(width: 640, height: 400)),
                    zOrder: 2,
                    hidden: true
                )
            ]
        )
    }

    func testFallsBackToFirstWindowWhenFocusIsMissing() {
        let engine = MonocleLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: 99
        )

        XCTAssertEqual(placements[0], Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 800, height: 600)))
        XCTAssertTrue(placements[1].hidden)
    }

    func testCyclesFocusNextAndPreviousWithWraparound() {
        let engine = MonocleLayoutEngine()
        let windows = snapshots(1, 2, 3)

        XCTAssertEqual(engine.nextFocus(windows: windows, focus: 1), 2)
        XCTAssertEqual(engine.nextFocus(windows: windows, focus: 3), 1)
        XCTAssertEqual(engine.nextFocus(windows: windows, focus: nil), 1)
        XCTAssertEqual(engine.previousFocus(windows: windows, focus: 1), 3)
        XCTAssertEqual(engine.previousFocus(windows: windows, focus: 3), 2)
        XCTAssertEqual(engine.previousFocus(windows: windows, focus: nil), 3)
    }

    func testFactoryBuildsEngine() throws {
        let factory = MonocleLayoutEngineFactory()
        let config = MonocleLayoutEngine.Config(offscreenOrigin: CGPoint(x: -1, y: -2))
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, MonocleLayoutEngine.engineID)
        XCTAssertEqual(engine.id, MonocleLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: CGRect(x: 0, y: 0, width: 100, height: 100)) }
    }
}
