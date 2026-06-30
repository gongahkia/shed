import Foundation
import ollyKit

public enum WindowTagPersistenceError: Error, Equatable, Sendable {
    case invalidTitleRegex(String)
}

public struct WindowTagRule: Equatable, Sendable {
    public let processID: pid_t
    public let bundleID: String?
    public let titleRegex: String
    public let tags: TagSet
    public let isSticky: Bool
    public let isPinned: Bool
    public let engineOverride: LayoutEngineID?

    public init(
        processID: pid_t,
        bundleID: String?,
        titleRegex: String,
        tags: TagSet,
        isSticky: Bool = false,
        isPinned: Bool = false,
        engineOverride: LayoutEngineID? = nil
    ) throws {
        do {
            _ = try NSRegularExpression(pattern: titleRegex)
        } catch {
            throw WindowTagPersistenceError.invalidTitleRegex(titleRegex)
        }

        self.processID = processID
        self.bundleID = bundleID
        self.titleRegex = titleRegex
        self.tags = tags
        self.isSticky = isSticky
        self.isPinned = isPinned
        self.engineOverride = engineOverride
    }

    public static func exactTitleRule(
        window: WindowState,
        bundleID: String?,
        tags: TagSet? = nil
    ) throws -> WindowTagRule {
        let title = window.title ?? ""
        let titleRegex = "^\(NSRegularExpression.escapedPattern(for: title))$"
        return try WindowTagRule(
            processID: window.processID,
            bundleID: bundleID,
            titleRegex: titleRegex,
            tags: tags ?? TagSet(rawValue: window.tagMask)
        )
    }

    public func matches(processID: pid_t, bundleID: String?, title: String?) -> Bool {
        guard self.bundleID == bundleID,
              let expression = try? NSRegularExpression(pattern: titleRegex) else {
            return false
        }

        let title = title ?? ""
        let range = NSRange(title.startIndex..<title.endIndex, in: title)
        return expression.firstMatch(in: title, range: range) != nil
    }
}

public struct WindowLayoutOrderRule: Codable, Equatable, Sendable {
    public let bundleID: String?
    public let title: String?
    public let role: String?
    public let subrole: String?
    public let displayID: DisplayID?
    public let tagMask: UInt64
    public let layoutOrder: Int

    public init(
        bundleID: String?,
        title: String?,
        role: String?,
        subrole: String?,
        displayID: DisplayID?,
        tagMask: UInt64,
        layoutOrder: Int
    ) {
        self.bundleID = bundleID
        self.title = Self.normalizedTitle(title)
        self.role = role
        self.subrole = subrole
        self.displayID = displayID
        self.tagMask = tagMask
        self.layoutOrder = layoutOrder
    }

    public init(window: WindowState, layoutOrder: Int? = nil) {
        self.init(
            bundleID: window.bundleID,
            title: window.title,
            role: window.role,
            subrole: window.subrole,
            displayID: window.displayID,
            tagMask: window.tagMask,
            layoutOrder: layoutOrder ?? window.layoutOrder ?? 0
        )
    }

    public func matches(_ window: WindowState) -> Bool {
        key == WindowLayoutOrderKey(window: window)
    }

    private static func normalizedTitle(_ title: String?) -> String? {
        let normalized = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized?.isEmpty == true ? nil : normalized
    }
}

extension WindowTagRule: Codable {
    private enum CodingKeys: String, CodingKey {
        case processID
        case bundleID
        case titleRegex
        case tags
        case isSticky
        case isPinned
        case engineOverride
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        processID = try container.decode(pid_t.self, forKey: .processID)
        bundleID = try container.decodeIfPresent(String.self, forKey: .bundleID)
        titleRegex = try container.decode(String.self, forKey: .titleRegex)
        tags = TagSet(rawValue: try container.decode(UInt64.self, forKey: .tags))
        isSticky = try container.decodeIfPresent(Bool.self, forKey: .isSticky) ?? false
        isPinned = try container.decodeIfPresent(Bool.self, forKey: .isPinned) ?? false
        engineOverride = try container.decodeIfPresent(LayoutEngineID.self, forKey: .engineOverride)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(processID, forKey: .processID)
        try container.encodeIfPresent(bundleID, forKey: .bundleID)
        try container.encode(titleRegex, forKey: .titleRegex)
        try container.encode(tags.rawValue, forKey: .tags)
        try container.encode(isSticky, forKey: .isSticky)
        try container.encode(isPinned, forKey: .isPinned)
        try container.encodeIfPresent(engineOverride, forKey: .engineOverride)
    }
}

public struct WindowTagPersistenceState: Codable, Equatable, Sendable {
    public static let currentVersion = 2

    public var version: Int
    public var rules: [WindowTagRule]
    public var layoutOrders: [WindowLayoutOrderRule]

    public init(
        version: Int = Self.currentVersion,
        rules: [WindowTagRule] = [],
        layoutOrders: [WindowLayoutOrderRule] = []
    ) {
        self.version = version
        self.rules = rules
        self.layoutOrders = layoutOrders
    }

