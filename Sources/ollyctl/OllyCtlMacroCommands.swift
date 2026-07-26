import ArgumentParser
import ollyIPC

struct Macro: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "macro",
        abstract: "Record and replay IPC command macros.",
        subcommands: [MacroRecord.self, MacroRun.self, MacroList.self, MacroDelete.self]
    )
}

struct MacroRecord: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "record",
        abstract: "Start or stop macro recording.",
        subcommands: [MacroRecordStart.self, MacroRecordStop.self]
    )
}

struct MacroRecordStart: ParsableCommand {
    static let configuration = CommandConfiguration(commandName: "start", abstract: "Start macro recording.")

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Macro name.")
    var name: String

    func run() throws {
        try OllyCtlRunner(options: options).send(.macroStart(IPCMacroStartCommand(name: name)))
    }
}

struct MacroRecordStop: ParsableCommand {
    static let configuration = CommandConfiguration(commandName: "stop", abstract: "Stop macro recording.")

    @OptionGroup
    var options: ClientOptions

    func run() throws {
        try OllyCtlRunner(options: options).send(.macroStop(IPCMacroStopCommand()))
    }
}

struct MacroRun: ParsableCommand {
    static let configuration = CommandConfiguration(commandName: "run", abstract: "Replay a recorded macro.")

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Macro name.")
    var name: String

    func run() throws {
        try OllyCtlRunner(options: options).send(.macroRun(IPCMacroRunCommand(name: name)))
    }
}

struct MacroList: ParsableCommand {
    static let configuration = CommandConfiguration(commandName: "list", abstract: "List recorded macros.")

    @OptionGroup
    var options: ClientOptions

    func run() throws {
        try OllyCtlRunner(options: options).send(.macroList(IPCMacroListCommand()))
    }
}

struct MacroDelete: ParsableCommand {
    static let configuration = CommandConfiguration(commandName: "delete", abstract: "Delete a recorded macro.")

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Macro name.")
    var name: String

    func run() throws {
        try OllyCtlRunner(options: options).send(.macroDelete(IPCMacroDeleteCommand(name: name)))
    }
}
