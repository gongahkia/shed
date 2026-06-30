import CoreGraphics
import Foundation
import ollyCore
import ollyDSL
import ollyKit

extension OllyRuntime {
    func configureFocusFollowsMouse() {
        focusFollowsMouseTask?.cancel()
        focusFollowsMouseDebounceTask?.cancel()
        focusFollowsMouseTask = nil
        focusFollowsMouseDebounceTask = nil
        guard focusPolicy.followsMouseDelay != nil else {
            return
        }
        let stream = mouseMoveStream()
        focusFollowsMouseTask = Task { [weak self] in
            for await point in stream {
                guard let self, !Task.isCancelled else {
                    return
                }
                await self.scheduleFocusFollowsMouse(point)
            }
        }
    }

    private func scheduleFocusFollowsMouse(_ point: CGPoint) {
        guard let delay = focusPolicy.followsMouseDelay else {
            return
        }
        focusFollowsMouseDebounceTask?.cancel()
        focusFollowsMouseDebounceTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: delay.nanoseconds)
            } catch {
                return
            }
            guard let self, !Task.isCancelled else {
                return
            }
            await self.focusWindowUnderMouse(point)
        }
    }

    func focusWindowUnderMouse(_ point: CGPoint) async {
        guard focusPolicy.followsMouseDelay != nil,
              let windowID = WindowUnderPointResolver.windowID(
                at: point,
                candidates: windowUnderPointCandidates()
              ),
              windowID != focusedWindowID,
              let window = await windowStore.state(for: windowID),
              await canFocusWindowUnderMouse(window),
              let target = windowTargets.target(for: window) else {
            return
        }
        do {
            try await setAXFocus(target, operation: "focus-follows-mouse")
            await setFocusedWindow(windowID, displayID: window.displayID, tagMask: window.tagMask, publish: true)
        } catch {
            lastError = String(describing: error)
        }
    }

    private func canFocusWindowUnderMouse(_ window: WindowState) async -> Bool {
        guard let displayID = window.displayID, !window.isOffSpace else {
            return false
        }
        let activeTags = await tagStore.activeTags(on: displayID)
        return TagSet(rawValue: window.tagMask).intersects(activeTags)
    }
}

private extension AnimationDuration {
    var nanoseconds: UInt64 {
        let value = milliseconds * 1_000_000
        return value >= Double(UInt64.max) ? UInt64.max : UInt64(value)
    }
}
