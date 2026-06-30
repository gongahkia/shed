import ApplicationServices
import AppKit
import CoreGraphics
import Foundation
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts

extension OllyRuntime {
    func focus(_ command: IPCDirectionalCommand) async throws {
        guard AXPermission.isTrusted else {
            throw OllyRuntimeError.unsupportedAXCommand("focus")
        }
        let displayID = try await selectedDisplayID(command.displayID)
        let windows = await visibleWindows(displayID: displayID)
        guard !windows.isEmpty else {
            throw OllyRuntimeError.missingFocusedWindow
        }
        let nextID: WindowID
        if command.direction == .next || command.direction == .previous {
            nextID = wrappingFocusTarget(direction: command.direction, windows: windows)
        } else {
            let sourceID = try focusedWindowID.requiredFocusedWindow()
            nextID = try await directionalTarget(
                for: command,
                displayID: displayID,
                windows: windows,
                focusedWindowID: sourceID
            )
        }
        guard let window = windows.first(where: { $0.id == nextID }),
              let target = windowTargets.target(for: window) else {
            throw OllyRuntimeError.axOperationFailed("focus", .invalidUIElement)
        }
        let error = AXUIElementSetAttributeValue(target.axElement, kAXFocusedAttribute as CFString, kCFBooleanTrue)
        guard error == .success else {
            await handleAXReadWriteError(error)
            throw OllyRuntimeError.axOperationFailed("focus", error)
        }
        await setFocusedWindow(nextID, displayID: displayID, publish: true)
    }

    private func wrappingFocusTarget(direction: IPCDirection, windows: [WindowState]) -> WindowID {
        let ids = windows.map(\.id)
        let currentIndex = focusedWindowID.flatMap { ids.firstIndex(of: $0) } ?? -1
        let delta = direction == .previous ? -1 : 1
        return ids[(currentIndex + delta + ids.count) % ids.count]
    }

    func visibleWindows(displayID: DisplayID) async -> [WindowState] {
        let activeTags = await tagStore.activeTags(on: displayID)
        return await windowStore.windows(onDisplay: displayID).filter {
            !$0.isFloating && !$0.isFullscreen && !$0.isOffSpace && TagSet(rawValue: $0.tagMask).intersects(activeTags)
        }
    }

    func startApplicationObservation() {
        guard applicationObservationTask == nil else {
            return
        }
        let task = Task { [weak self, applicationMonitor] in
            for await event in applicationMonitor.events() {
                guard let self, !Task.isCancelled else {
                    return
                }
                await self.handle(applicationEvent: event)
            }
        }
        applicationObservationTask = task
        tasks.append(task)
    }

    func startAXObservation(for application: Application) async {
        applicationsByProcessID[application.processID] = application
        guard axObserversByProcessID[application.processID] == nil else {
            return
        }
        let bridge = AXObserverBridge(application: application)
        do {
            let events = try bridge.events()
            axObserversByProcessID[application.processID] = bridge
            let task = Task { [weak self] in
                for await event in events {
                    guard let self, !Task.isCancelled else {
                        return
                    }
                    await self.handle(axEvent: event)
                }
            }
            tasks.append(task)
        } catch let error as AXObserverBridgeError {
            lastError = "AX observer failed for pid \(application.processID): \(error)"
            if let axError = error.axError {
                await handleAXReadWriteError(axError)
            }
        } catch {
            lastError = "AX observer failed for pid \(application.processID): \(error)"
        }
    }

    func stopAXObservers() {
        for observer in axObserversByProcessID.values {
            observer.stop()
        }
        axObserversByProcessID.removeAll()
        applicationsByProcessID.removeAll()
        windowTargets.removeAll()
    }

    func handle(applicationEvent: ApplicationEvent) async {
        switch applicationEvent {
        case let .launched(application):
            await refreshWindows(for: application)
            await startAXObservation(for: application)
        case let .terminated(application):
            let windows = await windowStore.windows(forProcessID: application.processID)
            for window in windows {
                await dragSession.end(windowID: window.id)
                fullscreenTasksByWindowID[window.id]?.cancel()
                fullscreenTasksByWindowID[window.id] = nil
                _ = await fullscreenTracker.exit(window.id)
                await windowStore.remove(id: window.id)
                await focusStack.remove(windowID: window.id)
                windowTargets.remove(windowID: window.id)
            }
            axObserversByProcessID[application.processID]?.stop()
            axObserversByProcessID[application.processID] = nil
            applicationsByProcessID[application.processID] = nil
        }
    }

    func refreshAllWindows() async {
        for application in applicationMonitor.runningApplications() {
            await refreshWindows(for: application)
            await startAXObservation(for: application)
        }
    }

    func refreshWindows(for application: Application) async {
        applicationsByProcessID[application.processID] = application
        for element in await axWindows(for: application.axElement) {
            await refreshWindowElement(element, application: application)
        }
    }

