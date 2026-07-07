import Darwin
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

	public func resolvedConfig(
		forLanguageID languageID: String,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> LSPServerConfig? {
		guard var config = config(forLanguageID: languageID), let resolvedCommand = Self.resolvedCommandPath(for: config, environment: environment) else {
			return nil
		}
		if !Self.isXcrunCommand(config.command) {
			config.command = resolvedCommand
		}
		return config
	}

	public func missingBinary(
		forLanguageID languageID: String,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> MissingBinary? {
		guard let config = config(forLanguageID: languageID), Self.resolvedCommandPath(for: config, environment: environment) == nil else {
			return nil
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
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> LSPServerConfig? {
		guard let languageID = languageID(for: url) else {
			return nil
		}
		return resolvedConfig(forLanguageID: languageID, environment: environment)
	}

	public func missingBinary(
		for url: URL,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> MissingBinary? {
		guard let languageID = languageID(for: url) else {
			return nil
		}
		return missingBinary(forLanguageID: languageID, environment: environment)
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

	public mutating func registerFileNames(_ map: [String: String]) {
		for (fileName, languageID) in map {
			languageIDByFileName[fileName.lowercased()] = languageID
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
		LSPServerConfig(
			languageId: "c",
			command: "clangd",
			args: [],
			rootPatterns: ["compile_commands.json", "compile_flags.txt", ".clangd", ".git"]
		),
		LSPServerConfig(
			languageId: "cpp",
			command: "clangd",
			args: [],
			rootPatterns: ["compile_commands.json", "compile_flags.txt", ".clangd", ".git"]
		),
		LSPServerConfig(
			languageId: "zig",
			command: "zls",
			args: [],
			rootPatterns: ["build.zig", ".git"]
		),
		LSPServerConfig(
			languageId: "elixir",
			command: "elixir-ls",
			args: [],
			rootPatterns: ["mix.exs", ".git"]
		),
		LSPServerConfig(
			languageId: "kotlin",
			command: "kotlin-language-server",
			args: [],
			rootPatterns: ["settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", ".git"]
		),
		LSPServerConfig(
			languageId: "csharp",
			command: "omnisharp",
			args: ["--languageserver"],
			rootPatterns: ["omnisharp.json", "global.json", "Directory.Build.props", ".git"]
		),
		LSPServerConfig(
			languageId: "bash",
			command: "bash-language-server",
			args: ["start"],
			rootPatterns: [".git"]
		),
		LSPServerConfig(
			languageId: "dockerfile",
			command: "docker-langserver",
			args: ["--stdio"],
			rootPatterns: [".git"]
		),
		LSPServerConfig(
			languageId: "sql",
			command: "sqls",
			args: [],
			rootPatterns: [".git"]
		),
		LSPServerConfig(
			languageId: "dart",
			command: "dart",
			args: ["language-server", "--protocol=lsp"],
			rootPatterns: ["pubspec.yaml", ".git"]
		),
		LSPServerConfig(
			languageId: "haskell",
			command: "haskell-language-server-wrapper",
			args: ["--lsp"],
			rootPatterns: ["hie.yaml", "stack.yaml", "cabal.project", "package.yaml", ".git"]
		),
		LSPServerConfig(
			languageId: "lua",
			command: "lua-language-server",
			args: [],
			rootPatterns: [".luarc.json", ".luarc.jsonc", ".git"]
		),
		LSPServerConfig(
			languageId: "ruby",
			command: "ruby-lsp",
			args: [],
			rootPatterns: ["Gemfile", ".git"]
		),
		LSPServerConfig(
			languageId: "terraform",
			command: "terraform-ls",
			args: ["serve"],
			rootPatterns: [".terraform", ".git"]
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
		"c": "c",
		"h": "c",
		"cc": "cpp",
		"cpp": "cpp",
		"cxx": "cpp",
		"hh": "cpp",
		"hpp": "cpp",
		"hxx": "cpp",
		"java": "java",
		"jl": "julia",
		"zig": "zig",
		"zon": "zig",
		"ex": "elixir",
		"exs": "elixir",
		"kt": "kotlin",
		"kts": "kotlin",
		"cs": "csharp",
		"csx": "csharp",
		"bash": "bash",
		"sh": "bash",
		"zsh": "bash",
		"graphql": "graphql",
		"gql": "graphql",
		"dockerfile": "dockerfile",
		"sql": "sql",
		"dart": "dart",
		"hs": "haskell",
		"lhs": "haskell",
		"tex": "latex",
		"sty": "latex",
		"cls": "latex",
		"lua": "lua",
		"nix": "nix",
		"ml": "ocaml",
		"mli": "ocaml",
		"php": "php",
		"proto": "proto",
		"r": "r",
		"rb": "ruby",
		"rake": "ruby",
		"scss": "scss",
		"svelte": "svelte",
		"tf": "terraform",
		"tfvars": "terraform",
		"hcl": "terraform",
		"vue": "vue",
	]

	public static let defaultFileNameMap: [String: String] = [
		"dockerfile": "dockerfile",
		"containerfile": "dockerfile",
		"gemfile": "ruby",
		"rakefile": "ruby",
	]

	private static func resolvedCommandPath(for config: LSPServerConfig, environment: [String: String]) -> String? {
		if isXcrunCommand(config.command), let tool = xcrunToolName(in: config.args), let xcrunPath = resolveExecutable(config.command, environment: environment) {
			return resolveWithXcrun(tool: tool, xcrunPath: xcrunPath, environment: environment)
		}
		return resolveExecutable(config.command, environment: environment)
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
		switch command {
		case "sourcekit-lsp":
			return "part of Xcode, install via `xcode-select --install`"
		case "typescript-language-server":
			return "`npm i -g typescript typescript-language-server`"
		case "rust-analyzer":
			return "`rustup component add rust-analyzer`"
		case "pyright-langserver":
			return "`npm i -g pyright`"
		case "gopls":
			return "`go install golang.org/x/tools/gopls@latest`"
		case "clangd":
			return "`brew install llvm` and add LLVM's bin directory to PATH"
		case "zls":
			return "`brew install zls`"
		case "elixir-ls":
			return "`brew install elixir-ls`"
		case "kotlin-language-server":
			return "`brew install fwcd/kotlin-language-server/kotlin-language-server`"
		case "omnisharp":
			return "`brew install omnisharp`"
		case "bash-language-server":
			return "`npm i -g bash-language-server`"
		case "docker-langserver":
			return "`npm i -g dockerfile-language-server-nodejs`"
		case "sqls":
			return "`brew install sqls`"
		case "dart":
			return "`brew install dart-sdk`"
		case "haskell-language-server-wrapper":
			return "`brew install haskell-language-server`"
		case "lua-language-server":
			return "`brew install lua-language-server`"
		case "ruby-lsp":
			return "`gem install ruby-lsp`"
		case "terraform-ls":
			return "`brew install hashicorp/tap/terraform-ls`"
		default:
			return "install `\(command)` and ensure it is executable"
		}
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
