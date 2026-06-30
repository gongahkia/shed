import CoreGraphics
import XCTest
import ollyIPC
@testable import ollyApp

final class SnapZoneResolverTests: XCTestCase {
    private let layoutFrame = CGRect(x: 0, y: 0, width: 1000, height: 800)

    func testZoneReturnsEdgesAndCorners() {
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 20, y: 400), in: layoutFrame), .leftHalf)
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 980, y: 400), in: layoutFrame), .rightHalf)
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 500, y: 780), in: layoutFrame), .topHalf)
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 500, y: 20), in: layoutFrame), .bottomHalf)
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 20, y: 780), in: layoutFrame), .topLeft)
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 980, y: 780), in: layoutFrame), .topRight)
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 20, y: 20), in: layoutFrame), .bottomLeft)
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 980, y: 20), in: layoutFrame), .bottomRight)
    }

    func testZoneReturnsCenterMaximizeAndNil() {
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 500, y: 400), in: layoutFrame), .center)
        XCTAssertEqual(SnapZoneResolver.zone(for: CGPoint(x: 730, y: 400), in: layoutFrame), .maximize)
        XCTAssertNil(SnapZoneResolver.zone(for: CGPoint(x: -1, y: 400), in: layoutFrame))
        XCTAssertNil(SnapZoneResolver.zone(for: CGPoint(x: 100, y: 100), in: .zero))
    }

    func testZonesContainAllSnapPositions() {
        let zones = SnapZoneResolver.zones(in: layoutFrame)

        XCTAssertEqual(Set(zones.map(\.position)), Set(IPCSnapPosition.allCases))
        XCTAssertEqual(
            zones.first { $0.position == .leftHalf }?.frame,
            CGRect(x: 0, y: 0, width: 500, height: 800)
        )
        XCTAssertEqual(
            zones.first { $0.position == .topRight }?.frame,
            CGRect(x: 500, y: 400, width: 500, height: 400)
        )
    }
}
