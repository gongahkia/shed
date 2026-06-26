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

    public init(
        processID: pid_t,
        bundleID: String?,
        titleRegex: String,
        tags: TagSet
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

extension WindowTagRule: Codable {
    private enum CodingKeys: String, CodingKey {
        case processID
        case bundleID
        case titleRegex
        case tags
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        processID = try container.decode(pid_t.self, forKey: .processID)
        bundleID = try container.decodeIfPresent(String.self, forKey: .bundleID)
        titleRegex = try container.decode(String.self, forKey: .titleRegex)
        tags = TagSet(rawValue: try container.decode(UInt64.self, forKey: .tags))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(processID, forKey: .processID)
        try container.encodeIfPresent(bundleID, forKey: .bundleID)
        try container.encode(titleRegex, forKey: .titleRegex)
        try container.encode(tags.rawValue, forKey: .tags)
    }
}

public struct WindowTagPersistenceState: Codable, Equatable, Sendable {
    public var version: Int
    public var rules: [WindowTagRule]

    public init(version: Int = 1, rules: [WindowTagRule] = []) {
        self.version = version
        self.rules = rules
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
        return try decoder.decode(WindowTagPersistenceState.self, from: data)
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
