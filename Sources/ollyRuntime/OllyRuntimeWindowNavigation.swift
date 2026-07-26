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
        let displayID = try await selectedDisplayID(command.displayID)
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
        try await applyLayoutOrder(nextOrder, windows: windows)
        try await arrange(displayID: displayID)
    }

    private func applyLayoutOrder(_ orderedIDs: [WindowID], windows: [WindowState]) async throws {
        let windowsByID = Dictionary(uniqueKeysWithValues: windows.map { ($0.id, $0) })
        var updatedWindows: [WindowState] = []
        for (index, windowID) in orderedIDs.enumerated() {
            guard let window = windowsByID[windowID], window.layoutOrder != index else {
                if let window = windowsByID[windowID] {
                    updatedWindows.append(window)
                }
                continue
            }
            let updated = window.withLayoutOrder(index)
            updatedWindows.append(updated)
            await windowStore.upsert(updated)
        }
        try await statePersistence.upsertLayoutOrders(for: updatedWindows)
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
        let geometry = await latestGeometry(displayID: displayID, windows: windows)
        guard let sourceFrame = geometry.framesByID[focusedWindowID] else {
            throw OllyRuntimeError.missingFocusedWindow
        }
        let resolver = DirectionalTargetResolver(
            direction: direction,
            sourceID: focusedWindowID,
            sourceFrame: sourceFrame,
            windows: windows,
            framesByID: geometry.framesByID,
            hiddenWindowIDs: geometry.hiddenWindowIDs
        )
        guard let best = resolver.target() else {
            throw OllyRuntimeError.missingDirectionalTarget(direction)
        }
        return best
    }

    private func latestGeometry(
        displayID: DisplayID,
        windows: [WindowState]
    ) async -> RuntimeWindowGeometry {
        var frames = Dictionary(uniqueKeysWithValues: windows.map { ($0.id, $0.frame) })
        var hiddenWindowIDs = Set<WindowID>()
        if let snapshot = await engineHost.snapshot(displayID: displayID) {
            for placement in snapshot.placements {
                if placement.placement.hidden {
                    hiddenWindowIDs.insert(placement.window.id)
                    frames[placement.window.id] = nil
                } else {
                    frames[placement.window.id] = placement.placement.frame
                }
            }
        }
        return RuntimeWindowGeometry(framesByID: frames, hiddenWindowIDs: hiddenWindowIDs)
    }
}

private struct RuntimeWindowGeometry {
    let framesByID: [WindowID: CGRect]
    let hiddenWindowIDs: Set<WindowID>
}

private enum WindowReorderOperation {
    case move
    case swap
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

    func containsCandidate(source: CGRect, candidate: CGRect) -> Bool {
        switch self {
        case .left:
            return candidate.midX < source.midX
        case .right:
            return candidate.midX > source.midX
        case .upward:
            return candidate.midY < source.midY
        case .downward:
            return candidate.midY > source.midY
        case .next, .previous:
            return false
        }
    }
}

private struct DirectionalTargetResolver {
    let direction: IPCDirection
    let sourceID: WindowID
    let sourceFrame: CGRect
    let windows: [WindowState]
    let framesByID: [WindowID: CGRect]
    let hiddenWindowIDs: Set<WindowID>

    func target() -> WindowID? {
        windows.compactMap(candidate).min { lhs, rhs in
            lhs.score == rhs.score ? lhs.window.id < rhs.window.id : lhs.score < rhs.score
        }?.window.id
    }

    private func candidate(_ window: WindowState) -> DirectionalTargetCandidate? {
        guard window.id != sourceID,
              !hiddenWindowIDs.contains(window.id),
              let frame = framesByID[window.id],
              direction.containsCandidate(source: sourceFrame, candidate: frame) else {
            return nil
        }
        return DirectionalTargetCandidate(
            window: window,
            score: DirectionalTargetScore(direction: direction, source: sourceFrame, candidate: frame, window: window)
        )
    }
}

private struct DirectionalTargetCandidate {
    let window: WindowState
    let score: DirectionalTargetScore
}

