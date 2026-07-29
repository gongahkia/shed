#!/usr/bin/env swift
import Foundation

struct Binding {
	let key: String
	let command: String
}

struct ModeBindings {
	let name: String
	var bindings: [Binding]
}

let repo = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let profiles = ["plain", "vim", "emacs"]
let resourceDir = repo.appendingPathComponent("Sources/ItsyKeymap/Resources")
let outputURL = repo.appendingPathComponent("docs/keymap-reference.md")

func stripComment(_ line: String) -> String {
	var escaped = false
	var quoted = false
	var result = ""
	for character in line {
		if escaped {
			result.append(character)
			escaped = false
			continue
		}
		if character == "\\" {
			result.append(character)
			escaped = true
			continue
		}
		if character == "\"" {
			result.append(character)
			quoted.toggle()
			continue
		}
		if character == "#", !quoted {
			break
		}
		result.append(character)
	}
	return result.trimmingCharacters(in: .whitespaces)
}

func firstEqualsOutsideQuotes(in line: String) -> String.Index? {
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

func parseQuoted(_ value: String, lineNumber: Int) throws -> String {
	guard value.count >= 2, value.first == "\"", value.last == "\"" else {
		throw NSError(domain: "gen_keymap_docs", code: 1, userInfo: [NSLocalizedDescriptionKey: "line \(lineNumber): expected quoted string"])
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
	if escaped {
		throw NSError(domain: "gen_keymap_docs", code: 1, userInfo: [NSLocalizedDescriptionKey: "line \(lineNumber): dangling escape"])
	}
	return result
}

func parseProfile(_ profile: String) throws -> [ModeBindings] {
	let url = resourceDir.appendingPathComponent("keys.\(profile).toml")
	let contents = try String(contentsOf: url, encoding: .utf8)
	var modes: [ModeBindings] = []
	var currentModeIndex: Int?

	for (offset, rawLine) in contents.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
		let lineNumber = offset + 1
		let line = stripComment(String(rawLine))
		guard !line.isEmpty else {
			continue
		}
		if line.hasPrefix("[") {
			guard line.hasSuffix("]"), line.hasPrefix("[mode.") else {
				throw NSError(domain: "gen_keymap_docs", code: 1, userInfo: [NSLocalizedDescriptionKey: "line \(lineNumber): invalid section \(line)"])
			}
			let name = String(line.dropFirst("[mode.".count).dropLast())
			modes.append(ModeBindings(name: name, bindings: []))
			currentModeIndex = modes.indices.last
			continue
		}
		guard let modeIndex = currentModeIndex else {
			throw NSError(domain: "gen_keymap_docs", code: 1, userInfo: [NSLocalizedDescriptionKey: "line \(lineNumber): binding outside mode"])
		}
		guard let equals = firstEqualsOutsideQuotes(in: line) else {
			throw NSError(domain: "gen_keymap_docs", code: 1, userInfo: [NSLocalizedDescriptionKey: "line \(lineNumber): invalid binding"])
		}
		let key = try parseQuoted(String(line[..<equals]).trimmingCharacters(in: .whitespaces), lineNumber: lineNumber)
		let command = try parseQuoted(String(line[line.index(after: equals)...]).trimmingCharacters(in: .whitespaces), lineNumber: lineNumber)
		modes[modeIndex].bindings.append(Binding(key: key, command: command))
	}
	return modes
}

func code(_ value: String) -> String {
	"`\(value.replacingOccurrences(of: "`", with: "\\`"))`"
}

func title(_ value: String) -> String {
	value.prefix(1).uppercased() + value.dropFirst()
}

var output = """
# Keymap Reference

Generated from `Sources/ItsyKeymap/Resources/keys.*.toml`.

Regenerate:

```sh
scripts/gen_keymap_docs.swift
```

"""
output += "\n"

for profile in profiles {
	let modes = try parseProfile(profile)
	let total = modes.reduce(0) { $0 + $1.bindings.count }
	output += "## \(title(profile))\n\n"
	output += "- Source: `Sources/ItsyKeymap/Resources/keys.\(profile).toml`\n"
	output += "- Bindings: `\(total)`\n\n"
	for mode in modes {
		output += "### mode.\(mode.name)\n\n"
		output += "| Key | Command |\n"
		output += "|---|---|\n"
		for binding in mode.bindings {
			output += "| \(code(binding.key)) | \(code(binding.command)) |\n"
		}
		output += "\n"
	}
}

try output.write(to: outputURL, atomically: true, encoding: .utf8)
print(outputURL.path)
