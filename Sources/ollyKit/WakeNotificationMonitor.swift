import AppKit
import Foundation

public final class WakeNotificationMonitor {
    private let notificationCenter: NotificationCenter

    public init(notificationCenter: NotificationCenter = NSWorkspace.shared.notificationCenter) {
        self.notificationCenter = notificationCenter
    }

    public func wakes() -> AsyncStream<Void> {
        AsyncStream<Void>(bufferingPolicy: .bufferingNewest(8)) { continuation in
            let token = notificationCenter.addObserver(
                forName: NSWorkspace.didWakeNotification,
                object: nil,
                queue: nil
            ) { _ in
                continuation.yield(())
            }
            continuation.onTermination = { [notificationCenter] _ in
                notificationCenter.removeObserver(token)
            }
        }
    }
}
