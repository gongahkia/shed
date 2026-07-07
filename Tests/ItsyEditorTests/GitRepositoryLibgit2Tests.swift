import Foundation
import ItsyEditor
import Testing

@Test func libgit2RepositoryFacadeOpensStatusDiffAndBlob() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try Libgit2TemporaryGitFixture()
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("tracked.txt", "one\n")
	try fixture.git(["add", "tracked.txt"])
	try fixture.git(["commit", "-m", "initial"])
	let oid = try fixture.git(["rev-parse", "HEAD:tracked.txt"]).trimmingCharacters(in: .whitespacesAndNewlines)
	try fixture.write("tracked.txt", "two\n")
	try fixture.write("other.txt", "other\n")

	let repository = try GitRepository.Libgit2.Repository.open(at: fixture.root)
	let status = try repository.status()
	let trackedStatus = try repository.status(pathspec: ["tracked.txt"])
	let worktreeDiff = try repository.diff(cached: false)
	let trackedDiff = try repository.diff(cached: false, pathspec: ["tracked.txt"])
	let cachedDiff = try repository.diff(cached: true)
	let blob = try repository.blob(at: oid)
	let worktreePatch = try worktreeDiff.patchText()
	let trackedPatch = try trackedDiff.patchText()
	let cachedPatch = try cachedDiff.patchText()

	#expect(status.count == 2)
	#expect(trackedStatus.count == 1)
	#expect(worktreeDiff.count == 1)
	#expect(trackedDiff.count == 1)
	#expect(cachedDiff.count == 0)
	#expect(String(data: blob.data, encoding: .utf8) == "one\n")
	#expect(worktreePatch.contains("diff --git a/tracked.txt b/tracked.txt"))
	#expect(worktreePatch.contains("-one"))
	#expect(worktreePatch.contains("+two"))
	#expect(trackedPatch == worktreePatch)
	#expect(cachedPatch.isEmpty)
}

@Test func gitRepositoryStatusMatchesPorcelainV2OnFixtureRepo() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try Libgit2TemporaryGitFixture()
	let remote = FileManager.default.temporaryDirectory
		.appendingPathComponent("itsy-libgit2-remote-\(UUID().uuidString)", isDirectory: true)
	try FileManager.default.createDirectory(at: remote, withIntermediateDirectories: true)
	defer {
		try? FileManager.default.removeItem(at: remote)
	}
	_ = try ProcessGitCommandRunner().runGit(arguments: ["init", "--bare"], root: remote)
	try fixture.git(["init"])
	try fixture.git(["checkout", "-b", "main"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("tracked.txt", "one\n")
	try fixture.write("old-name.txt", "rename me\n")
	try fixture.git(["add", "tracked.txt", "old-name.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.git(["remote", "add", "origin", remote.path])
	try fixture.git(["push", "-u", "origin", "main"])
	try fixture.write("ahead.txt", "ahead\n")
	try fixture.git(["add", "ahead.txt"])
	try fixture.git(["commit", "-m", "ahead"])
	try fixture.write("tracked.txt", "two\n")
	try fixture.write("staged.txt", "staged\n")
	try fixture.git(["add", "staged.txt"])
	try fixture.git(["mv", "old-name.txt", "new-name.txt"])
	try fixture.write("untracked.txt", "untracked\n")

	let shellOutput = try fixture.git(["status", "--porcelain=v2", "--branch", "--untracked-files=all"])
	let shellStatus = try GitStatusParser.parse(shellOutput)
	let libgit2Status = try GitRepository(root: fixture.root).status()

	#expect(libgit2Status == shellStatus)
}

@Test func libgit2BlameAndCommitFromStageWorkOnFixtureRepo() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try Libgit2TemporaryGitFixture()
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "one\ntwo\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.write("file.txt", "one changed\ntwo\n")
	try fixture.git(["commit", "-am", "change one"])
	try fixture.write("committed.txt", "from libgit2\n")
	try fixture.git(["add", "committed.txt"])

	try GitRepository(root: fixture.root).commit(summary: "libgit2 commit")
	let blame = try GitRepository(root: fixture.root).blame(path: "file.txt")
	let subject = try fixture.git(["log", "-1", "--format=%s"]).trimmingCharacters(in: .whitespacesAndNewlines)

	#expect(subject == "libgit2 commit")
	#expect(blame.count == 2)
	#expect(blame[0].summary == "change one")
	#expect(blame[0].author == "Itsy")
	#expect(blame[1].summary == "initial")
}

private final class Libgit2TemporaryGitFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-libgit2-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func write(_ path: String, _ contents: String) throws {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	@discardableResult
	func git(_ arguments: [String]) throws -> String {
		try ProcessGitCommandRunner().runGit(arguments: arguments, root: root)
	}
}
