import Foundation
import XCTest
import ollyCore
import ollyIPC

final class IPCCommandTests: XCTestCase {
    func testCommandNamesAreStable() {
        XCTAssertEqual(
            IPCCommandName.allCases.map(\.rawValue),
            [
                "state",
                "focus",
                "swap",
                "move-to-tag",
                "set-engine",
                "tag-add",
                "tag-remove",
                "reload",
                "subscribe-events",
                "version"
            ]
        )
    }

    func testCommandsRoundTripWithStableNames() throws {
        let commands: [IPCCommand] = [
            .state(IPCStateCommand(displayID: 1)),
            .focus(IPCDirectionalCommand(direction: .left, displayID: 1)),
            .swap(IPCDirectionalCommand(direction: .right)),
            .moveToTag(IPCMoveToTagCommand(tag: try tag(2), windowID: 42)),
            .setEngine(IPCSetEngineCommand(engineID: LayoutEngineID(rawValue: "bsp"), tag: try tag(3))),
            .tagAdd(IPCTagCommand(tag: try tag(4), displayID: 2)),
            .tagRemove(IPCTagCommand(tag: try tag(5))),
            .reload(IPCReloadCommand()),
            .subscribeEvents(IPCSubscribeEventsCommand(eventKinds: [.engine, .focus], replayCurrentState: true)),
            .version(IPCVersionCommand())
        ]

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
