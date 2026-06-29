import ollyKit

public enum IPCSnapPosition: String, CaseIterable, Codable, Equatable, Sendable {
    case leftHalf = "left-half"
    case rightHalf = "right-half"
    case topHalf = "top-half"
    case bottomHalf = "bottom-half"
    case topLeft = "top-left"
    case topRight = "top-right"
    case bottomLeft = "bottom-left"
    case bottomRight = "bottom-right"
    case center
    case maximize
}

public struct IPCSnapWindowCommand: Codable, Equatable, Sendable {
    public let position: IPCSnapPosition
    public let windowID: WindowID?
    public let displayID: DisplayID?
    public let makeFloating: Bool

    public init(
        position: IPCSnapPosition,
        windowID: WindowID? = nil,
        displayID: DisplayID? = nil,
        makeFloating: Bool = true
    ) {
        self.position = position
        self.windowID = windowID
        self.displayID = displayID
        self.makeFloating = makeFloating
    }
}

public enum IPCGestureTrigger: String, CaseIterable, Codable, Equatable, Sendable {
    case fourFingerHorizontal
    case fourFingerVertical
}

public enum IPCGestureMotion: String, CaseIterable, Codable, Equatable, Sendable {
    case left
    case right
    case upward
    case downward
}

public struct IPCDispatchGestureCommand: Codable, Equatable, Sendable {
    public let trigger: IPCGestureTrigger
    public let motion: IPCGestureMotion
    public let displayID: DisplayID?

    public init(
        trigger: IPCGestureTrigger,
        motion: IPCGestureMotion,
        displayID: DisplayID? = nil
    ) {
        self.trigger = trigger
        self.motion = motion
        self.displayID = displayID
    }
}
