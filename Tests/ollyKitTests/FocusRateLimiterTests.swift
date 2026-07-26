import XCTest
@testable import ollyKit

final class FocusRateLimiterTests: XCTestCase {
    func testProgrammaticBurstIsRejectedWithinHumanInterval() async {
        let clock = ManualFocusClock()
        let limiter = FocusRateLimiter(now: { clock.now })

        let first = await limiter.shouldAccept(processID: 42, isUserInitiated: false)
        clock.advance(by: 0.01)
        let second = await limiter.shouldAccept(processID: 42, isUserInitiated: false)

        XCTAssertTrue(first)
        XCTAssertFalse(second)
    }

    func testHumanPacedProgrammaticFocusIsAccepted() async {
        let clock = ManualFocusClock()
        let limiter = FocusRateLimiter(now: { clock.now })

        let first = await limiter.shouldAccept(processID: 42, isUserInitiated: false)
        clock.advance(by: 0.09)
        let second = await limiter.shouldAccept(processID: 42, isUserInitiated: false)

        XCTAssertTrue(first)
        XCTAssertTrue(second)
    }

    func testRecentUserInputBypassesRateLimit() async {
        let clock = ManualFocusClock()
        let limiter = FocusRateLimiter(
            settings: FocusRateLimitSettings(maxEventsPerSecond: 1),
            now: { clock.now }
        )

        let first = await limiter.shouldAccept(processID: 42, isUserInitiated: false)
        let second = await limiter.shouldAccept(processID: 42, isUserInitiated: true)

        XCTAssertTrue(first)
        XCTAssertTrue(second)
    }
}

private final class ManualFocusClock: @unchecked Sendable {
    private let lock = NSLock()
    private var time: TimeInterval = 0

    var now: TimeInterval {
        lock.lock()
        defer { lock.unlock() }
        return time
    }

    func advance(by interval: TimeInterval) {
        lock.lock()
        time += interval
        lock.unlock()
    }
}
