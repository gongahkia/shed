import ArgumentParser
import ollyIPC

struct SetSpacePolicy: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "set-space-policy",
        abstract: "Set native Space drift handling."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Policy: follow-window, rehome, unmanage.")
    var policy: String

    func run() throws {
        try OllyCtlRunner(options: options).send(.setSpacePolicy(IPCSetSpacePolicyCommand(
            policy: try parseSpacePolicy(policy)
        )))
    }
}
