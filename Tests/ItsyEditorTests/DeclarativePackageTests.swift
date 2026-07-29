import Foundation
import ItsyEditor
import Testing

@Test func packageConfigurationResolvesProjectSourcesAndResourceFilters() throws {
	let global = try DeclarativePackageConfigurationParser.parse(data: Data("""
	{"schema_version":1,"sources":[{"id":"theme","package_id":"dev.example.theme","kind":"path","location":"/tmp/theme","resource_filters":[{"resource":"theme:night","enabled":false}]}]}
	""".utf8))
	let project = try DeclarativePackageConfigurationParser.parse(data: Data("""
	{"schema_version":1,"sources":[{"id":"theme","package_id":"dev.example.theme","kind":"path","location":"/tmp/project-theme","resource_filters":[{"resource":"theme:night","enabled":true}]}]}
	""".utf8))

	let resolved = DeclarativePackageResolver.effectiveSources(global: global, project: project)

	#expect(resolved.count == 1)
	#expect(resolved[0].scope == .project)
	#expect(resolved[0].source.location == "/tmp/project-theme")
	#expect(DeclarativePackageResolver.isResourceEnabled("theme:night", for: resolved[0].source))
	#expect(throws: DeclarativePackageConfigurationError.missingSHA256("remote")) {
		_ = try DeclarativePackageConfigurationParser.parse(data: Data(#"{"schema_version":1,"sources":[{"id":"remote","package_id":"dev.example.remote","kind":"url","location":"https://example.com/package.zip"}]}"#.utf8))
	}
}

@Test func packageValidatorRejectsSymlinksAndArchiveTraversal() throws {
	let fixture = try TemporaryPackageFixture()
	try fixture.writeManifest()
	try FileManager.default.createSymbolicLink(at: fixture.root.appendingPathComponent("escape"), withDestinationURL: URL(fileURLWithPath: "/tmp"))
	#expect(throws: DeclarativePackageValidationError.symlinkRejected("escape")) {
		_ = try DeclarativePackageValidator.inspectDirectory(fixture.root, expectedPackageID: "dev.example.package")
	}
	try FileManager.default.removeItem(at: fixture.root.appendingPathComponent("escape"))
	let executable = try fixture.writeData("run", Data("not executable content".utf8))
	try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: executable.path)
	#expect(throws: DeclarativePackageValidationError.executableFileRejected("run")) {
		_ = try DeclarativePackageValidator.inspectDirectory(fixture.root, expectedPackageID: "dev.example.package")
	}
	try FileManager.default.removeItem(at: executable)
	let archive = try fixture.writeData("bad.zip", zipDirectoryEntry(path: "../escape"))
	let hash = try DeclarativePackageValidator.sha256File(archive)
	#expect(throws: DeclarativePackageValidationError.sha256Mismatch(expected: String(repeating: "0", count: 64), actual: hash)) {
		_ = try DeclarativePackageValidator.inspectArchive(archive, expectedSHA256: String(repeating: "0", count: 64))
	}
	#expect(throws: DeclarativePackageValidationError.archiveTraversal("../escape")) {
		_ = try DeclarativePackageValidator.inspectArchive(archive, expectedSHA256: hash)
	}
}

@Test func packageStorePersistsEnablementAndRemoval() throws {
	let fixture = try TemporaryPackageFixture()
	let store = DeclarativePackageStore(globalURL: fixture.root.appendingPathComponent("packages.json"))
	try store.save(DeclarativePackageConfiguration(sources: [.init(id: "package", packageID: "dev.example.package", kind: .path, location: "/tmp/package")]), scope: .global)
	try store.setEnabled(false, sourceID: "package", scope: .global)
	#expect(try store.load(scope: .global).sources.first?.enabled == false)
	try store.remove(sourceID: "package", scope: .global)
	#expect(try store.load(scope: .global).sources.isEmpty)
}

@Test func packageManagerInstallsVouchedLocalDirectory() async throws {
	let fixture = try TemporaryPackageFixture()
	try fixture.writeManifest()
	let source = DeclarativePackageSource(id: "package", packageID: "dev.example.package", kind: .path, location: fixture.root.path)
	let receipt = try await DeclarativePackageManager.install(
		source: source,
		scope: .global,
		repoRoot: fixture.root,
		workspaceRoot: fixture.root,
		homeDirectory: fixture.home,
		vouchEvidence: { subject in .allow(VouchRecord(directive: .allow, sha256: subject.sha256, identifier: subject.identifier, version: subject.version, signer: "test")) }
	)
	#expect(receipt.identifier == "dev.example.package")
	#expect(FileManager.default.fileExists(atPath: fixture.home.appendingPathComponent(".config/itsy/packages/dev.example.package/1.0.0/itsy-package.json").path))
}

private final class TemporaryPackageFixture {
	let root: URL
	let home: URL

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-package-\(UUID().uuidString)", isDirectory: true)
		home = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-package-home-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
		try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
		try? FileManager.default.removeItem(at: home)
	}

	func writeManifest() throws {
		try """
		{"schema_version":1,"identifier":"dev.example.package","name":"Package","version":"1.0.0","resources":["theme:night"]}
		""".write(to: root.appendingPathComponent("itsy-package.json"), atomically: true, encoding: .utf8)
	}

	func writeData(_ name: String, _ data: Data) throws -> URL {
		let url = root.appendingPathComponent(name)
		try data.write(to: url)
		return url
	}
}

private func zipDirectoryEntry(path: String) -> Data {
	func u16(_ value: UInt16) -> Data { Data([UInt8(value & 0xff), UInt8(value >> 8)]) }
	func u32(_ value: UInt32) -> Data { Data([UInt8(value & 0xff), UInt8((value >> 8) & 0xff), UInt8((value >> 16) & 0xff), UInt8(value >> 24)]) }
	let name = Data(path.utf8)
	var directory = Data()
	directory += u32(0x02014b50)
	directory += u16(20) + u16(20) + u16(0) + u16(0) + u16(0) + u16(0)
	directory += u32(0) + u32(0) + u32(0)
	directory += u16(UInt16(name.count)) + u16(0) + u16(0) + u16(0) + u16(0)
	directory += u32(0) + u32(0)
	directory += name
	var end = Data()
	end += u32(0x06054b50)
	end += u16(0) + u16(0) + u16(1) + u16(1)
	end += u32(UInt32(directory.count)) + u32(0) + u16(0)
	return directory + end
}
