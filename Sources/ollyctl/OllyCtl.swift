import ArgumentParser
import Foundation
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit

@main
struct OllyCtl: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "ollyctl",
        abstract: "Control a running olly instance over IPC.",
        subcommands: [
            State.self,
            ListWindows.self,
            ListDisplays.self,
            Focus.self,
            MoveWindow.self,
            MoveToDisplay.self,
            Swap.self,
            ToggleFloating.self,
            SnapWindow.self,
            DispatchGesture.self,
            SwitchTag.self,
            MoveToTag.self,
            ToggleTag.self,
            SetEngine.self,
            CycleEngine.self,
            ManualPreselect.self,
            BSPTree.self,
            TagAdd.self,
            TagRemove.self,
            Reload.self,
            RestoreWindows.self,
            Doctor.self,
            SubscribeEvents.self,
            Events.self,
            InitConfig.self,
            MigrateConfig.self,
            Version.self
        ]
    )
}

struct ClientOptions: ParsableArguments {
    @Option(help: "Path to the olly Unix-domain socket.")
    var socket: String?

    @Flag(help: "Print JSON output.")
    var json = false

    var socketPath: IPCSocketPath {
        socket.map(IPCSocketPath.init) ?? .resolved()
    }
}

struct State: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Print current olly state.")

    @OptionGroup
    var options: ClientOptions

    @Option(help: "Limit state to one display ID.")
    var displayID: DisplayID?

    func run() throws {
        try OllyCtlRunner(options: options).send(.state(IPCStateCommand(displayID: displayID)))
    }
}

struct Focus: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Focus a window in a direction.")

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Direction: up, down, left, right, next, previous.")
    var direction: String

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCDirectionalCommand(direction: try parseDirection(direction), displayID: displayID)
        try OllyCtlRunner(options: options).send(.focus(command))
    }
}

struct MoveWindow: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "move-window",
        abstract: "Move the focused window in a direction."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Direction: up, down, left, right, next, previous.")
    var direction: String

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCDirectionalCommand(direction: try parseDirection(direction), displayID: displayID)
        try OllyCtlRunner(options: options).send(.moveWindow(command))
    }
}

struct Swap: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Swap the focused window in a direction.")

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Direction: up, down, left, right, next, previous.")
    var direction: String

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCDirectionalCommand(direction: try parseDirection(direction), displayID: displayID)
        try OllyCtlRunner(options: options).send(.swap(command))
    }
}

struct SwitchTag: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "switch-tag",
        abstract: "Switch the active display view to one tag."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Tag index in 0..<64.")
    var tag: Int

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCTagCommand(tag: try parseTag(tag), displayID: displayID)
        try OllyCtlRunner(options: options).send(.switchTag(command))
    }
}

struct MoveToTag: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "move-to-tag",
        abstract: "Move a window to a tag."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Tag index in 0..<64.")
    var tag: Int

    @Option(help: "Window ID; focused window is used when omitted.")
    var windowID: WindowID?

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCMoveToTagCommand(
            tag: try parseTag(tag),
            windowID: windowID,
            displayID: displayID
        )
        try OllyCtlRunner(options: options).send(.moveToTag(command))
    }
}

struct ToggleTag: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "toggle-tag",
        abstract: "Toggle a tag in the active display view."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Tag index in 0..<64.")
    var tag: Int

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCTagCommand(tag: try parseTag(tag), displayID: displayID)
        try OllyCtlRunner(options: options).send(.toggleTag(command))
    }
}

struct SetEngine: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "set-engine",
        abstract: "Bind a layout engine."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Layout engine ID.")
    var engineID: String

    @Option(help: "Tag index in 0..<64; active tag is used when omitted.")
    var tag: Int?

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCSetEngineCommand(
            engineID: LayoutEngineID(rawValue: engineID),
            tag: try tag.map(parseTag),
            displayID: displayID
        )
        try OllyCtlRunner(options: options).send(.setEngine(command))
    }
}

struct CycleEngine: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "cycle-engine",
        abstract: "Cycle the active tag through configured layout engines."
    )

    @OptionGroup
    var options: ClientOptions

    @Flag(help: "Cycle in reverse order.")
    var reverse = false

    @Option(help: "Tag index in 0..<64; active tag is used when omitted.")
    var tag: Int?

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCCycleEngineCommand(reverse: reverse, tag: try tag.map(parseTag), displayID: displayID)
        try OllyCtlRunner(options: options).send(.cycleEngine(command))
    }
}

struct TagAdd: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "tag-add",
        abstract: "Add a tag to the active display view."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Tag index in 0..<64.")
    var tag: Int

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        try OllyCtlRunner(options: options).send(.tagAdd(IPCTagCommand(tag: try parseTag(tag), displayID: displayID)))
    }
}

struct TagRemove: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "tag-remove",
        abstract: "Remove a tag from the active display view."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Tag index in 0..<64.")
    var tag: Int

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCTagCommand(tag: try parseTag(tag), displayID: displayID)
        try OllyCtlRunner(options: options).send(.tagRemove(command))
    }
}

struct Reload: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Reload olly config.")

    @OptionGroup
    var options: ClientOptions

    func run() throws {
        try OllyCtlRunner(options: options).send(.reload(IPCReloadCommand()))
    }
}

struct RestoreWindows: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "restore-windows",
        abstract: "Restore windows parked or hidden by olly."
    )

    @OptionGroup
    var options: ClientOptions

    func run() throws {
        try OllyCtlRunner(options: options).send(.restoreWindows(IPCRestoreWindowsCommand()))
    }
}

struct SubscribeEvents: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "subscribe-events",
        abstract: "Request an event-stream subscription."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(name: .customLong("event-kind"), help: "Event kind; repeat for multiple kinds.")
    var eventKinds: [String] = []

    @Flag(help: "Ask the server to send current state before live events.")
    var replayCurrentState = false

    func run() throws {
        let command = IPCSubscribeEventsCommand(
            eventKinds: try parseEventKinds(eventKinds),
            replayCurrentState: replayCurrentState
        )
        try OllyCtlRunner(options: options).send(.subscribeEvents(command))
    }
}

struct Events: ParsableCommand {
    static let configuration = CommandConfiguration(
        abstract: "Stream olly events."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(name: .customLong("event-kind"), help: "Event kind; repeat for multiple kinds.")
    var eventKinds: [String] = []

    @Flag(help: "Ask the server to send current state before live events.")
    var replayCurrentState = false

    func run() throws {
        let command = IPCSubscribeEventsCommand(
            eventKinds: try parseEventKinds(eventKinds),
            replayCurrentState: replayCurrentState
        )
        try OllyCtlRunner(options: options).streamEvents(command)
    }
}

struct Version: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Print IPC protocol version.")

    @OptionGroup
    var options: ClientOptions

    func run() throws {
        try OllyCtlRunner(options: options).send(.version(IPCVersionCommand()))
    }
}

struct MigrateConfig: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "migrate-config",
        abstract: "Print a DSLVersion migration diff for Config.swift."
    )

    @Option(name: .customLong("config"), help: "Path to Config.swift.")
    var configPath: String?

    func run() throws {
        let url = configPath.map(URL.init(fileURLWithPath:)) ?? ConfigLoader.defaultSourceURL()
        let source = try String(contentsOf: url, encoding: .utf8)
        let suggestion = DSLVersionMigrator.suggestion(for: source, sourcePath: url.path)
        if suggestion.isEmpty {
            print("config already declares DSL \(DSLVersion.current.rawValue)")
        } else {
            print(suggestion.diff)
        }
    }
}
