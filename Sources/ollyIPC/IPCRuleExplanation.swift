import Foundation
import ollyCore
import ollyKit

public struct IPCRuleApply: Codable, Equatable, Sendable {
    public let tagMask: UInt64?
    public let engineOverride: LayoutEngineID?
    public let floating: Bool?
    public let sticky: Bool?
    public let pinned: Bool?

    public init(
        tagMask: UInt64? = nil,
        engineOverride: LayoutEngineID? = nil,
        floating: Bool? = nil,
        sticky: Bool? = nil,
        pinned: Bool? = nil
    ) {
        self.tagMask = tagMask
        self.engineOverride = engineOverride
        self.floating = floating
        self.sticky = sticky
        self.pinned = pinned
    }
}

public struct IPCRuleMatchTrace: Codable, Equatable, Sendable {
    public let ruleID: UUID
    public let matched: Bool
    public let bundleIDMatched: Bool?
    public let titleMatched: Bool?
    public let roleMatched: Bool?
    public let subroleMatched: Bool?
    public let predicateMatched: Bool?

    public init(
        ruleID: UUID,
        matched: Bool,
        bundleIDMatched: Bool? = nil,
        titleMatched: Bool? = nil,
        roleMatched: Bool? = nil,
        subroleMatched: Bool? = nil,
        predicateMatched: Bool? = nil
    ) {
        self.ruleID = ruleID
        self.matched = matched
        self.bundleIDMatched = bundleIDMatched
        self.titleMatched = titleMatched
        self.roleMatched = roleMatched
        self.subroleMatched = subroleMatched
        self.predicateMatched = predicateMatched
    }
}

public struct IPCRuleExplanation: Codable, Equatable, Sendable {
    public let windowID: WindowID?
    public let ruleID: UUID?
    public let traces: [IPCRuleMatchTrace]
    public let finalApply: IPCRuleApply

    public init(
        windowID: WindowID? = nil,
        ruleID: UUID? = nil,
        traces: [IPCRuleMatchTrace],
        finalApply: IPCRuleApply
    ) {
        self.windowID = windowID
        self.ruleID = ruleID
        self.traces = traces
        self.finalApply = finalApply
    }
}
