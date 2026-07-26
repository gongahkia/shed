import AppKit
import CoreGraphics
import ollyKit

@MainActor
final class OverlayPanelHost {
    static let shared = OverlayPanelHost()

    private let notificationCenter: NotificationCenter
    private var panels: [DisplayID: OverlayPanel] = [:]
    private var screenObserver: NSObjectProtocol?

    init(notificationCenter: NotificationCenter = .default) {
        self.notificationCenter = notificationCenter
        screenObserver = notificationCenter.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.rebuildOverlays()
            }
        }
    }

    deinit {
        if let screenObserver {
            notificationCenter.removeObserver(screenObserver)
        }
    }

    var activeCount: Int {
        panels.count
    }

    var activePanels: [DisplayID: OverlayPanel] {
        panels
    }

    func panel(for displayID: DisplayID) -> OverlayPanel? {
        panels[displayID]
    }

    @discardableResult
    func rebuildOverlays(
        level: NSWindow.Level = .floating,
        clickThrough: Bool = true
    ) -> [DisplayID: OverlayPanel] {
        closeAll()
        for screen in NSScreen.screens {
            guard let displayID = DisplayMonitor.displayID(for: screen) else {
                continue
            }
            panels[displayID] = OverlayPanel(screen: screen, level: level, clickThrough: clickThrough)
        }
        return panels
    }

    func showAll() {
        if panels.isEmpty {
            rebuildOverlays()
        }
        for panel in panels.values {
            panel.orderFrontRegardless()
            Self.fade(panel, to: 1)
        }
    }

    func hideAll() {
        let panels = Array(panels.values)
        self.panels.removeAll()
        for panel in panels {
            Self.fade(panel, to: 0) {
                panel.close()
            }
        }
    }

    func closeAll() {
        let panels = self.panels
        self.panels.removeAll()
        for panel in panels.values {
            panel.orderOut(nil)
            panel.close()
        }
    }

    static func fade(
        _ window: NSWindow,
        to alpha: CGFloat,
        duration: TimeInterval = 0.12,
        completion: (() -> Void)? = nil
    ) {
        NSAnimationContext.runAnimationGroup { context in
            context.duration = duration
            window.animator().alphaValue = alpha
        } completionHandler: {
            completion?()
        }
    }
}
