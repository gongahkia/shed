import ApplicationServices
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
        let displayID = try selectedDisplay(command.displayID).requiredID()
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
        focusedWindowID = nextID
        if let window = windows.first(where: { $0.id == nextID }),
           let target = windowTargets.target(for: window) {
            AXUIElementSetAttributeValue(target.axElement, kAXFocusedAttribute as CFString, kCFBooleanTrue)
        }
        let activeTags = await tagStore.activeTags(on: displayID)
        await focusStack.recordFocus(
            windowID: nextID,
            displayID: displayID,
            tagMask: activeTags.rawValue
        )
        await eventHub.publish(
            .focus(IPCFocusEvent(focusedWindowID: nextID, displayID: displayID, tagMask: activeTags.rawValue))
        )
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
            !$0.isFloating && TagSet(rawValue: $0.tagMask).intersects(activeTags)
        }
    }

    func startApplicationObservation() {
        let task = Task { [weak self, applicationMonitor] in
            for await event in applicationMonitor.events() {
                guard let self, !Task.isCancelled else {
                    return
                }
                await self.handle(applicationEvent: event)
            }
        }
        tasks.append(task)
    }

    func handle(applicationEvent: ApplicationEvent) async {
        switch applicationEvent {
        case let .launched(application):
            await refreshWindows(for: application)
        case let .terminated(application):
            let windows = await windowStore.windows(forProcessID: application.processID)
            for window in windows {
                await windowStore.remove(id: window.id)
                await focusStack.remove(windowID: window.id)
                windowTargets.remove(windowID: window.id)
            }
        }
    }

    func refreshAllWindows() async {
        for application in applicationMonitor.runningApplications() {
            await refreshWindows(for: application)
        }
    }

    func refreshWindows(for application: Application) async {
        for element in axWindows(for: application.axElement) {
            guard let snapshot = try? await snapshotCache.snapshot(for: element),
                  let windowID = snapshot.attributes.windowID else {
                continue
            }
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
            await upsertRuntimeWindow(baseState, element: element)
        }
    }

    func reapplyRulesToStoredWindows() async {
        for window in await windowStore.allWindows() {
            await upsertRuntimeWindow(window, element: windowTargets.target(for: window)?.axElement)
        }
    }

    func upsertRuntimeWindow(_ state: WindowState, element: AXUIElement?) async {
        let config = await configStore.current()
        let apply = config.resolvedApply(
            for: RuleContext(
                bundleID: state.bundleID,
                title: state.title,
                role: state.role,
                subrole: state.subrole,
                windowSize: state.frame.size
            )
        )
        let resolved = config.resolvedWindowState(for: state)
        await windowStore.upsert(resolved)
        if let element {
            windowTargets.set(
                WindowMoveTarget(id: resolved.id, axElement: element, displayID: resolved.displayID),
                for: resolved.id
            )
        }
        if let engineID = apply.engineOverride,
           let displayID = resolved.displayID {
            for tag in TagSet(rawValue: resolved.tagMask).tags {
                await tagStore.bindEngine(engineID, to: tag, on: displayID)
            }
        }
    }

    func axWindows(for applicationElement: AXUIElement) -> [AXUIElement] {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(applicationElement, kAXWindowsAttribute as CFString, &value)
        guard error == .success else {
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

extension CGRect {
    var area: CGFloat {
        guard !isNull else {
            return 0
        }
        return width * height
    }
}
