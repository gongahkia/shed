import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class LayoutEngineTests: XCTestCase {
    func testWindowSnapshotCopiesWindowState() {
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        let state = WindowState(
            id: 7,
            processID: 42,
            displayID: 3,
            tagMask: 0b101,
            frame: frame,
            title: "docs",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )

        let snapshot = WindowSnapshot(state: state)

        XCTAssertEqual(snapshot.windowID, 7)
        XCTAssertEqual(snapshot.frame, frame)
        XCTAssertEqual(snapshot.displayID, 3)
        XCTAssertEqual(snapshot.tags, TagSet(rawValue: 0b101))
        XCTAssertEqual(snapshot.title, "docs")
        XCTAssertEqual(snapshot.role, "AXWindow")
        XCTAssertEqual(snapshot.subrole, "AXStandardWindow")
    }

    func testLayoutEngineProtocolArrangesSnapshots() {
        let engine = StubLayoutEngine(config: StubLayoutEngine.Config(gap: 8))
        let bounds = CGRect(x: 0, y: 0, width: 800, height: 600)
        let windows = [
            WindowSnapshot(windowID: 1, frame: .zero),
            WindowSnapshot(windowID: 2, frame: .zero)
        ]

        let placements = engine.arrange(windows: windows, in: bounds, focus: 2)

        XCTAssertEqual(engine.id, LayoutEngineID(rawValue: "stub"))
        XCTAssertEqual(engine.displayName, "Stub")
        XCTAssertEqual(engine.config.gap, 8)
        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 400, height: 600), zOrder: 0),
                Placement(windowID: 2, frame: CGRect(x: 400, y: 0, width: 400, height: 600), zOrder: 1)
            ]
        )
    }

    func testLayoutEngineArrangeSignatureIsSynchronous() {
        let arrange: (StubLayoutEngine, [WindowSnapshot], CGRect, WindowID?) -> [Placement] = {
            engine, windows, bounds, focus in
            engine.arrange(windows: windows, in: bounds, focus: focus)
        }

        let placements = arrange(
            StubLayoutEngine(config: StubLayoutEngine.Config(gap: 0)),
            [WindowSnapshot(windowID: 1, frame: .zero)],
            CGRect(x: 0, y: 0, width: 800, height: 600),
            nil
        )

        XCTAssertEqual(placements.first?.frame, CGRect(x: 0, y: 0, width: 800, height: 600))
    }
}

private struct StubLayoutEngine: LayoutEngine {
    struct Config {
        let gap: CGFloat
    }

    let id = LayoutEngineID(rawValue: "stub")
    let displayName = "Stub"
    let config: Config

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        let width = bounds.width / CGFloat(max(1, windows.count))
        return windows.enumerated().map { index, window in
            Placement(
                windowID: window.windowID,
                frame: CGRect(
                    x: bounds.minX + CGFloat(index) * width,
                    y: bounds.minY,
                    width: width,
                    height: bounds.height
                ),
                zOrder: index,
                hidden: false
            )
        }
    }
}
