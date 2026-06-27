import XCTest
@testable import ollyDSL

final class GestureTests: XCTestCase {
    func testGestureBuilderCollectsBindings() {
        let gestures = Gestures {
            fourFingerHorizontal(.scrollColumns)
            fourFingerVertical(.switchTags)
        }

        XCTAssertEqual(
            gestures.bindings,
            [
                GestureBinding(.fourFingerHorizontal, .scrollColumns),
                GestureBinding(.fourFingerVertical, .switchTags)
            ]
        )
    }

    func testGestureCommandsResolveByAxis() {
        let gestures = Gestures {
            fourFingerHorizontal(.scrollColumns)
            fourFingerVertical(.switchTags)
        }

        XCTAssertEqual(
            gestures.command(for: .fourFingerHorizontal, motion: .left),
            .scrollColumns(.left)
        )
        XCTAssertEqual(
            gestures.command(for: .fourFingerVertical, motion: .downward),
            .switchTags(.next)
        )
        XCTAssertNil(gestures.command(for: .fourFingerHorizontal, motion: .upward))
    }

    func testLaterGestureBindingsOverrideEarlierBindings() {
        let gestures = Gestures {
            fourFingerHorizontal(.scrollColumns)
            fourFingerHorizontal(.action(.cycleEngine))
        }

        XCTAssertEqual(
            gestures.command(for: .fourFingerHorizontal, motion: .right),
            .action(.cycleEngine)
        )
    }

    func testGesturesCodableRoundTrips() throws {
        let gestures = Gestures {
            fourFingerHorizontal(.scrollColumns)
            fourFingerVertical(.switchTags)
        }

        let data = try JSONEncoder().encode(gestures)
        let decoded = try JSONDecoder().decode(Gestures.self, from: data)

        XCTAssertEqual(decoded, gestures)
    }
}
