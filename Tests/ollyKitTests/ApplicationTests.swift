import AppKit
import XCTest
@testable import ollyKit

final class ApplicationTests: XCTestCase {
    func testApplicationWrapsRunningApplication() throws {
        let runningApplication = try visibleRunningApplication()
        let application = try XCTUnwrap(Application(runningApplication: runningApplication))
        XCTAssertEqual(application.processID, runningApplication.processIdentifier)
    }

    func testApplicationMonitorExtractsApplicationFromWorkspaceNotification() throws {
        let runningApplication = try visibleRunningApplication()
        let notification = Notification(
            name: NSWorkspace.didLaunchApplicationNotification,
            object: nil,
            userInfo: [NSWorkspace.applicationUserInfoKey: runningApplication]
        )

        let application = try XCTUnwrap(ApplicationMonitor.application(from: notification))
        XCTAssertEqual(application.processID, runningApplication.processIdentifier)
    }

    private func visibleRunningApplication() throws -> NSRunningApplication {
        guard let runningApplication = NSWorkspace.shared.runningApplications.first(where: { $0.processIdentifier >= 0 }) else {
            throw XCTSkip("No visible NSRunningApplication available")
        }
        return runningApplication
    }
}
