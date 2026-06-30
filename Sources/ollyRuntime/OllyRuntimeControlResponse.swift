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
        case let .snapWindow(command):
            try await snapWindow(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "window snapped")))
        case let .dispatchGesture(command):
            try await dispatchGesture(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "gesture dispatched")))
        case .reload:
            try await reloadConfig()
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "config reloaded")))
        case let .restoreWindows(command):
            let info = await restoreWindows(command)
            return .ok(id: request.id, result: .restoredWindows(info))
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
}
