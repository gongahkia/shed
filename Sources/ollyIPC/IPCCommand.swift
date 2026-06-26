import Foundation
import ollyCore
import ollyKit

public enum IPCCommandName: String, CaseIterable, Codable, Equatable, Sendable {
    case state
    case focus
    case swap
    case moveToTag = "move-to-tag"
    case setEngine = "set-engine"
    case tagAdd = "tag-add"
    case tagRemove = "tag-remove"
    case reload
    case subscribeEvents = "subscribe-events"
    case version
}

public enum IPCDirection: String, CaseIterable, Codable, Equatable, Sendable {
    case upward = "up"
    case downward = "down"
    case left
    case right
    case next
    case previous
}

public enum IPCEventKind: String, CaseIterable, Codable, Equatable, Sendable {
    case display
    case engine
    case focus
    case tag
    case window
}

public struct IPCStateCommand: Codable, Equatable, Sendable {
    public let displayID: DisplayID?

    public init(displayID: DisplayID? = nil) {
        self.displayID = displayID
    }
}

public struct IPCDirectionalCommand: Codable, Equatable, Sendable {
    public let direction: IPCDirection
    public let displayID: DisplayID?

    public init(direction: IPCDirection, displayID: DisplayID? = nil) {
        self.direction = direction
        self.displayID = displayID
    }
}

public struct IPCMoveToTagCommand: Codable, Equatable, Sendable {
    public let tag: IPCTagIndex
    public let windowID: WindowID?
    public let displayID: DisplayID?

    public init(tag: IPCTagIndex, windowID: WindowID? = nil, displayID: DisplayID? = nil) {
        self.tag = tag
        self.windowID = windowID
        self.displayID = displayID
    }
}

public struct IPCSetEngineCommand: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let tag: IPCTagIndex?
    public let displayID: DisplayID?

    public init(engineID: LayoutEngineID, tag: IPCTagIndex? = nil, displayID: DisplayID? = nil) {
        self.engineID = engineID
        self.tag = tag
        self.displayID = displayID
    }
}

public struct IPCTagCommand: Codable, Equatable, Sendable {
    public let tag: IPCTagIndex
    public let displayID: DisplayID?

    public init(tag: IPCTagIndex, displayID: DisplayID? = nil) {
        self.tag = tag
        self.displayID = displayID
    }
}

public struct IPCReloadCommand: Codable, Equatable, Sendable {
    public init() {}
}

public struct IPCSubscribeEventsCommand: Codable, Equatable, Sendable {
    public let eventKinds: [IPCEventKind]
    public let replayCurrentState: Bool

    public init(
        eventKinds: [IPCEventKind] = IPCEventKind.allCases,
        replayCurrentState: Bool = false
    ) {
        self.eventKinds = eventKinds
        self.replayCurrentState = replayCurrentState
    }
}

public struct IPCVersionCommand: Codable, Equatable, Sendable {
    public init() {}
}

public enum IPCCommand: Equatable, Sendable {
    case state(IPCStateCommand)
    case focus(IPCDirectionalCommand)
    case swap(IPCDirectionalCommand)
    case moveToTag(IPCMoveToTagCommand)
    case setEngine(IPCSetEngineCommand)
    case tagAdd(IPCTagCommand)
    case tagRemove(IPCTagCommand)
    case reload(IPCReloadCommand)
    case subscribeEvents(IPCSubscribeEventsCommand)
    case version(IPCVersionCommand)

    public var name: IPCCommandName {
        switch self {
        case .state:
            return .state
        case .focus:
            return .focus
        case .swap:
            return .swap
        case .moveToTag:
            return .moveToTag
        case .setEngine:
            return .setEngine
        case .tagAdd:
            return .tagAdd
        case .tagRemove:
            return .tagRemove
        case .reload:
            return .reload
        case .subscribeEvents:
            return .subscribeEvents
        case .version:
            return .version
        }
    }
}

extension IPCCommand: Codable {
    private enum CodingKeys: String, CodingKey {
        case name
        case arguments
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let name = try container.decode(IPCCommandName.self, forKey: .name)

        switch name {
        case .state:
            self = .state(try container.decodeIfPresent(IPCStateCommand.self, forKey: .arguments) ?? .init())
        case .focus:
            self = .focus(try container.decodeRequired(IPCDirectionalCommand.self, forKey: .arguments))
        case .swap:
            self = .swap(try container.decodeRequired(IPCDirectionalCommand.self, forKey: .arguments))
        case .moveToTag:
            self = .moveToTag(try container.decodeRequired(IPCMoveToTagCommand.self, forKey: .arguments))
        case .setEngine:
            self = .setEngine(try container.decodeRequired(IPCSetEngineCommand.self, forKey: .arguments))
        case .tagAdd:
            self = .tagAdd(try container.decodeRequired(IPCTagCommand.self, forKey: .arguments))
        case .tagRemove:
            self = .tagRemove(try container.decodeRequired(IPCTagCommand.self, forKey: .arguments))
        case .reload:
            self = .reload(try container.decodeIfPresent(IPCReloadCommand.self, forKey: .arguments) ?? .init())
        case .subscribeEvents:
            let command = try container.decodeIfPresent(IPCSubscribeEventsCommand.self, forKey: .arguments) ?? .init()
            self = .subscribeEvents(command)
        case .version:
            self = .version(try container.decodeIfPresent(IPCVersionCommand.self, forKey: .arguments) ?? .init())
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)

        switch self {
        case let .state(command):
            try container.encode(command, forKey: .arguments)
        case let .focus(command):
            try container.encode(command, forKey: .arguments)
        case let .swap(command):
            try container.encode(command, forKey: .arguments)
        case let .moveToTag(command):
            try container.encode(command, forKey: .arguments)
        case let .setEngine(command):
            try container.encode(command, forKey: .arguments)
        case let .tagAdd(command):
            try container.encode(command, forKey: .arguments)
        case let .tagRemove(command):
            try container.encode(command, forKey: .arguments)
        case let .reload(command):
            try container.encode(command, forKey: .arguments)
        case let .subscribeEvents(command):
            try container.encode(command, forKey: .arguments)
        case let .version(command):
            try container.encode(command, forKey: .arguments)
        }
    }
}

private extension KeyedDecodingContainer {
    func decodeRequired<T: Decodable>(_ type: T.Type, forKey key: Key) throws -> T {
        guard contains(key) else {
            throw DecodingError.keyNotFound(
                key,
                DecodingError.Context(
                    codingPath: codingPath,
                    debugDescription: "missing IPC command arguments"
                )
            )
        }
        return try decode(type, forKey: key)
    }
}
