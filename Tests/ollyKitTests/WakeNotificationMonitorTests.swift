import AppKit
import XCTest
@testable import ollyKit

final class WakeNotificationMonitorTests: XCTestCase {
    func testWakesEmitsDidWakeNotification() async {
        let center = NotificationCenter()
        let monitor = WakeNotificationMonitor(notificationCenter: center)
        var iterator = monitor.wakes().makeAsyncIterator()

        center.post(name: NSWorkspace.didWakeNotification, object: nil)

        let event: Void? = await iterator.next()
        XCTAssertNotNil(event)
    }
}
