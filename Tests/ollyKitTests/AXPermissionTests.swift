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

    func testPermissionStreamEmitsTransitionsOnly() async {
        let provider = SequenceAXPermissionProvider([.trusted, .trusted, .missing, .missing, .trusted])
        let stream = AXPermission.permissionStream(interval: 0.01, provider: provider)

        let task = Task {
            var statuses: [AXPermissionStatus] = []
            for await status in stream {
                statuses.append(status)
                if statuses.count == 2 {
                    break
                }
            }
            return statuses
        }

        let statuses = await task.value
        XCTAssertEqual(statuses, [.missing, .trusted])
    }
}

private final class SequenceAXPermissionProvider: AXPermissionStatusProviding, @unchecked Sendable {
    private let lock = NSLock()
    private var statuses: [AXPermissionStatus]

    init(_ statuses: [AXPermissionStatus]) {
        self.statuses = statuses
    }

    func currentAXPermissionStatus() -> AXPermissionStatus {
        lock.lock()
        defer {
            lock.unlock()
        }
        guard statuses.count > 1 else {
            return statuses[0]
        }
        return statuses.removeFirst()
    }
}
