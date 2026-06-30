import Foundation

public struct LSPServerConfig: Codable, Equatable, Sendable {
	public var languageId: String
	public var command: String
	public var args: [String]
	public var rootPatterns: [String]
	public var initOptions: [String: String]
	public var settings: [String: String]

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
		self.initOptions = initOptions
		self.settings = settings
	}
}

public struct LSPServerRegistry: Equatable, Sendable {
	private var configsByLanguageID: [String: LSPServerConfig]
	private var languageIDByExtension: [String: String]

	public init(configs: [LSPServerConfig] = LSPServerRegistry.bundledDefaults) {
		configsByLanguageID = [:]
		languageIDByExtension = LSPServerRegistry.defaultExtensionMap
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

	public func languageID(forFileExtension fileExtension: String) -> String? {
		languageIDByExtension[fileExtension.lowercased()]
	}

	public func config(for url: URL) -> LSPServerConfig? {
		guard let languageID = languageID(forFileExtension: url.pathExtension) else {
			return nil
		}
		return config(forLanguageID: languageID)
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

	public mutating func registerExtensions(_ map: [String: String]) {
		for (ext, languageID) in map {
			languageIDByExtension[ext.lowercased()] = languageID
		}
	}

	public static let bundledDefaults: [LSPServerConfig] = [
		LSPServerConfig(
			languageId: "swift",
			command: "/usr/bin/xcrun",
			args: ["sourcekit-lsp"],
			rootPatterns: ["Package.swift", ".git"]
		),
		LSPServerConfig(
			languageId: "typescript",
			command: "typescript-language-server",
			args: ["--stdio"],
			rootPatterns: ["tsconfig.json", "package.json", ".git"]
		),
		LSPServerConfig(
			languageId: "javascript",
			command: "typescript-language-server",
			args: ["--stdio"],
			rootPatterns: ["package.json", "jsconfig.json", ".git"]
		),
		LSPServerConfig(
			languageId: "rust",
			command: "rust-analyzer",
			args: [],
			rootPatterns: ["Cargo.toml", ".git"]
		),
		LSPServerConfig(
			languageId: "python",
			command: "pyright-langserver",
			args: ["--stdio"],
			rootPatterns: ["pyproject.toml", "setup.py", "setup.cfg", ".git"]
		),
		LSPServerConfig(
			languageId: "go",
			command: "gopls",
			args: [],
			rootPatterns: ["go.mod", ".git"]
		),
	]

	public static let defaultExtensionMap: [String: String] = [
		"swift": "swift",
		"ts": "typescript",
		"tsx": "typescript",
		"mts": "typescript",
		"cts": "typescript",
		"js": "javascript",
		"jsx": "javascript",
		"mjs": "javascript",
		"cjs": "javascript",
		"rs": "rust",
		"py": "python",
		"pyi": "python",
		"go": "go",
	]
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
			.appendingPathComponent("lsp.json")
	}

	public static func load(from url: URL = defaultConfigURL, fileManager: FileManager = .default) throws -> LSPServerRegistry {
		guard fileManager.fileExists(atPath: url.path) else {
			throw LSPServerRegistryLoaderError.fileNotFound
		}
		let data = try Data(contentsOf: url)
		do {
			let overrides = try JSONDecoder().decode([String: LSPServerConfig].self, from: data)
			var registry = LSPServerRegistry()
			for (languageID, config) in overrides {
				var resolved = config
				resolved.languageId = languageID
				registry.register(resolved)
			}
			return registry
		} catch {
			throw LSPServerRegistryLoaderError.decodeFailed(String(describing: error))
		}
	}

	public static func loadOrBundled(from url: URL = defaultConfigURL, fileManager: FileManager = .default) -> LSPServerRegistry {
		(try? load(from: url, fileManager: fileManager)) ?? LSPServerRegistry()
	}
}
