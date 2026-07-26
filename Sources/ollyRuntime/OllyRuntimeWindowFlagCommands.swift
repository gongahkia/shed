import ollyIPC
import ollyKit

extension OllyRuntime {
    func toggleFloating(_ command: IPCFloatingCommand) async throws {
        let windowID = try command.windowID ?? focusedWindowID.requiredFocusedWindow()
        guard let current = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        let floating = command.floating ?? !current.isFloating
        let state = try await assignment.setFloating(window: windowID, floating: floating)
        let displayID = command.displayID ?? state.displayID ?? selectedDisplay(nil)?.id
        if let displayID {
            try await arrange(displayID: displayID)
        }
    }

    func toggleSticky(_ command: IPCStickyCommand) async throws {
        let windowID = try command.windowID ?? focusedWindowID.requiredFocusedWindow()
        guard let current = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        let sticky = command.sticky ?? !current.isSticky
        let state = try await assignment.setSticky(window: windowID, sticky: sticky)
        let displayID = command.displayID ?? state.displayID ?? selectedDisplay(nil)?.id
        if let displayID {
            try await applyAndArrange(displayID: displayID)
        }
    }

    func togglePinned(_ command: IPCPinnedCommand) async throws {
        let windowID = try command.windowID ?? focusedWindowID.requiredFocusedWindow()
        guard let current = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        let pinned = command.pinned ?? !current.isPinned
        let state = try await assignment.setPinned(window: windowID, pinned: pinned)
        let displayID = command.displayID ?? state.displayID ?? selectedDisplay(nil)?.id
        if pinned, let displayID {
            _ = try await assignment.assign(window: windowID, tags: await tagStore.activeTags(on: displayID))
        }
        if let displayID {
            try await applyAndArrange(displayID: displayID)
        }
    }
}
