import CoreGraphics
import Foundation
import ollyKit

public struct OffscreenParking: Equatable, Sendable {
    public static let defaultFallbackOrigin = CGPoint(x: -32_000, y: -32_000)
    public static let `default` = OffscreenParking()

    public let fallbackOrigin: CGPoint
    public let margin: CGFloat

    public init(fallbackOrigin: CGPoint = Self.defaultFallbackOrigin, margin: CGFloat = 1_024) {
        self.fallbackOrigin = fallbackOrigin
        self.margin = margin
    }

    public static func activeDisplayFrames(maxDisplays: UInt32 = 32) -> [CGRect] {
        activeDisplayBounds(maxDisplays: maxDisplays).map(\.frame)
    }

    public static func activeDisplays(maxDisplays: UInt32 = 32) -> [Display] {
        activeDisplayBounds(maxDisplays: maxDisplays).map { bounds in
            Display(
                id: bounds.id,
                frame: bounds.frame,
                visibleFrame: bounds.frame,
                scaleFactor: 1,
                localizedName: "Display \(bounds.id)",
                isMain: bounds.id == CGMainDisplayID()
            )
        }
    }

    public func origin(avoiding displays: [Display]) -> CGPoint {
        origin(forSize: .zero, avoiding: displays.map(\.frame))
    }

    public func origin(forSize size: CGSize, avoiding displayFrames: [CGRect]) -> CGPoint {
        guard let union = union(displayFrames) else {
            return fallbackOrigin
        }

        let width = max(0, size.width)
        let height = max(0, size.height)
        return CGPoint(
            x: min(fallbackOrigin.x, floor(union.minX - margin - width)),
            y: min(fallbackOrigin.y, floor(union.minY - margin - height))
        )
    }

    public func frame(for window: WindowState, avoiding displays: [Display]) -> CGRect {
        frame(for: window.frame.size, avoiding: displays.map(\.frame))
    }

    public func frame(for size: CGSize, avoiding displayFrames: [CGRect]) -> CGRect {
        CGRect(origin: origin(forSize: size, avoiding: displayFrames), size: size)
    }

    public func isOffscreen(_ frame: CGRect, avoiding displayFrames: [CGRect]) -> Bool {
        displayFrames.allSatisfy { !$0.intersects(frame) }
    }

    public func isOffscreen(_ frame: CGRect, avoiding displays: [Display]) -> Bool {
        isOffscreen(frame, avoiding: displays.map(\.frame))
    }

    private func union(_ displayFrames: [CGRect]) -> CGRect? {
        guard var result = displayFrames.first else {
            return nil
        }

        for frame in displayFrames.dropFirst() {
            result = result.union(frame)
        }
        return result
    }

    private static func activeDisplayBounds(maxDisplays: UInt32) -> [(id: DisplayID, frame: CGRect)] {
        let limit = max(1, maxDisplays)
        let capacity = Int(limit)
        var displays = [CGDirectDisplayID](repeating: 0, count: capacity)
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(limit, &displays, &count) == .success else {
            return []
        }
        return displays.prefix(Int(count)).map { id in
            (id: id, frame: CGDisplayBounds(id))
        }
    }
}
