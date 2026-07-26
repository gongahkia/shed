import CoreGraphics
import XCTest
@testable import ollyKit

final class WindowRefTests: XCTestCase {
    func testFallbackWindowIDMatchesPIDFrameAndTitle() {
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        let info = windowInfo(processID: 42, windowID: 9001, title: "Editor", frame: frame)

        let windowID = WindowRef.fallbackWindowID(
            processID: 42,
            title: "Editor",
            frame: frame,
            windowInfo: [info]
        )

        XCTAssertEqual(windowID, 9001)
    }

    func testFallbackWindowIDRejectsTitleMismatch() {
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        let info = windowInfo(processID: 42, windowID: 9001, title: "Other", frame: frame)

        let windowID = WindowRef.fallbackWindowID(
            processID: 42,
            title: "Editor",
            frame: frame,
            windowInfo: [info]
        )

        XCTAssertNil(windowID)
    }

    func testFallbackWindowIDRejectsNonWindowLayer() {
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        var info = windowInfo(processID: 42, windowID: 9001, title: "Editor", frame: frame)
        info[kCGWindowLayer as String] = 1

        let windowID = WindowRef.fallbackWindowID(
            processID: 42,
            title: "Editor",
            frame: frame,
            windowInfo: [info]
        )

        XCTAssertNil(windowID)
    }

    private func windowInfo(
        processID: Int,
        windowID: Int,
        title: String,
        frame: CGRect
    ) -> [String: Any] {
        [
            kCGWindowOwnerPID as String: NSNumber(value: processID),
            kCGWindowNumber as String: NSNumber(value: windowID),
            kCGWindowLayer as String: NSNumber(value: 0),
            kCGWindowName as String: title,
            kCGWindowBounds as String: [
                "X": frame.origin.x,
                "Y": frame.origin.y,
                "Width": frame.size.width,
                "Height": frame.size.height
            ]
        ]
    }
}
