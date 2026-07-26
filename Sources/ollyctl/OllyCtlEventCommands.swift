import ArgumentParser
import ollyIPC

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
