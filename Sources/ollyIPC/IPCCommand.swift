// swiftlint:disable file_length
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

public struct IPCVersionCommand: Codable, Equatable, Sendable {
    public init() {}
}

public enum IPCCommand: Equatable, Sendable {
    case state(IPCStateCommand)
    case focus(IPCDirectionalCommand)
    case listWindows(IPCWindowQueryCommand)
    case listDisplays(IPCDisplayQueryCommand)
    case listCooperativeApps(IPCListCooperativeAppsCommand)
    case explainWindow(IPCExplainWindowCommand)
    case explainRule(IPCExplainRuleCommand)
    case moveWindow(IPCDirectionalCommand)
    case moveToDisplay(IPCMoveToDisplayCommand)
    case swap(IPCDirectionalCommand)
    case toggleFloating(IPCFloatingCommand)
    case toggleSticky(IPCStickyCommand)
    case togglePinned(IPCPinnedCommand)
    case snapWindow(IPCSnapWindowCommand)
    case showOverlay(IPCShowOverlayCommand)
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
    case scratchpadAdd(IPCScratchpadAddCommand)
    case scratchpadToggle(IPCScratchpadToggleCommand)
    case scratchpadList(IPCScratchpadListCommand)
    case scratchpadRemove(IPCScratchpadRemoveCommand)
    case macroStart(IPCMacroStartCommand)
    case macroStop(IPCMacroStopCommand)
    case macroRun(IPCMacroRunCommand)
    case macroList(IPCMacroListCommand)
    case macroDelete(IPCMacroDeleteCommand)
    case runRawAction(IPCRunRawActionCommand)
    case setSpacePolicy(IPCSetSpacePolicyCommand)
    case setFocusPolicy(IPCSetFocusPolicyCommand)
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
        case .listCooperativeApps:
            return .listCooperativeApps
        case .explainWindow:
            return .explainWindow
        case .explainRule:
            return .explainRule
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
        case .showOverlay:
            return .showOverlay
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
        case .scratchpadAdd:
            return .scratchpadAdd
        case .scratchpadToggle:
            return .scratchpadToggle
        case .scratchpadList:
            return .scratchpadList
        case .scratchpadRemove:
            return .scratchpadRemove
        case .macroStart:
            return .macroStart
        case .macroStop:
            return .macroStop
        case .macroRun:
            return .macroRun
        case .macroList:
            return .macroList
        case .macroDelete:
            return .macroDelete
        case .runRawAction:
            return .runRawAction
        case .setSpacePolicy:
            return .setSpacePolicy
        case .setFocusPolicy:
            return .setFocusPolicy
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

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let name = try container.decode(IPCCommandName.self, forKey: .name)

        guard !name.isReservedV2 else {
            self = .reserved(IPCReservedCommand(name: name))
            return
        }

