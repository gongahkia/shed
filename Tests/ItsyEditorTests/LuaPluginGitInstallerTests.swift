import Foundation
import ItsyEditor
import Testing

@Test func luaPluginGitInstallerInstallsUpdatesAndRemovesLockedPackage() throws {
	let fixture = try LuaPluginGitInstallerFixture()
	defer { fixture.remove() }
	let firstRevision = try fixture.commitPlugin(contents: "return { value = 1 }\n")
	let firstRequest = fixture.request(revision: firstRevision)

	let installed = try LuaPluginGitInstaller.install(firstRequest)
	let destination = LuaPluginGitInstaller.installedURL(for: firstRequest)

	#expect(installed.revision == firstRevision)
	#expect(try LuaPluginGitInstaller.loadReceipt(installedURL: destination) == installed)
	#expect(try String(contentsOf: destination.appendingPathComponent("main.lua"), encoding: .utf8) == "return { value = 1 }\n")
	#expect(!FileManager.default.fileExists(atPath: destination.appendingPathComponent(".git").path))
	#expect(throws: LuaPluginGitInstallError.packageAlreadyInstalled(destination)) {
		_ = try LuaPluginGitInstaller.install(firstRequest)
	}

	let secondRevision = try fixture.commitPlugin(contents: "return { value = 2 }\n")
	let updated = try LuaPluginGitInstaller.update(fixture.request(revision: secondRevision))

	#expect(updated.revision == secondRevision)
	#expect(try String(contentsOf: destination.appendingPathComponent("main.lua"), encoding: .utf8) == "return { value = 2 }\n")
	#expect(try LuaPluginGitInstaller.remove(
		packageIdentifier: fixture.identifier,
		packageVersion: fixture.version,
		installRoot: fixture.installRoot
	) == updated)
	#expect(!FileManager.default.fileExists(atPath: destination.path))
}

@Test func luaPluginGitInstallerRejectsAbbreviatedRevisionsAndMissingUpdates() throws {
	let fixture = try LuaPluginGitInstallerFixture()
	defer { fixture.remove() }
	let revision = try fixture.commitPlugin(contents: "return {}\n")
	let request = fixture.request(revision: revision)

	#expect(throws: LuaPluginGitInstallError.invalidRevision(String(revision.prefix(12)))) {
		_ = try LuaPluginGitInstaller.install(fixture.request(revision: String(revision.prefix(12))))
	}
	#expect(throws: LuaPluginGitInstallError.packageNotInstalled(LuaPluginGitInstaller.installedURL(for: request))) {
		_ = try LuaPluginGitInstaller.update(request)
	}
}

@Test func luaPluginGitInstallerRejectsManifestIdentityChangesDuringUpdate() throws {
	let fixture = try LuaPluginGitInstallerFixture()
	defer { fixture.remove() }
	let firstRevision = try fixture.commitPlugin(contents: "return { value = 1 }\n")
	let request = fixture.request(revision: firstRevision)
	_ = try LuaPluginGitInstaller.install(request)
	let changedRevision = try fixture.commitPlugin(contents: "return { value = 2 }\n", identifier: "dev.example.changed")

	#expect(throws: LuaPluginGitInstallError.manifestIdentityMismatch(
		expectedIdentifier: fixture.identifier,
		expectedVersion: fixture.version,
		actualIdentifier: "dev.example.changed",
		actualVersion: fixture.version
	)) {
		_ = try LuaPluginGitInstaller.update(fixture.request(revision: changedRevision))
	}
	let destination = LuaPluginGitInstaller.installedURL(for: request)
	#expect(try String(contentsOf: destination.appendingPathComponent("main.lua"), encoding: .utf8) == "return { value = 1 }\n")
}

private final class LuaPluginGitInstallerFixture {
	let root: URL
	let source: URL
	let installRoot: URL
	let identifier = "dev.example.plugin"
	let version = "1.0.0"
	private let runner = ProcessGitCommandRunner()

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-lua-plugin-git-\(UUID().uuidString)", isDirectory: true)
		source = root.appendingPathComponent("source", isDirectory: true)
		installRoot = root.appendingPathComponent("installed", isDirectory: true)
		try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
		_ = try git(["init", "--initial-branch=main"])
	}

	func request(revision: String) -> LuaPluginGitInstallRequest {
		LuaPluginGitInstallRequest(
			repositoryURL: source.path,
			revision: revision,
			packageIdentifier: identifier,
			packageVersion: version,
			installRoot: installRoot
		)
	}

	func commitPlugin(contents: String, identifier: String? = nil) throws -> String {
		try write("main.lua", contents)
		try write("itsy.lua", """
			return {
				manifest_version = 1,
				id = "\(identifier ?? self.identifier)",
				version = "\(version)",
				api = ">=1.0.0 <2.0.0",
				entrypoint = "main.lua",
			}
			""")
		_ = try git(["add", "."])
		_ = try git(["-c", "user.name=Itsy Tests", "-c", "user.email=tests@example.invalid", "commit", "-m", "plugin"])
		return try git(["rev-parse", "HEAD"]).trimmingCharacters(in: .whitespacesAndNewlines)
	}

	func remove() {
		try? FileManager.default.removeItem(at: root)
	}

	private func write(_ path: String, _ contents: String) throws {
		let url = source.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	private func git(_ arguments: [String]) throws -> String {
		try runner.runGit(arguments: arguments, root: source)
	}
}
