import Darwin
import Foundation
import ItsyConfig
import ItsyLSP

public indirect enum LSPConfigurationValue: Codable, Equatable, Sendable {
	case null
	case bool(Bool)
	case int(Int)
	case double(Double)
	case string(String)
	case array([LSPConfigurationValue])
	case object([String: LSPConfigurationValue])

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if container.decodeNil() {
			self = .null
		} else if let value = try? container.decode(Bool.self) {
			self = .bool(value)
		} else if let value = try? container.decode(Int.self) {
			self = .int(value)
		} else if let value = try? container.decode(Double.self) {
			self = .double(value)
		} else if let value = try? container.decode(String.self) {
			self = .string(value)
		} else if let value = try? container.decode([LSPConfigurationValue].self) {
			self = .array(value)
		} else {
			self = .object(try container.decode([String: LSPConfigurationValue].self))
		}
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case .null:
			try container.encodeNil()
		case let .bool(value):
			try container.encode(value)
		case let .int(value):
			try container.encode(value)
		case let .double(value):
			try container.encode(value)
		case let .string(value):
			try container.encode(value)
		case let .array(value):
			try container.encode(value)
		case let .object(value):
			try container.encode(value)
		}
	}

	public var lspAny: LSPAny {
		switch self {
		case .null:
			return .null
		case let .bool(value):
			return .bool(value)
		case let .int(value):
			return .int(value)
		case let .double(value):
			return .double(value)
		case let .string(value):
			return .string(value)
		case let .array(value):
			return .array(value.map(\.lspAny))
		case let .object(value):
			return .object(value.mapValues(\.lspAny))
		}
	}
}

public enum LSPServerConfigValidationError: Error, Equatable, Sendable, CustomStringConvertible {
	case emptyLanguageID
	case emptyCommand(String)
	case emptyArgument(String, Int)
	case missingRootPatterns(String)
	case emptyRootPattern(String, Int)
	case emptyInitOptionKey(String)
	case emptySettingKey(String)

	public var description: String {
		switch self {
		case .emptyLanguageID:
			return "languageId must not be empty"
		case let .emptyCommand(languageID):
			return "\(languageID): command must not be empty"
		case let .emptyArgument(languageID, index):
			return "\(languageID): args[\(index)] must not be empty"
		case let .missingRootPatterns(languageID):
			return "\(languageID): rootPatterns must contain at least one pattern"
		case let .emptyRootPattern(languageID, index):
			return "\(languageID): rootPatterns[\(index)] must not be empty"
		case let .emptyInitOptionKey(languageID):
			return "\(languageID): initOptions keys must not be empty"
		case let .emptySettingKey(languageID):
			return "\(languageID): settings keys must not be empty"
		}
	}
}

public struct LSPServerConfig: Codable, Equatable, Sendable {
	public var languageId: String
	public var command: String
	public var args: [String]
	public var rootPatterns: [String]
	public var initOptions: [String: LSPConfigurationValue]
	public var settings: [String: LSPConfigurationValue]

	private enum CodingKeys: String, CodingKey {
		case languageId
		case command
		case args
		case rootPatterns
		case initOptions
		case settings
	}

	public init(
		languageId: String,
		command: String,
		args: [String] = [],
		rootPatterns: [String] = [".git"],
		initOptions: [String: String] = [:],
		settings: [String: String] = [:]
	) {
		self.languageId = languageId
		self.command = command
		self.args = args
		self.rootPatterns = rootPatterns
		self.initOptions = initOptions.mapValues(LSPConfigurationValue.string)
		self.settings = settings.mapValues(LSPConfigurationValue.string)
	}

	public init(
		languageId: String,
		command: String,
		args: [String] = [],
		rootPatterns: [String] = [".git"],
		typedInitOptions: [String: LSPConfigurationValue],
		typedSettings: [String: LSPConfigurationValue]
	) {
		self.languageId = languageId
		self.command = command
		self.args = args
		self.rootPatterns = rootPatterns
		initOptions = typedInitOptions
		settings = typedSettings
	}

