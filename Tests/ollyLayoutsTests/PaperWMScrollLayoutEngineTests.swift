import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class PaperWMScrollLayoutEngineTests: XCTestCase {
    func testUsesCurrentWindowWidthsForVariableWidthColumns() {
        let engine = PaperWMScrollLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(
                (1, CGSize(width: 300, height: 200)),
                (2, CGSize(width: 500, height: 200)),
                (3, CGSize(width: 250, height: 200))
            ),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 300, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 300, y: 0, width: 500, height: 600), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 800, y: 0, width: 250, height: 600), zOrder: 2)
            ]
        )
    }

    func testFocusScrollsJustEnoughToRevealFocusedColumn() {
        let engine = PaperWMScrollLayoutEngine()

        let placements = engine.arrange(
            windows: snapshots(
                (1, CGSize(width: 300, height: 200)),
                (2, CGSize(width: 500, height: 200)),
                (3, CGSize(width: 400, height: 200))
            ),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: 3
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: -300, y: 0, width: 300, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 0, width: 500, height: 600), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 500, y: 0, width: 400, height: 600), zOrder: 2)
            ]
        )
    }

    func testConfiguredColumnsStackWindowsVertically() {
        let strip = PaperWMScrollStrip(columns: [
            PaperWMColumn(windowIDs: [1, 2]),
            PaperWMColumn(windowIDs: [3])
        ])
        let engine = PaperWMScrollLayoutEngine(config: PaperWMScrollLayoutEngine.Config(strip: strip))

        let placements = engine.arrange(
            windows: snapshots(
                (1, CGSize(width: 300, height: 200)),
                (2, CGSize(width: 500, height: 200)),
                (3, CGSize(width: 250, height: 200))
            ),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 500, height: 300), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 0, y: 300, width: 500, height: 300), zOrder: 1),
                Placement(windowID: 3, frame: CGRect(x: 500, y: 0, width: 250, height: 600), zOrder: 2)
            ]
        )
    }

    func testPreferredWidthsOverrideSnapshotsAndClamp() {
        let sizing = PaperWMColumnSizing(
            preferredWidthsByWindowID: [1: 1_200],
            minColumnWidth: 200,
            maxColumnWidth: 700
        )
        let engine = PaperWMScrollLayoutEngine(config: PaperWMScrollLayoutEngine.Config(sizing: sizing))

        let placements = engine.arrange(
            windows: snapshots((1, CGSize(width: 300, height: 200))),
            in: CGRect(x: 0, y: 0, width: 900, height: 600),
            focus: nil
        )

        XCTAssertEqual(placements, [
            Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 700, height: 600), zOrder: 0)
        ])
    }

    func testFactoryBuildsEngine() throws {
        let factory = PaperWMScrollLayoutEngineFactory()
        let strip = PaperWMScrollStrip(columns: [PaperWMColumn(windowIDs: [1])])
        let config = PaperWMScrollLayoutEngine.Config(strip: strip)
        let engine = try factory.makeEngine(config: config)

        XCTAssertEqual(factory.id, PaperWMScrollLayoutEngine.engineID)
        XCTAssertEqual(engine.id, PaperWMScrollLayoutEngine.engineID)
        XCTAssertEqual(engine.config, config)
        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
    }

    private func snapshots(_ windows: (WindowID, CGSize)...) -> [WindowSnapshot] {
        windows.map { id, size in
            WindowSnapshot(windowID: id, frame: CGRect(origin: .zero, size: size))
        }
    }
}
