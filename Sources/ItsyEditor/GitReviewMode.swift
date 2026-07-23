import Foundation

public enum GitReviewDirtyWorktreeChoice: Equatable, Sendable {
	case cancel
	case keepChangesInSourceWorktree
}

public enum GitReviewRemoteChoice: Equatable, Sendable {
	case cancel
	case useRemote(String)
}

public enum GitReviewExitChoice: Equatable, Sendable {
	case cancel
	case keepReviewWorktree
	case discardReviewChangesAndRemoveWorktree
}

public enum GitReviewModeError: Error, Equatable, Sendable {
	case invalidPullRequestNumber
	case invalidReviewWorktreeURL(URL)
	case remoteUnavailable(String)
	case reviewWorktreeRemovalFailed(String)
}

public struct GitReviewModeSession: Equatable, Sendable {
	public let pullRequestNumber: Int
	public let sourceWorkspaceURL: URL
	public let sourceBranch: String?
	public let reviewWorkspaceURL: URL
	public let remoteName: String
	public let fetchedReference: String

	public init(pullRequestNumber: Int, sourceWorkspaceURL: URL, sourceBranch: String?, reviewWorkspaceURL: URL, remoteName: String, fetchedReference: String) {
		self.pullRequestNumber = pullRequestNumber
		self.sourceWorkspaceURL = sourceWorkspaceURL
		self.sourceBranch = sourceBranch
		self.reviewWorkspaceURL = reviewWorkspaceURL
		self.remoteName = remoteName
		self.fetchedReference = fetchedReference
	}
}

public enum GitReviewModeStartResult: Equatable, Sendable {
	case requiresDirtyWorktreeChoice
	case requiresRemoteChoice(availableRemotes: [String])
	case cancelled
	case started(GitReviewModeSession)
}

public enum GitReviewModeExitResult: Equatable, Sendable {
	case readyToRestore(sourceWorkspaceURL: URL)
	case requiresReviewWorktreeExitChoice(reviewWorkspaceURL: URL)
	case cancelled
	case readyToRestoreWithRetainedReviewWorktree(sourceWorkspaceURL: URL, reviewWorkspaceURL: URL, explanation: String)
}

public struct GitReviewModeManager {
	public let repository: GitRepository
	private let fileManager: FileManager

	public init(repository: GitRepository, fileManager: FileManager = .default) {
		self.repository = repository
		self.fileManager = fileManager
	}

	public func start(
		pullRequestNumber: Int,
		reviewWorkspaceURL: URL,
		dirtyWorktreeChoice: GitReviewDirtyWorktreeChoice? = nil,
		remoteChoice: GitReviewRemoteChoice? = nil
	) throws -> GitReviewModeStartResult {
		guard pullRequestNumber > 0 else { throw GitReviewModeError.invalidPullRequestNumber }
		let sourceURL = repository.root.standardizedFileURL
		let reviewURL = reviewWorkspaceURL.standardizedFileURL
		guard isValidReviewWorktreeURL(reviewURL, sourceWorkspaceURL: sourceURL) else {
			throw GitReviewModeError.invalidReviewWorktreeURL(reviewURL)
		}
		let status = try repository.status()
		if status.hasChanges {
			switch dirtyWorktreeChoice {
			case nil:
				return .requiresDirtyWorktreeChoice
			case .cancel:
				return .cancelled
			case .keepChangesInSourceWorktree:
				break
			}
		}
		let remotes = try availableRemotes()
		let remote: String
		if remotes.contains("origin") {
			if case .cancel = remoteChoice { return .cancelled }
			if case let .useRemote(name)? = remoteChoice {
				guard remotes.contains(name) else { throw GitReviewModeError.remoteUnavailable(name) }
				remote = name
			} else {
				remote = "origin"
			}
		} else {
			switch remoteChoice {
			case nil:
				return .requiresRemoteChoice(availableRemotes: remotes)
			case .cancel:
				return .cancelled
			case let .useRemote(name):
				guard remotes.contains(name) else { throw GitReviewModeError.remoteUnavailable(name) }
				remote = name
			}
		}
		let fetchedReference = "refs/itsy/review/pr-\(pullRequestNumber)-\(UUID().uuidString.lowercased())"
		do {
			_ = try repository.runner.runGit(
				arguments: ["fetch", "--no-tags", remote, "+refs/pull/\(pullRequestNumber)/head:\(fetchedReference)"],
				root: sourceURL
			)
			let commit = try repository.runner.runGit(arguments: ["rev-parse", fetchedReference], root: sourceURL)
			let oid = commit.trimmingCharacters(in: .whitespacesAndNewlines)
			_ = try repository.runner.runGit(arguments: ["worktree", "add", "--detach", reviewURL.path, oid], root: sourceURL)
			return .started(GitReviewModeSession(
				pullRequestNumber: pullRequestNumber,
				sourceWorkspaceURL: sourceURL,
				sourceBranch: status.branch.head,
				reviewWorkspaceURL: reviewURL,
				remoteName: remote,
				fetchedReference: fetchedReference
			))
		} catch {
			_ = try? repository.runner.runGit(arguments: ["update-ref", "-d", fetchedReference], root: sourceURL)
			throw error
		}
	}

