import Foundation

public enum KeymapLoaderError: Error, Equatable {
	case invalidSection(String)
	case bindingOutsideMode(Int)
	case invalidBinding(Int)
	case invalidKey(String)
	case invalidMode(String)
}

public enum KeymapLoader {
	public static func load(_ contents: String) throws -> [KeyBinding] {
		var mode: Mode?
		var bindings: [KeyBinding] = []
		for (offset, rawLine) in contents.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			let lineNumber = offset + 1
			let line = stripComment(String(rawLine)).trimmingCharacters(in: .whitespaces)
			guard !line.isEmpty else {
				continue
			}
			if line.hasPrefix("[") {
				mode = try parseSection(line)
				continue
			}
			guard let currentMode = mode else {
				throw KeymapLoaderError.bindingOutsideMode(lineNumber)
			}
			let pair = try parseBinding(line, lineNumber: lineNumber)
			bindings.append(KeyBinding(mode: currentMode, chord: try parseChord(pair.key), commandID: pair.commandID))
		}
		return bindings
	}

	private static func parseSection(_ line: String) throws -> Mode {
		guard line.hasSuffix("]") else {
			throw KeymapLoaderError.invalidSection(line)
		}
		let name = String(line.dropFirst().dropLast())
		guard name.hasPrefix("mode.") else {
			throw KeymapLoaderError.invalidSection(line)
		}
		return try parseMode(String(name.dropFirst("mode.".count)))
	}

	private static func parseMode(_ name: String) throws -> Mode {
		switch name {
		case "normal":
			return .normal
		case "insert":
			return .insert
		case "visual":
			return .visual
		case "operatorPending":
			return .operatorPending
		case "command":
			return .command
		case "emacs":
			return .emacs
		default:
			throw KeymapLoaderError.invalidMode(name)
		}
	}

	private static func parseBinding(_ line: String, lineNumber: Int) throws -> (key: String, commandID: String) {
		guard let equals = firstEqualsOutsideQuotes(in: line) else {
			throw KeymapLoaderError.invalidBinding(lineNumber)
		}
		let lhs = line[..<equals].trimmingCharacters(in: .whitespaces)
		let rhs = line[line.index(after: equals)...].trimmingCharacters(in: .whitespaces)
		guard let key = parseQuoted(lhs), let commandID = parseQuoted(rhs) else {
			throw KeymapLoaderError.invalidBinding(lineNumber)
		}
		return (key, commandID)
	}

	private static func parseChord(_ value: String) throws -> [Key] {
		let tokens = value.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
		if tokens.count > 1 {
			return try tokens.map(parseKeyToken(_:))
		}
		if isModifiedKey(value) || specialKeyName(value) != nil {
			return [try parseKeyToken(value)]
		}
		return value.map { Key(String($0).lowercased()) }
	}

	private static func parseKeyToken(_ token: String) throws -> Key {
		var modifiers: KeyModifiers = []
		var pieces = token.split(separator: "-", omittingEmptySubsequences: true).map(String.init)
		guard let keyName = pieces.popLast() else {
			throw KeymapLoaderError.invalidKey(token)
		}
		for modifier in pieces {
			switch modifier.lowercased() {
			case "cmd", "command", "m":
				modifiers.insert(.command)
			case "shift", "s":
				modifiers.insert(.shift)
			case "opt", "option", "alt", "a":
				modifiers.insert(.option)
			case "ctrl", "control", "c":
				modifiers.insert(.control)
			default:
				throw KeymapLoaderError.invalidKey(token)
			}
		}
		return Key(specialKeyName(keyName) ?? keyName.lowercased(), modifiers: modifiers)
	}

	private static func isModifiedKey(_ value: String) -> Bool {
		value.contains("-")
	}

	private static func specialKeyName(_ value: String) -> String? {
		switch value.lowercased() {
		case "esc", "escape":
			return "escape"
		case "return", "enter":
			return "return"
		case "tab":
			return "tab"
		case "delete", "backspace":
			return "delete"
		case "left", "right", "up", "down":
			return value.lowercased()
		default:
			return nil
		}
	}

	private static func firstEqualsOutsideQuotes(in line: String) -> String.Index? {
		var escaped = false
		var quoted = false
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
			if character == "=", !quoted {
				return index
			}
		}
		return nil
	}

	private static func stripComment(_ line: String) -> String {
		var escaped = false
		var quoted = false
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

	private static func parseQuoted(_ value: String) -> String? {
		guard value.count >= 2, value.first == "\"", value.last == "\"" else {
			return nil
		}
		var result = ""
		var escaped = false
		for character in value.dropFirst().dropLast() {
			if escaped {
				switch character {
				case "n":
					result.append("\n")
				case "t":
					result.append("\t")
				default:
					result.append(character)
				}
				escaped = false
				continue
			}
			if character == "\\" {
				escaped = true
			} else {
				result.append(character)
			}
		}
		return escaped ? nil : result
	}
}
