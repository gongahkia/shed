import Foundation
import ItsyEditor
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

@Test func projectReplacePreviewListsAffectedFilesAndAppliesAtomically() throws {
	let fixture = try TemporaryProjectFindFixture()
	defer { fixture.cleanUp() }
	try fixture.write("one.txt", "needle needle\n")
	try fixture.write("nested/two.txt", "needle\n")

	let preview = ProjectReplace.preview(root: fixture.root, options: ProjectReplaceOptions(query: "needle", replacement: "pin"))
	#expect(preview.files.map(\.relativePath) == ["nested/two.txt", "one.txt"])
	#expect(preview.files.map(\.replacementCount) == [1, 2])
	#expect(preview.replacementCount == 3)
	let result = try ProjectReplace.apply(preview)
	#expect(result.fileCount == 2)
	#expect(result.replacementCount == 3)
	#expect(result.recoveryJournalURL == nil)
	#expect(try fixture.contents("one.txt") == "pin pin\n")
	#expect(try fixture.contents("nested/two.txt") == "pin\n")
}

@Test func projectReplaceCancellationAndWriteFailureLeaveFilesUnchanged() throws {
	let fixture = try TemporaryProjectFindFixture()
	defer { fixture.cleanUp() }
	try fixture.write("one.txt", "needle\n")
	try fixture.write("two.txt", "needle\n")
	let preview = ProjectReplace.preview(root: fixture.root, options: ProjectReplaceOptions(query: "needle", replacement: "pin"))
	#expect(try fixture.contents("one.txt") == "needle\n")

	var writes = 0
	let io = ProjectReplaceIO(
		read: { try Data(contentsOf: $0) },
		write: { data, url in
			if url.pathExtension == "txt" {
				writes += 1
				if writes == 2 {
					throw CocoaError(.fileWriteUnknown)
				}
			}
			try data.write(to: url, options: .atomic)
		},
		createDirectory: { try FileManager.default.createDirectory(at: $0, withIntermediateDirectories: true) },
		remove: { try FileManager.default.removeItem(at: $0) }
	)
	#expect(throws: ProjectReplaceError.writeFailed(preview.files[1].url)) {
		try ProjectReplace.apply(preview, io: io)
	}
	#expect(try fixture.contents("one.txt") == "needle\n")
	#expect(try fixture.contents("two.txt") == "needle\n")
}

@Test func projectReplaceReportsAndRecoversFromJournalWhenRollbackFails() throws {
	let fixture = try TemporaryProjectFindFixture()
	defer { fixture.cleanUp() }
	try fixture.write("one.txt", "needle\n")
	try fixture.write("two.txt", "needle\n")
	let preview = ProjectReplace.preview(root: fixture.root, options: ProjectReplaceOptions(query: "needle", replacement: "pin"))
	var textWriteCount = 0
	let io = ProjectReplaceIO(
		read: { try Data(contentsOf: $0) },
		write: { data, url in
			if url.pathExtension == "txt" {
				textWriteCount += 1
				if textWriteCount >= 2 {
					throw CocoaError(.fileWriteUnknown)
				}
			}
			try data.write(to: url, options: .atomic)
		},
		createDirectory: { try FileManager.default.createDirectory(at: $0, withIntermediateDirectories: true) },
		remove: { try FileManager.default.removeItem(at: $0) }
	)
	let journalURL: URL
	do {
		_ = try ProjectReplace.apply(preview, io: io)
		Issue.record("expected replace failure")
		return
	} catch let error as ProjectReplaceError {
		guard case let .recoveryJournal(url) = error else {
			Issue.record("expected recovery journal, got \(error)")
			return
		}
		journalURL = url
	}
	#expect(FileManager.default.fileExists(atPath: journalURL.path))
	try ProjectReplace.recover(journalURL: journalURL)
	#expect(try fixture.contents("one.txt") == "needle\n")
	#expect(try fixture.contents("two.txt") == "needle\n")
	#expect(!FileManager.default.fileExists(atPath: journalURL.path))
}

private struct TemporaryProjectFindFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-project-find-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func write(_ relativePath: String, _ contents: String) throws {
		let url = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func contents(_ relativePath: String) throws -> String {
		try String(contentsOf: root.appendingPathComponent(relativePath), encoding: .utf8)
	}

	func cleanUp() {
		try? FileManager.default.removeItem(at: root)
	}
}
