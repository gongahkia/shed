import Foundation
@testable import ItsyEditor
import Testing

@Test func gitReviewModeCreatesDetachedWorktreeAndRestoresSourceBranch() throws {
	let fixture = try ReviewGitFixture()
	try fixture.preparePullRequest(number: 7)
	let manager = GitReviewModeManager(repository: GitRepository(root: fixture.source))
	let reviewURL = fixture.directory.appendingPathComponent("review", isDirectory: true)
	let result = try manager.start(pullRequestNumber: 7, reviewWorkspaceURL: reviewURL)
	guard case let .started(session) = result else {
		Issue.record("expected review session")
		return
	}
	#expect(session.sourceBranch == "main")
	#expect(try GitRepository(root: fixture.source).status().branch.head == "main")
	#expect(try String(contentsOf: fixture.source.appendingPathComponent("file.txt"), encoding: .utf8) == "main\n")
	#expect(try String(contentsOf: reviewURL.appendingPathComponent("file.txt"), encoding: .utf8) == "review\n")
	#expect(try GitRepository(root: fixture.source).worktrees().contains { $0.url.standardizedFileURL == reviewURL.standardizedFileURL })

	#expect(try manager.exit(session) == .readyToRestore(sourceWorkspaceURL: fixture.source.standardizedFileURL))
	#expect(!FileManager.default.fileExists(atPath: reviewURL.path))
	#expect(try GitRepository(root: fixture.source).status().branch.head == "main")
}

@Test func gitReviewModeRequiresExplicitDirtyAndMissingRemoteChoices() throws {
	let fixture = try ReviewGitFixture()
	try fixture.preparePullRequest(number: 7)
	try "dirty\n".write(to: fixture.source.appendingPathComponent("dirty.txt"), atomically: true, encoding: .utf8)
	let manager = GitReviewModeManager(repository: GitRepository(root: fixture.source))
	let reviewURL = fixture.directory.appendingPathComponent("review-dirty", isDirectory: true)
	#expect(try manager.start(pullRequestNumber: 7, reviewWorkspaceURL: reviewURL) == .requiresDirtyWorktreeChoice)
	#expect(try manager.start(pullRequestNumber: 7, reviewWorkspaceURL: reviewURL, dirtyWorktreeChoice: .cancel) == .cancelled)
	let started = try manager.start(pullRequestNumber: 7, reviewWorkspaceURL: reviewURL, dirtyWorktreeChoice: .keepChangesInSourceWorktree)
	guard case let .started(session) = started else {
		Issue.record("expected explicit dirty-source session")
		return
	}
	#expect(try GitRepository(root: fixture.source).status().hasChanges)
	_ = try manager.exit(session)

	let remoteFree = try ReviewGitFixture()
	try remoteFree.prepareLocalRepository()
	let remoteFreeManager = GitReviewModeManager(repository: GitRepository(root: remoteFree.source))
	let missingRemote = try remoteFreeManager.start(pullRequestNumber: 7, reviewWorkspaceURL: remoteFree.directory.appendingPathComponent("review", isDirectory: true))
	#expect(missingRemote == .requiresRemoteChoice(availableRemotes: []))
	#expect(try remoteFreeManager.start(pullRequestNumber: 7, reviewWorkspaceURL: remoteFree.directory.appendingPathComponent("review", isDirectory: true), remoteChoice: .cancel) == .cancelled)
}

@Test func gitReviewModeRequiresExplicitExitChoiceForReviewChanges() throws {
	let fixture = try ReviewGitFixture()
	try fixture.preparePullRequest(number: 7)
	let manager = GitReviewModeManager(repository: GitRepository(root: fixture.source))
	let reviewURL = fixture.directory.appendingPathComponent("review-exit", isDirectory: true)
	guard case let .started(session) = try manager.start(pullRequestNumber: 7, reviewWorkspaceURL: reviewURL) else {
		Issue.record("expected review session")
		return
	}
	try "local review edit\n".write(to: reviewURL.appendingPathComponent("local.txt"), atomically: true, encoding: .utf8)
	#expect(try manager.exit(session) == .requiresReviewWorktreeExitChoice(reviewWorkspaceURL: reviewURL.standardizedFileURL))
	guard case let .readyToRestoreWithRetainedReviewWorktree(source, retained, explanation) = try manager.exit(session, choice: .keepReviewWorktree) else {
		Issue.record("expected retained review worktree")
		return
	}
	#expect(source == fixture.source.standardizedFileURL)
	#expect(retained == reviewURL.standardizedFileURL)
	#expect(explanation.contains("retained"))
	#expect(FileManager.default.fileExists(atPath: reviewURL.path))
	#expect(try manager.exit(session, choice: .discardReviewChangesAndRemoveWorktree) == .readyToRestore(sourceWorkspaceURL: fixture.source.standardizedFileURL))
	#expect(!FileManager.default.fileExists(atPath: reviewURL.path))
}

private final class ReviewGitFixture {
	let directory: URL
	let source: URL
	let remote: URL
	private let runner = ProcessGitCommandRunner()

	init() throws {
		directory = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-review-\(UUID().uuidString)", isDirectory: true)
		source = directory.appendingPathComponent("source", isDirectory: true)
		remote = directory.appendingPathComponent("remote.git", isDirectory: true)
		try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: directory)
	}

	func prepareLocalRepository() throws {
		try git(["init"])
		try git(["checkout", "-b", "main"])
		try git(["config", "user.email", "itsy@example.invalid"])
		try git(["config", "user.name", "Itsy"])
		try "main\n".write(to: source.appendingPathComponent("file.txt"), atomically: true, encoding: .utf8)
		try git(["add", "file.txt"])
		try git(["commit", "-m", "main"])
	}

	func preparePullRequest(number: Int) throws {
		try prepareLocalRepository()
		try git(["checkout", "-b", "review-source"])
		try "review\n".write(to: source.appendingPathComponent("file.txt"), atomically: true, encoding: .utf8)
		try git(["commit", "-am", "review"])
		_ = try runner.runGit(arguments: ["init", "--bare", remote.path], root: directory)
		try git(["remote", "add", "origin", remote.path])
		try git(["push", "origin", "main"])
		try git(["push", "origin", "review-source:refs/pull/\(number)/head"])
		try git(["checkout", "main"])
	}

	private func git(_ arguments: [String]) throws {
		_ = try runner.runGit(arguments: arguments, root: source)
	}
}
