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
	#expect(status.entries[0] == GitStatusEntry(
		kind: .ordinary,
		indexStatus: "M",
		worktreeStatus: ".",
		path: "Sources/App.swift"
	))
	#expect(status.entries[1] == GitStatusEntry(kind: .ordinary, indexStatus: ".", worktreeStatus: "M", path: "README.md"))
	#expect(status.entries[2] == GitStatusEntry(
		kind: .renamed,
		indexStatus: "R",
		worktreeStatus: ".",
		path: "Sources/New.swift",
		originalPath: "Sources/Old.swift"
	))
	#expect(status.entries[3] == GitStatusEntry(
		kind: .unmerged,
		indexStatus: "U",
		worktreeStatus: "U",
		path: "conflict.txt"
	))
	#expect(status.entries[4] == GitStatusEntry(
		kind: .untracked,
		indexStatus: "?",
		worktreeStatus: "?",
		path: "scratch notes.txt"
	))
	#expect(status.entries[3].isConflict)
	#expect(status.stagedCount == 3)
	#expect(status.unstagedCount == 3)
}

@Test func gitStatusParserCoversEveryPorcelainV2FileRecordKind() throws {
	let ordinaryStates = ["M.", ".M", "MM", "A.", "D.", "R.", "C."]
	let unmergedStates = ["DD", "AU", "UD", "UA", "DU", "AA", "UU"]
	let ordinaryRecords = ordinaryStates.enumerated().map { index, state in
		"1 \(state) N... 100644 100644 100644 old\(index) new\(index) ordinary-\(state)"
	}
	let renamedRecords = ["R.", "C."].enumerated().map { index, state in
		"2 \(state) N... 100644 100644 100644 old\(index) new\(index) R100 renamed-\(state)\toriginal-\(state)"
	}
	let unmergedRecords = unmergedStates.map { state in
		"u \(state) N... 100644 100644 100644 100644 ancestor ours theirs conflict-\(state)"
	}
	let status = try GitStatusParser.parse((ordinaryRecords + renamedRecords + unmergedRecords + [
		"? untracked.txt",
		"! ignored.txt",
	]).joined(separator: "\n"))

	#expect(status.entries.map(\.kind) ==
		Array(repeating: .ordinary, count: ordinaryStates.count) +
		Array(repeating: .renamed, count: renamedRecords.count) +
		Array(repeating: .unmerged, count: unmergedStates.count) + [.untracked, .ignored])
	#expect(status.entries.prefix(ordinaryStates.count).map { "\($0.indexStatus ?? " ")\($0.worktreeStatus ?? " ")" } == ordinaryStates)
	#expect(status.entries.dropFirst(ordinaryStates.count).prefix(renamedRecords.count).map { $0.originalPath } == ["original-R.", "original-C."])
	#expect(status.entries.dropFirst(ordinaryStates.count + renamedRecords.count).prefix(unmergedStates.count).map { "\($0.indexStatus ?? " ")\($0.worktreeStatus ?? " ")" } == unmergedStates)
	#expect(status.entries.suffix(2).map(\.kind) == [.untracked, .ignored])
}

@Test func gitRepositoryShellStatusRequestsIgnoredRecords() throws {
	let runner = RecordingGitRunner(output: "! ignored.txt\n")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	let status = try repository.status()

	#expect(status.entries == [GitStatusEntry(kind: .ignored, indexStatus: "!", worktreeStatus: "!", path: "ignored.txt")])
	#expect(runner.recordedArguments == [["status", "--porcelain=v2", "--branch", "--untracked-files=all", "--ignored=matching"]])
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
	#expect(status.entries.contains(GitStatusEntry(
		kind: .ordinary,
		indexStatus: ".",
		worktreeStatus: "M",
		path: "tracked.txt"
	)))
	#expect(status.entries.contains(GitStatusEntry(
		kind: .untracked,
		indexStatus: "?",
		worktreeStatus: "?",
		path: "new.txt"
	)))
	let diff = try repository.diff(path: "tracked.txt")
	#expect(diff.contains("-one"))
	#expect(diff.contains("+two"))

	try repository.stage(paths: ["new.txt"])
	let staged = try repository.status()
	#expect(staged.entries.contains(GitStatusEntry(
		kind: .ordinary,
		indexStatus: "A",
		worktreeStatus: ".",
		path: "new.txt"
	)))

	try repository.unstage(paths: ["new.txt"])
	let unstaged = try repository.status()
	#expect(unstaged.entries.contains(GitStatusEntry(
		kind: .untracked,
		indexStatus: "?",
		worktreeStatus: "?",
		path: "new.txt"
	)))
}

