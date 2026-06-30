import Foundation

public enum IPCCommandResultName: String, CaseIterable, Codable, Equatable, Sendable {
    case acknowledged
    case cooperativeApps = "cooperative-apps"
    case restoredWindows = "restored-windows"
    case state
    case subscribed
    case version
}

public struct IPCAcknowledgement: Codable, Equatable, Sendable {
    public let message: String?

    public init(message: String? = nil) {
        self.message = message
    }
}

public struct IPCSubscriptionInfo: Codable, Equatable, Sendable {
    public let eventKinds: [IPCEventKind]

    public init(eventKinds: [IPCEventKind]) {
        self.eventKinds = eventKinds
    }
}

public struct IPCRestoreWindowsInfo: Codable, Equatable, Sendable {
    public let restoredCount: Int
    public let skippedCount: Int
    public let failedCount: Int

    public init(restoredCount: Int, skippedCount: Int, failedCount: Int) {
        self.restoredCount = restoredCount
        self.skippedCount = skippedCount
        self.failedCount = failedCount
    }
}

public struct IPCVersionInfo: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let supportedCommands: [IPCCommandName]
    public let supportedEventKinds: [IPCEventKind]

    private enum CodingKeys: String, CodingKey {
        case protocolVersion
        case supportedCommands
        case supportedEventKinds
    }

    public init(
        protocolVersion: Int = OllyIPC.protocolVersion,
        supportedCommands: [IPCCommandName] = OllyIPC.supportedCommandNames,
        supportedEventKinds: [IPCEventKind] = OllyIPC.supportedEventKinds
    ) {
        self.protocolVersion = protocolVersion
        self.supportedCommands = supportedCommands
        self.supportedEventKinds = supportedEventKinds
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        protocolVersion = try container.decode(Int.self, forKey: .protocolVersion)
        supportedCommands = try container.decode([IPCCommandName].self, forKey: .supportedCommands)
        supportedEventKinds = try container.decodeIfPresent(
            [IPCEventKind].self,
            forKey: .supportedEventKinds
        ) ?? OllyIPC.supportedEventKinds(forProtocolVersion: protocolVersion)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(protocolVersion, forKey: .protocolVersion)
        try container.encode(supportedCommands, forKey: .supportedCommands)
        try container.encode(supportedEventKinds, forKey: .supportedEventKinds)
    }
}

public enum IPCCommandResult: Equatable, Sendable {
    case acknowledged(IPCAcknowledgement)
    case cooperativeApps(IPCCooperativeAppsInfo)
    case restoredWindows(IPCRestoreWindowsInfo)
    case state(IPCStateSnapshot)
    case subscribed(IPCSubscriptionInfo)
    case version(IPCVersionInfo)

    public var name: IPCCommandResultName {
        switch self {
        case .acknowledged:
            return .acknowledged
        case .cooperativeApps:
            return .cooperativeApps
        case .restoredWindows:
            return .restoredWindows
        case .state:
            return .state
        case .subscribed:
            return .subscribed
        case .version:
            return .version
        }
    }
}

extension IPCCommandResult: Codable {
    private enum CodingKeys: String, CodingKey {
        case name
        case payload
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let name = try container.decode(IPCCommandResultName.self, forKey: .name)

        switch name {
        case .acknowledged:
            self = .acknowledged(try container.decode(IPCAcknowledgement.self, forKey: .payload))
        case .cooperativeApps:
            self = .cooperativeApps(try container.decode(IPCCooperativeAppsInfo.self, forKey: .payload))
        case .restoredWindows:
            self = .restoredWindows(try container.decode(IPCRestoreWindowsInfo.self, forKey: .payload))
        case .state:
            self = .state(try container.decode(IPCStateSnapshot.self, forKey: .payload))
        case .subscribed:
            self = .subscribed(try container.decode(IPCSubscriptionInfo.self, forKey: .payload))
        case .version:
            self = .version(try container.decode(IPCVersionInfo.self, forKey: .payload))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)

        switch self {
        case let .acknowledged(payload):
            try container.encode(payload, forKey: .payload)
        case let .cooperativeApps(payload):
            try container.encode(payload, forKey: .payload)
        case let .restoredWindows(payload):
            try container.encode(payload, forKey: .payload)
        case let .state(payload):
            try container.encode(payload, forKey: .payload)
        case let .subscribed(payload):
            try container.encode(payload, forKey: .payload)
        case let .version(payload):
            try container.encode(payload, forKey: .payload)
        }
    }
}
