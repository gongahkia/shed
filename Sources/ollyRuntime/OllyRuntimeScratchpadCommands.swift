import ApplicationServices
import AppKit
import CoreGraphics
import Foundation
import ollyCore
import ollyIPC
import ollyKit

extension OllyRuntime {
    static func makeScratchpadParker(
        displayProvider: @escaping @Sendable () -> [Display],
        moveWindow: TagWindowMoveHandler?,
        windowMover: WindowMover,
        windowTargets: RuntimeWindowTargets
    ) -> WindowParker {
        let moveWindow = moveWindow ?? { [windowMover, windowTargets] window, frame in
            guard let target = windowTargets.target(for: window) else {
                return
            }
            let displayTarget = target.withFallbackDisplayID(window.displayID)
            await windowMover.setPosition(frame.origin, for: displayTarget)
            await windowMover.setSize(frame.size, for: displayTarget)
            await windowMover.flushNow()
        }
        return WindowParker(displayProvider: { displayProvider() }, moveWindow: moveWindow)
    }

    public static func defaultScratchpadApplicationLauncher(_ bundleID: String) async throws {
        try await launchScratchpadApplication(bundleID: bundleID)
    }

    func scratchpadResponse(for request: IPCRequestEnvelope) async throws -> IPCResponseEnvelope {
        switch request.command {
        case let .scratchpadAdd(command):
            _ = try await addScratchpad(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "scratchpad added")))
        case let .scratchpadToggle(command):
            let entry = try await toggleScratchpad(command)
            let state = entry.isVisible ? "shown" : "hidden"
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "scratchpad \(state)")))
        case let .scratchpadRemove(command):
            _ = try await removeScratchpad(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "scratchpad removed")))
        default:
            preconditionFailure("invalid scratchpad command")
        }
    }

    func scratchpadListInfo() async throws -> IPCScratchpadListInfo {
        let entries = try await scratchpads.entries()
        return IPCScratchpadListInfo(scratchpads: entries.map(IPCScratchpadInfo.init))
    }

    func addScratchpad(_ command: IPCScratchpadAddCommand) async throws -> ScratchpadEntry {
        let existing = try await scratchpads.entry(named: command.name)
        let entry = ScratchpadEntry(
            name: command.name,
            bundleID: command.bundleID,
            titleRegex: command.titleRegex,
            role: command.role,
            lastVisibleFrame: existing?.lastVisibleFrame,
            isVisible: existing?.isVisible ?? true
        )
        try await scratchpads.upsert(entry)
        return entry
    }

    @discardableResult
    func toggleScratchpad(_ command: IPCScratchpadToggleCommand) async throws -> ScratchpadEntry {
        let entry = try await requiredScratchpad(named: command.name)
        guard let window = try await scratchpadWindow(matching: entry) else {
            guard let bundleID = entry.bundleID else {
                throw OllyRuntimeError.scratchpadUnavailable(command.name)
            }
            let updated = try await scratchpads.setVisibility(name: entry.name, isVisible: true)
            try await scratchpadApplicationLauncher(bundleID)
            return updated
        }

        if entry.isVisible {
            return try await hideScratchpad(entry, window: window)
        }
        return try await showScratchpad(entry, window: window)
    }

    func removeScratchpad(_ command: IPCScratchpadRemoveCommand) async throws -> ScratchpadEntry {
        guard let removed = try await scratchpads.remove(name: command.name) else {
            throw OllyRuntimeError.scratchpadUnavailable(command.name)
        }
        return removed
    }

    func parkHiddenScratchpadIfNeeded(_ window: WindowState) async {
        do {
            guard let entry = try await scratchpads.matchingEntry(for: window), !entry.isVisible else {
                return
            }
            let lastFrame = entry.lastVisibleFrame ?? WindowRecoveryFrame(window.frame)
            try await scratchpads.setVisibility(
                name: entry.name,
                isVisible: false,
                lastVisibleFrame: lastFrame
            )
            if let move = await scratchpadParker.park(window) {
                await windowStore.upsert(window.withFrame(move.targetFrame))
            }
        } catch {
            lastError = String(describing: error)
        }
    }

    private func requiredScratchpad(named name: String) async throws -> ScratchpadEntry {
        guard let entry = try await scratchpads.entry(named: name) else {
            throw OllyRuntimeError.scratchpadUnavailable(name)
        }
        return entry
    }

    private func scratchpadWindow(matching entry: ScratchpadEntry) async throws -> WindowState? {
        let windows = await windowStore.allWindows()
        if let focusedWindowID,
           let focused = windows.first(where: { $0.id == focusedWindowID }),
           try entry.matches(focused) {
            return focused
        }
        return try windows.first { try entry.matches($0) }
    }

    private func hideScratchpad(_ entry: ScratchpadEntry, window: WindowState) async throws -> ScratchpadEntry {
        let lastFrame = WindowRecoveryFrame(window.frame)
        if let move = await scratchpadParker.park(window) {
            await windowStore.upsert(window.withFrame(move.targetFrame))
        }
        return try await scratchpads.setVisibility(
            name: entry.name,
            isVisible: false,
            lastVisibleFrame: lastFrame
        )
    }

    private func showScratchpad(_ entry: ScratchpadEntry, window: WindowState) async throws -> ScratchpadEntry {
        let frame = targetFrame(for: entry, window: window)
        let move = await scratchpadParker.restore(window, targetFrame: frame)
        let updatedWindow = window.withFrame(move.targetFrame)
        await windowStore.upsert(updatedWindow)
        try await focusScratchpadWindow(updatedWindow)
        return try await scratchpads.setVisibility(
            name: entry.name,
            isVisible: true,
            lastVisibleFrame: WindowRecoveryFrame(frame)
        )
    }

    private func targetFrame(for entry: ScratchpadEntry, window: WindowState) -> CGRect {
        if let frame = entry.lastVisibleFrame?.cgRect {
            return frame
        }
        guard let display = selectedDisplay(window.displayID) else {
            return window.frame
        }
        return window.frame.centered(in: display.frame)
    }

    private func focusScratchpadWindow(_ window: WindowState) async throws {
        if let scratchpadFocusWindow {
            try await scratchpadFocusWindow(window)
            await setFocusedWindow(window.id, displayID: window.displayID, tagMask: window.tagMask, publish: true)
            return
        }
        guard let target = windowTargets.target(for: window) else {
            throw OllyRuntimeError.axOperationFailed("scratchpad-focus", .invalidUIElement)
        }
        let error = AXUIElementSetAttributeValue(target.axElement, kAXFocusedAttribute as CFString, kCFBooleanTrue)
        guard error == .success else {
            await handleAXReadWriteError(error)
            throw OllyRuntimeError.axOperationFailed("scratchpad-focus", error)
        }
        await setFocusedWindow(window.id, displayID: window.displayID, tagMask: window.tagMask, publish: true)
    }

    @MainActor private static func launchScratchpadApplication(bundleID: String) async throws {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleID) else {
            throw OllyRuntimeError.scratchpadLaunchFailed(bundleID)
        }
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.activates = true
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            NSWorkspace.shared.openApplication(at: url, configuration: configuration) { application, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if application != nil {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: OllyRuntimeError.scratchpadLaunchFailed(bundleID))
                }
            }
        }
    }
}

private extension WindowState {
    func withFrame(_ frame: CGRect) -> WindowState {
        WindowState(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            isSticky: isSticky,
            isPinned: isPinned,
            isFullscreen: isFullscreen,
            isOffSpace: isOffSpace,
            engineOverride: engineOverride,
            layoutOrder: layoutOrder,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }
}

private extension CGRect {
    func centered(in bounds: CGRect) -> CGRect {
        let size = CGSize(width: min(width, bounds.width), height: min(height, bounds.height))
        return CGRect(
            x: bounds.midX - size.width / 2,
            y: bounds.midY - size.height / 2,
            width: size.width,
            height: size.height
        )
    }
}
