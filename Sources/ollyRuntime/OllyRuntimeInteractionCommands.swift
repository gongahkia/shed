import CoreGraphics
import Foundation
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts

extension OllyRuntime {
    func snapWindow(_ command: IPCSnapWindowCommand) async throws {
        let windowID = try command.windowID ?? focusedWindowID.requiredFocusedWindow()
        guard let current = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        let display = try selectedDisplay(command.displayID ?? current.displayID).requiredDisplay()
        let safeZoneCalculator = await safeZones()
        let frame = command.position.frame(in: safeZoneCalculator.layoutFrame(for: display), current: current.frame)
        let updated = current.snapped(to: frame, displayID: display.id, makeFloating: command.makeFloating)

        await windowStore.upsert(updated)
        if let target = windowTargets.target(for: windowID) {
            let displayTarget = target.withFallbackDisplayID(display.id)
            await windowMover.setPosition(frame.origin, for: displayTarget)
            await windowMover.setSize(frame.size, for: displayTarget)
            await windowMover.flushNow()
        }

        let arrangeDisplayIDs = Set([current.displayID, updated.displayID].compactMap { $0 })
        for displayID in arrangeDisplayIDs where command.makeFloating || current.displayID != updated.displayID {
            try await arrange(displayID: displayID)
        }
    }

    func dispatchGesture(_ command: IPCDispatchGestureCommand) async throws {
        let config = await configStore.current()
        guard let gestureCommand = config.gestures.command(
            for: command.trigger.dslTrigger,
            motion: command.motion.dslMotion
        ) else {
            throw OllyRuntimeError.gestureUnbound(
                trigger: command.trigger.rawValue,
                motion: command.motion.rawValue
            )
        }
        try await run(gestureCommand, displayID: command.displayID, config: config)
    }

    private func run(_ command: GestureCommand, displayID: DisplayID?, config: Config) async throws {
        switch command {
        case let .scrollColumns(direction):
            try await scrollColumns(direction, displayID: displayID)
        case let .switchTags(direction):
            try await switchGestureTag(direction, displayID: displayID, config: config)
        case let .action(action):
            try await run(action, displayID: displayID)
        case .noop:
            return
        }
    }

    private func run(_ action: Action, displayID: DisplayID?) async throws {
        switch action {
        case .focus, .swap, .move:
            try await runDirectionalAction(action, displayID: displayID)
        case .switchTag, .toggleTag, .moveWindowToTag:
            try await runTagAction(action, displayID: displayID)
        case let .setEngine(engineID):
            try await setEngine(IPCSetEngineCommand(engineID: engineID, displayID: displayID))
        case .cycleEngine:
            try await cycleEngine(IPCCycleEngineCommand(displayID: displayID))
        case .reload:
            try await reloadConfig()
        case .noop:
            return
        case let .raw(label):
            throw OllyRuntimeError.unsupportedGestureAction("raw(\(label))")
        }
    }

    private func runDirectionalAction(_ action: Action, displayID: DisplayID?) async throws {
        switch action {
        case let .focus(direction):
            try await focus(IPCDirectionalCommand(direction: direction.ipcDirection, displayID: displayID))
        case let .swap(direction):
            try await swapWindow(IPCDirectionalCommand(direction: direction.ipcDirection, displayID: displayID))
        case let .move(direction):
            try await moveWindow(IPCDirectionalCommand(direction: direction.ipcDirection, displayID: displayID))
        default:
            throw OllyRuntimeError.unsupportedGestureAction(String(describing: action))
        }
    }

    private func runTagAction(_ action: Action, displayID: DisplayID?) async throws {
        switch action {
        case let .switchTag(index):
            try await switchTag(IPCTagCommand(tag: try IPCTagIndex(validating: index), displayID: displayID))
        case let .toggleTag(index):
            try await toggleTag(IPCTagCommand(tag: try IPCTagIndex(validating: index), displayID: displayID))
        case let .moveWindowToTag(index):
            try await moveToTag(IPCMoveToTagCommand(tag: try IPCTagIndex(validating: index), displayID: displayID))
        default:
            throw OllyRuntimeError.unsupportedGestureAction(String(describing: action))
        }
    }

