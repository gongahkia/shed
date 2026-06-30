import Foundation
import ItsyEditor
import Testing

@Test func lspServerRegistryReturnsBundledDefaultsByLanguageID() {
	let registry = LSPServerRegistry()
	#expect(registry.config(forLanguageID: "swift")?.command == "/usr/bin/xcrun")
	#expect(registry.config(forLanguageID: "swift")?.args == ["sourcekit-lsp"])
	#expect(registry.config(forLanguageID: "rust")?.command == "rust-analyzer")
	#expect(registry.config(forLanguageID: "python")?.args == ["--stdio"])
	#expect(registry.config(forLanguageID: "go")?.rootPatterns == ["go.mod", ".git"])
	#expect(registry.config(forLanguageID: "kotlin") == nil)
}

@Test func lspServerRegistryMapsCommonExtensionsToLanguageIDs() {
	let registry = LSPServerRegistry()
	#expect(registry.languageID(forFileExtension: "Swift") == "swift")
	#expect(registry.languageID(forFileExtension: "ts") == "typescript")
	#expect(registry.languageID(forFileExtension: "tsx") == "typescript")
	#expect(registry.languageID(forFileExtension: "jsx") == "javascript")
	#expect(registry.languageID(forFileExtension: "rs") == "rust")
	#expect(registry.languageID(forFileExtension: "go") == "go")
	#expect(registry.languageID(forFileExtension: "py") == "python")
	#expect(registry.languageID(forFileExtension: "kt") == nil)
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
}
