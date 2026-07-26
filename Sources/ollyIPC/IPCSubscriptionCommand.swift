import Foundation

public struct IPCSubscribeEventsCommand: Codable, Equatable, Sendable {
    public let eventKinds: [IPCEventKind]
    public let supportedEventKinds: [IPCEventKind]
    public let replayCurrentState: Bool

    private enum CodingKeys: String, CodingKey {
        case eventKinds
        case supportedEventKinds
        case replayCurrentState
    }

    public init(
        eventKinds: [IPCEventKind] = IPCEventKind.allCases,
        supportedEventKinds: [IPCEventKind] = IPCEventKind.allCases,
        replayCurrentState: Bool = false
    ) {
        self.eventKinds = eventKinds
        self.supportedEventKinds = supportedEventKinds
        self.replayCurrentState = replayCurrentState
    }

    public func negotiatedEventKinds(forProtocolVersion version: Int) -> [IPCEventKind] {
        let clientKinds = Set(supportedEventKinds)
        let protocolKinds = Set(OllyIPC.supportedEventKinds(forProtocolVersion: version))
        let supportedKinds = clientKinds.intersection(protocolKinds)
        return eventKinds.filter { supportedKinds.contains($0) }
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        eventKinds = try container.decodeIfPresent([IPCEventKind].self, forKey: .eventKinds) ?? IPCEventKind.allCases
        supportedEventKinds = try container.decodeIfPresent(
            [IPCEventKind].self,
            forKey: .supportedEventKinds
        ) ?? IPCEventKind.allCases
        replayCurrentState = try container.decodeIfPresent(Bool.self, forKey: .replayCurrentState) ?? false
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(eventKinds, forKey: .eventKinds)
        try container.encode(supportedEventKinds, forKey: .supportedEventKinds)
        try container.encode(replayCurrentState, forKey: .replayCurrentState)
    }
}
