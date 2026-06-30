import Foundation
import ollyLayouts
import ollyKit

public struct IPCAXPermissionEvent: Codable, Equatable, Sendable {
    public let status: String

    public init(status: String) {
        self.status = status
    }

    public init(status: AXPermissionStatus) {
        self.status = status.wireValue
    }
}

public struct IPCFocusEvent: Codable, Equatable, Sendable {
    public let focusedWindowID: WindowID?
    public let displayID: DisplayID?
    public let tagMask: UInt64?

    public init(focusedWindowID: WindowID?, displayID: DisplayID? = nil, tagMask: UInt64? = nil) {
        self.focusedWindowID = focusedWindowID
        self.displayID = displayID
        self.tagMask = tagMask
    }
}

public struct IPCFullscreenEvent: Codable, Equatable, Sendable {
    public let windowID: WindowID
    public let didEnter: Bool

    public init(windowID: WindowID, didEnter: Bool) {
        self.windowID = windowID
        self.didEnter = didEnter
    }
}

public enum IPCEvent: Codable, Equatable, Sendable {
    case axPermission(IPCAXPermissionEvent)
    case engine(EngineEvent)
    case focus(IPCFocusEvent)
    case fullscreen(IPCFullscreenEvent)
}

public struct IPCEventEnvelope: Codable, Equatable, Sendable {
    public let version: Int
    public let event: IPCEvent

    public init(version: Int = OllyIPC.protocolVersion, event: IPCEvent) {
        precondition(version > 0)
        self.version = version
        self.event = event
    }

    public func newlineDelimitedJSON(encoder: JSONEncoder = JSONEncoder()) throws -> Data {
        try JSONLineCodec.encodeLine(self, encoder: encoder)
    }
}
