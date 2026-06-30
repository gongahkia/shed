import AppKit
import Carbon.HIToolbox
import ollyDSL
import ollyIPC
import ollyRuntime

enum CheatsheetCategory: String, CaseIterable, Comparable {
    case focus
    case swap
    case move
    case tag
    case engine
    case snap
    case custom

    static func < (lhs: CheatsheetCategory, rhs: CheatsheetCategory) -> Bool {
        order(lhs) < order(rhs)
    }

    var title: String {
        rawValue.capitalized
    }

    private static func order(_ category: CheatsheetCategory) -> Int {
        Self.allCases.firstIndex(of: category) ?? 0
    }
}

struct CheatsheetEntry: Equatable {
    let category: CheatsheetCategory
    let title: String
    let detail: String
}

enum CheatsheetCatalog {
    static func entries(from keybinds: Keybinds) -> [CheatsheetEntry] {
        keybinds.bindings.map { keybind in
            CheatsheetEntry(
                category: category(for: keybind.action),
                title: title(for: keybind.action),
                detail: KeyChordFormatter.string(for: keybind.chord)
            )
        }.sorted { lhs, rhs in
            if lhs.category == rhs.category {
                return lhs.title < rhs.title
            }
            return lhs.category < rhs.category
        }
    }

    private static func category(for action: Action) -> CheatsheetCategory {
        switch action {
        case .focus:
            return .focus
        case .swap:
            return .swap
        case .move:
            return .move
        case .resize, .split:
            return .move
        case .switchTag, .toggleTag, .moveWindowToTag:
            return .tag
        case .setEngine, .cycleEngine:
            return .engine
        case .showGridOverlay, .showOverlay(.grid):
            return .snap
        default:
            return .custom
        }
    }

    private static func title(for action: Action) -> String {
        switch category(for: action) {
        case .focus:
            return directionalTitle("Focus", action)
        case .swap:
            return directionalTitle("Swap", action)
        case .move:
            return directionalTitle("Move", action)
        case .tag:
            return tagTitle(action)
        case .engine:
            return engineTitle(action)
        case .snap:
            return "Show grid overlay"
        case .custom:
            return customTitle(action)
        }
    }

    private static func directionalTitle(_ prefix: String, _ action: Action) -> String {
        switch action {
        case let .focus(direction):
            return "\(prefix) \(direction.rawValue)"
        case let .swap(direction):
            return "\(prefix) \(direction.rawValue)"
        case let .move(direction):
            return "\(prefix) \(direction.rawValue)"
        case let .resize(direction, points):
            return "Resize \(direction.rawValue) \(Int(points))pt"
        case let .split(direction, ratio):
            return "Split \(direction.rawValue) \(Int(ratio * 100))%"
        default:
            return prefix
        }
    }

    private static func tagTitle(_ action: Action) -> String {
        switch action {
        case let .switchTag(index):
            return "Switch tag \(index)"
        case let .toggleTag(index):
            return "Toggle tag \(index)"
        case let .moveWindowToTag(index):
            return "Move window to tag \(index)"
        default:
            return "Tag"
        }
    }

    private static func engineTitle(_ action: Action) -> String {
        switch action {
        case let .setEngine(engineID):
            return "Set engine \(engineID.rawValue)"
        case .cycleEngine:
            return "Cycle engine"
        default:
            return "Engine"
        }
    }

    private static func customTitle(_ action: Action) -> String {
        switch action {
        case .reload:
            return "Reload config"
        case .noop:
            return "No-op"
        case .showAltTab, .showOverlay(.altTab):
            return "Show alt-tab overlay"
        case .showCheatsheet, .showOverlay(.cheatsheet):
            return "Show cheatsheet"
        case let .macro(name):
            return "Run macro \(name)"
        case let .shell(action):
            return "Run \(action.label)"
        case let .raw(label):
            return "Run \(label)"
        default:
            return "Custom"
        }
    }
}

enum CheatsheetKeyAction: Equatable {
    case show
    case close
    case none

    static func action(for event: NSEvent, isVisible: Bool) -> CheatsheetKeyAction {
        guard event.type == .keyDown else {
            return .none
        }
        if matchesShowHotKey(event) {
            return .show
        }
        return isVisible && Int(event.keyCode) == kVK_Escape ? .close : .none
    }

    private static func matchesShowHotKey(_ event: NSEvent) -> Bool {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        return Int(event.keyCode) == kVK_ANSI_Slash && flags == .command
    }
}

final class CheatsheetController {
    typealias KeybindProvider = () async -> Keybinds

    private let runtime: OllyRuntime
    private let keybindProvider: KeybindProvider
    private var requestTask: Task<Void, Never>?
    private var keyMonitor: CheatsheetKeyMonitor?
    private var panel: CheatsheetPanel?
    private var cheatsheetView: CheatsheetView?
    private var isVisible = false