@Test func gitWorkspaceSnapshotLooksUpEntriesByURL() {
	let root = URL(fileURLWithPath: "/tmp/project", isDirectory: true)
	let status = GitStatus(entries: [
		GitStatusEntry(kind: .ordinary, indexStatus: ".", worktreeStatus: "M", path: "Sources/App.swift"),
		GitStatusEntry(
			kind: .renamed,
			indexStatus: "R",
			worktreeStatus: ".",
			path: "Sources/New.swift",
			originalPath: "Sources/Old.swift"
		),
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

@Test func gitRepositoryPrefersNestedRepositoryRoot() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	try fixture.git(["init"])
	let nestedRoot = fixture.root.appendingPathComponent("Vendor/Nested", isDirectory: true)
	try FileManager.default.createDirectory(at: nestedRoot, withIntermediateDirectories: true)
	_ = try ProcessGitCommandRunner().runGit(arguments: ["init"], root: nestedRoot)
	let nestedFile = nestedRoot.appendingPathComponent("Sources/App.swift")
	try FileManager.default.createDirectory(at: nestedFile.deletingLastPathComponent(), withIntermediateDirectories: true)
	try "let value = 1\n".write(to: nestedFile, atomically: true, encoding: .utf8)

	let root = try GitRepository.discoverRoot(containing: nestedFile)

	#expect(root.standardizedFileURL == nestedRoot.standardizedFileURL)
}

@Test func gitRepositoryDiscoversLinkedWorktreeRoot() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let worktree = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-git-worktree-\(UUID().uuidString)", isDirectory: true)
	defer { try? FileManager.default.removeItem(at: worktree) }
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("tracked.txt", "one\n")
	try fixture.git(["add", "tracked.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.git(["worktree", "add", "-b", "linked", worktree.path])
	let nested = worktree.appendingPathComponent("nested", isDirectory: true)
	try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)

	let root = try GitRepository.discoverRoot(containing: nested)

	#expect(root.standardizedFileURL == worktree.standardizedFileURL)
}

@Test func gitRepositoryRejectsBareRepositoriesAndReportsUnavailableGitRunner() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let bare = fixture.root.appendingPathComponent("bare.git", isDirectory: true)
	try FileManager.default.createDirectory(at: bare, withIntermediateDirectories: true)
	_ = try ProcessGitCommandRunner().runGit(arguments: ["init", "--bare"], root: bare)
	#expect(throws: GitRepositoryDiscoveryError.bareRepository(bare.standardizedFileURL)) {
		_ = try GitRepository.discoverRoot(containing: bare)
	}

	#expect(throws: GitCommandError.failed(status: 127, stderr: "git unavailable")) {
		_ = try GitRepository.discoverRoot(containing: fixture.root, runner: UnavailableGitRunner())
	}
}

@Test func gitRepositoryStatusIncludesIgnoredPaths() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	try fixture.git(["init"])
	try fixture.write(".gitignore", "ignored.txt\n")
	try fixture.write("ignored.txt", "ignored\n")

	let status = try GitRepository(root: fixture.root).status()

	#expect(status.entries.contains(GitStatusEntry(kind: .ignored, indexStatus: "!", worktreeStatus: "!", path: "ignored.txt")))
}

@Test func gitStatusRefreshCoordinatorRejectsStaleRapidRefresh() async throws {
	let coordinator = GitStatusRefreshCoordinator()
	let firstRoot = URL(fileURLWithPath: "/tmp/first")
	let secondRoot = URL(fileURLWithPath: "/tmp/second")
	let firstSnapshot = GitWorkspaceSnapshot(root: firstRoot, status: GitStatus())
	let secondSnapshot = GitWorkspaceSnapshot(root: secondRoot, status: GitStatus())
	let first = Task {
		await coordinator.refresh(root: firstRoot) { _ in
			Thread.sleep(forTimeInterval: 0.05)
			return firstSnapshot
		}
	}
	try await Task.sleep(nanoseconds: 5_000_000)
	let second = await coordinator.refresh(root: secondRoot) { _ in secondSnapshot }

	#expect(await first.value == nil)
	#expect(second == .snapshot(secondSnapshot))
}

@Test func gitRepositoryCommitBuildsSeparateMessageArguments() throws {
	let runner = RecordingGitRunner()
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	_ = try repository.commit(summary: " Add composer ", body: "\nBody line\n", signoff: true, amend: true)

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
	let repository = GitRepository(
		root: URL(fileURLWithPath: "/tmp/project", isDirectory: true),
		runner: RecordingGitRunner()
	)

	#expect(throws: GitCommitError.emptySummary) {
		try repository.commit(summary: "  ")
	}
}

@Test func gitRepositoryCommitReturnsStagedScopeAndRunsGit() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "content\n")
	try fixture.git(["add", "file.txt"])

	let result = try repository.commit(summary: "initial", body: "body")

	#expect(result.stagedPaths == ["file.txt"])
	#expect(!result.amended)
	#expect(try fixture.git(["log", "-1", "--format=%s"]).trimmingCharacters(in: .whitespacesAndNewlines) == "initial")
}

