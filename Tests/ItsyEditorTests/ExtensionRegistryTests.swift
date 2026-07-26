import Foundation
import ItsyEditor
import Testing

@Test func extensionContributionRegistryScopesAndValidatesMetadata() throws {
	let fixture = try TemporaryExtensionRegistryFixture()
	try fixture.write("themes/night.toml", "\"text\" = \"#ffffff\"\n")
	try fixture.write("snippets/itsylog.json", "{}\n")
	let manifest = ExtensionManifest(
		schemaVersion: 2,
		identifier: "dev.example.metadata",
		name: "Metadata",
		version: "1.0.0",
		contributes: ExtensionContributions(
			themes: [ExtensionThemeContribution(id: "night", label: "Night", path: "themes/night.toml")],
			snippets: [ExtensionSnippetContribution(language: "itsylog", path: "snippets/itsylog.json")],
			languages: [ExtensionLanguageContribution(id: "itsylog", aliases: ["Itsy Log"], extensions: [".itsylog"])],
			problemMatchers: [ExtensionProblemMatcherContribution(id: "swiftc", label: "swiftc", pattern: #"^(.*):(\d+):(\d+): error: (.*)$"#)]
		)
	)

	let registered = try ExtensionContributionRegistry.register(manifest: manifest, root: fixture.root)

	#expect(registered.themes == [
		ExtensionRegisteredTheme(
			id: "extension:dev.example.metadata:night",
			label: "Night",
			url: fixture.root.appendingPathComponent("themes/night.toml")
		),
	])
	#expect(registered.snippets == [
		ExtensionRegisteredSnippet(
			language: "extension:dev.example.metadata:itsylog",
			url: fixture.root.appendingPathComponent("snippets/itsylog.json")
		),
	])
	#expect(registered.languages == [
		ExtensionRegisteredLanguage(
			id: "extension:dev.example.metadata:itsylog",
			aliases: ["Itsy Log"],
			extensions: [".itsylog"]
		),
	])
	#expect(registered.problemMatchers.map(\.id) == ["extension:dev.example.metadata:swiftc"])
}

@Test func extensionContributionRegistryRejectsEscapingMissingAndInvalidMatcherPaths() throws {
	let fixture = try TemporaryExtensionRegistryFixture()
	let escapingManifest = ExtensionManifest(
		schemaVersion: 2,
		identifier: "dev.example.bad",
		name: "Bad",
		version: "1.0.0",
		contributes: ExtensionContributions(themes: [
			ExtensionThemeContribution(id: "bad", label: "Bad", path: "../outside.toml"),
		])
	)
	let missingManifest = ExtensionManifest(
		schemaVersion: 2,
		identifier: "dev.example.bad",
		name: "Bad",
		version: "1.0.0",
		contributes: ExtensionContributions(snippets: [
			ExtensionSnippetContribution(language: "swift", path: "missing.json"),
		])
	)
	let invalidMatcherManifest = ExtensionManifest(
		schemaVersion: 2,
		identifier: "dev.example.bad",
		name: "Bad",
		version: "1.0.0",
		contributes: ExtensionContributions(problemMatchers: [
			ExtensionProblemMatcherContribution(id: "bad", label: "Bad", pattern: "["),
		])
	)

	#expect(throws: ExtensionRegistryError.parentDirectoryPath("../outside.toml")) {
		_ = try ExtensionContributionRegistry.register(manifest: escapingManifest, root: fixture.root)
	}
	#expect(throws: ExtensionRegistryError.missingContributionFile("missing.json")) {
		_ = try ExtensionContributionRegistry.register(manifest: missingManifest, root: fixture.root)
	}
	#expect(throws: ExtensionRegistryError.invalidProblemMatcherPattern("[")) {
		_ = try ExtensionContributionRegistry.register(manifest: invalidMatcherManifest, root: fixture.root)
	}
}

