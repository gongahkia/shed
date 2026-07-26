import CoreGraphics
import CryptoKit
import Foundation
import ollyCore
import ollyKit

/// Purpose: Describes the window properties a DSL rule must match.
/// Parameters: Provide optional field matches or one composed `RulePredicate`.
/// Example: `RuleMatch(bundleID: "com.apple.Terminal", role: "AXWindow")`
/// See also: `Rule`, `RulePredicate`.
public struct RuleMatch: Codable, Equatable, Sendable {
    public let bundleID: String?
    public let titleRegex: String?
    public let role: String?
    public let subrole: String?
    public let predicate: RulePredicate?

    public init(
        bundleID: String? = nil,
        titleRegex: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.bundleID = bundleID
        self.titleRegex = titleRegex
        self.role = role
        self.subrole = subrole
        self.predicate = nil
    }

    public init(predicate: RulePredicate) {
        self.bundleID = nil
        self.titleRegex = nil
        self.role = nil
        self.subrole = nil
        self.predicate = predicate
    }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(bundleID: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(titleRegex: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(role: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(subrole: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(bundleID: String, titleRegex: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(bundleID: String, role: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(bundleID: String, subrole: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(titleRegex: String, role: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(titleRegex: String, subrole: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(role: String, subrole: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(bundleID: String, titleRegex: String, role: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(bundleID: String, titleRegex: String, subrole: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(bundleID: String, role: String, subrole: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(titleRegex: String, role: String, subrole: String, predicate: RulePredicate) { fatalError() }

    @available(*, unavailable, message: "ambiguous-rule: use RuleMatch fields or a RulePredicate, not both")
    public init(bundleID: String, titleRegex: String, role: String, subrole: String, predicate: RulePredicate) {
        fatalError()
    }

    public func matches(_ context: RuleContext) -> Bool {
        trace(context, ruleID: UUID()).matched
    }

    public func match(_ context: RuleContext, ruleID: UUID) -> RuleMatchTrace? {
        let trace = trace(context, ruleID: ruleID)
        return trace.matched ? trace : nil
    }

    public func trace(_ context: RuleContext, ruleID: UUID) -> RuleMatchTrace {
        RuleMatchTrace(
            ruleID: ruleID,
            bundleIDMatched: bundleID.map { $0 == context.bundleID },
            titleMatched: titleRegex.map { matchesTitleRegex($0, title: context.title) },
            roleMatched: role.map { $0 == context.role },
            subroleMatched: subrole.map { $0 == context.subrole },
            predicateMatched: predicate.map { $0.matches(context) }
        )
    }

    private func matchesTitleRegex(_ pattern: String, title: String?) -> Bool {
        guard let expression = try? NSRegularExpression(pattern: pattern) else {
            return false
        }
        let title = title ?? ""
        let range = NSRange(title.startIndex..<title.endIndex, in: title)
        return expression.firstMatch(in: title, range: range) != nil
    }
}

/// Purpose: Carries runtime window metadata used to evaluate rule matches.
/// Parameters: Provide bundle ID, title, role, subrole, parent bundle ID, and window size values.
/// Example: `RuleContext(bundleID: "com.apple.finder", windowSize: CGSize(width: 500, height: 400))`
/// See also: `RuleMatch`, `Rules`.
public struct RuleContext: Codable, Equatable, Sendable {
    public let bundleID: String?
    public let title: String?
    public let role: String?
    public let subrole: String?
    public let parentBundleID: String?
    public let windowSize: CGSize?

    public init(
        bundleID: String? = nil,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil,
        parentBundleID: String? = nil,
        windowSize: CGSize? = nil
    ) {
        self.bundleID = bundleID
        self.title = title
        self.role = role
        self.subrole = subrole
        self.parentBundleID = parentBundleID
        self.windowSize = windowSize
    }
}

/// Purpose: Declares the tag, engine, and floating changes applied by matching rules.
/// Parameters: Provide optional tags, engine override, or floating state.
/// Example: `RuleApply(tags: TagSet(try Tag(index: 2)), engine: BSPLayoutEngine.engineID)`
/// See also: `Rule`, `Rules`.
public struct RuleApply: Codable, Equatable, Sendable {
    public let tags: TagSet?
    public let engineOverride: LayoutEngineID?
    public let floating: Bool?
    public let sticky: Bool?
    public let pinned: Bool?

    public init(
        tags: TagSet? = nil,
        engine: LayoutEngineID? = nil,
        floating: Bool? = nil,
        sticky: Bool? = nil,
        pinned: Bool? = nil
    ) {
        self.tags = tags
        self.engineOverride = engine
        self.floating = floating
        self.sticky = sticky
        self.pinned = pinned
    }

    @available(*, unavailable, message: "unknown-engine-id: use a typed LayoutEngineID such as .bsp")
    public init(
        tags: TagSet? = nil,
        engine: String,
        floating: Bool? = nil,
        sticky: Bool? = nil,
        pinned: Bool? = nil
    ) {
        fatalError()
    }

    public func merging(_ override: RuleApply) -> RuleApply {
        RuleApply(
            tags: override.tags ?? tags,
            engine: override.engineOverride ?? engineOverride,
            floating: override.floating ?? floating,
            sticky: override.sticky ?? sticky,
            pinned: override.pinned ?? pinned
        )
    }
}

/// Purpose: Couples one `RuleMatch` predicate with one `RuleApply` payload.
/// Parameters: Pass a match object and the changes to apply when it matches.
/// Example: `Rule(match: RuleMatch(bundleID: "com.slack.Slack"), apply: RuleApply(floating: true))`
/// See also: `Rules`, `RuleBuilder`.
public struct Rule: Codable, Equatable, Sendable {
    public let id: UUID
    public let label: String?
    public let match: RuleMatch
    public let apply: RuleApply
    public let rawHandler: RawDSLBlock<Void>?

    public init(match: RuleMatch, apply: RuleApply, label: String? = nil, rawHandler: RawDSLBlock<Void>? = nil) {
        if let label {
            precondition(!label.isEmpty)
        }
        self.id = Self.stableID(match: match, apply: apply, label: label)
        self.label = label
        self.match = match
        self.apply = apply
        self.rawHandler = rawHandler
    }

    public init(
        match predicate: RulePredicate,
        apply: RuleApply,
        label: String? = nil,
        rawHandler: RawDSLBlock<Void>? = nil
    ) {
        self.init(match: RuleMatch(predicate: predicate), apply: apply, label: label, rawHandler: rawHandler)
    }

    public static func raw(
        match: RuleMatch = RuleMatch(),
        apply: RuleApply = RuleApply(),
        label: String = "raw",
        _ body: @escaping RawDSLHandler
    ) -> Rule {
        Rule(match: match, apply: apply, label: label, rawHandler: RawDSLBlock(label, body))
    }

    public static func raw(
        match predicate: RulePredicate,
        apply: RuleApply = RuleApply(),
        label: String = "raw",
        _ body: @escaping RawDSLHandler
    ) -> Rule {
        Rule(match: predicate, apply: apply, label: label, rawHandler: RawDSLBlock(label, body))
    }

    public func runRaw(context: RawDSLContext) {
        rawHandler?(context)
    }

    public static func == (lhs: Rule, rhs: Rule) -> Bool {
        lhs.match == rhs.match && lhs.apply == rhs.apply && lhs.label == rhs.label
    }

    enum CodingKeys: String, CodingKey {
        case label
        case match
        case apply
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            match: try container.decode(RuleMatch.self, forKey: .match),
            apply: try container.decode(RuleApply.self, forKey: .apply),
            label: try container.decodeIfPresent(String.self, forKey: .label)
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(label, forKey: .label)
        try container.encode(match, forKey: .match)
        try container.encode(apply, forKey: .apply)
    }

    private static func stableID(match: RuleMatch, apply: RuleApply, label: String?) -> UUID {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let identity = RuleIdentity(match: match, apply: apply, label: label)
        guard let data = try? encoder.encode(identity) else {
            preconditionFailure("rule identity must encode")
        }
        let digest = SHA256.hash(data: data)
        var bytes = Array(digest.prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}

private struct RuleIdentity: Codable {
    let match: RuleMatch
    let apply: RuleApply
    let label: String?
}

/// Purpose: Groups rule declarations and resolves their cumulative apply payload.
/// Parameters: Pass an array of `Rule` values or use `@RuleBuilder`.
/// Example: `Rules { Rule(match: RuleMatch(role: "AXDialog"), apply: RuleApply(floating: true)) }`
/// See also: `Rule`, `RuleApply`.
public struct Rules: Codable, Equatable, Sendable {
    public let rules: [Rule]

    public init(_ rules: [Rule] = []) {
        self.rules = rules
    }

    public init(@RuleBuilder _ build: () -> [Rule]) {
        self.rules = build()
    }

    public func resolvedApply(for context: RuleContext) -> RuleApply {
        resolvedExplanation(for: context).finalApply
    }

    public func resolvedExplanation(for context: RuleContext) -> RuleExplanation {
        var apply = RuleApply()
        let traces = rules.map { rule in
            let trace = rule.match.trace(context, ruleID: rule.id)
            if trace.matched {
                apply = apply.merging(rule.apply)
            }
            return trace
        }
        return RuleExplanation(traces: traces, finalApply: apply)
    }
}

public extension Config {
    func resolvedExplanation(for context: RuleContext) -> RuleExplanation {
        let explanation = rules.resolvedExplanation(for: context)
        var apply = explanation.finalApply
        if cooperativeApps.behavior(for: context.bundleID) != nil {
            apply = apply.merging(RuleApply(floating: true))
        }
        return RuleExplanation(traces: explanation.traces, finalApply: apply)
    }

    func resolvedApply(for context: RuleContext) -> RuleApply {
        resolvedExplanation(for: context).finalApply
    }

    func resolvedWindowState(for state: WindowState) -> WindowState {
        let apply = resolvedApply(
            for: RuleContext(
                bundleID: state.bundleID,
                title: state.title,
                role: state.role,
                subrole: state.subrole,
                windowSize: state.frame.size
            )
        )
        return WindowState(
            id: state.id,
            processID: state.processID,
            bundleID: state.bundleID,
            displayID: state.displayID,
            tagMask: apply.tags?.rawValue ?? state.tagMask,
            isFloating: apply.floating ?? state.isFloating,
            isSticky: apply.sticky ?? state.isSticky,
            isPinned: apply.pinned ?? state.isPinned,
            isFullscreen: state.isFullscreen,
            isOffSpace: state.isOffSpace,
            engineOverride: apply.engineOverride ?? state.engineOverride,
            layoutOrder: state.layoutOrder,
            frame: state.frame,
            title: state.title,
            role: state.role,
            subrole: state.subrole
        )
    }
}

/// Purpose: Builds rule declarations inside `Rules { ... }`.
/// Parameters: Accepts `Rule` expressions, arrays, and conditional branches.
/// Example: `Rules { Rule(match: RuleMatch(subrole: "AXSystemDialog"), apply: RuleApply(floating: true)) }`
/// See also: `Rules`, `Rule`.
@resultBuilder
public enum RuleBuilder {
    public static func buildBlock(_ components: [Rule]...) -> [Rule] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [Rule]?) -> [Rule] {
        component ?? []
    }

    public static func buildEither(first component: [Rule]) -> [Rule] {
        component
    }

    public static func buildEither(second component: [Rule]) -> [Rule] {
        component
    }

    public static func buildArray(_ components: [[Rule]]) -> [Rule] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: Rule) -> [Rule] { [expression] }
}
