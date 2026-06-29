import ArgumentParser
import ollyCore
import ollyIPC
import ollyKit

struct ListWindows: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "list-windows",
        abstract: "List known windows."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(help: "Limit output to one window ID.")
    var windowID: WindowID?

    @Option(help: "Limit output to one display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCWindowQueryCommand(windowID: windowID, displayID: displayID)
        try OllyCtlRunner(options: options).send(.listWindows(command))
    }
}

struct ListDisplays: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "list-displays",
        abstract: "List known displays and active tags."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(help: "Limit output to one display ID.")
    var displayID: DisplayID?

    func run() throws {
        try OllyCtlRunner(options: options).send(.listDisplays(IPCDisplayQueryCommand(displayID: displayID)))
    }
}

struct MoveToDisplay: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "move-to-display",
        abstract: "Move a window to a display."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Target display ID.")
    var displayID: DisplayID

    @Option(help: "Window ID; focused window is used when omitted.")
    var windowID: WindowID?

    func run() throws {
        let command = IPCMoveToDisplayCommand(displayID: displayID, windowID: windowID)
        try OllyCtlRunner(options: options).send(.moveToDisplay(command))
    }
}

struct ToggleFloating: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "toggle-floating",
        abstract: "Toggle or set a window's floating state."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(help: "Window ID; focused window is used when omitted.")
    var windowID: WindowID?

    @Option(help: "Target display ID for re-arrange; inferred when omitted.")
    var displayID: DisplayID?

    @Option(help: "Set true or false instead of toggling.")
    var floating: Bool?

    func run() throws {
        let command = IPCFloatingCommand(windowID: windowID, floating: floating, displayID: displayID)
        try OllyCtlRunner(options: options).send(.toggleFloating(command))
    }
}