@Test func gitRepositoryCommitRejectsEmptyIndex() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])

	#expect(throws: GitCommitError.emptyIndex) {
		try repository.commit(summary: "empty")
	}
}

@Test func gitRepositoryCommitRejectsAmendWithoutHead() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "content\n")
	try fixture.git(["add", "file.txt"])

	#expect(throws: GitCommitError.amendWithoutCommit) {
		try repository.commit(summary: "amend", amend: true)
	}
}

@Test func gitRepositoryCommitPreservesRejectedHookOutput() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "content\n")
	try fixture.git(["add", "file.txt"])
	let hook = fixture.root.appendingPathComponent(".git/hooks/pre-commit")
	try "#!/bin/sh\necho rejected-by-hook >&2\nexit 17\n".write(to: hook, atomically: true, encoding: .utf8)
	try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: hook.path)

	do {
		_ = try repository.commit(summary: "blocked")
		#expect(Bool(false))
	} catch let error as GitCommandError {
		switch error {
		case let .failed(status, stderr):
			#expect(status != 0)
			#expect(stderr.contains("rejected-by-hook"))
		default:
			#expect(Bool(false))
		}
	} catch {
		#expect(Bool(false))
	}
}

@Test func gitRepositoryCommitAmendsHeadAndReportsAmendState() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "first\n")
	try fixture.git(["add", "file.txt"])
	_ = try repository.commit(summary: "initial")
	try fixture.write("file.txt", "amended\n")
	try fixture.git(["add", "file.txt"])

	let result = try repository.commit(summary: "amended", amend: true)

	#expect(result.amended)
	#expect(result.stagedPaths == ["file.txt"])
	#expect(try fixture.git(["log", "-1", "--format=%s"]).trimmingCharacters(in: .whitespacesAndNewlines) == "amended")
}

@Test func gitRepositoryCommitReportsAuthorFailures() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.name", ""])
	try fixture.git(["config", "user.email", ""])
	try fixture.write("file.txt", "content\n")
	try fixture.git(["add", "file.txt"])

	#expect(throws: GitCommandError.self) {
		try repository.commit(summary: "author failure")
	}
}

@Test func gitRepositoryReadsRecentCommitMessagesAsNulSeparatedBodies() throws {
	let runner = RecordingGitRunner(output: "Summary one\n\nBody one\u{0}\nSummary two\u{0}")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	let messages = try repository.recentCommitMessages(limit: 10)

	#expect(messages == ["Summary one\n\nBody one", "Summary two"])
	#expect(runner.recordedArguments == [["log", "-10", "--format=%B%x00"]])
}

@Test func gitBlameParserReadsLinePorcelainRecords() {
	let output = """
	aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 1 1 1
	author Ada
	author-mail <ada@example.invalid>
	author-time 1700000000
	summary initial
	filename file.txt
	\tone
	bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 2 2 1
	author Bob
	author-mail <bob@example.invalid>
	author-time 1700000100
	summary change two
	filename file.txt
	\ttwo
	"""

	let lines = GitBlameParser.parse(output)

	#expect(lines == [
		GitBlameLine(
			line: 1,
			originalLine: 1,
			oid: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			summary: "initial",
			author: "Ada",
			authorEmail: "ada@example.invalid",
			time: Date(timeIntervalSince1970: 1_700_000_000),
			originalPath: "file.txt"
		),
		GitBlameLine(
			line: 2,
			originalLine: 2,
			oid: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			summary: "change two",
			author: "Bob",
			authorEmail: "bob@example.invalid",
			time: Date(timeIntervalSince1970: 1_700_000_100),
			originalPath: "file.txt"
		),
	])
}

@Test func gitHistoryParserReadsNulSeparatedRecords() {
	let output = "abc\u{1f}Ada\u{1f}ada@example.invalid\u{1f}1700000000\u{1f}initial\u{0}\ndef\u{1f}Bob\u{1f}bob@example.invalid\u{1f}1700000100\u{1f}change\u{0}"

	let entries = GitHistoryParser.parse(output)

	#expect(entries == [
		GitHistoryEntry(
			oid: "abc",
			author: "Ada",
			authorEmail: "ada@example.invalid",
			date: Date(timeIntervalSince1970: 1_700_000_000),
			summary: "initial"
		),
		GitHistoryEntry(
			oid: "def",
			author: "Bob",
			authorEmail: "bob@example.invalid",
			date: Date(timeIntervalSince1970: 1_700_000_100),
			summary: "change"
		),
	])
}

