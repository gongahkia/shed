import CoreGraphics
import XCTest
import ollyLayouts
@testable import ShowcaseLayouts

final class ShowcaseLayoutsTests: XCTestCase {
    func testDwmMonocleStacksEveryWindowAtBoundsWithFocusedWindowOnTop() {
        let engine = DwmMonocleLayoutEngine()
        let bounds = CGRect(x: 0, y: 0, width: 800, height: 600)
        let placements = engine.arrange(
            windows: windows(1, 2, 3),
            in: bounds,
            focus: 2
        )

        XCTAssertEqual(placements.map(\.frame), [bounds, bounds, bounds])
        XCTAssertEqual(placements.map(\.hidden), [false, false, false])
        XCTAssertEqual(placements.first(where: { $0.windowID == 2 })?.zOrder, 2)
    }

    func testDwmMonocleCyclesByLayoutOrderAndWraps() {
        let engine = DwmMonocleLayoutEngine()
        let windows = [
            WindowSnapshot(windowID: 30, frame: .zero, layoutOrder: 2),
            WindowSnapshot(windowID: 10, frame: .zero, layoutOrder: 0),
            WindowSnapshot(windowID: 20, frame: .zero, layoutOrder: 1)
        ]

        XCTAssertEqual(engine.nextFocus(windows: windows, focus: 20), 30)
        XCTAssertEqual(engine.nextFocus(windows: windows, focus: 30), 10)
        XCTAssertEqual(engine.previousFocus(windows: windows, focus: 10), 30)
    }

    func testDwmMonocleFallsBackToFirstOrderedWindowWhenFocusIsMissing() {
        let engine = DwmMonocleLayoutEngine()
        let placements = engine.arrange(
            windows: [
                WindowSnapshot(windowID: 2, frame: .zero, layoutOrder: 1),
                WindowSnapshot(windowID: 1, frame: .zero, layoutOrder: 0)
            ],
            in: CGRect(x: 0, y: 0, width: 600, height: 400),
            focus: 99
        )

        XCTAssertEqual(placements.first(where: { $0.windowID == 1 })?.zOrder, 1)
    }

    func testDwindleSpiralUsesClockwiseDirectionSequence() {
        let engine = DwindleSpiralLayoutEngine(config: .init(ratio: 0.5, startDirection: .right, clockwise: true))
        let placements = engine.arrange(
            windows: windows(1, 2, 3, 4),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(placements.map(\.frame), [
            CGRect(x: 0, y: 0, width: 400, height: 600),
            CGRect(x: 400, y: 0, width: 400, height: 300),
            CGRect(x: 600, y: 300, width: 200, height: 300),
            CGRect(x: 400, y: 300, width: 200, height: 300)
        ])
    }

    func testDwindleSpiralCanReverseChirality() {
        let engine = DwindleSpiralLayoutEngine(config: .init(ratio: 0.5, startDirection: .right, clockwise: false))
        let placements = engine.arrange(
            windows: windows(1, 2, 3, 4),
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(placements.map(\.frame), [
            CGRect(x: 0, y: 0, width: 400, height: 600),
            CGRect(x: 400, y: 300, width: 400, height: 300),
            CGRect(x: 600, y: 0, width: 200, height: 300),
            CGRect(x: 400, y: 0, width: 200, height: 300)
        ])
    }

    func testDwindleSpiralClampsExtremeRatios() {
        XCTAssertEqual(DwindleSpiralLayoutEngine.Config(ratio: 0.05).ratio, 0.2)
        XCTAssertEqual(DwindleSpiralLayoutEngine.Config(ratio: 0.95).ratio, 0.8)
    }

    func testDwindleSpiralHandlesEmptyAndSingleWindowInputs() {
        let engine = DwindleSpiralLayoutEngine()
        let bounds = CGRect(x: 0, y: 0, width: 500, height: 300)

        XCTAssertTrue(engine.arrange(windows: [], in: bounds, focus: nil).isEmpty)
        XCTAssertEqual(engine.arrange(windows: windows(1), in: bounds, focus: nil), [
            Placement(windowID: 1, frame: bounds)
        ])
    }

    func testMatrixGridPacksRowMajorWithFixedColumns() {
        let engine = MatrixGridLayoutEngine(config: .init(columns: 2))
        let placements = engine.arrange(
            windows: windows(1, 2, 3, 4, 5),
            in: CGRect(x: 0, y: 0, width: 200, height: 300),
            focus: nil
        )

        XCTAssertEqual(placements.map(\.frame), [
            CGRect(x: 0, y: 0, width: 100, height: 100),
            CGRect(x: 100, y: 0, width: 100, height: 100),
            CGRect(x: 0, y: 100, width: 100, height: 100),
            CGRect(x: 100, y: 100, width: 100, height: 100),
            CGRect(x: 0, y: 200, width: 100, height: 100)
        ])
    }

    func testMatrixGridPacksColumnMajorWithFixedColumns() {
        let engine = MatrixGridLayoutEngine(config: .init(columns: 2, fillOrder: .columnMajor))
        let placements = engine.arrange(
            windows: windows(1, 2, 3, 4, 5),
            in: CGRect(x: 0, y: 0, width: 200, height: 300),
            focus: nil
        )

        XCTAssertEqual(placements.map(\.frame), [
            CGRect(x: 0, y: 0, width: 100, height: 100),
            CGRect(x: 0, y: 100, width: 100, height: 100),
            CGRect(x: 0, y: 200, width: 100, height: 100),
            CGRect(x: 100, y: 0, width: 100, height: 100),
            CGRect(x: 100, y: 100, width: 100, height: 100)
        ])
    }

    func testMatrixGridAppliesGapsAndHandlesEmptyInput() {
        let engine = MatrixGridLayoutEngine(config: .init(columns: 2, gap: 10))
        let bounds = CGRect(x: 0, y: 0, width: 200, height: 100)

        XCTAssertEqual(MatrixGridLayoutEngine.Config(columns: 0).columns, 1)
        XCTAssertTrue(engine.arrange(windows: [], in: bounds, focus: nil).isEmpty)
        XCTAssertEqual(engine.arrange(windows: windows(1, 2), in: bounds, focus: nil).map(\.frame), [
            CGRect(x: 5, y: 5, width: 90, height: 90),
            CGRect(x: 105, y: 5, width: 90, height: 90)
        ])
    }

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
