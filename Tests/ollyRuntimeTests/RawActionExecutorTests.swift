import XCTest
import ollyDSL
import ollyIPC
@testable import ollyRuntime

final class RawActionExecutorTests: XCTestCase {
    func testRunInjectsEnvironment() async {
        let executor = RawActionExecutor()
        let action = ShellAction("printf '%s' \"$OLLY_EVENT:$OLLY_TAG\"", label: "env")

        let event = await executor.run(
            action,
            policy: .allowAll,
            environment: ["OLLY_EVENT": "test", "OLLY_TAG": "4"]
        )

        XCTAssertEqual(event.status, .completed)
        XCTAssertEqual(event.exit, 0)
        XCTAssertEqual(event.stdoutHead, "test:4")
    }

    func testTimeoutKillsChild() async {
        let executor = RawActionExecutor()
        let action = ShellAction("sleep 2", label: "slow", timeoutMs: 50)

        let event = await executor.run(action, policy: .allowAll)

        XCTAssertEqual(event.status, .timedOut)
        XCTAssertLessThan(event.elapsedMs, 1_800)
    }

    func testAllowlistDeniesMissingLabel() async {
        let executor = RawActionExecutor()
        let action = ShellAction("printf no", label: "blocked")

        let event = await executor.run(action, policy: .allow(["allowed"]))

        XCTAssertEqual(event.status, .denied)
        XCTAssertNil(event.exit)
        XCTAssertEqual(event.stderrHead, "not allowed")
    }

    func testStdoutHeadTruncatesToFourKilobytes() async {
        let executor = RawActionExecutor()
        let action = ShellAction("printf '%05000d' 0", label: "large")

        let event = await executor.run(action, policy: .allowAll)

        XCTAssertEqual(event.status, .completed)
        XCTAssertEqual(event.stdoutHead.count, 4_096)
    }
}
