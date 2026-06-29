import ApplicationServices
import CoreGraphics
import Foundation
import ollyCore
import XCTest
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts
@testable import ollyRuntime

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

    func testMoveWindowReordersFocusedWindowByLinearDirection() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300)),
                (3, 2, CGRect(x: 600, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(2)

            let response = try send(.moveWindow(.init(direction: .right, displayID: displayID)), to: socketPath)
            XCTAssertEqual(response.status, .success)

            let snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.windows.map(\.windowID), [1, 3, 2])
            XCTAssertEqual(snapshot.windows.map(\.layoutOrder), [0, 1, 2])
            let persisted = try await runtime.persistedState()
            XCTAssertEqual(persisted.layoutOrders.map(\.layoutOrder), [0, 1, 2])
        }
    }

    func testAXFocusEventUpdatesFocusedWindowFromSnapshot() async throws {
        let element = AXUIElementCreateApplication(9876)
        let snapshotCache = WindowSnapshotCache { _, _ in
            WindowAttributes(
                title: "Docs",
                role: "AXWindow",
                subrole: "AXStandardWindow",
                frame: CGRect(x: 0, y: 0, width: 300, height: 300),
                processID: 9876,
                windowID: 77
            )
        }

        try await withRuntime(snapshotCache: snapshotCache) { runtime, socketPath, displayID in
            await runtime.handle(axEvent: AXNotificationEvent(
                processID: 9876,
                element: element,
                notification: .focusedWindowChanged,
                rawNotificationName: AXNotification.focusedWindowChanged.rawValue
            ))

            let snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.focusedWindowID, 77)
            XCTAssertEqual(snapshot.windows.map(\.windowID), [77])
        }
    }

    func testSwapWindowUsesSpatialDirectionalTarget() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300)),
                (3, 2, CGRect(x: 0, y: 300, width: 300, height: 300)),
                (4, 3, CGRect(x: 300, y: 300, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(.swap(.init(direction: .downward, displayID: displayID)), to: socketPath)
            XCTAssertEqual(response.status, .success)

            let snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.windows.map(\.windowID), [3, 2, 1, 4])
            XCTAssertEqual(snapshot.windows.map(\.layoutOrder), [0, 1, 2, 3])
        }
    }

    func testMoveWindowAtEdgeReturnsStructuredDirectionalError() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(.moveWindow(.init(direction: .left, displayID: displayID)), to: socketPath)
            XCTAssertEqual(response.status, .error)
            XCTAssertEqual(response.error?.code, "missing_directional_target")
        }
    }

    func testSpatialTargetPrefersPerpendicularOverlapBeforeNearestCenter() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 200, y: 200, width: 100, height: 100)),
                (2, 1, CGRect(x: 100, y: 450, width: 100, height: 100)),
                (3, 2, CGRect(x: 80, y: 210, width: 100, height: 80))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(.swap(.init(direction: .left, displayID: displayID)), to: socketPath)
            XCTAssertEqual(response.status, .success)

            let snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.windows.map(\.windowID), [3, 2, 1])
        }
    }

    func testSpatialTargetIgnoresHiddenLayoutPlacements() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 0, y: 300, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)
            XCTAssertEqual(
                try send(.setEngine(.init(engineID: MonocleLayoutEngine.engineID, displayID: displayID)), to: socketPath).status,
                .success
            )

            let response = try send(.swap(.init(direction: .downward, displayID: displayID)), to: socketPath)

            XCTAssertEqual(response.status, .error)
            XCTAssertEqual(response.error?.code, "missing_directional_target")
        }
    }
}

private func withRuntime(
    snapshotCache: WindowSnapshotCache = WindowSnapshotCache(),
    _ body: (OllyRuntime, IPCSocketPath, DisplayID) async throws -> Void
) async throws {
    let fixture = try RuntimeFixture()
    let runtime = fixture.makeRuntime(snapshotCache: snapshotCache)
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

private func seedWindows(
    _ runtime: OllyRuntime,
    displayID: DisplayID,
    windows: [(WindowID, Int, CGRect)]
) async {
    for (id, layoutOrder, frame) in windows {
        await runtime.upsertRuntimeWindow(
            WindowState(
                id: id,
                processID: 42,
                displayID: displayID,
                tagMask: 1,
                isFloating: false,
                layoutOrder: layoutOrder,
                frame: frame,
                title: "window \(id)",
                role: "AXWindow",
                subrole: "AXStandardWindow"
            ),
            element: nil
        )
    }
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

    func makeRuntime(snapshotCache: WindowSnapshotCache = WindowSnapshotCache()) -> OllyRuntime {
        OllyRuntime(
            socketPath: socketPath,
            configLoader: ConfigLoader(
                sourceURL: directoryURL.appendingPathComponent("missing.swift"),
                cacheDirectory: directoryURL.appendingPathComponent("cache", isDirectory: true)
            ),
            displayProvider: { [display] in [display] },
            snapshotCache: snapshotCache,
            statePersistence: WindowTagPersistence(
                stateURL: directoryURL.appendingPathComponent("state.json")
            ),
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
