import CoreGraphics
import Foundation

public struct WindowFrameAnimation: Equatable, Sendable {
    public let elapsed: TimeInterval
    public let frame: CGRect

    public static func frames(
        from start: CGRect,
        to end: CGRect,
        duration: TimeInterval,
        frameInterval: TimeInterval,
        curve: AnimationCurve
    ) -> [WindowFrameAnimation] {
        guard duration > 0, frameInterval > 0 else {
            return [WindowFrameAnimation(elapsed: 0, frame: end)]
        }
        let stepCount = max(1, Int(ceil(duration / frameInterval)))
        return (1...stepCount).map { step in
            let elapsed = min(duration, TimeInterval(step) * frameInterval)
            let progress = min(1, elapsed / duration)
            return WindowFrameAnimation(
                elapsed: elapsed,
                frame: start.interpolated(to: end, progress: easedProgress(progress, curve: curve))
            )
        }
    }

    static func easedProgress(_ progress: Double, curve: AnimationCurve) -> Double {
        let clamped = min(max(progress, 0), 1)
        switch curve {
        case .linear:
            return clamped
        case .easeIn:
            return clamped * clamped
        case .easeOut:
            return 1 - (1 - clamped) * (1 - clamped)
        case .easeInOut:
            if clamped < 0.5 {
                return 2 * clamped * clamped
            }
            return 1 - pow(-2 * clamped + 2, 2) / 2
        }
    }
}

private extension CGRect {
    func interpolated(to end: CGRect, progress: Double) -> CGRect {
        CGRect(
            x: interpolate(origin.x, end.origin.x, progress),
            y: interpolate(origin.y, end.origin.y, progress),
            width: interpolate(size.width, end.size.width, progress),
            height: interpolate(size.height, end.size.height, progress)
        )
    }

    func interpolate(_ start: CGFloat, _ end: CGFloat, _ progress: Double) -> CGFloat {
        start + (end - start) * CGFloat(progress)
    }
}
