import AppKit
import Foundation
import ollyCore
import ollyDSL
import ollyKit
import ollyLayouts

actor ReduceMotionState {
    private let provider: ReduceMotionValueProvider
    private var cachedValue: Bool?

    init(provider: @escaping ReduceMotionValueProvider) {
        self.provider = provider
    }

    func current() async -> Bool {
        if let cachedValue {
            return cachedValue
        }
        let value = await provider()
        cachedValue = value
        return value
    }

    func invalidate() {
        cachedValue = nil
    }
}

struct LayoutAnimationPolicy {
    static func duration(animation: Animation, systemReduceMotion: Bool, isElectron: Bool) -> TimeInterval {
        if isElectron || animation.reduceMotion == .neverAnimate {
            return 0
        }
        if animation.reduceMotion == .respectSystem, systemReduceMotion {
            return 0
        }
        return animation.duration.seconds
    }
}

struct RuntimeLayoutPlacementApplier {
    let windowMover: WindowMover
    let windowTargets: RuntimeWindowTargets
    let recoveryJournal: WindowRecoveryJournal
    let configStore: RuntimeConfigStore
    let reduceMotionState: ReduceMotionState

    func apply(engineID: LayoutEngineID, window: WindowState, placement: Placement) async {
        if placement.hidden {
            try? await recoveryJournal.record(window: window, parkedFrame: placement.frame)
        } else {
            try? await recoveryJournal.remove(windowID: window.id)
        }
        guard let target = windowTargets.target(for: window) else {
            return
        }
        let displayTarget = target.withFallbackDisplayID(window.displayID)
        let animation = await configStore.current().animation(for: engineID)
        let duration = await OllyRuntime.layoutAnimationDuration(
            animation: animation,
            window: window,
            reduceMotionState: reduceMotionState
        )
        let startFrame = await windowMover.lastFrame(for: displayTarget) ?? window.frame
        await windowMover.setFrameAnimated(
            from: startFrame,
            to: placement.frame,
            duration: duration,
            curve: animation.curve,
            for: displayTarget
        )
    }
}

extension OllyRuntime {
    public static var defaultReduceMotionProvider: ReduceMotionValueProvider {
        { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }
    }

    public static var defaultReduceMotionChangeStream: ReduceMotionChangeStreamProvider {
        {
            AsyncStream { continuation in
                let observer = NSWorkspace.shared.notificationCenter.addObserver(
                    forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification,
                    object: nil,
                    queue: nil
                ) { _ in
                    continuation.yield(())
                }
                continuation.onTermination = { _ in
                    NSWorkspace.shared.notificationCenter.removeObserver(observer)
                }
            }
        }
    }

    func startReduceMotionObservation() {
        guard reduceMotionObservationTask == nil else {
            return
        }
        let task = Task { [weak self, reduceMotionChangeStream] in
            for await _ in reduceMotionChangeStream() {
                guard let self, !Task.isCancelled else {
                    return
                }
                await self.reduceMotionState.invalidate()
            }
        }
        reduceMotionObservationTask = task
        tasks.append(task)
    }

    static func layoutAnimationDuration(
        animation: Animation,
        window: WindowState,
        reduceMotionState: ReduceMotionState
    ) async -> TimeInterval {
        let systemReduceMotion = await reduceMotionState.current()
        return LayoutAnimationPolicy.duration(
            animation: animation,
            systemReduceMotion: systemReduceMotion,
            isElectron: isElectronWindow(window)
        )
    }

    static func isElectronWindow(_ window: WindowState) -> Bool {
        guard let bundleID = window.bundleID else {
            return false
        }
        return ElectronWorkaround.defaultBundleIDs.contains(bundleID)
    }
}
