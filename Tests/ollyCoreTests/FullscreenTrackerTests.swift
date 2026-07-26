import XCTest
@testable import ollyCore

final class FullscreenTrackerTests: XCTestCase {
    func testEnterExitRoundTripsSavedWindowState() async {
        let tracker = FullscreenTracker()

        await tracker.enter(42, tagMask: 7, displayID: 9)
        let entered = await tracker.isFullscreen(42)
        let snapshot = await tracker.snapshot(for: 42)
        let exited = await tracker.exit(42)
        let stillFullscreen = await tracker.isFullscreen(42)

        XCTAssertTrue(entered)
        XCTAssertEqual(snapshot, FullscreenSnapshot(tagMask: 7, displayID: 9))
        XCTAssertEqual(exited, FullscreenSnapshot(tagMask: 7, displayID: 9))
        XCTAssertFalse(stillFullscreen)
    }
}
