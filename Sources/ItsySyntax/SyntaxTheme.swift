import Foundation

public enum SyntaxThemeError: Error, Equatable {
	case invalidLine(Int)
	case invalidColor(Int, String)
	case themeLoadFailed(String)
}

public struct SyntaxColor: Sendable, Equatable {
	public var red: Float
	public var green: Float
	public var blue: Float
	public var alpha: Float

	public init(red: Float, green: Float, blue: Float, alpha: Float = 1) {
		self.red = red
		self.green = green
		self.blue = blue
		self.alpha = alpha
	}

	public init(hex: String) throws {
		let value = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
		guard value.count == 6 || value.count == 8, let raw = UInt32(value, radix: 16) else {
			throw SyntaxThemeError.invalidColor(0, hex)
		}
		let divisor: Float = 255
		if value.count == 6 {
			red = Float((raw >> 16) & 0xff) / divisor
			green = Float((raw >> 8) & 0xff) / divisor
			blue = Float(raw & 0xff) / divisor
			alpha = 1
		} else {
			red = Float((raw >> 24) & 0xff) / divisor
			green = Float((raw >> 16) & 0xff) / divisor
			blue = Float((raw >> 8) & 0xff) / divisor
			alpha = Float(raw & 0xff) / divisor
		}
	}
}

public struct SyntaxTheme: Sendable, Equatable {
	public var colors: [String: SyntaxColor]
	public static let selectedThemeDefaultsKey = "dev.itsy.editor.syntaxTheme"
	public static let userThemeDirectoryName = "themes"
	public static let defaultChoiceID = "bundled:default-light"
	public static let bundledChoices = [
		SyntaxThemeChoice(id: "bundled:default-dark", displayName: "Default Dark"),
		SyntaxThemeChoice(id: "bundled:default-light", displayName: "Default Light"),
		SyntaxThemeChoice(id: "bundled:solarized-light", displayName: "Solarized Light"),
		SyntaxThemeChoice(id: "bundled:solarized-dark", displayName: "Solarized Dark"),
		SyntaxThemeChoice(id: "bundled:gruvbox-light", displayName: "Gruvbox Light"),
		SyntaxThemeChoice(id: "bundled:gruvbox-dark", displayName: "Gruvbox Dark"),
		SyntaxThemeChoice(id: "bundled:nord", displayName: "Nord"),
		SyntaxThemeChoice(id: "bundled:catppuccin-mocha", displayName: "Catppuccin Mocha"),
		SyntaxThemeChoice(id: "bundled:catppuccin-latte", displayName: "Catppuccin Latte"),
		SyntaxThemeChoice(id: "bundled:tokyo-night", displayName: "Tokyo Night"),
	]
	public static let standardCaptures = [
		"keyword.control",
		"keyword.function",
		"keyword.operator",
		"keyword.return",
		"type.builtin",
		"type.parameter",
		"function.builtin",
		"function.macro",
		"function.method",
		"variable.builtin",
		"variable.member",
		"constant.builtin",
		"constant.macro",
		"string.escape",
		"string.regexp",
		"string.special",
		"number.float",
		"boolean",
		"character",
		"character.special",
		"comment.documentation",
		"punctuation.bracket",
		"punctuation.delimiter",
		"punctuation.special",
		"operator",
		"attribute",
		"tag",
		"label",
		"namespace",
		"module",
		"property",
		"field",
		"parameter",
		"error",
		"diff.plus",
		"diff.minus",
		"markup.heading",
		"markup.link",
		"markup.list",
		"markup.bold",
		"markup.italic",
		"markup.raw",
		"markup.quote",
	]
	private static let captureFallbacks: [String: [String]] = [
		"keyword.control": ["keyword"],
		"keyword.function": ["keyword"],
		"keyword.operator": ["keyword"],
		"keyword.return": ["keyword.control"],
		"type.parameter": ["type"],
		"function.builtin": ["function"],
		"function.macro": ["function"],
		"function.method": ["function"],
		"variable.builtin": ["variable"],
		"variable.member": ["variable"],
		"constant.builtin": ["constant"],
		"constant.macro": ["constant"],
		"string.escape": ["string"],
		"string.regexp": ["string"],
		"string.regex": ["string.regexp"],
		"string.special": ["string"],
		"number.float": ["number"],
		"boolean": ["constant.builtin"],
		"character": ["string"],
		"character.special": ["character", "string"],
		"comment.documentation": ["comment"],
		"punctuation.bracket": ["punctuation"],
		"punctuation.delimiter": ["punctuation"],
		"punctuation.special": ["punctuation"],
		"attribute": ["variable"],
		"tag": ["type"],
		"label": ["variable"],
		"namespace": ["type"],
		"module": ["namespace", "type"],
		"property": ["variable.member"],
		"field": ["variable.member"],
		"parameter": ["variable.parameter"],
		"error": ["constant"],
		"diff.plus": ["comment"],
		"diff.minus": ["string"],
		"markup.heading": ["keyword"],
		"markup.link": ["string"],
		"markup.list": ["punctuation"],
		"markup.bold": ["variable"],
		"markup.italic": ["variable"],
		"markup.raw": ["string"],
		"markup.quote": ["comment"],
		"comment.doc": ["comment.documentation"],
		"comment.doc.__attribute__": ["comment.documentation"],
		"comment.block.documentation": ["comment.documentation"],
		"conditional": ["keyword.control.conditional", "keyword.control"],
		"repeat": ["keyword.control.repeat", "keyword.control"],
		"import": ["keyword.control.import", "keyword.control"],
		"include": ["keyword.control.import", "keyword.control"],
		"exception": ["keyword.control.exception", "keyword.control"],
		"keyword.conditional": ["keyword.control.conditional", "keyword.control"],
		"keyword.coroutine": ["keyword.control"],
		"keyword.directive": ["keyword.control"],
		"keyword.exception": ["keyword.control.exception", "keyword.control"],
		"keyword.import": ["keyword.control.import", "keyword.control"],
		"keyword.modifier": ["keyword"],
		"keyword.repeat": ["keyword.control.repeat", "keyword.control"],
		"keyword.type": ["keyword"],
		"type.definition": ["type"],
		"type.qualifier": ["type"],
		"function.call": ["function"],
		"function.method.call": ["function.method"],
		"function.special": ["function"],
		"constant.character.escape": ["character.special"],
		"string.special.symbol": ["string.special"],
		"string.special.key": ["string.special"],
		"float": ["number.float"],
		"escape": ["string.escape"],
		"delimiter": ["punctuation.delimiter"],
		"text.title": ["markup.heading"],
		"text.uri": ["markup.link"],
		"text.reference": ["markup.link"],
		"text.literal": ["markup.raw"],
		"text.strong": ["markup.bold"],
		"text.emphasis": ["markup.italic"],
		"constructor": ["type"],
		"storageclass": ["keyword"],
		"cImport": ["function.macro"],
		"charset": ["keyword"],
		"media": ["keyword"],
		"supports": ["keyword"],
		"keyframes": ["keyword"],
		"tag.error": ["error", "tag"],
	]

