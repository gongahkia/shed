import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class PinnedColumnsLayoutEngineTests: XCTestCase {
    func testPinsScrolledNiriColumnToLeadingEdge() {
        let base = NiriScrollLayoutEngine()
        let engine = PinnedColumnsLayoutEngine(
            base: base,
            config: PinnedColumnsLayoutEngine<NiriScrollLayoutEngine>.Config(pinnedWindowIDs: [1])
        )

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: 3
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 450, height: 600), zOrder: 3),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 0, width: 450, height: 600), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 450, y: 0, width: 450, height: 600), zOrder: 2)
            ]
        )
    }

    func testPinsWholeColumnContainingPinnedWindow() {
        let strip = NiriScrollStrip(
            columns: [
                NiriColumn(windowIDs: [1, 2]),
                NiriColumn(windowIDs: [3]),
                NiriColumn(windowIDs: [4])
            ]
        )
        let base = NiriScrollLayoutEngine(config: NiriScrollLayoutEngine.Config(strip: strip))
        let engine = PinnedColumnsLayoutEngine(
            base: base,
            config: PinnedColumnsLayoutEngine<NiriScrollLayoutEngine>.Config(pinnedWindowIDs: [2])
        )

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3, 4),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: 4
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 450, height: 300), zOrder: 4),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 300, width: 450, height: 300), zOrder: 5),
                Placement(windowID: 3, frame: CGRect(x: 0, y: 0, width: 450, height: 600), zOrder: 2),
                Placement(windowID: 4, frame: CGRect(x: 450, y: 0, width: 450, height: 600), zOrder: 3)
            ]
        )
    }

    func testPinsMultipleColumnsToTrailingEdgeWithSpacing() {
        let base = FixedSlotLayoutEngine(placements: [
            Placement(windowID: 1, frame: CGRect(x: -100, y: 0, width: 100, height: 600), zOrder: 0),
            Placement(windowID: 2, frame: CGRect(x: 200, y: 0, width: 200, height: 600), zOrder: 1),
            Placement(windowID: 3, frame: CGRect(x: 700, y: 0, width: 300, height: 600), zOrder: 2)
        ])
        let engine = PinnedColumnsLayoutEngine(
            base: base,
            config: PinnedColumnsLayoutEngine<FixedSlotLayoutEngine>.Config(
                pinnedWindowIDs: [1, 3],
                edge: .trailing,
                spacing: 10
            )
        )

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 490, y: 0, width: 100, height: 600), zOrder: 3),
                Placement(windowID: 2, frame: CGRect(x: 200, y: 0, width: 200, height: 600), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 600, y: 0, width: 300, height: 600), zOrder: 4)
            ]
        )
    }

    func testFactoryBuildsEngineAndPreservesCapabilities() throws {
        let base = CapabilityFixedSlotLayoutEngine(placements: [])
        let factory = PinnedColumnsLayoutEngineFactory(base: base)
        let config = PinnedColumnsLayoutEngine<CapabilityFixedSlotLayoutEngine>.Config(pinnedWindowIDs: [42])
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, LayoutEngineID(rawValue: "pinned-columns.fixed"))
        XCTAssertEqual(factory.displayName, "PinnedColumns(Fixed)")
        XCTAssertEqual(engine.id, factory.id)
        XCTAssertEqual(engine.config, config)
        XCTAssertEqual(engine.capabilities, base.capabilities)
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}

private struct FixedSlotLayoutEngine: LayoutEngine {
    struct Config: Equatable, Sendable {}

    let id = LayoutEngineID(rawValue: "fixed")
    let displayName = "Fixed"
    let config = Config()
    let placements: [Placement]

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        placements
    }
}

private struct CapabilityFixedSlotLayoutEngine: LayoutEngine {
    struct Config: Equatable, Sendable {}

    let id = LayoutEngineID(rawValue: "fixed")
    let displayName = "Fixed"
    let config = Config()
    let capabilities: LayoutEngineCapabilities = [.supportsResizing]
    let placements: [Placement]

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        placements
    }
}
