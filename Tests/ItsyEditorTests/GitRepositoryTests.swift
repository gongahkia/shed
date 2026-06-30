import Foundation
import ItsyEditor
import Testing

@Test func gitStatusParserReadsBranchAndPorcelainV2Entries() throws {
	let output = """
	# branch.oid abc123
	# branch.head main
	# branch.upstream origin/main
	# branch.ab +2 -1
	1 M. N... 100644 100644 100644 abc abc Sources/App.swift
	1 .M N... 100644 100644 100644 abc abc README.md
	2 R. N... 100644 100644 100644 abc def R100 Sources/New.swift\tSources/Old.swift
	u UU N... 100644 100644 100644 100644 aaa bbb ccc conflict.txt
	? scratch notes.txt
	"""

	let status = try GitStatusParser.parse(output)

	#expect(status.branch == GitBranchStatus(oid: "abc123", head: "main", upstream: "origin/main", ahead: 2, behind: 1))
	#expect(status.entries.count == 5)
	#expect(status.entries[0] == GitStatusEntry(kind: .ordinary, indexStatus: "M", worktreeStatus: ".", path: "Sources/App.swift"))
	#expect(status.entries[1] == GitStatusEntry(kind: .ordinary, indexStatus: ".", worktreeStatus: "M", path: "README.md"))
	#expect(status.entries[2] == GitStatusEntry(kind: .renamed, indexStatus: "R", worktreeStatus: ".", path: "Sources/New.swift", originalPath: "Sources/Old.swift"))
	#expect(status.entries[3] == GitStatusEntry(kind: .unmerged, indexStatus: "U", worktreeStatus: "U", path: "conflict.txt"))
	#expect(status.entries[4] == GitStatusEntry(kind: .untracked, indexStatus: "?", worktreeStatus: "?", path: "scratch notes.txt"))
	#expect(status.stagedCount == 3)
	#expect(status.unstagedCount == 3)
}

@Test func gitRepositoryRunsStatusDiffStageAndUnstage() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)

	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("tracked.txt", "one\n")
	try fixture.git(["add", "tracked.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.write("tracked.txt", "two\n")
	try fixture.write("new.txt", "new\n")

	let status = try repository.status()
	#expect(status.entries.contains(GitStatusEntry(kind: .ordinary, indexStatus: ".", worktreeStatus: "M", path: "tracked.txt")))
	#expect(status.entries.contains(GitStatusEntry(kind: .untracked, indexStatus: "?", worktreeStatus: "?", path: "new.txt")))
	let diff = try repository.diff(path: "tracked.txt")
	#expect(diff.contains("-one"))
	#expect(diff.contains("+two"))

	try repository.stage(paths: ["new.txt"])
	let staged = try repository.status()
	#expect(staged.entries.contains(GitStatusEntry(kind: .ordinary, indexStatus: "A", worktreeStatus: ".", path: "new.txt")))

	try repository.unstage(paths: ["new.txt"])
	let unstaged = try repository.status()
	#expect(unstaged.entries.contains(GitStatusEntry(kind: .untracked, indexStatus: "?", worktreeStatus: "?", path: "new.txt")))
}

@Test func gitWorkspaceSnapshotLooksUpEntriesByURL() {
	let root = URL(fileURLWithPath: "/tmp/project", isDirectory: true)
	let status = GitStatus(entries: [
		GitStatusEntry(kind: .ordinary, indexStatus: ".", worktreeStatus: "M", path: "Sources/App.swift"),
		GitStatusEntry(kind: .renamed, indexStatus: "R", worktreeStatus: ".", path: "Sources/New.swift", originalPath: "Sources/Old.swift"),
	])
	let snapshot = GitWorkspaceSnapshot(root: root, status: status)

	#expect(snapshot.relativePath(for: root.appendingPathComponent("Sources/App.swift")) == "Sources/App.swift")
	#expect(snapshot.entry(for: root.appendingPathComponent("Sources/App.swift"))?.worktreeStatus == "M")
	#expect(snapshot.entry(for: root.appendingPathComponent("Sources/Old.swift"))?.kind == .renamed)
	#expect(snapshot.entry(for: URL(fileURLWithPath: "/tmp/other/App.swift")) == nil)
}

@Test func gitRepositoryDiscoversRootFromNestedDirectory() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	try fixture.git(["init"])
	let nested = fixture.root.appendingPathComponent("Sources/Nested", isDirectory: true)
	try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)

	let root = try GitRepository.discoverRoot(containing: nested)

	#expect(root.standardizedFileURL.path == fixture.root.standardizedFileURL.path)
}

@Test func gitRepositoryCommitBuildsSeparateMessageArguments() throws {
	let runner = RecordingGitRunner()
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	try repository.commit(summary: " Add composer ", body: "\nBody line\n", signoff: true, amend: true)

	#expect(runner.recordedArguments == [[
		"commit",
		"--signoff",
		"--amend",
		"-m",
		"Add composer",
		"-m",
		"Body line",
	]])
}

@Test func gitRepositoryCommitRejectsEmptySummary() {
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: RecordingGitRunner())

	#expect(throws: GitCommitError.emptySummary) {
		try repository.commit(summary: "  ")
	}
}

