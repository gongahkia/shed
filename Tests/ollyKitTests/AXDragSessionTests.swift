import CoreGraphics
import Foundation
import XCTest
@testable import ollyKit

final class AXDragSessionTests: XCTestCase {
    func testMoveBurstEmitsStartedMovedAndEndedWithFinalFrame() async {
        let scheduler = ManualDragEndScheduler()
        let session = AXDragSession(
            endDelayNanoseconds: 120,
            mouseProvider: { CGPoint(x: 9, y: 11) },
            endTaskFactory: { delayNanoseconds, operation in
                scheduler.schedule(delayNanoseconds: delayNanoseconds, operation: operation)
            }
        )
        let stream = await session.subscribe()
        let events = collect(stream, count: 3)
        let startFrame = CGRect(x: 10, y: 20, width: 300, height: 200)
        let movedFrame = CGRect(x: 18, y: 24, width: 300, height: 200)

        await session.feed(windowID: 1, frame: startFrame, ourLastFrame: nil)
        await session.feed(windowID: 1, frame: movedFrame, ourLastFrame: nil)
        await scheduler.fireNext()
        await scheduler.fireNext()

        let receivedEvents = await events.value
        let activeWindowID = await session.activeWindowID
        XCTAssertEqual(receivedEvents, [
            .started(1, startFrame, CGPoint(x: 9, y: 11)),
            .moved(1, movedFrame, CGPoint(x: 9, y: 11)),
            .ended(1, movedFrame)
        ])
        XCTAssertNil(activeWindowID)
    }

    func testProgrammaticMoveIsFilteredAgainstLastWindowMoverFrame() async {
        let scheduler = ManualDragEndScheduler()
        let session = AXDragSession(
            endDelayNanoseconds: 120,
            mouseProvider: { CGPoint(x: 1, y: 2) },
            endTaskFactory: { delayNanoseconds, operation in
                scheduler.schedule(delayNanoseconds: delayNanoseconds, operation: operation)
            }
        )
        let stream = await session.subscribe()
        var iterator = stream.makeAsyncIterator()
        let programmaticFrame = CGRect(x: 10.5, y: 20.5, width: 300, height: 200)
        let userFrame = CGRect(x: 42, y: 55, width: 300, height: 200)

        await session.feed(
            windowID: 2,
            frame: programmaticFrame,
            ourLastFrame: CGRect(x: 10, y: 20, width: 300, height: 200)
        )
        await session.feed(windowID: 2, frame: userFrame, ourLastFrame: nil)

        let receivedEvent = await iterator.next()
        XCTAssertEqual(receivedEvent, .started(2, userFrame, CGPoint(x: 1, y: 2)))
    }

    func testSwitchingWindowsEndsPreviousSessionBeforeStartingNext() async {
        let scheduler = ManualDragEndScheduler()
        let session = AXDragSession(
            endDelayNanoseconds: 120,
            mouseProvider: { CGPoint(x: 3, y: 4) },
            endTaskFactory: { delayNanoseconds, operation in
                scheduler.schedule(delayNanoseconds: delayNanoseconds, operation: operation)
            }
        )
        let stream = await session.subscribe()
        let events = collect(stream, count: 3)
        let firstFrame = CGRect(x: 0, y: 0, width: 100, height: 100)
        let secondFrame = CGRect(x: 200, y: 0, width: 100, height: 100)

        await session.feed(windowID: 1, frame: firstFrame, ourLastFrame: nil)
        await session.feed(windowID: 2, frame: secondFrame, ourLastFrame: nil)

        let receivedEvents = await events.value
        XCTAssertEqual(receivedEvents, [
            .started(1, firstFrame, CGPoint(x: 3, y: 4)),
            .ended(1, firstFrame),
            .started(2, secondFrame, CGPoint(x: 3, y: 4))
        ])
    }

    func testEndActiveSessionPublishesEndedAndClearsState() async {
        let scheduler = ManualDragEndScheduler()
        let session = AXDragSession(
            endDelayNanoseconds: 120,
            mouseProvider: { .zero },
            endTaskFactory: { delayNanoseconds, operation in
                scheduler.schedule(delayNanoseconds: delayNanoseconds, operation: operation)
            }
        )
        let stream = await session.subscribe()
        let events = collect(stream, count: 2)
        let frame = CGRect(x: 5, y: 6, width: 200, height: 120)

        await session.feed(windowID: 8, frame: frame, ourLastFrame: nil)
        await session.endActiveSession()

        let receivedEvents = await events.value
        let activeWindowID = await session.activeWindowID
        XCTAssertEqual(receivedEvents, [.started(8, frame, .zero), .ended(8, frame)])
        XCTAssertNil(activeWindowID)
    }
}

private func collect(_ stream: AsyncStream<AXDragEvent>, count: Int) -> Task<[AXDragEvent], Never> {
    Task {
        var iterator = stream.makeAsyncIterator()
        var events: [AXDragEvent] = []
        while events.count < count, let event = await iterator.next() {
            events.append(event)
        }
        return events
    }
}

private final class ManualDragEndScheduler: @unchecked Sendable {
    private let lock = NSLock()
    private var operations: [@Sendable () async -> Void] = []

    func schedule(
        delayNanoseconds: UInt64,
        operation: @escaping @Sendable () async -> Void
    ) -> Task<Void, Never> {
        lock.lock()
        operations.append(operation)
        lock.unlock()
        return Task {}
    }

    func fireNext() async {
        await popNext()?()
    }

    private func popNext() -> (@Sendable () async -> Void)? {
        lock.lock()
        defer {
            lock.unlock()
        }
        return operations.isEmpty ? nil : operations.removeFirst()
    }
}
