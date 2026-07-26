import XCTest
import ollyIPC
import ollyKit
@testable import ollyctl

final class OllyExplainCommandTests: XCTestCase {
    func testRuleIDParserAcceptsUUID() throws {
        let ruleID = try XCTUnwrap(UUID(uuidString: "11111111-2222-3333-4444-555555555555"))

        XCTAssertEqual(try parseRuleID(ruleID.uuidString), ruleID)
    }

    func testRuleIDParserRejectsNonUUID() {
        XCTAssertThrowsError(try parseRuleID("not-a-uuid"))
    }

    func testRuleExplanationPrettyRenderer() throws {
        let ruleID = try XCTUnwrap(UUID(uuidString: "11111111-2222-3333-8444-555555555555"))
        let explanation = IPCRuleExplanation(
            windowID: 42,
            traces: [
                IPCRuleMatchTrace(
                    ruleID: ruleID,
                    matched: true,
                    bundleIDMatched: true,
                    roleMatched: false
                )
            ],
            finalApply: IPCRuleApply(
                tagMask: 5,
                engineOverride: LayoutEngineID(rawValue: "bsp"),
                floating: true
            )
        )

        XCTAssertEqual(
            OllyCtlRuleExplanationRenderer().render(explanation),
            """
            window 42
            final tags=0,2 engine=bsp floating=true
            rule 11111111-2222-3333-8444-555555555555 matched bundleID=yes title=- role=no subrole=- predicate=-
            """
        )
    }
}
