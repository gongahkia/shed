import Foundation
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts

extension OllyRuntime {
    func loadConfig(useDefaultWhenMissing: Bool) async throws {
        do {
            let loaded = try configLoader.load()
            await configStore.replace(with: loaded.config)
        } catch ConfigLoaderError.missingSource where useDefaultWhenMissing {
            await configStore.replace(with: Config())
        }
    }

    func reloadConfig() async throws {
        do {
            try await loadConfig(useDefaultWhenMissing: true)
            await initializeDisplays()
            await reapplyRulesToStoredWindows()
            try await arrangeAllDisplays()
        } catch {
            lastError = String(describing: error)
            throw error
        }
    }

    func initializeDisplays() async {
        let engineID = await configStore.availableEngineIDs().first ?? FloatingLayoutEngine.engineID
        for display in displayProvider() {
            let state = await tagStore.state(for: display.id)
            let tags = state.activeTags.isEmpty ? Self.defaultActiveTags : state.activeTags
            await tagStore.setActiveTags(tags, on: display.id)
            for tag in tags.tags where await tagStore.engine(for: tag, on: display.id) == nil {
                await tagStore.bindEngine(engineID, to: tag, on: display.id)
            }
        }
    }

    func stateSnapshot(displayID: DisplayID?) async -> IPCStateSnapshot {
        let displays = await tagStore.allStates()
            .filter { displayID == nil || $0.displayID == displayID }
            .map(IPCDisplayState.init)
        let windows = await windowStore.allWindows()
            .filter { displayID == nil || $0.displayID == displayID }
            .map(IPCWindowState.init)
        return IPCStateSnapshot(displays: displays, windows: windows, focusedWindowID: focusedWindowID)
    }

    func persistedState() async throws -> WindowTagPersistenceState {
        try await statePersistence.load()
    }

    func setFocusedWindow(
        _ windowID: WindowID?,
        displayID: DisplayID? = nil,
        tagMask: UInt64? = nil,
        publish: Bool = false
    ) async {
        focusedWindowID = windowID
        guard publish else {
            return
        }
        if let windowID, let displayID {
            let activeTags: UInt64
            if let tagMask {
                activeTags = tagMask
            } else {
                activeTags = await tagStore.activeTags(on: displayID).rawValue
            }
            await focusStack.recordFocus(windowID: windowID, displayID: displayID, tagMask: activeTags)
            await eventHub.publish(
                .focus(IPCFocusEvent(focusedWindowID: windowID, displayID: displayID, tagMask: activeTags))
            )
        } else {
            await eventHub.publish(
                .focus(IPCFocusEvent(focusedWindowID: windowID, displayID: displayID, tagMask: tagMask))
            )
        }
    }

