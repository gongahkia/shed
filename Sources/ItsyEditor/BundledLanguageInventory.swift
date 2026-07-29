import Foundation

public enum BundledLanguageUnsupportedReason: String, Codable, Equatable, Sendable {
	case noBundledServer = "no_bundled_server"
}

public enum BundledLanguageSupport: Equatable, Sendable {
	case supported
	case unsupported(BundledLanguageUnsupportedReason)
}

public enum BundledLanguageLSPCapabilityState: String, Codable, Equatable, Sendable {
	case declaredServer = "declared_server"
	case unavailable
}

public struct BundledLanguageServer: Equatable, Sendable {
	public var id: String
	public var command: String
	public var args: [String]
	public var rootPatterns: [String]
	public var executableProbe: String
	public var installHint: String
	public var approvedPlatformLocations: [String]
	public var minimumVersion: LSPServerVersion?

	public init(id: String, command: String, args: [String], rootPatterns: [String], executableProbe: String, installHint: String, approvedPlatformLocations: [String] = ["/opt/homebrew/bin", "/usr/local/bin"], minimumVersion: LSPServerVersion? = nil) {
		self.id = id
		self.command = command
		self.args = args
		self.rootPatterns = rootPatterns
		self.executableProbe = executableProbe
		self.installHint = installHint
		self.approvedPlatformLocations = approvedPlatformLocations
		self.minimumVersion = minimumVersion
	}

	func config(languageID: String) -> LSPServerConfig {
		LSPServerConfig(languageId: languageID, command: command, args: args, rootPatterns: rootPatterns)
	}

	var detectionProbe: LSPExecutableProbe {
		LSPExecutableProbe(executable: executableProbe, approvedPlatformLocations: approvedPlatformLocations, minimumVersion: minimumVersion)
	}
}

public struct BundledLanguage: Equatable, Sendable {
	public var grammarID: String
	public var languageID: String
	public var fileExtensions: [String]
	public var fileNames: [String]
	public var fixture: String
	public var server: BundledLanguageServer?
	public var support: BundledLanguageSupport

	public init(grammarID: String, languageID: String, fileExtensions: [String], fileNames: [String] = [], fixture: String, server: BundledLanguageServer? = nil, support: BundledLanguageSupport) {
		self.grammarID = grammarID
		self.languageID = languageID
		self.fileExtensions = fileExtensions
		self.fileNames = fileNames
		self.fixture = fixture
		self.server = server
		self.support = support
	}

	public var lspCapabilityState: BundledLanguageLSPCapabilityState {
		switch support {
		case .supported:
			.declaredServer
		case .unsupported:
			.unavailable
		}
	}
}

public enum BundledLanguageInventoryValidationError: Error, Equatable, Sendable {
	case duplicateGrammarID(String)
	case duplicateFileExtension(String)
	case duplicateFileName(String)
	case missingFixture(String)
	case missingServer(String)
	case missingExecutableProbe(String)
	case missingUnsupportedReason(String)
	case unsupportedLanguageHasServer(String)
}

