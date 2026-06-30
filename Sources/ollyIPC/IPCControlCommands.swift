import ollyCore

public struct IPCReloadCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCRestoreWindowsCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCSetSpacePolicyCommand: Codable, Equatable, Sendable {
    public let policy: NativeSpaceDriftPolicy

    public init(policy: NativeSpaceDriftPolicy) {
        self.policy = policy
    }
}

public struct IPCSetFocusPolicyCommand: Codable, Equatable, Sendable {
    public let allowedBundleIDs: [String]?
    public let maxEventsPerSecond: Int?
    public let minHumanIntervalMilliseconds: Int?

    public init(
        allowedBundleIDs: [String]? = nil,
        maxEventsPerSecond: Int? = nil,
        minHumanIntervalMilliseconds: Int? = nil
    ) {
        self.allowedBundleIDs = allowedBundleIDs
        self.maxEventsPerSecond = maxEventsPerSecond
        self.minHumanIntervalMilliseconds = minHumanIntervalMilliseconds
    }
}
