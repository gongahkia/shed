import ApplicationServices
import ollyCore
import ollyKit

public extension OllyRuntime {
    func altTabWindows() async -> [WindowState] {
        guard let displayID = await focusedDisplayID() ?? selectedDisplay(nil)?.id else {
            return []
        }
        let activeTags = await tagStore.activeTags(on: displayID)
        return await windowStore.windows(onDisplay: displayID).filter { window in
            !window.isOffSpace && TagSet(rawValue: window.tagMask).intersects(activeTags)
        }
    }

    func focusedWindowForAltTab() async -> WindowID? {
        focusedWindowID
    }

    func focusWindowFromAltTab(_ windowID: WindowID) async throws {
        guard let window = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        guard let target = windowTargets.target(for: window) else {
            throw OllyRuntimeError.axOperationFailed("focus", .invalidUIElement)
        }
        let error = AXUIElementSetAttributeValue(target.axElement, kAXFocusedAttribute as CFString, kCFBooleanTrue)
        guard error == .success else {
            await handleAXReadWriteError(error)
            throw OllyRuntimeError.axOperationFailed("focus", error)
        }
        await setFocusedWindow(windowID, displayID: window.displayID, tagMask: window.tagMask, publish: true)
    }
}
