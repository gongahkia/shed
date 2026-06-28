import Foundation
import PicoEditor
import Testing

@Test func projectFindWalksRootAndHonorsGitIgnore() throws {
	let fixture = try TemporaryProjectFindFixture()
	defer { fixture.cleanUp() }
	try fixture.write("keep.txt", "alpha needle\nbeta\nneedle again\n")
	try fixture.write("ignored.log", "needle\n")
	try fixture.write("ignored/nested.txt", "needle\n")
	try fixture.write(".gitignore", "*.log\nignored/\n")

	let matches = ProjectFind.search(root: fixture.root, options: ProjectFindOptions(query: "needle"))
	#expect(matches.map(\.relativePath) == ["keep.txt", "keep.txt"])
	#expect(matches.map(\.line) == [1, 3])
	#expect(matches.map(\.column) == [7, 1])
}

@Test func projectFindDefaultsToCaseInsensitiveSearch() throws {
	let fixture = try TemporaryProjectFindFixture()
	defer { fixture.cleanUp() }
	try fixture.write("case.txt", "Alpha\n")

	let matches = ProjectFind.search(root: fixture.root, options: ProjectFindOptions(query: "alpha"))
	#expect(matches.count == 1)
	#expect(matches[0].relativePath == "case.txt")
}

private struct TemporaryProjectFindFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("pico-project-find-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func write(_ relativePath: String, _ contents: String) throws {
		let url = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func cleanUp() {
		try? FileManager.default.removeItem(at: root)
	}
}
