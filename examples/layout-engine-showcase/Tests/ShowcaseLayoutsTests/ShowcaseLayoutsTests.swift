import CoreGraphics
import XCTest
import ollyLayouts
@testable import ShowcaseLayouts

final class ShowcaseLayoutsTests: XCTestCase {
    func testFocusBandPlacesFocusedWindowInCenter() {
        let engine = FocusBandLayoutEngine(config: .init(focusRatio: 0.5))
        let placements = engine.arrange(
            windows: windows(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: 2
        )

        XCTAssertEqual(placements[1].windowID, 2)
        XCTAssertEqual(placements[1].frame, CGRect(x: 225, y: 0, width: 450, height: 600))
    }

    func testGoldenColumnsConsumesRemainingWidth() {
        let engine = GoldenColumnsLayoutEngine(config: .init(ratio: 0.5))
        let placements = engine.arrange(
            windows: windows(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 800, height: 400),
            focus: nil
        )

        XCTAssertEqual(placements.map(\.frame.width), [400, 200, 200])
    }

    func testPriorityGridReservesTopRowForPriorityApps() {
        let engine = PriorityGridLayoutEngine(config: .init(priorityBundleIDs: ["com.example.Editor"]))
        let placements = engine.arrange(
            windows: [
                WindowSnapshot(windowID: 1, bundleID: "com.example.Editor", frame: .zero),
                WindowSnapshot(windowID: 2, bundleID: "com.example.Chat", frame: .zero),
                WindowSnapshot(windowID: 3, bundleID: "com.example.Terminal", frame: .zero)
            ],
            in: CGRect(x: 0, y: 0, width: 1000, height: 1000),
            focus: nil
        )

        XCTAssertEqual(placements[0].frame, CGRect(x: 0, y: 0, width: 1000, height: 400))
        XCTAssertEqual(placements[1].frame.height, 600)
        XCTAssertEqual(placements[2].frame.height, 600)
    }

    private func windows(_ ids: Int...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: UInt32($0), frame: .zero) }
    }
}
