import ApplicationServices
import XCTest
@testable import ollyKit

final class AXObserverBridgeTests: XCTestCase {
    func testAXNotificationMapsKnownNames() {
        XCTAssertEqual(AXNotification(rawAXName: kAXWindowCreatedNotification), .windowCreated)
        XCTAssertEqual(AXNotification(rawAXName: kAXUIElementDestroyedNotification), .uiElementDestroyed)
        XCTAssertEqual(AXNotification(rawAXName: kAXFocusedWindowChangedNotification), .focusedWindowChanged)
        XCTAssertEqual(AXNotification(rawAXName: kAXWindowMovedNotification), .windowMoved)
        XCTAssertEqual(AXNotification(rawAXName: kAXWindowResizedNotification), .windowResized)
        XCTAssertEqual(AXNotification(rawAXName: kAXMainWindowChangedNotification), .mainWindowChanged)
        XCTAssertEqual(AXNotification(rawAXName: kAXApplicationActivatedNotification), .applicationActivated)
    }
}
