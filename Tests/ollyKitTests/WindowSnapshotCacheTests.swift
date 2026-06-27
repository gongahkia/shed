import ApplicationServices
import CoreGraphics
import XCTest
@testable import ollyKit

final class WindowSnapshotCacheTests: XCTestCase {
    func testReturnsCachedSnapshotUntilConfirmedAXDelta() async throws {
        let element = AXUIElementCreateApplication(getpid())
        let loader = SnapshotLoader()
        let cache = WindowSnapshotCache(loader: loader.load)

        let first = try await cache.snapshot(for: element, lookupOptions: .publicOnly)
        let second = try await cache.snapshot(for: element, lookupOptions: .publicOnly)
        await cache.invalidate(for: event(.focusedWindowChanged, element: element))
        let third = try await cache.snapshot(for: element, lookupOptions: .publicOnly)
        await cache.invalidate(for: event(.windowMoved, element: element))
        let fourth = try await cache.snapshot(for: element, lookupOptions: .publicOnly)

        XCTAssertTrue(first === second)
        XCTAssertTrue(second === third)
        XCTAssertFalse(third === fourth)
        XCTAssertEqual(loader.loadCount, 2)
    }

    func testRemoveAllDropsCachedSnapshot() async throws {
        let element = AXUIElementCreateApplication(getpid())
        let loader = SnapshotLoader()
        let cache = WindowSnapshotCache(loader: loader.load)

        _ = try await cache.snapshot(for: element, lookupOptions: .publicOnly)
        await cache.removeAll()
        _ = try await cache.snapshot(for: element, lookupOptions: .publicOnly)

        XCTAssertEqual(loader.loadCount, 2)
    }

    private func event(_ notification: AXNotification, element: AXUIElement) -> AXNotificationEvent {
        AXNotificationEvent(
            processID: getpid(),
            element: element,
            notification: notification,
            rawNotificationName: notification.rawValue
        )
    }
}

private final class SnapshotLoader: @unchecked Sendable {
    private(set) var loadCount = 0

    func load(_ element: AXUIElement, _ lookupOptions: WindowIDLookupOptions) throws -> WindowAttributes {
        loadCount += 1
        return WindowAttributes(
            title: "window-\(loadCount)",
            role: "AXWindow",
            subrole: "AXStandardWindow",
            frame: CGRect(x: loadCount, y: 0, width: 100, height: 100),
            processID: getpid(),
            windowID: WindowID(loadCount)
        )
    }
}
