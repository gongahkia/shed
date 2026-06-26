import Foundation
import XCTest
import ollyIPC

final class IPCSocketPathTests: XCTestCase {
    func testResolvedPathUsesXDGRuntimeDirectoryWhenPresent() {
        let path = IPCSocketPath.resolved(
            environment: ["XDG_RUNTIME_DIR": "/tmp/runtime-user"],
            homeDirectory: URL(fileURLWithPath: "/Users/example")
        )

        XCTAssertEqual(path.rawValue, "/tmp/runtime-user/olly.sock")
    }

    func testResolvedPathFallsBackToConfigDirectory() {
        let path = IPCSocketPath.resolved(
            environment: [:],
            homeDirectory: URL(fileURLWithPath: "/Users/example")
        )

        XCTAssertEqual(path.rawValue, "/Users/example/.config/olly/olly.sock")
    }
}
