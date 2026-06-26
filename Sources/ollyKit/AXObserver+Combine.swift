import ApplicationServices
import Foundation

public enum AXNotification: String, CaseIterable, Equatable, Sendable {
    case windowCreated = "AXWindowCreated"
    case uiElementDestroyed = "AXUIElementDestroyed"
    case focusedWindowChanged = "AXFocusedWindowChanged"
    case windowMoved = "AXWindowMoved"
    case windowResized = "AXWindowResized"
    case mainWindowChanged = "AXMainWindowChanged"
    case applicationActivated = "AXApplicationActivated"

    public init?(rawAXName: String) {
        self.init(rawValue: rawAXName)
    }

    public var axName: CFString {
        rawValue as CFString
    }
}

public struct AXNotificationEvent {
    public let processID: pid_t
    public let element: AXUIElement
    public let notification: AXNotification
    public let rawNotificationName: String
}

public enum AXObserverBridgeError: Error, Equatable {
    case observerCreateFailed(AXError)
    case addNotificationFailed(AXNotification, AXError)
    case streamContinuationUnavailable
}

public final class AXObserverBridge {
    private let processID: pid_t
    private let observedElement: AXUIElement
    private let notifications: [AXNotification]
    private var observer: AXObserver?
    private var registeredNotifications: [AXNotification] = []
    private var continuation: AsyncStream<AXNotificationEvent>.Continuation?

    public init(
        processID: pid_t,
        observedElement: AXUIElement,
        notifications: [AXNotification] = AXNotification.allCases
    ) {
        self.processID = processID
        self.observedElement = observedElement
        self.notifications = notifications
    }

    public convenience init(
        application: Application,
        notifications: [AXNotification] = AXNotification.allCases
    ) {
        self.init(
            processID: application.processID,
            observedElement: application.axElement,
            notifications: notifications
        )
    }

    deinit {
        stop(finishStream: false)
    }

    public func events() throws -> AsyncStream<AXNotificationEvent> {
        stop(finishStream: true)

        var capturedContinuation: AsyncStream<AXNotificationEvent>.Continuation?
        let stream = AsyncStream<AXNotificationEvent>(bufferingPolicy: .bufferingNewest(256)) { continuation in
            capturedContinuation = continuation
            continuation.onTermination = { [weak self] _ in
                self?.stop(finishStream: false)
            }
        }

        guard let capturedContinuation else {
            throw AXObserverBridgeError.streamContinuationUnavailable
        }

        continuation = capturedContinuation
        try start()
        return stream
    }

    public func stop() {
        stop(finishStream: true)
    }

    private func start() throws {
        var newObserver: AXObserver?
        let createError = AXSignpost.interval("ax.observer.create") {
            AXObserverCreate(processID, axObserverCallback, &newObserver)
        }
        guard createError == .success, let newObserver else {
            throw AXObserverBridgeError.observerCreateFailed(createError)
        }

        observer = newObserver
        let refcon = UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque())
        for notification in notifications {
            let error = AXSignpost.interval("ax.observer.addNotification") {
                AXObserverAddNotification(newObserver, observedElement, notification.axName, refcon)
            }
            guard error == .success else {
                stop(finishStream: true)
                throw AXObserverBridgeError.addNotificationFailed(notification, error)
            }
            registeredNotifications.append(notification)
        }

        CFRunLoopAddSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(newObserver), .commonModes)
    }

    private func stop(finishStream: Bool) {
        if let observer {
            for notification in registeredNotifications {
                AXSignpost.interval("ax.observer.removeNotification") {
                    AXObserverRemoveNotification(observer, observedElement, notification.axName)
                }
            }
            CFRunLoopRemoveSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(observer), .commonModes)
        }

        observer = nil
        registeredNotifications.removeAll()
        if finishStream {
            continuation?.finish()
        }
        continuation = nil
    }

    fileprivate func handle(element: AXUIElement, rawNotificationName: String) {
        guard let notification = AXNotification(rawAXName: rawNotificationName) else {
            return
        }

        continuation?.yield(
            AXNotificationEvent(
                processID: processID,
                element: element,
                notification: notification,
                rawNotificationName: rawNotificationName
            )
        )
    }
}

private let axObserverCallback: AXObserverCallback = { _, element, notification, refcon in
    guard let refcon else {
        return
    }

    let bridge = Unmanaged<AXObserverBridge>.fromOpaque(refcon).takeUnretainedValue()
    bridge.handle(element: element, rawNotificationName: notification as String)
}
