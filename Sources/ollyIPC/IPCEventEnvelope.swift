import Foundation
import ollyLayouts
import ollyKit

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

public enum IPCEvent: Codable, Equatable, Sendable {
    case engine(EngineEvent)
    case focus(IPCFocusEvent)
}

public struct IPCEventEnvelope: Codable, Equatable, Sendable {
    public let version: Int
    public let event: IPCEvent

    public init(version: Int = 1, event: IPCEvent) {
        precondition(version > 0)
        self.version = version
        self.event = event
    }

    public func newlineDelimitedJSON(encoder: JSONEncoder = JSONEncoder()) throws -> Data {
        try JSONLineCodec.encodeLine(self, encoder: encoder)
    }
}
