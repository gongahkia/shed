import Foundation
import XCTest
import ollyCore
import ollyIPC
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
            .moveWindow(IPCDirectionalCommand(direction: .downward)),
            .moveToDisplay(IPCMoveToDisplayCommand(displayID: 2, windowID: 42)),
            .swap(IPCDirectionalCommand(direction: .right)),
            .toggleFloating(IPCFloatingCommand(windowID: 42, floating: true, displayID: 1)),
            .snapWindow(IPCSnapWindowCommand(position: .topRight, windowID: 42, displayID: 1)),
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
            .cycleEngine(IPCCycleEngineCommand(reverse: true, displayID: 1)),
            .tagAdd(IPCTagCommand(tag: try tag(4), displayID: 2)),
            .tagRemove(IPCTagCommand(tag: try tag(5))),
            .reload(IPCReloadCommand()),
            .restoreWindows(IPCRestoreWindowsCommand()),
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

    func testRequiredArgumentsAreRejectedWhenMissing() {
        let data = Data(#"{"name":"focus"}"#.utf8)

        XCTAssertThrowsError(try JSONDecoder().decode(IPCCommand.self, from: data))
    }

    private func tag(_ index: Int) throws -> IPCTagIndex {
        try IPCTagIndex(validating: index)
    }
}
