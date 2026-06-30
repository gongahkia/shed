import ollyKit

public struct IPCWindowQueryCommand: Codable, Equatable, Sendable {
    public let windowID: WindowID?
    public let displayID: DisplayID?

    public init(windowID: WindowID? = nil, displayID: DisplayID? = nil) {
        self.windowID = windowID
        self.displayID = displayID
    }
}

public struct IPCDisplayQueryCommand: Codable, Equatable, Sendable {
    public let displayID: DisplayID?

    public init(displayID: DisplayID? = nil) {
        self.displayID = displayID
    }
}

public struct IPCMoveToDisplayCommand: Codable, Equatable, Sendable {
    public let displayID: DisplayID
    public let windowID: WindowID?

    public init(displayID: DisplayID, windowID: WindowID? = nil) {
        self.displayID = displayID
        self.windowID = windowID
    }
}

public struct IPCFloatingCommand: Codable, Equatable, Sendable {
    public let windowID: WindowID?
    public let floating: Bool?
    public let displayID: DisplayID?

    public init(windowID: WindowID? = nil, floating: Bool? = nil, displayID: DisplayID? = nil) {
        self.windowID = windowID
        self.floating = floating
        self.displayID = displayID
    }
}

public struct IPCStickyCommand: Codable, Equatable, Sendable {
    public let windowID: WindowID?
    public let sticky: Bool?
    public let displayID: DisplayID?

    public init(windowID: WindowID? = nil, sticky: Bool? = nil, displayID: DisplayID? = nil) {
        self.windowID = windowID
        self.sticky = sticky
        self.displayID = displayID
    }
}

public struct IPCPinnedCommand: Codable, Equatable, Sendable {
    public let windowID: WindowID?
    public let pinned: Bool?
    public let displayID: DisplayID?

    public init(windowID: WindowID? = nil, pinned: Bool? = nil, displayID: DisplayID? = nil) {
        self.windowID = windowID
        self.pinned = pinned
        self.displayID = displayID
    }
}
