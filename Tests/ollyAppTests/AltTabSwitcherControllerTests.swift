import AppKit
import Carbon.HIToolbox
import XCTest
import ollyKit
import ollyRuntime
@testable import ollyApp

final class AltTabSwitcherControllerTests: XCTestCase {
    func testGridLayoutAdaptsForOneThroughSixteenWindows() {
        let bounds = CGRect(x: 0, y: 0, width: 900, height: 600)
        for count in 1...16 {
            let layout = AltTabGridLayout.make(itemCount: count, in: bounds)

            XCTAssertEqual(layout.itemFrames.count, count)
            XCTAssertGreaterThanOrEqual(layout.columns, 1)
            XCTAssertGreaterThanOrEqual(layout.rows, 1)
            for frame in layout.itemFrames {
                XCTAssertTrue(bounds.contains(frame))
                XCTAssertGreaterThan(frame.width, 0)
                XCTAssertGreaterThan(frame.height, 0)
            }
        }
    }

    func testSelectionNavigatorMovesThroughGrid() {
        XCTAssertEqual(AltTabSelectionNavigator.move(from: 0, direction: .right, itemCount: 6, columns: 3), 1)
        XCTAssertEqual(AltTabSelectionNavigator.move(from: 1, direction: .left, itemCount: 6, columns: 3), 0)
        XCTAssertEqual(AltTabSelectionNavigator.move(from: 1, direction: .downward, itemCount: 6, columns: 3), 4)
        XCTAssertEqual(AltTabSelectionNavigator.move(from: 4, direction: .upward, itemCount: 6, columns: 3), 1)
    }

    func testKeyActionMatchesVisibleSwitcherControls() throws {
        XCTAssertEqual(
            AltTabKeyAction.action(for: try keyEvent(keyCode: kVK_RightArrow), isVisible: true),
            .move(.right)
        )
        XCTAssertEqual(AltTabKeyAction.action(for: try keyEvent(keyCode: kVK_Return), isVisible: true), .commit)
        XCTAssertEqual(AltTabKeyAction.action(for: try keyEvent(keyCode: kVK_Escape), isVisible: true), .cancel)
        XCTAssertEqual(AltTabKeyAction.action(for: try keyEvent(keyCode: kVK_RightArrow), isVisible: false), .none)
    }

    @MainActor
    func testControllerShowsListFallbackAndCommitsSelection() async throws {
        guard let screen = NSScreen.screens.first,
              let displayID = DisplayMonitor.displayID(for: screen) else {
            throw XCTSkip("no screen available")
        }
        let host = OverlayPanelHost(notificationCenter: NotificationCenter())
        let runtime = OllyRuntime()
        var committed: WindowID?
        let controller = AltTabSwitcherController(
            runtime: runtime,
            overlayHost: host,
            windowProvider: { Self.windows(count: 3, displayID: displayID) },
            focusedWindowProvider: { 1 },
            focusCommit: { windowID in committed = windowID },
            thumbnailAvailability: { false },
            reduceMotionProvider: { true }
        )
        defer {
            controller.stop()
        }

        await controller.show()
        await controller.handle(.move(.downward))
        await controller.handle(.commit)

        XCTAssertEqual(controller.presentationMode, .list)
        XCTAssertEqual(committed, 2)
        XCTAssertEqual(controller.activeOverlayCount, 0)
    }

    @MainActor
    func testSwitcherViewRendersGridRows() throws {
        let cache = WindowThumbnailCache(capture: { _, _ in Self.makeImage() }, availability: { true })
        let view = AltTabSwitcherView(thumbnailCache: cache)
        view.frame = CGRect(x: 0, y: 0, width: 900, height: 600)

        view.configure(windows: Self.windows(count: 12, displayID: 1), selectedID: 1, mode: .grid, reduceMotion: true)

        XCTAssertEqual(view.itemCount, 12)
        XCTAssertEqual(view.mode, .grid)
        XCTAssertEqual(view.subviews.count, 12)
    }

    private static func windows(count: Int, displayID: DisplayID) -> [WindowState] {
        (1...count).map { id in
            WindowState(
                id: WindowID(id),
                processID: 42,
                bundleID: "dev.olly.test",
                displayID: displayID,
                tagMask: 1,
                layoutOrder: id,
                frame: CGRect(x: id * 10, y: 0, width: 200, height: 100),
                title: "window \(id)"
            )
        }
    }

    private static func makeImage() -> CGImage {
        guard let context = CGContext(
            data: nil,
            width: 2,
            height: 2,
            bitsPerComponent: 8,
            bytesPerRow: 8,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ), let image = context.makeImage() else {
            fatalError("test image unavailable")
        }
        return image
    }

    private func keyEvent(
        keyCode: Int,
        flags: NSEvent.ModifierFlags = []
    ) throws -> NSEvent {
        try XCTUnwrap(NSEvent.keyEvent(
            with: .keyDown,
            location: .zero,
            modifierFlags: flags,
            timestamp: 0,
            windowNumber: 0,
            context: nil,
            characters: "",
            charactersIgnoringModifiers: "",
            isARepeat: false,
            keyCode: UInt16(keyCode)
        ))
    }
}
