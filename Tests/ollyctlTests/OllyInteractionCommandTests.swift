import XCTest
import ollyIPC
@testable import ollyctl

final class OllyInteractionCommandTests: XCTestCase {
    func testSnapPositionParserAcceptsDocumentedValues() throws {
        XCTAssertEqual(try parseSnapPosition("left-half"), .leftHalf)
        XCTAssertEqual(try parseSnapPosition("top-right"), .topRight)
        XCTAssertEqual(try parseSnapPosition("maximize"), .maximize)
    }

    func testSnapPositionParserRejectsUnknownValue() {
        XCTAssertThrowsError(try parseSnapPosition("leftHalf"))
    }

    func testGestureParsersAcceptConfiguredGestureNames() throws {
        XCTAssertEqual(try parseGestureTrigger("fourFingerHorizontal"), .fourFingerHorizontal)
        XCTAssertEqual(try parseGestureMotion("downward"), .downward)
    }

    func testGestureParsersRejectUnknownValues() {
        XCTAssertThrowsError(try parseGestureTrigger("pinch"))
        XCTAssertThrowsError(try parseGestureMotion("down"))
    }

    func testOverlayParserAcceptsDocumentedValues() throws {
        XCTAssertEqual(try parseOverlayKind("grid"), .grid)
        XCTAssertEqual(try parseOverlayKind("cheatsheet"), .cheatsheet)
        XCTAssertEqual(try parseOverlayKind("alt-tab"), .altTab)
    }

    func testOverlayParserRejectsUnknownValue() {
        XCTAssertThrowsError(try parseOverlayKind("altTab"))
    }
}
