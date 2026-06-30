import Foundation
import ollyCore

public struct IPCReloadCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCRestoreWindowsCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCMacroStartCommand: Codable, Equatable, Sendable {
    public let name: String

    public init(name: String) {
        self.name = name
    }
}

public struct IPCMacroStopCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCMacroRunCommand: Codable, Equatable, Sendable {
    public let name: String

    public init(name: String) {
        self.name = name
    }
}

public struct IPCMacroListCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCMacroDeleteCommand: Codable, Equatable, Sendable {
    public let name: String

    public init(name: String) {
        self.name = name
    }
}

public struct IPCMacroInfo: Codable, Equatable, Sendable {
    public let name: String
    public let createdAt: Date
    public let recordedDurationMs: Int
    public let commandCount: Int

    public init(name: String, createdAt: Date, recordedDurationMs: Int, commandCount: Int) {
        self.name = name
        self.createdAt = createdAt
        self.recordedDurationMs = recordedDurationMs
        self.commandCount = commandCount
    }
}

public struct IPCMacroListInfo: Codable, Equatable, Sendable {
    public let macros: [IPCMacroInfo]

    public init(macros: [IPCMacroInfo]) {
        self.macros = macros
    }
}

public struct IPCRunRawActionCommand: Codable, Equatable, Sendable {
    public let label: String

    public init(label: String) {
        self.label = label
    }
}

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
