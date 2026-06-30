import ollyCore
import ollyIPC
import ollyKit
import ollyLayouts

extension OllyRuntime {
    func manualPreselect(_ command: IPCManualPreselectCommand) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let engineID = try await activeEngineID(on: displayID)
        guard engineID == ManualLayoutEngine.engineID else {
            throw OllyRuntimeError.unsupportedEngineCommand(
                command: IPCCommandName.manualPreselect.rawValue,
                engineID: engineID
            )
        }
        let windowID = try command.windowID ?? focusedWindowID.requiredFocusedWindow()
        let windows = await visibleWindows(displayID: displayID)
        guard windows.contains(where: { $0.id == windowID }) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        let windowIDs = windows.map(\.id)
        let updatedTree = try await configStore.updateManualTree { tree in
            try tree.reconciled(with: windowIDs).preselect(command.direction, for: windowID)
        }
        let path = updatedTree.path(to: windowID) ?? .root
        await publishRuntimeEvent(.engine(.manualPreselected(ManualPreselectedEvent(
            windowID: windowID,
            path: path,
            direction: command.direction
        ))))
        try await arrange(displayID: displayID)
    }

    func mutateBSPTree(_ command: IPCBSPTreeCommand) async throws {
        let displayID = try selectedDisplay(command.displayID).requiredID()
        let engineID = try await activeEngineID(on: displayID)
        guard engineID == BSPLayoutEngine.engineID else {
            throw OllyRuntimeError.unsupportedEngineCommand(
                command: IPCCommandName.bspTree.rawValue,
                engineID: engineID
            )
        }
        guard let display = displayProvider().first(where: { $0.id == displayID }) else {
            throw OllyRuntimeError.displayUnavailable
        }
        let windows = await visibleWindows(displayID: displayID)
        let windowIDs = windows.map(\.id)
        let safeZones = await safeZones()
        let bounds = safeZones.layoutFrame(for: display)
        _ = try await configStore.updateBSPTree { tree in
            let reconciled = tree.reconciled(with: windowIDs, in: bounds)
            switch command.action {
            case .rotateChildren:
                return try reconciled.rotatingChildren(at: command.path)
            case .flipAxis:
                return try reconciled.flippingAxis(at: command.path)
            case .balanceTree:
                return reconciled.balancing(in: bounds)
            }
        }
        await publishRuntimeEvent(.engine(.bspTreeChanged(BSPTreeChangedEvent(
            action: command.action,
            path: command.path
        ))))
        try await arrange(displayID: displayID)
    }

    func activeEngineID(on displayID: DisplayID) async throws -> LayoutEngineID {
        let tag = try await firstActiveTag(on: displayID)
        return await tagStore.engine(for: tag, on: displayID) ?? FloatingLayoutEngine.engineID
    }
}
