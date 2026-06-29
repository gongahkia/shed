import CoreGraphics
import Foundation
import ollyCore
import ollyIPC
import ollyKit

extension OllyRuntime {
    func moveWindow(_ command: IPCDirectionalCommand) async throws {
        try await reorderFocusedWindow(command, operation: .move)
    }

    func swapWindow(_ command: IPCDirectionalCommand) async throws {
        try await reorderFocusedWindow(command, operation: .swap)
    }

    func directionalTarget(
        for command: IPCDirectionalCommand,
        displayID: DisplayID,
        windows: [WindowState],
        focusedWindowID: WindowID
    ) async throws -> WindowID {
        switch command.direction {
        case .next, .previous:
            return try linearTarget(
                direction: command.direction,
                windows: windows,
                focusedWindowID: focusedWindowID
            )
        case .left, .right, .upward, .downward:
            return try await spatialTarget(
                direction: command.direction,
                displayID: displayID,
                windows: windows,
                focusedWindowID: focusedWindowID
            )
        }
    }

    private func reorderFocusedWindow(
        _ command: IPCDirectionalCommand,
        operation: WindowReorderOperation
    ) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let windows = await visibleWindows(displayID: displayID)
        let sourceID = try focusedWindowID.requiredFocusedWindow()
        guard windows.contains(where: { $0.id == sourceID }) else {
            throw OllyRuntimeError.missingFocusedWindow
        }
        let targetID = try await directionalTarget(
            for: command,
            displayID: displayID,
            windows: windows,
            focusedWindowID: sourceID
        )
        let nextOrder = try reorderedWindowIDs(
            operation: operation,
            direction: command.direction,
            sourceID: sourceID,
            targetID: targetID,
            windows: windows
        )
        await applyLayoutOrder(nextOrder, windows: windows)
        try await arrange(displayID: displayID)
    }

    private func applyLayoutOrder(_ orderedIDs: [WindowID], windows: [WindowState]) async {
        let windowsByID = Dictionary(uniqueKeysWithValues: windows.map { ($0.id, $0) })
        for (index, windowID) in orderedIDs.enumerated() {
            guard let window = windowsByID[windowID], window.layoutOrder != index else {
                continue
            }
            await windowStore.upsert(window.withLayoutOrder(index))
        }
    }

    private func reorderedWindowIDs(
        operation: WindowReorderOperation,
        direction: IPCDirection,
        sourceID: WindowID,
        targetID: WindowID,
        windows: [WindowState]
    ) throws -> [WindowID] {
        var order = windows.map(\.id)
        guard let sourceIndex = order.firstIndex(of: sourceID),
              let targetIndex = order.firstIndex(of: targetID) else {
            throw OllyRuntimeError.missingFocusedWindow
        }
        switch operation {
        case .move:
            order.remove(at: sourceIndex)
            guard let updatedTargetIndex = order.firstIndex(of: targetID) else {
                throw OllyRuntimeError.missingDirectionalTarget(direction)
            }
            let insertionIndex = direction.insertsBeforeTarget ? updatedTargetIndex : updatedTargetIndex + 1
            order.insert(sourceID, at: insertionIndex)
        case .swap:
            order.swapAt(sourceIndex, targetIndex)
        }
        return order
    }

    private func linearTarget(
        direction: IPCDirection,
        windows: [WindowState],
        focusedWindowID: WindowID
    ) throws -> WindowID {
        guard let index = windows.firstIndex(where: { $0.id == focusedWindowID }) else {
            throw OllyRuntimeError.missingFocusedWindow
        }
        let targetIndex = direction == .previous ? index - 1 : index + 1
        guard windows.indices.contains(targetIndex) else {
            throw OllyRuntimeError.missingDirectionalTarget(direction)
        }
        return windows[targetIndex].id
    }

    private func spatialTarget(
        direction: IPCDirection,
        displayID: DisplayID,
        windows: [WindowState],
        focusedWindowID: WindowID
    ) async throws -> WindowID {
        let framesByID = await latestFramesByWindowID(displayID: displayID, windows: windows)
        guard let sourceFrame = framesByID[focusedWindowID] else {
            throw OllyRuntimeError.missingFocusedWindow
        }
        let sourceCenter = sourceFrame.center
        let candidates = windows.filter { window in
            guard window.id != focusedWindowID,
                  let frame = framesByID[window.id] else {
                return false
            }
            return direction.containsCandidate(source: sourceCenter, candidate: frame.center)
        }
        guard let best = candidates.min(by: { lhs, rhs in
            let lhsScore = direction.score(source: sourceCenter, candidate: framesByID[lhs.id]?.center ?? .zero)
            let rhsScore = direction.score(source: sourceCenter, candidate: framesByID[rhs.id]?.center ?? .zero)
            return lhsScore == rhsScore ? lhs.id < rhs.id : lhsScore < rhsScore
        }) else {
            throw OllyRuntimeError.missingDirectionalTarget(direction)
        }
        return best.id
    }

    private func latestFramesByWindowID(
        displayID: DisplayID,
        windows: [WindowState]
    ) async -> [WindowID: CGRect] {
        var frames = Dictionary(uniqueKeysWithValues: windows.map { ($0.id, $0.frame) })
        if let snapshot = await engineHost.snapshot(displayID: displayID) {
            for placement in snapshot.placements where !placement.placement.hidden {
                frames[placement.window.id] = placement.placement.frame
            }
        }
        return frames
    }
}

private enum WindowReorderOperation {
    case move
    case swap
}

private extension WindowState {
    func withLayoutOrder(_ layoutOrder: Int) -> WindowState {
        WindowState(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            layoutOrder: layoutOrder,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }
}

private extension IPCDirection {
    var insertsBeforeTarget: Bool {
        switch self {
        case .left, .upward, .previous:
            return true
        case .right, .downward, .next:
            return false
        }
    }

    func containsCandidate(source: CGPoint, candidate: CGPoint) -> Bool {
        switch self {
        case .left:
            return candidate.x < source.x
        case .right:
            return candidate.x > source.x
        case .upward:
            return candidate.y < source.y
        case .downward:
            return candidate.y > source.y
        case .next, .previous:
            return false
        }
    }

    func score(source: CGPoint, candidate: CGPoint) -> CGFloat {
        let deltaX = candidate.x - source.x
        let deltaY = candidate.y - source.y
        switch self {
        case .left, .right:
            return abs(deltaX) * 1_000 + abs(deltaY)
        case .upward, .downward:
            return abs(deltaY) * 1_000 + abs(deltaX)
        case .next, .previous:
            return .greatestFiniteMagnitude
        }
    }
}

private extension CGRect {
    var center: CGPoint {
        CGPoint(x: midX, y: midY)
    }
}
