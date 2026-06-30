import AppKit
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
import ollyRuntime

final class FocusRingController {
    private let runtime: OllyRuntime
    private let screenProvider: @MainActor () -> [NSScreen]
    private let panelFactory: @MainActor (NSScreen) -> OverlayPanel
    private let frameProvider: (WindowID) async -> CGRect?
    private let focusRingProvider: () async -> FocusRing
    private let reduceMotionProvider: @MainActor () -> Bool
    private var task: Task<Void, Never>?
    private var panel: OverlayPanel?
    private var ringView: FocusRingView?

    init(
        runtime: OllyRuntime,
        screenProvider: @escaping @MainActor () -> [NSScreen] = { NSScreen.screens },
        panelFactory: @escaping @MainActor (NSScreen) -> OverlayPanel = {
            OverlayPanel(screen: $0, level: .floating, clickThrough: true)
        },
        frameProvider: ((WindowID) async -> CGRect?)? = nil,
        focusRingProvider: (() async -> FocusRing)? = nil,
        reduceMotionProvider: @escaping @MainActor () -> Bool = {
            NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        }
    ) {
        self.runtime = runtime
        self.screenProvider = screenProvider
        self.panelFactory = panelFactory
        self.frameProvider = frameProvider ?? { [runtime] windowID in
            await runtime.frameForWindow(windowID)
        }
        self.focusRingProvider = focusRingProvider ?? { [runtime] in
            await runtime.focusRing()
        }
        self.reduceMotionProvider = reduceMotionProvider
    }

    @MainActor
    var currentPanelFrame: CGRect? {
        panel?.frame
    }

    @MainActor
    func start() {
        guard task == nil else {
            return
        }
        _ = OverlayRegistry.shared.register(.focusRing)
        let runtime = runtime
        task = Task { [weak self] in
            let stream = await runtime.runtimeEventBus.subscribe()
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
        OverlayRegistry.shared.unregister(.focusRing)
    }

    @MainActor
    func handle(_ event: IPCEvent) async {
        guard case let .focus(payload) = event else {
            return
        }
        guard let windowID = payload.focusedWindowID,
              let frame = await frameProvider(windowID) else {
            hide()
            return
        }
        let focusRing = await focusRingProvider()
        show(frame: frame.standardized, focusRing: focusRing)
    }

    @MainActor private func show(frame: CGRect, focusRing: FocusRing) {
        guard focusRing.width > 0, let screen = screen(containing: frame) else {
            hide()
            return
        }
        let panel = panel(for: screen)
        let view = ringView ?? FocusRingView()
        ringView = view
        panel.contentView = view
        view.apply(focusRing)
        panel.orderFrontRegardless()
        animate(panel: panel, to: frame, focusRing: focusRing)
    }

    @MainActor private func hide() {
        guard let panel else {
            return
        }
        self.panel = nil
        ringView = nil
        panel.orderOut(nil)
        panel.close()
    }

    @MainActor private func panel(for screen: NSScreen) -> OverlayPanel {
        if let panel {
            return panel
        }
        let panel = panelFactory(screen)
        panel.alphaValue = 1
        self.panel = panel
        return panel
    }

    @MainActor private func animate(panel: OverlayPanel, to frame: CGRect, focusRing: FocusRing) {
        let duration = animationDuration(for: focusRing)
        guard duration > 0 else {
            panel.setFrame(frame, display: true)
            panel.alphaValue = 1
            return
        }
        NSAnimationContext.runAnimationGroup { context in
            context.duration = duration
            panel.animator().setFrame(frame, display: true)
            panel.animator().alphaValue = 1
        }
    }

    @MainActor private func animationDuration(for focusRing: FocusRing) -> TimeInterval {
        switch focusRing.reduceMotion {
        case .alwaysAnimate:
            return 0.12
        case .neverAnimate:
            return 0
        case .respectSystem:
            return reduceMotionProvider() ? 0 : 0.12
        }
    }

    @MainActor private func screen(containing frame: CGRect) -> NSScreen? {
        let center = CGPoint(x: frame.midX, y: frame.midY)
        return screenProvider().first { $0.frame.contains(center) } ?? screenProvider().first
    }
}

@MainActor
final class FocusRingView: NSView {
    private let shapeLayer = CAShapeLayer()
    private var focusRing = FocusRing()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer = CALayer()
        layer?.addSublayer(shapeLayer)
        shapeLayer.fillColor = NSColor.clear.cgColor
        setAccessibilityElement(true)
        setAccessibilityRole(.unknown)
        setAccessibilityLabel(L10n.s("Focus ring", "focus ring accessibility label"))
        setAccessibilityHidden(true)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override func layout() {
        super.layout()
        updatePath()
    }

    func apply(_ focusRing: FocusRing) {
        self.focusRing = focusRing
        shapeLayer.strokeColor = focusRing.color.nsColor.cgColor
        shapeLayer.lineWidth = focusRing.width
        updatePath()
    }

    private func updatePath() {
        shapeLayer.frame = bounds
        let inset = focusRing.width / 2
        let rect = bounds.insetBy(dx: inset, dy: inset)
        shapeLayer.path = CGPath(
            roundedRect: rect,
            cornerWidth: focusRing.cornerRadius,
            cornerHeight: focusRing.cornerRadius,
            transform: nil
        )
    }
}

private extension FocusRingColor {
    var nsColor: NSColor {
        switch self {
        case .systemBlue:
            return .systemBlue
        case .systemGreen:
            return .systemGreen
        case .systemOrange:
            return .systemOrange
        case .systemPink:
            return .systemPink
        case .systemPurple:
            return .systemPurple
        case .systemRed:
            return .systemRed
        case .systemYellow:
            return .systemYellow
        case .white:
            return .labelColor
        }
    }
}
