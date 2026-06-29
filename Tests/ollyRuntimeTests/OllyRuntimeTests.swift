import CoreGraphics
import Foundation
import XCTest
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts
import ollyRuntime

final class OllyRuntimeTests: XCTestCase {
    func testRuntimeServesDefaultStateWhenConfigIsMissing() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            let response = try send(.state(.init()), to: socketPath)
            let snapshot = try stateSnapshot(from: response)
            let display = try XCTUnwrap(snapshot.displays.first)

            XCTAssertEqual(display.displayID, displayID)
            XCTAssertEqual(display.activeTags.map(\.rawValue), [0])
            XCTAssertEqual(display.tagEngines.map(\.engineID), [FloatingLayoutEngine.engineID])
            XCTAssertTrue(snapshot.windows.isEmpty)
            XCTAssertNil(snapshot.focusedWindowID)

            let menu = await runtime.menuSnapshot()
            XCTAssertEqual(menu.displayID, displayID)
            XCTAssertEqual(menu.activeTags, [0])
            XCTAssertEqual(menu.currentEngineID, FloatingLayoutEngine.engineID)
            XCTAssertTrue(menu.isIPCServerRunning)
        }
    }

    func testSwitchTagUpdatesActiveTagState() async throws {
        try await withRuntime { _, socketPath, displayID in
            let switched = try send(.switchTag(.init(tag: tag(2))), to: socketPath)
            XCTAssertEqual(switched.status, .success)

            let response = try send(.state(.init(displayID: displayID)), to: socketPath)
            let display = try XCTUnwrap(try stateSnapshot(from: response).displays.first)
            XCTAssertEqual(display.activeTags.map(\.rawValue), [2])
        }
    }

    func testSetAndCycleEngineUpdateTagEngineBinding() async throws {
        try await withRuntime { _, socketPath, displayID in
            let set = try send(
                .setEngine(.init(engineID: MasterStackLayoutEngine.engineID, displayID: displayID)),
                to: socketPath
            )
            XCTAssertEqual(set.status, .success)

            var display = try XCTUnwrap(try stateSnapshot(from: send(.state(.init()), to: socketPath)).displays.first)
            XCTAssertEqual(display.tagEngines.first?.engineID, MasterStackLayoutEngine.engineID)

            let cycled = try send(.cycleEngine(.init(displayID: displayID)), to: socketPath)
            XCTAssertEqual(cycled.status, .success)

            display = try XCTUnwrap(try stateSnapshot(from: send(.state(.init()), to: socketPath)).displays.first)
            XCTAssertEqual(display.tagEngines.first?.engineID, FloatingLayoutEngine.engineID)
        }
    }

    func testSubscribeEventsReceivesEngineEventAfterArrangeCommand() async throws {
        try await withRuntime { _, socketPath, displayID in
            let stream = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).openLineStream()
            defer {
                stream.close()
            }
            try stream.sendLine(try JSONEncoder().encode(IPCRequestEnvelope(
                command: .subscribeEvents(.init(eventKinds: [.engine]))
            )))
            XCTAssertEqual(
                try JSONDecoder().decode(IPCResponseEnvelope.self, from: try stream.readLine()).status,
                .success
            )

            XCTAssertEqual(
                try send(.switchTag(.init(tag: tag(1), displayID: displayID)), to: socketPath).status,
                .success
            )

            let event = try JSONDecoder().decode(IPCEventEnvelope.self, from: try stream.readLine())
            guard case let .engine(.arranged(payload)) = event.event else {
                return XCTFail("expected engine arranged event")
            }
            XCTAssertEqual(payload.displayID, displayID)
            XCTAssertEqual(payload.engineID, FloatingLayoutEngine.engineID)
            XCTAssertEqual(payload.placementCount, 0)
        }
    }

    func testUnimplementedCommandsReturnStructuredErrors() async throws {
        try await withRuntime { _, socketPath, _ in
            let response = try send(.moveWindow(.init(direction: .left)), to: socketPath)
            XCTAssertEqual(response.status, .error)
            XCTAssertEqual(response.error?.code, "not_implemented")
        }
    }
}

private func withRuntime(
    _ body: (OllyRuntime, IPCSocketPath, DisplayID) async throws -> Void
) async throws {
    let fixture = try RuntimeFixture()
    let runtime = fixture.makeRuntime()
    do {
        try await runtime.start()
        try await body(runtime, fixture.socketPath, fixture.display.id)
        await runtime.stop()
        fixture.cleanup()
    } catch {
        await runtime.stop()
        fixture.cleanup()
        throw error
    }
}

private func send(_ command: IPCCommand, to socketPath: IPCSocketPath) throws -> IPCResponseEnvelope {
    let request = try JSONEncoder().encode(IPCRequestEnvelope(command: command))
    let response = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).sendLine(request)
    return try JSONDecoder().decode(IPCResponseEnvelope.self, from: response)
}

private func stateSnapshot(from response: IPCResponseEnvelope) throws -> IPCStateSnapshot {
    XCTAssertEqual(response.status, .success)
    guard case let .state(snapshot)? = response.result else {
        throw RuntimeTestError.unexpectedResponse
    }
    return snapshot
}

private func tag(_ value: Int) throws -> IPCTagIndex {
    try IPCTagIndex(validating: value)
}

private struct RuntimeFixture {
    let directoryURL: URL
    let socketPath: IPCSocketPath
    let display: Display

    init() throws {
        let id = String(UUID().uuidString.prefix(8))
        directoryURL = URL(fileURLWithPath: "/tmp", isDirectory: true)
            .appendingPathComponent("olly-runtime-\(id)", isDirectory: true)
        socketPath = IPCSocketPath(directoryURL.appendingPathComponent("olly.sock").path)
        display = Display(
            id: 42,
            frame: CGRect(x: 0, y: 0, width: 1440, height: 900),
            visibleFrame: CGRect(x: 0, y: 0, width: 1440, height: 860),
            scaleFactor: 2,
            localizedName: "Test Display",
            isMain: true
        )
    }

    func makeRuntime() -> OllyRuntime {
        OllyRuntime(
            socketPath: socketPath,
            configLoader: ConfigLoader(
                sourceURL: directoryURL.appendingPathComponent("missing.swift"),
                cacheDirectory: directoryURL.appendingPathComponent("cache", isDirectory: true)
            ),
            displayProvider: { [display] in [display] },
            scanAXOnStart: false
        )
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: directoryURL)
    }
}

private enum RuntimeTestError: Error {
    case unexpectedResponse
}
