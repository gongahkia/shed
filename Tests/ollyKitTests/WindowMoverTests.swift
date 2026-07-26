import ApplicationServices
import CoreGraphics
import XCTest
@testable import ollyKit

final class WindowMoverTests: XCTestCase {
    func testCoalescesSequentialPositionsToLatestValue() async {
        let client = FakeAXWindowMoveClient()
        let mover = WindowMover(client: client, frameDelayNanoseconds: 1_000_000_000)
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))

        await mover.setPosition(CGPoint(x: 1, y: 1), for: target)
        await mover.setPosition(CGPoint(x: 2, y: 2), for: target)
        await mover.flushNow()

        XCTAssertEqual(client.positions, [CGPoint(x: 2, y: 2)])
    }

    func testSkipsSizeForNonResizableWindow() async {
        let client = FakeAXWindowMoveClient()
        client.resizable = false
        let mover = WindowMover(client: client, frameDelayNanoseconds: 1_000_000_000)
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))

        await mover.setSize(CGSize(width: 400, height: 300), for: target)
        await mover.flushNow()

        XCTAssertEqual(client.sizes, [])
    }

    func testRetriesFailedPositionWrite() async {
        let client = FakeAXWindowMoveClient()
        client.positionResults = [.cannotComplete, .success]
        let mover = WindowMover(
            client: client,
            frameDelayNanoseconds: 1_000_000_000,
            retryDelayNanoseconds: 0,
            maxRetries: 2
        )
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))

        await mover.setPosition(CGPoint(x: 5, y: 8), for: target)
        await mover.flushNow()

        XCTAssertEqual(client.positions, [CGPoint(x: 5, y: 8), CGPoint(x: 5, y: 8)])
    }

    func testSkipsNoOpWritesBelowOnePixel() async {
        let client = FakeAXWindowMoveClient()
        client.frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        let mover = WindowMover(client: client, frameDelayNanoseconds: 1_000_000_000)
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))

        await mover.setPosition(CGPoint(x: 10.5, y: 20.25), for: target)
        await mover.setSize(CGSize(width: 300.75, height: 200.5), for: target)
        await mover.flushNow()

        XCTAssertTrue(client.positions.isEmpty)
        XCTAssertTrue(client.sizes.isEmpty)
    }

    func testWritesWhenDeltaIsAtLeastOnePixel() async {
        let client = FakeAXWindowMoveClient()
        client.frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        let mover = WindowMover(client: client, frameDelayNanoseconds: 1_000_000_000)
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))

        await mover.setPosition(CGPoint(x: 11, y: 20), for: target)
        await mover.setSize(CGSize(width: 301, height: 200), for: target)
        await mover.flushNow()

        XCTAssertEqual(client.positions, [CGPoint(x: 11, y: 20)])
        XCTAssertEqual(client.sizes, [CGSize(width: 301, height: 200)])
    }

    func testCoalescesRepeatedTargetFrameAfterSuccessfulWrite() async {
        let client = FakeAXWindowMoveClient()
        let mover = WindowMover(client: client, frameDelayNanoseconds: 1_000_000_000)
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))

        await mover.setPosition(CGPoint(x: 40, y: 50), for: target)
        await mover.setSize(CGSize(width: 500, height: 320), for: target)
        await mover.flushNow()
        await mover.setPosition(CGPoint(x: 40, y: 50), for: target)
        await mover.setSize(CGSize(width: 500, height: 320), for: target)
        await mover.flushNow()

        XCTAssertEqual(client.positions, [CGPoint(x: 40, y: 50)])
        XCTAssertEqual(client.sizes, [CGSize(width: 500, height: 320)])
        let lastFrame = await mover.lastFrame(for: target)
        XCTAssertEqual(lastFrame, CGRect(x: 40, y: 50, width: 500, height: 320))
    }

    func testFlushAndPauseDrainsPendingMovesAndRejectsWritesUntilResume() async {
        let client = FakeAXWindowMoveClient()
        let mover = WindowMover(client: client, frameDelayNanoseconds: 1_000_000_000)
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))

        await mover.setPosition(CGPoint(x: 10, y: 20), for: target)
        await mover.flushAndPause()
        await mover.setPosition(CGPoint(x: 30, y: 40), for: target)
        await mover.flushNow()
        await mover.resume()
        await mover.setPosition(CGPoint(x: 50, y: 60), for: target)
        await mover.flushNow()

        XCTAssertEqual(client.positions, [CGPoint(x: 10, y: 20), CGPoint(x: 50, y: 60)])
    }

    func testReportsAXPermissionRevocationWriteFailure() async {
        let client = FakeAXWindowMoveClient()
        client.positionResults = [.cannotComplete]
        let recorder = AXErrorRecorder()
        let mover = WindowMover(client: client, frameDelayNanoseconds: 1_000_000_000, maxRetries: 0)
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))

        await mover.setAXErrorHandler { error in
            recorder.record(error)
        }
        await mover.setPosition(CGPoint(x: 10, y: 20), for: target)
        await mover.flushNow()

        XCTAssertEqual(recorder.errors, [.cannotComplete])
    }

    func testInterpolatesEaseOutAnimationFrames() {
        let frames = WindowFrameAnimation.frames(
            from: CGRect(x: 0, y: 0, width: 100, height: 100),
            to: CGRect(x: 200, y: 200, width: 400, height: 400),
            duration: 0.2,
            frameInterval: 0.05,
            curve: .easeOut
        )

        XCTAssertEqual(frames.count, 4)
        XCTAssertEqual(frames[0].elapsed, 0.05, accuracy: 0.0001)
        assertFrame(frames[0].frame, equals: CGRect(x: 87.5, y: 87.5, width: 231.25, height: 231.25))
        assertFrame(frames[1].frame, equals: CGRect(x: 150, y: 150, width: 325, height: 325))
        assertFrame(frames[2].frame, equals: CGRect(x: 187.5, y: 187.5, width: 381.25, height: 381.25))
    }

    func testZeroDurationAnimatedFrameIssuesOneWrite() async {
        let client = FakeAXWindowMoveClient()
        client.frame = CGRect(x: 0, y: 0, width: 100, height: 100)
        let mover = WindowMover(client: client, frameDelayNanoseconds: 1_000_000_000)
        let target = WindowMoveTarget(id: 1, axElement: AXUIElementCreateApplication(getpid()))
        let end = CGRect(x: 200, y: 200, width: 400, height: 400)

        await mover.setFrameAnimated(from: client.frame ?? .zero, to: end, duration: 0, curve: .easeOut, for: target)
        await mover.flushNow()

        XCTAssertEqual(client.positions, [end.origin])
        XCTAssertEqual(client.sizes, [end.size])
    }

    private func assertFrame(_ actual: CGRect, equals expected: CGRect, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(actual.origin.x, expected.origin.x, accuracy: 1, file: file, line: line)
        XCTAssertEqual(actual.origin.y, expected.origin.y, accuracy: 1, file: file, line: line)
        XCTAssertEqual(actual.size.width, expected.size.width, accuracy: 1, file: file, line: line)
        XCTAssertEqual(actual.size.height, expected.size.height, accuracy: 1, file: file, line: line)
    }
}

private final class AXErrorRecorder: @unchecked Sendable {
    private(set) var errors: [AXError] = []

    func record(_ error: AXError) {
        errors.append(error)
    }
}

private final class FakeAXWindowMoveClient: AXWindowMoveClient {
    var frame: CGRect?
    var resizable = true
    var positions: [CGPoint] = []
    var sizes: [CGSize] = []
    var positionResults: [AXError] = [.success]
    var sizeResults: [AXError] = [.success]

    func frame(for element: AXUIElement) -> CGRect? {
        frame
    }

    func isResizable(_ element: AXUIElement) -> Bool {
        resizable
    }

    func setPosition(_ position: CGPoint, for element: AXUIElement) -> AXError {
        positions.append(position)
        return nextResult(from: &positionResults)
    }

    func setSize(_ size: CGSize, for element: AXUIElement) -> AXError {
        sizes.append(size)
        return nextResult(from: &sizeResults)
    }

    private func nextResult(from results: inout [AXError]) -> AXError {
        guard results.count > 1 else {
            return results.first ?? .success
        }
        return results.removeFirst()
    }
}