private struct DirectionalTargetScore: Comparable {
    let perpendicularRank: Int
    let primaryGap: CGFloat
    let perpendicularGap: CGFloat
    let primaryCenterDistance: CGFloat
    let layoutOrder: Int
    let windowID: WindowID

    init(direction: IPCDirection, source: CGRect, candidate: CGRect, window: WindowState) {
        let metrics = DirectionalMetrics(direction: direction, source: source, candidate: candidate)
        self.perpendicularRank = metrics.perpendicularOverlap > 0 ? 0 : 1
        self.primaryGap = metrics.primaryGap
        self.perpendicularGap = metrics.perpendicularGap
        self.primaryCenterDistance = metrics.primaryCenterDistance
        self.layoutOrder = window.layoutOrder ?? Int(window.id)
        self.windowID = window.id
    }

    static func < (lhs: DirectionalTargetScore, rhs: DirectionalTargetScore) -> Bool {
        if lhs.perpendicularRank != rhs.perpendicularRank {
            return lhs.perpendicularRank < rhs.perpendicularRank
        }
        if lhs.primaryGap != rhs.primaryGap {
            return lhs.primaryGap < rhs.primaryGap
        }
        if lhs.perpendicularGap != rhs.perpendicularGap {
            return lhs.perpendicularGap < rhs.perpendicularGap
        }
        if lhs.primaryCenterDistance != rhs.primaryCenterDistance {
            return lhs.primaryCenterDistance < rhs.primaryCenterDistance
        }
        if lhs.layoutOrder != rhs.layoutOrder {
            return lhs.layoutOrder < rhs.layoutOrder
        }
        return lhs.windowID < rhs.windowID
    }
}

private struct DirectionalMetrics {
    let primaryGap: CGFloat
    let perpendicularGap: CGFloat
    let perpendicularOverlap: CGFloat
    let primaryCenterDistance: CGFloat

    init(direction: IPCDirection, source: CGRect, candidate: CGRect) {
        switch direction {
        case .left:
            self.primaryGap = max(0, source.minX - candidate.maxX)
            self.primaryCenterDistance = abs(source.midX - candidate.midX)
            self.perpendicularOverlap = source.verticalOverlap(with: candidate)
            self.perpendicularGap = source.verticalGap(to: candidate)
        case .right:
            self.primaryGap = max(0, candidate.minX - source.maxX)
            self.primaryCenterDistance = abs(candidate.midX - source.midX)
            self.perpendicularOverlap = source.verticalOverlap(with: candidate)
            self.perpendicularGap = source.verticalGap(to: candidate)
        case .upward:
            self.primaryGap = max(0, source.minY - candidate.maxY)
            self.primaryCenterDistance = abs(source.midY - candidate.midY)
            self.perpendicularOverlap = source.horizontalOverlap(with: candidate)
            self.perpendicularGap = source.horizontalGap(to: candidate)
        case .downward:
            self.primaryGap = max(0, candidate.minY - source.maxY)
            self.primaryCenterDistance = abs(candidate.midY - source.midY)
            self.perpendicularOverlap = source.horizontalOverlap(with: candidate)
            self.perpendicularGap = source.horizontalGap(to: candidate)
        case .next, .previous:
            self.primaryGap = .greatestFiniteMagnitude
            self.perpendicularGap = .greatestFiniteMagnitude
            self.perpendicularOverlap = 0
            self.primaryCenterDistance = .greatestFiniteMagnitude
        }
    }
}

private extension CGRect {
    func horizontalOverlap(with other: CGRect) -> CGFloat {
        max(0, min(maxX, other.maxX) - max(minX, other.minX))
    }

    func verticalOverlap(with other: CGRect) -> CGFloat {
        max(0, min(maxY, other.maxY) - max(minY, other.minY))
    }

    func horizontalGap(to other: CGRect) -> CGFloat {
        if horizontalOverlap(with: other) > 0 {
            return 0
        }
        return other.maxX < minX ? minX - other.maxX : other.minX - maxX
    }

    func verticalGap(to other: CGRect) -> CGFloat {
        if verticalOverlap(with: other) > 0 {
            return 0
        }
        return other.maxY < minY ? minY - other.maxY : other.minY - maxY
    }
}