	public init(colors: [String: SyntaxColor]) {
		self.colors = colors
	}

	public func color(for capture: String) -> SyntaxColor? {
		for key in Self.lookupKeys(for: capture) {
			if let color = colors[key] {
				return color
			}
		}
		return nil
	}

	private static func lookupKeys(for capture: String) -> [String] {
		var keys: [String] = []
		var seen = Set<String>()
		func append(_ key: String) {
			if seen.insert(key).inserted {
				keys.append(key)
			}
		}
		func appendParents(of key: String) {
			var pieces = key.split(separator: ".").map(String.init)
			while pieces.count > 1 {
				pieces.removeLast()
				append(pieces.joined(separator: "."))
			}
		}
		append(capture)
		for fallback in captureFallbacks[capture] ?? [] {
			append(fallback)
			appendParents(of: fallback)
		}
		appendParents(of: capture)
		return keys
	}

	public static func loadDefaultDark() throws -> SyntaxTheme {
		try loadBundled(name: "default-dark")
	}

	public static func loadDefaultLight() throws -> SyntaxTheme {
		try loadBundled(name: "default-light")
	}

	public static func loadUserOrDefault(fileManager: FileManager = .default) throws -> SyntaxTheme {
		try loadSelectedOrDefault(fileManager: fileManager)
	}