    private func scrollColumns(_ direction: Direction, displayID requestedDisplayID: DisplayID?) async throws {
        let displayID = try await selectedDisplayID(requestedDisplayID)
        let windows = await visibleWindows(displayID: displayID)
        let sourceID = try focusedWindowID.requiredFocusedWindow()
        guard windows.contains(where: { $0.id == sourceID }) else {
            throw OllyRuntimeError.missingFocusedWindow
        }
        let targetID = try await directionalTarget(
            for: IPCDirectionalCommand(direction: direction.ipcDirection, displayID: displayID),
            displayID: displayID,
            windows: windows,
            focusedWindowID: sourceID
        )
        let target = windows.first { $0.id == targetID }
        await setFocusedWindow(targetID, displayID: displayID, tagMask: target?.tagMask, publish: true)
        try await arrange(displayID: displayID)
    }

    private func switchGestureTag(
        _ direction: Direction,
        displayID requestedDisplayID: DisplayID?,
        config: Config
    ) async throws {
        let displayID = try await selectedDisplayID(requestedDisplayID)
        let nextTag = try await nextGestureTag(direction: direction, displayID: displayID, config: config)
        await tagStore.setActiveTags(TagSet(nextTag), on: displayID)
        try await applyAndArrange(displayID: displayID)
    }

    private func nextGestureTag(direction: Direction, displayID: DisplayID, config: Config) async throws -> Tag {
        let activeTags = await tagStore.activeTags(on: displayID).tags
        let current = try activeTags.first ?? Tag(index: 0)
        let configuredTags = config.workspaces.tags.map(\.tag)
        let orderedTags = configuredTags.isEmpty ? (0..<64).compactMap { try? Tag(index: $0) } : configuredTags
        guard let currentIndex = orderedTags.firstIndex(of: current) ?? orderedTags.indices.first else {
            return try Tag(index: 0)
        }
        let delta = direction == .previous || direction == .left || direction == .up ? -1 : 1
        let nextIndex = (currentIndex + delta + orderedTags.count) % orderedTags.count
        return orderedTags[nextIndex]
    }
}

private extension Display? {
    func requiredDisplay() throws -> Display {
        guard let self else {
            throw OllyRuntimeError.displayUnavailable
        }
        return self
    }
}

private extension WindowState {
    func snapped(to frame: CGRect, displayID: DisplayID, makeFloating: Bool) -> WindowState {
        WindowState(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: makeFloating ? true : isFloating,
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

private extension IPCSnapPosition {
    func frame(in bounds: CGRect, current: CGRect) -> CGRect {
        let halfWidth = floor(bounds.width / 2)
        let halfHeight = floor(bounds.height / 2)
        switch self {
        case .leftHalf:
            return CGRect(x: bounds.minX, y: bounds.minY, width: halfWidth, height: bounds.height)
        case .rightHalf:
            return CGRect(
                x: bounds.minX + halfWidth,
                y: bounds.minY,
                width: bounds.width - halfWidth,
                height: bounds.height
            )
        case .topHalf:
            return CGRect(
                x: bounds.minX,
                y: bounds.minY + halfHeight,
                width: bounds.width,
                height: bounds.height - halfHeight
            )
        case .bottomHalf:
            return CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: halfHeight)
        case .topLeft:
            return CGRect(
                x: bounds.minX,
                y: bounds.minY + halfHeight,
                width: halfWidth,
                height: bounds.height - halfHeight
            )
        case .topRight:
            return CGRect(
                x: bounds.minX + halfWidth,
                y: bounds.minY + halfHeight,
                width: bounds.width - halfWidth,
                height: bounds.height - halfHeight
            )
        case .bottomLeft:
            return CGRect(x: bounds.minX, y: bounds.minY, width: halfWidth, height: halfHeight)
        case .bottomRight:
            return CGRect(
                x: bounds.minX + halfWidth,
                y: bounds.minY,
                width: bounds.width - halfWidth,
                height: halfHeight
            )
        case .center:
            return current.centered(in: bounds)
        case .maximize:
            return bounds
        }
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

private extension IPCGestureTrigger {
    var dslTrigger: GestureTrigger {
        switch self {
        case .fourFingerHorizontal:
            return .fourFingerHorizontal
        case .fourFingerVertical:
            return .fourFingerVertical
        }
    }
}

private extension IPCGestureMotion {
    var dslMotion: GestureMotion {
        switch self {
        case .left:
            return .left
        case .right:
            return .right
        case .upward:
            return .upward
        case .downward:
            return .downward
        }
    }
}

private extension Direction {
    var ipcDirection: IPCDirection {
        switch self {
        case .up:
            return .upward
        case .down:
            return .downward
        case .left:
            return .left
        case .right:
            return .right
        case .next:
            return .next
        case .previous:
            return .previous
        }
    }
}
