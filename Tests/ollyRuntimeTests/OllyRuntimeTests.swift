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

    func testSwitchTagKeepsDialogWindowVisibleAcrossTags() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            let inactive = try Tag(index: 1)
            try await runtime.upsertRuntimeWindow(
                WindowState(
                    id: 40,
                    processID: 42,
                    displayID: displayID,
                    tagMask: TagSet(inactive).rawValue,
                    frame: CGRect(x: 0, y: 0, width: 300, height: 200),
                    title: "confirm",
                    role: "AXWindow",
                    subrole: "AXDialog"
                ),
                element: nil
            )

            let switched = try send(.switchTag(.init(tag: tag(2), displayID: displayID)), to: socketPath)
            let parkedIDs = try await runtime.recoveryState().entries.map(\.windowID)
            let snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))

            XCTAssertEqual(switched.status, .success)
            XCTAssertTrue(parkedIDs.isEmpty)
            XCTAssertEqual(snapshot.windows.map(\.windowID), [40])
        }
    }

    func testSwitchTagLaunchesConfiguredAppWhenNoBundleWindowExists() async throws {
        let launches = ApplicationLaunchRecorder()
        try await withRuntime(tagApplicationLauncher: { bundleID in await launches.record(bundleID) }) { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Workspaces {
                    Tag.named("main")
                    Tag.named("code").launch("com.microsoft.VSCode")
                }
            })
            await runtime.initializeDisplays()

            let switched = try send(.switchTag(.init(tag: tag(1), displayID: displayID)), to: socketPath)
            let launchedBundleIDs = await launches.bundleIDs

            XCTAssertEqual(switched.status, .success)
            XCTAssertEqual(launchedBundleIDs, ["com.microsoft.VSCode"])
        }
    }

    func testSwitchTagSkipsConfiguredLaunchWhenBundleWindowExists() async throws {
        let launches = ApplicationLaunchRecorder()
        try await withRuntime(tagApplicationLauncher: { bundleID in await launches.record(bundleID) }) { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Workspaces {
                    Tag.named("main")
                    Tag.named("code").launch("com.microsoft.VSCode")
                }
            })
            await runtime.initializeDisplays()
            try await runtime.upsertRuntimeWindow(
                WindowState(
                    id: 10,
                    processID: 42,
                    bundleID: "com.microsoft.VSCode",
                    displayID: displayID,
                    tagMask: 1,
                    frame: CGRect(x: 0, y: 0, width: 100, height: 100)
                ),
                element: nil
            )

            let switched = try send(.switchTag(.init(tag: tag(1), displayID: displayID)), to: socketPath)
            let launchedBundleIDs = await launches.bundleIDs

            XCTAssertEqual(switched.status, .success)
            XCTAssertEqual(launchedBundleIDs, [])
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

    func testTagSwitchAndEngineChangeHooksRunFromCommands() async throws {
        let recorder = HookRecorder()
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Engines {
                    EngineDeclaration.floating
                    EngineDeclaration.masterStack
                }
                Hooks {
                    onTagSwitch { context in
                        recorder.record("tag:\(context.previousTags.rawValue)->\(context.activeTags.rawValue)")
                    }
                    onEngineChange { context in
                        let previous = context.previousEngineID?.rawValue ?? "nil"
                        recorder.record("engine:\(previous)->\(context.currentEngineID.rawValue)")
                    }
                }
            })
            await runtime.initializeDisplays()

            XCTAssertEqual(try send(.switchTag(.init(tag: tag(2), displayID: displayID)), to: socketPath).status, .success)
            XCTAssertEqual(
                try send(.setEngine(.init(engineID: MasterStackLayoutEngine.engineID, displayID: displayID)), to: socketPath).status,
                .success
            )

            XCTAssertEqual(recorder.events, ["tag:1->4", "engine:nil->master-stack"])
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

    func testCooperativeReserveSpaceInjectsSafeZoneReserve() async throws {
        try await withRuntime { runtime, _, displayID in
            await runtime.replaceConfigForTest(Config {
                CooperativeApps(mode: .replace) {
                    CooperativeApp("com.example.Bar", behavior: .floatAndReserveSpace)
                }
            })
            try await runtime.upsertRuntimeWindow(
                WindowState(
                    id: 1,
                    processID: 42,
                    bundleID: "com.example.Bar",
                    displayID: displayID,
                    tagMask: 1,
                    frame: CGRect(x: 0, y: 820, width: 1440, height: 80)
                ),
                element: nil
            )

            let result = await runtime.safeZones().result(for: testDisplay(displayID))

            XCTAssertEqual(result.layoutFrame, CGRect(x: 0, y: 0, width: 1440, height: 820))
            XCTAssertTrue(result.reserves.contains { $0.kind == .cooperativeApp })
        }
    }

    func testExplainWindowReturnsRuleTracesAndFinalApply() async throws {
        let first = Rule(
            match: RuleMatch(bundleID: "com.example.Other"),
            apply: RuleApply(engine: .floating)
        )
        let second = Rule(
            match: RuleMatch(bundleID: "com.example.App", titleRegex: "^Build", role: "AXWindow"),
            apply: RuleApply(engine: .floating, floating: false)
        )
        let third = Rule(match: subrole("AXDialog"), apply: RuleApply(floating: true))
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Rules {
                    first
                    second
                    third
                }
            })
            try await runtime.upsertRuntimeWindow(
                WindowState(
                    id: 1,
                    processID: 42,
                    bundleID: "com.example.App",
                    displayID: displayID,
                    tagMask: 1,
                    frame: CGRect(x: 0, y: 0, width: 200, height: 80),
                    title: "Build Log",
                    role: "AXWindow"
                ),
                element: nil
            )

            let response = try send(.explainWindow(.init(windowID: 1)), to: socketPath)

            guard case let .ruleExplanation(explanation)? = response.result else {
                return XCTFail("expected rule explanation")
            }
            XCTAssertEqual(explanation.windowID, 1)
            XCTAssertEqual(explanation.traces.map(\.ruleID), [first.id, second.id, third.id])
            XCTAssertEqual(explanation.traces.map(\.matched), [false, true, false])
            XCTAssertEqual(explanation.traces[0].bundleIDMatched, false)
            XCTAssertEqual(explanation.traces[1].titleMatched, true)
            XCTAssertEqual(explanation.traces[2].predicateMatched, false)
            XCTAssertEqual(explanation.finalApply.engineOverride, .floating)
            XCTAssertEqual(explanation.finalApply.floating, false)
        }
    }

    func testExplainRuleUsesFocusedWindowAndFiltersTrace() async throws {
        let rule = Rule(
            match: RuleMatch(bundleID: "com.example.App"),
            apply: RuleApply(engine: .floating, floating: false)
        )
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Rules {
                    rule
                }
            })
            try await runtime.upsertRuntimeWindow(
                WindowState(
                    id: 1,
                    processID: 42,
                    bundleID: "com.example.App",
                    displayID: displayID,
                    tagMask: 1,
                    frame: CGRect(x: 0, y: 0, width: 200, height: 80)
                ),
                element: nil
            )
            await runtime.setFocusedWindow(1)

            let response = try send(.explainRule(.init(ruleID: rule.id)), to: socketPath)

            guard case let .ruleExplanation(explanation)? = response.result else {
                return XCTFail("expected rule explanation")
            }
            XCTAssertEqual(explanation.ruleID, rule.id)
            XCTAssertEqual(explanation.traces.map(\.ruleID), [rule.id])
            XCTAssertEqual(explanation.traces.map(\.matched), [true])
        }
    }

    func testCooperativeHideOnSwitchSetsLayoutOrderAndLeavesVisibleWindowsEmpty() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                CooperativeApps(mode: .replace) {
                    CooperativeApp("com.example.Overlay", behavior: .floatAndHideOnSwitch)
                }
            })
            try await runtime.upsertRuntimeWindow(
                WindowState(
                    id: 1,
                    processID: 42,
                    bundleID: "com.example.Overlay",
                    displayID: displayID,
                    tagMask: 1,
                    frame: CGRect(x: 0, y: 0, width: 200, height: 80)
                ),
                element: nil
            )

            let layoutOrder = await runtime.windowStateForTest(1)?.layoutOrder
            XCTAssertEqual(layoutOrder, Int.max)
            XCTAssertEqual(try send(.switchTag(.init(tag: tag(1), displayID: displayID)), to: socketPath).status, .success)
            let visibleWindows = await runtime.visibleWindows(displayID: displayID)
            XCTAssertTrue(visibleWindows.isEmpty)
        }
    }

    func testListCooperativeAppsReturnsResolvedBehaviorsAndDetectedWindows() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                CooperativeApps(mode: .replace) {
                    CooperativeApp("com.example.Bar", behavior: .dockAware)
                }
            })
            try await runtime.upsertRuntimeWindow(
                WindowState(
                    id: 1,
                    processID: 42,
                    bundleID: "com.example.Bar",
                    displayID: displayID,
                    tagMask: 1,
                    frame: CGRect(x: 0, y: 820, width: 1440, height: 80)
                ),
                element: nil
            )

            let response = try send(.listCooperativeApps(.init()), to: socketPath)

            guard case let .cooperativeApps(info)? = response.result else {
                return XCTFail("expected cooperative apps info")
            }
            XCTAssertEqual(info.apps, [
                IPCCooperativeAppInfo(bundleID: "com.example.Bar", behavior: "dockAware", detectedWindowCount: 1)
            ])
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
            let response = try send(.reserved(IPCReservedCommand(name: .telemetryStatus)), to: socketPath)

            XCTAssertEqual(response.status, .error)
            XCTAssertEqual(response.error?.code, "unknown_command")
        }
    }

    func testMacroRecordStopPersistsCommandsAndReplayRunsThem() async throws {
        let fixture = try RuntimeFixture()
        let runtime = fixture.makeRuntime()
        do {
            try await runtime.start()
            let displayID = fixture.display.id

            XCTAssertEqual(
                try send(.macroStart(.init(name: "workflow1")), to: fixture.socketPath).status,
                .success
            )
            XCTAssertEqual(try send(.macroList(.init()), to: fixture.socketPath).status, .success)
            XCTAssertEqual(
                try send(.switchTag(.init(tag: tag(2), displayID: displayID)), to: fixture.socketPath).status,
                .success
            )

            let stopped = try send(.macroStop(.init()), to: fixture.socketPath)
            guard case let .macro(info)? = stopped.result else {
                return XCTFail("expected macro info")
            }
            XCTAssertEqual(info.name, "workflow1")
            XCTAssertEqual(info.commandCount, 1)

            let macroURL = fixture.directoryURL
                .appendingPathComponent("macros", isDirectory: true)
                .appendingPathComponent("workflow1.json")
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            let persisted = try decoder.decode(MacroRecording.self, from: Data(contentsOf: macroURL))
            XCTAssertEqual(persisted.commands, [.switchTag(.init(tag: try tag(2), displayID: displayID))])

            XCTAssertEqual(
                try send(.switchTag(.init(tag: tag(0), displayID: displayID)), to: fixture.socketPath).status,
                .success
            )
            XCTAssertEqual(
                try send(.macroRun(.init(name: "workflow1")), to: fixture.socketPath).status,
                .success
            )

            let display = try XCTUnwrap(
                try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: fixture.socketPath)).displays.first
            )
            XCTAssertEqual(display.activeTags.map(\.rawValue), [2])
            await runtime.stop()
            fixture.cleanup()
        } catch {
            await runtime.stop()
            fixture.cleanup()
            throw error
        }
    }

    func testMacroStartRejectsConcurrentRecording() async throws {
        try await withRuntime { _, socketPath, _ in
            XCTAssertEqual(try send(.macroStart(.init(name: "one")), to: socketPath).status, .success)

            let response = try send(.macroStart(.init(name: "two")), to: socketPath)

            XCTAssertEqual(response.status, .error)
            XCTAssertEqual(response.error?.code, "macro_already_recording")
        }
    }

    func testRunRawActionCommandEmitsRawActionEvent() async throws {
        try await withRuntime { runtime, socketPath, _ in
            await runtime.replaceConfigForTest(Config {
                Permissions {
                    shellExec(.allowAll)
                }
                Keybinds {
                    Keybind(KeyChord([.command], .b), do: .shell("printf ipc", label: "echo"))
                }
            })
            let stream = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).openLineStream()
            defer {
                stream.close()
            }
            try stream.sendLine(try JSONEncoder().encode(IPCRequestEnvelope(
                command: .subscribeEvents(.init(eventKinds: [.rawAction]))
            )))
            XCTAssertEqual(try JSONDecoder().decode(IPCResponseEnvelope.self, from: try stream.readLine()).status, .success)

            XCTAssertEqual(try send(.runRawAction(.init(label: "echo")), to: socketPath).status, .success)
            let event = try JSONDecoder().decode(IPCEventEnvelope.self, from: try stream.readLine())
            guard case let .rawAction(payload) = event.event else {
                return XCTFail("expected raw action event")
            }
            XCTAssertEqual(payload.label, "echo")
            XCTAssertEqual(payload.status, .completed)
            XCTAssertEqual(payload.exit, 0)
            XCTAssertEqual(payload.stdoutHead, "ipc")
        }
    }

    func testRawGestureLabelRunsShellAction() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Permissions {
                    shellExec(.allowAll)
                }
                Keybinds {
                    Keybind(KeyChord([.command], .b), do: .shell("printf gesture", label: "gesture.echo"))
                }
                Gestures {
                    fourFingerHorizontal(.action(.raw("gesture.echo")))
                }
            })
            let stream = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).openLineStream()
            defer {
                stream.close()
            }
            try stream.sendLine(try JSONEncoder().encode(IPCRequestEnvelope(
                command: .subscribeEvents(.init(eventKinds: [.rawAction]))
            )))
            XCTAssertEqual(try JSONDecoder().decode(IPCResponseEnvelope.self, from: try stream.readLine()).status, .success)

            XCTAssertEqual(
                try send(.dispatchGesture(.init(
                    trigger: .fourFingerHorizontal,
                    motion: .left,
                    displayID: displayID
                )), to: socketPath).status,
                .success
            )
            let event = try JSONDecoder().decode(IPCEventEnvelope.self, from: try stream.readLine())
            guard case let .rawAction(payload) = event.event else {
                return XCTFail("expected raw action event")
            }
            XCTAssertEqual(payload.label, "gesture.echo")
            XCTAssertEqual(payload.status, .completed)
            XCTAssertEqual(payload.exit, 0)
            XCTAssertEqual(payload.stdoutHead, "gesture")
        }
    }

    func testAXPermissionChangePublishesEventAndRunsHook() async throws {
        let recorder = HookRecorder()
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

    func testConfigReloadAndDisplayChangeHooksRun() async throws {
        let recorder = HookRecorder()
        try await withRuntime { runtime, _, displayID in
            await runtime.replaceConfigForTest(Config {
                Hooks {
                    onConfigReload { context in
                        recorder.record("reload:\(context.current.version.rawValue):\(context.sourceURL?.lastPathComponent ?? "-")")
                    }
                    onDisplayChange { context in
                        recorder.record("display:\(context.change.displayID)")
                    }
                }
            })

            try await runtime.reloadConfig()
            await runtime.replaceConfigForTest(Config {
                Hooks {
                    onDisplayChange { context in
                        recorder.record("display:\(context.change.displayID)")
                    }
                }
            })
            await runtime.handleDisplayChange(DisplayChange(
                displayID: displayID,
                flags: CGDisplayChangeSummaryFlags(),
                displays: []
            ))

            XCTAssertEqual(recorder.events, ["reload:v1:missing.swift", "display:\(displayID)"])
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

    func testFocusFollowsMouseFocusesWindowUnderCursor() async throws {
        let mouseMoves = MouseMoveProbe()
        let focusedTargets = FocusTargetRecorder()
        let candidates = [
            WindowUnderPointCandidate(
                windowID: 2,
                processID: 42,
                layer: 0,
                bounds: CGRect(x: 300, y: 0, width: 300, height: 200)
            )
        ]

        try await withRuntime(
            mouseMoveStream: { mouseMoves.stream() },
            windowUnderPointCandidates: { candidates },
            axWindowFocusSetter: { target in
                await focusedTargets.record(target.id)
                return .success
            }
        ) { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                FocusPolicy {
                    followsMouse(delay: 0.ms)
                }
            })
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 200)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 200))
            ])
            await runtime.setWindowTargetForTest(2, displayID: displayID)

            mouseMoves.send(CGPoint(x: 350, y: 50))
            try await waitForFocusedWindow(2, in: runtime)

            let snapshot = try stateSnapshot(from: send(.state(.init(displayID: displayID)), to: socketPath))
            let focusedIDs = await focusedTargets.ids
            XCTAssertEqual(snapshot.focusedWindowID, 2)
            XCTAssertEqual(focusedIDs, [2])
        }
    }

    func testWindowAppearedAndClosedHooksRunFromAXLifecycle() async throws {
        let recorder = HookRecorder()
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

        try await withRuntime(snapshotCache: snapshotCache) { runtime, _, _ in
            await runtime.replaceConfigForTest(Config {
                Hooks {
                    onWindowAppeared { context in
                        recorder.record("appeared:\(context.window.id)")
                    }
                    onWindowClosed { context in
                        recorder.record("closed:\(context.window.id)")
                    }
                }
            })
            await runtime.handle(axEvent: AXNotificationEvent(
                processID: 9876,
                element: element,
                notification: .windowCreated,
                rawNotificationName: AXNotification.windowCreated.rawValue
            ))
            await runtime.handle(applicationEvent: .terminated(Application(processID: 9876)))

            XCTAssertEqual(recorder.events, ["appeared:77", "closed:77"])
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
        let recorder = HookRecorder()
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
            await runtime.replaceConfigForTest(Config {
                Hooks {
                    onFullscreenEnter { context in
                        recorder.record("fullscreen:\(context.window.id):\(context.didEnter)")
                    }
                    onFullscreenExit { context in
                        recorder.record("fullscreen:\(context.window.id):\(context.didEnter)")
                    }
                }
            })
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
            XCTAssertEqual(recorder.events, ["fullscreen:77:true", "fullscreen:77:false"])
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

    func testRestoreOnLaunchRestoresJournaledTargetsWhenEnabled() async throws {
        try await withRuntime { runtime, _, displayID in
            let window = WindowState(
                id: 90,
                processID: getpid(),
                displayID: displayID,
                tagMask: 1,
                isFloating: false,
                layoutOrder: 0,
                frame: CGRect(x: 10, y: 20, width: 300, height: 200),
                title: "journaled",
                role: "AXWindow",
                subrole: "AXStandardWindow"
            )
            try await runtime.upsertRuntimeWindow(
                window,
                element: AXUIElementCreateApplication(getpid())
            )
            try await runtime.recoveryJournal.record(
                window: window,
                parkedFrame: CGRect(x: -10_000, y: -10_000, width: 300, height: 200)
            )
            await runtime.replaceConfigForTest(Config {
                Session {
                    restoreOnLaunch(true)
                }
            })

            await runtime.restoreWindowsOnLaunchIfEnabled()

            let remainingIDs = try await runtime.recoveryState().entries.map(\.windowID)
            XCTAssertTrue(remainingIDs.isEmpty)
        }
    }

    func testRestoreOnLaunchDefaultsOff() async throws {
        try await withRuntime { runtime, _, displayID in
            let window = WindowState(
                id: 91,
                processID: getpid(),
                displayID: displayID,
                tagMask: 1,
                isFloating: false,
                layoutOrder: 0,
                frame: CGRect(x: 10, y: 20, width: 300, height: 200),
                title: "journaled",
                role: "AXWindow",
                subrole: "AXStandardWindow"
            )
            try await runtime.upsertRuntimeWindow(
                window,
                element: AXUIElementCreateApplication(getpid())
            )
            try await runtime.recoveryJournal.record(
                window: window,
                parkedFrame: CGRect(x: -10_000, y: -10_000, width: 300, height: 200)
            )

            await runtime.restoreWindowsOnLaunchIfEnabled()

            let remainingIDs = try await runtime.recoveryState().entries.map(\.windowID)
            XCTAssertEqual(remainingIDs, [91])
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

    func testShowOverlayPublishesOverlayRequest() async throws {
        try await withRuntime { runtime, socketPath, _ in
            let stream = await runtime.overlayRequests.subscribe()
            let task = Task {
                var iterator = stream.makeAsyncIterator()
                return await iterator.next()
            }

            let response = try send(.showOverlay(.init(kind: .grid)), to: socketPath)
            let value = await task.value

            XCTAssertEqual(response.status, .success)
            XCTAssertEqual(value, .grid)
        }
    }

    func testDispatchGestureResizeChangesFocusedWindowSize() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Gestures {
                    fourFingerHorizontal(.action(.resize(.right, points: 40)))
                }
            })
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 200))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(
                .dispatchGesture(.init(trigger: .fourFingerHorizontal, motion: .right, displayID: displayID)),
                to: socketPath
            )

            XCTAssertEqual(response.status, .success)
            let snapshot = try stateSnapshot(from: send(.listWindows(.init(windowID: 1)), to: socketPath))
            XCTAssertEqual(snapshot.windows.first?.frame, IPCFrame(x: 0, y: 0, width: 340, height: 200))
        }
    }

    func testDispatchGestureSplitUpdatesBSPRatio() async throws {
        try await withRuntime { runtime, socketPath, displayID in
            await runtime.replaceConfigForTest(Config {
                Gestures {
                    fourFingerHorizontal(.action(.split(.right, ratio: 0.75)))
                }
            })
            XCTAssertEqual(
                try send(.setEngine(.init(engineID: BSPLayoutEngine.engineID, displayID: displayID)), to: socketPath).status,
                .success
            )
            try await seedWindows(runtime, displayID: displayID, windows: [
                (1, 0, CGRect(x: 0, y: 0, width: 300, height: 200)),
                (2, 1, CGRect(x: 300, y: 0, width: 300, height: 200))
            ])
            await runtime.setFocusedWindow(1)

            let response = try send(
                .dispatchGesture(.init(trigger: .fourFingerHorizontal, motion: .right, displayID: displayID)),
                to: socketPath
            )
            let rawConfig = await runtime.configForTest(engineID: BSPLayoutEngine.engineID)
            let config = try XCTUnwrap(rawConfig as? BSPLayoutEngine.Config)

            XCTAssertEqual(response.status, .success)
            XCTAssertEqual(
                config.tree.root,
                .split(axis: .horizontal, ratio: 0.75, first: .window(id: 1), second: .window(id: 2))
            )
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
    displayChangeStream: @escaping DisplayChangeStreamProvider = { AsyncStream { $0.finish() } },
    activeSpaceWindowIDs: @escaping ActiveSpaceWindowIDProvider = OllyRuntime.defaultActiveSpaceWindowIDs,
    focusInputAttribution: FocusInputAttribution = FocusInputAttribution(),
    mouseMoveStream: MouseMoveStreamProvider? = nil,
    windowUnderPointCandidates: @escaping WindowUnderPointCandidateProvider =
        { WindowUnderPointResolver.systemCandidates() },
    axWindowFocusSetter: @escaping AXWindowFocusSetter = { await OllyRuntime.defaultAXWindowFocusSetter($0) },
    tagApplicationLauncher: @escaping TagApplicationLauncher = { _ in },
    fullscreenDebounceNanoseconds: UInt64 = 100_000_000,
    extraDisplays: [Display] = [],
    _ body: (OllyRuntime, IPCSocketPath, DisplayID) async throws -> Void
) async throws {
    let fixture = try RuntimeFixture(extraDisplays: extraDisplays)
    let runtime = fixture.makeRuntime(
        snapshotCache: snapshotCache,
        dragSession: dragSession,
        axSubroleReader: axSubroleReader,
        displayChangeStream: displayChangeStream,
        activeSpaceWindowIDs: activeSpaceWindowIDs,
        focusInputAttribution: focusInputAttribution,
        mouseMoveStream: mouseMoveStream,
        windowUnderPointCandidates: windowUnderPointCandidates,
        axWindowFocusSetter: axWindowFocusSetter,
        tagApplicationLauncher: tagApplicationLauncher,
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

private func testDisplay(_ displayID: DisplayID) -> Display {
    Display(
        id: displayID,
        frame: CGRect(x: 0, y: 0, width: 1440, height: 900),
        visibleFrame: CGRect(x: 0, y: 0, width: 1440, height: 860),
        scaleFactor: 2,
        localizedName: "Test Display",
        isMain: true
    )
}

private extension OllyRuntime {
    func configForTest(engineID: LayoutEngineID) async -> Any? {
        await configStore.config(for: engineID)
    }

    func replaceConfigForTest(_ config: Config) async {
        await configStore.replace(with: config)
        nativeSpaceDriftPolicy = config.nativeSpace.driftPolicy
        focusPolicy = config.focusPolicy
        await hookDispatcher.update(config.hooks)
        await focusRateLimiter.update(settings: FocusRateLimitSettings(
            maxEventsPerSecond: config.focusPolicy.maxEventsPerSecond,
            minHumanIntervalMilliseconds: config.focusPolicy.minHumanIntervalMilliseconds
        ))
        configureFocusFollowsMouse()
    }

    func windowStateForTest(_ id: WindowID) async -> WindowState? {
        await windowStore.state(for: id)
    }

    func setAXPermissionStatusForTest(_ status: AXPermissionStatus?) {
        axPermissionStatus = status
    }

    func focusedWindowForTest() -> WindowID? {
        focusedWindowID
    }

    func setWindowTargetForTest(_ id: WindowID, displayID: DisplayID) {
        let target = WindowMoveTarget(
            id: id,
            axElement: AXUIElementCreateApplication(42),
            displayID: displayID
        )
        windowTargets.set(target, for: id)
    }

    func registerApplicationForTest(processID: pid_t, bundleID: String? = nil) {
        applicationsByProcessID[processID] = Application(processID: processID, bundleIdentifier: bundleID)
    }
}

private final class MouseMoveProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: AsyncStream<CGPoint>.Continuation?

    func stream() -> AsyncStream<CGPoint> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            lock.lock()
            self.continuation = continuation
            lock.unlock()
        }
    }

    func send(_ point: CGPoint) {
        lock.lock()
        let continuation = continuation
        lock.unlock()
        continuation?.yield(point)
    }
}

