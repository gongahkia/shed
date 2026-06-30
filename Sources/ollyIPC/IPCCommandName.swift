import Foundation

public enum IPCCommandName: String, CaseIterable, Codable, Equatable, Sendable {
    case state
    case focus
    case listWindows = "list-windows"
    case listDisplays = "list-displays"
    case moveWindow = "move-window"
    case moveToDisplay = "move-to-display"
    case swap
    case toggleFloating = "toggle-floating"
    case snapWindow = "snap-window"
    case dispatchGesture = "dispatch-gesture"
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
    case scratchpadAdd = "scratchpad-add"
    case scratchpadToggle = "scratchpad-toggle"
    case scratchpadList = "scratchpad-list"
    case scratchpadRemove = "scratchpad-remove"
    case toggleSticky = "toggle-sticky"
    case togglePinned = "toggle-pinned"
    case explainWindow = "explain-window"
    case explainRule = "explain-rule"
    case macroStart = "macro-start"
    case macroStop = "macro-stop"
    case macroRun = "macro-run"
    case macroList = "macro-list"
    case macroDelete = "macro-delete"
    case runRawAction = "run-raw-action"
    case setSpacePolicy = "set-space-policy"
    case setFocusPolicy = "set-focus-policy"
    case telemetryStatus = "telemetry-status"
    case telemetryFlush = "telemetry-flush"
    case showOverlay = "show-overlay"
    case listCooperativeApps = "list-cooperative-apps"
}

public extension IPCCommandName {
    static let reservedV2: [IPCCommandName] = [
        .scratchpadAdd,
        .scratchpadToggle,
        .scratchpadList,
        .scratchpadRemove,
        .explainWindow,
        .explainRule,
        .macroStart,
        .macroStop,
        .macroRun,
        .macroList,
        .macroDelete,
        .runRawAction,
        .telemetryStatus,
        .telemetryFlush,
        .showOverlay,
        .listCooperativeApps
    ]

    var isReservedV2: Bool {
        Self.reservedV2.contains(self)
    }
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
    case axPermission
    case config
    case display
    case engine
    case engineChange
    case focus
    case focusBlocked
    case fullscreen
    case macro
    case rawAction
    case space
    case tag
    case window

    public static let v1Cases: [IPCEventKind] = [.axPermission, .display, .engine, .focus, .tag, .window]
}

public struct IPCReservedCommand: Codable, Equatable, Sendable {
    public let name: IPCCommandName

    public init(name: IPCCommandName) {
        precondition(name.isReservedV2)
        self.name = name
    }
}
