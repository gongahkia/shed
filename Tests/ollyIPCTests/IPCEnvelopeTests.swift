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