        switch name {
        case .state, .listWindows, .listDisplays, .listCooperativeApps, .explainWindow:
            self = try Self.decodeQueryCommand(name, from: container)
        case .explainRule:
            self = .explainRule(try container.decodeRequired(IPCExplainRuleCommand.self, forKey: .arguments))
        case .focus, .moveWindow, .swap:
            self = try Self.decodeDirectionalCommand(name, from: container)
        case .moveToDisplay, .toggleFloating, .toggleSticky, .togglePinned, .snapWindow:
            self = try Self.decodeWindowCommand(name, from: container)
        case .showOverlay, .dispatchGesture:
            self = try Self.decodeInteractionCommand(name, from: container)
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
            self = try Self.decodeRestoreWindows(from: container)
        case .scratchpadAdd, .scratchpadToggle, .scratchpadList, .scratchpadRemove:
            self = try Self.decodeScratchpadCommand(name, from: container)
        case .macroStart, .macroStop, .macroRun, .macroList, .macroDelete:
            self = try Self.decodeMacroCommand(name, from: container)
        case .runRawAction:
            self = .runRawAction(try container.decodeRequired(IPCRunRawActionCommand.self, forKey: .arguments))
        case .setSpacePolicy:
            self = .setSpacePolicy(try container.decodeRequired(IPCSetSpacePolicyCommand.self, forKey: .arguments))
        case .setFocusPolicy:
            self = .setFocusPolicy(try container.decodeRequired(IPCSetFocusPolicyCommand.self, forKey: .arguments))
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
        case .listCooperativeApps:
            return .listCooperativeApps(
                try container.decodeIfPresent(IPCListCooperativeAppsCommand.self, forKey: .arguments) ?? .init()
            )
        case .explainWindow:
            return .explainWindow(
                try container.decodeIfPresent(IPCExplainWindowCommand.self, forKey: .arguments) ?? .init()
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

    private static func decodeInteractionCommand(
        _ name: IPCCommandName,
        from container: KeyedDecodingContainer<CodingKeys>
    ) throws -> IPCCommand {
        switch name {
        case .showOverlay:
            return .showOverlay(try container.decodeRequired(IPCShowOverlayCommand.self, forKey: .arguments))
        default:
            return .dispatchGesture(try container.decodeRequired(IPCDispatchGestureCommand.self, forKey: .arguments))
        }
    }

    private static func decodeMacroCommand(
        _ name: IPCCommandName,
        from container: KeyedDecodingContainer<CodingKeys>
    ) throws -> IPCCommand {
        switch name {
        case .macroStart:
            return .macroStart(try container.decodeRequired(IPCMacroStartCommand.self, forKey: .arguments))
        case .macroStop:
            return .macroStop(try container.decodeIfPresent(IPCMacroStopCommand.self, forKey: .arguments) ?? .init())
        case .macroRun:
            return .macroRun(try container.decodeRequired(IPCMacroRunCommand.self, forKey: .arguments))
        case .macroList:
            return .macroList(try container.decodeIfPresent(IPCMacroListCommand.self, forKey: .arguments) ?? .init())
        default:
            return .macroDelete(try container.decodeRequired(IPCMacroDeleteCommand.self, forKey: .arguments))
        }
    }

    private static func decodeRestoreWindows(
        from container: KeyedDecodingContainer<CodingKeys>
    ) throws -> IPCCommand {
        .restoreWindows(try container.decodeIfPresent(IPCRestoreWindowsCommand.self, forKey: .arguments) ?? .init())
    }

    private static func decodeScratchpadCommand(
        _ name: IPCCommandName,
        from container: KeyedDecodingContainer<CodingKeys>
    ) throws -> IPCCommand {
        switch name {
        case .scratchpadAdd:
            return .scratchpadAdd(try container.decodeRequired(IPCScratchpadAddCommand.self, forKey: .arguments))
        case .scratchpadToggle:
            return .scratchpadToggle(try container.decodeRequired(IPCScratchpadToggleCommand.self, forKey: .arguments))
        case .scratchpadList:
            let command = try container.decodeIfPresent(IPCScratchpadListCommand.self, forKey: .arguments) ?? .init()
            return .scratchpadList(command)
        default:
            return .scratchpadRemove(try container.decodeRequired(IPCScratchpadRemoveCommand.self, forKey: .arguments))
        }
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
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
        case let .listCooperativeApps(command):
            try container.encode(command, forKey: .arguments)
        case let .explainWindow(command):
            try container.encode(command, forKey: .arguments)
        case let .explainRule(command):
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
        case let .showOverlay(command):
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
        case let .scratchpadAdd(command):
            try container.encode(command, forKey: .arguments)
        case let .scratchpadToggle(command):
            try container.encode(command, forKey: .arguments)
        case let .scratchpadList(command):
            try container.encode(command, forKey: .arguments)
        case let .scratchpadRemove(command):
            try container.encode(command, forKey: .arguments)
        case let .macroStart(command):
            try container.encode(command, forKey: .arguments)
        case let .macroStop(command):
            try container.encode(command, forKey: .arguments)
        case let .macroRun(command):
            try container.encode(command, forKey: .arguments)
        case let .macroList(command):
            try container.encode(command, forKey: .arguments)
        case let .macroDelete(command):
            try container.encode(command, forKey: .arguments)
        case let .runRawAction(command):
            try container.encode(command, forKey: .arguments)
        case let .setSpacePolicy(command):
            try container.encode(command, forKey: .arguments)
        case let .setFocusPolicy(command):
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
