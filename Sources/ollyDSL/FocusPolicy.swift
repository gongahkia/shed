/// Purpose: Represents one focus-stealing policy builder directive.
/// Parameters: Use allowlist, rate-limit, or human-interval builder helpers.
/// Example: `allowStealingFor("com.apple.Terminal")`
/// See also: `FocusPolicy`, `FocusPolicyBuilder`.
public enum FocusPolicyDirective: Equatable, Sendable {
    case allowStealingFor(String)
    case maxEventsPerSecond(Int)
    case minHumanIntervalMilliseconds(Int)
}

/// Purpose: Configures programmatic focus-stealing throttles and bundle allowlists.
/// Parameters: Provide allowed bundle IDs, events per second, and minimum human interval in milliseconds.
/// Example: `FocusPolicy { allowStealingFor("com.apple.Terminal"); maxEventsPerSecond(20) }`
/// See also: `FocusPolicyBuilder`, `ConfigSection`.
public struct FocusPolicy: Codable, Equatable, Sendable {
    public let allowedBundleIDs: [String]
    public let maxEventsPerSecond: Int
    public let minHumanIntervalMilliseconds: Int

    public init(
        allowedBundleIDs: [String] = [],
        maxEventsPerSecond: Int = 20,
        minHumanIntervalMilliseconds: Int = 80
    ) {
        self.allowedBundleIDs = allowedBundleIDs.sorted()
        self.maxEventsPerSecond = max(1, maxEventsPerSecond)
        self.minHumanIntervalMilliseconds = max(0, minHumanIntervalMilliseconds)
    }

    public init(@FocusPolicyBuilder _ build: () -> [FocusPolicyDirective]) {
        var allowedBundleIDs: [String] = []
        var maxEventsPerSecond = 20
        var minHumanIntervalMilliseconds = 80
        for directive in build() {
            switch directive {
            case let .allowStealingFor(bundleID):
                allowedBundleIDs.append(bundleID)
            case let .maxEventsPerSecond(value):
                maxEventsPerSecond = value
            case let .minHumanIntervalMilliseconds(value):
                minHumanIntervalMilliseconds = value
            }
        }
        self.init(
            allowedBundleIDs: allowedBundleIDs,
            maxEventsPerSecond: maxEventsPerSecond,
            minHumanIntervalMilliseconds: minHumanIntervalMilliseconds
        )
    }

    public func allowsStealing(bundleID: String?) -> Bool {
        guard let bundleID else {
            return false
        }
        return allowedBundleIDs.contains(bundleID)
    }
}

/// Purpose: Builds focus-stealing policy directives inside `FocusPolicy { ... }`.
/// Parameters: Accepts focus-policy directive expressions.
/// Example: `FocusPolicy { allowStealingFor("com.apple.Terminal") }`
/// See also: `FocusPolicy`, `FocusPolicyDirective`.
@resultBuilder
public enum FocusPolicyBuilder {
    public static func buildBlock(_ components: FocusPolicyDirective...) -> [FocusPolicyDirective] {
        components
    }

    public static func buildExpression(_ expression: FocusPolicyDirective) -> FocusPolicyDirective {
        expression
    }
}

/// Purpose: Allows a trusted bundle ID to bypass focus-stealing throttles.
/// Parameters: Pass one app bundle identifier.
/// Example: `allowStealingFor("com.apple.Terminal")`
/// See also: `FocusPolicy`, `maxEventsPerSecond(_:)`.
public func allowStealingFor(_ bundleID: String) -> FocusPolicyDirective {
    FocusPolicyDirective.allowStealingFor(bundleID)
}

/// Purpose: Sets the maximum accepted programmatic focus changes per second.
/// Parameters: Pass a positive event count.
/// Example: `maxEventsPerSecond(20)`
/// See also: `FocusPolicy`, `minHumanIntervalMilliseconds(_:)`.
public func maxEventsPerSecond(_ value: Int) -> FocusPolicyDirective {
    FocusPolicyDirective.maxEventsPerSecond(value)
}

/// Purpose: Sets the minimum interval treated as human-paced focus activity.
/// Parameters: Pass a non-negative interval in milliseconds.
/// Example: `minHumanIntervalMilliseconds(80)`
/// See also: `FocusPolicy`, `maxEventsPerSecond(_:)`.
public func minHumanIntervalMilliseconds(_ value: Int) -> FocusPolicyDirective {
    FocusPolicyDirective.minHumanIntervalMilliseconds(value)
}

public extension ConfigBuilder {
    static func buildExpression(_ expression: FocusPolicy) -> [ConfigSection] {
        [.focusPolicy(expression)]
    }
}
