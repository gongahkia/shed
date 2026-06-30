import AppKit
import Carbon.HIToolbox
import ollyIPC
import ollyKit
import ollyRuntime

enum GridNavigationDirection: Equatable {
    case left
    case right
    case upward
    case downward
}

enum GridZoneNavigator {
    private static let rows: [[IPCSnapPosition]] = [
        [.topLeft, .topHalf, .topRight],
        [.leftHalf, .center, .rightHalf],
        [.bottomLeft, .bottomHalf, .bottomRight],
        [.maximize]
    ]

    static func move(from position: IPCSnapPosition, direction: GridNavigationDirection) -> IPCSnapPosition {
        guard let coordinate = coordinate(for: position) else {
            return .center
        }
        if position == .maximize {
            return direction == .upward ? .center : .maximize
        }
        switch direction {
        case .left:
            return positionAt(row: coordinate.row, column: coordinate.column - 1)
        case .right:
            return positionAt(row: coordinate.row, column: coordinate.column + 1)
        case .upward:
            return positionAt(row: coordinate.row - 1, column: coordinate.column)
        case .downward:
            return positionAt(row: coordinate.row + 1, column: coordinate.column)
        }
    }

    private static func coordinate(for position: IPCSnapPosition) -> (row: Int, column: Int)? {
        for (row, positions) in rows.enumerated() {
            if let column = positions.firstIndex(of: position) {
                return (row, column)
            }
        }
        return nil
    }

    private static func positionAt(row: Int, column: Int) -> IPCSnapPosition {
        if row >= rows.count - 1 {
            return .maximize
        }
        let row = max(0, row)
        let positions = rows[row]
        let column = min(max(0, column), positions.count - 1)
        return positions[column]
    }
}

enum GridOverlayKeyAction: Equatable {
    case showGrid
    case move(GridNavigationDirection)
    case commit
    case cancel
    case none

    static func action(for event: NSEvent, isGridActive: Bool) -> GridOverlayKeyAction {
        guard event.type == .keyDown else {
            return .none
        }
        if matchesShowGridHotKey(event) {
            return .showGrid
        }
        guard isGridActive else {
            return .none
        }
        switch Int(event.keyCode) {
        case kVK_LeftArrow:
            return .move(.left)
        case kVK_RightArrow:
            return .move(.right)
        case kVK_UpArrow:
            return .move(.upward)
        case kVK_DownArrow:
            return .move(.downward)
        case kVK_Return:
            return .commit
        case kVK_Escape:
            return .cancel
        default:
            return .none
        }
    }

    private static func matchesShowGridHotKey(_ event: NSEvent) -> Bool {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        return Int(event.keyCode) == kVK_ANSI_Slash && flags == [.command, .shift]
    }
}

final class GridOverlayController {
    typealias DisplayIDProvider = () async -> DisplayID?
    typealias LayoutFrameProvider = (DisplayID) async -> CGRect?
    typealias SnapCommit = (IPCSnapWindowCommand) async throws -> Void

    private let runtime: OllyRuntime
    private let overlayHostOverride: OverlayPanelHost?
    private let displayIDProvider: DisplayIDProvider
    private let layoutFrameProvider: LayoutFrameProvider
    private let snapCommit: SnapCommit
    private let reduceMotionProvider: @MainActor () -> Bool
    private var requestTask: Task<Void, Never>?
    private var keyMonitor: GridOverlayKeyMonitor?
    private var views: [DisplayID: SnapZoneView] = [:]
    private var currentDisplayID: DisplayID?
    private var currentLayoutFrame: CGRect?
    private var selected = IPCSnapPosition.center
    private var isVisible = false

    init(
        runtime: OllyRuntime,
        overlayHost: OverlayPanelHost? = nil,
        displayIDProvider: DisplayIDProvider? = nil,
        layoutFrameProvider: LayoutFrameProvider? = nil,
        snapCommit: SnapCommit? = nil,
        reduceMotionProvider: @escaping @MainActor () -> Bool = {
            NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        }
    ) {
        self.runtime = runtime
        self.overlayHostOverride = overlayHost
        self.displayIDProvider = displayIDProvider ?? { [runtime] in
            await runtime.snapTargetDisplayID()
        }
        self.layoutFrameProvider = layoutFrameProvider ?? { [runtime] displayID in
            await runtime.snapLayoutFrame(for: displayID)
        }
        self.snapCommit = snapCommit ?? { [runtime] command in
            try await runtime.snapWindowFromOverlay(command)
        }
        self.reduceMotionProvider = reduceMotionProvider
    }

