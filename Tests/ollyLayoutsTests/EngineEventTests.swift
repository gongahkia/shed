import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class EngineEventTests: XCTestCase {
    func testEngineEventRoundTripsTypedPayloads() throws {
        let event = EngineEvent.niriColumnWidthChanged(
            NiriColumnWidthChangedEvent(columnIndex: 1, widthPreset: .twoThirds)
        )

        let data = try JSONEncoder().encode(event)
        let decoded = try JSONDecoder().decode(EngineEvent.self, from: data)

        XCTAssertEqual(decoded, event)
    }

    func testEngineEventBusPublishesToSubscribers() async {
        let bus = EngineEventBus()
        var iterator = await bus.events().makeAsyncIterator()
        let event = EngineEvent.bspTreeChanged(
            BSPTreeChangedEvent(action: .flipAxis, path: .root)
        )

        await bus.publish(event)
        let received = await iterator.next()

        XCTAssertEqual(received, event)
    }
}
