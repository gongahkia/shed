import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class FrameLayoutEngineTests: XCTestCase {
    func testSplitFramesRunIndependentSubEngines() {
        let tree = FrameLayoutTree(root: .split(
            axis: .horizontal,
            ratio: 0.5,
            first: .frame(FrameLayoutFrame(engineID: GridLayoutEngine.engineID, windowIDs: [1, 2, 3])),
            second: .frame(FrameLayoutFrame(engineID: MasterStackLayoutEngine.engineID, windowIDs: [4, 5]))
        ))
        let engine = FrameLayoutEngine(config: FrameLayoutEngine.Config(tree: tree))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3, 4, 5),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 200, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 200, y: 0, width: 200, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 300, width: 200, height: 300), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 400, y: 0, width: 220, height: 600), zOrder: 3),
                Placement(windowID: 5, frame: CGRect(x: 620, y: 0, width: 180, height: 600), zOrder: 4)
            ]
        )
    }

    func testNestedFramesSplitRecursively() {
        let tree = FrameLayoutTree(root: .split(
            axis: .vertical,
            ratio: 0.5,
            first: .frame(FrameLayoutFrame(engineID: MonocleLayoutEngine.engineID, windowIDs: [1, 2])),
            second: .split(
                axis: .horizontal,
                ratio: 0.5,
                first: .frame(FrameLayoutFrame(engineID: FloatingLayoutEngine.engineID, windowIDs: [3])),
                second: .frame(FrameLayoutFrame(engineID: GridLayoutEngine.engineID, windowIDs: [4]))
            )
        ))
        let engine = FrameLayoutEngine(config: FrameLayoutEngine.Config(tree: tree))

        let placements = engine.arrange(
            windows: [
                WindowSnapshot(windowID: 1, frame: CGRect(x: 0, y: 0, width: 100, height: 100)),
                WindowSnapshot(windowID: 2, frame: CGRect(x: 0, y: 0, width: 80, height: 80)),
                WindowSnapshot(windowID: 3, frame: CGRect(x: 20, y: 20, width: 120, height: 90)),
                WindowSnapshot(windowID: 4, frame: .zero)
            ],
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: 1
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 800, height: 300), zOrder: 0),
                Placement(
                    windowID: 2,
                    frame: CGRect(x: -32_000, y: -32_000, width: 80, height: 80),
                    zOrder: 1,
                    hidden: true
                ),
                Placement(windowID: 3, frame: CGRect(x: 20, y: 20, width: 120, height: 90), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 400, y: 300, width: 400, height: 300), zOrder: 3)
            ]
        )
    }

    func testDefaultFrameUsesFloatingEngineForAllWindows() {
        let engine = FrameLayoutEngine()
        let windows = [
            WindowSnapshot(windowID: 1, frame: CGRect(x: 10, y: 20, width: 100, height: 80)),
            WindowSnapshot(windowID: 2, frame: CGRect(x: 30, y: 40, width: 120, height: 90))
        ]

        let placements = engine.arrange(windows: windows, in: CGRect(x: 0, y: 0, width: 800, height: 600), focus: nil)

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: windows[0].frame, zOrder: 0),
                Placement(windowID: 2, frame: windows[1].frame, zOrder: 1)
            ]
        )
    }

    func testFactoryBuildsEngine() throws {
        let factory = FrameLayoutEngineFactory()
        let tree = FrameLayoutTree(root: .frame(FrameLayoutFrame(engineID: GridLayoutEngine.engineID)))
        let config = FrameLayoutEngine.Config(tree: tree)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, FrameLayoutEngine.engineID)
        XCTAssertEqual(engine.id, FrameLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
