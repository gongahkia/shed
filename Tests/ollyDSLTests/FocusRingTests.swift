import XCTest
@testable import ollyDSL

final class FocusRingTests: XCTestCase {
    func testFocusRingBuilderUsesDefaultsAndOverrides() {
        let focusRing = FocusRing {
            color(.systemGreen)
            width(3)
            cornerRadius(10)
            reduceMotion(.neverAnimate)
        }

        XCTAssertEqual(focusRing.color, FocusRingColor.systemGreen)
        XCTAssertEqual(focusRing.width, 3)
        XCTAssertEqual(focusRing.cornerRadius, 10)
        XCTAssertEqual(focusRing.reduceMotion, ReduceMotionPolicy.neverAnimate)
    }

    func testConfigStoresFocusRing() throws {
        let config = Config {
            FocusRing {
                color(.systemPurple)
                width(4)
                cornerRadius(12)
            }
        }

        let data = try JSONEncoder().encode(config)
        let decoded = try JSONDecoder().decode(Config.self, from: data)

        XCTAssertEqual(decoded.focusRing.color, .systemPurple)
        XCTAssertEqual(decoded.focusRing.width, 4)
        XCTAssertEqual(decoded.focusRing.cornerRadius, 12)
    }
}
