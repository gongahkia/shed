import AppKit
import CoreGraphics
import Foundation

public struct Display: Equatable, Sendable, Identifiable {
    public let id: DisplayID
    public let frame: CGRect
    public let visibleFrame: CGRect
    public let safeAreaInsets: DisplaySafeAreaInsets
    public let scaleFactor: CGFloat
    public let localizedName: String
    public let isMain: Bool

    public init(
        id: DisplayID,
        frame: CGRect,
        visibleFrame: CGRect,
        safeAreaInsets: DisplaySafeAreaInsets = DisplaySafeAreaInsets(),
        scaleFactor: CGFloat,
        localizedName: String,
        isMain: Bool
    ) {
        self.id = id
        self.frame = frame
        self.visibleFrame = visibleFrame
        self.safeAreaInsets = safeAreaInsets
        self.scaleFactor = scaleFactor
        self.localizedName = localizedName
        self.isMain = isMain
    }
}

public struct DisplayChange: Equatable, Sendable {
    public let displayID: DisplayID
    public let flags: CGDisplayChangeSummaryFlags
    public let displays: [Display]
}

public final class DisplayMonitor {
    private let screenProvider: () -> [NSScreen]

    public init(screenProvider: @escaping () -> [NSScreen] = { NSScreen.screens }) {
        self.screenProvider = screenProvider
    }

    public func displays() -> [Display] {
        screenProvider().compactMap { screen in
            guard let displayID = Self.displayID(for: screen) else {
                return nil
            }
            return Display(
                id: displayID,
                frame: screen.frame,
                visibleFrame: screen.visibleFrame,
                safeAreaInsets: DisplaySafeAreaInsets(screen.safeAreaInsets),
                scaleFactor: screen.backingScaleFactor,
                localizedName: screen.localizedName,
                isMain: screen == NSScreen.main
            )
        }.sorted { $0.id < $1.id }
    }

    public func screen(for displayID: DisplayID) -> NSScreen? {
        screenProvider().first { screen in
            Self.displayID(for: screen) == displayID
        }
    }

    public static func displayID(for screen: NSScreen) -> DisplayID? {
        let key = NSDeviceDescriptionKey("NSScreenNumber")
        if let displayID = screen.deviceDescription[key] as? DisplayID {
            return displayID
        }
        if let number = screen.deviceDescription[key] as? NSNumber {
            return number.uint32Value
        }
        return nil
    }

    public func changes() -> AsyncStream<DisplayChange> {
        AsyncStream<DisplayChange>(bufferingPolicy: .bufferingNewest(32)) { continuation in
            let subscription = DisplayReconfigurationSubscription(
                monitor: self,
                continuation: continuation
            )
            continuation.onTermination = { _ in
                subscription.cancel()
            }
        }
    }
}

private extension DisplaySafeAreaInsets {
    init(_ insets: NSEdgeInsets) {
        self.init(top: insets.top, left: insets.left, bottom: insets.bottom, right: insets.right)
    }
}

private final class DisplayReconfigurationSubscription {
    private let monitor: DisplayMonitor
    private let continuation: AsyncStream<DisplayChange>.Continuation
    private var isCancelled = false

    init(
        monitor: DisplayMonitor,
        continuation: AsyncStream<DisplayChange>.Continuation
    ) {
        self.monitor = monitor
        self.continuation = continuation
        let refcon = UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque())
        CGDisplayRegisterReconfigurationCallback(displayReconfigurationCallback, refcon)
    }

    deinit {
        cancel()
    }

    func cancel() {
        guard !isCancelled else {
            return
        }
        let refcon = UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque())
        CGDisplayRemoveReconfigurationCallback(displayReconfigurationCallback, refcon)
        isCancelled = true
    }

    func handle(displayID: DisplayID, flags: CGDisplayChangeSummaryFlags) {
        continuation.yield(
            DisplayChange(
                displayID: displayID,
                flags: flags,
                displays: monitor.displays()
            )
        )
    }
}

private let displayReconfigurationCallback: CGDisplayReconfigurationCallBack = { displayID, flags, refcon in
    guard let refcon else {
        return
    }

    let subscription = Unmanaged<DisplayReconfigurationSubscription>.fromOpaque(refcon).takeUnretainedValue()
    subscription.handle(displayID: displayID, flags: flags)
}
