import CoreGraphics
import Foundation
import XCTest
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
@testable import ollyRuntime

final class OllyRuntimeScratchpadTests: XCTestCase {
    func testScratchpadToggleHidesAndShowsWithoutAX() async throws {
        let fixture = try ScratchpadRuntimeFixture()
        let moves = ScratchpadMoveRecorder()
        let focus = ScratchpadFocusRecorder()
        let runtime = fixture.makeRuntime(moveRecorder: moves, focusRecorder: focus)
        defer { fixture.cleanup() }
        do {
            try await runtime.start()
            try await runtime.upsertRuntimeWindow(fixture.window(id: 10), element: nil)
            XCTAssertEqual(
                try fixture.send(.scratchpadAdd(.init(name: "term", bundleID: "com.apple.Terminal"))).status,
                .success
            )

            XCTAssertEqual(try fixture.send(.scratchpadToggle(.init(name: "term"))).status, .success)
            XCTAssertEqual(try fixture.send(.scratchpadToggle(.init(name: "term"))).status, .success)

            let recorded = await moves.frames
            XCTAssertEqual(recorded.count, 2)
            XCTAssertLessThan(recorded[0].origin.x, 0)
            XCTAssertEqual(recorded[1], fixture.visibleFrame)
            let focusedWindowIDs = await focus.windowIDs
            XCTAssertEqual(focusedWindowIDs, [10])
            await runtime.stop()
        } catch {
            await runtime.stop()
            throw error
        }
    }

    func testScratchpadToggleLaunchesMissingBundle() async throws {
        let fixture = try ScratchpadRuntimeFixture()
        let launches = ScratchpadLaunchRecorder()
        let runtime = fixture.makeRuntime(launchRecorder: launches)
        defer { fixture.cleanup() }
        do {
            try await runtime.start()
            XCTAssertEqual(
                try fixture.send(.scratchpadAdd(.init(name: "term", bundleID: "com.apple.Terminal"))).status,
                .success
            )
            XCTAssertEqual(try fixture.send(.scratchpadToggle(.init(name: "term"))).status, .success)

            let launchedBundleIDs = await launches.bundleIDs
            XCTAssertEqual(launchedBundleIDs, ["com.apple.Terminal"])
            await runtime.stop()
        } catch {
            await runtime.stop()
            throw error
        }
    }
}

private struct ScratchpadRuntimeFixture {
    let directoryURL: URL
    let socketPath: IPCSocketPath
    let display: Display
    let visibleFrame = CGRect(x: 100, y: 100, width: 600, height: 400)

    init() throws {
        directoryURL = URL(fileURLWithPath: "/tmp", isDirectory: true)
            .appendingPathComponent("olly-sp-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        socketPath = IPCSocketPath(directoryURL.appendingPathComponent("olly.sock").path)
        display = Display(
            id: 1,
            frame: CGRect(x: 0, y: 0, width: 1200, height: 900),
            visibleFrame: CGRect(x: 0, y: 0, width: 1200, height: 860),
            scaleFactor: 2,
            localizedName: "Display",
            isMain: true
        )
    }

    func makeRuntime(
        launchRecorder: ScratchpadLaunchRecorder = ScratchpadLaunchRecorder(),
        moveRecorder: ScratchpadMoveRecorder = ScratchpadMoveRecorder(),
        focusRecorder: ScratchpadFocusRecorder = ScratchpadFocusRecorder()
    ) -> OllyRuntime {
        OllyRuntime(
            socketPath: socketPath,
            configLoader: ConfigLoader(
                sourceURL: directoryURL.appendingPathComponent("missing.swift"),
                cacheDirectory: directoryURL.appendingPathComponent("cache", isDirectory: true)
            ),
            displayProvider: { [display] },
            statePersistence: WindowTagPersistence(stateURL: directoryURL.appendingPathComponent("state.json")),
            recoveryJournal: WindowRecoveryJournal(stateURL: directoryURL.appendingPathComponent("recovery.json")),
            scratchpads: ScratchpadRegistry(stateURL: directoryURL.appendingPathComponent("scratchpads.json")),
            scanAXOnStart: false,
            displayChangeStream: { AsyncStream { $0.finish() } },
            activeSpaceWindowIDs: { nil },
            nativeSpaceChangeStream: { AsyncStream { $0.finish() } },
            scratchpadApplicationLauncher: { bundleID in await launchRecorder.record(bundleID) },
            scratchpadMoveWindow: { window, frame in await moveRecorder.record(windowID: window.id, frame: frame) },
            scratchpadFocusWindow: { window in await focusRecorder.record(windowID: window.id) }
        )
    }

    func send(_ command: IPCCommand) throws -> IPCResponseEnvelope {
        let request = try JSONEncoder().encode(IPCRequestEnvelope(command: command))
        let response = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).sendLine(request)
        return try JSONDecoder().decode(IPCResponseEnvelope.self, from: response)
    }

    func window(id: WindowID) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            bundleID: "com.apple.Terminal",
            displayID: display.id,
            tagMask: 1,
            frame: visibleFrame,
            title: "Scratch shell",
            role: "AXWindow"
        )
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: directoryURL)
    }
}

private actor ScratchpadMoveRecorder {
    private(set) var frames: [CGRect] = []

    func record(windowID: WindowID, frame: CGRect) {
        frames.append(frame)
    }
}

private actor ScratchpadFocusRecorder {
    private(set) var windowIDs: [WindowID] = []

    func record(windowID: WindowID) {
        windowIDs.append(windowID)
    }
}

private actor ScratchpadLaunchRecorder {
    private(set) var bundleIDs: [String] = []

    func record(_ bundleID: String) {
        bundleIDs.append(bundleID)
    }
}