@Test func gitGraphParserReadsParentsAndReferences() {
	let output = "merge\u{1f}main feature\u{1f}HEAD -> main, origin/main\u{1f}Ada\u{1f}ada@example.invalid\u{1f}1700000000\u{1f}merge feature\u{0}"

	let entries = GitGraphParser.parse(output)

	#expect(entries == [GitGraphEntry(
		history: GitHistoryEntry(
			oid: "merge",
			author: "Ada",
			authorEmail: "ada@example.invalid",
			date: Date(timeIntervalSince1970: 1_700_000_000),
			summary: "merge feature"
		),
		parentOIDs: ["main", "feature"],
		references: ["HEAD -> main", "origin/main"]
	)])
}

@Test func gitRepositoryRunsBlameAndHistoryCommandsWithInjectedRunner() throws {
	let runner = RecordingGitRunner(output: "")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	_ = try repository.blame(path: "file.txt")
	_ = try repository.fileHistory(path: "file.txt", limit: 2)
	_ = try repository.lineHistory(path: "file.txt", line: 3, limit: 4)

	#expect(runner.recordedArguments == [
		["blame", "--line-porcelain", "--", "file.txt"],
		["log", "-2", "--skip=0", "--follow", "--find-renames", "--format=%H%x1f%an%x1f%ae%x1f%at%x1f%s%x00", "--", "file.txt"],
		["log", "-4", "--format=%H%x1f%an%x1f%ae%x1f%at%x1f%s%x00", "--no-patch", "-L", "3,3:file.txt"],
	])
}

@Test func gitHistoryPagerRejectsCanceledPage() async throws {
	let pager = GitHistoryPager()
	let task = Task {
		try await pager.loadNext(limit: 1) { _, _ in
			Thread.sleep(forTimeInterval: 0.05)
			return [GitGraphEntry(history: GitHistoryEntry(oid: "one", author: "Ada", authorEmail: "ada@example.invalid", summary: "one"))]
		}
	}
	try await Task.sleep(nanoseconds: 5_000_000)
	await pager.cancel()

	#expect(try await task.value == nil)
}

@Test func gitRepositoryHistorySupportsMergesDetachedHeadRemotesAndRenameFollowing() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["checkout", "-b", "main"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("old.txt", "base\n")
	try fixture.git(["add", "old.txt"])
	try fixture.git(["commit", "-m", "base"])
	try fixture.git(["checkout", "-b", "feature"])
	try fixture.write("feature.txt", "feature\n")
	try fixture.git(["add", "feature.txt"])
	try fixture.git(["commit", "-m", "feature"])
	try fixture.git(["checkout", "main"])
	try fixture.git(["mv", "old.txt", "renamed.txt"])
	try fixture.git(["commit", "-m", "rename file"])
	try fixture.write("main.txt", "main\n")
	try fixture.git(["add", "main.txt"])
	try fixture.git(["commit", "-m", "main"])
	try fixture.git(["merge", "--no-ff", "feature", "-m", "merge feature"])
	try fixture.git(["remote", "add", "origin", "https://example.invalid/origin.git"])
	try fixture.git(["remote", "add", "upstream", "https://example.invalid/upstream.git"])
	try fixture.git(["update-ref", "refs/remotes/origin/main", "HEAD"])
	try fixture.git(["update-ref", "refs/remotes/upstream/main", "HEAD"])

	let firstPage = try repository.historyPage(limit: 2)
	let secondPage = try repository.historyPage(limit: 2, offset: 2)
	let renameTimeline = try repository.fileHistory(path: "renamed.txt", limit: 10)
	let branches = try repository.branches()
	try fixture.git(["checkout", "HEAD~1"])
	let detachedStatus = try repository.status()

	#expect(firstPage.entries.count == 2)
	#expect(firstPage.hasMore)
	#expect(secondPage.offset == 2)
	#expect((firstPage.entries + secondPage.entries).contains { $0.history.summary == "merge feature" && $0.parentOIDs.count == 2 })
	#expect(renameTimeline.map(\.summary).contains("rename file"))
	#expect(renameTimeline.map(\.summary).contains("base"))
	#expect(branches.contains { $0.name == "origin/main" && $0.kind == .remote })
	#expect(branches.contains { $0.name == "upstream/main" && $0.kind == .remote })
	#expect(detachedStatus.branch.head == nil)
}

@Test func gitBlameCacheReusesRepositoryResultsUntilInvalidated() throws {
	let output = """
	aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 1 1 1
	author Ada
	author-mail <ada@example.invalid>
	author-time 1700000000
	summary initial
	filename file.txt
	\tone
	"""
	let runner = RecordingGitRunner(output: output)
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)
	var cache = GitBlameCache()

	let first = try cache.blame(path: "file.txt", repository: repository)
	let second = try cache.blame(path: "file.txt", repository: repository)
	cache.invalidate()
	let third = try cache.blame(path: "file.txt", repository: repository)

	#expect(first == second)
	#expect(third == first)
	#expect(runner.recordedArguments == [
		["blame", "--line-porcelain", "--", "file.txt"],
		["blame", "--line-porcelain", "--", "file.txt"],
	])
}

