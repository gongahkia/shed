import CoreGraphics
import ollyKit

public enum TagDispatchReason: Equatable, Sendable {
    case hide
    case show
}

public struct TagDispatchMove: Equatable, Sendable {
    public let windowID: WindowID
    public let targetFrame: CGRect
    public let reason: TagDispatchReason
}

public typealias TagWindowMoveHandler = (WindowState, CGRect) async -> Void
public typealias WindowMoveTargetResolver = (WindowState) -> WindowMoveTarget?
public typealias TagDisplayProvider = () async -> [Display]

public actor TagDispatcher {
    public static let defaultOffscreenOrigin = OffscreenParking.defaultFallbackOrigin

    private let windowStore: WindowStore
    private let tagStore: TagStore
    private let offscreenParking: OffscreenParking
    private let displayProvider: TagDisplayProvider
    private let moveWindow: TagWindowMoveHandler
    private var parkedWindowIDs: Set<WindowID> = []
    private var visibleFramesByWindowID: [WindowID: CGRect] = [:]

    public init(
        windowStore: WindowStore,
        tagStore: TagStore,
        offscreenOrigin: CGPoint = TagDispatcher.defaultOffscreenOrigin,
        moveWindow: @escaping TagWindowMoveHandler
    ) {
        self.init(
            windowStore: windowStore,
            tagStore: tagStore,
            offscreenParking: OffscreenParking(fallbackOrigin: offscreenOrigin),
            displayProvider: { OffscreenParking.activeDisplays() },
            moveWindow: moveWindow
        )
    }

    public init(
        windowStore: WindowStore,
        tagStore: TagStore,
        offscreenParking: OffscreenParking = .default,
        displayProvider: @escaping TagDisplayProvider,
        moveWindow: @escaping TagWindowMoveHandler
    ) {
        self.windowStore = windowStore
        self.tagStore = tagStore
        self.offscreenParking = offscreenParking
        self.displayProvider = displayProvider
        self.moveWindow = moveWindow
    }

    public init(
        windowStore: WindowStore,
        tagStore: TagStore,
        windowMover: WindowMover,
        offscreenParking: OffscreenParking = .default,
        displayProvider: @escaping TagDisplayProvider = { [] },
        targetResolver: @escaping WindowMoveTargetResolver
    ) {
        self.init(
            windowStore: windowStore,
            tagStore: tagStore,
            offscreenParking: offscreenParking,
            displayProvider: displayProvider
        ) { window, frame in
            guard let target = targetResolver(window) else {
                return
            }
            let displayTarget = target.withFallbackDisplayID(window.displayID)
            await windowMover.setPosition(frame.origin, for: displayTarget)
            await windowMover.setSize(frame.size, for: displayTarget)
        }
    }

    @discardableResult
    public func apply(displayID: DisplayID) async -> [TagDispatchMove] {
        await PerformanceSignpost.interval("dispatch.tagSwitch") {
            await applyWithSignpost(displayID: displayID)
        }
    }

    private func applyWithSignpost(displayID: DisplayID) async -> [TagDispatchMove] {
        _ = await tagStore.activeTags(on: displayID)
        let visibleTags = await tagStore.globallyVisibleTagSet()
        let windows = await windowStore.windows(onDisplay: displayID)
        var moves: [TagDispatchMove] = []

        for window in windows {
            guard !window.isOffSpace else {
                continue
            }
            if shouldShow(window, visibleTags: visibleTags) {
                if let move = await show(window) {
                    moves.append(move)
                }
            } else if let move = await hide(window) {
                moves.append(move)
            }
        }

        return moves
    }

    public func isParked(windowID: WindowID) -> Bool {
        parkedWindowIDs.contains(windowID)
    }

    private func shouldShow(_ window: WindowState, visibleTags: TagSet) -> Bool {
        if window.isFullscreen {
            return true
        }
        if window.isSticky || window.isPinned {
            return true
        }
        return TagSet(rawValue: window.tagMask).intersects(visibleTags)
    }

    private func hide(_ window: WindowState) async -> TagDispatchMove? {
        guard !parkedWindowIDs.contains(window.id) else {
            return nil
        }

        parkedWindowIDs.insert(window.id)
        visibleFramesByWindowID[window.id] = window.frame
        let frame = offscreenParking.frame(for: window, avoiding: await displayProvider())
        await moveWindow(window, frame)
        return TagDispatchMove(windowID: window.id, targetFrame: frame, reason: .hide)
    }

    private func show(_ window: WindowState) async -> TagDispatchMove? {
        guard parkedWindowIDs.remove(window.id) != nil else {
            return nil
        }

        let frame = visibleFramesByWindowID.removeValue(forKey: window.id) ?? window.frame
        await moveWindow(window, frame)
        return TagDispatchMove(windowID: window.id, targetFrame: frame, reason: .show)
    }
}
