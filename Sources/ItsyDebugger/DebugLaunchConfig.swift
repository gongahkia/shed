import Foundation
import ItsyDAP

public enum DebugAdapterType {
	public static let executable = "executable"
}

public enum DebugAdapterKind: String, Codable, Equatable, Sendable {
	case lldb
	case debugpy
	case jsDebug = "js-debug"
	case delve
	case codeLLDB = "codelldb"
	case custom

	public static func inferred(from identifier: String) -> DebugAdapterKind {
		switch identifier {
		case "lldb", "lldb-dap":
			return .lldb
		case "debugpy":
			return .debugpy
		case "vscode-js-debug", "js-debug":
			return .jsDebug
		case "delve", "dlv":
			return .delve
		case "codelldb", "code-lldb":
			return .codeLLDB
		default:
			return .custom
		}
	}
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
	public var kind: DebugAdapterKind
	public var remediation: String?

	public init(
		id: String,
		command: String,
		type: String = DebugAdapterType.executable,
		args: [String] = [],
		kind: DebugAdapterKind? = nil,
		remediation: String? = nil
	) {
		self.id = id
		self.command = command
		self.type = type
		self.args = args
		self.kind = kind ?? DebugAdapterKind.inferred(from: id)
		self.remediation = remediation
	}

	private enum CodingKeys: String, CodingKey {
		case id
		case command
		case type
		case args
		case kind
		case remediation
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		id = try container.decode(String.self, forKey: .id)
		command = try container.decode(String.self, forKey: .command)
		type = try container.decodeIfPresent(String.self, forKey: .type) ?? DebugAdapterType.executable
		args = try container.decodeIfPresent([String].self, forKey: .args) ?? []
		kind = try container.decodeIfPresent(DebugAdapterKind.self, forKey: .kind) ?? DebugAdapterKind.inferred(from: id)
		remediation = try container.decodeIfPresent(String.self, forKey: .remediation)
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
	public var exceptionFilters: [String]
	public var sourceMap: [String: String]
	public var adapterOptions: [String: DAPAny]

	public init(name: String, type: String, request: String, program: String? = nil, args: [String] = [], cwd: String? = nil, env: [String: String] = [:], stopOnEntry: Bool? = nil, noDebug: Bool? = nil, exceptionFilters: [String] = [], sourceMap: [String: String] = [:], adapterOptions: [String: DAPAny] = [:]) {
		self.name = name
		self.type = type
		self.request = request
		self.program = program
		self.args = args
		self.cwd = cwd
		self.env = env
		self.stopOnEntry = stopOnEntry
		self.noDebug = noDebug
		self.exceptionFilters = exceptionFilters
		self.sourceMap = sourceMap
		self.adapterOptions = adapterOptions
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
		exceptionFilters = try container.decodeIfPresent([String].self, forKey: .exceptionFilters) ?? []
		sourceMap = try container.decodeIfPresent([String: String].self, forKey: .sourceMap) ?? [:]
		adapterOptions = try container.decodeIfPresent([String: DAPAny].self, forKey: .adapterOptions) ?? [:]
	}

	private enum CodingKeys: String, CodingKey {
		case name
		case type
		case request
		case program
		case args
		case cwd
		case env
		case stopOnEntry
		case noDebug
		case exceptionFilters
		case sourceMap
		case adapterOptions
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
		return try DebugLaunchConfigParser.parse(data: Data(contentsOf: url))
	}
}

public enum DebugLaunchConfigError: Error, Equatable, Sendable {
	case invalidJSON
	case duplicateAdapterID(String)
	case duplicateConfigurationName(String)
	case invalidAdapter(id: String, field: String, reason: String)
	case invalidConfiguration(name: String, field: String, reason: String)
}

public enum DebugLaunchConfigParser {
	public static func parse(data: Data) throws -> DebugLaunchConfig {
		let config: DebugLaunchConfig
		do {
			config = try JSONDecoder().decode(DebugLaunchConfig.self, from: data)
		} catch {
			throw DebugLaunchConfigError.invalidJSON
		}
		var adapterIDs = Set<String>()
		for adapter in config.adapters {
			guard adapterIDs.insert(adapter.id).inserted else {
				throw DebugLaunchConfigError.duplicateAdapterID(adapter.id)
			}
			try validate(adapter)
		}
		var configurationNames = Set<String>()
		for configuration in config.configurations {
			guard configurationNames.insert(configuration.name).inserted else {
				throw DebugLaunchConfigError.duplicateConfigurationName(configuration.name)
			}
			try validate(configuration)
		}
		return config
	}

