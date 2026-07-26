import Foundation

public enum GitStatusRefreshResult: Equatable, Sendable {
	case snapshot(GitWorkspaceSnapshot)
	case failure(String)
}

public actor GitStatusRefreshCoordinator {
	public typealias Loader = @Sendable (URL) throws -> GitWorkspaceSnapshot

	private var generation = 0
	private var activeTask: Task<GitStatusRefreshResult, Never>?

	public init() {}

	public func refresh(root: URL) async -> GitStatusRefreshResult? {
		await refresh(root: root, loader: Self.defaultLoader)
	}

	public func refresh(root: URL, loader: @escaping Loader) async -> GitStatusRefreshResult? {
		generation += 1
		let requestGeneration = generation
		activeTask?.cancel()
		let task: Task<GitStatusRefreshResult, Never> = Task.detached(priority: .userInitiated) {
			do {
				return GitStatusRefreshResult.snapshot(try loader(root))
			} catch {
				return GitStatusRefreshResult.failure(String(describing: error))
			}
		}
		activeTask = task
		let result = await task.value
		guard requestGeneration == generation, !Task.isCancelled else {
			return nil
		}
		return result
	}

	public func cancel() {
		generation += 1
		activeTask?.cancel()
		activeTask = nil
	}

	private static let defaultLoader: Loader = { root in
		let gitRoot = try GitRepository.discoverRoot(containing: root)
		return try GitRepository(root: gitRoot).snapshot()
	}
}