    var selectedPosition: IPCSnapPosition {
        selected
    }

    @MainActor
    var activeOverlayCount: Int {
        overlayHost.activeCount
    }

    @MainActor
    func start() {
        guard requestTask == nil, keyMonitor == nil else {
            return
        }
        keyMonitor = GridOverlayKeyMonitor(
            isGridActive: { [weak self] in self?.isVisible == true },
            onAction: { [weak self] action in
                Task { @MainActor in
                    await self?.handle(action)
                }
            }
        )
        keyMonitor?.install()
        requestTask = Task { [weak self, runtime] in
            let stream = await runtime.overlayRequests.subscribe()
            for await kind in stream where !Task.isCancelled {
                guard kind == .grid else {
                    continue
                }
                await self?.show()
            }
        }
    }

    @MainActor
    func stop() {
        requestTask?.cancel()
        requestTask = nil
        keyMonitor?.remove()
        keyMonitor = nil
        hide()
    }

    @MainActor
    func show() async {
        guard let displayID = await displayIDProvider(),
              let layoutFrame = await layoutFrameProvider(displayID) else {
            hide()
            return
        }
        currentDisplayID = displayID
        currentLayoutFrame = layoutFrame
        selected = .center
        _ = OverlayRegistry.shared.register(.grid, hiding: [.dragSnap])
        if overlayHost.activeCount == 0 {
            overlayHost.rebuildOverlays(level: .floating, clickThrough: true)
        }
        render()
        guard !isVisible else {
            return
        }
        isVisible = true
        overlayHost.showAll()
    }

    @MainActor
    func handle(_ action: GridOverlayKeyAction) async {
        switch action {
        case .showGrid:
            isVisible ? hide() : await show()
        case let .move(direction):
            guard isVisible else {
                return
            }
            selected = GridZoneNavigator.move(from: selected, direction: direction)
            render()
        case .commit:
            commitSelection()
        case .cancel:
            hide()
        case .none:
            return
        }
    }

    @MainActor private func render() {
        guard let currentDisplayID, let currentLayoutFrame else {
            return
        }
        for (displayID, panel) in overlayHost.activePanels {
            let view = view(for: displayID, panel: panel)
            if displayID == currentDisplayID {
                view.update(
                    zones: localZones(in: currentLayoutFrame, panel: panel),
                    highlighted: selected,
                    animateHighlight: !reduceMotionProvider()
                )
            } else {
                view.update(zones: [], highlighted: nil, animateHighlight: false)
            }
        }
    }

    @MainActor private func commitSelection() {
        guard isVisible, let currentDisplayID else {
            return
        }
        let command = IPCSnapWindowCommand(position: selected, displayID: currentDisplayID)
        hide()
        Task { [snapCommit] in
            try? await snapCommit(command)
        }
    }

    @MainActor private func hide() {
        guard isVisible else {
            OverlayRegistry.shared.unregister(.grid)
            return
        }
        isVisible = false
        currentDisplayID = nil
        currentLayoutFrame = nil
        selected = .center
        views.removeAll()
        overlayHost.hideAll()
        OverlayRegistry.shared.unregister(.grid)
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
            SnapZone(position: zone.position, frame: zone.frame.offsetBy(dx: -origin.x, dy: -origin.y))
        }
    }

    @MainActor private var overlayHost: OverlayPanelHost {
        overlayHostOverride ?? .shared
    }
}

final class GridOverlayKeyMonitor {
    private let isGridActive: () -> Bool
    private let onAction: (GridOverlayKeyAction) -> Void
    private var localMonitor: Any?
    private var globalMonitor: Any?

    init(isGridActive: @escaping () -> Bool, onAction: @escaping (GridOverlayKeyAction) -> Void) {
        self.isGridActive = isGridActive
        self.onAction = onAction
    }

    func install() {
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else {
                return event
            }
            let action = GridOverlayKeyAction.action(for: event, isGridActive: isGridActive())
            guard action != .none else {
                return event
            }
            onAction(action)
            return nil
        }
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else {
                return
            }
            let action = GridOverlayKeyAction.action(for: event, isGridActive: isGridActive())
            guard action != .none else {
                return
            }
            onAction(action)
        }
    }

    func remove() {
        if let localMonitor {
            NSEvent.removeMonitor(localMonitor)
        }
        if let globalMonitor {
            NSEvent.removeMonitor(globalMonitor)
        }
        localMonitor = nil
        globalMonitor = nil
    }
}
