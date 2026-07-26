import CoreGraphics
import XCTest
import ollyKit
@testable import ollyCore

final class WindowParkerTests: XCTestCase {
    func testParkIsIdempotentAndRestoreMovesToExplicitFrame() async throws {
        let recorder = WindowParkingRecorder()
        let display = Display(
            id: 1,
            frame: CGRect(x: 0, y: 0, width: 1000, height: 800),
            visibleFrame: CGRect(x: 0, y: 0, width: 1000, height: 800),
            scaleFactor: 1,
            localizedName: "Display",
            isMain: true
        )
        let parker = WindowParker(displayProvider: { [display] }) { window, frame in
            await recorder.record(windowID: window.id, frame: frame)
        }
        let window = WindowState(
            id: 7,
            processID: 42,
            displayID: 1,
            tagMask: 1,
            frame: CGRect(x: 10, y: 20, width: 300, height: 200)
        )

        let hidden = await parker.park(window)
        let secondHide = await parker.park(window)
        let restored = await parker.restore(window, targetFrame: window.frame)

        XCTAssertNotNil(hidden)
        XCTAssertNil(secondHide)
        XCTAssertEqual(restored.targetFrame, window.frame)
        let windowIDs = await recorder.windowIDs
        XCTAssertEqual(windowIDs, [7, 7])
    }
}

private actor WindowParkingRecorder {
    private(set) var moves: [(windowID: WindowID, frame: CGRect)] = []

    var windowIDs: [WindowID] {
        moves.map(\.windowID)
    }

    func record(windowID: WindowID, frame: CGRect) {
        moves.append((windowID, frame))
    }
}
