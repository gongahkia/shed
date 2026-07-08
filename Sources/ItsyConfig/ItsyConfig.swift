import Darwin
import Foundation

public struct ItsySettings: Equatable, Sendable {
	public enum EditorStorage: String, Equatable, Sendable {
		case rope
		case pieceTree = "piecetree"
	}

	public enum SyntaxPreloadGrammars: String, Equatable, Sendable {
		case none
		case opened
		case all
	}

	public struct EditorSettings: Equatable, Sendable {
		public struct LanguageSettings: Equatable, Sendable {
			public var font: String?
			public var fontSize: Double?
			public var lineNumbers: Bool?
			public var tabWidth: Int?
			public var useSpaces: Bool?

			public init(
				font: String? = nil,
				fontSize: Double? = nil,
				lineNumbers: Bool? = nil,
				tabWidth: Int? = nil,
				useSpaces: Bool? = nil
			) {
				self.font = font
				self.fontSize = fontSize
				self.lineNumbers = lineNumbers
				self.tabWidth = tabWidth
				self.useSpaces = useSpaces
			}
		}

		public struct ExperimentalSettings: Equatable, Sendable {
			public var storage: EditorStorage

			public init(storage: EditorStorage = .pieceTree) {
				self.storage = storage
			}
		}

		public static let defaultFont = "Menlo"
		public static let defaultFontSize = 14.95
		public static let minFontSize = 9.0
		public static let maxFontSize = 36.0
		public static let defaultTabWidth = 4
		public static let minTabWidth = 1
		public static let maxTabWidth = 16

		public var font: String
		public var fontSize: Double
		public var lineNumbers: Bool
		public var tabWidth: Int
		public var useSpaces: Bool
		public var language: [String: LanguageSettings]
		public var experimental: ExperimentalSettings

		public init(
			font: String = Self.defaultFont,
			fontSize: Double = Self.defaultFontSize,
			lineNumbers: Bool = false,
			tabWidth: Int = Self.defaultTabWidth,
			useSpaces: Bool = false,
			language: [String: LanguageSettings] = [:],
			experimental: ExperimentalSettings = ExperimentalSettings()
		) {
			self.font = font
			self.fontSize = fontSize
			self.lineNumbers = lineNumbers
			self.tabWidth = tabWidth
			self.useSpaces = useSpaces
			self.language = language
			self.experimental = experimental
		}
	}

	public struct ThemeSettings: Equatable, Sendable {
		public static let defaultID = "bundled:default-light"

		public var id: String

		public init(id: String = Self.defaultID) {
			self.id = id
		}
	}

	public struct SyntaxSettings: Equatable, Sendable {
		public var preloadGrammars: SyntaxPreloadGrammars

		public init(preloadGrammars: SyntaxPreloadGrammars = .opened) {
			self.preloadGrammars = preloadGrammars
		}
	}

	public struct TerminalSettings: Equatable, Sendable {
		public static let defaultFontSize = 12.0
		public static let minFontSize = 8.0
		public static let maxFontSize = 36.0
		public static let defaultScrollbackLines = 10_000
		public static let minScrollbackLines = 0
		public static let maxScrollbackLines = 1_000_000

		public var fontSize: Double
		public var scrollbackLines: Int

		public init(fontSize: Double = Self.defaultFontSize, scrollbackLines: Int = Self.defaultScrollbackLines) {
			self.fontSize = fontSize
			self.scrollbackLines = scrollbackLines
		}
	}

	public static let `default` = ItsySettings()

	public var editor: EditorSettings
	public var theme: ThemeSettings
	public var syntax: SyntaxSettings
	public var terminal: TerminalSettings

	public init(
		editor: EditorSettings = EditorSettings(),
		theme: ThemeSettings = ThemeSettings(),
		syntax: SyntaxSettings = SyntaxSettings(),
		terminal: TerminalSettings = TerminalSettings()
	) {
		self.editor = editor
		self.theme = theme
		self.syntax = syntax
		self.terminal = terminal
	}

