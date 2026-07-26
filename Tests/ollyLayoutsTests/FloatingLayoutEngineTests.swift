import CoreGraphics
import XCTest
import ollyCore
@testable import ollyLayouts

final class FloatingLayoutEngineTests: XCTestCase {
    func testFloatingEnginePassesThroughFramesAndZOrder() {
        let engine = FloatingLayoutEngine()
        let windows = [
            WindowSnapshot(windowID: 1, frame: CGRect(x: 10, y: 10, width: 100, height: 100)),
            WindowSnapshot(windowID: 2, frame: CGRect(x: 20, y: 20, width: 200, height: 200))
        ]

        let placements = engine.arrange(windows: windows, in: .zero, focus: nil)

        XCTAssertEqual(engine.id, FloatingLayoutEngine.engineID)
        XCTAssertEqual(engine.displayName, "Floating")
        XCTAssertEqual(
            placements,
            [
                Placement(windowID: 1, frame: windows[0].frame, zOrder: 0, hidden: false),
                Placement(windowID: 2, frame: windows[1].frame, zOrder: 1, hidden: false)
            ]
        )
    }

    func testFloatingFactoryBuildsEngine() throws {
        let factory = FloatingLayoutEngineFactory()
        let engine = try factory.makeEngine(config: FloatingLayoutEngine.Config())

        XCTAssertEqual(factory.id, FloatingLayoutEngine.engineID)
        XCTAssertEqual(engine.id, FloatingLayoutEngine.engineID)
    }
}
