import Foundation
import ollyLayouts

public enum IPCEvent: Codable, Equatable, Sendable {
    case engine(EngineEvent)
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
        var data = try encoder.encode(self)
        data.append(0x0A)
        return data
    }
}
