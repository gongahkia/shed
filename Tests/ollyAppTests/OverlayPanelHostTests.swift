import AppKit
import XCTest
import ollyDSL
import ollyIPC
import ollyRuntime
@testable import ollyApp

final class OverlayPanelHostTests: XCTestCase {
    @MainActor
    func testRebuildAndCloseOverlaysDoesNotLeakPanels() {
        let host = OverlayPanelHost(notificationCenter: NotificationCenter())

        for _ in 0..<100 {
            host.rebuildOverlays()
            host.closeAll()
        }

        XCTAssertEqual(host.activeCount, 0)
    }

    @MainActor
    func testOverlayRegistryTracksActiveKindsAndHiddenCollisions() {
        let registry = OverlayRegistry()

        XCTAssertTrue(registry.register(.grid).isEmpty)
        XCTAssertEqual(registry.register(.commandPalette, hiding: [.grid]), [.grid])

        XCTAssertFalse(registry.isActive(.grid))
        XCTAssertTrue(registry.isActive(.commandPalette))
        registry.unregister(.commandPalette)
        XCTAssertTrue(registry.active.isEmpty)
    }

    @MainActor
    func testFocusRingControllerShowsPanelAtFocusedWindowFrame() async throws {
        guard let screen = NSScreen.screens.first else {
            throw XCTSkip("no screen available")
        }
        let runtime = OllyRuntime()
        let frame = CGRect(x: screen.frame.midX - 120, y: screen.frame.midY - 80, width: 240, height: 160)
        let controller = FocusRingController(
            runtime: runtime,
            screenProvider: { [screen] in [screen] },
            frameProvider: { _ in frame },
            focusRingProvider: { FocusRing(width: 2, reduceMotion: .neverAnimate) },
            reduceMotionProvider: { true }
        )
        defer {
            controller.stop()
        }

        await controller.handle(.focus(IPCFocusEvent(focusedWindowID: 42)))

        XCTAssertEqual(controller.currentPanelFrame, frame)
    }
}
