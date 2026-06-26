import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class BSPLayoutEngineTests: XCTestCase {
    func testDefaultTreeBalancesWindowsByLongerAxis() {
        let engine = BSPLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 300, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 300, y: 0, width: 300, height: 600), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 600, y: 0, width: 300, height: 600), zOrder: 2)
            ]
        )
    }

    func testInsertionSplitsFocusedLeafOnLongerAxis() throws {
        let bounds = CGRect(x: 0, y: 0, width: 800, height: 600)
        let firstTree = try BSPLayoutTree(root: .window(id: 1)).placingNextWindow(2, after: 1, in: bounds)
        let secondTree = try firstTree.placingNextWindow(3, after: 2, in: bounds)
        let engine = BSPLayoutEngine(config: BSPLayoutEngine.Config(tree: secondTree))

        let placements = engine.arrange(windows: snapshots(1, 2, 3), in: bounds, focus: nil)

        XCTAssertEqual(
            secondTree.root,
            .split(
                axis: .horizontal,
                first: .window(id: 1),
                second: .split(axis: .vertical, first: .window(id: 2), second: .window(id: 3))
            )
        )
        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 400, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 400, y: 300, width: 400, height: 300), zOrder: 2)
            ]
        )
    }

    func testRotateChildrenSwapsImmediateChildren() throws {
        let tree = BSPLayoutTree(
            root: .split(axis: .horizontal, first: .window(id: 1), second: .window(id: 2))
        )
        let rotatedTree = try tree.rotatingChildren()
        let engine = BSPLayoutEngine(config: BSPLayoutEngine.Config(tree: rotatedTree))

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 2, frame: CGRect(x: 0, y: 0, width: 400, height: 600), zOrder: 0),
                Placement(windowID: 1, frame: CGRect(x: 400, y: 0, width: 400, height: 600), zOrder: 1)
            ]
        )
    }

    func testFlipAxisTogglesSplitOrientation() throws {
        let tree = BSPLayoutTree(
            root: .split(axis: .horizontal, first: .window(id: 1), second: .window(id: 2))
        )
        let flippedTree = try tree.flippingAxis()
        let engine = BSPLayoutEngine(config: BSPLayoutEngine.Config(tree: flippedTree))

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(flippedTree.root?.axis, .vertical)
        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 800, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 300, width: 800, height: 300), zOrder: 1)
            ]
        )
    }

    func testBalanceTreeRebuildsEqualAreaLeaves() {
        let unbalancedTree = BSPLayoutTree(
            root: .split(
                axis: .horizontal,
                first: .window(id: 1),
                second: .split(
                    axis: .vertical,
                    first: .window(id: 2),
                    second: .split(axis: .horizontal, first: .window(id: 3), second: .window(id: 4))
                )
            )
        )
        let balancedTree = unbalancedTree.balancing(in: CGRect(x: 0, y: 0, width: 800, height: 600))
        let engine = BSPLayoutEngine(config: BSPLayoutEngine.Config(tree: balancedTree))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3, 4),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 300, width: 400, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 400, y: 0, width: 400, height: 300), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 400, y: 300, width: 400, height: 300), zOrder: 3)
            ]
        )
    }

    func testFactoryBuildsEngine() throws {
        let factory = BSPLayoutEngineFactory()
        let tree = BSPLayoutTree(root: .window(id: 1))
        let engine = try factory.makeEngine(config: BSPLayoutEngine.Config(tree: tree))

        XCTAssertEqual(factory.id, BSPLayoutEngine.engineID)
        XCTAssertEqual(engine.config, BSPLayoutEngine.Config(tree: tree))
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
