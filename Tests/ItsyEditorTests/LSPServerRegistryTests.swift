import Foundation
import ItsyEditor
import Testing

@Test func lspServerRegistryResolvedConfigFindsCommandOnPath() throws {
	let fixture = try TemporaryRegistryFixture()
	let bin = try fixture.executable("bin/typescript-language-server")
	let registry = LSPServerRegistry(configs: [
		LSPServerConfig(languageId: "typescript", command: "typescript-language-server")
	])
	let config = registry.resolvedConfig(forLanguageID: "typescript", environment: ["PATH": bin.deletingLastPathComponent().path])
	#expect(config?.command == bin.path)
}

@Test func lspServerRegistryResolvedConfigAcceptsAbsoluteExecutable() throws {
	let fixture = try TemporaryRegistryFixture()
	let bin = try fixture.executable("bin/pyright-langserver")
	let registry = LSPServerRegistry(configs: [
		LSPServerConfig(languageId: "python", command: bin.path)
	])
	let config = registry.resolvedConfig(forLanguageID: "python", environment: ["PATH": ""])
	#expect(config?.command == bin.path)
}

@Test func lspServerRegistryReportsMissingBinaryWithInstallHint() {
	let registry = LSPServerRegistry(configs: [
		LSPServerConfig(languageId: "typescript", command: "typescript-language-server")
	])
	let missing = registry.missingBinary(forLanguageID: "typescript", environment: ["PATH": ""])
	#expect(registry.resolvedConfig(forLanguageID: "typescript", environment: ["PATH": ""]) == nil)
	#expect(missing == LSPServerRegistry.MissingBinary(
		languageID: "typescript",
		command: "typescript-language-server",
		hint: "`npm i -g typescript typescript-language-server`"
	))
}

@Test func lspServerRegistryReportsInstallHintsForBundledCommands() {
	let expectedHints: [String: String] = [
		"clangd": "`brew install llvm` and add LLVM's bin directory to PATH",
		"zls": "`brew install zls`",
		"elixir-ls": "`brew install elixir-ls`",
		"kotlin-language-server": "`brew install fwcd/kotlin-language-server/kotlin-language-server`",
		"omnisharp": "`brew install omnisharp`",
		"bash-language-server": "`npm i -g bash-language-server`",
		"docker-langserver": "`npm i -g dockerfile-language-server-nodejs`",
		"sqls": "`brew install sqls`",
		"dart": "`brew install dart-sdk`",
		"haskell-language-server-wrapper": "`brew install haskell-language-server`",
		"lua-language-server": "`brew install lua-language-server`",
		"ruby-lsp": "`gem install ruby-lsp`",
		"terraform-ls": "`brew install hashicorp/tap/terraform-ls`",
	]
	let configs = expectedHints.map { command, _ in
		LSPServerConfig(languageId: command, command: command)
	}
	let registry = LSPServerRegistry(configs: configs)
	for (command, hint) in expectedHints {
		#expect(registry.missingBinary(forLanguageID: command, environment: ["PATH": ""]) == LSPServerRegistry.MissingBinary(
			languageID: command,
			command: command,
			hint: hint
		))
	}
}

@Test func lspServerRegistryReturnsBundledDefaultsByLanguageID() {
	let registry = LSPServerRegistry()
	#expect(registry.config(forLanguageID: "swift")?.command == "/usr/bin/xcrun")
	#expect(registry.config(forLanguageID: "swift")?.args == ["sourcekit-lsp"])
	#expect(registry.config(forLanguageID: "rust")?.command == "rust-analyzer")
	#expect(registry.config(forLanguageID: "python")?.args == ["--stdio"])
	#expect(registry.config(forLanguageID: "go")?.rootPatterns == ["go.mod", ".git"])
	#expect(registry.config(forLanguageID: "c")?.command == "clangd")
	#expect(registry.config(forLanguageID: "cpp")?.command == "clangd")
	#expect(registry.config(forLanguageID: "zig")?.command == "zls")
	#expect(registry.config(forLanguageID: "elixir")?.command == "elixir-ls")
	#expect(registry.config(forLanguageID: "kotlin")?.command == "kotlin-language-server")
	#expect(registry.config(forLanguageID: "csharp")?.args == ["--languageserver"])
	#expect(registry.config(forLanguageID: "bash")?.args == ["start"])
	#expect(registry.config(forLanguageID: "dockerfile")?.args == ["--stdio"])
	#expect(registry.config(forLanguageID: "sql")?.command == "sqls")
	#expect(registry.config(forLanguageID: "dart")?.args == ["language-server", "--protocol=lsp"])
	#expect(registry.config(forLanguageID: "haskell")?.args == ["--lsp"])
	#expect(registry.config(forLanguageID: "lua")?.command == "lua-language-server")
	#expect(registry.config(forLanguageID: "ruby")?.command == "ruby-lsp")
	#expect(registry.config(forLanguageID: "terraform")?.args == ["serve"])
}

