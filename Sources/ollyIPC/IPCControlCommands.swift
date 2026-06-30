import ollyCore

public struct IPCReloadCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCRestoreWindowsCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCSetSpacePolicyCommand: Codable, Equatable, Sendable {
    public let policy: NativeSpaceDriftPolicy

    public init(policy: NativeSpaceDriftPolicy) {
        self.policy = policy
    }
}
