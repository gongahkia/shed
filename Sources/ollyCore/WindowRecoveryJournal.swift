import CoreGraphics
import Foundation
import ollyKit

public struct WindowRecoveryFrame: Codable, Equatable, Sendable {
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double

    public init(_ frame: CGRect) {
        self.x = Double(frame.origin.x)
        self.y = Double(frame.origin.y)
        self.width = Double(frame.size.width)
        self.height = Double(frame.size.height)
    }

    public var cgRect: CGRect {
        CGRect(x: x, y: y, width: width, height: height)
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
    public var version: Int
    public var entries: [WindowRecoveryEntry]

    public init(version: Int = 1, entries: [WindowRecoveryEntry] = []) {
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
        return try decoder.decode(WindowRecoveryJournalState.self, from: data)
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