    public func tags(processID: pid_t, bundleID: String?, title: String?) -> TagSet? {
        let matchingRules = rules.filter {
            $0.matches(processID: processID, bundleID: bundleID, title: title)
        }
        return matchingRules.first { $0.processID == processID }?.tags ?? matchingRules.first?.tags
    }

    public mutating func upsert(_ rule: WindowTagRule) {
        if let index = rules.firstIndex(where: { $0.key == rule.key }) {
            rules[index] = rule
        } else {
            rules.append(rule)
        }
    }

    public func layoutOrder(for window: WindowState) -> Int? {
        let key = WindowLayoutOrderKey(window: window)
        return layoutOrders.first { $0.key == key }?.layoutOrder
    }

    public mutating func upsertLayoutOrder(_ rule: WindowLayoutOrderRule) {
        if let index = layoutOrders.firstIndex(where: { $0.key == rule.key }) {
            layoutOrders[index] = rule
        } else {
            layoutOrders.append(rule)
        }
        layoutOrders.sort { lhs, rhs in
            if lhs.layoutOrder != rhs.layoutOrder {
                return lhs.layoutOrder < rhs.layoutOrder
            }
            return lhs.stableSortKey < rhs.stableSortKey
        }
    }
}

extension WindowTagPersistenceState {
    private enum CodingKeys: String, CodingKey {
        case version
        case rules
        case layoutOrders
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decode(Int.self, forKey: .version)
        rules = try container.decodeIfPresent([WindowTagRule].self, forKey: .rules) ?? []
        layoutOrders = try container.decodeIfPresent([WindowLayoutOrderRule].self, forKey: .layoutOrders) ?? []
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encode(rules, forKey: .rules)
        try container.encode(layoutOrders, forKey: .layoutOrders)
    }

    mutating func migrateToCurrentVersion() {
        if version < Self.currentVersion {
            version = Self.currentVersion
        }
    }
}

public actor WindowTagPersistence {
    public static var defaultStateURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config")
            .appendingPathComponent("olly")
            .appendingPathComponent("state.json")
    }

    private let stateURL: URL
    private let fileManager: FileManager
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public init(stateURL: URL = WindowTagPersistence.defaultStateURL, fileManager: FileManager = .default) {
        self.stateURL = stateURL
        self.fileManager = fileManager
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    }

    public func load() throws -> WindowTagPersistenceState {
        guard fileManager.fileExists(atPath: stateURL.path) else {
            return WindowTagPersistenceState()
        }

        let data = try Data(contentsOf: stateURL)
        var state = try decoder.decode(WindowTagPersistenceState.self, from: data)
        let originalVersion = state.version
        state.migrateToCurrentVersion()
        if state.version != originalVersion {
            try save(state)
        }
        return state
    }

    public func save(_ state: WindowTagPersistenceState) throws {
        let directoryURL = stateURL.deletingLastPathComponent()
        try fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        let data = try encoder.encode(state)
        try data.write(to: stateURL, options: [.atomic])
    }

    public func upsert(_ rule: WindowTagRule) throws {
        var state = try load()
        state.upsert(rule)
        try save(state)
    }

    public func layoutOrder(for window: WindowState) throws -> Int? {
        try load().layoutOrder(for: window)
    }

    public func upsertLayoutOrders(for windows: [WindowState]) throws {
        var state = try load()
        var didChange = false
        for window in windows {
            guard let layoutOrder = window.layoutOrder else {
                continue
            }
            state.upsertLayoutOrder(WindowLayoutOrderRule(window: window, layoutOrder: layoutOrder))
            didChange = true
        }
        if didChange {
            try save(state)
        }
    }
}

private extension WindowTagRule {
    var key: WindowTagRuleKey {
        WindowTagRuleKey(processID: processID, bundleID: bundleID, titleRegex: titleRegex)
    }
}

private struct WindowTagRuleKey: Equatable {
    let processID: pid_t
    let bundleID: String?
    let titleRegex: String
}

private extension WindowLayoutOrderRule {
    var key: WindowLayoutOrderKey {
        WindowLayoutOrderKey(
            bundleID: bundleID,
            title: title,
            role: role,
            subrole: subrole,
            displayID: displayID,
            tagMask: tagMask
        )
    }

    var stableSortKey: String {
        [
            bundleID ?? "",
            title ?? "",
            role ?? "",
            subrole ?? "",
            displayID.map(String.init) ?? "",
            String(tagMask)
        ].joined(separator: "\u{1F}")
    }
}

private struct WindowLayoutOrderKey: Equatable {
    let bundleID: String?
    let title: String?
    let role: String?
    let subrole: String?
    let displayID: DisplayID?
    let tagMask: UInt64

    init(
        bundleID: String?,
        title: String?,
        role: String?,
        subrole: String?,
        displayID: DisplayID?,
        tagMask: UInt64
    ) {
        self.bundleID = bundleID
        self.title = title?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.role = role
        self.subrole = subrole
        self.displayID = displayID
        self.tagMask = tagMask
    }

    init(window: WindowState) {
        self.init(
            bundleID: window.bundleID,
            title: window.title,
            role: window.role,
            subrole: window.subrole,
            displayID: window.displayID,
            tagMask: window.tagMask
        )
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
