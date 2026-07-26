import Foundation

public struct GitRepositoryDomain: Sendable {
	public typealias RepositoryFactory = @Sendable (URL) -> GitRepository

	private let repositoryFactory: RepositoryFactory

	public init(repositoryFactory: @escaping RepositoryFactory = { GitRepository(root: $0) }) {
		self.repositoryFactory = repositoryFactory
	}

	public func discoverRoot(containing url: URL) throws -> URL {
		try GitRepository.discoverRoot(containing: url)
	}

	public func snapshot(at root: URL) throws -> GitWorkspaceSnapshot { try repository(at: root).snapshot() }
	public func status(at root: URL) throws -> GitStatus { try repository(at: root).status() }
	public func worktrees(at root: URL) throws -> [GitWorktree] { try repository(at: root).worktrees() }
	public func branches(at root: URL) throws -> [GitBranch] { try repository(at: root).branches() }
	public func historyPage(at root: URL, limit: Int, offset: Int) throws -> GitHistoryPage { try repository(at: root).historyPage(limit: limit, offset: offset) }
	public func switchBranch(_ name: String, at root: URL, stashingDirtyChanges: Bool) throws { try repository(at: root).switchBranch(name, stashingDirtyChanges: stashingDirtyChanges) }
	public func createBranch(named name: String, from startPoint: String?, at root: URL, stashingDirtyChanges: Bool) throws { try repository(at: root).createBranch(named: name, from: startPoint, stashingDirtyChanges: stashingDirtyChanges) }
	public func deleteBranch(_ name: String, at root: URL, force: Bool = false) throws { try repository(at: root).deleteBranch(name, force: force) }
	public func stashes(at root: URL) throws -> [GitStashEntry] { try repository(at: root).stashes() }
	public func stash(message: String, at root: URL) throws { try repository(at: root).stash(message: message) }
	public func applyStash(_ ref: String, at root: URL) throws { try repository(at: root).applyStash(ref) }
	public func applyStash(_ entry: GitStashEntry, at root: URL) throws { try repository(at: root).applyStash(entry) }
	public func popStash(_ ref: String, at root: URL) throws { try repository(at: root).popStash(ref) }
	public func popStash(_ entry: GitStashEntry, at root: URL) throws { try repository(at: root).popStash(entry) }
	public func dropStash(_ ref: String, at root: URL) throws { try repository(at: root).dropStash(ref) }
	public func dropStash(_ entry: GitStashEntry, at root: URL) throws { try repository(at: root).dropStash(entry) }
	public func stashDiff(_ ref: String, at root: URL) throws -> String { try repository(at: root).stashDiff(ref) }
	public func stashDiff(_ entry: GitStashEntry, at root: URL) throws -> String { try repository(at: root).stashDiff(entry) }
	public func fetchArguments(at root: URL) -> [String] { repository(at: root).fetchArguments() }
	public func pullArguments(at root: URL, mode: GitPullMode = .ffOnly) -> [String] { repository(at: root).pullArguments(mode: mode) }
	public func pushArguments(at root: URL) throws -> [String] { try repository(at: root).pushArguments() }
	public func blame(path: String, at root: URL) throws -> [GitBlameLine] { try repository(at: root).blame(path: path) }
	public func fileHistory(path: String, at root: URL) throws -> [GitHistoryEntry] { try repository(at: root).fileHistory(path: path) }
	public func lineHistory(path: String, line: Int, at root: URL) throws -> [GitHistoryEntry] { try repository(at: root).lineHistory(path: path, line: line) }
	public func diffFiles(path: String, at root: URL, staged: Bool) throws -> [DiffFile] { try repository(at: root).diffFiles(path: path, staged: staged) }
	public func stage(paths: [String], at root: URL) throws { try repository(at: root).stage(paths: paths) }
	public func unstage(paths: [String], at root: URL) throws { try repository(at: root).unstage(paths: paths) }
	public func stage(hunk: DiffHunk, in file: DiffFile, at root: URL) throws { try repository(at: root).stage(hunk: hunk, in: file) }
	public func unstage(hunk: DiffHunk, in file: DiffFile, at root: URL) throws { try repository(at: root).unstage(hunk: hunk, in: file) }
	public func stage(lineIndexes: IndexSet, in hunk: DiffHunk, file: DiffFile, at root: URL) throws { try repository(at: root).stage(lineIndexes: lineIndexes, in: hunk, file: file) }
	public func unstage(lineIndexes: IndexSet, in hunk: DiffHunk, file: DiffFile, at root: URL) throws { try repository(at: root).unstage(lineIndexes: lineIndexes, in: hunk, file: file) }
	public func commit(summary: String, body: String, signoff: Bool, amend: Bool, at root: URL) throws -> GitCommitResult { try repository(at: root).commit(summary: summary, body: body, signoff: signoff, amend: amend) }
	public func recentCommitMessages(at root: URL) throws -> [String] { try repository(at: root).recentCommitMessages() }
	public func conflictBlob(path: String, stage: Int, at root: URL) throws -> String { try repository(at: root).conflictBlob(path: path, stage: stage) }
	public func restoreConflictMarkers(path: String, at root: URL) throws { try repository(at: root).restoreConflictMarkers(path: path) }
	public func conflictResolutionState(at root: URL) throws -> GitConflictResolutionState { try repository(at: root).conflictResolutionState() }

	private func repository(at root: URL) -> GitRepository {
		repositoryFactory(root.standardizedFileURL)
	}
}
