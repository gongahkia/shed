import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyRuntime

final class OllyRuntimeAltTabTests: XCTestCase {
    func testAltTabWindowsReturnsWindowsOnCurrentTag() async throws {
        let display = Display(
            id: 42,
            frame: CGRect(x: 0, y: 0, width: 1440, height: 900),
            visibleFrame: CGRect(x: 0, y: 0, width: 1440, height: 860),
            scaleFactor: 2,
            localizedName: "Test",
            isMain: true
        )
        let runtime = OllyRuntime(
            displayProvider: { [display] in [display] },
            scanAXOnStart: false,
            axPermissionStream: { AsyncStream { $0.finish() } },
            displayChangeStream: { AsyncStream { $0.finish() } }
        )
        try await runtime.upsertRuntimeWindow(window(1, displayID: display.id, tagMask: 1), element: nil)
        try await runtime.upsertRuntimeWindow(window(2, displayID: display.id, tagMask: 2), element: nil)
        try await runtime.upsertRuntimeWindow(
            window(3, displayID: display.id, tagMask: 1, isFloating: true),
            element: nil
        )
        try await runtime.upsertRuntimeWindow(
            window(4, displayID: display.id, tagMask: 1, isOffSpace: true),
            element: nil
        )
        await runtime.setFocusedWindow(1, displayID: display.id, tagMask: 1)

        let windows = await runtime.altTabWindows()

        XCTAssertEqual(windows.map(\.id), [1, 3])
    }

    private func window(
        _ id: WindowID,
        displayID: DisplayID,
        tagMask: UInt64,
        isFloating: Bool = false,
        isOffSpace: Bool = false
    ) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            isOffSpace: isOffSpace,
            layoutOrder: Int(id),
            frame: CGRect(x: Int(id) * 10, y: 0, width: 200, height: 100),
            title: "window \(id)"
        )
    }
}
