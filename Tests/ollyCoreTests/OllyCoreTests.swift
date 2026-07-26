import XCTest
@testable import ollyCore

final class OllyCoreTests: XCTestCase {
    func testModuleName() {
        XCTAssertEqual(OllyCore.moduleName, "ollyCore")
    }
}
