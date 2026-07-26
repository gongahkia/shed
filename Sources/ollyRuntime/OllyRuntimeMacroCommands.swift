import ollyIPC

extension OllyRuntime {
    func macroResponse(for request: IPCRequestEnvelope) async throws -> IPCResponseEnvelope {
        switch request.command {
        case let .macroStart(command):
            let info = try await macroRecorder.start(name: command.name)
            return .ok(id: request.id, result: .macro(info))
        case .macroStop:
            let info = try await macroRecorder.stop()
            return .ok(id: request.id, result: .macro(info))
        case let .macroRun(command):
            let info = try await runMacro(name: command.name)
            return .ok(id: request.id, result: .macro(info))
        case .macroList:
            let info = try await macroRecorder.list()
            return .ok(id: request.id, result: .macros(info))
        case let .macroDelete(command):
            try await macroRecorder.delete(name: command.name)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "macro deleted")))
        default:
            preconditionFailure("invalid macro command")
        }
    }

    func runMacro(name: String) async throws -> IPCMacroInfo {
        let macro = try await macroRecorder.run(name: name)
        for command in macro.commands {
            try await executeMacroCommand(command)
        }
        return macro.info
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    private func executeMacroCommand(_ command: IPCCommand) async throws {
        switch command {
        case let .focus(payload):
            try await focus(payload)
        case let .moveWindow(payload):
            try await moveWindow(payload)
        case let .swap(payload):
            try await swapWindow(payload)
        case let .toggleFloating(payload):
            try await toggleFloating(payload)
        case let .toggleSticky(payload):
            try await toggleSticky(payload)
        case let .togglePinned(payload):
            try await togglePinned(payload)
        case let .snapWindow(payload):
            try await snapWindow(payload)
        case let .showOverlay(payload):
            await showOverlay(payload)
        case let .dispatchGesture(payload):
            try await dispatchGesture(payload)
        case let .switchTag(payload):
            try await switchTag(payload)
        case let .toggleTag(payload):
            try await toggleTag(payload)
        case let .tagAdd(payload):
            try await addTag(payload)
        case let .tagRemove(payload):
            try await removeTag(payload)
        case let .moveToTag(payload):
            try await moveToTag(payload)
        case let .moveToDisplay(payload):
            try await moveToDisplay(payload)
        case let .setEngine(payload):
            try await setEngine(payload)
        case let .cycleEngine(payload):
            try await cycleEngine(payload)
        case let .manualPreselect(payload):
            try await manualPreselect(payload)
        case let .bspTree(payload):
            try await mutateBSPTree(payload)
        case .reload:
            try await reloadConfig()
        case let .restoreWindows(payload):
            _ = await restoreWindows(payload)
        case let .scratchpadAdd(payload):
            _ = try await addScratchpad(payload)
        case let .scratchpadToggle(payload):
            _ = try await toggleScratchpad(payload)
        case .scratchpadList:
            _ = try await scratchpadListInfo()
        case let .scratchpadRemove(payload):
            _ = try await removeScratchpad(payload)
        case let .runRawAction(payload):
            await runRawAction(payload)
        case let .setSpacePolicy(payload):
            await setSpacePolicy(payload)
        case let .setFocusPolicy(payload):
            await setFocusPolicy(payload)
        case .state, .listWindows, .listDisplays, .listCooperativeApps, .explainWindow, .explainRule,
             .macroStart, .macroStop, .macroRun, .macroList, .macroDelete, .subscribeEvents, .version, .reserved:
            return
        }
    }
}
