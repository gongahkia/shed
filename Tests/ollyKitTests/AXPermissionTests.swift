import XCTest
@testable import ollyKit

final class AXPermissionTests: XCTestCase {
    func testRefreshMatchesNonPromptingStatus() async {
        let status = AXPermission.status(prompt: false)
        let refreshedStatus = await AXPermission.refresh()
        XCTAssertEqual(refreshedStatus, status)
    }
}
