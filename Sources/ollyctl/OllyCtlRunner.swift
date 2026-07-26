import ArgumentParser
import Foundation
import ollyCore
import ollyIPC
import ollyLayouts

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

    func streamEvents(_ command: IPCSubscribeEventsCommand) throws {
        let request = IPCRequestEnvelope(command: .subscribeEvents(command))
        let requestData = try JSONEncoder().encode(request)
        let stream = try UnixDomainSocketClient(socketPath: options.socketPath).openLineStream()
        defer {
            stream.close()
        }

        try stream.sendLine(requestData)

        while true {
            do {
                let line = try stream.readLine()
                try emitEventOrConsumeSubscriptionResponse(line)
            } catch IPCSocketError.connectionClosedBeforeLine {
                return
            }
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
        case let .cooperativeApps(info):
            return renderCooperativeApps(info)
        case .macro, .macros:
            return renderMacroResult(result)
        case let .restoredWindows(info):
            return "restored \(info.restoredCount), skipped \(info.skippedCount), failed \(info.failedCount)"
        case let .scratchpads(info):
            return renderScratchpads(info)
        default:
            return renderStructuredResult(result)
        }
    }

    private func renderStructuredResult(_ result: IPCCommandResult) -> String {
        switch result {
        case let .ruleExplanation(explanation):
            return OllyCtlRuleExplanationRenderer().render(explanation)
        case let .state(snapshot):
            return renderState(snapshot)
        case let .subscribed(info):
            return "subscribed: \(info.eventKinds.map(\.rawValue).joined(separator: ", "))"
        case let .version(info):
            let commands = info.supportedCommands.map(\.rawValue).joined(separator: ", ")
            let events = info.supportedEventKinds.map(\.rawValue).joined(separator: ", ")
            return "ipc v\(info.protocolVersion)\ncommands: \(commands)\nevents: \(events)"
        default:
            preconditionFailure("invalid structured result")
        }
    }

    private func emitEventOrConsumeSubscriptionResponse(_ line: Data) throws {
        if let response = try? JSONDecoder().decode(IPCResponseEnvelope.self, from: line) {
            if let error = response.error {
                throw OllyCtlError("\(error.code): \(error.message)")
            }
            return
        }

        if options.json {
            try writeLine(line)
        } else {
            let event = try JSONDecoder().decode(IPCEventEnvelope.self, from: line)
            writeLine(renderEvent(event))
        }
    }

    private func renderEvent(_ envelope: IPCEventEnvelope) -> String {
        switch envelope.event {
        case let .axPermission(event):
            return "ax-permission \(event.status)"
        case let .engine(event):
            return renderEngineEvent(event)
        case let .focus(event):
            return renderFocusEvent(event)
        case let .focusBlocked(event):
            let bundle = event.bundleID.map { " bundle \($0)" } ?? ""
            return "focus-blocked pid \(event.processID)\(bundle)"
        case let .fullscreen(event):
            let state = event.didEnter ? "entered" : "exited"
            return "fullscreen \(state) window \(event.windowID)"
        case let .rawAction(event):
            let exit = event.exit.map { " exit \($0)" } ?? ""
            return "raw-action \(event.label) \(event.status.rawValue)\(exit)"
        case let .runtimeError(event):
            return "runtime-error \(event.message)"
        case let .space(event):
            return "space \(event.action.rawValue) window \(event.windowID)"
        }
    }

    private func renderFocusEvent(_ event: IPCFocusEvent) -> String {
        let window = event.focusedWindowID.map(String.init) ?? "-"
        let display = event.displayID.map { " display \($0)" } ?? ""
        return "focus window \(window)\(display)"
    }

    private func renderEngineEvent(_ event: EngineEvent) -> String {
        switch event {
        case let .arranged(payload):
            return "engine arranged display \(payload.displayID) \(payload.engineID.rawValue)"
        case let .masterSwapped(payload):
            return renderPrimarySwapped(payload)
        case let .manualPreselected(payload):
            return "engine manual-preselected window \(payload.windowID.map(String.init) ?? "-")"
        case let .manualWindowInserted(payload):
            return "engine manual-inserted window \(payload.windowID)"
        case let .bspTreeChanged(payload):
            return "engine bsp-tree-changed \(payload.action.rawValue)"
        case let .niriColumnCreated(payload):
            return "engine niri-column-created \(payload.columnIndex) window \(payload.windowID)"
        case let .niriWindowStacked(payload):
            return "engine niri-window-stacked \(payload.columnIndex) window \(payload.windowID)"
        case let .niriColumnWidthChanged(payload):
            return "engine niri-column-width \(payload.columnIndex) \(payload.widthPreset.rawValue)"
        }
    }

    private func renderCooperativeApps(_ info: IPCCooperativeAppsInfo) -> String {
        guard !info.apps.isEmpty else {
            return "no cooperative apps"
        }
        return info.apps.map { app in
            "\(app.bundleID) \(app.behavior) windows \(app.detectedWindowCount)"
        }.joined(separator: "\n")
    }

    private func renderMacro(_ info: IPCMacroInfo) -> String {
        "macro \(info.name): \(info.commandCount) commands, \(info.recordedDurationMs)ms"
    }

    private func renderMacroResult(_ result: IPCCommandResult) -> String {
        switch result {
        case let .macro(info):
            return renderMacro(info)
        case let .macros(info):
            return renderMacros(info)
        default:
            preconditionFailure("invalid macro result")
        }
    }

    private func renderMacros(_ info: IPCMacroListInfo) -> String {
        guard !info.macros.isEmpty else {
            return "no macros"
        }
        return info.macros.map(renderMacro).joined(separator: "\n")
    }

    private func renderScratchpads(_ info: IPCScratchpadListInfo) -> String {
        guard !info.scratchpads.isEmpty else {
            return "no scratchpads"
        }
        return info.scratchpads.map { scratchpad in
            let bundle = scratchpad.bundleID.map { " bundle \($0)" } ?? ""
            let title = scratchpad.titleRegex.map { " title-regex \($0)" } ?? ""
            let role = scratchpad.role.map { " role \($0)" } ?? ""
            let state = scratchpad.isVisible ? "visible" : "hidden"
            return "scratchpad \(scratchpad.name): \(state)\(bundle)\(title)\(role)"
        }.joined(separator: "\n")
    }

    private func renderPrimarySwapped(_ payload: MasterSwappedEvent) -> String {
        let previous = payload.previousMaster.map(String.init) ?? "-"
        let current = payload.currentMaster.map(String.init) ?? "-"
        return "engine master-swapped \(previous) -> \(current)"
    }

    private func writeLine(_ line: Data) throws {
        var output = line
        output.append(JSONLineCodec.lineFeed)
        FileHandle.standardOutput.write(output)
    }

    private func writeLine(_ line: String) {
        FileHandle.standardOutput.write(Data((line + "\n").utf8))
    }

    private func renderState(_ snapshot: IPCStateSnapshot) -> String {
        let displays = snapshot.displays.map { display in
            let tags = display.activeTags.map { String($0.rawValue) }.joined(separator: ",")
            return "display \(display.displayID): tags [\(tags)]"
        }
        let windows = snapshot.windows.map { window in
            let title = window.title.map { " \"\($0)\"" } ?? ""
            let bundle = window.bundleID.map { " bundle \($0)" } ?? ""
            let floating = window.isFloating ? " floating" : ""
            let offSpace = window.isOffSpace ? " off-space" : ""
            let order = window.layoutOrder.map { " order \($0)" } ?? ""
            return "window \(window.windowID): pid \(window.processID)\(bundle)\(floating)\(offSpace)\(order)\(title)"
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
            let values = IPCEventKind.allCases.map(\.rawValue).joined(separator: ", ")
            throw ValidationError("event kind must be one of: \(values)")
        }
        return kind
    }
}

func parseSpacePolicy(_ rawValue: String) throws -> NativeSpaceDriftPolicy {
    guard let policy = NativeSpaceDriftPolicy(rawValue: rawValue) else {
        throw ValidationError("space policy must be one of: follow-window, rehome, unmanage")
    }
    return policy
}
