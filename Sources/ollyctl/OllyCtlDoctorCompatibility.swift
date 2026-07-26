import ApplicationServices
import AppKit
import ollyKit

extension OllyDoctor {
    static func compatibilitySummary(_ status: AXPermissionStatus) -> DoctorCompatibilitySummary {
        let runningApps = NSWorkspace.shared.runningApplications
        let installed = commonApps.filter {
            NSWorkspace.shared.urlForApplication(withBundleIdentifier: $0.bundleID) != nil
        }
        let running = commonApps.filter { app in
            runningApps.contains { $0.bundleIdentifier == app.bundleID }
        }
        guard status == .trusted else {
            return DoctorCompatibilitySummary(
                installedCommonAppCount: installed.count,
                runningCommonAppCount: running.count,
                observedWindowCount: 0,
                inspectedAXWindows: false
            )
        }
        let windowCount = running.reduce(0) { count, app in
            let processID = runningApps.first { $0.bundleIdentifier == app.bundleID }?.processIdentifier
            guard let processID else {
                return count
            }
            return count + axWindowCount(processID: processID)
        }
        return DoctorCompatibilitySummary(
            installedCommonAppCount: installed.count,
            runningCommonAppCount: running.count,
            observedWindowCount: windowCount,
            inspectedAXWindows: true
        )
    }

    private static func axWindowCount(processID: pid_t) -> Int {
        let element = AXUIElementCreateApplication(processID)
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(element, kAXWindowsAttribute as CFString, &value)
        guard error == .success else {
            return 0
        }
        return (value as? [AXUIElement])?.count ?? 0
    }

    private static let commonApps = [
        DoctorApp(name: "Finder", bundleID: "com.apple.finder"),
        DoctorApp(name: "Safari", bundleID: "com.apple.Safari"),
        DoctorApp(name: "Chrome", bundleID: "com.google.Chrome"),
        DoctorApp(name: "Firefox", bundleID: "org.mozilla.firefox"),
        DoctorApp(name: "Xcode", bundleID: "com.apple.dt.Xcode"),
        DoctorApp(name: "Slack", bundleID: "com.tinyspeck.slackmacgap"),
        DoctorApp(name: "Zoom", bundleID: "us.zoom.xos"),
        DoctorApp(name: "Raycast", bundleID: "com.raycast.macos"),
        DoctorApp(name: "Alfred", bundleID: "com.runningwithcrayons.Alfred")
    ]
}

private struct DoctorApp {
    let name: String
    let bundleID: String
}