    func handle(axEvent event: AXNotificationEvent) async {
        if event.notification == .uiElementDestroyed {
            await removeWindow(for: event.element)
        }
        await snapshotCache.invalidate(for: event)
        switch event.notification {
        case .focusedWindowChanged, .mainWindowChanged:
            await dragSession.endActiveSession()
            await refreshFocusedWindow(from: event)
        case .applicationActivated:
            await refreshFocusedWindow(from: event)
        case .windowCreated:
            let application = applicationsByProcessID[event.processID] ?? Application(processID: event.processID)
            await refreshWindowElement(event.element, application: application)
            await refreshWindows(for: application)
            scheduleNativeSpaceVerification()
        case .windowMoved:
            let application = applicationsByProcessID[event.processID] ?? Application(processID: event.processID)
            await refreshMovedWindowElement(event.element, application: application)
            scheduleNativeSpaceVerification()
        case .windowResized:
            let application = applicationsByProcessID[event.processID] ?? Application(processID: event.processID)
            await refreshWindowElement(event.element, application: application)
            await scheduleFullscreenProbe(element: event.element)
        case .uiElementDestroyed:
            break
        }
    }

    func refreshFocusedWindowFromSystem() async {
        guard let application = frontmostApplication(),
              let element = await focusedWindowElement(for: application.axElement) else {
            return
        }
        await refreshFocusedWindow(element: element, application: application)
    }

    func reapplyRulesToStoredWindows() async throws {
        for window in await windowStore.allWindows() {
            try await upsertRuntimeWindow(window, element: windowTargets.target(for: window)?.axElement)
        }
    }

    func upsertRuntimeWindow(_ state: WindowState, element: AXUIElement?) async throws {
        let resolved = try await resolvedRuntimeWindowState(state)
        let previous = await windowStore.state(for: resolved.id)
        await windowStore.upsert(resolved)
        if let element {
            windowTargets.set(
                WindowMoveTarget(id: resolved.id, axElement: element, displayID: resolved.displayID),
                for: resolved.id
            )
        }
        if previous?.isOffSpace == true && !resolved.isOffSpace {
            await publishRuntimeEvent(.space(IPCSpaceDriftEvent(
                windowID: resolved.id,
                fromDisplayID: previous?.displayID,
                action: .returned
            )))
        }
    }

    private func resolvedRuntimeWindowState(_ state: WindowState) async throws -> WindowState {
        let config = await configStore.current()
        let current = await windowStore.state(for: state.id)
        let base = state.withFullscreen(current?.isFullscreen ?? state.isFullscreen)
        let resolved = config.resolvedWindowState(for: base)
        if let engineOverride = resolved.engineOverride,
           engineOverride != FloatingLayoutEngine.engineID {
            throw OllyRuntimeError.unsupportedEngineCommand(
                command: "rule-engine-override",
                engineID: engineOverride
            )
        }
        return await restoredLayoutOrder(resolved)
    }

    private func refreshWindowElement(_ element: AXUIElement, application: Application) async {
        guard let snapshot = try? await snapshotCache.snapshot(for: element),
              let windowID = snapshot.attributes.windowID else {
            return
        }
        await refreshWindowSnapshot(snapshot, windowID: windowID, element: element, application: application)
    }

    private func refreshMovedWindowElement(_ element: AXUIElement, application: Application) async {
        guard let snapshot = try? await snapshotCache.snapshot(for: element),
              let windowID = snapshot.attributes.windowID else {
            return
        }
        let frame = snapshot.attributes.frame
        let target = WindowMoveTarget(
            id: windowID,
            axElement: element,
            displayID: displayID(for: frame)
        )
        let ourLastFrame = await windowMover.lastFrame(for: target)
        await dragSession.feed(windowID: windowID, frame: frame, ourLastFrame: ourLastFrame)
        await refreshWindowSnapshot(snapshot, windowID: windowID, element: element, application: application)
    }

    private func refreshWindowSnapshot(
        _ snapshot: WindowSnapshotCache.Snapshot,
        windowID: WindowID,
        element: AXUIElement,
        application: Application
    ) async {
        let displayID = displayID(for: snapshot.attributes.frame)
        let baseState = WindowState(
            id: windowID,
            processID: snapshot.attributes.processID,
            bundleID: application.bundleIdentifier,
            displayID: displayID,
            tagMask: Self.defaultActiveTags.rawValue,
            isFloating: false,
            frame: snapshot.attributes.frame,
            title: snapshot.attributes.title,
            role: snapshot.attributes.role,
            subrole: snapshot.attributes.subrole
        )
        do {
            try await upsertRuntimeWindow(baseState, element: element)
        } catch {
            lastError = String(describing: error)
        }
    }

