import ArgumentParser
import Foundation
import ollyCore
import ollyIPC
import ollyKit

@main
struct OllyCtl: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "ollyctl",
        abstract: "Control a running olly instance over IPC.",
        subcommands: [
            State.self,
            Focus.self,
            Swap.self,
            MoveToTag.self,
            SetEngine.self,
            TagAdd.self,
            TagRemove.self,
            Reload.self,
            SubscribeEvents.self,
            Version.self
        ]
    )
}

struct ClientOptions: ParsableArguments {
    @Option(help: "Path to the olly Unix-domain socket.")
    var socket: String?

    @Flag(help: "Print the response envelope as JSON.")
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

struct Version: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Print IPC protocol version.")

    @OptionGroup
    var options: ClientOptions

    func run() throws {
        try OllyCtlRunner(options: options).send(.version(IPCVersionCommand()))
    }
}

struct OllyCtlRunner {
    let options: ClientOptions

    func send(_ command: IPCCommand) throws {
        let request = IPCRequestEnvelope(command: command)
        let requestData = try JSONEncoder().encode(request)
        let client = UnixDomainSocketClient(socketPath: options.socketPath)
        let responseLine = try client.sendLine(requestData)
        let response = try JSONDecoder().decode(IPCResponseEnvelope.self, from: responseLine)

        if options.json {
            print(try renderJSON(response))
        } else {
            print(try renderPretty(response))
        }
    }

    private func renderJSON(_ response: IPCResponseEnvelope) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(response)
        guard let string = String(data: data, encoding: .utf8) else {
            throw OllyCtlError("failed to encode JSON response as UTF-8")
        }
        return string
    }

    private func renderPretty(_ response: IPCResponseEnvelope) throws -> String {
        if let error = response.error {
            throw OllyCtlError("\(error.code): \(error.message)")
        }

        guard let result = response.result else {
            return response.status == .success ? "ok" : "error"
        }

        switch result {
        case let .acknowledged(payload):
            return payload.message ?? "ok"
        case let .state(snapshot):
            return renderState(snapshot)
        case let .subscribed(info):
            return "subscribed: \(info.eventKinds.map(\.rawValue).joined(separator: ", "))"
        case let .version(info):
            let commands = info.supportedCommands.map(\.rawValue).joined(separator: ", ")
            return "ipc v\(info.protocolVersion)\ncommands: \(commands)"
        }
    }

    private func renderState(_ snapshot: IPCStateSnapshot) -> String {
        let displays = snapshot.displays.map { display in
            let tags = display.activeTags.map { String($0.rawValue) }.joined(separator: ",")
            return "display \(display.displayID): tags [\(tags)]"
        }
        let windows = snapshot.windows.map { window in
            let title = window.title.map { " \"\($0)\"" } ?? ""
            return "window \(window.windowID): pid \(window.processID)\(title)"
        }
        return (displays + windows).isEmpty ? "no state" : (displays + windows).joined(separator: "\n")
    }
}

struct OllyCtlError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}

func parseDirection(_ rawValue: String) throws -> IPCDirection {
    guard let direction = IPCDirection(rawValue: rawValue) else {
        throw ValidationError("direction must be one of: up, down, left, right, next, previous")
    }
    return direction
}

func parseTag(_ rawValue: Int) throws -> IPCTagIndex {
    do {
        return try IPCTagIndex(validating: rawValue)
    } catch {
        throw ValidationError("tag must be in 0..<64")
    }
}

func parseEventKinds(_ rawValues: [String]) throws -> [IPCEventKind] {
    guard !rawValues.isEmpty else {
        return IPCEventKind.allCases
    }

    return try rawValues.map { rawValue in
        guard let kind = IPCEventKind(rawValue: rawValue) else {
            throw ValidationError("event kind must be one of: display, engine, focus, tag, window")
        }
        return kind
    }
}
