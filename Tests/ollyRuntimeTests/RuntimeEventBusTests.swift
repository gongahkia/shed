import XCTest
import ollyIPC
@testable import ollyRuntime

final class RuntimeEventBusTests: XCTestCase {
    func testPublishesToMultipleSubscribers() async {
        let bus = RuntimeEventBus()
        let first = await bus.subscribe()
        let second = await bus.subscribe()
        let event = IPCEvent.focus(IPCFocusEvent(focusedWindowID: 42, displayID: 7, tagMask: 1))

        let firstTask = Task {
            var iterator = first.makeAsyncIterator()
            return await iterator.next()
        }
        let secondTask = Task {
            var iterator = second.makeAsyncIterator()
            return await iterator.next()
        }
        await bus.publish(event)

        let firstEvent = await firstTask.value
        let secondEvent = await secondTask.value
        XCTAssertEqual(firstEvent, event)
        XCTAssertEqual(secondEvent, event)
    }

    func testCancelledSubscribersAreRemoved() async throws {
        let bus = RuntimeEventBus()
        let stream = await bus.subscribe()
        let task = Task {
            for await _ in stream {}
        }

        let initialCount = await bus.activeSubscriberCount
        XCTAssertEqual(initialCount, 1)
        task.cancel()
        try await Task.sleep(nanoseconds: 50_000_000)

        let finalCount = await bus.activeSubscriberCount
        XCTAssertEqual(finalCount, 0)
    }

    func testOverlayRequestBusPublishesToSubscriber() async {
        let bus = RuntimeOverlayRequestBus()
        let stream = await bus.subscribe()
        let task = Task {
            var iterator = stream.makeAsyncIterator()
            return await iterator.next()
        }

        await bus.publish(.grid)
        let value = await task.value

        XCTAssertEqual(value, .grid)
    }
}
