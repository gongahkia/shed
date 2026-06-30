import AppKit
import ollyIPC
import ollyKit
import ollyRuntime

final class DragSnapOverlayController {
    typealias LayoutFrameProvider = (DisplayID) async -> CGRect?
    typealias DisplayIDProvider = (CGPoint, CGRect) async -> DisplayID?
    typealias SnapCommit = (IPCSnapWindowCommand) async throws -> Void

    private struct Selection: Equatable {
        let windowID: WindowID
        let displayID: DisplayID
        let position: IPCSnapPosition
    }

    private let runtime: OllyRuntime
    private let overlayHostOverride: OverlayPanelHost?
    private let layoutFrameProvider: LayoutFrameProvider
    private let displayIDProvider: DisplayIDProvider
    private let snapCommit: SnapCommit
    private let reduceMotionProvider: @MainActor () -> Bool
    private var task: Task<Void, Never>?
    private var views: [DisplayID: SnapZoneView] = [:]
    private var selection: Selection?
    private var isVisible = false

    init(
        runtime: OllyRuntime,
        overlayHost: OverlayPanelHost? = nil,
        layoutFrameProvider: LayoutFrameProvider? = nil,
        displayIDProvider: DisplayIDProvider? = nil,
        snapCommit: SnapCommit? = nil,
        reduceMotionProvider: @escaping @MainActor () -> Bool = {
            NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        }
    ) {
        self.runtime = runtime
        self.overlayHostOverride = overlayHost
        self.layoutFrameProvider = layoutFrameProvider ?? { [runtime] displayID in
            await runtime.snapLayoutFrame(for: displayID)
        }
        self.displayIDProvider = displayIDProvider ?? { [runtime] mousePoint, fallbackFrame in
            if let displayID = await runtime.displayID(containing: mousePoint) {
                return displayID
            }
            return await runtime.displayID(containing: CGPoint(x: fallbackFrame.midX, y: fallbackFrame.midY))
        }
        self.snapCommit = snapCommit ?? { [runtime] command in
            try await runtime.snapWindowFromOverlay(command)
        }
        self.reduceMotionProvider = reduceMotionProvider
    }

    @MainActor
    var highlightedPosition: IPCSnapPosition? {
        selection?.position
    }

    @MainActor
    var activeOverlayCount: Int {
        overlayHost.activeCount
    }

    @MainActor
    func start() {
        guard task == nil else {
            return
        }
        task = Task { [weak self, runtime] in
            let stream = await runtime.dragSession.subscribe()
            for await event in stream where !Task.isCancelled {
                await self?.handle(event)
            }
        }
    }

    @MainActor
    func stop() {
        task?.cancel()
        task = nil
        hide()
    }

    @MainActor
    func handle(_ event: AXDragEvent) async {
        switch event {
        case let .started(windowID, frame, mousePoint),
             let .moved(windowID, frame, mousePoint):
            await update(windowID: windowID, frame: frame, mousePoint: mousePoint)
        case let .ended(windowID, _):
            commitSelection(for: windowID)
            hide()
        }
    }

    @MainActor private func update(windowID: WindowID, frame: CGRect, mousePoint: CGPoint) async {
        guard let displayID = await displayIDProvider(mousePoint, frame),
              let layoutFrame = await layoutFrameProvider(displayID) else {
            hide()
            return
        }
        let position = SnapZoneResolver.zone(for: mousePoint, in: layoutFrame)
        selection = position.map { Selection(windowID: windowID, displayID: displayID, position: $0) }
        show(displayID: displayID, layoutFrame: layoutFrame, highlighted: position)
    }

    @MainActor private func show(displayID: DisplayID, layoutFrame: CGRect, highlighted: IPCSnapPosition?) {
        _ = OverlayRegistry.shared.register(.dragSnap)
        let overlayHost = overlayHost
        if overlayHost.activeCount == 0 {
            overlayHost.rebuildOverlays(level: .floating, clickThrough: true)
        }
        let panels = overlayHost.activePanels
        for (panelDisplayID, panel) in panels {
            let view = view(for: panelDisplayID, panel: panel)
            if panelDisplayID == displayID {
                view.update(
                    zones: localZones(in: layoutFrame, panel: panel),
                    highlighted: highlighted,
                    animateHighlight: !reduceMotionProvider()
                )
            } else {
                view.update(zones: [], highlighted: nil, animateHighlight: false)
            }
        }
        guard !isVisible else {
            return
        }
        isVisible = true
        overlayHost.showAll()
    }

    @MainActor private func hide() {
        let overlayHost = overlayHost
        guard isVisible || overlayHost.activeCount > 0 else {
            OverlayRegistry.shared.unregister(.dragSnap)
            return
        }
        isVisible = false
        selection = nil
        views.removeAll()
        overlayHost.hideAll()
        OverlayRegistry.shared.unregister(.dragSnap)
    }

    @MainActor private func commitSelection(for windowID: WindowID) {
        guard let selection, selection.windowID == windowID else {
            return
        }
        let command = IPCSnapWindowCommand(
            position: selection.position,
            windowID: windowID,
            displayID: selection.displayID
        )
        Task { [snapCommit] in
            try? await snapCommit(command)
        }
    }

    @MainActor private func view(for displayID: DisplayID, panel: OverlayPanel) -> SnapZoneView {
        if let view = views[displayID] {
            return view
        }
        let view = SnapZoneView(frame: CGRect(origin: .zero, size: panel.frame.size))
        view.autoresizingMask = [.width, .height]
        panel.contentView = view
        views[displayID] = view
        return view
    }

    @MainActor private func localZones(in layoutFrame: CGRect, panel: OverlayPanel) -> [SnapZone] {
        let origin = panel.frame.origin
        return SnapZoneResolver.zones(in: layoutFrame).map { zone in
            SnapZone(
                position: zone.position,
                frame: zone.frame.offsetBy(dx: -origin.x, dy: -origin.y)
            )
        }
    }

    @MainActor private var overlayHost: OverlayPanelHost {
        overlayHostOverride ?? .shared
    }
}