	public func normalized() -> ItsySettings {
		var copy = self
		if copy.editor.font.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.editor.font = EditorSettings.defaultFont
		}
		copy.editor.fontSize = Self.clamp(copy.editor.fontSize, min: EditorSettings.minFontSize, max: EditorSettings.maxFontSize)
		copy.editor.tabWidth = Self.clamp(copy.editor.tabWidth, min: EditorSettings.minTabWidth, max: EditorSettings.maxTabWidth)
		copy.editor.language = copy.editor.language.reduce(into: [:]) { result, entry in
			let key = entry.key.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !key.isEmpty else {
				return
			}
			var value = entry.value
			if value.font?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true {
				value.font = nil
			}
			if let fontSize = value.fontSize {
				value.fontSize = Self.clamp(fontSize, min: EditorSettings.minFontSize, max: EditorSettings.maxFontSize)
			}
			if let tabWidth = value.tabWidth {
				value.tabWidth = Self.clamp(tabWidth, min: EditorSettings.minTabWidth, max: EditorSettings.maxTabWidth)
			}
			result[key] = value
		}
		if copy.theme.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.theme.id = ThemeSettings.defaultID
		}
		copy.terminal.fontSize = Self.clamp(copy.terminal.fontSize, min: TerminalSettings.minFontSize, max: TerminalSettings.maxFontSize)
		copy.terminal.scrollbackLines = Self.clamp(copy.terminal.scrollbackLines, min: TerminalSettings.minScrollbackLines, max: TerminalSettings.maxScrollbackLines)
		return copy
	}

	public func editorSettings(languageID: String?) -> EditorSettings {
		var editor = normalized().editor
		guard let languageID = languageID?.trimmingCharacters(in: .whitespacesAndNewlines), !languageID.isEmpty else {
			return editor
		}
		guard let override = editor.language[languageID] ?? editor.language[languageID.lowercased()] else {
			return editor
		}
		editor.font = override.font ?? editor.font
		editor.fontSize = override.fontSize ?? editor.fontSize
		editor.lineNumbers = override.lineNumbers ?? editor.lineNumbers
		editor.tabWidth = override.tabWidth ?? editor.tabWidth
		editor.useSpaces = override.useSpaces ?? editor.useSpaces
		return ItsySettings(editor: editor).normalized().editor
	}

	private static func clamp(_ value: Double, min: Double, max: Double) -> Double {
		Swift.min(Swift.max(value, min), max)
	}

	private static func clamp(_ value: Int, min: Int, max: Int) -> Int {
		Swift.min(Swift.max(value, min), max)
	}
}

public struct ItsySettingsWarning: Equatable, Sendable, CustomStringConvertible {
	public var line: Int?
	public var message: String

	public init(line: Int? = nil, message: String) {
		self.line = line
		self.message = message
	}

	public var description: String {
		if let line {
			return "line \(line): \(message)"
		}
		return message
	}
}

public struct ItsySettingsLoadResult: Equatable, Sendable {
	public var settings: ItsySettings
	public var warnings: [ItsySettingsWarning]
	public var loadedFromFile: Bool

	public init(settings: ItsySettings, warnings: [ItsySettingsWarning] = [], loadedFromFile: Bool = false) {
		self.settings = settings
		self.warnings = warnings
		self.loadedFromFile = loadedFromFile
	}
}

public final class ItsySettingsStore {
	public let fileURL: URL
	private let fileManager: FileManager

	public convenience init(fileManager: FileManager = .default) {
		self.init(fileURL: Self.defaultFileURL(fileManager: fileManager), fileManager: fileManager)
	}

	public init(fileURL: URL, fileManager: FileManager = .default) {
		self.fileURL = fileURL
		self.fileManager = fileManager
	}

	public static func defaultFileURL(fileManager: FileManager = .default) -> URL {
		fileManager.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("settings.toml")
	}

	public static func workspaceFileURL(workspaceRoot: URL) -> URL {
		workspaceRoot
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("settings.toml")
	}

	public func load(fallback: ItsySettings = .default) -> ItsySettingsLoadResult {
		guard fileManager.fileExists(atPath: fileURL.path) else {
			return ItsySettingsLoadResult(settings: fallback.normalized(), loadedFromFile: false)
		}
		do {
			let contents = try String(contentsOf: fileURL, encoding: .utf8)
			var parser = ItsySettingsParser(settings: fallback)
			let result = parser.parse(contents)
			return ItsySettingsLoadResult(settings: result.settings.normalized(), warnings: result.warnings, loadedFromFile: true)
		} catch {
			return ItsySettingsLoadResult(
				settings: fallback.normalized(),
				warnings: [ItsySettingsWarning(message: "failed to read \(fileURL.path): \(error)")],
				loadedFromFile: true
			)
		}
	}

