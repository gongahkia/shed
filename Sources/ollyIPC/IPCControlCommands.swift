import Foundation
import ollyCore

public struct IPCReloadCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCRestoreWindowsCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCScratchpadAddCommand: Codable, Equatable, Sendable {
    public let name: String
    public let bundleID: String?
    public let titleRegex: String?
    public let role: String?

    public init(name: String, bundleID: String? = nil, titleRegex: String? = nil, role: String? = nil) {
        self.name = name
        self.bundleID = bundleID
        self.titleRegex = titleRegex
        self.role = role
    }
}

public struct IPCScratchpadToggleCommand: Codable, Equatable, Sendable {
    public let name: String

    public init(name: String) {
        self.name = name
    }
}

public struct IPCScratchpadListCommand: Codable, Equatable, Sendable { public init() {} }

public struct IPCScratchpadRemoveCommand: Codable, Equatable, Sendable {
    public let name: String

    public init(name: String) {
        self.name = name
    }
}

public struct IPCScratchpadInfo: Codable, Equatable, Sendable {
    public let name: String
    public let bundleID: String?
    public let titleRegex: String?
    public let role: String?
    public let lastVisibleFrame: WindowRecoveryFrame?
    public let isVisible: Bool

    public init(entry: ScratchpadEntry) {
        self.name = entry.name
        self.bundleID = entry.bundleID
        self.titleRegex = entry.titleRegex
        self.role = entry.role
        self.lastVisibleFrame = entry.lastVisibleFrame
        self.isVisible = entry.isVisible
    }

    public init(
        name: String,
        bundleID: String? = nil,
        titleRegex: String? = nil,
        role: String? = nil,
        lastVisibleFrame: WindowRecoveryFrame? = nil,
        isVisible: Bool = true
    ) {
        self.name = name
        self.bundleID = bundleID
        self.titleRegex = titleRegex
        self.role = role
        self.lastVisibleFrame = lastVisibleFrame
        self.isVisible = isVisible
    }
}

public struct IPCScratchpadListInfo: Codable, Equatable, Sendable {
    public let scratchpads: [IPCScratchpadInfo]

    public init(scratchpads: [IPCScratchpadInfo]) {
        self.scratchpads = scratchpads
    }
}

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
