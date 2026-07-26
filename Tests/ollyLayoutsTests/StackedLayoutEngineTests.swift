import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class StackedLayoutEngineTests: XCTestCase {
    func testStackedShowsFocusedWindowBesideRailAndHidesSiblings() {
        let engine = StackedLayoutEngine(config: StackedLayoutEngine.Config(railWidth: 160))
        let bounds = CGRect(x: 0, y: 0, width: 900, height: 600)

        let placements = engine.arrange(windows: snapshots(1, 2, 3), in: bounds, focus: 2)

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: -32_000, y: -32_000, width: 300, height: 200), hidden: true),
                Placement(windowID: 2, frame: CGRect(x: 160, y: 0, width: 740, height: 600), zOrder: 1),
                Placement(
                    windowID: 3,
                    frame: CGRect(x: -32_000, y: -32_000, width: 300, height: 200),
                    zOrder: 2,
                    hidden: true
                )
            ]
        )
    }

    func testStackedRailWidthClampsToBoundsWidth() {
        let engine = StackedLayoutEngine(config: StackedLayoutEngine.Config(railWidth: 900))
        let placements = engine.arrange(
            windows: snapshots(1),
            in: CGRect(x: 0, y: 0, width: 400, height: 300),
            focus: nil
        )

        XCTAssertEqual(placements, [Placement(windowID: 1, frame: CGRect(x: 400, y: 0, width: 0, height: 300))])
    }

    func testStackedEmitsRailMetadata() {
        let engine = StackedLayoutEngine()
        let windows = [
            WindowSnapshot(windowID: 1, frame: .zero, title: "Docs"),
            WindowSnapshot(windowID: 2, frame: .zero, title: "Build")
        ]

        XCTAssertEqual(
            engine.items(windows: windows, focus: 1),
            [
                StackedLayoutItem(windowID: 1, title: "Docs", index: 0, isSelected: true),
                StackedLayoutItem(windowID: 2, title: "Build", index: 1, isSelected: false)
            ]
        )
    }

    func testFactoryBuildsEngine() throws {
        let factory = StackedLayoutEngineFactory()
        let config = StackedLayoutEngine.Config(railWidth: 144)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, StackedLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map {
            WindowSnapshot(windowID: $0, frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        }
    }
}
