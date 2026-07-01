import Foundation

public enum DebugAdapterType {
	public static let executable = "executable"
}

public enum DebugLaunchRequest {
	public static let launch = "launch"
	public static let attach = "attach"
}

public struct DebugAdapterConfig: Codable, Equatable, Sendable {
	public var id: String
	public var command: String
	public var type: String
	public var args: [String]

	public init(id: String, command: String, type: String = DebugAdapterType.executable, args: [String] = []) {
		self.id = id
		self.command = command
		self.type = type
		self.args = args
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		id = try container.decode(String.self, forKey: .id)
		command = try container.decode(String.self, forKey: .command)
		type = try container.decodeIfPresent(String.self, forKey: .type) ?? DebugAdapterType.executable
		args = try container.decodeIfPresent([String].self, forKey: .args) ?? []
	}
}

public struct DebugLaunchConfiguration: Codable, Equatable, Sendable {
	public var name: String
	public var type: String
	public var request: String
	public var program: String?
	public var args: [String]
	public var cwd: String?
	public var env: [String: String]
	public var stopOnEntry: Bool?
	public var noDebug: Bool?

	public init(name: String, type: String, request: String, program: String? = nil, args: [String] = [], cwd: String? = nil, env: [String: String] = [:], stopOnEntry: Bool? = nil, noDebug: Bool? = nil) {
		self.name = name
		self.type = type
		self.request = request
		self.program = program
		self.args = args
		self.cwd = cwd
		self.env = env
		self.stopOnEntry = stopOnEntry
		self.noDebug = noDebug
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		name = try container.decode(String.self, forKey: .name)
		type = try container.decode(String.self, forKey: .type)
		request = try container.decode(String.self, forKey: .request)
		program = try container.decodeIfPresent(String.self, forKey: .program)
		args = try container.decodeIfPresent([String].self, forKey: .args) ?? []
		cwd = try container.decodeIfPresent(String.self, forKey: .cwd)
		env = try container.decodeIfPresent([String: String].self, forKey: .env) ?? [:]
		stopOnEntry = try container.decodeIfPresent(Bool.self, forKey: .stopOnEntry)
		noDebug = try container.decodeIfPresent(Bool.self, forKey: .noDebug)
	}
}

public struct DebugLaunchConfig: Codable, Equatable, Sendable {
	public var adapters: [DebugAdapterConfig]
	public var configurations: [DebugLaunchConfiguration]

	public init(adapters: [DebugAdapterConfig] = [], configurations: [DebugLaunchConfiguration] = []) {
		self.adapters = adapters
		self.configurations = configurations
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		adapters = try container.decodeIfPresent([DebugAdapterConfig].self, forKey: .adapters) ?? []
		configurations = try container.decodeIfPresent([DebugLaunchConfiguration].self, forKey: .configurations) ?? []
	}

	public func adapter(id: String) -> DebugAdapterConfig? {
		adapters.first { $0.id == id }
	}

	public func configuration(named name: String) -> DebugLaunchConfiguration? {
		configurations.first { $0.name == name }
	}

	public func merging(_ override: DebugLaunchConfig) -> DebugLaunchConfig {
		DebugLaunchConfig(
			adapters: merge(base: adapters, override: override.adapters, key: \.id),
			configurations: merge(base: configurations, override: override.configurations, key: \.name)
		)
	}

	private func merge<Value>(base: [Value], override: [Value], key: (Value) -> String) -> [Value] {
		var orderedKeys: [String] = []
		var valuesByKey: [String: Value] = [:]
		for value in base {
			let id = key(value)
			if !orderedKeys.contains(id) {
				orderedKeys.append(id)
			}
			valuesByKey[id] = value
		}
		for value in override {
			let id = key(value)
			if !orderedKeys.contains(id) {
				orderedKeys.append(id)
			}
			valuesByKey[id] = value
		}
		return orderedKeys.compactMap { valuesByKey[$0] }
	}
}

public struct DebugLaunchConfigLoader {
	public var userConfigURL: URL
	public var fileManager: FileManager

	public init(userConfigURL: URL = DebugLaunchConfigLoader.defaultUserConfigURL, fileManager: FileManager = .default) {
		self.userConfigURL = userConfigURL
		self.fileManager = fileManager
	}

	public static var defaultUserConfigURL: URL {
		FileManager.default.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("debug.json")
	}

	public func workspaceConfigURL(for workspaceRoot: URL) -> URL {
		workspaceRoot
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("debug.json")
	}

	public func load(workspaceRoot: URL) throws -> DebugLaunchConfig {
		let user = try loadIfPresent(userConfigURL)
		let workspace = try loadIfPresent(workspaceConfigURL(for: workspaceRoot))
		return user.merging(workspace)
	}

	private func loadIfPresent(_ url: URL) throws -> DebugLaunchConfig {
		guard fileManager.fileExists(atPath: url.path) else {
			return DebugLaunchConfig()
		}
		return try JSONDecoder().decode(DebugLaunchConfig.self, from: Data(contentsOf: url))
	}
}
