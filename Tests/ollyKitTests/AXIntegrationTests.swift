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
        var window = try await waitForWindowRef(application.axElement)
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

    private func waitForWindowRef(_ applicationElement: AXUIElement) async throws -> ollyKit.WindowRef {
        var sawWindow = false
        for _ in 0..<50 {
            let windows = axWindows(for: applicationElement)
            sawWindow = sawWindow || !windows.isEmpty
            for window in windows {
                if let windowRef = try? ollyKit.WindowRef(axElement: window, lookupOptions: .publicOnly) {
                    return windowRef
                }
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        if sawWindow {
            throw XCTSkip("TextEdit did not expose a framed AX window")
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
