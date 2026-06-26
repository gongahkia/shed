import CoreGraphics
import Foundation

public struct ElectronFallbackWindow: Equatable, Sendable {
    public let windowID: WindowID
    public let processID: pid_t
    public let frame: CGRect
    public let title: String?
}

public struct ElectronWorkaround: Equatable, Sendable {
    public static let defaultBundleIDs: Set<String> = [
        "com.tinyspeck.slackmacgap",
        "com.hnc.Discord",
        "com.microsoft.VSCode",
        "com.microsoft.VSCodeInsiders",
        "com.github.GitHubClient",
        "com.postmanlabs.mac",
        "notion.id",
        "md.obsidian",
        "com.figma.Desktop",
        "org.whispersystems.signal-desktop",
        "com.microsoft.teams2"
    ]

    public let bundleIDs: Set<String>

    public init(bundleIDs: Set<String> = Self.defaultBundleIDs) {
        self.bundleIDs = bundleIDs
    }

    public func shouldUseFallback(bundleIdentifier: String?, axWindowCount: Int) -> Bool {
        guard axWindowCount == 0, let bundleIdentifier else {
            return false
        }
        return bundleIDs.contains(bundleIdentifier)
    }

    public func fallbackWindows(
        for application: Application,
        axWindowCount: Int
    ) -> [ElectronFallbackWindow] {
        guard shouldUseFallback(
            bundleIdentifier: application.bundleIdentifier,
            axWindowCount: axWindowCount
        ) else {
            return []
        }
        return Self.fallbackWindows(processID: application.processID)
    }

    static func fallbackWindows(processID: pid_t) -> [ElectronFallbackWindow] {
        guard let windowInfo = CGWindowListCopyWindowInfo(.optionAll, kCGNullWindowID) as? [[String: Any]] else {
            return []
        }
        return fallbackWindows(processID: processID, windowInfo: windowInfo)
    }

    static func fallbackWindows(
        processID: pid_t,
        windowInfo: [[String: Any]]
    ) -> [ElectronFallbackWindow] {
        windowInfo.compactMap { info in
            fallbackWindow(processID: processID, info: info)
        }.sorted { $0.windowID < $1.windowID }
    }

    private static func fallbackWindow(processID: pid_t, info: [String: Any]) -> ElectronFallbackWindow? {
        guard intValue(info[kCGWindowOwnerPID as String]) == Int(processID),
              intValue(info[kCGWindowLayer as String]) == 0,
              let windowNumber = intValue(info[kCGWindowNumber as String]),
              let bounds = info[kCGWindowBounds as String] as? [String: Any],
              let frame = CGRect(dictionaryRepresentation: bounds as CFDictionary),
              frame.width > 0,
              frame.height > 0 else {
            return nil
        }

        return ElectronFallbackWindow(
            windowID: WindowID(windowNumber),
            processID: processID,
            frame: frame,
            title: info[kCGWindowName as String] as? String
        )
    }

    private static func intValue(_ value: Any?) -> Int? {
        if let number = value as? NSNumber {
            return number.intValue
        }
        return value as? Int
    }
}
