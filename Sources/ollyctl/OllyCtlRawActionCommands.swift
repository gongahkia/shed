import ArgumentParser
import ollyIPC

struct RunRawAction: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "run-raw-action",
        abstract: "Run a configured raw shell action by label."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Raw action label.")
    var label: String

    func run() throws {
        try OllyCtlRunner(options: options).send(.runRawAction(IPCRunRawActionCommand(label: label)))
    }
}
