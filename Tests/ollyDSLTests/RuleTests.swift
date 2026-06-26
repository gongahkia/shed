import XCTest
import ollyCore
@testable import ollyDSL

final class RuleTests: XCTestCase {
    func testRuleBuilderCollectsRules() throws {
        let tag = try Tag(index: 2)
        let first = Rule(
            match: RuleMatch(bundleID: "com.example.Terminal", titleRegex: "^build", role: "AXWindow"),
            apply: RuleApply(tags: TagSet(tag), engine: "bsp", floating: false)
        )
        let second = Rule(
            match: RuleMatch(subrole: "AXDialog"),
            apply: RuleApply(floating: true)
        )

        let rules = Rules {
            first
            second
        }

        XCTAssertEqual(rules.rules, [first, second])
    }

    func testConfigStoresRuleSection() {
        let rule = Rule(
            match: RuleMatch(bundleID: "com.example.Chat"),
            apply: RuleApply(engine: "floating", floating: true)
        )
        let config = Config {
            Rules {
                rule
            }
        }

        XCTAssertEqual(config.rules.rules, [rule])
    }

    func testRuleMatchRequiresAllSpecifiedPredicates() {
        let match = RuleMatch(
            bundleID: "com.example.Editor",
            titleRegex: "^README",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )
        let matching = RuleContext(
            bundleID: "com.example.Editor",
            title: "README.md",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )
        let wrongTitle = RuleContext(
            bundleID: "com.example.Editor",
            title: "TODO.md",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )

        XCTAssertTrue(match.matches(matching))
        XCTAssertFalse(match.matches(wrongTitle))
    }

    func testRuleMatchUsesMissingPredicatesAsWildcards() {
        let match = RuleMatch(bundleID: "com.example.Editor")
        let context = RuleContext(
            bundleID: "com.example.Editor",
            title: "Any",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )

        XCTAssertTrue(match.matches(context))
    }

    func testInvalidTitleRegexDoesNotMatch() {
        let match = RuleMatch(titleRegex: "[")
        let context = RuleContext(title: "README.md")

        XCTAssertFalse(match.matches(context))
    }
}
