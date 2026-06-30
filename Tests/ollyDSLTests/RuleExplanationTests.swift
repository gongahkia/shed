import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyDSL

final class RuleExplanationTests: XCTestCase {
    func testRulesExplainEveryTraceInDeclarationOrder() throws {
        let tag = try Tag(index: 3)
        let first = Rule(
            match: RuleMatch(bundleID: "com.example.Other"),
            apply: RuleApply(engine: .floating)
        )
        let second = Rule(
            match: RuleMatch(bundleID: "com.example.App", titleRegex: "^Build", role: "AXWindow"),
            apply: RuleApply(tags: TagSet(tag), engine: .bsp, floating: false)
        )
        let third = Rule(
            match: subrole("AXDialog") || windowSize(.largerThan(CGSize(width: 200, height: 200))),
            apply: RuleApply(floating: true)
        )
        let rules = Rules {
            first
            second
            third
        }

        let explanation = rules.resolvedExplanation(for: RuleContext(
            bundleID: "com.example.App",
            title: "Build Log",
            role: "AXWindow",
            windowSize: CGSize(width: 300, height: 240)
        ))

        XCTAssertEqual(explanation.traces.map(\.ruleID), [first.id, second.id, third.id])
        XCTAssertEqual(explanation.traces.map(\.matched), [false, true, true])
        XCTAssertEqual(explanation.traces[0].bundleIDMatched, false)
        XCTAssertEqual(explanation.traces[1].bundleIDMatched, true)
        XCTAssertEqual(explanation.traces[1].titleMatched, true)
        XCTAssertEqual(explanation.traces[1].roleMatched, true)
        XCTAssertEqual(explanation.traces[2].predicateMatched, true)
        XCTAssertEqual(explanation.finalApply.tags, TagSet(tag))
        XCTAssertEqual(explanation.finalApply.engineOverride, .bsp)
        XCTAssertEqual(explanation.finalApply.floating, true)
    }

    func testRuleIDIsStableAndIncludesRawLabel() {
        let first = Rule.raw(match: RuleMatch(bundleID: "com.example.App"), label: "one") { _ in }
        let second = Rule.raw(match: RuleMatch(bundleID: "com.example.App"), label: "two") { _ in }
        let rebuilt = Rule.raw(match: RuleMatch(bundleID: "com.example.App"), label: "one") { _ in }

        XCTAssertEqual(first.id, rebuilt.id)
        XCTAssertNotEqual(first.id, second.id)
    }
}
