import CoreGraphics
import XCTest
@testable import ollyKit

final class WindowThumbnailCacheTests: XCTestCase {
    func testReturnsCachedImageWithinTTL() async throws {
        let clock = ThumbnailTestClock()
        let capture = ThumbnailCaptureScript()
        let cache = WindowThumbnailCache(
            ttl: 1,
            capture: { windowID, size in try await capture.capture(windowID: windowID, size: size) },
            availability: { true },
            now: { clock.now() }
        )

        _ = try await cache.image(for: 1, size: CGSize(width: 10.2, height: 12.1))
        _ = try await cache.image(for: 1, size: CGSize(width: 11, height: 13))

        let count = await capture.count()
        XCTAssertEqual(count, 1)
    }

    func testRefreshesAfterTTL() async throws {
        let clock = ThumbnailTestClock()
        let capture = ThumbnailCaptureScript()
        let cache = WindowThumbnailCache(
            ttl: 1,
            capture: { windowID, size in try await capture.capture(windowID: windowID, size: size) },
            availability: { true },
            now: { clock.now() }
        )

        _ = try await cache.image(for: 1, size: CGSize(width: 10, height: 12))
        clock.advance(by: 2)
        _ = try await cache.image(for: 1, size: CGSize(width: 10, height: 12))

        let count = await capture.count()
        XCTAssertEqual(count, 2)
    }

    func testRefreshesWhenSizeChanges() async throws {
        let capture = ThumbnailCaptureScript()
        let cache = WindowThumbnailCache(
            ttl: 1,
            capture: { windowID, size in try await capture.capture(windowID: windowID, size: size) },
            availability: { true }
        )

        _ = try await cache.image(for: 1, size: CGSize(width: 10, height: 12))
        _ = try await cache.image(for: 1, size: CGSize(width: 20, height: 12))

        let count = await capture.count()
        XCTAssertEqual(count, 2)
    }

    func testClearDropsCachedImage() async throws {
        let capture = ThumbnailCaptureScript()
        let cache = WindowThumbnailCache(
            ttl: 1,
            capture: { windowID, size in try await capture.capture(windowID: windowID, size: size) },
            availability: { true }
        )

        _ = try await cache.image(for: 1, size: CGSize(width: 10, height: 12))
        await cache.clear()
        let cached = await cache.cachedImage(for: 1)
        XCTAssertNil(cached)
    }
}

private final class ThumbnailTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current = Date(timeIntervalSince1970: 0)

    func now() -> Date {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    func advance(by interval: TimeInterval) {
        lock.lock()
        current = current.addingTimeInterval(interval)
        lock.unlock()
    }
}

private actor ThumbnailCaptureScript {
    private var storedCount = 0
    private let image = WindowThumbnailCacheTests.makeImage()

    func count() -> Int {
        storedCount
    }

    func capture(windowID: WindowID, size: CGSize) async throws -> CGImage? {
        _ = windowID
        _ = size
        storedCount += 1
        return image
    }
}

private extension WindowThumbnailCacheTests {
    static func makeImage() -> CGImage {
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
}
