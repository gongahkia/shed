import Foundation

/// Purpose: Records how one rule matched or rejected a window context.
/// Parameters: Stores the rule ID and optional per-field booleans for configured match clauses.
/// Example: `trace.bundleIDMatched == true`
/// See also: `RuleExplanation`, `Rules`.
public struct RuleMatchTrace: Codable, Equatable, Sendable {
    public let ruleID: UUID
    public let bundleIDMatched: Bool?
    public let titleMatched: Bool?
    public let roleMatched: Bool?
    public let subroleMatched: Bool?
    public let predicateMatched: Bool?

    public init(
        ruleID: UUID,
        bundleIDMatched: Bool? = nil,
        titleMatched: Bool? = nil,
        roleMatched: Bool? = nil,
        subroleMatched: Bool? = nil,
        predicateMatched: Bool? = nil
    ) {
        self.ruleID = ruleID
        self.bundleIDMatched = bundleIDMatched
        self.titleMatched = titleMatched
        self.roleMatched = roleMatched
        self.subroleMatched = subroleMatched
        self.predicateMatched = predicateMatched
    }

    public var matched: Bool {
        [
            bundleIDMatched,
            titleMatched,
            roleMatched,
            subroleMatched,
            predicateMatched
        ].compactMap { $0 }.allSatisfy { $0 }
    }
}

/// Purpose: Explains rule matching in declaration order and the final merged apply payload.
/// Parameters: Stores every rule trace plus the cumulative `RuleApply`.
/// Example: `rules.resolvedExplanation(for: context).traces`
/// See also: `RuleMatchTrace`, `Rules`.
public struct RuleExplanation: Codable, Equatable, Sendable {
    public let traces: [RuleMatchTrace]
    public let finalApply: RuleApply

    public init(traces: [RuleMatchTrace], finalApply: RuleApply) {
        self.traces = traces
        self.finalApply = finalApply
    }
}