    init(runtime: OllyRuntime, keybindProvider: KeybindProvider? = nil) {
        self.runtime = runtime
        self.keybindProvider = keybindProvider ?? { [runtime] in
            await runtime.keybinds()
        }
    }

    @MainActor
    var rowCount: Int {
        cheatsheetView?.rowCount ?? 0
    }

    @MainActor
    func start() {
        guard requestTask == nil, keyMonitor == nil else {
            return
        }
        keyMonitor = CheatsheetKeyMonitor(
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
                guard kind == .cheatsheet else {
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
        let entries = CheatsheetCatalog.entries(from: await keybindProvider())
        let panel = panel ?? CheatsheetPanel()
        let view = cheatsheetView ?? CheatsheetView()
        self.panel = panel
        cheatsheetView = view
        view.configure(entries: entries)
        panel.contentView = view
        panel.onCancel = { [weak self] in
            Task { @MainActor in
                self?.hide()
            }
        }
        position(panel)
        _ = OverlayRegistry.shared.register(.cheatsheet, hiding: [.commandPalette, .grid])
        panel.orderFrontRegardless()
        OverlayPanelHost.fade(panel, to: 1)
        isVisible = true
    }

    @MainActor
    func handle(_ action: CheatsheetKeyAction) async {
        switch action {
        case .show:
            isVisible ? hide() : await show()
        case .close:
            hide()
        case .none:
            return
        }
    }

    @MainActor private func hide() {
        guard isVisible else {
            OverlayRegistry.shared.unregister(.cheatsheet)
            return
        }
        isVisible = false
        guard let panel else {
            OverlayRegistry.shared.unregister(.cheatsheet)
            return
        }
        self.panel = nil
        cheatsheetView = nil
        OverlayPanelHost.fade(panel, to: 0) {
            panel.close()
        }
        OverlayRegistry.shared.unregister(.cheatsheet)
    }

    @MainActor private func position(_ panel: CheatsheetPanel) {
        let screen = NSScreen.main ?? NSScreen.screens.first
        let screenFrame = screen?.visibleFrame ?? CGRect(x: 0, y: 0, width: 900, height: 700)
        let width = min(720, screenFrame.width - 80)
        let height = min(560, screenFrame.height - 80)
        panel.setFrame(
            CGRect(
                x: screenFrame.midX - width / 2,
                y: screenFrame.midY - height / 2,
                width: width,
                height: height
            ),
            display: true
        )
        panel.alphaValue = 0
    }
}

final class CheatsheetPanel: NSPanel {
    var onCancel: (() -> Void)?

    init() {
        super.init(
            contentRect: CGRect(x: 0, y: 0, width: 720, height: 560),
            styleMask: [.titled, .nonactivatingPanel, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        title = "Keybinds"
        isOpaque = false
        backgroundColor = .windowBackgroundColor
        hasShadow = true
        level = .floating
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        isReleasedWhenClosed = false
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }

    override func cancelOperation(_ sender: Any?) {
        onCancel?()
    }
}

final class CheatsheetView: NSView {
    private let scrollView = NSScrollView()
    private let stack = NSStackView()
    private(set) var rowCount = 0

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        configure()
    }

    required init?(coder: NSCoder) {
        nil
    }

    func configure(entries: [CheatsheetEntry]) {
        stack.arrangedSubviews.forEach { view in
            stack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        rowCount = entries.count
        for category in CheatsheetCategory.allCases {
            let grouped = entries.filter { $0.category == category }
            guard !grouped.isEmpty else {
                continue
            }
            stack.addArrangedSubview(header(category.title))
            for entry in grouped {
                let row = CommandPaletteRowView()
                row.configure(title: entry.title, detail: entry.detail)
                row.heightAnchor.constraint(equalToConstant: 46).isActive = true
                stack.addArrangedSubview(row)
            }
        }
        let rows = stack.arrangedSubviews.compactMap { $0 as? CommandPaletteRowView }
        setAccessibilityChildren(rows)
        setAccessibilityChildrenInNavigationOrder(rows.map { $0 as any NSAccessibilityElementProtocol })
    }

    private func configure() {
        wantsLayer = true
        layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
        setAccessibilityElement(true)
        setAccessibilityRole(.list)
        setAccessibilityLabel("Keybinds")
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.orientation = .vertical
        stack.spacing = 2
        stack.edgeInsets = NSEdgeInsets(top: 18, left: 20, bottom: 18, right: 20)
        scrollView.documentView = stack
        addSubview(scrollView)
        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.widthAnchor.constraint(equalTo: scrollView.contentView.widthAnchor)
        ])
    }

    private func header(_ title: String) -> NSTextField {
        let label = NSTextField(labelWithString: title)
        label.font = .systemFont(ofSize: 12, weight: .bold)
        label.textColor = .secondaryLabelColor
        return label
    }
}
