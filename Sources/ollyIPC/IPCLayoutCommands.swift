import ollyCore
import ollyKit
import ollyLayouts

public struct IPCManualPreselectCommand: Codable, Equatable, Sendable {
    public let direction: ManualPreselectDirection
    public let windowID: WindowID?
    public let displayID: DisplayID?

    public init(direction: ManualPreselectDirection, windowID: WindowID? = nil, displayID: DisplayID? = nil) {
        self.direction = direction
        self.windowID = windowID
        self.displayID = displayID
    }
}

public struct IPCBSPTreeCommand: Codable, Equatable, Sendable {
    public let action: BSPTreeAction
    public let path: BSPContainerPath
    public let displayID: DisplayID?

    public init(
        action: BSPTreeAction,
        path: BSPContainerPath = .root,
        displayID: DisplayID? = nil
    ) {
        self.action = action
        self.path = path
        self.displayID = displayID
    }
}
