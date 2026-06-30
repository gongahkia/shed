import XCTest
import ollyCore
import ollyKit
@testable import ollyDSL

final class RuleTests: XCTestCase {
    func testRuleBuilderCollectsRules() throws {
        let tag = try Tag(index: 2)
        let first = Rule(
            match: RuleMatch(bundleID: "com.example.Terminal", titleRegex: "^build", role: "AXWindow"),
            apply: RuleApply(tags: TagSet(tag), engine: .bsp, floating: false)
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
            apply: RuleApply(engine: .floating, floating: true)
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

    func testRulePredicateBuildersComposeWithAndOrAndNot() {
        let predicate = (
            bundleID("com.example.Editor") &&
                titleRegex("^README") &&
                role("AXWindow") &&
                windowSize(.largerThan(CGSize(width: 400, height: 300)))
        ) || (
            parentBundleID("com.example.HelperHost") &&
                !subrole("AXDialog")
        )

        XCTAssertTrue(
            predicate.matches(
                RuleContext(
                    bundleID: "com.example.Editor",
                    title: "README.md",
                    role: "AXWindow",
                    subrole: "AXStandardWindow",
                    windowSize: CGSize(width: 800, height: 600)
                )
            )
        )
        XCTAssertTrue(
            predicate.matches(
                RuleContext(
                    subrole: "AXStandardWindow",
                    parentBundleID: "com.example.HelperHost"
                )
            )
        )
        XCTAssertFalse(
            predicate.matches(
                RuleContext(
                    bundleID: "com.example.Editor",
                    title: "README.md",
                    role: "AXWindow",
                    subrole: "AXDialog",
                    windowSize: CGSize(width: 200, height: 150)
                )
            )
        )
    }

    func testRuleCanUsePredicateBuildersDirectly() {
        let rules = Rules {
            Rule(
                match: bundleID("com.example.Editor") && windowSize(.smallerThan(CGSize(width: 500, height: 500))),
                apply: RuleApply(engine: .floating, floating: true)
            )
        }

        let apply = rules.resolvedApply(
            for: RuleContext(
                bundleID: "com.example.Editor",
                windowSize: CGSize(width: 300, height: 400)
            )
        )

        XCTAssertEqual(apply.engineOverride, .floating)
        XCTAssertEqual(apply.floating, true)
    }

    func testRulePredicateCodableRoundTrips() throws {
        let rule = Rule(
            match: parentBundleID("com.example.Host") || subrole("AXDialog"),
            apply: RuleApply(engine: .floating, floating: true)
        )

        let data = try JSONEncoder().encode(rule)
        let decoded = try JSONDecoder().decode(Rule.self, from: data)

        XCTAssertEqual(decoded, rule)
    }

    func testConfigResolvesCooperativeAppsAsFloating() {
        let config = Config {
            CooperativeApps(mode: .replace) {
                CooperativeApp("com.example.Overlay")
            }
            Rules {
                Rule(
                    match: RuleMatch(bundleID: "com.example.Overlay"),
                    apply: RuleApply(engine: .bsp, floating: false)
                )
            }
        }

        let apply = config.resolvedApply(for: RuleContext(bundleID: "com.example.Overlay"))

        XCTAssertEqual(apply.engineOverride, .bsp)
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

    func testConfigResolvesStickyPinnedWindowState() {
        let state = WindowState(
            id: 9,
            processID: 42,
            bundleID: "com.example.App",
            displayID: 1,
            frame: .zero
        )
        let config = Config {
            Rules {
                Rule(
                    match: RuleMatch(bundleID: "com.example.App"),
                    apply: RuleApply(sticky: true, pinned: true)
                )
            }
        }

        let resolved = config.resolvedWindowState(for: state)

        XCTAssertTrue(resolved.isSticky)
        XCTAssertTrue(resolved.isPinned)
    }

    func testConfigResolvesEngineOverrideWindowState() {
        let state = WindowState(
            id: 9,
            processID: 42,
            bundleID: "com.tinyspeck.slackmacgap",
            displayID: 1,
            frame: .zero
        )
        let config = Config {
            Rules {
                Rule(
                    match: RuleMatch(bundleID: "com.tinyspeck.slackmacgap"),
                    apply: RuleApply(engine: .floating)
                )
            }
        }

        let resolved = config.resolvedWindowState(for: state)

        XCTAssertEqual(resolved.engineOverride, .floating)
        XCTAssertFalse(resolved.isFloating)
    }

    func testRuleApplyMergesStickyPinnedOverrides() {
        let base = RuleApply(sticky: false, pinned: true)
        let override = RuleApply(sticky: true)

        let merged = base.merging(override)

        XCTAssertEqual(merged.sticky, true)
        XCTAssertEqual(merged.pinned, true)
    }

    func testRulesResolveLaterMatchesOverEarlierMatches() {
        let rules = Rules {
            Rule(match: RuleMatch(bundleID: "com.example.App"), apply: RuleApply(engine: .bsp))
            Rule(match: RuleMatch(bundleID: "com.example.App"), apply: RuleApply(engine: .niriScroll))
        }

        let apply = rules.resolvedApply(for: RuleContext(bundleID: "com.example.App"))

        XCTAssertEqual(apply.engineOverride, .niriScroll)
    }
}
