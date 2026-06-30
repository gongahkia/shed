import ollyIPC

extension OllyRuntime {
    func controlResponse(for request: IPCRequestEnvelope) async throws -> IPCResponseEnvelope? {
        switch request.command {
        case let .focus(command):
            try await focus(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "focus changed")))
        case let .moveWindow(command):
            try await moveWindow(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "window moved")))
        case let .swap(command):
            try await swapWindow(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "window swapped")))
        case .toggleFloating, .toggleSticky, .togglePinned:
            return try await windowFlagResponse(for: request)
        case .snapWindow, .showOverlay, .dispatchGesture:
            return try await interactionResponse(for: request)
        case .reload:
            try await reloadConfig()
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "config reloaded")))
        case .restoreWindows, .runRawAction:
            return await rawControlResponse(for: request)
        case .scratchpadAdd, .scratchpadToggle, .scratchpadRemove:
            return try await scratchpadResponse(for: request)
        case .setSpacePolicy, .setFocusPolicy:
            return await policyResponse(for: request)
        default:
            preconditionFailure("invalid control command")
        }
    }

    private func windowFlagResponse(for request: IPCRequestEnvelope) async throws -> IPCResponseEnvelope {
        switch request.command {
        case let .toggleFloating(command):
            try await toggleFloating(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "floating toggled")))
        case let .toggleSticky(command):
            try await toggleSticky(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "sticky toggled")))
        case let .togglePinned(command):
            try await togglePinned(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "pinned toggled")))
        default:
            preconditionFailure("invalid window flag command")
        }
    }

    private func interactionResponse(for request: IPCRequestEnvelope) async throws -> IPCResponseEnvelope {
        switch request.command {
        case let .snapWindow(command):
            try await snapWindow(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "window snapped")))
        case let .showOverlay(command):
            await showOverlay(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "overlay shown")))
        case let .dispatchGesture(command):
            try await dispatchGesture(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "gesture dispatched")))
        default:
            preconditionFailure("invalid interaction command")
        }
    }

    private func policyResponse(for request: IPCRequestEnvelope) async -> IPCResponseEnvelope {
        switch request.command {
        case let .setSpacePolicy(command):
            await setSpacePolicy(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "space policy set")))
        case let .setFocusPolicy(command):
            await setFocusPolicy(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "focus policy set")))
        default:
            preconditionFailure("invalid policy command")
        }
    }

    private func rawControlResponse(for request: IPCRequestEnvelope) async -> IPCResponseEnvelope {
        switch request.command {
        case let .restoreWindows(command):
            let info = await restoreWindows(command)
            return .ok(id: request.id, result: .restoredWindows(info))
        case let .runRawAction(command):
            await runRawAction(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "raw action dispatched")))
        default:
            preconditionFailure("invalid raw control command")
        }
    }
}
