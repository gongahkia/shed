import AppKit
import Foundation
import OSLog
import UserNotifications
import ollyDSL

final class HotKeyStartupDiagnostics {
    private let loadConfig: () throws -> Config
    private let detector: HotKeyCollisionDetector
    private let notifier: HotKeyConflictNotifier
    private let store: HotKeyDiagnosticsStore
    private let logger = Logger(subsystem: "dev.olly.ollyApp", category: "HotKeys")

    convenience init(
        loader: ConfigLoader = HotKeyStartupDiagnostics.defaultLoader(),
        detector: HotKeyCollisionDetector = .live(),
        notifier: HotKeyConflictNotifier = UserNotificationHotKeyConflictNotifier(),
        store: HotKeyDiagnosticsStore = .shared
    ) {
        self.init(
            loadConfig: { try loader.load().config },
            detector: detector,
            notifier: notifier,
            store: store
        )
    }

    init(
        loadConfig: @escaping () throws -> Config,
        detector: HotKeyCollisionDetector = .live(),
        notifier: HotKeyConflictNotifier = UserNotificationHotKeyConflictNotifier(),
        store: HotKeyDiagnosticsStore = .shared
    ) {
        self.loadConfig = loadConfig
        self.detector = detector
        self.notifier = notifier
        self.store = store
    }

    func run() {
        do {
            let report = try scan()
            store.update(report)
            logSourceErrors(report.sourceErrors)
            logCollisions(report.collisions)
            notifier.notify(collisions: report.collisions)
        } catch ConfigLoaderError.missingSource {
            store.clear()
            logger.info("Skipping hotkey collision scan: no Config.swift")
        } catch {
            store.clear()
            logger.error("Skipping hotkey collision scan: \(String(describing: error), privacy: .public)")
        }
    }

    func scan() throws -> HotKeyCollisionReport {
        let config = try loadConfig()
        return detector.report(for: config.keybinds)
    }

    private static func defaultLoader() -> ConfigLoader {
        let modulesURL = Bundle.main.bundleURL
            .deletingLastPathComponent()
            .appendingPathComponent("Modules", isDirectory: true)
        return ConfigLoader(moduleSearchPaths: [modulesURL])
    }

    private func logSourceErrors(_ errors: [HotKeySourceError]) {
        for error in errors {
            logger.error(
                "Hotkey scan source failed: \(error.owner.rawValue, privacy: .public) \(error.detail, privacy: .public)"
            )
        }
    }

    private func logCollisions(_ collisions: [HotKeyCollision]) {
        for collision in collisions {
            logger.warning("Hotkey collision: \(collision.description, privacy: .public)")
        }
    }
}

protocol HotKeyConflictNotifier {
    func notify(collisions: [HotKeyCollision])
}

final class UserNotificationHotKeyConflictNotifier: HotKeyConflictNotifier {
    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func notify(collisions: [HotKeyCollision]) {
        for collision in collisions {
            deliver(collision)
        }
    }

    private func deliver(_ collision: HotKeyCollision) {
        center.getNotificationSettings { [center] settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                Self.add(collision, center: center)
            case .notDetermined:
                center.requestAuthorization(options: [.alert]) { granted, _ in
                    if granted {
                        Self.add(collision, center: center)
                    }
                }
            case .denied:
                break
            @unknown default:
                break
            }
        }
    }

    private static func add(_ collision: HotKeyCollision, center: UNUserNotificationCenter) {
        let content = UNMutableNotificationContent()
        content.title = L10n.s("Olly hotkey collision", "hotkey collision notification title")
        content.body = L10n.f(
            "%@ also belongs to %@",
            "hotkey collision notification body",
            collision.chord.description,
            collision.externalOwner.rawValue
        )
        let request = UNNotificationRequest(
            identifier: "dev.olly.hotkey.\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        center.add(request)
    }
}