	private static func validate(_ adapter: DebugAdapterConfig) throws {
		guard isIdentifier(adapter.id) else {
			throw DebugLaunchConfigError.invalidAdapter(id: adapter.id, field: "id", reason: "must use [A-Za-z0-9._:-]")
		}
		guard adapter.type == DebugAdapterType.executable else {
			throw DebugLaunchConfigError.invalidAdapter(id: adapter.id, field: "type", reason: "must be executable")
		}
		guard isPlain(adapter.command) else {
			throw DebugLaunchConfigError.invalidAdapter(id: adapter.id, field: "command", reason: "must be non-empty without control characters")
		}
		guard adapter.args.allSatisfy(isPlain) else {
			throw DebugLaunchConfigError.invalidAdapter(id: adapter.id, field: "args", reason: "must not contain control characters")
		}
		if let remediation = adapter.remediation, !isPlain(remediation) {
			throw DebugLaunchConfigError.invalidAdapter(id: adapter.id, field: "remediation", reason: "must be non-empty without control characters")
		}
	}

	private static func validate(_ configuration: DebugLaunchConfiguration) throws {
		guard isPlain(configuration.name) else {
			throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "name", reason: "must be non-empty without control characters")
		}
		guard isIdentifier(configuration.type) else {
			throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "type", reason: "must use [A-Za-z0-9._:-]")
		}
		guard configuration.request == DebugLaunchRequest.launch || configuration.request == DebugLaunchRequest.attach else {
			throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "request", reason: "must be launch or attach")
		}
		if configuration.request == DebugLaunchRequest.launch, !(configuration.program.map(isPlain) ?? false) {
			throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "program", reason: "is required for launch and must not contain control characters")
		}
		if let program = configuration.program, !isPlain(program) {
			throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "program", reason: "must be non-empty without control characters")
		}
		if let cwd = configuration.cwd, !isPlain(cwd) {
			throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "cwd", reason: "must be non-empty without control characters")
		}
		guard configuration.args.allSatisfy(isPlain) else {
			throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "args", reason: "must not contain control characters")
		}
		for (key, value) in configuration.env {
			guard isEnvironmentKey(key) else {
				throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "env.\(key)", reason: "must be a POSIX environment key")
			}
			guard !containsControl(value) else {
				throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "env.\(key)", reason: "must not contain control characters")
			}
		}
		for (from, to) in configuration.sourceMap {
			guard isPlain(from), isPlain(to) else {
				throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "sourceMap", reason: "paths must be non-empty without control characters")
			}
		}
		for (key, value) in configuration.adapterOptions {
			guard isIdentifier(key), !reservedAdapterOptionKeys.contains(key) else {
				throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "adapterOptions.\(key)", reason: "must use a non-reserved adapter option key")
			}
			guard isSafeAdapterOption(value) else {
				throw DebugLaunchConfigError.invalidConfiguration(name: configuration.name, field: "adapterOptions.\(key)", reason: "must not contain control characters")
			}
		}
	}

	private static let reservedAdapterOptionKeys: Set<String> = ["program", "args", "cwd", "env", "noDebug", "stopOnEntry", "request"]

	private static func isSafeAdapterOption(_ value: DAPAny) -> Bool {
		switch value {
		case .null, .bool, .int, .double:
			return true
		case let .string(value):
			return !containsControl(value)
		case let .array(values):
			return values.allSatisfy(isSafeAdapterOption)
		case let .object(values):
			return values.allSatisfy { isIdentifier($0.key) && isSafeAdapterOption($0.value) }
		}
	}

	private static func isIdentifier(_ value: String) -> Bool {
		let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._:-"))
		return !value.isEmpty && value.unicodeScalars.allSatisfy(allowed.contains)
	}

	private static func isEnvironmentKey(_ value: String) -> Bool {
		guard let first = value.unicodeScalars.first,
		      CharacterSet.letters.union(CharacterSet(charactersIn: "_")).contains(first)
		else {
			return false
		}
		let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "_"))
		return value.unicodeScalars.allSatisfy(allowed.contains)
	}

	private static func isPlain(_ value: String) -> Bool {
		!value.isEmpty && !containsControl(value)
	}

	private static func containsControl(_ value: String) -> Bool {
		value.unicodeScalars.contains { $0.value == 0 || $0.value < 0x20 || $0.value == 0x7F }
	}
}
