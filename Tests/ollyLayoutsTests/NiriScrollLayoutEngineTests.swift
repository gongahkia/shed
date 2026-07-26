import CoreGraphics
import QuartzCore
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class NiriScrollLayoutEngineTests: XCTestCase {
    func testDefaultStripPlacesColumnsOnInfiniteXStrip() {
        let engine = NiriScrollLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 450, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 450, y: 0, width: 450, height: 600), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 900, y: 0, width: 450, height: 600), zOrder: 2)
            ]
        )
    }

    func testViewportScrollFollowsFocusedColumn() {
        let engine = NiriScrollLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: 3
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: -450, y: 0, width: 450, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 0, width: 450, height: 600), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 450, y: 0, width: 450, height: 600), zOrder: 2)
            ]
        )
    }

    func testColumnsCanStackWindowsVertically() {
        let strip = NiriScrollStrip(
            columns: [
                NiriColumn(windowIDs: [1, 2], widthPreset: .twoThirds),
                NiriColumn(windowIDs: [3], widthPreset: .oneThird)
            ]
        )
        let engine = NiriScrollLayoutEngine(config: NiriScrollLayoutEngine.Config(strip: strip))

        let placements = engine.arrange(
            windows: snapshots(1, 2, 3),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 600, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 300, width: 600, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 600, y: 0, width: 300, height: 600), zOrder: 2)
            ]
        )
    }

    func testInsertionCanCreateColumnsOrStackInFocusedColumn() throws {
        let strip = NiriScrollStrip(
            columns: [
                NiriColumn(windowIDs: [1]),
                NiriColumn(windowIDs: [2])
            ]
        )

        let newColumnStrip = try strip.placingNextWindow(3, after: 1)
        let stackedStrip = try strip.placingNextWindow(3, after: 1, insertion: .stacked)

        XCTAssertEqual(newColumnStrip.columns.map(\.windowIDs), [[1], [3], [2]])
        XCTAssertEqual(stackedStrip.columns.map(\.windowIDs), [[1, 3], [2]])
    }

    func testSwitchPresetColumnWidthCyclesWidthPresets() throws {
        let strip = NiriScrollStrip(columns: [NiriColumn(windowIDs: [1], widthPreset: .half)])

        let nextStrip = try strip.switchingPresetColumnWidth(for: 1)
        let previousStrip = try strip.switchingPresetColumnWidth(for: 1, reverse: true)

        XCTAssertEqual(nextStrip.columns[0].widthPreset, .twoThirds)
        XCTAssertEqual(previousStrip.columns[0].widthPreset, .oneThird)
    }

    func testViewportAnimationUsesCoreAnimationEaseOut() {
        let engine = NiriScrollLayoutEngine()
        let animation = engine.viewportAnimation(from: 0, to: 450)

        XCTAssertEqual(animation.keyPath, "transform.translation.x")
        XCTAssertEqual(animation.duration, 0.2, accuracy: 0.000_001)
        XCTAssertEqual((animation.fromValue as? NSNumber)?.doubleValue, 0)
        XCTAssertEqual((animation.toValue as? NSNumber)?.doubleValue, -450)
        XCTAssertNotNil(animation.timingFunction)
    }

    func testFactoryBuildsEngine() throws {
        let factory = NiriScrollLayoutEngineFactory()
        let strip = NiriScrollStrip(columns: [NiriColumn(windowIDs: [1], widthPreset: .full)])
        let config = NiriScrollLayoutEngine.Config(strip: strip, defaultColumnWidth: .full)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, NiriScrollLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ ids: WindowID...) -> [WindowSnapshot] {
        ids.map { WindowSnapshot(windowID: $0, frame: .zero) }
    }
}
