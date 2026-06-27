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