	public func load(workspaceRoot: URL?, fallback: ItsySettings = .default) -> ItsySettingsLoadResult {
		let global = load(fallback: fallback)
		guard let workspaceRoot else {
			return global
		}
		let workspaceStore = ItsySettingsStore(fileURL: Self.workspaceFileURL(workspaceRoot: workspaceRoot), fileManager: fileManager)
		let workspace = workspaceStore.load(fallback: global.settings)
		return ItsySettingsLoadResult(
			settings: workspace.settings,
			warnings: global.warnings + workspace.warnings,
			loadedFromFile: global.loadedFromFile || workspace.loadedFromFile
		)
	}

	public func save(_ settings: ItsySettings) throws {
		let directory = fileURL.deletingLastPathComponent()
		try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
		try Self.serialize(settings.normalized()).write(to: fileURL, atomically: true, encoding: .utf8)
	}

	public static func serialize(_ settings: ItsySettings) -> String {
		let settings = settings.normalized()
		return """
		# Itsy settings. Changes reload while Itsy is running.

		[editor]
		font = "\(escape(settings.editor.font))"
		font_size = \(format(settings.editor.fontSize))
		line_numbers = \(settings.editor.lineNumbers ? "true" : "false")
		tab_width = \(settings.editor.tabWidth)
		use_spaces = \(settings.editor.useSpaces ? "true" : "false")

		[editor.experimental]
		storage = "\(settings.editor.experimental.storage.rawValue)"

		[theme]
		id = "\(escape(settings.theme.id))"

		[syntax]
		preload_grammars = "\(settings.syntax.preloadGrammars.rawValue)"

		[terminal]
		font_size = \(format(settings.terminal.fontSize))
		scrollback_lines = \(settings.terminal.scrollbackLines)
		""" + serializeLanguageSettings(settings.editor.language)
	}

	private static func format(_ value: Double) -> String {
		let rounded = (value * 100).rounded() / 100
		var text = String(rounded)
		while text.contains("."), text.last == "0" {
			text.removeLast()
		}
		if text.last == "." {
			text.removeLast()
		}
		return text
	}

	private static func escape(_ value: String) -> String {
		value
			.replacingOccurrences(of: "\\", with: "\\\\")
			.replacingOccurrences(of: "\"", with: "\\\"")
			.replacingOccurrences(of: "\n", with: "\\n")
			.replacingOccurrences(of: "\t", with: "\\t")
	}

	private static func serializeLanguageSettings(_ language: [String: ItsySettings.EditorSettings.LanguageSettings]) -> String {
		guard !language.isEmpty else {
			return ""
		}
		return language.keys.sorted().map { key in
			guard let settings = language[key] else {
				return ""
			}
			var lines = ["", "[editor.language.\(key)]"]
			if let font = settings.font {
				lines.append("font = \"\(escape(font))\"")
			}
			if let fontSize = settings.fontSize {
				lines.append("font_size = \(format(fontSize))")
			}
			if let lineNumbers = settings.lineNumbers {
				lines.append("line_numbers = \(lineNumbers ? "true" : "false")")
			}
			if let tabWidth = settings.tabWidth {
				lines.append("tab_width = \(tabWidth)")
			}
			if let useSpaces = settings.useSpaces {
				lines.append("use_spaces = \(useSpaces ? "true" : "false")")
			}
			return lines.joined(separator: "\n")
		}.joined(separator: "\n") + "\n"
	}
}

public extension Notification.Name {
	static let itsySettingsChanged = Notification.Name("dev.itsy.settings.changed")
}

public enum ItsySettingsNotificationUserInfoKey {
	public static let settings = "settings"
}

public final class ItsySettingsWatcher: @unchecked Sendable {
	public typealias Handler = () -> Void

	private let urls: [URL]
	private let queue: DispatchQueue
	private let debounce: TimeInterval
	private let handler: Handler
	private var sources: [DispatchSourceFileSystemObject] = []
	private var fileDescriptors: [Int32] = []
	private var scheduled = false

	public init(
		urls: [URL],
		queue: DispatchQueue = DispatchQueue(label: "dev.itsy.settings-watcher"),
		debounce: TimeInterval = 0.12,
		handler: @escaping Handler
	) {
		self.urls = urls
		self.queue = queue
		self.debounce = debounce
		self.handler = handler
	}

	deinit {
		stop()
	}

