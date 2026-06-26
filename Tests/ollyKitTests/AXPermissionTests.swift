import XCTest
@testable import ollyKit

final class AXPermissionTests: XCTestCase {
    func testAccessibilitySettingsDeepLinkIsStable() throws {
        let url = try XCTUnwrap(AXPermission.accessibilitySettingsURL)

        XCTAssertEqual(url.scheme, "x-apple.systempreferences")
        XCTAssertTrue(AXPermission.accessibilitySettingsDeepLink.contains("Privacy_Accessibility"))
    }

    func testRefreshMatchesNonPromptingStatus() async {
        let status = AXPermission.status(prompt: false)
        let refreshedStatus = await AXPermission.refresh()
        XCTAssertEqual(refreshedStatus, status)
    }
}
