import Foundation
import XCTest
import ollyCore
import ollyIPC
import ollyKit
import ollyLayouts

final class IPCCommandTests: XCTestCase {
    func testCommandNamesAreStable() {
        XCTAssertEqual(
            IPCCommandName.allCases.map(\.rawValue),
            [
                "state",
                "focus",
                "list-windows",
                "list-displays",
                "move-window",
                "move-to-display",
                "swap",
                "toggle-floating",
                "snap-window",
                "dispatch-gesture",
                "manual-preselect",
                "bsp-tree",
                "switch-tag",
                "move-to-tag",
                "toggle-tag",
                "set-engine",
                "cycle-engine",
                "tag-add",
                "tag-remove",
                "reload",
                "restore-windows",
                "subscribe-events",
                "version",
                "scratchpad-add",
                "scratchpad-toggle",
                "scratchpad-list",
                "scratchpad-remove",
                "toggle-sticky",
                "toggle-pinned",
                "explain-window",
                "explain-rule",
                "macro-start",
                "macro-stop",
                "macro-run",
                "macro-list",
                "macro-delete",
                "run-raw-action",
                "set-space-policy",
                "set-focus-policy",
                "telemetry-status",
                "telemetry-flush",
                "show-overlay",
                "list-cooperative-apps"
            ]
        )
    }

    func testCommandsRoundTripWithStableNames() throws {
        let commands: [IPCCommand] = [
            .state(IPCStateCommand(displayID: 1)),
            .focus(IPCDirectionalCommand(direction: .left, displayID: 1)),
            .listWindows(IPCWindowQueryCommand(windowID: 42, displayID: 1)),
            .listDisplays(IPCDisplayQueryCommand(displayID: 1)),
            .listCooperativeApps(IPCListCooperativeAppsCommand()),
            .explainWindow(IPCExplainWindowCommand(windowID: 42)),
            .explainRule(IPCExplainRuleCommand(ruleID: UUID())),
            .moveWindow(IPCDirectionalCommand(direction: .downward)),
            .moveToDisplay(IPCMoveToDisplayCommand(displayID: 2, windowID: 42)),
            .swap(IPCDirectionalCommand(direction: .right)),
            .toggleFloating(IPCFloatingCommand(windowID: 42, floating: true, displayID: 1)),
            .toggleSticky(IPCStickyCommand(windowID: 42, sticky: true, displayID: 1)),
            .togglePinned(IPCPinnedCommand(windowID: 42, pinned: true, displayID: 1)),
            .snapWindow(IPCSnapWindowCommand(position: .topRight, windowID: 42, displayID: 1)),
            .showOverlay(IPCShowOverlayCommand(kind: .grid)),
            .dispatchGesture(IPCDispatchGestureCommand(
                trigger: .fourFingerHorizontal,
                motion: .left,
                displayID: 1
            )),
            .manualPreselect(IPCManualPreselectCommand(direction: .left, windowID: 42, displayID: 1)),
            .bspTree(IPCBSPTreeCommand(action: .flipAxis, path: BSPContainerPath([0]), displayID: 1)),
            .switchTag(IPCTagCommand(tag: try tag(1), displayID: 2)),
            .moveToTag(IPCMoveToTagCommand(tag: try tag(2), windowID: 42)),
            .toggleTag(IPCTagCommand(tag: try tag(6))),
            .setEngine(IPCSetEngineCommand(engineID: LayoutEngineID(rawValue: "bsp"), tag: try tag(3))),
            .cycleEngine(IPCCycleEngineCommand(reverse: true, tag: try tag(2), displayID: 1)),
            .tagAdd(IPCTagCommand(tag: try tag(4), displayID: 2)),
            .tagRemove(IPCTagCommand(tag: try tag(5))),
            .reload(IPCReloadCommand()),
            .restoreWindows(IPCRestoreWindowsCommand()),
            .scratchpadAdd(IPCScratchpadAddCommand(
                name: "term",
                bundleID: "com.apple.Terminal",
                titleRegex: "Scratch"
            )),
            .scratchpadToggle(IPCScratchpadToggleCommand(name: "term")),
            .scratchpadList(IPCScratchpadListCommand()),
            .scratchpadRemove(IPCScratchpadRemoveCommand(name: "term")),
            .macroStart(IPCMacroStartCommand(name: "workflow1")),
            .macroStop(IPCMacroStopCommand()),
            .macroRun(IPCMacroRunCommand(name: "workflow1")),
            .macroList(IPCMacroListCommand()),
            .macroDelete(IPCMacroDeleteCommand(name: "workflow1")),
            .runRawAction(IPCRunRawActionCommand(label: "safari")),
            .setSpacePolicy(IPCSetSpacePolicyCommand(policy: .followWindow)),
            .setFocusPolicy(IPCSetFocusPolicyCommand(
                allowedBundleIDs: ["com.apple.Terminal"],
                maxEventsPerSecond: 10
            )),
            .subscribeEvents(IPCSubscribeEventsCommand(eventKinds: [.engine, .focus], replayCurrentState: true)),
            .version(IPCVersionCommand())
        ] + IPCCommandName.reservedV2.map { .reserved(IPCReservedCommand(name: $0)) }

        for command in commands {
            let data = try JSONEncoder().encode(command)
            let decoded = try JSONDecoder().decode(IPCCommand.self, from: data)
            let json = String(decoding: data, as: UTF8.self)

            XCTAssertEqual(decoded, command)
            XCTAssertTrue(json.contains(command.name.rawValue))
        }
    }

    func testMacroResultsRoundTrip() throws {
        let info = IPCMacroInfo(
            name: "workflow1",
            createdAt: Date(timeIntervalSince1970: 0),
            recordedDurationMs: 25,
            commandCount: 2
        )
        let results: [IPCCommandResult] = [
            .macro(info),
            .macros(IPCMacroListInfo(macros: [info]))
        ]

        for result in results {
            let data = try JSONEncoder().encode(result)
            let decoded = try JSONDecoder().decode(IPCCommandResult.self, from: data)

            XCTAssertEqual(decoded, result)
        }
    }

    func testScratchpadResultsRoundTrip() throws {
        let info = IPCScratchpadInfo(
            name: "term",
            bundleID: "com.apple.Terminal",
            titleRegex: "Scratch",
            isVisible: false
        )
        let result = IPCCommandResult.scratchpads(IPCScratchpadListInfo(scratchpads: [info]))

        let data = try JSONEncoder().encode(result)
        let decoded = try JSONDecoder().decode(IPCCommandResult.self, from: data)

        XCTAssertEqual(decoded, result)
    }

    func testRequiredArgumentsAreRejectedWhenMissing() {
        let data = Data(#"{"name":"focus"}"#.utf8)

        XCTAssertThrowsError(try JSONDecoder().decode(IPCCommand.self, from: data))
    }

    private func tag(_ index: Int) throws -> IPCTagIndex {
        try IPCTagIndex(validating: index)
    }
}