@Test func lspServerRegistryMapsCommonExtensionsToLanguageIDs() {
	let registry = LSPServerRegistry()
	let expectations: [(String, String)] = [
		("Swift", "swift"),
		("ts", "typescript"),
		("tsx", "typescript"),
		("jsx", "javascript"),
		("rs", "rust"),
		("go", "go"),
		("py", "python"),
		("c", "c"),
		("hpp", "cpp"),
		("zig", "zig"),
		("exs", "elixir"),
		("kt", "kotlin"),
		("cs", "csharp"),
		("sh", "bash"),
		("dockerfile", "dockerfile"),
		("sql", "sql"),
		("dart", "dart"),
		("hs", "haskell"),
		("lua", "lua"),
		("rb", "ruby"),
		("tfvars", "terraform"),
	]
	for (ext, languageID) in expectations {
		#expect(registry.languageID(forFileExtension: ext) == languageID)
	}
	#expect(registry.languageID(forFileName: "Dockerfile") == "dockerfile")
	#expect(registry.languageID(forFileName: "Gemfile") == "ruby")
	#expect(registry.languageID(for: URL(fileURLWithPath: "/tmp/Dockerfile.dev")) == "dockerfile")
	#expect(registry.languageID(for: URL(fileURLWithPath: "/tmp/app.tf")) == "terraform")
}

@Test func lspServerRegistryDiscoversWorkspaceRootViaRootPatterns() throws {
	let fixture = try TemporaryRegistryFixture()
	try fixture.write("a/b/c/Package.swift", "")
	try fixture.write("a/b/c/Sources/App.swift", "")

	let registry = LSPServerRegistry()
	let source = fixture.root.appendingPathComponent("a/b/c/Sources/App.swift")
	let discovered = registry.discoverWorkspaceRoot(for: source)
	#expect(discovered?.standardizedFileURL.path == fixture.root.appendingPathComponent("a/b/c").standardizedFileURL.path)
}

@Test func lspServerRegistryReturnsNilWhenNoRootPatternMatches() throws {
	let fixture = try TemporaryRegistryFixture()
	try fixture.write("a/loose.swift", "")
	let registry = LSPServerRegistry()
	let source = fixture.root.appendingPathComponent("a/loose.swift")
	#expect(registry.discoverWorkspaceRoot(for: source) == nil)
}

@Test func lspServerRegistryLoaderReadsJSONOverridesAndKeepsLanguageIDFromKey() throws {
	let fixture = try TemporaryRegistryFixture()
	let path = fixture.root.appendingPathComponent("lsp.json")
	let json = """
	{
		"swift": {
			"languageId": "ignored",
			"command": "swift-lsp-shim",
			"args": ["--lsp"],
			"rootPatterns": ["Package.swift"],
			"initOptions": {},
			"settings": {}
		}
	}
	"""
	try json.write(to: path, atomically: true, encoding: .utf8)
	let registry = try LSPServerRegistryLoader.load(from: path)
	#expect(registry.config(forLanguageID: "swift")?.command == "swift-lsp-shim")
	#expect(registry.config(forLanguageID: "swift")?.languageId == "swift")
	#expect(registry.config(forLanguageID: "rust")?.command == "rust-analyzer")
}

@Test func lspServerRegistryLoaderFallsBackToBundledWhenFileMissing() throws {
	let fixture = try TemporaryRegistryFixture()
	let path = fixture.root.appendingPathComponent("missing.json")
	let registry = LSPServerRegistryLoader.loadOrBundled(from: path)
	#expect(registry.config(forLanguageID: "swift")?.command == "/usr/bin/xcrun")
}

private final class TemporaryRegistryFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-lsp-registry-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func write(_ relativePath: String, _ contents: String) throws {
		let url = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func executable(_ relativePath: String) throws -> URL {
		let url = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try "#!/bin/sh\nexit 0\n".write(to: url, atomically: true, encoding: .utf8)
		try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
		return url
	}
}
