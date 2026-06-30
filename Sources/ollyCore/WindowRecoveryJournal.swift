import CoreGraphics
import Foundation
import ollyKit

public struct WindowRecoveryFrame: Codable, Equatable, Sendable {
    public let originX: Double
    public let originY: Double
    public let width: Double
    public let height: Double

    public init(_ frame: CGRect) {
        self.originX = Double(frame.origin.x)
        self.originY = Double(frame.origin.y)
        self.width = Double(frame.size.width)
        self.height = Double(frame.size.height)
    }

    public var cgRect: CGRect {
        CGRect(x: originX, y: originY, width: width, height: height)
    }

    private enum CodingKeys: String, CodingKey {
        case originX = "x"
        case originY = "y"
        case width
        case height
    }
}

public struct WindowRecoveryEntry: Codable, Equatable, Sendable {
    public let windowID: WindowID
    public let processID: pid_t
    public let bundleID: String?
    public let title: String?
    public let role: String?
    public let subrole: String?
    public let displayID: DisplayID?
    public let tagMask: UInt64
    public let isSticky: Bool
    public let isFullscreen: Bool
    public let engineOverride: LayoutEngineID?
    public let originalFrame: WindowRecoveryFrame
    public let parkedFrame: WindowRecoveryFrame
    public let updatedAt: Date

    public init(
        windowID: WindowID,
        processID: pid_t,
        bundleID: String?,
        title: String?,
        role: String?,
        subrole: String?,
        displayID: DisplayID?,
        tagMask: UInt64,
        isSticky: Bool = false,
        isFullscreen: Bool = false,
        engineOverride: LayoutEngineID? = nil,
        originalFrame: CGRect,
        parkedFrame: CGRect,
        updatedAt: Date = Date()
    ) {
        self.windowID = windowID
        self.processID = processID
        self.bundleID = bundleID
        self.title = title
        self.role = role
        self.subrole = subrole
        self.displayID = displayID
        self.tagMask = tagMask
        self.isSticky = isSticky
        self.isFullscreen = isFullscreen
        self.engineOverride = engineOverride
        self.originalFrame = WindowRecoveryFrame(originalFrame)
        self.parkedFrame = WindowRecoveryFrame(parkedFrame)
        self.updatedAt = updatedAt
    }

    public init(window: WindowState, parkedFrame: CGRect, updatedAt: Date = Date()) {
        self.init(
            windowID: window.id,
            processID: window.processID,
            bundleID: window.bundleID,
            title: window.title,
            role: window.role,
            subrole: window.subrole,
            displayID: window.displayID,
            tagMask: window.tagMask,
            originalFrame: window.frame,
            parkedFrame: parkedFrame,
            updatedAt: updatedAt
        )
    }
}

public struct WindowRecoveryJournalState: Codable, Equatable, Sendable {
    public static let currentVersion = 2

    public var version: Int
    public var entries: [WindowRecoveryEntry]

    public init(version: Int = Self.currentVersion, entries: [WindowRecoveryEntry] = []) {
        self.version = version
        self.entries = entries
    }

    public mutating func upsert(_ entry: WindowRecoveryEntry) {
        if let index = entries.firstIndex(where: { $0.windowID == entry.windowID }) {
            entries[index] = entry
        } else {
            entries.append(entry)
        }
        entries.sort { $0.windowID < $1.windowID }
    }

    public mutating func remove(windowID: WindowID) {
        entries.removeAll { $0.windowID == windowID }
    }

    mutating func migrateToCurrentVersion() {
        if version < Self.currentVersion {
            version = Self.currentVersion
        }
    }
}

extension WindowRecoveryEntry {
    private enum CodingKeys: String, CodingKey {
        case windowID
        case processID
        case bundleID
        case title
        case role
        case subrole
        case displayID
        case tagMask
        case isSticky
        case isFullscreen
        case engineOverride
        case originalFrame
        case parkedFrame
        case updatedAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        windowID = try container.decode(WindowID.self, forKey: .windowID)
        processID = try container.decode(pid_t.self, forKey: .processID)
        bundleID = try container.decodeIfPresent(String.self, forKey: .bundleID)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        role = try container.decodeIfPresent(String.self, forKey: .role)
        subrole = try container.decodeIfPresent(String.self, forKey: .subrole)
        displayID = try container.decodeIfPresent(DisplayID.self, forKey: .displayID)
        tagMask = try container.decode(UInt64.self, forKey: .tagMask)
        isSticky = try container.decodeIfPresent(Bool.self, forKey: .isSticky) ?? false
        isFullscreen = try container.decodeIfPresent(Bool.self, forKey: .isFullscreen) ?? false
        engineOverride = try container.decodeIfPresent(LayoutEngineID.self, forKey: .engineOverride)
        originalFrame = try container.decode(WindowRecoveryFrame.self, forKey: .originalFrame)
        parkedFrame = try container.decode(WindowRecoveryFrame.self, forKey: .parkedFrame)
        updatedAt = try container.decode(Date.self, forKey: .updatedAt)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(windowID, forKey: .windowID)
        try container.encode(processID, forKey: .processID)
        try container.encodeIfPresent(bundleID, forKey: .bundleID)
        try container.encodeIfPresent(title, forKey: .title)
        try container.encodeIfPresent(role, forKey: .role)
        try container.encodeIfPresent(subrole, forKey: .subrole)
        try container.encodeIfPresent(displayID, forKey: .displayID)
        try container.encode(tagMask, forKey: .tagMask)
        try container.encode(isSticky, forKey: .isSticky)
        try container.encode(isFullscreen, forKey: .isFullscreen)
        try container.encodeIfPresent(engineOverride, forKey: .engineOverride)
        try container.encode(originalFrame, forKey: .originalFrame)
        try container.encode(parkedFrame, forKey: .parkedFrame)
        try container.encode(updatedAt, forKey: .updatedAt)
    }
}

public actor WindowRecoveryJournal {
    public static var defaultStateURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config")
            .appendingPathComponent("olly")
            .appendingPathComponent("recovery.json")
    }

    private let stateURL: URL
    private let fileManager: FileManager
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public init(stateURL: URL = WindowRecoveryJournal.defaultStateURL, fileManager: FileManager = .default) {
        self.stateURL = stateURL
        self.fileManager = fileManager
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
    }

    public func load() throws -> WindowRecoveryJournalState {
        guard fileManager.fileExists(atPath: stateURL.path) else {
            return WindowRecoveryJournalState()
        }
        let data = try Data(contentsOf: stateURL)
        var state = try decoder.decode(WindowRecoveryJournalState.self, from: data)
        let originalVersion = state.version
        state.migrateToCurrentVersion()
        if state.version != originalVersion {
            try save(state)
        }
        return state
    }

    public func save(_ state: WindowRecoveryJournalState) throws {
        let directoryURL = stateURL.deletingLastPathComponent()
        try fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        let data = try encoder.encode(state)
        try data.write(to: stateURL, options: [.atomic])
    }

    public func record(window: WindowState, parkedFrame: CGRect) throws {
        var state = try load()
        state.upsert(WindowRecoveryEntry(window: window, parkedFrame: parkedFrame))
        try save(state)
    }

    public func remove(windowID: WindowID) throws {
        var state = try load()
        state.remove(windowID: windowID)
        try save(state)
    }

    public func remove(windowIDs: [WindowID]) throws {
        var state = try load()
        for windowID in windowIDs {
            state.remove(windowID: windowID)
        }
        try save(state)
    }

    public func clear() throws {
        try save(WindowRecoveryJournalState())
    }
}
