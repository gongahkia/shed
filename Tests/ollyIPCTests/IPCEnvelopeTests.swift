import Foundation
import XCTest
import ollyIPC

final class IPCEnvelopeTests: XCTestCase {
    func testRequestEnvelopeRoundTripsAsJSONLine() throws {
        let envelope = IPCRequestEnvelope(
            id: "request-1",
            command: .focus(IPCDirectionalCommand(direction: .next))
        )

        let line = try envelope.newlineDelimitedJSON()
        let decoded = try JSONDecoder().decode(IPCRequestEnvelope.self, from: Data(line.dropLast()))

        XCTAssertEqual(line.last, JSONLineCodec.lineFeed)
        XCTAssertEqual(decoded, envelope)
        XCTAssertEqual(decoded.version, OllyIPC.protocolVersion)
    }

    func testSuccessResponseEnvelopeRoundTrips() throws {
        let envelope = IPCResponseEnvelope.ok(
            id: "request-2",
            result: .version(IPCVersionInfo())
        )

        let data = try JSONEncoder().encode(envelope)
        let decoded = try JSONDecoder().decode(IPCResponseEnvelope.self, from: data)

        XCTAssertEqual(decoded, envelope)
        XCTAssertEqual(decoded.status, .success)
    }

    func testVersionInfoReportsSupportedEventKinds() {
        let info = IPCVersionInfo()

        XCTAssertEqual(info.protocolVersion, 2)
        XCTAssertEqual(info.supportedEventKinds, IPCEventKind.allCases)
    }

    func testVersionInfoDecodesV1PayloadWithoutSupportedEventKinds() throws {
        let data = Data(#"{"protocolVersion":1,"supportedCommands":["state","version"]}"#.utf8)

        let info = try JSONDecoder().decode(IPCVersionInfo.self, from: data)

        XCTAssertEqual(info.protocolVersion, 1)
        XCTAssertEqual(info.supportedEventKinds, IPCEventKind.v1Cases)
    }

    func testSubscribeEventsDecodesMissingSupportedEventKinds() throws {
        let data = Data(#"{"eventKinds":["focus"],"replayCurrentState":true}"#.utf8)

        let command = try JSONDecoder().decode(IPCSubscribeEventsCommand.self, from: data)

        XCTAssertEqual(command.eventKinds, [.focus])
        XCTAssertEqual(command.supportedEventKinds, IPCEventKind.allCases)
        XCTAssertTrue(command.replayCurrentState)
    }

    func testRestoreWindowsResponseRoundTrips() throws {
        let envelope = IPCResponseEnvelope.ok(
            id: "request-restore",
            result: .restoredWindows(IPCRestoreWindowsInfo(restoredCount: 2, skippedCount: 1, failedCount: 0))
        )

        let data = try JSONEncoder().encode(envelope)
        let decoded = try JSONDecoder().decode(IPCResponseEnvelope.self, from: data)

        XCTAssertEqual(decoded, envelope)
        XCTAssertEqual(decoded.status, .success)
    }

    func testErrorResponseEnvelopeRoundTrips() throws {
        let envelope = IPCResponseEnvelope.failure(
            id: "request-3",
            error: IPCErrorPayload(code: "invalid-command", message: "unknown command")
        )

        let data = try JSONEncoder().encode(envelope)
        let decoded = try JSONDecoder().decode(IPCResponseEnvelope.self, from: data)

        XCTAssertEqual(decoded, envelope)
        XCTAssertEqual(decoded.status, .error)
    }
}
