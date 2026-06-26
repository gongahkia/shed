import ApplicationServices
import AppKit

public struct Application: Identifiable {
    public let processID: pid_t
    public let axElement: AXUIElement
    public let bundleIdentifier: String?
    public let localizedName: String?

    public var id: pid_t {
        processID
    }

    public init(processID: pid_t, bundleIdentifier: String? = nil, localizedName: String? = nil) {
        self.processID = processID
        self.axElement = AXUIElementCreateApplication(processID)
        self.bundleIdentifier = bundleIdentifier
        self.localizedName = localizedName
    }

    public init?(runningApplication: NSRunningApplication) {
        let processID = runningApplication.processIdentifier
        guard processID >= 0 else {
            return nil
        }

        self.init(
            processID: processID,
            bundleIdentifier: runningApplication.bundleIdentifier,
            localizedName: runningApplication.localizedName
        )
    }
}

extension Application: Equatable {
    public static func == (lhs: Application, rhs: Application) -> Bool {
        lhs.processID == rhs.processID
    }
}

extension Application: Hashable {
    public func hash(into hasher: inout Hasher) {
        hasher.combine(processID)
    }
}

public enum ApplicationEvent: Equatable {
    case launched(Application)
    case terminated(Application)
}

public final class ApplicationMonitor {
    private let workspace: NSWorkspace
    private let notificationCenter: NotificationCenter

    public init(
        workspace: NSWorkspace = .shared,
        notificationCenter: NotificationCenter = NSWorkspace.shared.notificationCenter
    ) {
        self.workspace = workspace
        self.notificationCenter = notificationCenter
    }

    public func runningApplications() -> [Application] {
        workspace.runningApplications.compactMap(Application.init(runningApplication:))
    }

    public func events() -> AsyncStream<ApplicationEvent> {
        AsyncStream { continuation in
            let launchObserver = notificationCenter.addObserver(
                forName: NSWorkspace.didLaunchApplicationNotification,
                object: nil,
                queue: nil
            ) { notification in
                if let application = Self.application(from: notification) {
                    continuation.yield(.launched(application))
                }
            }

            let terminateObserver = notificationCenter.addObserver(
                forName: NSWorkspace.didTerminateApplicationNotification,
                object: nil,
                queue: nil
            ) { notification in
                if let application = Self.application(from: notification) {
                    continuation.yield(.terminated(application))
                }
            }

            continuation.onTermination = { [notificationCenter] _ in
                notificationCenter.removeObserver(launchObserver)
                notificationCenter.removeObserver(terminateObserver)
            }
        }
    }

    static func application(from notification: Notification) -> Application? {
        guard let runningApplication = notification.userInfo?[NSWorkspace.applicationUserInfoKey]
            as? NSRunningApplication else {
            return nil
        }
        return Application(runningApplication: runningApplication)
    }
}
