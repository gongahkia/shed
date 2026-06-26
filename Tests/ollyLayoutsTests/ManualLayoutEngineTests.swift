import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class ManualLayoutEngineTests: XCTestCase {
    func testDefaultTreeArrangesWindowsHorizontally() {
        let engine = ManualLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 400, height: 600), zOrder: 1)
            ]
        )
    }

    func testPreselectRightIsConsumedByNextWindow() throws {
        let tree = ManualLayoutTree(root: .window(id: 1, preselect: .right))

        let updatedTree = try tree.placingNextWindow(2, after: 1)
        let engine = ManualLayoutEngine(config: ManualLayoutEngine.Config(tree: updatedTree))
        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(updatedTree.root?.children.map(\.preselect), [nil, nil])
        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 400, height: 600), zOrder: 1)
            ]
        )
    }

    func testPreselectDownCreatesNestedVerticalContainer() throws {
        let tree = ManualLayoutTree(
            root: .split(
                axis: .horizontal,
                children: [
                    .window(id: 1, preselect: .down),
                    .window(id: 2)
                ]
            )
        )

        let updatedTree = try tree.placingNextWindow(3, after: 1)
        let engine = ManualLayoutEngine(config: ManualLayoutEngine.Config(tree: updatedTree))
        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            updatedTree.root,
            .split(
                axis: .horizontal,
                children: [
                    .split(axis: .vertical, children: [.window(id: 1), .window(id: 3)]),
                    .window(id: 2)
                ]
            )
        )
        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 300), zOrder: 0),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 300, width: 400, height: 300), zOrder: 1),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 400, height: 600), zOrder: 2)
            ]
        )
    }

    func testPreselectLeftInsertsBeforeTarget() throws {
        let tree = ManualLayoutTree(root: .window(id: 1, preselect: .left))
        let updatedTree = try tree.placingNextWindow(2, after: 1)
        let engine = ManualLayoutEngine(config: ManualLayoutEngine.Config(tree: updatedTree))

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

    func testNoPreselectInsertsAfterFocusedSibling() throws {
        let tree = ManualLayoutTree(
            root: .split(axis: .horizontal, children: [.window(id: 1), .window(id: 2)])
        )
        let updatedTree = try tree.placingNextWindow(3, after: 1)
        let engine = ManualLayoutEngine(config: ManualLayoutEngine.Config(tree: updatedTree))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            updatedTree.root,
            .split(axis: .horizontal, children: [.window(id: 1), .window(id: 3), .window(id: 2)])
        )
        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 300, height: 600), zOrder: 0),
                Placement(windowID: 3, frame: CGRect(x: 300, y: 0, width: 300, height: 600), zOrder: 1),
                Placement(windowID: 2, frame: CGRect(x: 600, y: 0, width: 300, height: 600), zOrder: 2)
            ]
        )
    }

    func testRootPreselectWrapsContainer() throws {
        let tree = ManualLayoutTree(
            root: .split(
                axis: .vertical,
                children: [.window(id: 1), .window(id: 2)],
                preselect: .right
            )
        )
        let updatedTree = try tree.placingNextWindow(3, at: .root)
        let engine = ManualLayoutEngine(config: ManualLayoutEngine.Config(tree: updatedTree))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            updatedTree.root,
            .split(
                axis: .horizontal,
                children: [
                    .split(axis: .vertical, children: [.window(id: 1), .window(id: 2)]),
                    .window(id: 3)
                ]
            )
        )
        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 300, width: 400, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 400, y: 0, width: 400, height: 600), zOrder: 2)
            ]
        )
    }

    func testFactoryBuildsEngine() throws {
        let factory = ManualLayoutEngineFactory()
        let tree = ManualLayoutTree(root: .window(id: 1))
        let engine = try factory.makeEngine(config: ManualLayoutEngine.Config(tree: tree))

        XCTAssertEqual(factory.id, ManualLayoutEngine.engineID)
        XCTAssertEqual(engine.config, ManualLayoutEngine.Config(tree: tree))
        XCTAssertTrue(engine.capabilities.contains(.supportsManualSplits))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