@Test func gitRepositoryDiffUsesNoColorAndCachedMode() throws {
	let runner = RecordingGitRunner(output: "")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	_ = try repository.diff(path: "Sources/App.swift")
	_ = try repository.diff(path: "Sources/App.swift", staged: true)
	_ = try repository.diffAgainstHead(path: "Sources/App.swift")
	_ = try repository.conflictBlob(path: "Sources/App.swift", stage: 2)

	#expect(runner.recordedArguments == [
		["diff", "--no-color", "--", "Sources/App.swift"],
		["diff", "--no-color", "--cached", "--", "Sources/App.swift"],
		["diff", "--no-color", "HEAD", "--", "Sources/App.swift"],
		["show", ":2:Sources/App.swift"],
	])
}

@Test func gitBranchParserReadsNulSeparatedForEachRefRows() {
	let output = "main\torigin/main\t*\t2 hours ago\trefs/heads/main\u{0}\norigin/feature\t\t \t1 day ago\trefs/remotes/origin/feature\u{0}\norigin\t\t \t1 day ago\trefs/remotes/origin/HEAD\u{0}"

	let branches = GitBranchParser.parse(output)

	#expect(branches == [
		GitBranch(
			name: "main",
			upstream: "origin/main",
			isCurrent: true,
			committerDateRelative: "2 hours ago",
			refname: "refs/heads/main",
			kind: .local
		),
		GitBranch(
			name: "origin/feature",
			upstream: nil,
			isCurrent: false,
			committerDateRelative: "1 day ago",
			refname: "refs/remotes/origin/feature",
			kind: .remote
		),
	])
}

@Test func gitWorktreeParserReadsLinkedAndBareWorktrees() {
	let output = """
	worktree /tmp/main
	HEAD abc
	branch refs/heads/main

	worktree /tmp/feature
	HEAD def
	branch refs/heads/feature

	worktree /tmp/bare
	bare

	"""

	let worktrees = GitWorktreeParser.parse(output)

	#expect(worktrees == [
		GitWorktree(url: URL(fileURLWithPath: "/tmp/main", isDirectory: true).resolvingSymlinksInPath(), headOID: "abc", branch: "main"),
		GitWorktree(url: URL(fileURLWithPath: "/tmp/feature", isDirectory: true).resolvingSymlinksInPath(), headOID: "def", branch: "feature"),
		GitWorktree(url: URL(fileURLWithPath: "/tmp/bare", isDirectory: true).resolvingSymlinksInPath(), isBare: true),
	])
}

@Test func gitStashParserReadsFormattedRows() {
	let output = """
	stash@{0}|2026-06-30 10:11:12 +0800|WIP on main: one
	stash@{1}|2026-06-29 09:00:00 +0800|message with | pipe
	invalid
	"""

	let entries = GitStashParser.parse(output)

	#expect(entries == [
		GitStashEntry(ref: "stash@{0}", date: "2026-06-30 10:11:12 +0800", message: "WIP on main: one"),
		GitStashEntry(ref: "stash@{1}", date: "2026-06-29 09:00:00 +0800", message: "message with | pipe"),
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

@Test func gitRepositoryRunsStashListAndActions() throws {
	let runner = RecordingGitRunner(output: "stash@{0}|2026-06-30 10:11:12 +0800|work\n")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	let entries = try repository.stashes()
	try repository.stash(message: " work in progress ")
	try repository.applyStash(" stash@{0} ")
	try repository.popStash("stash@{0}")
	try repository.dropStash("stash@{0}")
	let diff = try repository.stashDiff("stash@{0}")

	#expect(entries == [
		GitStashEntry(ref: "stash@{0}", date: "2026-06-30 10:11:12 +0800", message: "work"),
	])
	#expect(diff == "stash@{0}|2026-06-30 10:11:12 +0800|work\n")
	#expect(runner.recordedArguments == [
		["stash", "list", "--format=%gd|%ai|%s"],
		["stash", "push", "-u", "-m", "work in progress"],
		["stash", "apply", "stash@{0}"],
		["stash", "pop", "stash@{0}"],
		["stash", "drop", "stash@{0}"],
		["stash", "show", "--patch", "stash@{0}"],
	])
}

@Test func gitRepositoryRejectsEmptyStashInputs() {
	let repository = GitRepository(
		root: URL(fileURLWithPath: "/tmp/project", isDirectory: true),
		runner: RecordingGitRunner()
	)

	#expect(throws: GitStashError.emptyMessage) {
		try repository.stash(message: "  ")
	}
	#expect(throws: GitStashError.emptyRef) {
		try repository.applyStash("  ")
	}
}

