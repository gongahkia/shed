import ApplicationServices
import Foundation
import ollyDSL
import ollyIPC
import ollyKit

extension OllyRuntime {
    public static var defaultAXSubroleReader: AXSubroleReader {
        { element in
            try await readAXSubrole(element)
        }
    }

    static let fullscreenWindowSubrole = "AXFullScreenWindow"

    func scheduleFullscreenProbe(element: AXUIElement) async {
        guard let snapshot = try? await snapshotCache.snapshot(for: element),
              let windowID = snapshot.attributes.windowID else {
            return
        }
        fullscreenTasksByWindowID[windowID]?.cancel()
        let reader = axSubroleReader
        let delay = fullscreenDebounceNanoseconds
        fullscreenTasksByWindowID[windowID] = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: delay)
                let subrole = try await reader(element)
                guard !Task.isCancelled else {
                    return
                }
                await self?.handleFullscreenTransition(
                    windowID: windowID,
                    isFullscreen: subrole == Self.fullscreenWindowSubrole
                )
            } catch is CancellationError {
                return
            } catch {
                await self?.recordAXFullscreenProbeError(error)
            }
        }
    }

    func handleFullscreenTransition(windowID: WindowID, isFullscreen: Bool) async {
        guard let current = await windowStore.state(for: windowID),
              current.isFullscreen != isFullscreen else {
            return
        }
        let updated: WindowState
        if isFullscreen {
            await fullscreenTracker.enter(windowID, tagMask: current.tagMask, displayID: current.displayID)
            updated = current.withFullscreen(true)
        } else {
            let saved = await fullscreenTracker.exit(windowID)
            let restoredTags = saved?.tagMask ?? current.tagMask
            let restoredDisplayID = saved?.displayID ?? current.displayID
            updated = current.withFullscreen(false)
                .withTagMask(restoredTags)
                .withDisplayID(restoredDisplayID)
        }
        await windowStore.upsert(updated)
        await publishRuntimeEvent(.fullscreen(IPCFullscreenEvent(windowID: windowID, didEnter: isFullscreen)))
        await hookDispatcher.fullscreen(FullscreenHookContext(window: updated, didEnter: isFullscreen))
        if let displayID = updated.displayID {
            try? await applyAndArrange(displayID: displayID)
        }
    }

    func recordAXFullscreenProbeError(_ error: Error) async {
        lastError = "fullscreen probe failed: \(error)"
    }

    private static func readAXSubrole(_ element: AXUIElement) async throws -> String? {
        var lastError = AXError.success
        for attempt in 0..<4 {
            var value: CFTypeRef?
            let error = AXUIElementCopyAttributeValue(element, kAXSubroleAttribute as CFString, &value)
            switch error {
            case .success:
                return value as? String
            case .attributeUnsupported, .noValue:
                return nil
            case .invalidUIElement where attempt < 3:
                lastError = error
                try await Task.sleep(nanoseconds: 250_000_000)
            default:
                throw OllyRuntimeError.axOperationFailed("read subrole", error)
            }
        }
        throw OllyRuntimeError.axOperationFailed("read subrole", lastError)
    }
}
