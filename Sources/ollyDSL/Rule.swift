import Foundation
import ollyCore

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

public struct RuleApply: Codable, Equatable, Sendable {
    public let tags: TagSet?
    public let engineOverride: LayoutEngineID?
    public let floating: Bool?

    public init(tags: TagSet? = nil, engine: LayoutEngineID? = nil, floating: Bool? = nil) {
        self.tags = tags
        self.engineOverride = engine
        self.floating = floating
    }
}

public struct Rule: Codable, Equatable, Sendable {
    public let match: RuleMatch
    public let apply: RuleApply

    public init(match: RuleMatch, apply: RuleApply) {
        self.match = match
        self.apply = apply
    }
}

public struct Rules: Codable, Equatable, Sendable {
    public let rules: [Rule]

    public init(_ rules: [Rule] = []) {
        self.rules = rules
    }

    public init(@RuleBuilder _ build: () -> [Rule]) {
        self.rules = build()
    }
}

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
