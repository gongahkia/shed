import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test(arguments: SupportedLanguageCapabilityMatrix.core)
func coreLanguageRootDetectionAndWorkspaceOverrides(_ row: SupportedLanguageCapabilityRow) async throws {
	let fixture = try CoreLanguageWorkspaceFixture()
	defer { fixture.cleanup() }
	let registry = LSPServerRegistry()
	let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == row.grammarID })
	let extensionName = try #require(language.fileExtensions.first)
	let bundledConfig = try #require(registry.config(forLanguageID: row.languageID))
	let marker = try #require(bundledConfig.rootPatterns.first)
	let workspace = fixture.root.appendingPathComponent("workspace-\(row.grammarID)", isDirectory: true)
	let source = workspace.appendingPathComponent("Sources/App.\(extensionName)")
	try fixture.write("", at: workspace.appendingPathComponent(marker))
	try fixture.write(language.fixture, at: source)
	#expect(registry.discoverWorkspaceRoot(for: source)?.standardizedFileURL == workspace.standardizedFileURL)

	let override = try fixture.executable("bin/\(row.grammarID)-language-server")
	try fixture.write("""
	[\(row.languageID)]
	command = "\(override.path)"
	args = ["--core", "\(row.grammarID)"]
	root_patterns = ["\(marker)"]

	[\(row.languageID).settings]
	core_grammar = "\(row.grammarID)"
	""", at: workspace.appendingPathComponent(".itsy/lsp.toml"))
	let recorder = CoreLanguageConfigurationRecorder()
	let manager = LSPManager(registry: registry, clientFactory: recorder.factory)
	_ = try await manager.ensureClient(for: source)
	#expect(recorder.configs == [LSPServerConfig(
		languageId: row.languageID,
		command: override.path,
		args: ["--core", row.grammarID],
		rootPatterns: [marker],
		typedInitOptions: [:],
		typedSettings: ["core_grammar": .string(row.grammarID)]
	)])

	let orphan = fixture.root.appendingPathComponent("orphan/Source.\(extensionName)")
	try fixture.write(language.fixture, at: orphan)
	#expect(registry.discoverWorkspaceRoot(for: orphan) == nil)
}

private final class CoreLanguageWorkspaceFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-core-language-workspace-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func write(_ contents: String, at url: URL) throws {
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func executable(_ relativePath: String) throws -> URL {
		let url = root.appendingPathComponent(relativePath)
		try write("#!/bin/sh\nexit 0\n", at: url)
		try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
		return url
	}

	func cleanup() {
		try? FileManager.default.removeItem(at: root)
	}
}

private final class CoreLanguageConfigurationRecorder: @unchecked Sendable {
	private let lock = NSLock()
	private var values: [LSPServerConfig] = []

	var configs: [LSPServerConfig] {
		lock.lock()
		defer { lock.unlock() }
		return values
	}

	var factory: LSPManager.ClientFactory {
		{ [weak self] config, _ in
			self?.lock.lock()
			self?.values.append(config)
			self?.lock.unlock()
			return LSPProcessClient(executableURL: URL(fileURLWithPath: "/usr/bin/true"))
		}
	}
}
