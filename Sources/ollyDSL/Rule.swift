import Foundation
import ollyCore
import ollyKit

/// Purpose: Describes the window properties a DSL rule must match.
/// Parameters: Provide optional bundle ID, title regex, role, or subrole predicates.
/// Example: `RuleMatch(bundleID: "com.apple.Terminal", titleRegex: "ssh")`
/// See also: `Rule`, `RuleContext`.
public struct RuleMatch: Codable, Equatable, Sendable {
    public let bundleID: String?
    public let titleRegex: String?
    public let role: String?
    public let subrole: String?

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
    }

    public func matches(_ context: RuleContext) -> Bool {
        if let bundleID, bundleID != context.bundleID {
            return false
        }
        if let titleRegex, !matchesTitleRegex(titleRegex, title: context.title) {
            return false
        }
        if let role, role != context.role {
            return false
        }
        if let subrole, subrole != context.subrole {
            return false
        }
        return true
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
/// Parameters: Provide bundle ID, title, role, and subrole values from a window snapshot.
/// Example: `RuleContext(bundleID: "com.apple.finder", title: "Downloads")`
/// See also: `RuleMatch`, `Rules`.
public struct RuleContext: Codable, Equatable, Sendable {
    public let bundleID: String?
    public let title: String?
    public let role: String?
    public let subrole: String?

    public init(bundleID: String? = nil, title: String? = nil, role: String? = nil, subrole: String? = nil) {
        self.bundleID = bundleID
        self.title = title
        self.role = role
        self.subrole = subrole
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

    public init(tags: TagSet? = nil, engine: LayoutEngineID? = nil, floating: Bool? = nil) {
        self.tags = tags
        self.engineOverride = engine
        self.floating = floating
    }

    public func merging(_ override: RuleApply) -> RuleApply {
        RuleApply(
            tags: override.tags ?? tags,
            engine: override.engineOverride ?? engineOverride,
            floating: override.floating ?? floating
        )
    }
}

/// Purpose: Couples one `RuleMatch` predicate with one `RuleApply` payload.
/// Parameters: Pass a match object and the changes to apply when it matches.
/// Example: `Rule(match: RuleMatch(bundleID: "com.slack.Slack"), apply: RuleApply(floating: true))`
/// See also: `Rules`, `RuleBuilder`.
public struct Rule: Codable, Equatable, Sendable {
    public let match: RuleMatch
    public let apply: RuleApply

    public init(match: RuleMatch, apply: RuleApply) {
        self.match = match
        self.apply = apply
    }
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
        rules.reduce(RuleApply()) { result, rule in
            rule.match.matches(context) ? result.merging(rule.apply) : result
        }
    }
}

public extension Config {
    func resolvedApply(for context: RuleContext) -> RuleApply {
        var apply = rules.resolvedApply(for: context)
        if cooperativeApps.contains(bundleID: context.bundleID) {
            apply = apply.merging(RuleApply(floating: true))
        }
        return apply
    }

    func resolvedWindowState(for state: WindowState) -> WindowState {
        let apply = resolvedApply(
            for: RuleContext(
                bundleID: state.bundleID,
                title: state.title,
                role: state.role,
                subrole: state.subrole
            )
        )
        return WindowState(
            id: state.id,
            processID: state.processID,
            bundleID: state.bundleID,
            displayID: state.displayID,
            tagMask: apply.tags?.rawValue ?? state.tagMask,
            isFloating: apply.floating ?? state.isFloating,
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

    public static func buildExpression(_ expression: Rule) -> [Rule] {
        [expression]
    }
}
