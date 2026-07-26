import CoreGraphics
import XCTest
@testable import ollyKit

final class WindowUnderPointResolverTests: XCTestCase {
    func testWindowIDReturnsFrontmostLayerZeroCandidateContainingPoint() {
        let candidates = [
            WindowUnderPointCandidate(
                windowID: 1,
                processID: 10,
                layer: 1,
                bounds: CGRect(x: 0, y: 0, width: 300, height: 300)
            ),
            WindowUnderPointCandidate(
                windowID: 2,
                processID: 20,
                layer: 0,
                bounds: CGRect(x: 0, y: 0, width: 300, height: 300)
            ),
            WindowUnderPointCandidate(
                windowID: 3,
                processID: 30,
                layer: 0,
                bounds: CGRect(x: 20, y: 20, width: 300, height: 300)
            )
        ]

        XCTAssertEqual(WindowUnderPointResolver.windowID(at: CGPoint(x: 40, y: 40), candidates: candidates), 2)
    }

    func testWindowIDReturnsNilWhenNoLayerZeroWindowContainsPoint() {
        let candidates = [
            WindowUnderPointCandidate(
                windowID: 1,
                processID: 10,
                layer: 1,
                bounds: CGRect(x: 0, y: 0, width: 300, height: 300)
            )
        ]

        XCTAssertNil(WindowUnderPointResolver.windowID(at: CGPoint(x: 40, y: 40), candidates: candidates))
    }
}