	public var initializationOptions: LSPAny? {
		initOptions.isEmpty ? nil : .object(initOptions.mapValues(\.lspAny))
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		languageId = try container.decodeIfPresent(String.self, forKey: .languageId) ?? ""
		command = try container.decodeIfPresent(String.self, forKey: .command) ?? ""
		args = try container.decodeIfPresent([String].self, forKey: .args) ?? []
		rootPatterns = try container.decodeIfPresent([String].self, forKey: .rootPatterns) ?? [".git"]
		initOptions = try container.decodeIfPresent([String: LSPConfigurationValue].self, forKey: .initOptions) ?? [:]
		settings = try container.decodeIfPresent([String: LSPConfigurationValue].self, forKey: .settings) ?? [:]
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.container(keyedBy: CodingKeys.self)
		try container.encode(languageId, forKey: .languageId)
		try container.encode(command, forKey: .command)
		try container.encode(args, forKey: .args)
		try container.encode(rootPatterns, forKey: .rootPatterns)
		try container.encode(initOptions, forKey: .initOptions)
		try container.encode(settings, forKey: .settings)
	}

	public func validate() throws {
		guard !languageId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			throw LSPServerConfigValidationError.emptyLanguageID
		}
		guard !command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			throw LSPServerConfigValidationError.emptyCommand(languageId)
		}
		for (index, arg) in args.enumerated() where arg.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			throw LSPServerConfigValidationError.emptyArgument(languageId, index)
		}
		guard !rootPatterns.isEmpty else {
			throw LSPServerConfigValidationError.missingRootPatterns(languageId)
		}
		for (index, pattern) in rootPatterns.enumerated() where pattern.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			throw LSPServerConfigValidationError.emptyRootPattern(languageId, index)
		}
		guard initOptions.keys.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
			throw LSPServerConfigValidationError.emptyInitOptionKey(languageId)
		}
		guard settings.keys.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
			throw LSPServerConfigValidationError.emptySettingKey(languageId)
		}
	}
}

public struct LSPServerRegistry: Equatable, Sendable {
	public enum ProvisioningStatus: Equatable, Sendable {
		case disabled
		case system(LSPServerConfig)
		case managed(LSPServerConfig, ManagedSupportComponent)
		case availableToInstall(ManagedSupportComponent)
		case systemRequired(ManagedSupportComponent)
		case unavailable
	}
	public struct UnsupportedLanguage: Error, Equatable, Sendable {
		public var languageID: String
		public var reason: BundledLanguageUnsupportedReason

		public init(languageID: String, reason: BundledLanguageUnsupportedReason) {
			self.languageID = languageID
			self.reason = reason
		}

		public var message: String {
			switch reason {
			case .noBundledServer:
				"No bundled language server is declared for \(languageID)."
			}
		}
	}

	public struct MissingBinary: Error, Equatable, Sendable {
		public var languageID: String
		public var command: String
		public var hint: String

		public init(languageID: String, command: String, hint: String) {
			self.languageID = languageID
			self.command = command
			self.hint = hint
		}
	}

	private var configsByLanguageID: [String: LSPServerConfig]
	private var languageIDByExtension: [String: String]
	private var languageIDByFileName: [String: String]

	public init(configs: [LSPServerConfig] = LSPServerRegistry.bundledDefaults) {
		configsByLanguageID = [:]
		languageIDByExtension = LSPServerRegistry.defaultExtensionMap
		languageIDByFileName = LSPServerRegistry.defaultFileNameMap
		for config in configs {
			configsByLanguageID[config.languageId] = config
		}
	}

	public var configs: [LSPServerConfig] {
		Array(configsByLanguageID.values).sorted { $0.languageId < $1.languageId }
	}

	public func config(forLanguageID languageID: String) -> LSPServerConfig? {
		configsByLanguageID[languageID]
	}

	public func supportComponent(forLanguageID languageID: String) -> ManagedSupportComponent? {
		Self.supportCatalog.component(languageID: languageID, kind: .languageServer)
	}