@Test func gitRepositoryStashesAroundBranchSwitchAndCreateWhenRequested() throws {
	let runner = RecordingGitRunner(output: "")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)

	try repository.switchBranch("feature", stashingDirtyChanges: true)
	try repository.createBranch(named: "topic", from: "origin/topic", stashingDirtyChanges: true)

	#expect(runner.recordedArguments == [
		["stash", "push", "-u", "-m", "itsy-autostash-feature"],
		["switch", "feature"],
		["stash", "push", "-u", "-m", "itsy-autostash-topic"],
		["switch", "-c", "topic", "origin/topic"],
	])
}

@Test func gitRepositoryStashesAndRestoresDirtyChangesOnBranchSwitch() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["checkout", "-b", "main"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "base\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "base"])
	try fixture.git(["branch", "feature"])
	try fixture.write("file.txt", "dirty\n")

	try repository.switchBranch("feature", stashingDirtyChanges: true)

	#expect(try repository.status().branch.head == "feature")
	#expect(try String(contentsOf: fixture.root.appendingPathComponent("file.txt"), encoding: .utf8) == "dirty\n")
	#expect(try repository.stashes().isEmpty)
}

@Test func gitRepositoryRestoresDirtyChangesWhenCheckoutFailsAfterStashing() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["checkout", "-b", "main"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "base\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "base"])
	try fixture.write("file.txt", "dirty\n")

	#expect(throws: GitCommandError.self) {
		try repository.switchBranch("missing-branch", stashingDirtyChanges: true)
	}
	#expect(try repository.status().branch.head == "main")
	#expect(try String(contentsOf: fixture.root.appendingPathComponent("file.txt"), encoding: .utf8) == "dirty\n")
	#expect(try repository.stashes().isEmpty)
}

@Test func gitRepositoryRejectsBranchCheckedOutInAnotherWorktree() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	let linked = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-linked-worktree-\(UUID().uuidString)", isDirectory: true)
	defer {
		_ = try? fixture.git(["worktree", "remove", "--force", linked.path])
		try? FileManager.default.removeItem(at: linked)
	}
	try fixture.git(["init"])
	try fixture.git(["checkout", "-b", "main"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "base\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "base"])
	try fixture.git(["branch", "feature"])
	try fixture.git(["worktree", "add", linked.path, "feature"])

	#expect(throws: GitBranchError.checkedOutInWorktree(linked.resolvingSymlinksInPath().standardizedFileURL)) {
		try repository.switchBranch("feature")
	}
}

@Test func gitRepositoryCreatesAppliesAndDropsStash() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["checkout", "-b", "main"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "base\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "base"])
	try fixture.write("file.txt", "dirty\n")

	try repository.stash(message: "work")
	#expect(try repository.stashes().count == 1)
	try repository.applyStash("stash@{0}")
	#expect(try String(contentsOf: fixture.root.appendingPathComponent("file.txt"), encoding: .utf8) == "dirty\n")
	try repository.dropStash("stash@{0}")
	#expect(try repository.stashes().isEmpty)
}

@Test func gitRepositoryBuildsRemoteOperationArguments() throws {
	let newBranchRunner = RecordingGitRunner(output: "# branch.head feature\n# branch.oid abc123\n")
	let trackingRunner =
		RecordingGitRunner(output: "# branch.head main\n# branch.upstream origin/main\n# branch.oid abc123\n")
	let newBranchRepository = GitRepository(
		root: URL(fileURLWithPath: "/tmp/project", isDirectory: true),
		runner: newBranchRunner
	)
	let trackingRepository = GitRepository(
		root: URL(fileURLWithPath: "/tmp/project", isDirectory: true),
		runner: trackingRunner
	)

	#expect(newBranchRepository.fetchArguments() == ["fetch", "--all", "--prune"])
	#expect(newBranchRepository.pullArguments() == ["pull", "--ff-only"])
	#expect(newBranchRepository.pullArguments(mode: .rebase) == ["pull", "--rebase"])
	#expect(try newBranchRepository.pushArguments() == ["push", "--set-upstream", "origin", "feature"])
	#expect(try trackingRepository.pushArguments() == ["push"])
}

