import XCTest
@testable import ollyKit

final class FocusInputAttributionTests: XCTestCase {
    func testRecordedInputExpiresByInterval() {
        let attribution = FocusInputAttribution()

        attribution.recordInput(pid: 42, at: Date())

        XCTAssertTrue(attribution.hasRecentInput(pid: 42, within: 100))
        XCTAssertFalse(attribution.hasRecentInput(pid: 42, within: -1))
    }

    func testMouseMoveStreamPublishesLatestPoint() async {
        let attribution = FocusInputAttribution()
        var iterator = attribution.mouseMoves().makeAsyncIterator()

        attribution.recordMouseMove(location: CGPoint(x: 9, y: 11))

        let point = await iterator.next()
        XCTAssertEqual(point, CGPoint(x: 9, y: 11))
    }
}