	public static func loadSelectedOrDefault(defaults: UserDefaults = .standard, fileManager: FileManager = .default) throws -> SyntaxTheme {
		if let selectedID = defaults.string(forKey: selectedThemeDefaultsKey), !selectedID.isEmpty {
			if let selectedTheme = try? loadChoice(id: selectedID, fileManager: fileManager) {
				return selectedTheme
			}
		}
		let url = fileManager.homeDirectoryForCurrentUser
			.appendingPathComponent(".config")
			.appendingPathComponent("itsy")
			.appendingPathComponent("theme.toml")
		if fileManager.fileExists(atPath: url.path) {
			return try parse(String(contentsOf: url, encoding: .utf8))
		}
		return try loadDefaultLight()
	}

	public static func availableChoices(fileManager: FileManager = .default) -> [SyntaxThemeChoice] {
		availableChoices(userThemesURL: userThemesDirectory(fileManager: fileManager), fileManager: fileManager)
	}

	static func availableChoices(userThemesURL: URL, fileManager: FileManager = .default) -> [SyntaxThemeChoice] {
		var choices = bundledChoices
		let urls = (try? fileManager.contentsOfDirectory(
			at: userThemesURL,
			includingPropertiesForKeys: nil,
			options: [.skipsHiddenFiles]
		)) ?? []
		let userChoices = urls
			.filter { $0.pathExtension == "toml" }
			.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
			.map { url in
				SyntaxThemeChoice(
					id: "user:\(url.lastPathComponent)",
					displayName: url.deletingPathExtension().lastPathComponent
				)
			}
		choices.append(contentsOf: userChoices)
		return choices
	}

	public static func userThemesDirectory(fileManager: FileManager = .default) -> URL {
		fileManager.homeDirectoryForCurrentUser
			.appendingPathComponent(".config")
			.appendingPathComponent("itsy")
			.appendingPathComponent(userThemeDirectoryName, isDirectory: true)
	}

	public static func loadChoice(id: String, fileManager: FileManager = .default) throws -> SyntaxTheme {
		if id.hasPrefix("bundled:") {
			return try loadBundled(name: String(id.dropFirst("bundled:".count)))
		}
		if id.hasPrefix("user:") {
			let fileName = String(id.dropFirst("user:".count))
			let url = userThemesDirectory(fileManager: fileManager).appendingPathComponent(fileName)
			return try parse(String(contentsOf: url, encoding: .utf8))
		}
		throw SyntaxThemeError.themeLoadFailed(id)
	}

	public static func loadBundled(name: String) throws -> SyntaxTheme {
		guard let url = Bundle.module.url(forResource: name, withExtension: "toml", subdirectory: "Resources/themes") else {
			throw SyntaxThemeError.themeLoadFailed(name)
		}
		return try parse(String(contentsOf: url, encoding: .utf8))
	}

	public static func parse(_ contents: String) throws -> SyntaxTheme {
		var colors: [String: SyntaxColor] = [:]
		for (offset, rawLine) in contents.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			let lineNumber = offset + 1
			let line = stripComment(String(rawLine)).trimmingCharacters(in: .whitespaces)
			guard !line.isEmpty else {
				continue
			}
			guard let equals = line.firstIndex(of: "=") else {
				throw SyntaxThemeError.invalidLine(lineNumber)
			}
			let key = unquote(line[..<equals].trimmingCharacters(in: .whitespaces))
			let value = unquote(line[line.index(after: equals)...].trimmingCharacters(in: .whitespaces))
			do {
				colors[key] = try SyntaxColor(hex: value)
			} catch {
				throw SyntaxThemeError.invalidColor(lineNumber, value)
			}
		}
		return SyntaxTheme(colors: colors)
	}

	private static func stripComment(_ line: String) -> String {
		var quoted = false
		for index in line.indices {
			if line[index] == "\"" {
				quoted.toggle()
			}
			if line[index] == "#", !quoted {
				return String(line[..<index])
			}
		}
		return line
	}

	private static func unquote(_ value: String) -> String {
		if value.count >= 2, value.first == "\"", value.last == "\"" {
			return String(value.dropFirst().dropLast())
		}
		return value
	}
}

public struct SyntaxThemeChoice: Sendable, Equatable {
	public var id: String
	public var displayName: String

	public init(id: String, displayName: String) {
		self.id = id
		self.displayName = displayName
	}
}
