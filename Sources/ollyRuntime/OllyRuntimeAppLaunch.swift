import AppKit
import Foundation
import ollyCore
import ollyDSL
import ollyKit

extension OllyRuntime {
    @MainActor public static func defaultTagApplicationLauncher(_ bundleID: String) async throws {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleID) else {
            throw OllyRuntimeError.applicationLaunchFailed(bundleID)
        }
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.activates = false
        configuration.addsToRecentItems = false
        configuration.createsNewApplicationInstance = false
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            NSWorkspace.shared.openApplication(at: url, configuration: configuration) { application, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if application != nil {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: OllyRuntimeError.applicationLaunchFailed(bundleID))
                }
            }
        }
    }

    func launchConfiguredApps(for tag: Tag, on displayID: DisplayID) async throws {
        let workspaces = await configStore.current().workspaces
        for bundleID in workspaces.launchBundleIDs(for: tag, on: displayID) {
            guard await windowStore.windows(forBundleID: bundleID).isEmpty else {
                continue
            }
            try await tagApplicationLauncher(bundleID)
        }
    }
}
