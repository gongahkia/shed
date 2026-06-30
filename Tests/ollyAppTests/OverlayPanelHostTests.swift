import XCTest
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
}
