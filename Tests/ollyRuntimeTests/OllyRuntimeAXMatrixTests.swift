import ApplicationServices
import AppKit
import Foundation
import XCTest
import ollyKit

final class OllyRuntimeAXMatrixTests: XCTestCase {
    func testCommonApplicationAXMatrixWhenOptedIn() throws {
        guard ProcessInfo.processInfo.environment["OLLY_RUN_AX_MATRIX"] == "1" else {
            throw XCTSkip("Set OLLY_RUN_AX_MATRIX=1 to run the real-app AX matrix.")
        }
        guard AXPermission.isTrusted else {
            throw XCTSkip("Accessibility permission is required for the AX matrix.")
        }

        let report = AXMatrixReport(
            generatedAt: ISO8601DateFormatter().string(from: Date()),
            applications: Self.applications.map(reportApplication)
        )
        try write(report)
        XCTAssertFalse(report.applications.isEmpty)
    }

    private func reportApplication(_ application: AXMatrixApplication) -> AXMatrixApplicationResult {
        let installedURL = NSWorkspace.shared.urlForApplication(withBundleIdentifier: application.bundleID)
        let running = NSWorkspace.shared.runningApplications.first {
            $0.bundleIdentifier == application.bundleID
        }
        guard let running, running.processIdentifier > 0 else {
            return AXMatrixApplicationResult(
                name: application.name,
                bundleID: application.bundleID,
                installed: installedURL != nil,
                running: false,
                windowCount: 0,
                status: installedURL == nil ? "not_installed" : "not_running"
            )
        }

        let element = AXUIElementCreateApplication(running.processIdentifier)
        let windows = axWindows(for: element)
        return AXMatrixApplicationResult(
            name: application.name,
            bundleID: application.bundleID,
            installed: true,
            running: true,
            windowCount: windows.count,
            status: "observed"
        )
    }

    private func axWindows(for applicationElement: AXUIElement) -> [AXUIElement] {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(applicationElement, kAXWindowsAttribute as CFString, &value)
        guard error == .success else {
            return []
        }
        return value as? [AXUIElement] ?? []
    }

    private func write(_ report: AXMatrixReport) throws {
        let path = ProcessInfo.processInfo.environment["OLLY_AX_MATRIX_REPORT"]
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("olly-ax-matrix.json").path
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(report).write(to: URL(fileURLWithPath: path), options: [.atomic])
    }

    private static let applications = [
        AXMatrixApplication(name: "TextEdit", bundleID: "com.apple.TextEdit"),
        AXMatrixApplication(name: "Finder", bundleID: "com.apple.finder"),
        AXMatrixApplication(name: "Terminal", bundleID: "com.apple.Terminal"),
        AXMatrixApplication(name: "Safari", bundleID: "com.apple.Safari"),
        AXMatrixApplication(name: "Google Chrome", bundleID: "com.google.Chrome"),
        AXMatrixApplication(name: "Firefox", bundleID: "org.mozilla.firefox"),
        AXMatrixApplication(name: "Visual Studio Code", bundleID: "com.microsoft.VSCode"),
        AXMatrixApplication(name: "Xcode", bundleID: "com.apple.dt.Xcode"),
        AXMatrixApplication(name: "Slack", bundleID: "com.tinyspeck.slackmacgap"),
        AXMatrixApplication(name: "Zoom", bundleID: "us.zoom.xos")
    ]
}

private struct AXMatrixApplication {
    let name: String
    let bundleID: String
}

private struct AXMatrixReport: Encodable {
    let generatedAt: String
    let applications: [AXMatrixApplicationResult]
}

private struct AXMatrixApplicationResult: Encodable {
    let name: String
    let bundleID: String
    let installed: Bool
    let running: Bool
    let windowCount: Int
    let status: String
}
