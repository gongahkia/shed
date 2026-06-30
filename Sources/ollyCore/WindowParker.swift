import CoreGraphics
import ollyKit

public enum WindowParkingReason: Equatable, Sendable {
    case hide
    case show
}

public struct WindowParkingMove: Equatable, Sendable {
    public let windowID: WindowID
    public let targetFrame: CGRect
    public let reason: WindowParkingReason

    public init(windowID: WindowID, targetFrame: CGRect, reason: WindowParkingReason) {
        self.windowID = windowID
        self.targetFrame = targetFrame
        self.reason = reason
    }
}

public typealias WindowParkingMoveHandler = (WindowState, CGRect) async -> Void

public actor WindowParker {
    private let offscreenParking: OffscreenParking
    private let displayProvider: TagDisplayProvider
    private let moveWindow: WindowParkingMoveHandler
    private var parkedWindowIDs: Set<WindowID> = []
    private var visibleFramesByWindowID: [WindowID: CGRect] = [:]

    public init(
        offscreenParking: OffscreenParking = .default,
        displayProvider: @escaping TagDisplayProvider,
        moveWindow: @escaping WindowParkingMoveHandler
    ) {
        self.offscreenParking = offscreenParking
        self.displayProvider = displayProvider
        self.moveWindow = moveWindow
    }

    public func isParked(windowID: WindowID) -> Bool {
        parkedWindowIDs.contains(windowID)
    }

    public func park(_ window: WindowState) async -> WindowParkingMove? {
        guard !parkedWindowIDs.contains(window.id) else {
            return nil
        }

        parkedWindowIDs.insert(window.id)
        visibleFramesByWindowID[window.id] = window.frame
        let frame = offscreenParking.frame(for: window, avoiding: await displayProvider())
        await moveWindow(window, frame)
        return WindowParkingMove(windowID: window.id, targetFrame: frame, reason: .hide)
    }

    public func unpark(_ window: WindowState, targetFrame: CGRect? = nil) async -> WindowParkingMove? {
        guard parkedWindowIDs.remove(window.id) != nil else {
            return nil
        }

        let frame = targetFrame ?? visibleFramesByWindowID.removeValue(forKey: window.id) ?? window.frame
        await moveWindow(window, frame)
        return WindowParkingMove(windowID: window.id, targetFrame: frame, reason: .show)
    }

    public func restore(_ window: WindowState, targetFrame: CGRect) async -> WindowParkingMove {
        parkedWindowIDs.remove(window.id)
        visibleFramesByWindowID.removeValue(forKey: window.id)
        await moveWindow(window, targetFrame)
        return WindowParkingMove(windowID: window.id, targetFrame: targetFrame, reason: .show)
    }
}
