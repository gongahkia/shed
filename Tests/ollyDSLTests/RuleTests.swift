import XCTest
import ollyCore
import ollyKit
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

    func testCooperativeAppsExtendNorthstarDefaults() {
        let apps = CooperativeApps {
            CooperativeApp("com.example.CustomOverlay")
        }

        XCTAssertTrue(apps.contains(bundleID: "com.felixkratz.SketchyBar"))
        XCTAssertTrue(apps.contains(bundleID: "org.hammerspoon.Hammerspoon"))
        XCTAssertTrue(apps.contains(bundleID: "com.example.CustomOverlay"))
    }

    func testCooperativeAppsCanReplaceNorthstarDefaults() {
        let apps = CooperativeApps(mode: .replace) {
            CooperativeApp("com.example.OnlyOverlay")
        }

        XCTAssertFalse(apps.contains(bundleID: "com.felixkratz.SketchyBar"))
        XCTAssertTrue(apps.contains(bundleID: "com.example.OnlyOverlay"))
    }

    func testCooperativeAppsYamlMirrorsDefaults() throws {
        let packageRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let yamlURL = packageRoot.appendingPathComponent("docs/cooperative-apps.yml")
        let yaml = try String(contentsOf: yamlURL, encoding: .utf8)
        let bundleIDs = yaml.components(separatedBy: .newlines).compactMap { line -> String? in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("bundle_id: ") else {
                return nil
            }
            return String(trimmed.dropFirst("bundle_id: ".count))
        }

        XCTAssertEqual(bundleIDs, CooperativeApps.defaultBundleIDs)
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

    func testConfigResolvesCooperativeAppsAsFloating() {
        let config = Config {
            CooperativeApps(mode: .replace) {
                CooperativeApp("com.example.Overlay")
            }
            Rules {
                Rule(
                    match: RuleMatch(bundleID: "com.example.Overlay"),
                    apply: RuleApply(engine: "bsp", floating: false)
                )
            }
        }

        let apply = config.resolvedApply(for: RuleContext(bundleID: "com.example.Overlay"))

        XCTAssertEqual(apply.engineOverride, "bsp")
        XCTAssertEqual(apply.floating, true)
    }

    func testConfigResolvesCooperativeWindowStateAsFloating() {
        let state = WindowState(
            id: 9,
            processID: 42,
            bundleID: "com.felixkratz.SketchyBar",
            displayID: 1,
            frame: .zero
        )

        let resolved = Config().resolvedWindowState(for: state)

        XCTAssertTrue(resolved.isFloating)
        XCTAssertEqual(resolved.bundleID, "com.felixkratz.SketchyBar")
    }

    func testRulesResolveLaterMatchesOverEarlierMatches() {
        let rules = Rules {
            Rule(match: RuleMatch(bundleID: "com.example.App"), apply: RuleApply(engine: "bsp"))
            Rule(match: RuleMatch(bundleID: "com.example.App"), apply: RuleApply(engine: "niri-scroll"))
        }

        let apply = rules.resolvedApply(for: RuleContext(bundleID: "com.example.App"))

        XCTAssertEqual(apply.engineOverride, "niri-scroll")
    }
}
