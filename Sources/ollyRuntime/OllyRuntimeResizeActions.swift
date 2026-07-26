import CoreGraphics
import ollyCore
import ollyDSL
import ollyKit
import ollyLayouts

extension OllyRuntime {
    func resizeFocusedWindow(_ direction: Direction, points: CGFloat, displayID: DisplayID?) async throws {
        let windowID = try focusedWindowID.requiredFocusedWindow()
        guard let current = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        let updatedSize = try current.frame.size.resized(direction: direction, points: points)
        let updated = current.withFrame(CGRect(origin: current.frame.origin, size: updatedSize))
        await windowStore.upsert(updated)

        if let target = windowTargets.target(for: windowID) {
            let displayTarget = target.withFallbackDisplayID(displayID ?? current.displayID)
            await windowMover.setSize(updatedSize, for: displayTarget)
            await windowMover.flushNow()
        }
    }

    func splitFocusedWindow(
        _ direction: Direction,
        ratio: CGFloat,
        displayID requestedDisplayID: DisplayID?
    ) async throws {
        let adjustment = try direction.bspSplitAdjustment()
        let displayID = try await selectedDisplayID(requestedDisplayID)
        let engineID = try await activeEngineID(on: displayID)
        guard engineID == BSPLayoutEngine.engineID else {
            throw OllyRuntimeError.unsupportedEngineCommand(command: "split", engineID: engineID)
        }
        let windowID = try focusedWindowID.requiredFocusedWindow()
        let windows = await visibleWindows(displayID: displayID)
        guard windows.contains(where: { $0.id == windowID }) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        guard let display = displayProvider().first(where: { $0.id == displayID }) else {
            throw OllyRuntimeError.displayUnavailable
        }
        let windowIDs = windows.map(\.id)
        let bounds = await safeZones().layoutFrame(for: display)
        _ = try await configStore.updateBSPTree { tree in
            try tree.reconciled(with: windowIDs, in: bounds).resizingSplit(
                containing: windowID,
                axis: adjustment.axis,
                focusedChild: adjustment.focusedChild,
                ratio: ratio
            )
        }
        try await arrange(displayID: displayID)
    }
}

private struct BSPSplitAdjustment {
    let axis: BSPSplitAxis
    let focusedChild: BSPChildSide
}

private extension CGSize {
    func resized(direction: Direction, points: CGFloat) throws -> CGSize {
        let points = max(0, points)
        switch direction {
        case .left:
            return CGSize(width: max(1, width - points), height: height)
        case .right:
            return CGSize(width: max(1, width + points), height: height)
        case .up:
            return CGSize(width: width, height: max(1, height - points))
        case .down:
            return CGSize(width: width, height: max(1, height + points))
        case .next, .previous:
            throw OllyRuntimeError.unsupportedGestureAction("resize(\(direction.rawValue))")
        }
    }
}

private extension Direction {
    func bspSplitAdjustment() throws -> BSPSplitAdjustment {
        switch self {
        case .left:
            return BSPSplitAdjustment(axis: .horizontal, focusedChild: .second)
        case .right:
            return BSPSplitAdjustment(axis: .horizontal, focusedChild: .first)
        case .up:
            return BSPSplitAdjustment(axis: .vertical, focusedChild: .second)
        case .down:
            return BSPSplitAdjustment(axis: .vertical, focusedChild: .first)
        case .next, .previous:
            throw OllyRuntimeError.unsupportedGestureAction("split(\(rawValue))")
        }
    }
}