	@discardableResult
	public func start() -> Bool {
		stop()
		var watched: Set<String> = []
		for url in urls {
			let directory = watchDirectory(for: url)
			guard watched.insert(directory.path).inserted else {
				continue
			}
			let descriptor = open(directory.path, O_EVTONLY)
			guard descriptor >= 0 else {
				continue
			}
			let source = DispatchSource.makeFileSystemObjectSource(
				fileDescriptor: descriptor,
				eventMask: [.write, .delete, .rename, .attrib, .extend, .link],
				queue: queue
			)
			source.setEventHandler { [weak self] in
				self?.schedule()
			}
			source.setCancelHandler {
				close(descriptor)
			}
			fileDescriptors.append(descriptor)
			sources.append(source)
			source.resume()
		}
		return !sources.isEmpty
	}

	public func stop() {
		sources.forEach { $0.cancel() }
		sources.removeAll()
		fileDescriptors.removeAll()
		scheduled = false
	}

	private func schedule() {
		guard !scheduled else {
			return
		}
		scheduled = true
		queue.asyncAfter(deadline: .now() + debounce) { [weak self] in
			guard let self else {
				return
			}
			self.scheduled = false
			self.handler()
		}
	}

	private func watchDirectory(for url: URL) -> URL {
		var directory = url.deletingLastPathComponent().standardizedFileURL
		while !FileManager.default.fileExists(atPath: directory.path) {
			let parent = directory.deletingLastPathComponent().standardizedFileURL
			if parent.path == directory.path {
				break
			}
			directory = parent
		}
		return directory
	}
}

enum ItsySettingsValue: Equatable {
	case string(String)
	case bool(Bool)
	case int(Int)
	case double(Double)
}

struct ItsySettingsParser {
	private var settings: ItsySettings
	private var warnings: [ItsySettingsWarning] = []

	init(settings: ItsySettings = .default) {
		self.settings = settings
	}

	mutating func parse(_ contents: String) -> ItsySettingsLoadResult {
		var section = ""
		for (offset, rawLine) in contents.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			let lineNumber = offset + 1
			let line = stripComment(String(rawLine)).trimmingCharacters(in: .whitespaces)
			guard !line.isEmpty else {
				continue
			}
			if line.hasPrefix("["), line.hasSuffix("]") {
				section = String(line.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
				if !["editor", "editor.experimental", "theme", "syntax", "terminal"].contains(section),
				   !section.hasPrefix("editor.language.") {
					warnings.append(ItsySettingsWarning(line: lineNumber, message: "unknown section [\(section)]"))
				}
				continue
			}
			guard let equals = line.firstIndex(of: "=") else {
				warnings.append(ItsySettingsWarning(line: lineNumber, message: "expected key = value"))
				continue
			}
			let rawKey = line[..<equals].trimmingCharacters(in: .whitespaces)
			let rawValue = line[line.index(after: equals)...].trimmingCharacters(in: .whitespaces)
			let key = section.isEmpty ? String(rawKey) : "\(section).\(rawKey)"
			guard let value = parseValue(String(rawValue)) else {
				warnings.append(ItsySettingsWarning(line: lineNumber, message: "invalid value for \(key)"))
				continue
			}
			assign(value, key: key, line: lineNumber)
		}
		return ItsySettingsLoadResult(settings: settings, warnings: warnings, loadedFromFile: true)
	}