	public func exit(_ session: GitReviewModeSession, choice: GitReviewExitChoice? = nil) throws -> GitReviewModeExitResult {
		let reviewRepository = GitRepository(root: session.reviewWorkspaceURL, runner: repository.runner)
		let hasReviewChanges = try reviewRepository.status().hasChanges
		if hasReviewChanges {
			switch choice {
			case nil:
				return .requiresReviewWorktreeExitChoice(reviewWorkspaceURL: session.reviewWorkspaceURL)
			case .cancel:
				return .cancelled
			case .keepReviewWorktree:
				return .readyToRestoreWithRetainedReviewWorktree(
					sourceWorkspaceURL: session.sourceWorkspaceURL,
					reviewWorkspaceURL: session.reviewWorkspaceURL,
					explanation: "Review worktree has local changes and was retained at \(session.reviewWorkspaceURL.path)."
				)
			case .discardReviewChangesAndRemoveWorktree:
				try removeReviewWorktree(session, force: true)
				return .readyToRestore(sourceWorkspaceURL: session.sourceWorkspaceURL)
			}
		}
		try removeReviewWorktree(session, force: false)
		return .readyToRestore(sourceWorkspaceURL: session.sourceWorkspaceURL)
	}

	private func availableRemotes() throws -> [String] {
		let output = try repository.runner.runGit(arguments: ["remote"], root: repository.root)
		return output.split(whereSeparator: \.isNewline).map(String.init).filter { isSafeRemoteName($0) }.sorted()
	}

	private func removeReviewWorktree(_ session: GitReviewModeSession, force: Bool) throws {
		do {
			var arguments = ["worktree", "remove"]
			if force { arguments.append("--force") }
			arguments.append(session.reviewWorkspaceURL.path)
			_ = try repository.runner.runGit(arguments: arguments, root: session.sourceWorkspaceURL)
			_ = try repository.runner.runGit(arguments: ["update-ref", "-d", session.fetchedReference], root: session.sourceWorkspaceURL)
		} catch {
			throw GitReviewModeError.reviewWorktreeRemovalFailed(String(describing: error))
		}
	}

	private func isValidReviewWorktreeURL(_ reviewURL: URL, sourceWorkspaceURL: URL) -> Bool {
		let sourcePath = sourceWorkspaceURL.path
		let reviewPath = reviewURL.path
		guard !reviewPath.isEmpty, reviewPath != sourcePath, !reviewPath.hasPrefix(sourcePath + "/") else { return false }
		return !fileManager.fileExists(atPath: reviewPath)
	}

	private func isSafeRemoteName(_ name: String) -> Bool {
		!name.isEmpty && name.allSatisfy { $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-" }
	}
}
