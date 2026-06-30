import XCTest
import ollyDSL
@testable import ollyRuntime

final class OllyRuntimeReduceMotionTests: XCTestCase {
    func testRespectSystemReduceMotionReturnsZeroDuration() {
        let animation = Animation(duration: 200.ms, curve: .easeOut, reduceMotion: .respectSystem)

        let duration = LayoutAnimationPolicy.duration(
            animation: animation,
            systemReduceMotion: true,
            isElectron: false
        )

        XCTAssertEqual(duration, 0)
    }

    func testAlwaysAnimateIgnoresSystemReduceMotion() {
        let animation = Animation(duration: 200.ms, curve: .easeOut, reduceMotion: .alwaysAnimate)

        let duration = LayoutAnimationPolicy.duration(
            animation: animation,
            systemReduceMotion: true,
            isElectron: false
        )

        XCTAssertEqual(duration, 0.2, accuracy: 0.0001)
    }

    func testElectronWindowReturnsZeroDuration() {
        let animation = Animation(duration: 200.ms, curve: .easeOut, reduceMotion: .alwaysAnimate)

        let duration = LayoutAnimationPolicy.duration(
            animation: animation,
            systemReduceMotion: false,
            isElectron: true
        )

        XCTAssertEqual(duration, 0)
    }

    func testReduceMotionStateCachesUntilInvalidated() async {
        let counter = await MainActor.run { ReduceMotionCounter() }
        let state = ReduceMotionState {
            counter.next()
        }

        let first = await state.current()
        let second = await state.current()
        await state.invalidate()
        let third = await state.current()
        let count = await counter.readCount()

        XCTAssertFalse(first)
        XCTAssertFalse(second)
        XCTAssertTrue(third)
        XCTAssertEqual(count, 2)
    }
}

@MainActor
private final class ReduceMotionCounter {
    private(set) var count = 0

    func next() -> Bool {
        count += 1
        return count > 1
    }

    func readCount() -> Int {
        count
    }
}
