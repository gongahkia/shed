import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class PseudotileLayoutEngineTests: XCTestCase {
    func testCentersPreferredSizesInsideBaseSlotsAndClampsOversizedWindows() {
        let base = FixedSlotLayoutEngine(placements: [
            Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 500, height: 400), zOrder: 0),
            Placement(windowID: 2, frame: CGRect(x: 500, y: 0, width: 300, height: 400), zOrder: 1)
        ])
        let engine = PseudotileLayoutEngine(
            base: base,
            config: PseudotileLayoutEngine<FixedSlotLayoutEngine>.Config(
                preferredSizesByWindowID: [
                    1: CGSize(width: 200, height: 100),
                    2: CGSize(width: 1_000, height: 100)
                ]
            )
        )

        let placements = engine.arrange(
            windows: snapshots(1, 2),
            in: CGRect(x: 0, y: 0, width: 800, height: 400),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 150, y: 150, width: 200, height: 100), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 500, y: 150, width: 300, height: 100), zOrder: 1)
            ]
        )
    }

    func testFallsBackToCurrentWindowSizeWhenNoPreferredOverrideExists() {
        let base = FixedSlotLayoutEngine(placements: [
            Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 500, height: 400), zOrder: 0)
        ])
        let engine = PseudotileLayoutEngine(base: base)

        let placements = engine.arrange(
            windows: [WindowSnapshot(windowID: 1, frame: CGRect(x: 10, y: 10, width: 100, height: 120))],
            in: CGRect(x: 0, y: 0, width: 500, height: 400),
            focus: nil
        )

        XCTAssertEqual(placements, [
            Placement(windowID: 1, frame: CGRect(x: 200, y: 140, width: 100, height: 120), zOrder: 0)
        ])
    }

    func testLeavesHiddenAndInvalidPreferredSizePlacementsUnchanged() {
        let hidden = Placement(
            windowID: 1,
            frame: CGRect(x: 0, y: 0, width: 500, height: 400),
            zOrder: 0,
            hidden: true
        )
        let invalidSize = Placement(windowID: 2, frame: CGRect(x: 500, y: 0, width: 300, height: 400), zOrder: 1)
        let base = FixedSlotLayoutEngine(placements: [hidden, invalidSize])
        let engine = PseudotileLayoutEngine(base: base)

        let placements = engine.arrange(
            windows: [
                WindowSnapshot(windowID: 1, frame: CGRect(x: 0, y: 0, width: 100, height: 100)),
                WindowSnapshot(windowID: 2, frame: .zero)
            ],
            in: CGRect(x: 0, y: 0, width: 800, height: 400),
            focus: nil
        )

        XCTAssertEqual(placements, [hidden, invalidSize])
    }

    func testFactoryBuildsEngine() throws {
        let base = FixedSlotLayoutEngine(placements: [])
        let factory = PseudotileLayoutEngineFactory(base: base)
        let config = PseudotileLayoutEngine<FixedSlotLayoutEngine>.Config(
            preferredSizesByWindowID: [1: CGSize(width: 200, height: 100)]
        )
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, LayoutEngineID(rawValue: "pseudotile.fixed"))
        XCTAssertEqual(factory.displayName, "Pseudotile(Fixed)")
        XCTAssertEqual(engine.id, factory.id)
        XCTAssertEqual(engine.config, config)
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
