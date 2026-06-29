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
	public static let selectedThemeDefaultsKey = "dev.pico.editor.syntaxTheme"
	public static let userThemeDirectoryName = "themes"
	public static let defaultChoiceID = "bundled:default-dark"

	public init(colors: [String: SyntaxColor]) {
		self.colors = colors
	}

	public func color(for capture: String) -> SyntaxColor? {
		if let color = colors[capture] {
			return color
		}
		var pieces = capture.split(separator: ".").map(String.init)
		while pieces.count > 1 {
			pieces.removeLast()
			if let color = colors[pieces.joined(separator: ".")] {
				return color
			}
		}
		return nil
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
			.appendingPathComponent("pico")
			.appendingPathComponent("theme.toml")
		if fileManager.fileExists(atPath: url.path) {
			return try parse(String(contentsOf: url, encoding: .utf8))
		}
		return try loadDefaultDark()
	}

	public static func availableChoices(fileManager: FileManager = .default) -> [SyntaxThemeChoice] {
		var choices = [
			SyntaxThemeChoice(id: "bundled:default-dark", displayName: "Default Dark"),
			SyntaxThemeChoice(id: "bundled:default-light", displayName: "Default Light"),
		]
		let userThemesURL = userThemesDirectory(fileManager: fileManager)
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
			.appendingPathComponent("pico")
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
