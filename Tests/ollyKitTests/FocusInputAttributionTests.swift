import XCTest
@testable import ollyKit

final class FocusInputAttributionTests: XCTestCase {
    func testRecordedInputExpiresByInterval() {
        let attribution = FocusInputAttribution()

        attribution.recordInput(pid: 42, at: Date())

        XCTAssertTrue(attribution.hasRecentInput(pid: 42, within: 100))
        XCTAssertFalse(attribution.hasRecentInput(pid: 42, within: -1))
    }
}
