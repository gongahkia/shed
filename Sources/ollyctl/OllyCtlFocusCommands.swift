import ArgumentParser
import ollyIPC

struct SetFocusPolicy: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "set-focus-policy",
        abstract: "Set focus-stealing prevention policy."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(help: "Allowed app bundle ID; repeat for multiple apps.")
    var allowBundleID: [String] = []

    @Option(help: "Maximum accepted programmatic focus events per second.")
    var maxEventsPerSecond: Int?

    @Option(help: "Minimum interval between programmatic focus events in milliseconds.")
    var minHumanIntervalMilliseconds: Int?

    func run() throws {
        try OllyCtlRunner(options: options).send(.setFocusPolicy(IPCSetFocusPolicyCommand(
            allowedBundleIDs: allowBundleID.isEmpty ? nil : allowBundleID,
            maxEventsPerSecond: maxEventsPerSecond,
            minHumanIntervalMilliseconds: minHumanIntervalMilliseconds
        )))
    }
}
