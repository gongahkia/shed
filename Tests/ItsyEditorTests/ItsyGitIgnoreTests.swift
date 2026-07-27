import Foundation
import ItsyEditor
import Testing

@Test func itsyGitIgnoreAppendsRuleAtRepositoryRootOnce() throws {
	let fixture = try TemporaryItsyGitFixture()
	defer { fixture.cleanUp() }
	try fixture.git(["init"])
	try fixture.write(".gitignore", "build\n")
	let nested = fixture.root.appendingPathComponent("Sources/App", isDirectory: true)
	try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)

	let expected = fixture.root.appendingPathComponent(".gitignore")
	#expect(ItsyGitIgnore.ensureItsyDirectoryIgnored(in: nested) == .appended(expected))
	#expect(try fixture.contents(".gitignore") == "build\n.itsy/\n")
	#expect(ItsyGitIgnore.ensureItsyDirectoryIgnored(in: nested) == .alreadyIgnored(expected))
	#expect(try fixture.contents(".gitignore") == "build\n.itsy/\n")
}

@Test func itsyGitIgnoreLeavesNonRepositoryUntouched() throws {
	let fixture = try TemporaryItsyGitFixture()
	defer { fixture.cleanUp() }

	#expect(ItsyGitIgnore.ensureItsyDirectoryIgnored(in: fixture.root) == .notRepository)
	#expect(!FileManager.default.fileExists(atPath: fixture.root.appendingPathComponent(".gitignore").path))
}

@Test func itsyGitIgnoreRecognizesExistingEquivalentRule() throws {
	let fixture = try TemporaryItsyGitFixture()
	defer { fixture.cleanUp() }
	try fixture.git(["init"])
	try fixture.write(".gitignore", "/.itsy\n")

	let expected = fixture.root.appendingPathComponent(".gitignore")
	#expect(ItsyGitIgnore.ensureItsyDirectoryIgnored(in: fixture.root) == .alreadyIgnored(expected))
	#expect(try fixture.contents(".gitignore") == "/.itsy\n")
}

private struct TemporaryItsyGitFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-git-ignore-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func git(_ arguments: [String]) throws {
		_ = try ProcessGitCommandRunner().runGit(arguments: arguments, root: root)
	}

	func write(_ path: String, _ contents: String) throws {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func contents(_ path: String) throws -> String {
		try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
	}

	func cleanUp() {
		try? FileManager.default.removeItem(at: root)
	}
}
