import XCTest
import ollyKit
@testable import ollyCore

final class LayoutEnginePolicyTests: XCTestCase {
    func testResolvedEngineUsesLowestActiveTagIndex() throws {
        let lowerTag = try Tag(index: 1)
        let higherTag = try Tag(index: 3)
        let lowerEngineID = LayoutEngineID(rawValue: "lower")
        let higherEngineID = LayoutEngineID(rawValue: "higher")

        let engineID = LayoutEnginePolicy.resolvedEngineID(
            activeTags: TagSet([higherTag, lowerTag]),
            tagToEngine: [
                higherTag: higherEngineID,
                lowerTag: lowerEngineID
            ]
        )

        XCTAssertEqual(engineID, lowerEngineID)
    }
}
