#!/usr/bin/env swift
import Darwin
import Foundation

struct Binding {
	let file: URL
	let line: Int
	let key: String
	let commandID: String
}

let repo = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let keymapDir = repo.appendingPathComponent("Sources/ItsyKeymap/Resources")
let sourcesDir = repo.appendingPathComponent("Sources")
let hiddenCatalogURL = repo.appendingPathComponent("Sources/ItsyApp/KeymapCommandCatalog.swift")

func fail(_ message: String) -> Never {
	FileHandle.standardError.write(Data((message + "\n").utf8))
	exit(EXIT_FAILURE)
}

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

func parseQuoted(_ value: String, file: URL, line: Int) throws -> String {
	guard value.count >= 2, value.first == "\"", value.last == "\"" else {
		throw NSError(
			domain: "validate_keymaps",
			code: 1,
			userInfo: [NSLocalizedDescriptionKey: "\(relativePath(file)):\(line): expected quoted string"]
		)
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
		throw NSError(
			domain: "validate_keymaps",
			code: 1,
			userInfo: [NSLocalizedDescriptionKey: "\(relativePath(file)):\(line): dangling escape"]
		)
	}
	return result
}

func keymapFiles() throws -> [URL] {
	try FileManager.default.contentsOfDirectory(
		at: keymapDir,
		includingPropertiesForKeys: nil
	)
	.filter { $0.lastPathComponent.hasPrefix("keys.") && $0.pathExtension == "toml" }
	.sorted { $0.path < $1.path }
}

func parseBindings(in file: URL) throws -> [Binding] {
	let contents = try String(contentsOf: file, encoding: .utf8)
	var bindings: [Binding] = []
	var hasMode = false
	for (offset, rawLine) in contents.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
		let lineNumber = offset + 1
		let line = stripComment(String(rawLine))
		guard !line.isEmpty else {
			continue
		}
		if line.hasPrefix("[") {
			hasMode = true
			continue
		}
		guard hasMode else {
			throw NSError(
				domain: "validate_keymaps",
				code: 1,
				userInfo: [NSLocalizedDescriptionKey: "\(relativePath(file)):\(lineNumber): binding outside mode"]
			)
		}
		guard let equals = firstEqualsOutsideQuotes(in: line) else {
			throw NSError(
				domain: "validate_keymaps",
				code: 1,
				userInfo: [NSLocalizedDescriptionKey: "\(relativePath(file)):\(lineNumber): invalid binding"]
			)
		}
		let key = try parseQuoted(String(line[..<equals]).trimmingCharacters(in: .whitespaces), file: file, line: lineNumber)
		let commandID = try parseQuoted(String(line[line.index(after: equals)...]).trimmingCharacters(in: .whitespaces), file: file, line: lineNumber)
		bindings.append(Binding(file: file, line: lineNumber, key: key, commandID: commandID))
	}
	return bindings
}

func swiftFiles(in root: URL) throws -> [URL] {
	let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) ?? FileManager.default.enumerator(atPath: root.path)!
	var files: [URL] = []
	for case let url as URL in enumerator where url.pathExtension == "swift" {
		files.append(url)
	}
	return files
}

func literalCommandIDs(inSwiftFiles files: [URL]) throws -> Set<String> {
	let regex = try NSRegularExpression(pattern: #"Command\s*\(\s*id:\s*"([^"]+)""#)
	var ids = Set<String>()
	for file in files {
		let contents = try String(contentsOf: file, encoding: .utf8)
		let nsRange = NSRange(contents.startIndex ..< contents.endIndex, in: contents)
		for match in regex.matches(in: contents, range: nsRange) {
			guard let range = Range(match.range(at: 1), in: contents) else {
				continue
			}
			ids.insert(String(contents[range]))
		}
	}
	return ids
}

func hiddenCatalogIDs() throws -> Set<String> {
	let contents = try String(contentsOf: hiddenCatalogURL, encoding: .utf8)
	guard let declaration = contents.range(of: "static let hiddenCommandIDs"),
	      let equals = contents[declaration.upperBound...].firstIndex(of: "="),
	      let start = contents[equals...].firstIndex(of: "["),
	      let end = contents[start...].firstIndex(of: "]")
	else {
		throw NSError(
			domain: "validate_keymaps",
			code: 1,
			userInfo: [NSLocalizedDescriptionKey: "\(relativePath(hiddenCatalogURL)): missing hiddenCommandIDs array"]
		)
	}
	let body = String(contents[contents.index(after: start) ..< end])
	let regex = try NSRegularExpression(pattern: #""([^"]+)""#)
	let nsRange = NSRange(body.startIndex ..< body.endIndex, in: body)
	var ids = Set<String>()
	for match in regex.matches(in: body, range: nsRange) {
		guard let range = Range(match.range(at: 1), in: body) else {
			continue
		}
		ids.insert(String(body[range]))
	}
	return ids
}

func relativePath(_ url: URL) -> String {
	let path = url.standardizedFileURL.path
	let prefix = repo.standardizedFileURL.path + "/"
	return path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : path
}

do {
	let bindings = try keymapFiles().flatMap(parseBindings(in:))
	let registeredIDs = try literalCommandIDs(inSwiftFiles: swiftFiles(in: sourcesDir)).union(hiddenCatalogIDs())
	let failures = bindings.filter { !registeredIDs.contains($0.commandID) }
	if !failures.isEmpty {
		for failure in failures {
			FileHandle.standardError.write(Data("\(relativePath(failure.file)):\(failure.line): unknown command id \"\(failure.commandID)\" for key \"\(failure.key)\"\n".utf8))
		}
		exit(EXIT_FAILURE)
	}
	print("Validated \(bindings.count) keymap bindings against \(registeredIDs.count) registered command ids.")
} catch {
	fail("\(error.localizedDescription)")
}
