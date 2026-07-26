import XCTest
@testable import ollyKit

final class FocusStackTests: XCTestCase {
    func testPreferredWindowUsesMRUWithinDisplayAndTagScope() async {
        let stack = FocusStack()

        await stack.recordFocus(windowID: 1, displayID: 10, tagMask: 0b1)
        await stack.recordFocus(windowID: 2, displayID: 10, tagMask: 0b1)
        await stack.recordFocus(windowID: 3, displayID: 11, tagMask: 0b1)

        let preferred = await stack.preferredWindow(
            displayID: 10,
            tagMask: 0b1,
            availableWindows: [1, 2, 3]
        )

        XCTAssertEqual(preferred, 2)
    }

    func testRestoreFocusFallsBackWhenMostRecentFails() async {
        let stack = FocusStack()
        var focused: [WindowID] = []

        await stack.recordFocus(windowID: 1, displayID: 10, tagMask: 0b1)
        await stack.recordFocus(windowID: 2, displayID: 10, tagMask: 0b1)

        let restored = await stack.restoreFocus(
            displayID: 10,
            tagMask: 0b1,
            availableWindows: [1, 2]
        ) { windowID in
            focused.append(windowID)
            return windowID == 1
        }

        XCTAssertEqual(restored, 1)
        XCTAssertEqual(focused, [2, 1])
    }
}
