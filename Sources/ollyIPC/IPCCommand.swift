import ollyCore
import ollyKit

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

public struct IPCCycleEngineCommand: Codable, Equatable, Sendable {
    public let reverse: Bool
    public let tag: IPCTagIndex?
    public let displayID: DisplayID?

    public init(reverse: Bool = false, tag: IPCTagIndex? = nil, displayID: DisplayID? = nil) {
        self.reverse = reverse
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

public struct IPCVersionCommand: Codable, Equatable, Sendable {
    public init() {}
}

public enum IPCCommand: Equatable, Sendable {
    case state(IPCStateCommand)
    case focus(IPCDirectionalCommand)
    case listWindows(IPCWindowQueryCommand)
    case listDisplays(IPCDisplayQueryCommand)
    case moveWindow(IPCDirectionalCommand)
    case moveToDisplay(IPCMoveToDisplayCommand)
    case swap(IPCDirectionalCommand)
    case toggleFloating(IPCFloatingCommand)
    case toggleSticky(IPCStickyCommand)
    case togglePinned(IPCPinnedCommand)
    case snapWindow(IPCSnapWindowCommand)
    case dispatchGesture(IPCDispatchGestureCommand)
    case manualPreselect(IPCManualPreselectCommand)
    case bspTree(IPCBSPTreeCommand)
    case switchTag(IPCTagCommand)
    case moveToTag(IPCMoveToTagCommand)
    case toggleTag(IPCTagCommand)
    case setEngine(IPCSetEngineCommand)
    case cycleEngine(IPCCycleEngineCommand)
    case tagAdd(IPCTagCommand)
    case tagRemove(IPCTagCommand)
    case reload(IPCReloadCommand)
    case restoreWindows(IPCRestoreWindowsCommand)
    case setSpacePolicy(IPCSetSpacePolicyCommand)
    case subscribeEvents(IPCSubscribeEventsCommand)
    case version(IPCVersionCommand)
    case reserved(IPCReservedCommand)

    public var name: IPCCommandName {
        switch self {
        case .state:
            return .state
        case .focus:
            return .focus
        case .listWindows:
            return .listWindows
        case .listDisplays:
            return .listDisplays
        case .moveWindow:
            return .moveWindow
        case .moveToDisplay:
            return .moveToDisplay
        case .swap:
            return .swap
        case .toggleFloating:
            return .toggleFloating
        case .toggleSticky:
            return .toggleSticky
        case .togglePinned:
            return .togglePinned
        case .snapWindow:
            return .snapWindow
        case .dispatchGesture:
            return .dispatchGesture
        case .manualPreselect:
            return .manualPreselect
        case .bspTree:
            return .bspTree
        case .switchTag:
            return .switchTag
        case .moveToTag:
            return .moveToTag
        case .toggleTag:
            return .toggleTag
        case .setEngine:
            return .setEngine
        case .cycleEngine:
            return .cycleEngine
        case .tagAdd:
            return .tagAdd
        case .tagRemove:
            return .tagRemove
        case .reload:
            return .reload
        case .restoreWindows:
            return .restoreWindows
        case .setSpacePolicy:
            return .setSpacePolicy
        case .subscribeEvents:
            return .subscribeEvents
        case .version:
            return .version
        case let .reserved(command):
            return command.name
        }
    }
}

extension IPCCommand: Codable {
    private enum CodingKeys: String, CodingKey {
        case name
        case arguments
    }

    // swiftlint:disable:next cyclomatic_complexity
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let name = try container.decode(IPCCommandName.self, forKey: .name)

        guard !name.isReservedV2 else {
            self = .reserved(IPCReservedCommand(name: name))
            return
        }

        switch name {
        case .state, .listWindows, .listDisplays:
            self = try Self.decodeQueryCommand(name, from: container)
        case .focus, .moveWindow, .swap:
            self = try Self.decodeDirectionalCommand(name, from: container)
        case .moveToDisplay, .toggleFloating, .toggleSticky, .togglePinned, .snapWindow:
            self = try Self.decodeWindowCommand(name, from: container)
        case .dispatchGesture:
            self = .dispatchGesture(try container.decodeRequired(IPCDispatchGestureCommand.self, forKey: .arguments))
        case .manualPreselect:
            self = .manualPreselect(try container.decodeRequired(IPCManualPreselectCommand.self, forKey: .arguments))
        case .bspTree:
            self = .bspTree(try container.decodeRequired(IPCBSPTreeCommand.self, forKey: .arguments))
        case .switchTag, .toggleTag, .tagAdd, .tagRemove:
            self = try Self.decodeTagCommand(name, from: container)
        case .moveToTag:
            self = .moveToTag(try container.decodeRequired(IPCMoveToTagCommand.self, forKey: .arguments))
        case .setEngine:
            self = .setEngine(try container.decodeRequired(IPCSetEngineCommand.self, forKey: .arguments))
        case .cycleEngine:
            let command = try container.decodeIfPresent(IPCCycleEngineCommand.self, forKey: .arguments) ?? .init()
            self = .cycleEngine(command)
        case .reload:
            self = .reload(try container.decodeIfPresent(IPCReloadCommand.self, forKey: .arguments) ?? .init())
        case .restoreWindows:
            self = .restoreWindows(
                try container.decodeIfPresent(IPCRestoreWindowsCommand.self, forKey: .arguments) ?? .init()
            )
        case .setSpacePolicy:
            self = .setSpacePolicy(try container.decodeRequired(IPCSetSpacePolicyCommand.self, forKey: .arguments))
        case .subscribeEvents:
            let command = try container.decodeIfPresent(IPCSubscribeEventsCommand.self, forKey: .arguments) ?? .init()
            self = .subscribeEvents(command)
        case .version:
            self = .version(try container.decodeIfPresent(IPCVersionCommand.self, forKey: .arguments) ?? .init())
        default:
            self = .reserved(IPCReservedCommand(name: name))
        }
    }

    private static func decodeQueryCommand(
        _ name: IPCCommandName,
        from container: KeyedDecodingContainer<CodingKeys>
    ) throws -> IPCCommand {
        switch name {
        case .listWindows:
            return .listWindows(
                try container.decodeIfPresent(IPCWindowQueryCommand.self, forKey: .arguments) ?? .init()
            )
        case .listDisplays:
            return .listDisplays(
                try container.decodeIfPresent(IPCDisplayQueryCommand.self, forKey: .arguments) ?? .init()
            )
        default:
            return .state(try container.decodeIfPresent(IPCStateCommand.self, forKey: .arguments) ?? .init())
        }
    }

    private static func decodeWindowCommand(
        _ name: IPCCommandName,
        from container: KeyedDecodingContainer<CodingKeys>
    ) throws -> IPCCommand {
        switch name {
        case .moveToDisplay:
            return .moveToDisplay(try container.decodeRequired(IPCMoveToDisplayCommand.self, forKey: .arguments))
        case .toggleFloating:
            let command = try container.decodeIfPresent(IPCFloatingCommand.self, forKey: .arguments) ?? .init()
            return .toggleFloating(command)
        case .toggleSticky:
            let command = try container.decodeIfPresent(IPCStickyCommand.self, forKey: .arguments) ?? .init()
            return .toggleSticky(command)
        case .togglePinned:
            let command = try container.decodeIfPresent(IPCPinnedCommand.self, forKey: .arguments) ?? .init()
            return .togglePinned(command)
        default:
            return .snapWindow(try container.decodeRequired(IPCSnapWindowCommand.self, forKey: .arguments))
        }
    }

    // swiftlint:disable:next cyclomatic_complexity
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)

