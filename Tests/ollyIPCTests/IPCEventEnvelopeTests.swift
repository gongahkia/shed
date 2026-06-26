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
}