@Test func gitRepositoryReadsRecentCommitMessagesAsNulSeparatedBodies() throws {
	let runner = RecordingGitRunner(output: "Summary one\n\nBody one\u{0}\nSummary two\u{0}")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	let messages = try repository.recentCommitMessages(limit: 10)

	#expect(messages == ["Summary one\n\nBody one", "Summary two"])
	#expect(runner.recordedArguments == [["log", "-10", "--format=%B%x00"]])
}

@Test func gitRepositoryDiffUsesNoColorAndCachedMode() throws {
	let runner = RecordingGitRunner(output: "")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	_ = try repository.diff(path: "Sources/App.swift")
	_ = try repository.diff(path: "Sources/App.swift", staged: true)

	#expect(runner.recordedArguments == [
		["diff", "--no-color", "--", "Sources/App.swift"],
		["diff", "--no-color", "--cached", "--", "Sources/App.swift"],
	])
}

@Test func gitBranchParserReadsNulSeparatedForEachRefRows() {
	let output = "main\torigin/main\t*\t2 hours ago\trefs/heads/main\u{0}\norigin/feature\t\t \t1 day ago\trefs/remotes/origin/feature\u{0}\norigin\t\t \t1 day ago\trefs/remotes/origin/HEAD\u{0}"

	let branches = GitBranchParser.parse(output)

	#expect(branches == [
		GitBranch(name: "main", upstream: "origin/main", isCurrent: true, committerDateRelative: "2 hours ago", refname: "refs/heads/main", kind: .local),
		GitBranch(name: "origin/feature", upstream: nil, isCurrent: false, committerDateRelative: "1 day ago", refname: "refs/remotes/origin/feature", kind: .remote),
	])
}

@Test func gitRepositoryRunsBranchListAndActions() throws {
	let runner = RecordingGitRunner(output: "main\torigin/main\t*\t2 hours ago\trefs/heads/main\u{0}")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	let branches = try repository.branches()
	try repository.switchBranch("feature")
	try repository.createBranch(named: "topic", from: "origin/topic")
	try repository.deleteBranch("topic")
	try repository.deleteBranch("topic", force: true)

	#expect(branches.first?.name == "main")
	#expect(runner.recordedArguments == [
		[
			"for-each-ref",
			"--format=%(refname:short)%09%(upstream:short)%09%(HEAD)%09%(committerdate:relative)%09%(refname)%00",
			"refs/heads",
			"refs/remotes",
		],
		["switch", "feature"],
		["switch", "-c", "topic", "origin/topic"],
		["branch", "-d", "topic"],
		["branch", "-D", "topic"],
	])
}

@Test func gitRepositoryStageAndUnstageHunkValidateBeforeApplyingPatch() throws {
	let runner = RecordingGitRunner(output: "")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)
	let hunk = DiffHunk(oldStart: 1, oldCount: 1, newStart: 1, newCount: 1, lines: [
		.remove("old"),
		.add("new"),
	])
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", indexLine: "index 1111111..2222222 100644", hunks: [hunk])
	let patch = DiffPatchBuilder.patch(file: file, hunk: hunk)

	try repository.stage(hunk: hunk, in: file)
	try repository.unstage(hunk: hunk, in: file)

	#expect(runner.recordedArguments == [
		["apply", "--cached", "--check", "-"],
		["apply", "--cached", "-"],
		["apply", "--cached", "--check", "--reverse", "-"],
		["apply", "--cached", "--reverse", "-"],
	])
	#expect(runner.recordedInputs == [patch, patch, patch, patch])
}

@Test func gitRepositoryStagesSingleWorkingTreeHunk() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	let initial = (1 ... 20).map { "line \($0)" }.joined(separator: "\n") + "\n"
	var modified = (1 ... 20).map { "line \($0)" }
	modified[1] = "line two"
	modified[14] = "line fifteen"

	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", initial)
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.write("file.txt", modified.joined(separator: "\n") + "\n")

	let files = try repository.diffFiles(path: "file.txt")
	let file = try #require(files.first)
	let hunk = try #require(file.hunks.first)

	try repository.stage(hunk: hunk, in: file)
	let staged = try repository.diff(path: "file.txt", staged: true)
	let unstaged = try repository.diff(path: "file.txt")

	#expect(staged.contains("+line two"))
	#expect(!staged.contains("+line fifteen"))
	#expect(unstaged.contains("+line fifteen"))
}

private final class TemporaryGitFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-git-\(UUID().uuidString)", isDirectory: true)
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

private final class RecordingGitRunner: GitCommandRunning, @unchecked Sendable {
	private let lock = NSLock()
	private var arguments: [[String]] = []
	private var inputs: [String?] = []
	private let output: String

	init(output: String = "") {
		self.output = output
	}

	var recordedArguments: [[String]] {
		lock.lock()
		let value = arguments
		lock.unlock()
		return value
	}

	var recordedInputs: [String] {
		lock.lock()
		let value = inputs.compactMap { $0 }
		lock.unlock()
		return value
	}

	func runGit(arguments: [String], root: URL) throws -> String {
		try runGit(arguments: arguments, input: nil, root: root)
	}

	func runGit(arguments: [String], input: String, root: URL) throws -> String {
		try runGit(arguments: arguments, input: Optional(input), root: root)
	}

	private func runGit(arguments: [String], input: String?, root: URL) throws -> String {
		lock.lock()
		self.arguments.append(arguments)
		self.inputs.append(input)
		lock.unlock()
		return output
	}
}
