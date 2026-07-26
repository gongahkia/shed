import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class TabbedLayoutEngineTests: XCTestCase {
    func testTabbedShowsFocusedWindowBelowTabBarAndHidesSiblings() {
        let engine = TabbedLayoutEngine(config: TabbedLayoutEngine.Config(tabBarHeight: 30))
        let bounds = CGRect(x: 0, y: 0, width: 900, height: 600)

        let placements = engine.arrange(windows: snapshots(1, 2, 3), in: bounds, focus: 2)

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: -32_000, y: -32_000, width: 300, height: 200), hidden: true),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 0, width: 900, height: 570), zOrder: 1),
                Placement(
                    windowID: 3,
                    frame: CGRect(x: -32_000, y: -32_000, width: 300, height: 200),
                    zOrder: 2,
                    hidden: true
                )
            ]
        )
    }

    func testTabbedFallsBackToFirstWindowWhenFocusIsMissing() {
        let engine = TabbedLayoutEngine()
        let placements = engine.arrange(windows: snapshots(1, 2), in: CGRect(x: 0, y: 0, width: 800, height: 600), focus: 9)

        XCTAssertFalse(placements[0].hidden)
        XCTAssertTrue(placements[1].hidden)
    }

    func testTabbedEmitsTabMetadata() {
        let engine = TabbedLayoutEngine()
        let windows = [
            WindowSnapshot(windowID: 1, frame: .zero, title: "Docs"),
            WindowSnapshot(windowID: 2, frame: .zero, title: "Build")
        ]

        XCTAssertEqual(
            engine.tabs(windows: windows, focus: 2),
            [
                TabbedLayoutTab(windowID: 1, title: "Docs", index: 0, isSelected: false),
                TabbedLayoutTab(windowID: 2, title: "Build", index: 1, isSelected: true)
            ]
        )
    }

    func testFactoryBuildsEngine() throws {
        let factory = TabbedLayoutEngineFactory()
        let config = TabbedLayoutEngine.Config(tabBarHeight: 32)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, TabbedLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map {
            WindowSnapshot(windowID: $0, frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        }
    }
}