@Test func extensionInstallerVerifiesSHA256TrustAndCopiesInstalledVersion() throws {
	let fixture = try TemporaryExtensionRegistryFixture()
	let archiveURL = try fixture.write("downloads/example.ext", "archive-bytes")
	let sha = try ExtensionInstaller.sha256(url: archiveURL)
	let extracted = fixture.root.appendingPathComponent("extracted", isDirectory: true)
	try FileManager.default.createDirectory(at: extracted, withIntermediateDirectories: true)
	try fixture.write("extracted/extension.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.metadata",
	  "name": "Metadata",
	  "version": "1.0.0",
	  "contributes": {
	    "themes": [
	      { "id": "night", "label": "Night", "path": "themes/night.toml" }
	    ]
	  }
	}
	""")
	try fixture.write("extracted/themes/night.toml", "\"text\" = \"#ffffff\"\n")
	let repo = fixture.root.appendingPathComponent("repo", isDirectory: true)
	let workspace = fixture.root.appendingPathComponent("workspace", isDirectory: true)
	let home = fixture.root.appendingPathComponent("home", isDirectory: true)
	try FileManager.default.createDirectory(at: repo, withIntermediateDirectories: true)
	try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
	try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
	try fixture.write("repo/VOUCHED", "allow sha256:\(sha) id:dev.example.metadata version:1.0.0 signer:repo\n")
	let installRoot = fixture.root.appendingPathComponent("install", isDirectory: true)

	let result = try ExtensionInstaller.install(ExtensionInstallRequest(
		archiveURL: archiveURL,
		extractedRoot: extracted,
		expectedSHA256: sha,
		installRoot: installRoot,
		repoRoot: repo,
		workspaceRoot: workspace,
		homeDirectory: home,
		vouchEvidence: nil
	))

	#expect(result.manifest.identifier == "dev.example.metadata")
	#expect(result.installedURL == installRoot.appendingPathComponent("dev.example.metadata/1.0.0", isDirectory: true))
	#expect(FileManager.default.fileExists(atPath: result.installedURL.appendingPathComponent("extension.json").path))
	#expect(result.contributions.themes.map(\.id) == ["extension:dev.example.metadata:night"])
}

@Test func extensionInstallerRejectsHashMismatchAndTrustMissing() throws {
	let fixture = try TemporaryExtensionRegistryFixture()
	let archiveURL = try fixture.write("downloads/example.ext", "archive-bytes")
	let sha = try ExtensionInstaller.sha256(url: archiveURL)
	let extracted = fixture.root.appendingPathComponent("extracted", isDirectory: true)
	try FileManager.default.createDirectory(at: extracted, withIntermediateDirectories: true)
	try fixture.write("extracted/extension.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.metadata",
	  "name": "Metadata",
	  "version": "1.0.0",
	  "contributes": {}
	}
	""")
	let installRoot = fixture.root.appendingPathComponent("install", isDirectory: true)

	#expect(throws: ExtensionInstallError.sha256Mismatch(expected: String(repeating: "0", count: 64), actual: sha)) {
		_ = try ExtensionInstaller.install(ExtensionInstallRequest(
			archiveURL: archiveURL,
			extractedRoot: extracted,
			expectedSHA256: String(repeating: "0", count: 64),
			installRoot: installRoot,
			repoRoot: fixture.root,
			workspaceRoot: fixture.root,
			homeDirectory: fixture.root,
			vouchEvidence: nil
		))
	}
	#expect(throws: ExtensionInstallError.trustMissing(VouchSubject(
		sha256: sha,
		identifier: "dev.example.metadata",
		version: "1.0.0"
	))) {
		_ = try ExtensionInstaller.install(ExtensionInstallRequest(
			archiveURL: archiveURL,
			extractedRoot: extracted,
			expectedSHA256: sha,
			installRoot: installRoot,
			repoRoot: fixture.root,
			workspaceRoot: fixture.root,
			homeDirectory: fixture.root,
			vouchEvidence: nil
		))
	}
}

@Test func extensionInstallerAcceptsInjectedVouchEvidence() throws {
	let fixture = try TemporaryExtensionRegistryFixture()
	let archiveURL = try fixture.write("downloads/example.ext", "archive-bytes")
	let sha = try ExtensionInstaller.sha256(url: archiveURL)
	let extracted = fixture.root.appendingPathComponent("extracted", isDirectory: true)
	try FileManager.default.createDirectory(at: extracted, withIntermediateDirectories: true)
	try fixture.write("extracted/extension.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.metadata",
	  "name": "Metadata",
	  "version": "1.0.0",
	  "contributes": {}
	}
	""")

	let result = try ExtensionInstaller.install(ExtensionInstallRequest(
		archiveURL: archiveURL,
		extractedRoot: extracted,
		expectedSHA256: sha,
		installRoot: fixture.root.appendingPathComponent("install", isDirectory: true),
		repoRoot: fixture.root,
		workspaceRoot: fixture.root,
		homeDirectory: fixture.root,
		vouchEvidence: { subject in
			.allow(VouchRecord(
				directive: .allow,
				sha256: subject.sha256,
				identifier: subject.identifier,
				version: subject.version,
				signer: "test-cli"
			))
		}
	))

	#expect(result.trust == .allow(VouchRecord(
		directive: .allow,
		sha256: sha,
		identifier: "dev.example.metadata",
		version: "1.0.0",
		signer: "test-cli"
	)))
}

@Test func extensionMarketplaceCacheRoundTripsIndex() throws {
	let fixture = try TemporaryExtensionRegistryFixture()
	let cache = ExtensionMarketplaceCache(entries: [
		ExtensionMarketplaceEntry(
			identifier: "dev.example.metadata",
			name: "Metadata",
			version: "1.0.0",
			archiveURL: URL(string: "https://example.com/metadata.ext")!,
			sha256: String(repeating: "a", count: 64)
		),
	])
	let url = fixture.root.appendingPathComponent("cache/index.json")

	try cache.save(url: url)
	let loaded = try ExtensionMarketplaceCache.load(url: url)

	#expect(loaded == cache)
	#expect(loaded.entry(identifier: "dev.example.metadata")?.version == "1.0.0")
	#expect(try ExtensionMarketplaceClient.fetchIndex(url: url) == cache)
}

private final class TemporaryExtensionRegistryFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-extension-registry-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	@discardableResult
	func write(_ path: String, _ contents: String) throws -> URL {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
		return url
	}
}
