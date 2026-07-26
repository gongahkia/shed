import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class TreeTabLayoutEngineTests: XCTestCase {
    func testTreeTabShowsFocusedWindowBesideLeftRailAndHidesSiblings() {
        let engine = TreeTabLayoutEngine(config: TreeTabLayoutEngine.Config(railWidth: 150))
        let bounds = CGRect(x: 0, y: 0, width: 900, height: 600)

        let placements = engine.arrange(windows: snapshots(1, 2, 3), in: bounds, focus: 2)

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: -32_000, y: -32_000, width: 300, height: 200), hidden: true),
                Placement(windowID: 2, frame: CGRect(x: 150, y: 0, width: 750, height: 600), zOrder: 1),
                Placement(
                    windowID: 3,
                    frame: CGRect(x: -32_000, y: -32_000, width: 300, height: 200),
                    zOrder: 2,
                    hidden: true
                )
            ]
        )
    }

    func testTreeTabRightRailKeepsContentOnLeft() {
        let engine = TreeTabLayoutEngine(config: TreeTabLayoutEngine.Config(railWidth: 150, side: .right))
        let placements = engine.arrange(
            windows: snapshots(1),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: 1
        )

        XCTAssertEqual(placements, [Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 750, height: 600))])
    }

    func testTreeTabEmitsDepthAwareRailMetadata() {
        let tree = TreeTabTree([
            .window(id: 1, children: [.window(id: 3)]),
            .window(id: 2)
        ])
        let engine = TreeTabLayoutEngine(config: TreeTabLayoutEngine.Config(tree: tree))
        let windows = [
            WindowSnapshot(windowID: 1, frame: .zero, title: "Docs"),
            WindowSnapshot(windowID: 2, frame: .zero, title: "Build"),
            WindowSnapshot(windowID: 3, frame: .zero, title: "Tests"),
            WindowSnapshot(windowID: 4, frame: .zero, title: "Logs")
        ]

        XCTAssertEqual(
            engine.items(windows: windows, focus: 3),
            [
                TreeTabLayoutItem(windowID: 1, title: "Docs", index: 0, depth: 0, isSelected: false),
                TreeTabLayoutItem(windowID: 3, title: "Tests", index: 1, depth: 1, isSelected: true),
                TreeTabLayoutItem(windowID: 2, title: "Build", index: 2, depth: 0, isSelected: false),
                TreeTabLayoutItem(windowID: 4, title: "Logs", index: 3, depth: 0, isSelected: false)
            ]
        )
    }

    func testFactoryBuildsEngine() throws {
        let factory = TreeTabLayoutEngineFactory()
        let config = TreeTabLayoutEngine.Config(railWidth: 144, side: .right)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, TreeTabLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map {
            WindowSnapshot(windowID: $0, frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        }
    }
}