    func switchTag(_ command: IPCTagCommand) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let tag = try Tag(index: Int(command.tag.rawValue))
        await tagStore.setActiveTags(TagSet(tag), on: displayID)
        try await applyAndArrange(displayID: displayID)
    }

    func toggleTag(_ command: IPCTagCommand) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let tag = try Tag(index: Int(command.tag.rawValue))
        let current = await tagStore.activeTags(on: displayID)
        let updated = current.contains(tag) ? current.removing(tag) : current.inserting(tag)
        await tagStore.setActiveTags(updated.isEmpty ? TagSet(tag) : updated, on: displayID)
        try await applyAndArrange(displayID: displayID)
    }

    func addTag(_ command: IPCTagCommand) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let tag = try Tag(index: Int(command.tag.rawValue))
        let updated = await tagStore.activeTags(on: displayID).inserting(tag)
        await tagStore.setActiveTags(updated, on: displayID)
        try await applyAndArrange(displayID: displayID)
    }

    func removeTag(_ command: IPCTagCommand) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let tag = try Tag(index: Int(command.tag.rawValue))
        let current = await tagStore.activeTags(on: displayID)
        let updated = current.removing(tag)
        await tagStore.setActiveTags(updated.isEmpty ? TagSet(tag) : updated, on: displayID)
        try await applyAndArrange(displayID: displayID)
    }

    func moveToTag(_ command: IPCMoveToTagCommand) async throws {
        let windowID = try command.windowID ?? focusedWindowID.requiredFocusedWindow()
        let tag = try Tag(index: Int(command.tag.rawValue))
        let state = try await assignment.assign(window: windowID, tags: TagSet(tag))
        try await statePersistence.upsertLayoutOrders(for: [state])
        let displayID = command.displayID ?? state.displayID ?? selectedDisplay(nil)?.id
        if let displayID {
            try await applyAndArrange(displayID: displayID)
        }
    }

    func setEngine(_ command: IPCSetEngineCommand) async throws {
        guard await configStore.config(for: command.engineID) != nil else {
            throw OllyRuntimeError.engineUnavailable(command.engineID)
        }
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let tag: Tag
        if let requestedTag = command.tag {
            tag = try Tag(index: Int(requestedTag.rawValue))
        } else {
            tag = try await firstActiveTag(on: displayID)
        }
        await tagStore.bindEngine(command.engineID, to: tag, on: displayID)
        try await arrange(displayID: displayID)
    }

    func cycleEngine(_ command: IPCCycleEngineCommand) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let tag = try await firstActiveTag(on: displayID)
        let engineIDs = await configStore.availableEngineIDs()
        guard !engineIDs.isEmpty else {
            throw OllyRuntimeError.engineUnavailable(FloatingLayoutEngine.engineID)
        }
        let current = await tagStore.engine(for: tag, on: displayID) ?? engineIDs[0]
        let currentIndex = engineIDs.firstIndex(of: current) ?? 0
        let delta = command.reverse ? -1 : 1
        let nextIndex = (currentIndex + delta + engineIDs.count) % engineIDs.count
        await tagStore.bindEngine(engineIDs[nextIndex], to: tag, on: displayID)
        try await arrange(displayID: displayID)
    }

    func applyAndArrange(displayID: DisplayID) async throws {
        _ = await dispatcher.apply(displayID: displayID)
        try await arrange(displayID: displayID)
    }

    func arrange(displayID: DisplayID) async throws {
        guard let display = displayProvider().first(where: { $0.id == displayID }) else {
            throw OllyRuntimeError.displayUnavailable
        }
        _ = try await engineHost.arrange(display: display, safeZones: await safeZones(), focus: focusedWindowID)
    }

    func arrangeAllDisplays() async throws {
        for display in displayProvider() {
            try await arrange(displayID: display.id)
        }
    }

    func selectedDisplay(_ displayID: DisplayID?) -> Display? {
        let displays = displayProvider()
        if let displayID {
            return displays.first { $0.id == displayID }
        }
        return displays.first(where: \.isMain) ?? displays.first
    }

    func firstActiveTag(on displayID: DisplayID) async throws -> Tag {
        if let tag = await tagStore.activeTags(on: displayID).tags.first {
            return tag
        }
        return try Tag(index: 0)
    }

    func safeZones() async -> SafeZoneCalculator {
        let safeZones = await configStore.current().safeZones
        return safeZones.calculator()
    }

    func replayCurrentState(to connection: UnixDomainSocketServerConnection, kinds: [IPCEventKind]) async {
        guard Set(kinds).contains(.focus) else {
            return
        }
        let snapshot = await stateSnapshot(displayID: nil)
        let focusedWindow = snapshot.focusedWindowID.flatMap { id in
            snapshot.windows.first { $0.windowID == id }
        }
        let event = IPCEvent.focus(IPCFocusEvent(
            focusedWindowID: snapshot.focusedWindowID,
            displayID: focusedWindow?.displayID,
            tagMask: focusedWindow?.tags.reduce(UInt64(0)) { mask, tag in
                mask | (UInt64(1) << UInt64(tag.rawValue))
            }
        ))
        if let data = try? JSONEncoder().encode(IPCEventEnvelope(event: event)) {
            connection.sendLine(data)
        }
    }
}

extension Display? {
    func requiredID() throws -> DisplayID {
        guard let id = self?.id else {
            throw OllyRuntimeError.displayUnavailable
        }
        return id
    }
}

extension WindowID? {
    func requiredFocusedWindow() throws -> WindowID {
        guard let self else {
            throw OllyRuntimeError.missingFocusedWindow
        }
        return self
    }
}
