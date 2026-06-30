import CoreGraphics
import Foundation
import ollyKit

public struct ScratchpadEntry: Codable, Equatable, Sendable {
    public let name: String
    public let bundleID: String?
    public let titleRegex: String?
    public let role: String?
    public let lastVisibleFrame: WindowRecoveryFrame?
    public let isVisible: Bool

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

public enum ScratchpadRegistryError: Error, Equatable, Sendable, CustomStringConvertible {
    case invalidName
    case invalidTitleRegex(name: String, pattern: String)
    case missingEntry(String)

    public var description: String {
        switch self {
        case .invalidName:
            return "scratchpad name is empty"
        case let .invalidTitleRegex(name, pattern):
            return "scratchpad \(name) has invalid title regex: \(pattern)"
        case let .missingEntry(name):
            return "scratchpad unavailable: \(name)"
        }
    }
}

public actor ScratchpadRegistry {
    public static var defaultStateURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config")
            .appendingPathComponent("olly")
            .appendingPathComponent("scratchpads.json")
    }

    private let stateURL: URL
    private let fileManager: FileManager
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private var cache: [ScratchpadEntry]?

    public init(stateURL: URL = ScratchpadRegistry.defaultStateURL, fileManager: FileManager = .default) {
        self.stateURL = stateURL
        self.fileManager = fileManager
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    }

    public func entries() throws -> [ScratchpadEntry] {
        try loadIfNeeded()
    }

    public func entry(named name: String) throws -> ScratchpadEntry? {
        try loadIfNeeded().first { $0.name == name }
    }

    public func upsert(_ entry: ScratchpadEntry) throws {
        try validate(entry)
        var entries = try loadIfNeeded()
        if let index = entries.firstIndex(where: { $0.name == entry.name }) {
            entries[index] = entry
        } else {
            entries.append(entry)
        }
        try save(entries)
    }

    public func upsert(_ newEntries: [ScratchpadEntry]) throws {
        for entry in newEntries {
            try upsert(entry)
        }
    }

    @discardableResult
    public func remove(name: String) throws -> ScratchpadEntry? {
        var entries = try loadIfNeeded()
        guard let index = entries.firstIndex(where: { $0.name == name }) else {
            return nil
        }
        let removed = entries.remove(at: index)
        try save(entries)
        return removed
    }

    @discardableResult
    public func setVisibility(
        name: String,
        isVisible: Bool,
        lastVisibleFrame: WindowRecoveryFrame? = nil
    ) throws -> ScratchpadEntry {
        var entries = try loadIfNeeded()
        guard let index = entries.firstIndex(where: { $0.name == name }) else {
            throw ScratchpadRegistryError.missingEntry(name)
        }
        let current = entries[index]
        let updated = ScratchpadEntry(
            name: current.name,
            bundleID: current.bundleID,
            titleRegex: current.titleRegex,
            role: current.role,
            lastVisibleFrame: lastVisibleFrame ?? current.lastVisibleFrame,
            isVisible: isVisible
        )
        entries[index] = updated
        try save(entries)
        return updated
    }

    public func matchingEntry(for window: WindowState) throws -> ScratchpadEntry? {
        try loadIfNeeded().first { try $0.matches(window) }
    }

    private func loadIfNeeded() throws -> [ScratchpadEntry] {
        if let cache {
            return cache
        }
        guard fileManager.fileExists(atPath: stateURL.path) else {
            cache = []
            return []
        }
        let entries = try decoder.decode([ScratchpadEntry].self, from: Data(contentsOf: stateURL))
        cache = entries.sorted { $0.name < $1.name }
        return cache ?? []
    }

    private func save(_ entries: [ScratchpadEntry]) throws {
        let sorted = entries.sorted { $0.name < $1.name }
        try fileManager.createDirectory(at: stateURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try encoder.encode(sorted).write(to: stateURL, options: [.atomic])
        cache = sorted
    }

    private func validate(_ entry: ScratchpadEntry) throws {
        guard !entry.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ScratchpadRegistryError.invalidName
        }
        if let pattern = entry.titleRegex {
            do {
                _ = try NSRegularExpression(pattern: pattern)
            } catch {
                throw ScratchpadRegistryError.invalidTitleRegex(name: entry.name, pattern: pattern)
            }
        }
    }
}

public extension ScratchpadEntry {
    func matches(_ window: WindowState) throws -> Bool {
        if let bundleID, window.bundleID != bundleID {
            return false
        }
        if let role, window.role != role {
            return false
        }
        if let titleRegex {
            let title = window.title ?? ""
            let range = NSRange(title.startIndex..<title.endIndex, in: title)
            let regex = try NSRegularExpression(pattern: titleRegex)
            return regex.firstMatch(in: title, range: range) != nil
        }
        return bundleID != nil || role != nil
    }
}
