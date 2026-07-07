import Foundation

public struct DebugAdapterRegistry: Equatable, Sendable {
	private var adaptersByID: [String: DebugAdapterConfig]

	public init(adapters: [DebugAdapterConfig] = DebugAdapterRegistry.bundledDefaults) {
		adaptersByID = [:]
		for adapter in adapters {
			adaptersByID[adapter.id] = adapter
		}
	}

	public var adapters: [DebugAdapterConfig] {
		Array(adaptersByID.values).sorted { $0.id < $1.id }
	}

	public func adapter(id: String) -> DebugAdapterConfig? {
		adaptersByID[id]
	}

	public mutating func register(_ adapter: DebugAdapterConfig) {
		adaptersByID[adapter.id] = adapter
	}

	public func merging(_ override: DebugAdapterRegistry) -> DebugAdapterRegistry {
		var copy = self
		for adapter in override.adapters {
			copy.register(adapter)
		}
		return copy
	}

	public static let bundledDefaults: [DebugAdapterConfig] = [
		DebugAdapterConfig(id: "debugpy", command: "python3", args: ["-m", "debugpy.adapter"]),
		DebugAdapterConfig(id: "delve", command: "dlv", args: ["dap"]),
		DebugAdapterConfig(id: "lldb", command: "lldb-dap"),
		DebugAdapterConfig(id: "lldb-dap", command: "lldb-dap"),
		DebugAdapterConfig(id: "vscode-js-debug", command: "js-debug-adapter"),
	]
}

public enum DebugAdapterRegistryLoaderError: Error, Equatable {
	case fileNotFound
	case decodeFailed(String)
}

public struct DebugAdapterRegistryLoader {
	public var userConfigURL: URL
	public var fileManager: FileManager

	public init(userConfigURL: URL = DebugAdapterRegistryLoader.defaultUserConfigURL, fileManager: FileManager = .default) {
		self.userConfigURL = userConfigURL
		self.fileManager = fileManager
	}

	public static var defaultUserConfigURL: URL {
		FileManager.default.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("dap.toml")
	}

	public func workspaceConfigURL(for workspaceRoot: URL) -> URL {
		workspaceRoot
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("dap.toml")
	}

	public func load(workspaceRoot: URL) throws -> DebugAdapterRegistry {
		let user = try loadIfPresent(userConfigURL)
		let workspace = try loadIfPresent(workspaceConfigURL(for: workspaceRoot))
		return DebugAdapterRegistry().merging(user).merging(workspace)
	}

	public func load(from url: URL) throws -> DebugAdapterRegistry {
		guard fileManager.fileExists(atPath: url.path) else {
			throw DebugAdapterRegistryLoaderError.fileNotFound
		}
		do {
			let text = try String(contentsOf: url, encoding: .utf8)
			return try DebugAdapterRegistryTOMLParser().parse(text)
		} catch let error as DebugAdapterRegistryLoaderError {
			throw error
		} catch {
			throw DebugAdapterRegistryLoaderError.decodeFailed(String(describing: error))
		}
	}

	private func loadIfPresent(_ url: URL) throws -> DebugAdapterRegistry {
		guard fileManager.fileExists(atPath: url.path) else {
			return DebugAdapterRegistry(adapters: [])
		}
		return try load(from: url)
	}
}

private struct DebugAdapterRegistryTOMLParser {
	private enum Value: Equatable {
		case string(String)
		case strings([String])
	}

	func parse(_ text: String) throws -> DebugAdapterRegistry {
		var adapters: [String: DebugAdapterConfig] = [:]
		var section: String?
		for (offset, rawLine) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			let line = stripComment(String(rawLine)).trimmingCharacters(in: .whitespaces)
			guard !line.isEmpty else {
				continue
			}
			if line.hasPrefix("["), line.hasSuffix("]") {
				section = String(line.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
				continue
			}
			guard let id = section, !id.isEmpty, let equals = line.firstIndex(of: "=") else {
				throw DebugAdapterRegistryLoaderError.decodeFailed("line \(offset + 1): expected [adapter] and key = value")
			}
			let key = String(line[..<equals]).trimmingCharacters(in: .whitespaces)
			let rawValue = String(line[line.index(after: equals)...]).trimmingCharacters(in: .whitespaces)
			guard let value = parseValue(rawValue) else {
				throw DebugAdapterRegistryLoaderError.decodeFailed("line \(offset + 1): invalid value")
			}
			var adapter = adapters[id] ?? DebugAdapterConfig(id: id, command: "")
			switch (key, value) {
			case ("command", let .string(command)):
				adapter.command = command
			case ("type", let .string(type)):
				adapter.type = type
			case ("args", let .strings(args)):
				adapter.args = args
			default:
				throw DebugAdapterRegistryLoaderError.decodeFailed("line \(offset + 1): unsupported key \(key)")
			}
			adapters[id] = adapter
		}
		var registry = DebugAdapterRegistry(adapters: [])
		for (id, adapter) in adapters {
			guard !adapter.command.isEmpty else {
				throw DebugAdapterRegistryLoaderError.decodeFailed("\(id): command is required")
			}
			registry.register(adapter)
		}
		return registry
	}

	private func parseValue(_ raw: String) -> Value? {
		if raw.hasPrefix("\""), raw.hasSuffix("\""), raw.count >= 2 {
			return .string(unescape(String(raw.dropFirst().dropLast())))
		}
		if raw.hasPrefix("["), raw.hasSuffix("]") {
			let inner = String(raw.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
			guard !inner.isEmpty else {
				return .strings([])
			}
			var values: [String] = []
			for part in splitArray(inner) {
				let trimmed = part.trimmingCharacters(in: .whitespaces)
				guard trimmed.hasPrefix("\""), trimmed.hasSuffix("\""), trimmed.count >= 2 else {
					return nil
				}
				values.append(unescape(String(trimmed.dropFirst().dropLast())))
			}
			return .strings(values)
		}
		return nil
	}

	private func splitArray(_ value: String) -> [String] {
		var parts: [String] = []
		var current = ""
		var quoted = false
		var escaped = false
		for character in value {
			if escaped {
				current.append(character)
				escaped = false
				continue
			}
			if character == "\\" {
				current.append(character)
				escaped = true
				continue
			}
			if character == "\"" {
				quoted.toggle()
				current.append(character)
				continue
			}
			if character == ",", !quoted {
				parts.append(current)
				current = ""
			} else {
				current.append(character)
			}
		}
		parts.append(current)
		return parts
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
			} else if character == "\\" {
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
