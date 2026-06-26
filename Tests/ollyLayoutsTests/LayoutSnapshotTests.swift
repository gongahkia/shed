import CoreGraphics
import Foundation
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class LayoutSnapshotTests: XCTestCase {
    func testBuiltInLayoutGoldenPlacements() throws {
        let bounds = CGRect(x: 0, y: 0, width: 900, height: 600)
        let cases: [(String, [Placement])] = [
            ("floating", floatingPlacements(in: bounds)),
            ("master-stack", masterStackPlacements(in: bounds)),
            ("manual", manualPlacements(in: bounds)),
            ("bsp", bspPlacements(in: bounds)),
            ("niri-scroll", niriScrollPlacements(in: bounds))
        ]

        for (name, placements) in cases {
            try assertMatchesGolden(name: name, placements: placements)
        }
    }

    private func floatingPlacements(in bounds: CGRect) -> [Placement] {
        FloatingLayoutEngine().arrange(
            windows: [
                WindowSnapshot(windowID: 1, frame: CGRect(x: 10, y: 20, width: 300, height: 200)),
                WindowSnapshot(windowID: 2, frame: CGRect(x: 30, y: 40, width: 250, height: 150)),
                WindowSnapshot(windowID: 3, frame: CGRect(x: 50, y: 60, width: 200, height: 100))
            ],
            in: bounds,
            focus: nil
        )
    }

    private func masterStackPlacements(in bounds: CGRect) -> [Placement] {
        let engine = MasterStackLayoutEngine(config: MasterStackLayoutEngine.Config(masterRatio: 0.5))
        return engine.arrange(windows: snapshots(1, 2, 3), in: bounds, focus: nil)
    }

    private func manualPlacements(in bounds: CGRect) -> [Placement] {
        let tree = ManualLayoutTree(
            root: .split(
                axis: .vertical,
                children: [
                    .split(axis: .horizontal, children: [.window(id: 1), .window(id: 2)]),
                    .window(id: 3)
                ]
            )
        )
        let engine = ManualLayoutEngine(config: ManualLayoutEngine.Config(tree: tree))
        return engine.arrange(windows: snapshots(1, 2, 3), in: bounds, focus: nil)
    }

    private func bspPlacements(in bounds: CGRect) -> [Placement] {
        let tree = BSPLayoutTree(
            root: .split(
                axis: .horizontal,
                first: .split(axis: .vertical, first: .window(id: 1), second: .window(id: 2)),
                second: .window(id: 3)
            )
        )
        let engine = BSPLayoutEngine(config: BSPLayoutEngine.Config(tree: tree))
        return engine.arrange(windows: snapshots(1, 2, 3), in: bounds, focus: nil)
    }

    private func niriScrollPlacements(in bounds: CGRect) -> [Placement] {
        NiriScrollLayoutEngine().arrange(windows: snapshots(1, 2, 3), in: bounds, focus: 3)
    }

    private func assertMatchesGolden(name: String, placements: [Placement]) throws {
        let data = try Data(contentsOf: fixtureURL(name: name))
        let expected = try JSONDecoder().decode([GoldenPlacement].self, from: data)
        XCTAssertEqual(placements.map(GoldenPlacement.init), expected, name)
    }

    private func fixtureURL(name: String) -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Fixtures/LayoutSnapshots/\(name).json")
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}

private struct GoldenPlacement: Codable, Equatable {
    let windowID: WindowID
    let x: CGFloat
    let y: CGFloat
    let width: CGFloat
    let height: CGFloat
    let zOrder: Int
    let hidden: Bool

    init(
        windowID: WindowID,
        x: CGFloat,
        y: CGFloat,
        width: CGFloat,
        height: CGFloat,
        zOrder: Int,
        hidden: Bool
    ) {
        self.windowID = windowID
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.zOrder = zOrder
        self.hidden = hidden
    }

    init(placement: Placement) {
        self.init(
            windowID: placement.windowID,
            x: placement.frame.origin.x,
            y: placement.frame.origin.y,
            width: placement.frame.size.width,
            height: placement.frame.size.height,
            zOrder: placement.zOrder,
            hidden: placement.hidden
        )
    }
}
