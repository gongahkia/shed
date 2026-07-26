import CoreGraphics
import ollyCore

public struct Scratchpads: Codable, Equatable, Sendable {
    public let entries: [ScratchpadEntry]

    public init(entries: [ScratchpadEntry] = []) {
        self.entries = entries
    }

    public init(@ScratchpadsBuilder _ build: () -> [ScratchpadEntry]) {
        self.entries = build()
    }
}

@resultBuilder
public enum ScratchpadsBuilder {
    public static func buildBlock(_ components: [ScratchpadEntry]...) -> [ScratchpadEntry] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [ScratchpadEntry]?) -> [ScratchpadEntry] {
        component ?? []
    }

    public static func buildEither(first component: [ScratchpadEntry]) -> [ScratchpadEntry] {
        component
    }

    public static func buildEither(second component: [ScratchpadEntry]) -> [ScratchpadEntry] {
        component
    }

    public static func buildArray(_ components: [[ScratchpadEntry]]) -> [ScratchpadEntry] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: ScratchpadEntry) -> [ScratchpadEntry] {
        [expression]
    }
}

public enum ScratchpadDirective: Equatable, Sendable {
    case bundleID(String)
    case titleRegex(String)
    case role(String)
    case visible(Bool)
    case lastVisibleFrame(CGRect)
}

@resultBuilder
public enum ScratchpadBuilder {
    public static func buildBlock(_ components: [ScratchpadDirective]...) -> [ScratchpadDirective] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [ScratchpadDirective]?) -> [ScratchpadDirective] {
        component ?? []
    }

    public static func buildEither(first component: [ScratchpadDirective]) -> [ScratchpadDirective] {
        component
    }

    public static func buildEither(second component: [ScratchpadDirective]) -> [ScratchpadDirective] {
        component
    }

    public static func buildArray(_ components: [[ScratchpadDirective]]) -> [ScratchpadDirective] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: RulePredicate) -> [ScratchpadDirective] {
        expression.scratchpadDirectives
    }

    public static func buildExpression(_ expression: ScratchpadDirective) -> [ScratchpadDirective] {
        [expression]
    }
}

// swiftlint:disable:next identifier_name
public func Scratchpad(
    _ name: String,
    @ScratchpadBuilder _ build: () -> [ScratchpadDirective] = { [] }
) -> ScratchpadEntry {
    let directives = build()
    return ScratchpadEntry(
        name: name,
        bundleID: directives.lastBundleID,
        titleRegex: directives.lastTitleRegex,
        role: directives.lastRole,
        lastVisibleFrame: directives.lastVisibleFrame.map(WindowRecoveryFrame.init),
        isVisible: directives.lastVisible ?? true
    )
}

public func visible(_ value: Bool) -> ScratchpadDirective {
    .visible(value)
}

public func lastVisibleFrame(_ value: CGRect) -> ScratchpadDirective {
    .lastVisibleFrame(value)
}

private extension RulePredicate {
    var scratchpadDirectives: [ScratchpadDirective] {
        switch kind {
        case let .bundleID(value):
            return [.bundleID(value)]
        case let .titleRegex(value):
            return [.titleRegex(value)]
        case let .role(value):
            return [.role(value)]
        case let .allOf(predicates):
            return predicates.flatMap(\.scratchpadDirectives)
        default:
            return []
        }
    }
}

private extension Array where Element == ScratchpadDirective {
    var lastBundleID: String? {
        compactMap {
            if case let .bundleID(value) = $0 { return value }
            return nil
        }.last
    }

    var lastTitleRegex: String? {
        compactMap {
            if case let .titleRegex(value) = $0 { return value }
            return nil
        }.last
    }

    var lastRole: String? {
        compactMap {
            if case let .role(value) = $0 { return value }
            return nil
        }.last
    }

    var lastVisible: Bool? {
        compactMap {
            if case let .visible(value) = $0 { return value }
            return nil
        }.last
    }

    var lastVisibleFrame: CGRect? {
        compactMap {
            if case let .lastVisibleFrame(value) = $0 { return value }
            return nil
        }.last
    }
}
