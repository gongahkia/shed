import ArgumentParser
import ollyIPC

struct ScratchpadAdd: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "scratchpad-add",
        abstract: "Register a scratchpad window predicate."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(help: "Scratchpad name.")
    var name: String

    @Option(name: .customLong("bundle"), help: "Application bundle ID.")
    var bundleID: String?

    @Option(name: .customLong("title-regex"), help: "Window title regex.")
    var titleRegex: String?

    @Option(help: "AX role, for example AXWindow.")
    var role: String?

    func run() throws {
        let command = IPCScratchpadAddCommand(
            name: name,
            bundleID: bundleID,
            titleRegex: titleRegex,
            role: role
        )
        try OllyCtlRunner(options: options).send(.scratchpadAdd(command))
    }
}

struct ScratchpadToggle: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "scratchpad-toggle",
        abstract: "Toggle a scratchpad window."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(help: "Scratchpad name.")
    var name: String

    func run() throws {
        try OllyCtlRunner(options: options).send(.scratchpadToggle(IPCScratchpadToggleCommand(name: name)))
    }
}

struct ScratchpadList: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "scratchpad-list",
        abstract: "List registered scratchpads."
    )

    @OptionGroup
    var options: ClientOptions

    func run() throws {
        try OllyCtlRunner(options: options).send(.scratchpadList(IPCScratchpadListCommand()))
    }
}

struct ScratchpadRemove: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "scratchpad-remove",
        abstract: "Remove a scratchpad."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(help: "Scratchpad name.")
    var name: String

    func run() throws {
        try OllyCtlRunner(options: options).send(.scratchpadRemove(IPCScratchpadRemoveCommand(name: name)))
    }
}
