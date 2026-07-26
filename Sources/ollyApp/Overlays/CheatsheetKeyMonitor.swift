import AppKit

final class CheatsheetKeyMonitor {
    private let isVisible: () -> Bool
    private let onAction: (CheatsheetKeyAction) -> Void
    private var localMonitor: Any?
    private var globalMonitor: Any?

    init(isVisible: @escaping () -> Bool, onAction: @escaping (CheatsheetKeyAction) -> Void) {
        self.isVisible = isVisible
        self.onAction = onAction
    }

    func install() {
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else {
                return event
            }
            let action = CheatsheetKeyAction.action(for: event, isVisible: isVisible())
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
            let action = CheatsheetKeyAction.action(for: event, isVisible: isVisible())
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