public enum BundledLanguageInventory {
	public static let languages: [BundledLanguage] = [
		supported("bash", extensions: ["bash", "sh", "zsh"], fixture: "echo hi\n", server: bashLanguageServer),
		supported("c", extensions: ["c", "h"], fixture: "int main(void) { return 0; }\n", server: clangd),
		supported("cpp", extensions: ["cc", "cpp", "cxx", "hh", "hpp", "hxx"], fixture: "int main() { return 0; }\n", server: clangd),
		supported("csharp", extensions: ["cs", "csx"], fixture: "class Program { static void Main() {} }\n", server: omnisharp),
		supported("css", extensions: ["css"], fixture: ".app { color: red; }\n", server: css),
		supported("dart", extensions: ["dart"], fixture: "void main() {}\n", server: dart),
		supported("dockerfile", extensions: ["dockerfile"], fileNames: ["dockerfile", "containerfile"], fixture: "FROM scratch\n", server: docker),
		supported("elixir", extensions: ["ex", "exs"], fixture: "value = 1\n", server: elixir),
		supported("go", extensions: ["go"], fixture: "package main\nfunc main() {}\n", server: gopls),
		unsupported("graphql", extensions: ["graphql", "gql"], fixture: "type Query { hello: String }\n"),
		supported("haskell", extensions: ["hs", "lhs"], fixture: "main = putStrLn \"hi\"\n", server: haskell),
		supported("html", extensions: ["html", "htm"], fixture: "<main>hello</main>\n", server: html),
		supported("java", extensions: ["java"], fixture: "class Main { void run() {} }\n", server: java),
		supported("javascript", extensions: ["js", "jsx", "mjs", "cjs"], fixture: "const value = 1;\n", server: javascript),
		unsupported("julia", extensions: ["jl"], fixture: "value = 1\n"),
		supported("json", extensions: ["json", "jsonc"], fixture: "{\"value\": true}\n", server: json),
		supported("kotlin", extensions: ["kt", "kts"], fixture: "fun main() {}\n", server: kotlin),
		unsupported("latex", extensions: ["tex", "sty", "cls"], fixture: "\\section{Hi}\n"),
		supported("lua", extensions: ["lua"], fixture: "local value = 1\n", server: lua),
		supported("markdown", extensions: ["md", "markdown"], fixture: "# Heading\n", server: markdown),
		unsupported("markdown-inline", extensions: [], fixture: "**inline**", languageID: "markdown"),
		unsupported("nix", extensions: ["nix"], fixture: "{ value = 1; }\n"),
		unsupported("ocaml", extensions: ["ml", "mli"], fixture: "let value = 1\n"),
		supported("php", extensions: ["php"], fixture: "<?php echo \"hi\";\n", server: php),
		unsupported("proto", extensions: ["proto"], fixture: "syntax = \"proto3\"; message Value {}\n"),
		supported("python", extensions: ["py", "pyi"], fixture: "value = 1\n", server: pyright),
		unsupported("r", extensions: ["r"], fixture: "value <- 1\n"),
		supported("ruby", extensions: ["rb", "rake"], fileNames: ["gemfile", "rakefile"], fixture: "value = 1\n", server: ruby),
		supported("rust", extensions: ["rs"], fixture: "fn main() {}\n", server: rustAnalyzer),
		supported("scss", extensions: ["scss"], fixture: "$color: red;\n", server: scss),
		supported("sql", extensions: ["sql"], fixture: "select 1;\n", server: sqls),
		supported("svelte", extensions: ["svelte"], fixture: "<h1>Hello</h1>\n", server: svelte),
		supported("swift", extensions: ["swift"], fixture: "let value = 1\n", server: sourceKit),
		supported("terraform", extensions: ["tf", "tfvars", "hcl"], fixture: "resource \"x\" \"y\" {}\n", server: terraform),
		supported("toml", extensions: ["toml"], fixture: "value = 1\n", server: toml),
		supported("tsx", extensions: ["tsx"], fixture: "const View = <main />;\n", languageID: "typescript", server: typescript),
		supported("typescript", extensions: ["ts", "mts", "cts"], fixture: "const value: number = 1;\n", server: typescript),
		supported("vue", extensions: ["vue"], fixture: "<template><main>Hello</main></template>\n", server: vue),
		supported("yaml", extensions: ["yaml", "yml"], fixture: "value: true\n", server: yaml),
		supported("zig", extensions: ["zig", "zon"], fixture: "pub fn main() void {}\n", server: zls),
	]

	public static var lspConfigs: [LSPServerConfig] {
		LSPServerRegistrationCatalog.bundled.map(\.config)
	}

	public static var fileExtensionMap: [String: String] {
		Dictionary(languages.flatMap { language in
			language.fileExtensions.map { ($0.lowercased(), language.languageID) }
		}, uniquingKeysWith: { first, _ in first })
	}

	public static var fileNameMap: [String: String] {
		Dictionary(languages.flatMap { language in
			language.fileNames.map { ($0.lowercased(), language.languageID) }
		}, uniquingKeysWith: { first, _ in first })
	}

	public static func server(probing executable: String) -> BundledLanguageServer? {
		languages.compactMap(\.server).first { $0.executableProbe == executable }
	}

	public static func server(forLanguageID languageID: String) -> BundledLanguageServer? {
		languages.first { $0.languageID == languageID }?.server
	}

