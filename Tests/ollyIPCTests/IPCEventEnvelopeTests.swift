import Foundation
import XCTest
import ollyCore
import ollyIPC
import ollyLayouts

final class IPCEventEnvelopeTests: XCTestCase {
    func testEngineEventEnvelopeRoundTripsAsNewlineDelimitedJSON() throws {
        let event = EngineEvent.masterSwapped(
            MasterSwappedEvent(
                previousMaster: 1,
                currentMaster: 2,
                order: [2, 1, 3]
            )
        )
        let envelope = IPCEventEnvelope(event: .engine(event))

        let line = try envelope.newlineDelimitedJSON()
        let decoded = try JSONDecoder().decode(IPCEventEnvelope.self, from: Data(line.dropLast()))

        XCTAssertEqual(line.last, 0x0A)
        XCTAssertEqual(decoded, envelope)
    }

    func testFocusEventEnvelopeRoundTripsAsNewlineDelimitedJSON() throws {
        let envelope = IPCEventEnvelope(
            event: .focus(IPCFocusEvent(focusedWindowID: 42, displayID: 7, tagMask: 3))
        )

        let line = try envelope.newlineDelimitedJSON()
        let decoded = try JSONDecoder().decode(IPCEventEnvelope.self, from: Data(line.dropLast()))

        XCTAssertEqual(line.last, 0x0A)
        XCTAssertEqual(decoded, envelope)
    }

    func testAXPermissionEventEnvelopeRoundTripsAsNewlineDelimitedJSON() throws {
        let envelope = IPCEventEnvelope(event: .axPermission(IPCAXPermissionEvent(status: .missing)))

        let line = try envelope.newlineDelimitedJSON()
        let decoded = try JSONDecoder().decode(IPCEventEnvelope.self, from: Data(line.dropLast()))

        XCTAssertEqual(line.last, 0x0A)
        XCTAssertEqual(decoded, envelope)
    }

    func testFullscreenEventEnvelopeRoundTripsAsNewlineDelimitedJSON() throws {
        let envelope = IPCEventEnvelope(event: .fullscreen(IPCFullscreenEvent(windowID: 42, didEnter: true)))

        let line = try envelope.newlineDelimitedJSON()
        let decoded = try JSONDecoder().decode(IPCEventEnvelope.self, from: Data(line.dropLast()))

        XCTAssertEqual(line.last, 0x0A)
        XCTAssertEqual(decoded, envelope)
    }

    func testSpaceEventEnvelopeRoundTripsAsNewlineDelimitedJSON() throws {
        let envelope = IPCEventEnvelope(event: .space(IPCSpaceDriftEvent(
            windowID: 42,
            fromDisplayID: 7,
            action: .markedOffSpace
        )))

        let line = try envelope.newlineDelimitedJSON()
        let decoded = try JSONDecoder().decode(IPCEventEnvelope.self, from: Data(line.dropLast()))

        XCTAssertEqual(line.last, 0x0A)
        XCTAssertEqual(decoded, envelope)
    }
}