	public func resolvedConfig(
		forLanguageID languageID: String,
		mode: ItsySettings.LSPMode = .automatic,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> LSPServerConfig? {
		switch provisioningStatus(forLanguageID: languageID, mode: mode, environment: environment) {
		case let .system(config), let .managed(config, _):
			return config
		case .disabled, .availableToInstall, .systemRequired, .unavailable:
			return nil
		}
	}

	public func provisioningStatus(
		forLanguageID languageID: String,
		mode: ItsySettings.LSPMode = .automatic,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> ProvisioningStatus {
		guard mode != .disabled else { return .disabled }
		guard var config = config(forLanguageID: languageID) else { return .unavailable }
		if mode != .managed, let resolvedCommand = Self.resolvedCommandPath(for: config, environment: environment) {
			if !Self.isXcrunCommand(config.command) {
				config.command = resolvedCommand
			}
			return .system(config)
		}
		guard mode != .system, isBundledManagedConfig(config, languageID: languageID),
		      let component = supportComponent(forLanguageID: languageID)
		else {
			return .unavailable
		}
		if let managed = ManagedSupportResolver.executableURL(for: component),
		   mode == .managed || ManagedSupportEnablement.isEnabled(component)
		{
			config.command = managed.path
			return .managed(config, component)
		}
		return component.hasVerifiedManagedInstall ? .availableToInstall(component) : .systemRequired(component)
	}

	public func executableResolution(
		forLanguageID languageID: String,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) throws -> LSPExecutableResolution {
		guard let config = config(forLanguageID: languageID) else {
			throw LSPExecutableDetectionError.missingExecutable(languageID)
		}
		return try Self.executableResolution(for: config, environment: environment)
	}

	public func missingBinary(
		forLanguageID languageID: String,
		mode: ItsySettings.LSPMode = .automatic,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> MissingBinary? {
		guard let config = config(forLanguageID: languageID) else { return nil }
		switch provisioningStatus(forLanguageID: languageID, mode: mode, environment: environment) {
		case .disabled, .system, .managed:
			return nil
		case let .availableToInstall(component):
			return MissingBinary(
				languageID: languageID,
				command: component.command,
				hint: "Open Language & Debugger Support in Itsy to install (component.displayName)."
			)
		case let .systemRequired(component) where mode == .managed:
			return MissingBinary(
				languageID: languageID,
				command: config.command,
				hint: "No verified managed download is bundled for \(component.displayName); choose System or configure lsp.toml."
			)
		case .systemRequired, .unavailable:
			break
		}
		let command = Self.resolutionCommandName(for: config)
		return MissingBinary(languageID: languageID, command: command, hint: Self.installHint(for: command))
	}

	public func languageID(forFileExtension fileExtension: String) -> String? {
		languageIDByExtension[fileExtension.lowercased()]
	}

	public func languageID(forFileName fileName: String) -> String? {
		languageIDByFileName[fileName.lowercased()]
	}

	public func languageID(for url: URL) -> String? {
		let fileName = url.lastPathComponent.lowercased()
		if let languageID = languageID(forFileName: fileName) {
			return languageID
		}
		if fileName.hasPrefix("dockerfile.") || fileName.hasPrefix("containerfile.") || fileName.hasSuffix(".dockerfile") {
			return "dockerfile"
		}
		return languageID(forFileExtension: url.pathExtension)
	}

	public func config(for url: URL) -> LSPServerConfig? {
		guard let languageID = languageID(for: url) else {
			return nil
		}
		return config(forLanguageID: languageID)
	}

	public func resolvedConfig(
		for url: URL,
		mode: ItsySettings.LSPMode = .automatic,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> LSPServerConfig? {
		guard let languageID = languageID(for: url) else {
			return nil
		}
		return resolvedConfig(forLanguageID: languageID, mode: mode, environment: environment)
	}

	public func missingBinary(
		for url: URL,
		mode: ItsySettings.LSPMode = .automatic,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> MissingBinary? {
		guard let languageID = languageID(for: url) else {
			return nil
		}
		return missingBinary(forLanguageID: languageID, mode: mode, environment: environment)
	}

	public func unsupportedLanguage(for url: URL) -> UnsupportedLanguage? {
		guard
			let languageID = languageID(for: url),
			let language = BundledLanguageInventory.languages.first(where: { $0.languageID == languageID }),
			case let .unsupported(reason) = language.support
		else {
			return nil
		}
		return UnsupportedLanguage(languageID: languageID, reason: reason)
	}

	public func discoverWorkspaceRoot(for fileURL: URL, fileManager: FileManager = .default) -> URL? {
		guard let config = config(for: fileURL) else {
			return nil
		}
		return Self.walkRootPatterns(from: fileURL, patterns: config.rootPatterns, fileManager: fileManager)
	}

	public static func walkRootPatterns(from fileURL: URL, patterns: [String], fileManager: FileManager = .default) -> URL? {
		var directory = fileURL.deletingLastPathComponent().standardizedFileURL
		while true {
			for pattern in patterns {
				let candidate = directory.appendingPathComponent(pattern)
				if fileManager.fileExists(atPath: candidate.path) {
					return directory
				}
			}
			let parent = directory.deletingLastPathComponent().standardizedFileURL
			if parent.path == directory.path {
				return nil
			}
			directory = parent
		}
	}

	public mutating func register(_ config: LSPServerConfig) {
		configsByLanguageID[config.languageId] = config
	}

	public func merging(_ override: LSPServerRegistry) -> LSPServerRegistry {
		var copy = self
		for config in override.configs {
			copy.register(config)
		}
		return copy
	}

	public mutating func registerExtensions(_ map: [String: String]) {
		for (ext, languageID) in map {
			languageIDByExtension[ext.lowercased()] = languageID
		}
	}

	public mutating func registerFileNames(_ map: [String: String]) {
		for (fileName, languageID) in map {
			languageIDByFileName[fileName.lowercased()] = languageID
		}
	}

	public static let bundledDefaults: [LSPServerConfig] = BundledLanguageInventory.lspConfigs

	public static var supportCatalog: ManagedSupportCatalog { ManagedSupportCatalogStore.current() }

	public static let defaultExtensionMap: [String: String] = BundledLanguageInventory.fileExtensionMap

	public static let defaultFileNameMap: [String: String] = BundledLanguageInventory.fileNameMap

	private static func resolvedCommandPath(for config: LSPServerConfig, environment: [String: String]) -> String? {
		try? executableResolution(for: config, environment: environment).executableURL.path
	}

	private func isBundledManagedConfig(_ config: LSPServerConfig, languageID: String) -> Bool {
		guard let bundled = BundledLanguageInventory.server(forLanguageID: languageID) else { return false }
		return bundled.command == config.command && bundled.args == config.args && bundled.rootPatterns == config.rootPatterns
	}

	private static func executableResolution(for config: LSPServerConfig, environment: [String: String]) throws -> LSPExecutableResolution {
		if isXcrunCommand(config.command), let tool = xcrunToolName(in: config.args), let xcrunPath = resolveExecutable(config.command, environment: environment) {
			guard let toolPath = resolveWithXcrun(tool: tool, xcrunPath: xcrunPath, environment: environment) else {
				throw LSPExecutableDetectionError.missingExecutable(tool)
			}
			return LSPExecutableResolution(executableURL: URL(fileURLWithPath: toolPath), source: .explicitCommand, version: nil)
		}
		let server = BundledLanguageInventory.server(forLanguageID: config.languageId)
		let probe = server.flatMap { server in
			server.command == config.command && server.args == config.args && server.rootPatterns == config.rootPatterns
				? server.detectionProbe
				: nil
		}
		return try LSPExecutableDetector.detect(command: config.command, probe: probe, environment: environment)
	}

	private static func resolveExecutable(_ command: String, environment: [String: String]) -> String? {
		if command.hasPrefix("/") {
			return access(command, X_OK) == 0 ? command : nil
		}
		let searchPath = environment["PATH"] ?? "/usr/bin:/bin:/usr/sbin:/sbin"
		for directory in searchPath.split(separator: ":") where !directory.isEmpty {
			let candidate = URL(fileURLWithPath: String(directory)).appendingPathComponent(command).path
			if access(candidate, X_OK) == 0 {
				return candidate
			}
		}
		return nil
	}

	private static func resolveWithXcrun(tool: String, xcrunPath: String, environment: [String: String]) -> String? {
		let process = Process()
		process.executableURL = URL(fileURLWithPath: xcrunPath)
		process.arguments = ["-f", tool]
		process.environment = environment
		let output = Pipe()
		process.standardOutput = output
		process.standardError = Pipe()
		do {
			try process.run()
			process.waitUntilExit()
		} catch {
			return nil
		}
		guard process.terminationStatus == 0 else {
			return nil
		}
		let data = output.fileHandleForReading.readDataToEndOfFile()
		let path = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
		guard !path.isEmpty, access(path, X_OK) == 0 else {
			return nil
		}
		return path
	}

	private static func isXcrunCommand(_ command: String) -> Bool {
		URL(fileURLWithPath: command).lastPathComponent == "xcrun"
	}

	private static func xcrunToolName(in args: [String]) -> String? {
		var skipsNext = false
		for arg in args {
			if skipsNext {
				skipsNext = false
				continue
			}
			if arg == "--sdk" || arg == "-sdk" || arg == "--toolchain" || arg == "-toolchain" {
				skipsNext = true
				continue
			}
			if !arg.hasPrefix("-") {
				return arg
			}
		}
		return nil
	}

	private static func resolutionCommandName(for config: LSPServerConfig) -> String {
		if isXcrunCommand(config.command), let tool = xcrunToolName(in: config.args) {
			return tool
		}
		return config.command
	}

	private static func installHint(for command: String) -> String {
		BundledLanguageInventory.server(probing: command)?.installHint
			?? "install `\(command)` and ensure it is executable"
	}
}

public enum LSPServerRegistryLoaderError: Error, Equatable {
	case fileNotFound
	case decodeFailed(String)
}

public enum LSPServerRegistryLoader {
	public static var defaultConfigURL: URL {
		FileManager.default
			.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("lsp.toml")
	}

	public static var legacyJSONConfigURL: URL {
		FileManager.default
			.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("lsp.json")
	}

	public static func load(from url: URL = defaultConfigURL, fileManager: FileManager = .default) throws -> LSPServerRegistry {
		guard fileManager.fileExists(atPath: url.path) else {
			throw LSPServerRegistryLoaderError.fileNotFound
		}
		if url.pathExtension.lowercased() == "toml" {
			return try loadTOML(from: url, fileManager: fileManager)
		}
		let data = try Data(contentsOf: url)
		do {
			let overrides = try JSONDecoder().decode([String: LSPServerConfig].self, from: data)
			var registry = LSPServerRegistry()
			for (languageID, config) in overrides {
				var resolved = config
				resolved.languageId = languageID
				try resolved.validate()
				registry.register(resolved)
			}
			return registry
		} catch {
			throw LSPServerRegistryLoaderError.decodeFailed(String(describing: error))
		}
	}

	public static func loadTOML(from url: URL, fileManager: FileManager = .default) throws -> LSPServerRegistry {
		guard fileManager.fileExists(atPath: url.path) else {
			throw LSPServerRegistryLoaderError.fileNotFound
		}
		do {
			let text = try String(contentsOf: url, encoding: .utf8)
			return try LSPRegistryTOMLParser().parse(text)
		} catch let error as LSPServerRegistryLoaderError {
			throw error
		} catch {
			throw LSPServerRegistryLoaderError.decodeFailed(String(describing: error))
		}
	}

	public static func loadOrBundled(from url: URL = defaultConfigURL, fileManager: FileManager = .default) -> LSPServerRegistry {
		if let registry = try? load(from: url, fileManager: fileManager) {
			return registry
		}
		if url == defaultConfigURL, let registry = try? load(from: legacyJSONConfigURL, fileManager: fileManager) {
			return registry
		}
		return LSPServerRegistry()
	}
}

private struct LSPRegistryTOMLParser {
	private enum Value: Equatable {
		case configuration(LSPConfigurationValue)
	}

	func parse(_ text: String) throws -> LSPServerRegistry {
		var configs: [String: LSPServerConfig] = [:]
		var section: [String] = []
		for (offset, rawLine) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			let line = stripComment(String(rawLine)).trimmingCharacters(in: .whitespaces)
			guard !line.isEmpty else {
				continue
			}
			if line.hasPrefix("["), line.hasSuffix("]") {
				section = line.dropFirst().dropLast().split(separator: ".").map { String($0).trimmingCharacters(in: .whitespaces) }
				continue
			}
			guard !section.isEmpty, let equals = line.firstIndex(of: "=") else {
				throw LSPServerRegistryLoaderError.decodeFailed("line \(offset + 1): expected [language] and key = value")
			}
			let key = String(line[..<equals]).trimmingCharacters(in: .whitespaces)
			let rawValue = String(line[line.index(after: equals)...]).trimmingCharacters(in: .whitespaces)
			guard let value = parseValue(rawValue) else {
				throw LSPServerRegistryLoaderError.decodeFailed("line \(offset + 1): invalid value")
			}
			let languageID = section[0]
			var config = configs[languageID] ?? LSPServerConfig(languageId: languageID, command: "")
			switch (Array(section.dropFirst()), key, value) {
			case ([], "command", let .configuration(.string(command))):
				config.command = command
			case ([], "args", let .configuration(.array(values))):
				guard let args = stringArray(values) else {
					throw LSPServerRegistryLoaderError.decodeFailed("line \(offset + 1): args must be an array of strings")
				}
				config.args = args
			case ([], "root_patterns", let .configuration(.array(values))), ([], "rootPatterns", let .configuration(.array(values))):
				guard let patterns = stringArray(values) else {
					throw LSPServerRegistryLoaderError.decodeFailed("line \(offset + 1): rootPatterns must be an array of strings")
				}
				config.rootPatterns = patterns
			case (["initOptions"], let optionKey, let .configuration(optionValue)):
				config.initOptions[optionKey] = optionValue
			case (["settings"], let settingKey, let .configuration(settingValue)):
				config.settings[settingKey] = settingValue
			default:
				throw LSPServerRegistryLoaderError.decodeFailed("line \(offset + 1): unsupported key \(key)")
			}
			configs[languageID] = config
		}
		var registry = LSPServerRegistry()
		for (_, config) in configs {
			do {
				try config.validate()
			} catch let error as LSPServerConfigValidationError {
				throw LSPServerRegistryLoaderError.decodeFailed(error.description)
			}
			registry.register(config)
		}
		return registry
	}

	private func parseValue(_ raw: String) -> Value? {
		if raw.hasPrefix("\""), raw.hasSuffix("\""), raw.count >= 2 {
			return .configuration(.string(unescape(String(raw.dropFirst().dropLast()))))
		}
		if raw == "true" {
			return .configuration(.bool(true))
		}
		if raw == "false" {
			return .configuration(.bool(false))
		}
		if let value = Int(raw) {
			return .configuration(.int(value))
		}
		if let value = Double(raw) {
			return .configuration(.double(value))
		}
		if raw.hasPrefix("["), raw.hasSuffix("]") {
			let inner = String(raw.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
			guard !inner.isEmpty else {
				return .configuration(.array([]))
			}
			var values: [LSPConfigurationValue] = []
			for part in splitArray(inner) {
				guard case let .configuration(value)? = parseValue(part.trimmingCharacters(in: .whitespaces)) else {
					return nil
				}
				values.append(value)
			}
			return .configuration(.array(values))
		}
		return nil
	}

	private func stringArray(_ values: [LSPConfigurationValue]) -> [String]? {
		var strings: [String] = []
		for value in values {
			guard case let .string(string) = value else {
				return nil
			}
			strings.append(string)
		}
		return strings
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
