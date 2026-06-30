import Foundation

public struct ItsySettings: Equatable, Sendable {
	public struct EditorSettings: Equatable, Sendable {
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

		public init(font: String = Self.defaultFont, fontSize: Double = Self.defaultFontSize, lineNumbers: Bool = false, tabWidth: Int = Self.defaultTabWidth) {
			self.font = font
			self.fontSize = fontSize
			self.lineNumbers = lineNumbers
			self.tabWidth = tabWidth
		}
	}

	public struct ThemeSettings: Equatable, Sendable {
		public static let defaultID = "bundled:default-light"

		public var id: String

		public init(id: String = Self.defaultID) {
			self.id = id
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
	public var terminal: TerminalSettings

	public init(editor: EditorSettings = EditorSettings(), theme: ThemeSettings = ThemeSettings(), terminal: TerminalSettings = TerminalSettings()) {
		self.editor = editor
		self.theme = theme
		self.terminal = terminal
	}

	public func normalized() -> ItsySettings {
		var copy = self
		if copy.editor.font.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.editor.font = EditorSettings.defaultFont
		}
		copy.editor.fontSize = Self.clamp(copy.editor.fontSize, min: EditorSettings.minFontSize, max: EditorSettings.maxFontSize)
		copy.editor.tabWidth = Self.clamp(copy.editor.tabWidth, min: EditorSettings.minTabWidth, max: EditorSettings.maxTabWidth)
		if copy.theme.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.theme.id = ThemeSettings.defaultID
		}
		copy.terminal.fontSize = Self.clamp(copy.terminal.fontSize, min: TerminalSettings.minFontSize, max: TerminalSettings.maxFontSize)
		copy.terminal.scrollbackLines = Self.clamp(copy.terminal.scrollbackLines, min: TerminalSettings.minScrollbackLines, max: TerminalSettings.maxScrollbackLines)
		return copy
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

	public func save(_ settings: ItsySettings) throws {
		let directory = fileURL.deletingLastPathComponent()
		try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
		try Self.serialize(settings.normalized()).write(to: fileURL, atomically: true, encoding: .utf8)
	}

	public static func serialize(_ settings: ItsySettings) -> String {
		let settings = settings.normalized()
		return """
		# Itsy settings. Reload from Settings or restart Itsy after editing.

		[editor]
		font = "\(escape(settings.editor.font))"
		font_size = \(format(settings.editor.fontSize))
		line_numbers = \(settings.editor.lineNumbers ? "true" : "false")
		tab_width = \(settings.editor.tabWidth)

		[theme]
		id = "\(escape(settings.theme.id))"

		[terminal]
		font_size = \(format(settings.terminal.fontSize))
		scrollback_lines = \(settings.terminal.scrollbackLines)
		"""
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
				if !["editor", "theme", "terminal"].contains(section) {
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
		case "theme.id":
			if case let .string(id) = value {
				settings.theme.id = id
			} else {
				warnType(key, line: line, expected: "string")
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
