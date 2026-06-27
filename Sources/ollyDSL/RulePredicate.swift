import CoreGraphics
import Foundation

/// Purpose: Selects a window-size comparison for rule predicates.
/// Parameters: Use `.smallerThan` or `.largerThan` with a target size.
/// Example: `windowSize(.smallerThan(CGSize(width: 800, height: 600)))`
/// See also: `windowSize(_:)`, `RulePredicate`.
public enum WindowSizePredicate: Codable, Equatable, Sendable {
    case smallerThan(CGSize)
    case largerThan(CGSize)

    public func matches(_ size: CGSize?) -> Bool {
        guard let size else {
            return false
        }
        switch self {
        case .smallerThan(let target):
            return size.width < target.width && size.height < target.height
        case .largerThan(let target):
            return size.width > target.width && size.height > target.height
        }
    }
}

/// Purpose: Represents a composable rule predicate tree.
/// Parameters: Build values with `bundleID`, `titleRegex`, `role`, `subrole`, `windowSize`, and operators.
/// Example: `bundleID("com.apple.Terminal") && role("AXWindow")`
/// See also: `RuleMatch`, `Rule`.
public struct RulePredicate: Codable, Equatable, Sendable {
    public let kind: Kind

    public init(_ kind: Kind) {
        self.kind = kind
    }

    public func matches(_ context: RuleContext) -> Bool {
        switch kind {
        case .bundleID(let bundleID):
            return context.bundleID == bundleID
        case .titleRegex(let pattern):
            return Self.matchesRegex(pattern, value: context.title)
        case .role(let role):
            return context.role == role
        case .subrole(let subrole):
            return context.subrole == subrole
        case .windowSize(let predicate):
            return predicate.matches(context.windowSize)
        case .parentBundleID(let bundleID):
            return context.parentBundleID == bundleID
        case .allOf(let predicates):
            return predicates.allSatisfy { $0.matches(context) }
        case .anyOf(let predicates):
            return predicates.contains { $0.matches(context) }
        case .not(let predicate):
            return !predicate.matches(context)
        }
    }

    private static func matchesRegex(_ pattern: String, value: String?) -> Bool {
        guard let expression = try? NSRegularExpression(pattern: pattern) else {
            return false
        }
        let value = value ?? ""
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return expression.firstMatch(in: value, range: range) != nil
    }

    public indirect enum Kind: Codable, Equatable, Sendable {
        case bundleID(String)
        case titleRegex(String)
        case role(String)
        case subrole(String)
        case windowSize(WindowSizePredicate)
        case parentBundleID(String)
        case allOf([RulePredicate])
        case anyOf([RulePredicate])
        case not(RulePredicate)
    }
}

/// Purpose: Matches windows owned by a bundle identifier.
/// Parameters: Pass the exact application bundle identifier.
/// Example: `bundleID("com.apple.Safari")`
/// See also: `RulePredicate`, `RuleMatch`.
public func bundleID(_ value: String) -> RulePredicate {
    RulePredicate(.bundleID(value))
}

/// Purpose: Matches windows whose title satisfies a regular expression.
/// Parameters: Pass an `NSRegularExpression` pattern string.
/// Example: `titleRegex("^Downloads")`
/// See also: `RulePredicate`, `RuleMatch`.
public func titleRegex(_ pattern: String) -> RulePredicate {
    RulePredicate(.titleRegex(pattern))
}

/// Purpose: Matches windows by Accessibility role.
/// Parameters: Pass the exact AX role string, such as `AXWindow`.
/// Example: `role("AXWindow")`
/// See also: `RulePredicate`, `RuleMatch`.
public func role(_ value: String) -> RulePredicate {
    RulePredicate(.role(value))
}

/// Purpose: Matches windows by Accessibility subrole.
/// Parameters: Pass the exact AX subrole string, such as `AXDialog`.
/// Example: `subrole("AXDialog")`
/// See also: `RulePredicate`, `RuleMatch`.
public func subrole(_ value: String) -> RulePredicate {
    RulePredicate(.subrole(value))
}

/// Purpose: Matches windows by their current frame size.
/// Parameters: Pass a `WindowSizePredicate` comparison.
/// Example: `windowSize(.largerThan(CGSize(width: 1200, height: 700)))`
/// See also: `WindowSizePredicate`, `RulePredicate`.
public func windowSize(_ predicate: WindowSizePredicate) -> RulePredicate {
    RulePredicate(.windowSize(predicate))
}

/// Purpose: Matches windows whose parent process has a bundle identifier.
/// Parameters: Pass the exact parent application bundle identifier.
/// Example: `parentBundleID("com.apple.dt.Xcode")`
/// See also: `RulePredicate`, `RuleMatch`.
public func parentBundleID(_ value: String) -> RulePredicate {
    RulePredicate(.parentBundleID(value))
}

/// Purpose: Combines two rule predicates and requires both to match.
/// Parameters: Put `&&` between two `RulePredicate` values.
/// Example: `bundleID("com.apple.Terminal") && role("AXWindow")`
/// See also: `RulePredicate`, `Rule`.
public func && (lhs: RulePredicate, rhs: RulePredicate) -> RulePredicate {
    RulePredicate(.allOf([lhs, rhs]))
}

/// Purpose: Combines two rule predicates and accepts either match.
/// Parameters: Put `||` between two `RulePredicate` values.
/// Example: `bundleID("com.apple.Safari") || parentBundleID("com.apple.dt.Xcode")`
/// See also: `RulePredicate`, `Rule`.
public func || (lhs: RulePredicate, rhs: RulePredicate) -> RulePredicate {
    RulePredicate(.anyOf([lhs, rhs]))
}

/// Purpose: Negates one rule predicate.
/// Parameters: Prefix a `RulePredicate` with `!`.
/// Example: `!subrole("AXDialog")`
/// See also: `RulePredicate`, `Rule`.
public prefix func ! (predicate: RulePredicate) -> RulePredicate {
    RulePredicate(.not(predicate))
}
