import CoreGraphics
import XCTest
@testable import ollyLayouts

final class PlacementTests: XCTestCase {
    func testPlacementStoresWindowFrameZOrderAndHiddenState() {
        let frame = CGRect(x: 1, y: 2, width: 300, height: 200)
        let placement = Placement(windowID: 7, frame: frame, zOrder: 3, hidden: true)

        XCTAssertEqual(placement.windowID, 7)
        XCTAssertEqual(placement.frame, frame)
        XCTAssertEqual(placement.zOrder, 3)
        XCTAssertTrue(placement.hidden)
    }

    func testPlacementCodableRoundTrip() throws {
        let placement = Placement(
            windowID: 7,
            frame: CGRect(x: 1, y: 2, width: 300, height: 200),
            zOrder: 3,
            hidden: true
        )

        let data = try JSONEncoder().encode(placement)
        let decoded = try JSONDecoder().decode(Placement.self, from: data)

        XCTAssertEqual(decoded, placement)
    }
}
