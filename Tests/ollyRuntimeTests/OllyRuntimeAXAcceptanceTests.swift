import AppKit
import CoreGraphics
import Foundation
import XCTest
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
@testable import ollyRuntime

final class OllyRuntimeAXAcceptanceTests: XCTestCase {
    func testRealAXFocusMoveAndSwapFlowWhenExplicitlyEnabled() async throws {
        guard ProcessInfo.processInfo.environment["OLLY_RUN_AX_ACCEPTANCE"] == "1" else {
            throw XCTSkip("set OLLY_RUN_AX_ACCEPTANCE=1 to run real AX acceptance")
        }
        guard AXPermission.isTrusted else {
            throw XCTSkip("Accessibility trust is required")
        }
        let fixture = try AXAcceptanceFixture()
        let launch = try await fixture.launchTextEditDocuments(count: 3)
        defer {
            launch.application.terminate()
            fixture.cleanup()
        }
        let runtime = fixture.makeRuntime()
        try await runtime.start()
        do {
            let windows = try await fixture.waitForRuntimeWindows(socketPath: fixture.socketPath, minimumCount: 3)
            let first = try XCTUnwrap(windows.first)
            await runtime.setFocusedWindow(first.windowID)

            XCTAssertEqual(try fixture.send(.focus(.init(direction: .next)), to: fixture.socketPath).status, .success)
            XCTAssertEqual(try fixture.send(.moveWindow(.init(direction: .next)), to: fixture.socketPath).status, .success)
            XCTAssertEqual(try fixture.send(.swap(.init(direction: .previous)), to: fixture.socketPath).status, .success)

            let snapshot = try fixture.state(socketPath: fixture.socketPath)
            XCTAssertNotNil(snapshot.focusedWindowID)
            XCTAssertGreaterThanOrEqual(snapshot.windows.count, 3)
            await runtime.stop()
        } catch {
            await runtime.stop()
            throw error
        }
    }
}

private struct AXAcceptanceFixture {
    let directoryURL: URL
    let socketPath: IPCSocketPath

    init() throws {
        directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("olly-ax-acceptance-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        socketPath = IPCSocketPath(directoryURL.appendingPathComponent("olly.sock").path)
    }

    func makeRuntime() -> OllyRuntime {
        OllyRuntime(
            socketPath: socketPath,
            configLoader: ConfigLoader(
                sourceURL: directoryURL.appendingPathComponent("missing.swift"),
                cacheDirectory: directoryURL.appendingPathComponent("cache", isDirectory: true)
            ),
            statePersistence: WindowTagPersistence(stateURL: directoryURL.appendingPathComponent("state.json")),
            scanAXOnStart: true
        )
    }

    func launchTextEditDocuments(count: Int) async throws -> TextEditLaunch {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.TextEdit") else {
            throw AXAcceptanceError.missingTextEdit
        }
        var documentURLs: [URL] = []
        for index in 0..<count {
            let documentURL = directoryURL.appendingPathComponent("acceptance-\(index).txt")
            try "olly ax acceptance \(index)\n".write(to: documentURL, atomically: true, encoding: .utf8)
            documentURLs.append(documentURL)
        }
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.activates = true
        return try await withCheckedThrowingContinuation { continuation in
            NSWorkspace.shared.open(documentURLs, withApplicationAt: url, configuration: configuration) { application, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let application {
                    continuation.resume(returning: TextEditLaunch(application: application))
                } else {
                    continuation.resume(throwing: AXAcceptanceError.launchFailed)
                }
            }
        }
    }

    func waitForRuntimeWindows(socketPath: IPCSocketPath, minimumCount: Int) async throws -> [IPCWindowState] {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let windows = try state(socketPath: socketPath).windows.filter {
                $0.bundleID == "com.apple.TextEdit"
            }
            if windows.count >= minimumCount {
                return windows
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw AXAcceptanceError.windowDiscoveryTimedOut
    }

    func send(_ command: IPCCommand, to socketPath: IPCSocketPath) throws -> IPCResponseEnvelope {
        let request = try JSONEncoder().encode(IPCRequestEnvelope(command: command))
        let response = try UnixDomainSocketClient(socketPath: socketPath, timeout: 2).sendLine(request)
        return try JSONDecoder().decode(IPCResponseEnvelope.self, from: response)
    }

    func state(socketPath: IPCSocketPath) throws -> IPCStateSnapshot {
        let response = try send(.state(.init()), to: socketPath)
        guard case let .state(snapshot)? = response.result else {
            throw AXAcceptanceError.unexpectedResponse
        }
        return snapshot
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: directoryURL)
    }
}

private struct TextEditLaunch {
    let application: NSRunningApplication
}

private enum AXAcceptanceError: Error {
    case launchFailed
    case missingTextEdit
    case unexpectedResponse
    case windowDiscoveryTimedOut
}
