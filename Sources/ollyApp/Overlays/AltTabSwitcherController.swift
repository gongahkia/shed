import AppKit
import Carbon.HIToolbox
import ollyIPC
import ollyKit
import ollyRuntime

enum AltTabNavigationDirection: Equatable {
    case left
    case right
    case upward
    case downward
}

enum AltTabPresentationMode: Equatable {
    case grid
    case list
}

enum AltTabKeyAction: Equatable {
    case move(AltTabNavigationDirection)
    case commit
    case cancel
    case none

    static func action(for event: NSEvent, isVisible: Bool) -> AltTabKeyAction {
        guard event.type == .keyDown, isVisible else {
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
}

struct AltTabGridLayout: Equatable {
    let columns: Int
    let rows: Int
    let itemFrames: [CGRect]

    static func make(itemCount: Int, in bounds: CGRect) -> AltTabGridLayout {
        guard itemCount > 0 else {
            return AltTabGridLayout(columns: 0, rows: 0, itemFrames: [])
        }
        let columns = min(max(1, Int(ceil(sqrt(Double(itemCount))))), 5)
        let rows = Int(ceil(Double(itemCount) / Double(columns)))
        let spacing: CGFloat = 14
        let content = bounds.insetBy(dx: max(28, bounds.width * 0.08), dy: max(28, bounds.height * 0.12))
        let itemWidth = max(1, (content.width - CGFloat(columns - 1) * spacing) / CGFloat(columns))
        let itemHeight = max(1, (content.height - CGFloat(rows - 1) * spacing) / CGFloat(rows))
        let frames = (0..<itemCount).map { index in
            let row = index / columns
            let column = index % columns
            return CGRect(
                x: content.minX + CGFloat(column) * (itemWidth + spacing),
                y: content.maxY - CGFloat(row + 1) * itemHeight - CGFloat(row) * spacing,
                width: itemWidth,
                height: itemHeight
            )
        }
        return AltTabGridLayout(columns: columns, rows: rows, itemFrames: frames)
    }
}

enum AltTabSelectionNavigator {
    static func move(
        from index: Int,
        direction: AltTabNavigationDirection,
        itemCount: Int,
        columns: Int
    ) -> Int {
        guard itemCount > 0 else {
            return 0
        }
        switch direction {
        case .left:
            return max(0, index - 1)
        case .right:
            return min(itemCount - 1, index + 1)
        case .upward:
            return max(0, index - max(1, columns))
        case .downward:
            return min(itemCount - 1, index + max(1, columns))
        }
    }
}

final class AltTabSwitcherController {
    typealias WindowProvider = () async -> [WindowState]
    typealias FocusedWindowProvider = () async -> WindowID?
    typealias FocusCommit = (WindowID) async throws -> Void
    typealias ThumbnailAvailability = () async -> Bool

    private static let thumbnailLimit = 50

    private let runtime: OllyRuntime
    private let overlayHostOverride: OverlayPanelHost?
    private let thumbnailCache: WindowThumbnailCache
    private let windowProvider: WindowProvider
    private let focusedWindowProvider: FocusedWindowProvider
    private let focusCommit: FocusCommit
    private let thumbnailAvailability: ThumbnailAvailability
    private let reduceMotionProvider: @MainActor () -> Bool
    private var requestTask: Task<Void, Never>?
    private var keyMonitor: AltTabKeyMonitor?
    private var switcherView: AltTabSwitcherView?
    private var windows: [WindowState] = []
    private var selectedWindowID: WindowID?
    private var mode = AltTabPresentationMode.grid
    private var isVisible = false

    init(
        runtime: OllyRuntime,
        overlayHost: OverlayPanelHost? = nil,
        thumbnailCache: WindowThumbnailCache = WindowThumbnailCache(),
        windowProvider: WindowProvider? = nil,
        focusedWindowProvider: FocusedWindowProvider? = nil,
        focusCommit: FocusCommit? = nil,
        thumbnailAvailability: ThumbnailAvailability? = nil,
        reduceMotionProvider: @escaping @MainActor () -> Bool = {
            NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        }
    ) {
        self.runtime = runtime
        self.overlayHostOverride = overlayHost
        self.thumbnailCache = thumbnailCache
        self.windowProvider = windowProvider ?? { [runtime] in await runtime.altTabWindows() }
        self.focusedWindowProvider = focusedWindowProvider ?? { [runtime] in await runtime.focusedWindowForAltTab() }
        self.focusCommit = focusCommit ?? { [runtime] windowID in try await runtime.focusWindowFromAltTab(windowID) }
        self.thumbnailAvailability = thumbnailAvailability ?? { [thumbnailCache] in
            await thumbnailCache.isCaptureAvailable()
        }
        self.reduceMotionProvider = reduceMotionProvider
    }

    @MainActor
    var selectedID: WindowID? {
        selectedWindowID
    }

    @MainActor
    var presentationMode: AltTabPresentationMode {
        mode
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
        keyMonitor = AltTabKeyMonitor(
            isVisible: { [weak self] in self?.isVisible == true },
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
                guard kind == .altTab else {
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
        let providedWindows = await windowProvider()
        guard !providedWindows.isEmpty else {
            hide()
            return
        }
        windows = providedWindows
        selectedWindowID = await initialSelection(in: providedWindows)
        mode = await presentationMode(for: providedWindows)
        _ = OverlayRegistry.shared.register(
            .altTab,
            hiding: [.cheatsheet, .commandPalette, .dragSnap, .grid, .overview]
        )
        ensurePanels()
        render()
        guard !isVisible else {
            return
        }
        isVisible = true
        overlayHost.showAll()
    }

    @MainActor
    func handle(_ action: AltTabKeyAction) async {
        switch action {
        case let .move(direction):
            guard isVisible else {
                return
            }
            moveSelection(direction)
        case .commit:
            await commitSelection()
        case .cancel:
            hide()
        case .none:
            return
        }
    }

    @MainActor private func presentationMode(for windows: [WindowState]) async -> AltTabPresentationMode {
        guard windows.count <= Self.thumbnailLimit else {
            return .list
        }
        return await thumbnailAvailability() ? .grid : .list
    }

    @MainActor private func initialSelection(in windows: [WindowState]) async -> WindowID? {
        if let selectedWindowID, windows.contains(where: { $0.id == selectedWindowID }) {
            return selectedWindowID
        }
        if let focused = await focusedWindowProvider(), windows.contains(where: { $0.id == focused }) {
            return focused
        }
        return windows.first?.id
    }

    @MainActor private func ensurePanels() {
        if overlayHost.activeCount == 0 {
            overlayHost.rebuildOverlays(level: .floating, clickThrough: false)
        }
    }

    @MainActor private func render() {
        guard let displayID = displayID(for: windows),
              let panel = overlayHost.panel(for: displayID) else {
            return
        }
        for (panelDisplayID, panel) in overlayHost.activePanels where panelDisplayID != displayID {
            panel.contentView = NSView(frame: CGRect(origin: .zero, size: panel.frame.size))
        }
        let view = switcherView ?? AltTabSwitcherView(thumbnailCache: thumbnailCache)
        view.frame = CGRect(origin: .zero, size: panel.frame.size)
            view.autoresizingMask = [.width, .height]
        panel.contentView = view
        switcherView = view
        view.configure(
            windows: windows,
            selectedID: selectedWindowID,
            mode: mode,
            reduceMotion: reduceMotionProvider()
        )
    }

    @MainActor private func moveSelection(_ direction: AltTabNavigationDirection) {
        guard let selectedWindowID,
              let index = windows.firstIndex(where: { $0.id == selectedWindowID }) else {
            self.selectedWindowID = windows.first?.id
            render()
            return
        }
        let columns = AltTabGridLayout.make(itemCount: windows.count, in: switcherView?.bounds ?? .zero).columns
        let nextIndex = AltTabSelectionNavigator.move(
            from: index,
            direction: direction,
            itemCount: windows.count,
            columns: mode == .grid ? columns : 1
        )
        self.selectedWindowID = windows[nextIndex].id
        render()
    }

    @MainActor private func commitSelection() async {
        guard isVisible, let selectedWindowID else {
            return
        }
        hide()
        try? await focusCommit(selectedWindowID)
    }

    @MainActor private func hide() {
        guard isVisible || overlayHost.activeCount > 0 else {
            OverlayRegistry.shared.unregister(.altTab)
            return
        }
        isVisible = false
        windows = []
        selectedWindowID = nil
        switcherView?.resetContent()
        switcherView = nil
        overlayHost.hideAll()
        OverlayRegistry.shared.unregister(.altTab)
    }

    @MainActor private func displayID(for windows: [WindowState]) -> DisplayID? {
        selectedWindowID
            .flatMap { id in windows.first { $0.id == id }?.displayID }
            ?? windows.first?.displayID
            ?? overlayHost.activePanels.keys.sorted().first
    }

    @MainActor private var overlayHost: OverlayPanelHost {
        overlayHostOverride ?? .shared
    }
}

final class AltTabKeyMonitor {
    private let isVisible: () -> Bool
    private let onAction: (AltTabKeyAction) -> Void
    private var localMonitor: Any?
    private var globalMonitor: Any?

    init(isVisible: @escaping () -> Bool, onAction: @escaping (AltTabKeyAction) -> Void) {
        self.isVisible = isVisible
        self.onAction = onAction
    }

    func install() {
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else {
                return event
            }
            let action = AltTabKeyAction.action(for: event, isVisible: isVisible())
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
            let action = AltTabKeyAction.action(for: event, isVisible: isVisible())
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