	public static func validationErrors(for languages: [BundledLanguage] = languages) -> [BundledLanguageInventoryValidationError] {
		var errors: [BundledLanguageInventoryValidationError] = []
		var grammarIDs: Set<String> = []
		var extensions: Set<String> = []
		var fileNames: Set<String> = []
		for language in languages {
			if !grammarIDs.insert(language.grammarID).inserted {
				errors.append(.duplicateGrammarID(language.grammarID))
			}
			if language.fixture.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
				errors.append(.missingFixture(language.grammarID))
			}
			switch (language.server, language.support) {
			case (nil, .supported):
				errors.append(.missingServer(language.grammarID))
			case (nil, .unsupported):
				break
			case (.some, .supported):
				break
			case (.some, .unsupported):
				errors.append(.unsupportedLanguageHasServer(language.grammarID))
			}
			if let server = language.server, server.executableProbe.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
				errors.append(.missingExecutableProbe(language.grammarID))
			}
			for fileExtension in language.fileExtensions.map({ $0.lowercased() }) where !extensions.insert(fileExtension).inserted {
				errors.append(.duplicateFileExtension(fileExtension))
			}
			for fileName in language.fileNames.map({ $0.lowercased() }) where !fileNames.insert(fileName).inserted {
				errors.append(.duplicateFileName(fileName))
			}
		}
		return errors
	}

	private static func supported(_ grammarID: String, extensions: [String], fileNames: [String] = [], fixture: String, languageID: String? = nil, server: BundledLanguageServer) -> BundledLanguage {
		BundledLanguage(grammarID: grammarID, languageID: languageID ?? grammarID, fileExtensions: extensions, fileNames: fileNames, fixture: fixture, server: server, support: .supported)
	}

	private static func unsupported(_ grammarID: String, extensions: [String], fileNames: [String] = [], fixture: String, languageID: String? = nil) -> BundledLanguage {
		BundledLanguage(grammarID: grammarID, languageID: languageID ?? grammarID, fileExtensions: extensions, fileNames: fileNames, fixture: fixture, support: .unsupported(.noBundledServer))
	}

	private static let sourceKit = BundledLanguageServer(id: "sourcekit-lsp", command: "/usr/bin/xcrun", args: ["sourcekit-lsp"], rootPatterns: ["Package.swift", ".git"], executableProbe: "sourcekit-lsp", installHint: "Open Language & Debugger Support in Itsy to install Xcode Command Line Tools.")
	private static let css = BundledLanguageServer(id: "vscode-css-language-server", command: "vscode-css-language-server", args: ["--stdio"], rootPatterns: ["package.json", ".git"], executableProbe: "vscode-css-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let html = BundledLanguageServer(id: "vscode-html-language-server", command: "vscode-html-language-server", args: ["--stdio"], rootPatterns: ["package.json", ".git"], executableProbe: "vscode-html-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let java = BundledLanguageServer(id: "jdtls", command: "jdtls", args: [], rootPatterns: ["pom.xml", "build.gradle", "settings.gradle", ".git"], executableProbe: "jdtls", installHint: "Open Language & Debugger Support in Itsy.")
	private static let json = BundledLanguageServer(id: "vscode-json-language-server", command: "vscode-json-language-server", args: ["--stdio"], rootPatterns: ["package.json", ".git"], executableProbe: "vscode-json-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let markdown = BundledLanguageServer(id: "marksman", command: "marksman", args: ["server"], rootPatterns: [".marksman.toml", ".git"], executableProbe: "marksman", installHint: "Open Language & Debugger Support in Itsy.")
	private static let php = BundledLanguageServer(id: "intelephense", command: "intelephense", args: ["--stdio"], rootPatterns: ["composer.json", ".git"], executableProbe: "intelephense", installHint: "Open Language & Debugger Support in Itsy.")
	private static let scss = BundledLanguageServer(id: "vscode-css-language-server", command: "vscode-css-language-server", args: ["--stdio"], rootPatterns: ["package.json", ".git"], executableProbe: "vscode-css-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let svelte = BundledLanguageServer(id: "svelte-language-server", command: "svelteserver", args: ["--stdio"], rootPatterns: ["svelte.config.js", "package.json", ".git"], executableProbe: "svelteserver", installHint: "Open Language & Debugger Support in Itsy.")
	private static let toml = BundledLanguageServer(id: "taplo", command: "taplo", args: ["lsp", "stdio"], rootPatterns: ["Cargo.toml", ".git"], executableProbe: "taplo", installHint: "Open Language & Debugger Support in Itsy.")
	private static let typescript = BundledLanguageServer(id: "typescript-language-server", command: "typescript-language-server", args: ["--stdio"], rootPatterns: ["tsconfig.json", "package.json", ".git"], executableProbe: "typescript-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let vue = BundledLanguageServer(id: "vue-language-server", command: "vue-language-server", args: ["--stdio"], rootPatterns: ["vite.config.ts", "vite.config.js", "package.json", ".git"], executableProbe: "vue-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let yaml = BundledLanguageServer(id: "yaml-language-server", command: "yaml-language-server", args: ["--stdio"], rootPatterns: [".git"], executableProbe: "yaml-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let javascript = BundledLanguageServer(id: "typescript-language-server", command: "typescript-language-server", args: ["--stdio"], rootPatterns: ["package.json", "jsconfig.json", ".git"], executableProbe: "typescript-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let rustAnalyzer = BundledLanguageServer(id: "rust-analyzer", command: "rust-analyzer", args: [], rootPatterns: ["Cargo.toml", ".git"], executableProbe: "rust-analyzer", installHint: "Open Language & Debugger Support in Itsy.")
	private static let pyright = BundledLanguageServer(id: "pyright", command: "pyright-langserver", args: ["--stdio"], rootPatterns: ["pyproject.toml", "setup.py", "setup.cfg", ".git"], executableProbe: "pyright-langserver", installHint: "Open Language & Debugger Support in Itsy.")
	private static let gopls = BundledLanguageServer(id: "gopls", command: "gopls", args: [], rootPatterns: ["go.mod", ".git"], executableProbe: "gopls", installHint: "Open Language & Debugger Support in Itsy.")
	private static let clangd = BundledLanguageServer(id: "clangd", command: "clangd", args: [], rootPatterns: ["compile_commands.json", "compile_flags.txt", ".clangd", ".git"], executableProbe: "clangd", installHint: "Open Language & Debugger Support in Itsy to install Xcode Command Line Tools.")
	private static let zls = BundledLanguageServer(id: "zls", command: "zls", args: [], rootPatterns: ["build.zig", ".git"], executableProbe: "zls", installHint: "Open Language & Debugger Support in Itsy.")
	private static let elixir = BundledLanguageServer(id: "elixir-ls", command: "elixir-ls", args: [], rootPatterns: ["mix.exs", ".git"], executableProbe: "elixir-ls", installHint: "Open Language & Debugger Support in Itsy.")
	private static let kotlin = BundledLanguageServer(id: "kotlin-language-server", command: "kotlin-language-server", args: [], rootPatterns: ["settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", ".git"], executableProbe: "kotlin-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let omnisharp = BundledLanguageServer(id: "omnisharp", command: "omnisharp", args: ["--languageserver"], rootPatterns: ["omnisharp.json", "global.json", "Directory.Build.props", ".git"], executableProbe: "omnisharp", installHint: "Open Language & Debugger Support in Itsy.")
	private static let bashLanguageServer = BundledLanguageServer(id: "bash-language-server", command: "bash-language-server", args: ["start"], rootPatterns: [".git"], executableProbe: "bash-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let docker = BundledLanguageServer(id: "docker-langserver", command: "docker-langserver", args: ["--stdio"], rootPatterns: [".git"], executableProbe: "docker-langserver", installHint: "Open Language & Debugger Support in Itsy.")
	private static let sqls = BundledLanguageServer(id: "sqls", command: "sqls", args: [], rootPatterns: [".git"], executableProbe: "sqls", installHint: "Open Language & Debugger Support in Itsy.")
	private static let dart = BundledLanguageServer(id: "dart", command: "dart", args: ["language-server", "--protocol=lsp"], rootPatterns: ["pubspec.yaml", ".git"], executableProbe: "dart", installHint: "Open Language & Debugger Support in Itsy.")
	private static let haskell = BundledLanguageServer(id: "haskell-language-server", command: "haskell-language-server-wrapper", args: ["--lsp"], rootPatterns: ["hie.yaml", "stack.yaml", "cabal.project", "package.yaml", ".git"], executableProbe: "haskell-language-server-wrapper", installHint: "Open Language & Debugger Support in Itsy.")
	private static let lua = BundledLanguageServer(id: "lua-language-server", command: "lua-language-server", args: [], rootPatterns: [".luarc.json", ".luarc.jsonc", ".git"], executableProbe: "lua-language-server", installHint: "Open Language & Debugger Support in Itsy.")
	private static let ruby = BundledLanguageServer(id: "ruby-lsp", command: "ruby-lsp", args: [], rootPatterns: ["Gemfile", ".git"], executableProbe: "ruby-lsp", installHint: "Open Language & Debugger Support in Itsy.")
	private static let terraform = BundledLanguageServer(id: "terraform-ls", command: "terraform-ls", args: ["serve"], rootPatterns: [".terraform", ".git"], executableProbe: "terraform-ls", installHint: "Open Language & Debugger Support in Itsy.")
}
