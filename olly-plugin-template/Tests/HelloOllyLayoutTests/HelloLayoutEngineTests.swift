import CoreGraphics
import XCTest
import ollyLayouts
@testable import HelloOllyLayout

final class HelloLayoutEngineTests: XCTestCase {
    func testHelloLayoutCascadesInsetFrames() {
        let engine = HelloLayoutEngine(config: HelloLayoutEngine.Config(inset: 20))
        let placements = engine.arrange(
            windows: [
                WindowSnapshot(windowID: 1, frame: .zero),
                WindowSnapshot(windowID: 2, frame: .zero)
            ],
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(placements[0], Placement(windowID: 1, frame: CGRect(x: 20, y: 20, width: 760, height: 560)))
        XCTAssertEqual(placements[1], Placement(windowID: 2, frame: CGRect(x: 36, y: 36, width: 760, height: 560), zOrder: 1))
    }

    func testFactoryBuildsEngine() throws {
        let engine = try HelloLayoutEngineFactory().makeEngine(config: HelloLayoutEngine.Config(inset: 12))

        XCTAssertEqual(engine.id, HelloLayoutEngine.engineID)
        XCTAssertEqual(engine.config, HelloLayoutEngine.Config(inset: 12))
    }
}
