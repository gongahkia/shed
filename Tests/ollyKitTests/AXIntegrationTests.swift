import AppKit
import ApplicationServices
import CoreGraphics
import XCTest
@testable import ollyKit

final class AXIntegrationTests: XCTestCase {
    func testAXPermissionAsyncRefreshFlow() async {
        let status = AXPermission.status(prompt: false)
        let refreshed = await AXPermission.refresh()
        XCTAssertEqual(refreshed, status)
    }

    @MainActor
    func testTextEditWindowDiscoveryAndMoveResizeFidelityWhenAXTrusted() async throws {
        try skipUnlessAXTrusted()
        let textEdit = try await launchTextEditDocument()
        defer {
            _ = textEdit.application.terminate()
            try? FileManager.default.removeItem(at: textEdit.documentURL)
        }

        let application = try XCTUnwrap(Application(runningApplication: textEdit.application))
        let windows = try await waitForWindows(application.axElement)
        XCTAssertFalse(windows.isEmpty)
        let axWindow = try XCTUnwrap(windows.first)
        var window = try WindowRef(axElement: axWindow, lookupOptions: .publicOnly)
        let originalFrame = window.attributes.frame
        let targetFrame = CGRect(
            x: originalFrame.origin.x + 12,
            y: originalFrame.origin.y + 12,
            width: max(240, originalFrame.width - 24),
            height: max(180, originalFrame.height - 24)
        )
        let mover = WindowMover(frameDelayNanoseconds: 0, retryDelayNanoseconds: 0)

        await mover.setPosition(targetFrame.origin, for: window)
        await mover.setSize(targetFrame.size, for: window)
        await mover.flushNow()
        try await Task.sleep(nanoseconds: 200_000_000)
        try window.refresh(lookupOptions: .publicOnly)

        XCTAssertLessThanOrEqual(abs(window.attributes.frame.origin.x - targetFrame.origin.x), 2)
        XCTAssertLessThanOrEqual(abs(window.attributes.frame.origin.y - targetFrame.origin.y), 2)
        XCTAssertLessThanOrEqual(abs(window.attributes.frame.width - targetFrame.width), 2)
        XCTAssertLessThanOrEqual(abs(window.attributes.frame.height - targetFrame.height), 2)

        await mover.setPosition(originalFrame.origin, for: window)
        await mover.setSize(originalFrame.size, for: window)
        await mover.flushNow()
    }

    func testDisplayHotplugEventStreamCanBeCreated() {
        let monitor = DisplayMonitor()
        let stream = monitor.changes()
        _ = stream.makeAsyncIterator()
        XCTAssertNotNil(stream)
    }

    private func skipUnlessAXTrusted() throws {
        guard AXPermission.isTrusted else {
            throw XCTSkip("Accessibility permission not granted")
        }
    }

    @MainActor
    private func launchTextEditDocument() async throws -> TextEditLaunch {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.TextEdit") else {
            throw XCTSkip("TextEdit is unavailable")
        }

        let documentURL = FileManager.default.temporaryDirectory.appendingPathComponent(
            "olly-textedit-\(UUID().uuidString).txt"
        )
        try "olly ax integration\n".write(to: documentURL, atomically: true, encoding: .utf8)
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.activates = false
        return try await withCheckedThrowingContinuation { continuation in
            NSWorkspace.shared.open([documentURL], withApplicationAt: url, configuration: configuration) { application, error in
                if let application {
                    continuation.resume(returning: TextEditLaunch(application: application, documentURL: documentURL))
                } else if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(throwing: AXIntegrationTestError.textEditLaunchFailed)
                }
            }
        }
    }

    private func waitForWindows(_ applicationElement: AXUIElement) async throws -> [AXUIElement] {
        for _ in 0..<50 {
            let windows = axWindows(for: applicationElement)
            if !windows.isEmpty {
                return windows
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw AXIntegrationTestError.windowDiscoveryTimedOut
    }

    private func axWindows(for applicationElement: AXUIElement) -> [AXUIElement] {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(applicationElement, kAXWindowsAttribute as CFString, &value)
        guard error == .success else {
            return []
        }
        return value as? [AXUIElement] ?? []
    }
}

private struct TextEditLaunch {
    let application: NSRunningApplication
    let documentURL: URL
}

private enum AXIntegrationTestError: Error {
    case textEditLaunchFailed
    case windowDiscoveryTimedOut
}
