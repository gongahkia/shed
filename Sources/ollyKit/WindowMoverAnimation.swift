import CoreGraphics
import Foundation

extension WindowMover {
    public func setPosition(_ position: CGPoint, for window: WindowRef) {
        setPosition(position, for: WindowMoveTarget(window: window))
    }

    public func setSize(_ size: CGSize, for window: WindowRef) {
        setSize(size, for: WindowMoveTarget(window: window))
    }

    public func setFrameAnimated(
        from start: CGRect,
        to end: CGRect,
        duration: TimeInterval,
        curve: AnimationCurve,
        for target: WindowMoveTarget
    ) {
        guard !isPaused else {
            return
        }
        let key = WindowMoveKey(target: target)
        cancelAnimation(for: key)
        guard duration > 0 else {
            setFrame(end, for: target, cancelsAnimation: false)
            return
        }

        let frames = WindowFrameAnimation.frames(
            from: start,
            to: end,
            duration: duration,
            frameInterval: animationFrameInterval(),
            curve: curve
        )
        guard !frames.isEmpty else {
            setFrame(end, for: target, cancelsAnimation: false)
            return
        }

        nextAnimationID &+= 1
        let animationID = nextAnimationID
        animationIDs[key] = animationID
        let delay = animationFrameDelayNanoseconds()
        animationTasks[key] = Task { [weak self, frames, key, target, animationID, delay] in
            for frame in frames {
                if delay > 0 {
                    try? await Task.sleep(nanoseconds: delay)
                }
                guard !Task.isCancelled else {
                    return
                }
                await self?.enqueueAnimatedFrame(frame.frame, for: target, key: key, animationID: animationID)
            }
            await self?.finishAnimation(key: key, animationID: animationID)
        }
    }

    func setFrame(_ frame: CGRect, for target: WindowMoveTarget, cancelsAnimation: Bool) {
        setPosition(frame.origin, for: target, cancelsAnimation: cancelsAnimation)
        setSize(frame.size, for: target, cancelsAnimation: cancelsAnimation)
    }

    func enqueueAnimatedFrame(
        _ frame: CGRect,
        for target: WindowMoveTarget,
        key: WindowMoveKey,
        animationID: Int
    ) {
        guard !isPaused, animationIDs[key] == animationID else {
            return
        }
        setFrame(frame, for: target, cancelsAnimation: false)
    }

    func finishAnimation(key: WindowMoveKey, animationID: Int) {
        guard animationIDs[key] == animationID else {
            return
        }
        animationTasks[key] = nil
        animationIDs[key] = nil
    }

    func cancelAnimation(for key: WindowMoveKey) {
        animationTasks[key]?.cancel()
        animationTasks[key] = nil
        animationIDs[key] = nil
    }

    func cancelAnimations() {
        animationTasks.values.forEach { $0.cancel() }
        animationTasks.removeAll()
        animationIDs.removeAll()
    }

    func animationFrameInterval() -> TimeInterval {
        TimeInterval(animationFrameDelayNanoseconds()) / 1_000_000_000
    }

    func animationFrameDelayNanoseconds() -> UInt64 {
        frameDelayNanoseconds > 0 ? frameDelayNanoseconds : Self.defaultFrameDelayNanoseconds
    }
}
