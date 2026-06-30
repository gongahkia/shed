import Foundation
import ollyIPC

public struct MacroRecording: Codable, Equatable, Sendable {
    public let name: String
    public let createdAt: Date
    public let recordedDurationMs: Int
    public let commandCount: Int
    public let commands: [IPCCommand]

    init(name: String, createdAt: Date, recordedDurationMs: Int, commands: [IPCCommand]) {
        self.name = name
        self.createdAt = createdAt
        self.recordedDurationMs = recordedDurationMs
        self.commandCount = commands.count
        self.commands = commands
    }

    public var info: IPCMacroInfo {
        IPCMacroInfo(
            name: name,
            createdAt: createdAt,
            recordedDurationMs: recordedDurationMs,
            commandCount: commandCount
        )
    }
}

public actor MacroRecorder {
    public static let defaultDirectory = FileManager.default
        .homeDirectoryForCurrentUser
        .appendingPathComponent(".config/olly/macros", isDirectory: true)

    private let directoryURL: URL
    private let fileManager: FileManager
    private var active: ActiveRecording?

    public init(directoryURL: URL = MacroRecorder.defaultDirectory, fileManager: FileManager = .default) {
        self.directoryURL = directoryURL
        self.fileManager = fileManager
    }

    public func start(name rawName: String) async throws -> IPCMacroInfo {
        guard active == nil else {
            throw OllyRuntimeError.macroAlreadyRecording
        }
        let name = try Self.validatedName(rawName)
        let now = Date()
        active = ActiveRecording(name: name, startedAt: now, commands: [])
        return IPCMacroInfo(name: name, createdAt: now, recordedDurationMs: 0, commandCount: 0)
    }

    public func record(_ command: IPCCommand) {
        guard !command.isMacroCommand, active != nil else {
            return
        }
        active?.commands.append(command)
    }

    public func stop() async throws -> IPCMacroInfo {
        guard let recording = active else {
            throw OllyRuntimeError.macroNotRecording
        }
        active = nil
        let elapsed = max(0, Int(Date().timeIntervalSince(recording.startedAt) * 1_000))
        let macro = MacroRecording(
            name: recording.name,
            createdAt: recording.startedAt,
            recordedDurationMs: elapsed,
            commands: recording.commands
        )
        try save(macro)
        return macro.info
    }

    public func run(name: String) async throws -> MacroRecording {
        try load(name: name)
    }

    public func load(name rawName: String) throws -> MacroRecording {
        let name = try Self.validatedName(rawName)
        let url = fileURL(for: name)
        guard fileManager.fileExists(atPath: url.path) else {
            throw OllyRuntimeError.macroUnavailable(name)
        }
        do {
            return try Self.decoder.decode(MacroRecording.self, from: Data(contentsOf: url))
        } catch {
            throw OllyRuntimeError.macroPersistenceFailed(String(describing: error))
        }
    }

    public func list() throws -> IPCMacroListInfo {
        guard fileManager.fileExists(atPath: directoryURL.path) else {
            return IPCMacroListInfo(macros: [])
        }
        do {
            let urls = try fileManager.contentsOfDirectory(
                at: directoryURL,
                includingPropertiesForKeys: nil
            ).filter { $0.pathExtension == "json" }
            var macros: [IPCMacroInfo] = []
            for url in urls {
                let data = try Data(contentsOf: url)
                let macro = try Self.decoder.decode(MacroRecording.self, from: data)
                macros.append(macro.info)
            }
            macros.sort(by: Self.sortMacroInfo)
            return IPCMacroListInfo(macros: macros)
        } catch {
            throw OllyRuntimeError.macroPersistenceFailed(String(describing: error))
        }
    }

    public func delete(name rawName: String) throws {
        let name = try Self.validatedName(rawName)
        let url = fileURL(for: name)
        guard fileManager.fileExists(atPath: url.path) else {
            throw OllyRuntimeError.macroUnavailable(name)
        }
        do {
            try fileManager.removeItem(at: url)
        } catch {
            throw OllyRuntimeError.macroPersistenceFailed(String(describing: error))
        }
    }

    public nonisolated static func macroInfos(in directoryURL: URL = defaultDirectory) throws -> [IPCMacroInfo] {
        let recorder = MacroFileReader(directoryURL: directoryURL)
        return try recorder.list().macros
    }

    private func save(_ macro: MacroRecording) throws {
        do {
            try fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
            try Self.encoder.encode(macro).write(to: fileURL(for: macro.name), options: [.atomic])
        } catch {
            throw OllyRuntimeError.macroPersistenceFailed(String(describing: error))
        }
    }

    private func fileURL(for name: String) -> URL {
        directoryURL.appendingPathComponent(name).appendingPathExtension("json")
    }

    private nonisolated static func validatedName(_ rawName: String) throws -> String {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        let range = name.range(of: #"^[A-Za-z0-9._-]+$"#, options: .regularExpression)
        guard !name.isEmpty, name != ".", name != "..", range?.lowerBound == name.startIndex,
              range?.upperBound == name.endIndex else {
            throw OllyRuntimeError.invalidMacroName(rawName)
        }
        return name
    }

    private nonisolated static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }()

    private nonisolated static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()

    private nonisolated static func sortMacroInfo(_ lhs: IPCMacroInfo, _ rhs: IPCMacroInfo) -> Bool {
        lhs.name == rhs.name ? lhs.createdAt < rhs.createdAt : lhs.name < rhs.name
    }
}

private struct ActiveRecording {
    let name: String
    let startedAt: Date
    var commands: [IPCCommand]
}

private struct MacroFileReader {
    let directoryURL: URL
    let fileManager = FileManager.default

    func list() throws -> IPCMacroListInfo {
        guard fileManager.fileExists(atPath: directoryURL.path) else {
            return IPCMacroListInfo(macros: [])
        }
        let urls = try fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: nil
        ).filter { $0.pathExtension == "json" }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        var macros: [IPCMacroInfo] = []
        for url in urls {
            let data = try Data(contentsOf: url)
            let macro = try decoder.decode(MacroRecording.self, from: data)
            macros.append(macro.info)
        }
        macros.sort { lhs, rhs in
            lhs.name == rhs.name ? lhs.createdAt < rhs.createdAt : lhs.name < rhs.name
        }
        return IPCMacroListInfo(macros: macros)
    }
}

private extension IPCCommand {
    var isMacroCommand: Bool {
        switch self {
        case .macroStart, .macroStop, .macroRun, .macroList, .macroDelete:
            return true
        default:
            return false
        }
    }
}