private actor FocusTargetRecorder {
    private var values: [WindowID?] = []

    var ids: [WindowID?] {
        values
    }

    func record(_ id: WindowID?) {
        values.append(id)
    }
}

private func waitForFocusedWindow(_ windowID: WindowID, in runtime: OllyRuntime) async throws {
    let deadline = Date().addingTimeInterval(1)
    while Date() < deadline {
        if await runtime.focusedWindowForTest() == windowID {
            return
        }
        try await Task.sleep(nanoseconds: 10_000_000)
    }
    XCTFail("timed out waiting for focused window \(windowID)")
    throw RuntimeTestError.unexpectedResponse
}

private final class HookRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var storedEvents: [String] = []

    var events: [String] {
        lock.lock()
        defer { lock.unlock() }
        return storedEvents
    }

    func record(_ event: String) {
        lock.lock()
        storedEvents.append(event)
        lock.unlock()
    }
}

private actor ApplicationLaunchRecorder {
    private(set) var bundleIDs: [String] = []

    func record(_ bundleID: String) {
        bundleIDs.append(bundleID)
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
        displayChangeStream: @escaping DisplayChangeStreamProvider = { AsyncStream { $0.finish() } },
        activeSpaceWindowIDs: @escaping ActiveSpaceWindowIDProvider = OllyRuntime.defaultActiveSpaceWindowIDs,
        focusInputAttribution: FocusInputAttribution = FocusInputAttribution(),
        mouseMoveStream: MouseMoveStreamProvider? = nil,
        windowUnderPointCandidates: @escaping WindowUnderPointCandidateProvider =
            { WindowUnderPointResolver.systemCandidates() },
        axWindowFocusSetter: @escaping AXWindowFocusSetter = { await OllyRuntime.defaultAXWindowFocusSetter($0) },
        tagApplicationLauncher: @escaping TagApplicationLauncher = { _ in },
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
            macroRecorder: MacroRecorder(
                directoryURL: directoryURL.appendingPathComponent("macros", isDirectory: true)
            ),
            scanAXOnStart: false,
            dragSession: dragSession,
            axPermissionStream: {
                AsyncStream { continuation in
                    continuation.finish()
                }
            },
            axSubroleReader: axSubroleReader,
            displayChangeStream: displayChangeStream,
            activeSpaceWindowIDs: activeSpaceWindowIDs,
            focusInputAttribution: focusInputAttribution,
            mouseMoveStream: mouseMoveStream,
            windowUnderPointCandidates: windowUnderPointCandidates,
            axWindowFocusSetter: axWindowFocusSetter,
            tagApplicationLauncher: tagApplicationLauncher,
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
