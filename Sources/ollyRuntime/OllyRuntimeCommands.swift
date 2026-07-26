import Foundation
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts

extension OllyRuntime {
    func initializeDisplays() async {
        let workspaces = await configStore.current().workspaces
        let engineID = await configStore.availableEngineIDs().first ?? FloatingLayoutEngine.engineID
        for display in displayProvider() {
            let state = await tagStore.state(for: display.id)
            let tags = workspaces.initialTags(on: display.id)
                ?? (state.activeTags.isEmpty ? Self.defaultActiveTags : state.activeTags)
            await tagStore.setActiveTags(tags, on: display.id)
            await bindInitialWorkspaceEngines(workspaces, on: display.id)
            for tag in tags.tags
                where await tagStore.engine(for: tag, on: display.id) == nil
                && workspaces.engineBinding(for: tag, on: display.id) == nil {
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

    func windowListSnapshot(_ command: IPCWindowQueryCommand) async -> IPCStateSnapshot {
        let windows = await windowStore.allWindows()
            .filter { command.displayID == nil || $0.displayID == command.displayID }
            .filter { command.windowID == nil || $0.id == command.windowID }
            .map(IPCWindowState.init)
        return IPCStateSnapshot(displays: [], windows: windows, focusedWindowID: focusedWindowID)
    }

    func displayListSnapshot(_ command: IPCDisplayQueryCommand) async -> IPCStateSnapshot {
        let displays = await tagStore.allStates()
            .filter { command.displayID == nil || $0.displayID == command.displayID }
            .map(IPCDisplayState.init)
        return IPCStateSnapshot(displays: displays, windows: [], focusedWindowID: focusedWindowID)
    }

    func persistedState() async throws -> WindowTagPersistenceState {
        try await statePersistence.load()
    }

    func recoveryState() async throws -> WindowRecoveryJournalState {
        try await recoveryJournal.load()
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
            await publishRuntimeEvent(
                .focus(IPCFocusEvent(focusedWindowID: windowID, displayID: displayID, tagMask: activeTags))
            )
        } else {
            await publishRuntimeEvent(.focus(IPCFocusEvent(
                focusedWindowID: windowID,
                displayID: displayID,
                tagMask: tagMask
            )))
        }
    }

    func switchTag(_ command: IPCTagCommand) async throws {
        let displayID = try await selectedDisplayID(command.displayID)
        let tag = try Tag(index: Int(command.tag.rawValue))
        let activeTags = TagSet(tag)
        await setActiveTags(activeTags, on: displayID)
        await rewritePinnedWindows(on: displayID, to: activeTags)
        try await launchConfiguredApps(for: tag, on: displayID)
        try await applyAndArrange(displayID: displayID)
    }

    func toggleTag(_ command: IPCTagCommand) async throws {
        let displayID = try await selectedDisplayID(command.displayID)
        let tag = try Tag(index: Int(command.tag.rawValue))
        let current = await tagStore.activeTags(on: displayID)
        let updated = current.contains(tag) ? current.removing(tag) : current.inserting(tag)
        let activeTags = updated.isEmpty ? TagSet(tag) : updated
        await setActiveTags(activeTags, on: displayID)
        await rewritePinnedWindows(on: displayID, to: activeTags)
        try await applyAndArrange(displayID: displayID)
    }

    func addTag(_ command: IPCTagCommand) async throws {
        let displayID = try await selectedDisplayID(command.displayID)
        let tag = try Tag(index: Int(command.tag.rawValue))
        let updated = await tagStore.activeTags(on: displayID).inserting(tag)
        await setActiveTags(updated, on: displayID)
        try await applyAndArrange(displayID: displayID)
    }

    func removeTag(_ command: IPCTagCommand) async throws {
        let displayID = try await selectedDisplayID(command.displayID)
        let tag = try Tag(index: Int(command.tag.rawValue))
        let current = await tagStore.activeTags(on: displayID)
        let updated = current.removing(tag)
        await setActiveTags(updated.isEmpty ? TagSet(tag) : updated, on: displayID)
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

    func moveToDisplay(_ command: IPCMoveToDisplayCommand) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let windowID = try command.windowID ?? focusedWindowID.requiredFocusedWindow()
        guard let current = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        let previousDisplayID = current.displayID
        let state = try await assignment.move(window: windowID, toDisplay: displayID)
        try await statePersistence.upsertLayoutOrders(for: [state])
        for arrangeDisplayID in Set([previousDisplayID, state.displayID].compactMap { $0 }) {
            try await applyAndArrange(displayID: arrangeDisplayID)
        }
    }

    func setEngine(_ command: IPCSetEngineCommand) async throws {
        guard await configStore.config(for: command.engineID) != nil else {
            throw OllyRuntimeError.engineUnavailable(command.engineID)
        }
        let displayID = try await selectedDisplayID(command.displayID)
        let tag: Tag
        if let requestedTag = command.tag {
            tag = try Tag(index: Int(requestedTag.rawValue))
        } else {
            tag = try await firstActiveTag(on: displayID)
        }
        let previousEngineID = await tagStore.engine(for: tag, on: displayID)
        await tagStore.bindEngine(command.engineID, to: tag, on: displayID)
        await publishEngineChangeHook(
            displayID: displayID,
            tag: tag,
            previousEngineID: previousEngineID,
            currentEngineID: command.engineID
        )
        try await arrange(displayID: displayID)
    }

    func cycleEngine(_ command: IPCCycleEngineCommand) async throws {
        let displayID = try await selectedDisplayID(command.displayID)
        let tag: Tag
        if let requestedTag = command.tag {
            tag = try Tag(index: Int(requestedTag.rawValue))
        } else {
            tag = try await firstActiveTag(on: displayID)
        }
        let engineIDs = await configStore.availableEngineIDs()
        guard !engineIDs.isEmpty else {
            throw OllyRuntimeError.engineUnavailable(FloatingLayoutEngine.engineID)
        }
        let current = await tagStore.engine(for: tag, on: displayID) ?? engineIDs[0]
        let currentIndex = engineIDs.firstIndex(of: current) ?? 0
        let delta = command.reverse ? -1 : 1
        let nextIndex = (currentIndex + delta + engineIDs.count) % engineIDs.count
        let nextEngineID = engineIDs[nextIndex]
        await tagStore.bindEngine(nextEngineID, to: tag, on: displayID)
        await publishEngineChangeHook(
            displayID: displayID,
            tag: tag,
            previousEngineID: current,
            currentEngineID: nextEngineID
        )
        try await arrange(displayID: displayID)
    }

    func bindInitialWorkspaceEngines(_ workspaces: Workspaces, on displayID: DisplayID) async {
        for binding in workspaces.engineBindings(on: displayID) {
            await tagStore.bindEngine(binding.engineID, to: binding.tag, on: displayID)
        }
    }

    func rewritePinnedWindows(on displayID: DisplayID, to activeTags: TagSet) async {
        for window in await windowStore.windows(onDisplay: displayID)
            where window.isPinned && window.tagMask != activeTags.rawValue {
            await windowStore.upsert(window.withTagMask(activeTags.rawValue))
        }
    }

    func applyAndArrange(displayID: DisplayID) async throws {
        let moves = await dispatcher.apply(displayID: displayID)
        await recordRecoveryMoves(moves)
        try await arrange(displayID: displayID)
    }

    func restoreWindows(_ command: IPCRestoreWindowsCommand) async -> IPCRestoreWindowsInfo {
        _ = command
        return await restoreJournaledWindows()
    }

    func setSpacePolicy(_ command: IPCSetSpacePolicyCommand) async {
        nativeSpaceDriftPolicy = command.policy
        scheduleNativeSpaceVerification()
    }

    func restoreJournaledWindows() async -> IPCRestoreWindowsInfo {
        let entries: [WindowRecoveryEntry]
        do {
            entries = try await recoveryJournal.load().entries
        } catch {
            lastError = String(describing: error)
            return IPCRestoreWindowsInfo(restoredCount: 0, skippedCount: 0, failedCount: 1)
        }

        var restoredIDs: [WindowID] = []
        var skippedCount = 0

        for entry in entries {
            guard let target = windowTargets.target(for: entry.windowID) else {
                skippedCount += 1
                continue
            }
            let frame = entry.originalFrame.cgRect
            let displayTarget = target.withFallbackDisplayID(entry.displayID)
            await windowMover.setPosition(frame.origin, for: displayTarget)
            await windowMover.setSize(frame.size, for: displayTarget)
            restoredIDs.append(entry.windowID)
        }

        if !restoredIDs.isEmpty {
            await windowMover.flushNow()
        }

        do {
            try await recoveryJournal.remove(windowIDs: restoredIDs)
            return IPCRestoreWindowsInfo(
                restoredCount: restoredIDs.count,
                skippedCount: skippedCount,
                failedCount: 0
            )
        } catch {
            lastError = String(describing: error)
            return IPCRestoreWindowsInfo(
                restoredCount: restoredIDs.count,
                skippedCount: skippedCount,
                failedCount: 1
            )
        }
    }

    func recordRecoveryMoves(_ moves: [TagDispatchMove]) async {
        for move in moves {
            switch move.reason {
            case .hide:
                guard let window = await windowStore.state(for: move.windowID) else {
                    continue
                }
                try? await recoveryJournal.record(window: window, parkedFrame: move.targetFrame)
            case .show:
                try? await recoveryJournal.remove(windowID: move.windowID)
            }
        }
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

    func selectedDisplayID(_ displayID: DisplayID?) async throws -> DisplayID {
        if let displayID {
            guard displayProvider().contains(where: { $0.id == displayID }) else {
                throw OllyRuntimeError.displayUnavailable
            }
            return displayID
        }
        if let focusedDisplayID = await focusedDisplayID(),
           displayProvider().contains(where: { $0.id == focusedDisplayID }) {
            return focusedDisplayID
        }
        return try selectedDisplay(nil).requiredID()
    }

    func focusedDisplayID() async -> DisplayID? {
        guard let focusedWindowID,
              let window = await windowStore.state(for: focusedWindowID) else {
            return nil
        }
        return window.displayID
    }

    func firstActiveTag(on displayID: DisplayID) async throws -> Tag {
        if let tag = await tagStore.activeTags(on: displayID).tags.first {
            return tag
        }
        return try Tag(index: 0)
    }

    func safeZones() async -> SafeZoneCalculator {
        let config = await configStore.current()
        let reserves = await cooperativeSafeZoneReserves(config: config)
        return config.safeZones.calculator(dynamicReserves: reserves)
    }

    func replayCurrentState(
        to connection: UnixDomainSocketServerConnection,
        kinds: [IPCEventKind],
        protocolVersion: Int
    ) async {
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
        if let data = try? JSONEncoder().encode(IPCEventEnvelope(version: protocolVersion, event: event)) {
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
