import CoreGraphics
import ollyDSL
import ollyIPC
import ollyKit

public extension OllyRuntime {
    func focusRing() async -> FocusRing {
        await configStore.current().focusRing
    }

    func keybinds() async -> Keybinds {
        await configStore.current().keybinds
    }

    func frameForWindow(_ windowID: WindowID) async -> CGRect? {
        if let target = windowTargets.target(for: windowID),
           let frame = AXFrameReader().frame(for: target.axElement) {
            return frame
        }
        return await windowStore.state(for: windowID)?.frame
    }

    func snapLayoutFrame(for displayID: DisplayID) async -> CGRect? {
        guard let display = displayProvider().first(where: { $0.id == displayID }) else {
            return nil
        }
        return await safeZones().layoutFrame(for: display)
    }

    func displayID(containing point: CGPoint) -> DisplayID? {
        displayProvider().first { $0.frame.contains(point) }?.id
    }

    func snapTargetDisplayID() async -> DisplayID? {
        if let focusedDisplayID = await focusedDisplayID() {
            return focusedDisplayID
        }
        return selectedDisplay(nil)?.id
    }

    func snapWindowFromOverlay(_ command: IPCSnapWindowCommand) async throws {
        try await snapWindow(command)
    }
}