        switch self {
        case let .state(command):
            try container.encode(command, forKey: .arguments)
        case let .listWindows(command):
            try container.encode(command, forKey: .arguments)
        case let .listDisplays(command):
            try container.encode(command, forKey: .arguments)
        case let .focus(command), let .moveWindow(command), let .swap(command):
            try container.encode(command, forKey: .arguments)
        case let .moveToDisplay(command):
            try container.encode(command, forKey: .arguments)
        case let .toggleFloating(command):
            try container.encode(command, forKey: .arguments)
        case let .toggleSticky(command):
            try container.encode(command, forKey: .arguments)
        case let .togglePinned(command):
            try container.encode(command, forKey: .arguments)
        case let .snapWindow(command):
            try container.encode(command, forKey: .arguments)
        case let .dispatchGesture(command):
            try container.encode(command, forKey: .arguments)
        case let .manualPreselect(command):
            try container.encode(command, forKey: .arguments)
        case let .bspTree(command):
            try container.encode(command, forKey: .arguments)
        case let .switchTag(command), let .toggleTag(command), let .tagAdd(command), let .tagRemove(command):
            try container.encode(command, forKey: .arguments)
        case let .moveToTag(command):
            try container.encode(command, forKey: .arguments)
        case let .setEngine(command):
            try container.encode(command, forKey: .arguments)
        case let .cycleEngine(command):
            try container.encode(command, forKey: .arguments)
        case let .reload(command):
            try container.encode(command, forKey: .arguments)
        case let .restoreWindows(command):
            try container.encode(command, forKey: .arguments)
        case let .setSpacePolicy(command):
            try container.encode(command, forKey: .arguments)
        case let .subscribeEvents(command):
            try container.encode(command, forKey: .arguments)
        case let .version(command):
            try container.encode(command, forKey: .arguments)
        case .reserved:
            break
        }
    }

    private static func decodeDirectionalCommand(
        _ name: IPCCommandName,
        from container: KeyedDecodingContainer<CodingKeys>
    ) throws -> IPCCommand {
        let command = try container.decodeRequired(IPCDirectionalCommand.self, forKey: .arguments)
        switch name {
        case .focus:
            return .focus(command)
        case .moveWindow:
            return .moveWindow(command)
        default:
            return .swap(command)
        }
    }

    private static func decodeTagCommand(
        _ name: IPCCommandName,
        from container: KeyedDecodingContainer<CodingKeys>
    ) throws -> IPCCommand {
        let command = try container.decodeRequired(IPCTagCommand.self, forKey: .arguments)
        switch name {
        case .switchTag:
            return .switchTag(command)
        case .toggleTag:
            return .toggleTag(command)
        case .tagAdd:
            return .tagAdd(command)
        default:
            return .tagRemove(command)
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