@Test func gitRepositoryStageAndUnstageHunksUseSingleCachedPatchTransactions() throws {
	let runner = RecordingGitRunner(output: "")
	let repository = GitRepository(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)
	let hunk = DiffHunk(oldStart: 1, oldCount: 1, newStart: 1, newCount: 1, lines: [
		.remove("old"),
		.add("new"),
	])
	let file = DiffFile(
		oldPath: "file.txt",
		newPath: "file.txt",
		indexLine: "index 1111111..2222222 100644",
		hunks: [hunk]
	)
	let patch = DiffPatchBuilder.patch(file: file, hunk: hunk)
	let linePatch = try DiffPatchBuilder.patch(
		file: file,
		hunk: hunk,
		selectedLineIndexes: IndexSet(integersIn: 0 ..< 2),
		operation: .stage
	)
	let reverseLinePatch = try DiffPatchBuilder.patch(
		file: file,
		hunk: hunk,
		selectedLineIndexes: IndexSet(integersIn: 0 ..< 2),
		operation: .unstage
	)

	try repository.stage(hunk: hunk, in: file)
	try repository.unstage(hunk: hunk, in: file)
	try repository.stage(lineIndexes: IndexSet(integersIn: 0 ..< 2), in: hunk, file: file)
	try repository.unstage(lineIndexes: IndexSet(integersIn: 0 ..< 2), in: hunk, file: file)

	#expect(runner.recordedArguments == [
		["apply", "--cached", "-"],
		["apply", "--cached", "--reverse", "-"],
		["apply", "--cached", "-"],
		["apply", "--cached", "--reverse", "-"],
	])
	#expect(runner.recordedInputs == [
		patch,
		patch,
		linePatch,
		reverseLinePatch,
	])
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

@Test func gitRepositoryStagesSelectedWorkingTreeLines() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "one\ntwo\nthree\nfour\nfive\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.write("file.txt", "one\ntwo changed\nthree\nfour changed\nfive\n")

	let file = try #require(try repository.diffFiles(path: "file.txt").first)
	let hunk = try #require(file.hunks.first)
	let selected = lineIndexes(in: hunk, containing: ["two", "two changed"])

	try repository.stage(lineIndexes: selected, in: hunk, file: file)
	let staged = try repository.diff(path: "file.txt", staged: true)
	let unstaged = try repository.diff(path: "file.txt")

	#expect(staged.contains("+two changed"))
	#expect(!staged.contains("+four changed"))
	#expect(unstaged.contains("+four changed"))
	#expect(!unstaged.contains("+two changed"))
}

@Test func gitRepositoryUnstagesSelectedIndexLines() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "one\ntwo\nthree\nfour\nfive\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.write("file.txt", "one\ntwo changed\nthree\nfour changed\nfive\n")
	try repository.stage(paths: ["file.txt"])

	let file = try #require(try repository.diffFiles(path: "file.txt", staged: true).first)
	let hunk = try #require(file.hunks.first)
	let selected = lineIndexes(in: hunk, containing: ["two", "two changed"])

	try repository.unstage(lineIndexes: selected, in: hunk, file: file)
	let staged = try repository.diff(path: "file.txt", staged: true)
	let unstaged = try repository.diff(path: "file.txt")

	#expect(!staged.contains("+two changed"))
	#expect(staged.contains("+four changed"))
	#expect(unstaged.contains("+two changed"))
	#expect(!unstaged.contains("+four changed"))
}

@Test func gitRepositoryStagesNoNewlinePatchWithoutAddingANewline() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "old")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.write("file.txt", "new")

	let file = try #require(try repository.diffFiles(path: "file.txt").first)
	let hunk = try #require(file.hunks.first)
	try repository.stage(hunk: hunk, in: file)

	#expect(hunk.noNewlineLineIndexes == [0, 1])
	#expect(try fixture.git(["show", ":file.txt"]) == "new")
}

@Test func gitRepositoryRejectsStaleWorktreeBeforeStaging() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "one\ntwo\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.write("file.txt", "one\ntwo selected\n")
	let file = try #require(try repository.diffFiles(path: "file.txt").first)
	let hunk = try #require(file.hunks.first)
	try fixture.write("file.txt", "one\ntwo changed after selection\n")

	#expect(throws: GitPatchApplicationError.staleWorktree) {
		try repository.stage(hunk: hunk, in: file)
	}
	#expect(try repository.diff(path: "file.txt", staged: true).isEmpty)
}

@Test func gitRepositoryRejectsStaleIndexBeforeUnstaging() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "one\ntwo\nthree\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.write("file.txt", "one\ntwo selected\nthree\n")
	try repository.stage(paths: ["file.txt"])
	let file = try #require(try repository.diffFiles(path: "file.txt", staged: true).first)
	let hunk = try #require(file.hunks.first)
	try fixture.write("file.txt", "one\ntwo selected\nthree changed after selection\n")
	try repository.stage(paths: ["file.txt"])
	let before = try repository.diff(path: "file.txt", staged: true)

	#expect(throws: GitPatchApplicationError.staleIndex) {
		try repository.unstage(hunk: hunk, in: file)
	}
	#expect(try repository.diff(path: "file.txt", staged: true) == before)
}

