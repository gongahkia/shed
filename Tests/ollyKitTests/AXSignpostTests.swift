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

    func testPerformanceIntervalReturnsOperationValue() async {
        let value: Int = await PerformanceSignpost.interval("perf.test") {
            try? await Task.sleep(nanoseconds: 0)
            return 42
        }
        XCTAssertEqual(value, 42)
    }

    func testPerformanceIntervalRethrowsOperationError() async {
        await XCTAssertThrowsErrorAsync(
            try await PerformanceSignpost.interval("perf.test") {
                try await Task.sleep(nanoseconds: 0)
                throw TestError.expected
            }
        )
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("expected error", file: file, line: line)
    } catch {}
}

private enum TestError: Error {
    case expected
}