    private func refreshFocusedWindow(from event: AXNotificationEvent) async {
        let application = applicationsByProcessID[event.processID] ?? Application(processID: event.processID)
        if let snapshot = try? await snapshotCache.snapshot(for: event.element),
           snapshot.attributes.windowID != nil {
            await refreshFocusedWindow(element: event.element, application: application)
            return
        }
        guard let focusedElement = await focusedWindowElement(for: application.axElement) else {
            return
        }
        await refreshFocusedWindow(element: focusedElement, application: application)
    }

    private func refreshFocusedWindow(element: AXUIElement, application: Application) async {
        await refreshWindowElement(element, application: application)
        guard let windowID = windowTargets.windowID(for: element),
              let window = await windowStore.state(for: windowID),
              let displayID = window.displayID else {
            return
        }
        guard await shouldAcceptFocusChange(processID: application.processID, bundleID: window.bundleID) else {
            return
        }
        await setFocusedWindow(windowID, displayID: displayID, tagMask: window.tagMask, publish: true)
    }

    private func removeWindow(for element: AXUIElement) async {
        guard let windowID = windowTargets.windowID(for: element) else {
            return
        }
        await dragSession.end(windowID: windowID)
        fullscreenTasksByWindowID[windowID]?.cancel()
        fullscreenTasksByWindowID[windowID] = nil
        _ = await fullscreenTracker.exit(windowID)
        await windowStore.remove(id: windowID)
        await focusStack.remove(windowID: windowID)
        windowTargets.remove(windowID: windowID)
        if focusedWindowID == windowID {
            focusedWindowID = nil
            await publishRuntimeEvent(.focus(IPCFocusEvent(focusedWindowID: nil)))
        }
    }

    private func restoredLayoutOrder(_ state: WindowState) async -> WindowState {
        guard state.layoutOrder == nil,
              let layoutOrder = try? await statePersistence.layoutOrder(for: state) else {
            return state
        }
        return state.withLayoutOrder(layoutOrder)
    }

    private func focusedWindowElement(for applicationElement: AXUIElement) async -> AXUIElement? {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(applicationElement, kAXFocusedWindowAttribute as CFString, &value)
        guard error == .success, let value else {
            await handleAXReadWriteError(error)
            return nil
        }
        return unsafeBitCast(value, to: AXUIElement.self)
    }

    private func frontmostApplication() -> Application? {
        applicationMonitor.runningApplications().first { application in
            NSRunningApplication(processIdentifier: application.processID)?.isActive == true
        }
    }

    func axWindows(for applicationElement: AXUIElement) async -> [AXUIElement] {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(applicationElement, kAXWindowsAttribute as CFString, &value)
        guard error == .success else {
            await handleAXReadWriteError(error)
            return []
        }
        return value as? [AXUIElement] ?? []
    }

    func displayID(for frame: CGRect) -> DisplayID? {
        displayProvider().max { lhs, rhs in
            lhs.frame.intersection(frame).area < rhs.frame.intersection(frame).area
        }?.id
    }

    static func makeRegistry() -> LayoutEngineRegistry {
        do {
            return try LayoutEngineRegistry(factories: [
                AnyLayoutEngineFactory(FloatingLayoutEngineFactory()),
                AnyLayoutEngineFactory(MasterStackLayoutEngineFactory()),
                AnyLayoutEngineFactory(ManualLayoutEngineFactory()),
                AnyLayoutEngineFactory(BSPLayoutEngineFactory()),
                AnyLayoutEngineFactory(NiriScrollLayoutEngineFactory()),
                AnyLayoutEngineFactory(MonocleLayoutEngineFactory()),
                AnyLayoutEngineFactory(SpiralLayoutEngineFactory()),
                AnyLayoutEngineFactory(GridLayoutEngineFactory()),
                AnyLayoutEngineFactory(ThreeColLayoutEngineFactory()),
                AnyLayoutEngineFactory(AccordionLayoutEngineFactory()),
                AnyLayoutEngineFactory(TabbedLayoutEngineFactory()),
                AnyLayoutEngineFactory(StackedLayoutEngineFactory()),
                AnyLayoutEngineFactory(TreeTabLayoutEngineFactory()),
                AnyLayoutEngineFactory(FrameLayoutEngineFactory()),
                AnyLayoutEngineFactory(PaperWMScrollLayoutEngineFactory()),
                AnyLayoutEngineFactory(VerticalTileLayoutEngineFactory()),
                AnyLayoutEngineFactory(RatioTileLayoutEngineFactory())
            ])
        } catch {
            fatalError("failed to create built-in layout registry: \(error)")
        }
    }

    static var defaultActiveTags: TagSet {
        do {
            return TagSet(try Tag(index: 0))
        } catch {
            fatalError("failed to create default tag: \(error)")
        }
    }
}