@Test func gitRepositoryPreservesIndexWhenSelectedStageOrUnstageLinesAreStale() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "one\ntwo\nthree\nfour\nfive\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])

	try fixture.write("file.txt", "one\ntwo selected\nthree\nfour selected\nfive\n")
	let workingFile = try #require(try repository.diffFiles(path: "file.txt").first)
	let workingHunk = try #require(workingFile.hunks.first)
	let selected = lineIndexes(in: workingHunk, containing: ["two", "two selected"])
	try fixture.write("file.txt", "one\ntwo changed after selection\nthree\nfour selected\nfive\n")

	#expect(throws: GitPatchApplicationError.staleWorktree) {
		try repository.stage(lineIndexes: selected, in: workingHunk, file: workingFile)
	}
	#expect(try repository.diff(path: "file.txt", staged: true).isEmpty)

	try fixture.write("file.txt", "one\ntwo selected\nthree\nfour selected\nfive\n")
	try repository.stage(paths: ["file.txt"])
	let indexedFile = try #require(try repository.diffFiles(path: "file.txt", staged: true).first)
	let indexedHunk = try #require(indexedFile.hunks.first)
	let indexedSelection = lineIndexes(in: indexedHunk, containing: ["four", "four selected"])
	try fixture.write("file.txt", "one\ntwo selected\nthree\nfour changed after selection\nfive\n")
	try repository.stage(paths: ["file.txt"])
	let before = try repository.diff(path: "file.txt", staged: true)

	#expect(throws: GitPatchApplicationError.staleIndex) {
		try repository.unstage(lineIndexes: indexedSelection, in: indexedHunk, file: indexedFile)
	}
	#expect(try repository.diff(path: "file.txt", staged: true) == before)
}

@Test func gitRepositoryReadsConflictStageBlobsFromRealRepo() throws {
	guard FileManager.default.isExecutableFile(atPath: "/usr/bin/git") else {
		return
	}
	let fixture = try TemporaryGitFixture()
	let repository = GitRepository(root: fixture.root)
	try fixture.git(["init"])
	try fixture.git(["checkout", "-b", "main"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy"])
	try fixture.write("file.txt", "base\n")
	try fixture.git(["add", "file.txt"])
	try fixture.git(["commit", "-m", "initial"])
	try fixture.git(["checkout", "-b", "feature"])
	try fixture.write("file.txt", "theirs\n")
	try fixture.git(["commit", "-am", "feature"])
	try fixture.git(["checkout", "main"])
	try fixture.write("file.txt", "ours\n")
	try fixture.git(["commit", "-am", "main"])
	_ = try? fixture.git(["merge", "feature"])

	let status = try repository.status()
	let entry = try #require(status.entries.first)
	let conflicts = try repository.conflicts()
	let merged = try String(contentsOf: fixture.root.appendingPathComponent("file.txt"), encoding: .utf8)

	#expect(entry.isConflict)
	#expect(conflicts == [
		GitConflictEntry(path: "file.txt", ancestorPath: "file.txt", oursPath: "file.txt", theirsPath: "file.txt"),
	])
	#expect(try repository.conflictBlob(path: "file.txt", stage: 1) == "base\n")
	#expect(try repository.conflictBlob(path: "file.txt", stage: 2) == "ours\n")
	#expect(try repository.conflictBlob(path: "file.txt", stage: 3) == "theirs\n")
	#expect(GitConflictParser.parse(merged).count == 1)
	#expect(try repository.conflictResolutionState() == GitConflictResolutionState(unresolvedPaths: ["file.txt"]))

	try fixture.write("file.txt", "manual edit\n")
	try repository.restoreConflictMarkers(path: "file.txt")
	let restored = try String(contentsOf: fixture.root.appendingPathComponent("file.txt"), encoding: .utf8)
	var resolutionDocument = GitConflictResolutionDocument(text: restored)
	resolutionDocument.resolve(regionIndex: 0, with: .both)
	let resolved = try resolutionDocument.textForStaging()
	try fixture.write("file.txt", resolved)
	try repository.stage(paths: ["file.txt"])

	#expect(restored.contains("<<<<<<<"))
	#expect(resolved == "ours\ntheirs\n")
	#expect(try repository.conflictResolutionState().isComplete)
}

private func lineIndexes(in hunk: DiffHunk, containing values: Set<String>) -> IndexSet {
	var indexes = IndexSet()
	for (index, line) in hunk.lines.enumerated() {
		switch line {
		case .context:
			continue
		case let .add(content), let .remove(content):
			if values.contains(content) {
				indexes.insert(index)
			}
		}
	}
	return indexes
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

	private func runGit(arguments: [String], input: String?, root _: URL) throws -> String {
		lock.lock()
		self.arguments.append(arguments)
		inputs.append(input)
		lock.unlock()
		return output
	}
}

private struct UnavailableGitRunner: GitCommandRunning {
	func runGit(arguments: [String], root: URL) throws -> String {
		throw GitCommandError.failed(status: 127, stderr: "git unavailable")
	}

	func runGit(arguments: [String], input: String, root: URL) throws -> String {
		throw GitCommandError.failed(status: 127, stderr: "git unavailable")
	}
}
