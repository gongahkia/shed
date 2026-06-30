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

    func testUnqualifiedSwitchTagTargetsFocusedWindowDisplay() async throws {
        let secondaryDisplay = Display(
            id: 77,
            frame: CGRect(x: 1440, y: 0, width: 1440, height: 900),
            visibleFrame: CGRect(x: 1440, y: 0, width: 1440, height: 860),
            scaleFactor: 2,
            localizedName: "Second Display",
            isMain: false
        )
        try await withRuntime(extraDisplays: [secondaryDisplay]) { runtime, socketPath, primaryDisplayID in
            try await seedWindows(runtime, displayID: secondaryDisplay.id, windows: [
                (1, 0, CGRect(x: 1440, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            XCTAssertEqual(try send(.switchTag(.init(tag: tag(2))), to: socketPath).status, .success)

            let displays = try stateSnapshot(from: send(.state(.init()), to: socketPath)).displays
            let primary = try XCTUnwrap(displays.first { $0.displayID == primaryDisplayID })
            let secondary = try XCTUnwrap(displays.first { $0.displayID == secondaryDisplay.id })
            XCTAssertEqual(primary.activeTags.map(\.rawValue), [0])
            XCTAssertEqual(secondary.activeTags.map(\.rawValue), [2])
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

    func testRuntimeRejectsNonFloatingWindowEngineOverride() async throws {
        try await withRuntime { runtime, _, displayID in
            await runtime.replaceConfigForTest(Config {
                Rules {
                    Rule(
                        match: RuleMatch(bundleID: "com.example.App"),
                        apply: RuleApply(engine: .bsp)
                    )
                }
            })

            do {
                try await runtime.upsertRuntimeWindow(
                    WindowState(
                        id: 1,
                        processID: 42,
                        bundleID: "com.example.App",
                        displayID: displayID,
                        tagMask: 1,
                        frame: .zero
                    ),
                    element: nil
                )
                XCTFail("expected unsupported engine command")
            } catch let error as OllyRuntimeError {
                guard case let .unsupportedEngineCommand(command, engineID) = error else {
                    XCTFail("expected unsupported engine command")
                    return
                }
                XCTAssertEqual(command, "rule-engine-override")
                XCTAssertEqual(engineID, BSPLayoutEngine.engineID)
            }
        }
    }

    func testCycleEngineCanTargetExplicitTag() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Engines {
                    EngineDeclaration.floating
                    EngineDeclaration.masterStack
                }
            })
            await runtime.initializeDisplays()

            XCTAssertEqual(try send(.tagAdd(.init(tag: tag(1), displayID: displayID)), to: socketPath).status, .success)
            XCTAssertEqual(
                try send(.cycleEngine(.init(tag: tag(1), displayID: displayID)), to: socketPath).status,
                .success
            )

            let display = try XCTUnwrap(try stateSnapshot(from: send(.state(.init()), to: socketPath)).displays.first)
            XCTAssertEqual(display.tagEngines.first { $0.tag.rawValue == 0 }?.engineID, FloatingLayoutEngine.engineID)
            XCTAssertEqual(display.tagEngines.first { $0.tag.rawValue == 1 }?.engineID, MasterStackLayoutEngine.engineID)
        }
    }

    func testDisplayWorkspaceInitialTagsInitializePerDisplay() async throws {
        let secondDisplay = Display(
            id: 77,
            frame: CGRect(x: 1440, y: 0, width: 1440, height: 900),
            visibleFrame: CGRect(x: 1440, y: 0, width: 1440, height: 860),
            scaleFactor: 2,
            localizedName: "Second Display",
            isMain: false
        )
        let fixture = try RuntimeFixture(extraDisplays: [secondDisplay])
        let runtime = fixture.makeRuntime()
        let config = Config {
            Workspaces {
                display(fixture.display.id) {
                    Tag.named("web")
                }
                display(secondDisplay.id) {
                    Tag.named("chat")
                }
            }
        }

        do {
            try await runtime.start()
            await runtime.replaceConfigForTest(config)
            await runtime.initializeDisplays()

            let displays = try stateSnapshot(from: send(.state(.init()), to: fixture.socketPath)).displays
            let primary = try XCTUnwrap(displays.first { $0.displayID == fixture.display.id })
            let secondary = try XCTUnwrap(displays.first { $0.displayID == secondDisplay.id })
            XCTAssertEqual(primary.activeTags.map(\.rawValue), [0])
            XCTAssertEqual(secondary.activeTags.map(\.rawValue), [1])
            await runtime.stop()
            fixture.cleanup()
        } catch {
            await runtime.stop()
            fixture.cleanup()
            throw error
        }
    }

    func testDisplayWorkspaceEngineBindingsInitializePerDisplay() async throws {
        let secondDisplay = Display(
            id: 77,
            frame: CGRect(x: 1440, y: 0, width: 1440, height: 900),
            visibleFrame: CGRect(x: 1440, y: 0, width: 1440, height: 860),
            scaleFactor: 2,
            localizedName: "Second Display",
            isMain: false
        )
        let fixture = try RuntimeFixture(extraDisplays: [secondDisplay])
        let runtime = fixture.makeRuntime()
        let config = Config {
            Engines {
                EngineDeclaration.floating
                EngineDeclaration.bsp
                EngineDeclaration.tabbed
            }
            Workspaces {
                display(fixture.display.id) {
                    Tag.named("web").engine(.bsp)
                }
                display(secondDisplay.id) {
                    Tag.named("web").engine(.tabbed)
                }
            }
        }

        do {
            try await runtime.start()
            await runtime.replaceConfigForTest(config)
            await runtime.initializeDisplays()

            let displays = try stateSnapshot(from: send(.state(.init()), to: fixture.socketPath)).displays
            let primary = try XCTUnwrap(displays.first { $0.displayID == fixture.display.id })
            let secondary = try XCTUnwrap(displays.first { $0.displayID == secondDisplay.id })

            XCTAssertEqual(primary.tagEngines.first { $0.tag.rawValue == 0 }?.engineID, BSPLayoutEngine.engineID)
            XCTAssertEqual(secondary.tagEngines.first { $0.tag.rawValue == 0 }?.engineID, TabbedLayoutEngine.engineID)
            await runtime.stop()
            fixture.cleanup()
        } catch {
            await runtime.stop()
            fixture.cleanup()
            throw error
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

    func testV1EventSubscriptionFiltersV2OnlyEventKinds() async throws {
        try await withRuntime { _, socketPath, _ in
            let stream = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).openLineStream()
            defer {
                stream.close()
            }
            try stream.sendLine(try JSONEncoder().encode(IPCRequestEnvelope(
                version: 1,
                command: .subscribeEvents(IPCSubscribeEventsCommand(eventKinds: [.focus, .space, .config]))
            )))

            let response = try JSONDecoder().decode(IPCResponseEnvelope.self, from: try stream.readLine())
            guard case let .subscribed(info)? = response.result else {
                return XCTFail("expected subscription response")
            }
            XCTAssertEqual(info.eventKinds, [.focus])
        }
    }

    func testReservedV2CommandReturnsUnknownCommand() async throws {
        try await withRuntime { _, socketPath, _ in
            let response = try send(.reserved(IPCReservedCommand(name: .scratchpadList)), to: socketPath)

            XCTAssertEqual(response.status, .error)
            XCTAssertEqual(response.error?.code, "unknown_command")
        }
    }

    func testAXPermissionChangePublishesEventAndRunsHook() async throws {
        let recorder = PermissionRecorder()
        try await withRuntime { runtime, socketPath, _ in
            await runtime.replaceConfigForTest(Config {
                Hooks {
                    onAXPermissionChanged { context in
                        recorder.record(context.status.wireValue)
                    }
                }
            })
            await runtime.setAXPermissionStatusForTest(.trusted)
            let stream = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).openLineStream()
            defer {
                stream.close()
            }
            try stream.sendLine(try JSONEncoder().encode(IPCRequestEnvelope(
                command: .subscribeEvents(.init(eventKinds: [.axPermission]))
            )))
            XCTAssertEqual(
                try JSONDecoder().decode(IPCResponseEnvelope.self, from: try stream.readLine()).status,
                .success
            )

            await runtime.handleAXPermissionChange(.missing)

            let event = try JSONDecoder().decode(IPCEventEnvelope.self, from: try stream.readLine())
            XCTAssertEqual(event.event, .axPermission(IPCAXPermissionEvent(status: .missing)))
            XCTAssertEqual(recorder.events, ["missing"])
        }
    }

    func testMoveWindowReordersFocusedWindowByLinearDirection() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
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

    func testAXFocusBurstPublishesFocusBlockedEvent() async throws {
        let element = AXUIElementCreateApplication(9876)
        let attributes = WindowAttributesScript([
            WindowAttributes(
                title: "Noisy 1",
                role: "AXWindow",
                subrole: "AXStandardWindow",
                frame: CGRect(x: 0, y: 0, width: 300, height: 300),
                processID: 9876,
                windowID: 77
            ),
            WindowAttributes(
                title: "Noisy 2",
                role: "AXWindow",
                subrole: "AXStandardWindow",
                frame: CGRect(x: 20, y: 20, width: 300, height: 300),
                processID: 9876,
                windowID: 88
            )
        ])
        let snapshotCache = WindowSnapshotCache { _, _ in try attributes.next() }
        let attribution = FocusInputAttribution()

        try await withRuntime(snapshotCache: snapshotCache, focusInputAttribution: attribution) { runtime, socketPath, _ in
            await runtime.registerApplicationForTest(processID: 9876, bundleID: "com.example.Noisy")
            let stream = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).openLineStream()
            defer {
                stream.close()
            }
            try stream.sendLine(try JSONEncoder().encode(IPCRequestEnvelope(
                command: .subscribeEvents(.init(eventKinds: [.focusBlocked]))
            )))
            XCTAssertEqual(
                try JSONDecoder().decode(IPCResponseEnvelope.self, from: try stream.readLine()).status,
                .success
            )

            for _ in 0..<2 {
                await runtime.handle(axEvent: AXNotificationEvent(
                    processID: 9876,
                    element: element,
                    notification: .focusedWindowChanged,
                    rawNotificationName: AXNotification.focusedWindowChanged.rawValue
                ))
            }

            let event = try JSONDecoder().decode(IPCEventEnvelope.self, from: try stream.readLine())
            XCTAssertEqual(event.event, .focusBlocked(IPCFocusBlockedEvent(
                processID: 9876,
                bundleID: "com.example.Noisy"
            )))
        }
    }

    func testAXFocusPolicyAllowlistBypassesFocusRateLimit() async throws {
        let element = AXUIElementCreateApplication(9876)
        let snapshotCache = WindowSnapshotCache { _, _ in
            WindowAttributes(
                title: "Terminal",
                role: "AXWindow",
                subrole: "AXStandardWindow",
                frame: CGRect(x: 0, y: 0, width: 300, height: 300),
                processID: 9876,
                windowID: 77
            )
        }

        try await withRuntime(snapshotCache: snapshotCache) { runtime, socketPath, displayID in
            await runtime.registerApplicationForTest(processID: 9876, bundleID: "com.apple.Terminal")
            await runtime.replaceConfigForTest(Config {
                FocusPolicy {
                    allowStealingFor("com.apple.Terminal")
                }
            })

            for _ in 0..<2 {
                await runtime.handle(axEvent: AXNotificationEvent(
                    processID: 9876,
                    element: element,
                    notification: .focusedWindowChanged,
                    rawNotificationName: AXNotification.focusedWindowChanged.rawValue
                ))
            }

            let snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.focusedWindowID, 77)
        }
    }

    func testAXWindowMovedFeedsDragSessionFromSnapshot() async throws {
        let element = AXUIElementCreateApplication(9876)
        let frame = CGRect(x: 40, y: 50, width: 320, height: 240)
        let snapshotCache = WindowSnapshotCache { _, _ in
            WindowAttributes(
                title: "Docs",
                role: "AXWindow",
                subrole: "AXStandardWindow",
                frame: frame,
                processID: 9876,
                windowID: 88
            )
        }
        let dragSession = AXDragSession(
            endDelayNanoseconds: 10_000_000_000,
            mouseProvider: { CGPoint(x: 7, y: 8) }
        )

        try await withRuntime(snapshotCache: snapshotCache, dragSession: dragSession) { runtime, _, _ in
            let stream = await dragSession.subscribe()
            var iterator = stream.makeAsyncIterator()

            await runtime.handle(axEvent: AXNotificationEvent(
                processID: 9876,
                element: element,
                notification: .windowMoved,
                rawNotificationName: AXNotification.windowMoved.rawValue
            ))

            let event = await iterator.next()
            XCTAssertEqual(event, .started(88, frame, CGPoint(x: 7, y: 8)))
            await dragSession.endActiveSession()
        }
    }

    func testWindowResizeFullscreenProbePublishesTransitions() async throws {
        let element = AXUIElementCreateApplication(9876)
        let subroles = SubroleScript([OllyRuntime.fullscreenWindowSubrole, "AXStandardWindow"])
        let snapshotCache = WindowSnapshotCache { _, _ in
            WindowAttributes(
                title: "Safari",
                role: "AXWindow",
                subrole: "AXStandardWindow",
                frame: CGRect(x: 0, y: 0, width: 1440, height: 860),
                processID: 9876,
                windowID: 77
            )
        }

        try await withRuntime(
            snapshotCache: snapshotCache,
            axSubroleReader: { _ in await subroles.next() },
            fullscreenDebounceNanoseconds: 1
        ) { runtime, socketPath, displayID in
            let stream = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).openLineStream()
            defer {
                stream.close()
            }
            try stream.sendLine(try JSONEncoder().encode(IPCRequestEnvelope(
                command: .subscribeEvents(.init(eventKinds: [.fullscreen]))
            )))
            XCTAssertEqual(
                try JSONDecoder().decode(IPCResponseEnvelope.self, from: try stream.readLine()).status,
                .success
            )

            await runtime.handle(axEvent: AXNotificationEvent(
                processID: 9876,
                element: element,
                notification: .windowResized,
                rawNotificationName: AXNotification.windowResized.rawValue
            ))
            var event = try JSONDecoder().decode(IPCEventEnvelope.self, from: try stream.readLine())
            XCTAssertEqual(event.event, .fullscreen(IPCFullscreenEvent(windowID: 77, didEnter: true)))
            var snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.isFullscreen, true)

            await runtime.handle(axEvent: AXNotificationEvent(
                processID: 9876,
                element: element,
                notification: .windowResized,
                rawNotificationName: AXNotification.windowResized.rawValue
            ))
            event = try JSONDecoder().decode(IPCEventEnvelope.self, from: try stream.readLine())
            XCTAssertEqual(event.event, .fullscreen(IPCFullscreenEvent(windowID: 77, didEnter: false)))
            snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.isFullscreen, false)
        }
    }

    func testNativeSpaceVerifierMarksAndReturnsOffSpaceWindow() async throws {
        let activeSpaceProbe = ActiveSpaceProbe([])
        try await withRuntime(activeSpaceWindowIDs: { activeSpaceProbe.current() }) { runtime, socketPath, displayID in
            await runtime.registerApplicationForTest(processID: 42)
            let eventStream = await runtime.runtimeEventBus.subscribe()
            var events = eventStream.makeAsyncIterator()
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300))
            ])

            await runtime.verifyNativeSpaces()

            var snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.isOffSpace, true)
            guard case let .space(marked)? = await events.next() else {
                XCTFail("expected space event")
                return
            }
            XCTAssertEqual(marked, IPCSpaceDriftEvent(
                windowID: 1,
                fromDisplayID: displayID,
                action: .markedOffSpace
            ))

            activeSpaceProbe.set([1])
            await runtime.verifyNativeSpaces()

            snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.isOffSpace, false)
            guard case let .space(returned)? = await events.next() else {
                XCTFail("expected return space event")
                return
            }
            XCTAssertEqual(returned, IPCSpaceDriftEvent(windowID: 1, fromDisplayID: displayID, action: .returned))
        }
    }

    func testSwapWindowUsesSpatialDirectionalTarget() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
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
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(.moveWindow(.init(direction: .left, displayID: displayID)), to: socketPath)
            XCTAssertEqual(response.status, .error)
            XCTAssertEqual(response.error?.code, "missing_directional_target")
        }
    }

    func testRestoreWindowsReportsSkippedTargetsFromRecoveryJournal() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300))
            ])

            let switched = try send(.switchTag(.init(tag: tag(1), displayID: displayID)), to: socketPath)
            XCTAssertEqual(switched.status, .success)
            let parkedIDs = try await runtime.recoveryState().entries.map(\.windowID)
            XCTAssertEqual(parkedIDs, [1, 2])

            let response = try send(.restoreWindows(.init()), to: socketPath)

            XCTAssertEqual(response.status, .success)
            guard case let .restoredWindows(info)? = response.result else {
                return XCTFail("expected restored-windows result")
            }
            XCTAssertEqual(info.restoredCount, 0)
            XCTAssertEqual(info.skippedCount, 2)
            XCTAssertEqual(info.failedCount, 0)
            let remainingIDs = try await runtime.recoveryState().entries.map(\.windowID)
            XCTAssertEqual(remainingIDs, [1, 2])
        }
    }

    func testListWindowsAndDisplaysReturnScopedState() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300))
            ])

            let windows = try stateSnapshot(from: send(
                .listWindows(.init(windowID: 2, displayID: displayID)),
                to: socketPath
            ))
            XCTAssertTrue(windows.displays.isEmpty)
            XCTAssertEqual(windows.windows.map(\.windowID), [2])

            let displays = try stateSnapshot(from: send(.listDisplays(.init(displayID: displayID)), to: socketPath))
            XCTAssertEqual(displays.displays.map(\.displayID), [displayID])
            XCTAssertTrue(displays.windows.isEmpty)
        }
    }

    func testToggleFloatingUpdatesFocusedWindow() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            XCTAssertEqual(try send(.toggleFloating(.init()), to: socketPath).status, .success)
            var snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.isFloating, true)

            let command = IPCFloatingCommand(windowID: 1, floating: false, displayID: displayID)
            XCTAssertEqual(try send(.toggleFloating(command), to: socketPath).status, .success)
            snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.isFloating, false)
        }
    }

    func testToggleStickyUpdatesFocusedWindow() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            XCTAssertEqual(try send(.toggleSticky(.init()), to: socketPath).status, .success)
            var snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.isSticky, true)

            let command = IPCStickyCommand(windowID: 1, sticky: false, displayID: displayID)
            XCTAssertEqual(try send(.toggleSticky(command), to: socketPath).status, .success)
            snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.isSticky, false)
        }
    }

    func testPinnedWindowTagMaskRewritesOnTagSwitch() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            XCTAssertEqual(try send(.togglePinned(.init(displayID: displayID)), to: socketPath).status, .success)
            XCTAssertEqual(try send(.switchTag(.init(tag: tag(2), displayID: displayID)), to: socketPath).status, .success)

            let snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            let window = try XCTUnwrap(snapshot.windows.first)
            XCTAssertEqual(window.tags.map(\.rawValue), [2])
            XCTAssertEqual(window.isPinned, true)
        }
    }

    func testMoveToDisplayUpdatesWindowDisplay() async throws {
        let secondaryDisplay = Display(
            id: 99,
            frame: CGRect(x: 1440, y: 0, width: 1200, height: 900),
            visibleFrame: CGRect(x: 1440, y: 0, width: 1200, height: 860),
            scaleFactor: 2,
            localizedName: "Secondary",
            isMain: false
        )
        try await withRuntime(extraDisplays: [secondaryDisplay]) { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(.moveToDisplay(.init(displayID: 99)), to: socketPath)

            XCTAssertEqual(response.status, .success)
            let snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.displayID, 99)
        }
    }

    func testMoveToDisplayDispatchesParkingForDestination() async throws {
        let secondaryDisplay = Display(
            id: 99,
            frame: CGRect(x: 1440, y: 0, width: 1200, height: 900),
            visibleFrame: CGRect(x: 1440, y: 0, width: 1200, height: 860),
            scaleFactor: 2,
            localizedName: "Secondary",
            isMain: false
        )
        try await withRuntime(extraDisplays: [secondaryDisplay]) { runtime, socketPath, displayID in
            let inactive = try Tag(index: 1)
            try await runtime.upsertRuntimeWindow(
                WindowState(
                    id: 1,
                    processID: 42,
                    displayID: displayID,
                    tagMask: TagSet(inactive).rawValue,
                    frame: CGRect(x: 0, y: 0, width: 300, height: 300)
                ),
                element: nil
            )
            await runtime.setFocusedWindow(1)

            XCTAssertEqual(try send(.moveToDisplay(.init(displayID: 99)), to: socketPath).status, .success)

            let parkedIDs = try await runtime.recoveryState().entries.map(\.windowID)
            XCTAssertEqual(parkedIDs, [1])
        }
    }

    func testSnapWindowUsesSafeLayoutBoundsAndFloatsWindow() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 100, y: 100, width: 320, height: 240))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(.snapWindow(.init(position: .rightHalf, displayID: displayID)), to: socketPath)

            XCTAssertEqual(response.status, .success)
            let snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            let window = try XCTUnwrap(snapshot.windows.first)
            XCTAssertEqual(window.isFloating, true)
            XCTAssertEqual(window.frame, IPCFrame(x: 720, y: 0, width: 720, height: 860))
        }
    }

    func testSnapWindowCanKeepWindowTiled() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 200, height: 100))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(
                .snapWindow(.init(position: .center, displayID: displayID, makeFloating: false)),
                to: socketPath
            )

            XCTAssertEqual(response.status, .success)
            let snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            let window = try XCTUnwrap(snapshot.windows.first)
            XCTAssertEqual(window.isFloating, false)
            XCTAssertEqual(window.frame, IPCFrame(x: 620, y: 380, width: 200, height: 100))
        }
    }

    func testDispatchGestureSwitchesTagsFromConfiguredGestures() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Workspaces {
                    Tag.named("code")
                    Tag.named("web")
                }
                Gestures {
                    fourFingerVertical(.switchTags)
                }
            })
            await runtime.initializeDisplays()

            let response = try send(
                .dispatchGesture(.init(trigger: .fourFingerVertical, motion: .downward, displayID: displayID)),
                to: socketPath
            )

            XCTAssertEqual(response.status, .success)
            let display = try XCTUnwrap(try stateSnapshot(from: send(.state(.init()), to: socketPath)).displays.first)
            XCTAssertEqual(display.activeTags.map(\.rawValue), [1])
        }
    }

    func testDispatchGestureScrollsColumnsByMovingRuntimeFocus() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Gestures {
                    fourFingerHorizontal(.scrollColumns)
                }
            })
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(
                .dispatchGesture(.init(trigger: .fourFingerHorizontal, motion: .right, displayID: displayID)),
                to: socketPath
            )

            XCTAssertEqual(response.status, .success)
            let snapshot = try stateSnapshot(from: send(.state(.init()), to: socketPath))
            XCTAssertEqual(snapshot.focusedWindowID, 2)
        }
    }

    func testDispatchGestureReturnsStructuredErrorForUnboundGesture() async throws {
        try await withRuntime { _, socketPath, displayID in
            let response = try send(
                .dispatchGesture(.init(trigger: .fourFingerHorizontal, motion: .left, displayID: displayID)),
                to: socketPath
            )

            XCTAssertEqual(response.status, .error)
            XCTAssertEqual(response.error?.code, "gesture_unbound")
        }
    }

    func testManualPreselectMutatesManualTree() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)
            XCTAssertEqual(
                try send(.setEngine(.init(engineID: ManualLayoutEngine.engineID, displayID: displayID)), to: socketPath).status,
                .success
            )

            let response = try send(.manualPreselect(.init(direction: .right, displayID: displayID)), to: socketPath)

            XCTAssertEqual(response.status, .success)
            let rawConfig = await runtime.configForTest(engineID: ManualLayoutEngine.engineID)
            let config = try XCTUnwrap(rawConfig as? ManualLayoutEngine.Config)
            let path = try XCTUnwrap(config.tree.path(to: 1))
            XCTAssertEqual(path, ManualContainerPath([0]))
            XCTAssertEqual(config.tree.root?.children.first?.preselect, .right)
        }
    }

    func testBSPTreeCommandMutatesBSPTree() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 300)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 300))
            ])
            await runtime.setFocusedWindow(1)
            XCTAssertEqual(
                try send(.setEngine(.init(engineID: BSPLayoutEngine.engineID, displayID: displayID)), to: socketPath).status,
                .success
            )

            let response = try send(.bspTree(.init(action: .flipAxis, displayID: displayID)), to: socketPath)

            XCTAssertEqual(response.status, .success)
            let rawConfig = await runtime.configForTest(engineID: BSPLayoutEngine.engineID)
            let config = try XCTUnwrap(rawConfig as? BSPLayoutEngine.Config)
            XCTAssertEqual(config.tree.root?.axis, .vertical)
        }
    }

    func testSpatialTargetPrefersPerpendicularOverlapBeforeNearestCenter() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            try await seedWindows(runtime, displayID: displayID, windows: [
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
            try await seedWindows(runtime, displayID: displayID, windows: [
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
    dragSession: AXDragSession = AXDragSession(),
    axSubroleReader: @escaping AXSubroleReader = OllyRuntime.defaultAXSubroleReader,
    activeSpaceWindowIDs: @escaping ActiveSpaceWindowIDProvider = OllyRuntime.defaultActiveSpaceWindowIDs,
    focusInputAttribution: FocusInputAttribution = FocusInputAttribution(),
    fullscreenDebounceNanoseconds: UInt64 = 100_000_000,
    extraDisplays: [Display] = [],
    _ body: (OllyRuntime, IPCSocketPath, DisplayID) async throws -> Void
) async throws {
    let fixture = try RuntimeFixture(extraDisplays: extraDisplays)
    let runtime = fixture.makeRuntime(
        snapshotCache: snapshotCache,
        dragSession: dragSession,
        axSubroleReader: axSubroleReader,
        activeSpaceWindowIDs: activeSpaceWindowIDs,
        focusInputAttribution: focusInputAttribution,
        fullscreenDebounceNanoseconds: fullscreenDebounceNanoseconds
    )
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

private extension OllyRuntime {
    func configForTest(engineID: LayoutEngineID) async -> Any? {
        await configStore.config(for: engineID)
    }

    func replaceConfigForTest(_ config: Config) async {
        await configStore.replace(with: config)
        nativeSpaceDriftPolicy = config.nativeSpace.driftPolicy
        focusPolicy = config.focusPolicy
        await focusRateLimiter.update(settings: FocusRateLimitSettings(
            maxEventsPerSecond: config.focusPolicy.maxEventsPerSecond,
            minHumanIntervalMilliseconds: config.focusPolicy.minHumanIntervalMilliseconds
        ))
    }

    func setAXPermissionStatusForTest(_ status: AXPermissionStatus?) {
        axPermissionStatus = status
    }

    func registerApplicationForTest(processID: pid_t, bundleID: String? = nil) {
        applicationsByProcessID[processID] = Application(processID: processID, bundleIdentifier: bundleID)
    }
}

private final class PermissionRecorder: @unchecked Sendable {
    private(set) var events: [String] = []

    func record(_ event: String) {
        events.append(event)
    }
}

private actor SubroleScript {
    private var values: [String?]

    init(_ values: [String?]) {
        self.values = values
    }

    func next() -> String? {
        values.isEmpty ? nil : values.removeFirst()
    }
}

private final class WindowAttributesScript: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [WindowAttributes]

    init(_ values: [WindowAttributes]) {
        self.values = values
    }

    func next() throws -> WindowAttributes {
        lock.lock()
        defer { lock.unlock() }
        guard !values.isEmpty else {
            throw RuntimeTestError.unexpectedResponse
        }
        return values.removeFirst()
    }
}

private final class ActiveSpaceProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var ids: Set<WindowID>?

    init(_ ids: Set<WindowID>?) {
        self.ids = ids
    }

    func current() -> Set<WindowID>? {
        lock.lock()
        defer { lock.unlock() }
        return ids
    }

    func set(_ ids: Set<WindowID>?) {
        lock.lock()
        self.ids = ids
        lock.unlock()
    }
}

private func seedWindows(
    _ runtime: OllyRuntime,
    displayID: DisplayID,
    windows: [(WindowID, Int, CGRect)]
) async throws {
    for (id, layoutOrder, frame) in windows {
        try await runtime.upsertRuntimeWindow(
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
    let displays: [Display]

    init(extraDisplays: [Display] = []) throws {
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
        displays = [display] + extraDisplays
    }

    func makeRuntime(
        snapshotCache: WindowSnapshotCache = WindowSnapshotCache(),
        dragSession: AXDragSession = AXDragSession(),
        axSubroleReader: @escaping AXSubroleReader = OllyRuntime.defaultAXSubroleReader,
        activeSpaceWindowIDs: @escaping ActiveSpaceWindowIDProvider = OllyRuntime.defaultActiveSpaceWindowIDs,
        focusInputAttribution: FocusInputAttribution = FocusInputAttribution(),
        fullscreenDebounceNanoseconds: UInt64 = 100_000_000
    ) -> OllyRuntime {
        OllyRuntime(
            socketPath: socketPath,
            configLoader: ConfigLoader(
                sourceURL: directoryURL.appendingPathComponent("missing.swift"),
                cacheDirectory: directoryURL.appendingPathComponent("cache", isDirectory: true)
            ),
            displayProvider: { [displays] in displays },
            snapshotCache: snapshotCache,
            statePersistence: WindowTagPersistence(
                stateURL: directoryURL.appendingPathComponent("state.json")
            ),
            recoveryJournal: WindowRecoveryJournal(
                stateURL: directoryURL.appendingPathComponent("recovery.json")
            ),
            scanAXOnStart: false,
            dragSession: dragSession,
            axPermissionStream: {
                AsyncStream { continuation in
                    continuation.finish()
                }
            },
            axSubroleReader: axSubroleReader,
            activeSpaceWindowIDs: activeSpaceWindowIDs,
            focusInputAttribution: focusInputAttribution,
            fullscreenDebounceNanoseconds: fullscreenDebounceNanoseconds,
            presentAXOnboarding: {}
        )
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: directoryURL)
    }
}

private enum RuntimeTestError: Error {
    case unexpectedResponse
}
