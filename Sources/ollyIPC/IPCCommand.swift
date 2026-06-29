import Foundation
import ollyCore
import ollyKit

public enum IPCCommandName: String, CaseIterable, Codable, Equatable, Sendable {
    case state
    case focus
    case listWindows = "list-windows"
    case listDisplays = "list-displays"
    case moveWindow = "move-window"
    case moveToDisplay = "move-to-display"
    case swap
    case toggleFloating = "toggle-floating"
    case manualPreselect = "manual-preselect"
    case bspTree = "bsp-tree"
    case switchTag = "switch-tag"
    case moveToTag = "move-to-tag"
    case toggleTag = "toggle-tag"
    case setEngine = "set-engine"
    case cycleEngine = "cycle-engine"
    case tagAdd = "tag-add"
    case tagRemove = "tag-remove"
    case reload
    case restoreWindows = "restore-windows"
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
    public let displayID: DisplayID?

    public init(reverse: Bool = false, displayID: DisplayID? = nil) {
        self.reverse = reverse
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

public struct IPCRestoreWindowsCommand: Codable, Equatable, Sendable {
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
    case listWindows(IPCWindowQueryCommand)
    case listDisplays(IPCDisplayQueryCommand)
    case moveWindow(IPCDirectionalCommand)
    case moveToDisplay(IPCMoveToDisplayCommand)
    case swap(IPCDirectionalCommand)
    case toggleFloating(IPCFloatingCommand)
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
    case subscribeEvents(IPCSubscribeEventsCommand)
    case version(IPCVersionCommand)

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

    // swiftlint:disable:next cyclomatic_complexity
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let name = try container.decode(IPCCommandName.self, forKey: .name)

        switch name {
        case .state:
            self = .state(try container.decodeIfPresent(IPCStateCommand.self, forKey: .arguments) ?? .init())
        case .listWindows:
            self = .listWindows(
                try container.decodeIfPresent(IPCWindowQueryCommand.self, forKey: .arguments) ?? .init()
            )
        case .listDisplays:
            self = .listDisplays(
                try container.decodeIfPresent(IPCDisplayQueryCommand.self, forKey: .arguments) ?? .init()
            )
        case .focus, .moveWindow, .swap:
            self = try Self.decodeDirectionalCommand(name, from: container)
        case .moveToDisplay:
            self = .moveToDisplay(try container.decodeRequired(IPCMoveToDisplayCommand.self, forKey: .arguments))
        case .toggleFloating:
            self = .toggleFloating(
                try container.decodeIfPresent(IPCFloatingCommand.self, forKey: .arguments) ?? .init()
            )
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
        case .subscribeEvents:
            let command = try container.decodeIfPresent(IPCSubscribeEventsCommand.self, forKey: .arguments) ?? .init()
            self = .subscribeEvents(command)
        case .version:
            self = .version(try container.decodeIfPresent(IPCVersionCommand.self, forKey: .arguments) ?? .init())
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
        case let .subscribeEvents(command):
            try container.encode(command, forKey: .arguments)
        case let .version(command):
            try container.encode(command, forKey: .arguments)
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