	private mutating func assign(_ value: ItsySettingsValue, key: String, line: Int) {
		if assignLanguageEditor(value, key: key, line: line) {
			return
		}
		switch key {
		case "editor.font":
			if case let .string(font) = value {
				settings.editor.font = font
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "editor.font_size":
			if let number = doubleValue(value) {
				settings.editor.fontSize = number
			} else {
				warnType(key, line: line, expected: "number")
			}
		case "editor.line_numbers":
			if case let .bool(lineNumbers) = value {
				settings.editor.lineNumbers = lineNumbers
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "editor.tab_width":
			if let integer = intValue(value) {
				settings.editor.tabWidth = integer
			} else {
				warnType(key, line: line, expected: "integer")
			}
		case "editor.use_spaces":
			if case let .bool(useSpaces) = value {
				settings.editor.useSpaces = useSpaces
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "editor.experimental.storage":
			if case let .string(storage) = value, let storage = ItsySettings.EditorStorage(rawValue: storage.lowercased()) {
				settings.editor.experimental.storage = storage
			} else {
				warnType(key, line: line, expected: #""rope" or "piecetree""#)
			}
		case "theme.id":
			if case let .string(id) = value {
				settings.theme.id = id
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "syntax.preload_grammars":
			if case let .string(mode) = value, let mode = ItsySettings.SyntaxPreloadGrammars(rawValue: mode.lowercased()) {
				settings.syntax.preloadGrammars = mode
			} else {
				warnType(key, line: line, expected: #""none", "opened", or "all""#)
			}
		case "terminal.font_size":
			if let number = doubleValue(value) {
				settings.terminal.fontSize = number
			} else {
				warnType(key, line: line, expected: "number")
			}
		case "terminal.scrollback_lines":
			if let integer = intValue(value) {
				settings.terminal.scrollbackLines = integer
			} else {
				warnType(key, line: line, expected: "integer")
			}
		default:
			warnings.append(ItsySettingsWarning(line: line, message: "unknown setting \(key)"))
		}
	}

	private mutating func assignLanguageEditor(_ value: ItsySettingsValue, key: String, line: Int) -> Bool {
		let prefix = "editor.language."
		guard key.hasPrefix(prefix) else {
			return false
		}
		let suffix = key.dropFirst(prefix.count)
		guard let dot = suffix.firstIndex(of: ".") else {
			warnings.append(ItsySettingsWarning(line: line, message: "unknown setting \(key)"))
			return true
		}
		let languageID = suffix[..<dot].trimmingCharacters(in: .whitespacesAndNewlines)
		let setting = String(suffix[suffix.index(after: dot)...])
		guard !languageID.isEmpty else {
			warnings.append(ItsySettingsWarning(line: line, message: "unknown setting \(key)"))
			return true
		}
		var language = settings.editor.language[languageID] ?? ItsySettings.EditorSettings.LanguageSettings()
		switch setting {
		case "font":
			if case let .string(font) = value {
				language.font = font
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "font_size":
			if let number = doubleValue(value) {
				language.fontSize = number
			} else {
				warnType(key, line: line, expected: "number")
			}
		case "line_numbers":
			if case let .bool(lineNumbers) = value {
				language.lineNumbers = lineNumbers
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "tab_width":
			if let integer = intValue(value) {
				language.tabWidth = integer
			} else {
				warnType(key, line: line, expected: "integer")
			}
		case "use_spaces":
			if case let .bool(useSpaces) = value {
				language.useSpaces = useSpaces
			} else {
				warnType(key, line: line, expected: "bool")
			}
		default:
			warnings.append(ItsySettingsWarning(line: line, message: "unknown setting \(key)"))
		}
		settings.editor.language[languageID] = language
		return true
	}

	private mutating func warnType(_ key: String, line: Int, expected: String) {
		warnings.append(ItsySettingsWarning(line: line, message: "\(key) expects \(expected)"))
	}

	private func doubleValue(_ value: ItsySettingsValue) -> Double? {
		switch value {
		case let .double(value):
			return value
		case let .int(value):
			return Double(value)
		default:
			return nil
		}
	}

	private func intValue(_ value: ItsySettingsValue) -> Int? {
		if case let .int(value) = value {
			return value
		}
		return nil
	}

	private func parseValue(_ raw: String) -> ItsySettingsValue? {
		if raw.hasPrefix("\""), raw.hasSuffix("\""), raw.count >= 2 {
			return .string(unescape(String(raw.dropFirst().dropLast())))
		}
		switch raw.lowercased() {
		case "true":
			return .bool(true)
		case "false":
			return .bool(false)
		default:
			break
		}
		if raw.contains("."), let value = Double(raw) {
			return .double(value)
		}
		if let value = Int(raw) {
			return .int(value)
		}
		return nil
	}

	private func stripComment(_ line: String) -> String {
		var quoted = false
		var escaped = false
		for index in line.indices {
			let character = line[index]
			if escaped {
				escaped = false
				continue
			}
			if character == "\\" {
				escaped = true
				continue
			}
			if character == "\"" {
				quoted.toggle()
				continue
			}
			if character == "#", !quoted {
				return String(line[..<index])
			}
		}
		return line
	}

	private func unescape(_ value: String) -> String {
		var result = ""
		var escaping = false
		for character in value {
			if escaping {
				switch character {
				case "n":
					result.append("\n")
				case "t":
					result.append("\t")
				default:
					result.append(character)
				}
				escaping = false
				continue
			}
			if character == "\\" {
				escaping = true
			} else {
				result.append(character)
			}
		}
		if escaping {
			result.append("\\")
		}
		return result
	}
}
