import CoreGraphics
import Foundation
import ScreenCaptureKit

public actor WindowThumbnailCache {
    public typealias Capture = @Sendable (WindowID, CGSize) async throws -> CGImage?
    public typealias Availability = @Sendable () async -> Bool
    public typealias Now = @Sendable () -> Date

    private struct Entry {
        let image: CGImage
        let size: CGSize
        let capturedAt: Date
    }

    private let ttl: TimeInterval
    private let capture: Capture
    private let availability: Availability
    private let now: Now
    private var entries: [WindowID: Entry] = [:]

    public init(
        ttl: TimeInterval = 0.25,
        capture: Capture? = nil,
        availability: Availability? = nil,
        now: @escaping Now = { Date() }
    ) {
        self.ttl = ttl
        self.capture = capture ?? Self.defaultCapture
        self.availability = availability ?? Self.defaultAvailability
        self.now = now
    }

    public func image(
        for windowID: WindowID,
        size: CGSize,
        forceRefresh: Bool = false
    ) async throws -> CGImage? {
        let size = normalized(size)
        let current = now()
        if !forceRefresh,
           let cached = entries[windowID],
           cached.size == size,
           current.timeIntervalSince(cached.capturedAt) <= ttl {
            return cached.image
        }
        guard let image = try await capture(windowID, size) else {
            entries[windowID] = nil
            return nil
        }
        entries[windowID] = Entry(image: image, size: size, capturedAt: now())
        return image
    }

    public func cachedImage(for windowID: WindowID) -> CGImage? {
        guard let cached = entries[windowID],
              now().timeIntervalSince(cached.capturedAt) <= ttl else {
            entries[windowID] = nil
            return nil
        }
        return cached.image
    }

    public func isCaptureAvailable() async -> Bool {
        await availability()
    }

    public func clear() {
        entries.removeAll()
    }

    public func purgeExpired() {
        let current = now()
        entries = entries.filter { current.timeIntervalSince($0.value.capturedAt) <= ttl }
    }

    private func normalized(_ size: CGSize) -> CGSize {
        CGSize(width: max(1, ceil(size.width)), height: max(1, ceil(size.height)))
    }

    private static let defaultAvailability: Availability = {
        do {
            _ = try await SCShareableContent.current
            return true
        } catch {
            return false
        }
    }

    private static let defaultCapture: Capture = { windowID, size in
        try await captureSCK(windowID: windowID, size: size)
    }

    private static func captureSCK(windowID: WindowID, size: CGSize) async throws -> CGImage? {
        let content = try await SCShareableContent.current
        guard let window = content.windows.first(where: { $0.windowID == windowID }) else {
            return nil
        }
        let filter = SCContentFilter(desktopIndependentWindow: window)
        let config = SCStreamConfiguration()
        config.width = Int(size.width)
        config.height = Int(size.height)
        config.showsCursor = false
        config.scalesToFit = true
        config.preservesAspectRatio = true
        return try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: config)
    }
}
