import AppKit
import CoreGraphics
import Foundation
import ollyIPC
import ollyKit

extension OllyRuntime {
    public static var defaultActiveSpaceWindowIDs: ActiveSpaceWindowIDProvider {
        {
            let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
            guard let list = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] else {
                return nil
            }
            return Set(list.compactMap { info in
                intValue(info[kCGWindowNumber as String]).map(WindowID.init)
            })
        }
    }

    public static var defaultNativeSpaceChangeStream: NativeSpaceChangeStreamProvider {
        {
            AsyncStream { continuation in
                let observer = NSWorkspace.shared.notificationCenter.addObserver(
                    forName: NSWorkspace.activeSpaceDidChangeNotification,
                    object: nil,
                    queue: nil
                ) { _ in
                    continuation.yield(())
                }
                continuation.onTermination = { _ in
                    NSWorkspace.shared.notificationCenter.removeObserver(observer)
                }
            }
        }
    }

    func startNativeSpaceObservation() {
        guard nativeSpaceObservationTask == nil else {
            return
        }
        let task = Task { [weak self, nativeSpaceChangeStream] in
            for await _ in nativeSpaceChangeStream() {
                guard let self, !Task.isCancelled else {
                    return
                }
                await self.scheduleNativeSpaceVerification()
            }
        }
        nativeSpaceObservationTask = task
        tasks.append(task)
    }

    func scheduleNativeSpaceVerification() {
        nativeSpaceVerificationTask?.cancel()
        let delay = nativeSpaceDebounceNanoseconds
        nativeSpaceVerificationTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: delay)
                guard !Task.isCancelled else {
                    return
                }
                await self?.verifyNativeSpaces()
            } catch is CancellationError {
                return
            } catch {
                await self?.recordNativeSpaceError(error)
            }
        }
    }

    func verifyNativeSpaces() async {
        guard let visibleWindowIDs = activeSpaceWindowIDs() else {
            return
        }
        for window in await windowStore.allWindows() where isProcessRunning(window.processID) {
            if visibleWindowIDs.contains(window.id) {
                await markWindowOnActiveSpace(window)
            } else {
                await handleOffSpaceWindow(window)
            }
        }
    }

    private func handleOffSpaceWindow(_ window: WindowState) async {
        switch nativeSpaceDriftPolicy {
        case .followWindow:
            await markWindowOffSpace(window)
        case .unmanage:
            await unmanageNativeSpaceWindow(window)
        case .rehome:
            await rehomeNativeSpaceWindow(window)
        }
    }

    private func markWindowOffSpace(_ window: WindowState) async {
        guard !window.isOffSpace else {
            return
        }
        await windowStore.upsert(window.withOffSpace(true))
        await publishRuntimeEvent(.space(IPCSpaceDriftEvent(
            windowID: window.id,
            fromDisplayID: window.displayID,
            action: .markedOffSpace
        )))
        if focusedWindowID == window.id {
            await setFocusedWindow(nil, publish: true)
        }
    }

    private func markWindowOnActiveSpace(_ window: WindowState) async {
        guard window.isOffSpace else {
            return
        }
        let updated = window.withOffSpace(false)
        await windowStore.upsert(updated)
        await publishRuntimeEvent(.space(IPCSpaceDriftEvent(
            windowID: window.id,
            fromDisplayID: window.displayID,
            action: .returned
        )))
        if let displayID = updated.displayID {
            try? await applyAndArrange(displayID: displayID)
        }
    }

    private func rehomeNativeSpaceWindow(_ window: WindowState) async {
        guard let target = windowTargets.target(for: window),
              let snapshot = try? await snapshotCache.snapshot(for: target.axElement) else {
            await markWindowOffSpace(window)
            return
        }
        let updated = WindowState(
            id: window.id,
            processID: snapshot.attributes.processID,
            bundleID: window.bundleID,
            displayID: displayID(for: snapshot.attributes.frame) ?? window.displayID,
            tagMask: window.tagMask,
            isFloating: window.isFloating,
            isSticky: window.isSticky,
            isPinned: window.isPinned,
            isFullscreen: window.isFullscreen,
            isOffSpace: false,
            engineOverride: window.engineOverride,
            layoutOrder: window.layoutOrder,
            frame: snapshot.attributes.frame,
            title: snapshot.attributes.title,
            role: snapshot.attributes.role,
            subrole: snapshot.attributes.subrole
        )
        await windowStore.upsert(updated)
        await publishRuntimeEvent(.space(IPCSpaceDriftEvent(
            windowID: window.id,
            fromDisplayID: window.displayID,
            action: .rehomed
        )))
        if let displayID = updated.displayID {
            try? await applyAndArrange(displayID: displayID)
        }
    }

    private func unmanageNativeSpaceWindow(_ window: WindowState) async {
        await dragSession.end(windowID: window.id)
        fullscreenTasksByWindowID[window.id]?.cancel()
        fullscreenTasksByWindowID[window.id] = nil
        _ = await fullscreenTracker.exit(window.id)
        await windowStore.remove(id: window.id)
        await focusStack.remove(windowID: window.id)
        windowTargets.remove(windowID: window.id)
        await publishRuntimeEvent(.space(IPCSpaceDriftEvent(
            windowID: window.id,
            fromDisplayID: window.displayID,
            action: .unmanaged
        )))
        if focusedWindowID == window.id {
            await setFocusedWindow(nil, publish: true)
        }
    }

    private func recordNativeSpaceError(_ error: Error) async {
        lastError = "native space verification failed: \(error)"
    }

    private func isProcessRunning(_ processID: pid_t) -> Bool {
        applicationsByProcessID[processID] != nil
            || NSRunningApplication(processIdentifier: processID) != nil
    }
}

private func intValue(_ value: Any?) -> Int? {
    if let number = value as? NSNumber {
        return number.intValue
    }
    return value as? Int
}
