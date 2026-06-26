import CoreGraphics
import XCTest
import ollyKit
@testable import ollyCore

final class OffscreenParkingTests: XCTestCase {
    func testFallbackOriginWhenNoDisplaysAreKnown() {
        let parking = OffscreenParking()
        let frame = parking.frame(for: CGSize(width: 100, height: 100), avoiding: [])

        XCTAssertEqual(frame.origin, OffscreenParking.defaultFallbackOrigin)
    }

    func testFrameIsOutsideAllDisplayFrames() {
        let parking = OffscreenParking()
        let displayFrames = [
            CGRect(x: -600, y: 0, width: 600, height: 400),
            CGRect(x: 0, y: 0, width: 1_440, height: 900)
        ]

        let frame = parking.frame(for: CGSize(width: 500, height: 300), avoiding: displayFrames)

        XCTAssertTrue(parking.isOffscreen(frame, avoiding: displayFrames))
    }

    func testFallbackCollisionMovesPastDisplayUnion() {
        let parking = OffscreenParking()
        let displayFrames = [
            CGRect(x: -33_000, y: -33_000, width: 4_000, height: 4_000)
        ]

        let frame = parking.frame(for: CGSize(width: 800, height: 600), avoiding: displayFrames)

        XCTAssertTrue(parking.isOffscreen(frame, avoiding: displayFrames))
        XCTAssertLessThan(frame.maxX, displayFrames[0].minX)
        XCTAssertLessThan(frame.maxY, displayFrames[0].minY)
    }

    func testDisplayUnplugRecomputesToSafeFrame() {
        let parking = OffscreenParking()
        let attached = [
            CGRect(x: -1_920, y: 0, width: 1_920, height: 1_080),
            CGRect(x: 0, y: 0, width: 1_440, height: 900)
        ]
        let afterUnplug = [CGRect(x: 0, y: 0, width: 1_440, height: 900)]

        let attachedFrame = parking.frame(for: CGSize(width: 200, height: 200), avoiding: attached)
        let unpluggedFrame = parking.frame(for: CGSize(width: 200, height: 200), avoiding: afterUnplug)

        XCTAssertTrue(parking.isOffscreen(attachedFrame, avoiding: attached))
        XCTAssertTrue(parking.isOffscreen(unpluggedFrame, avoiding: afterUnplug))
    }
}
