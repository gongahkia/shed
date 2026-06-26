import XCTest
@testable import ollyKit

final class AXSignpostTests: XCTestCase {
    func testIntervalReturnsOperationValue() {
        let value = AXSignpost.interval("ax.test") {
            42
        }
        XCTAssertEqual(value, 42)
    }

    func testIntervalRethrowsOperationError() {
        XCTAssertThrowsError(
            try AXSignpost.interval("ax.test") {
                throw TestError.expected
            }
        )
    }
}

private enum TestError: Error {
    case expected
}
