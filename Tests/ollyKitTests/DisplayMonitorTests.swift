import XCTest
@testable import ollyKit

final class DisplayMonitorTests: XCTestCase {
    func testDisplaysExposeCurrentScreensWhenAvailable() throws {
        let monitor = DisplayMonitor()
        let displays = monitor.displays()

        guard !displays.isEmpty else {
            throw XCTSkip("No NSScreen instances available")
        }

        XCTAssertEqual(displays.map(\.id), displays.map(\.id).sorted())
        XCTAssertTrue(displays.allSatisfy { $0.frame.width > 0 && $0.frame.height > 0 })
    }
}
