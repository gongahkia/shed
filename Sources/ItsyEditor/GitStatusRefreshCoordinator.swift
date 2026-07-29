import Foundation

public enum GitStatusRefreshResult: Equatable, Sendable {
	case snapshot(GitWorkspaceSnapshot)
	case failure(String)
}

public actor GitStatusRefreshCoordinator {
	public typealias Loader = @Sendable (URL) throws -> GitWorkspaceSnapshot

	private var generation = 0
	private var activeTask: Task<GitStatusRefreshResult?, Never>?

	public init() {}

	public func refresh(root: URL) async -> GitStatusRefreshResult? {
		await refresh(root: root, loader: Self.defaultLoader)
	}

	public func refresh(root: URL, loader: @escaping Loader) async -> GitStatusRefreshResult? {
		generation += 1
		let requestGeneration = generation
		activeTask?.cancel()
		let task: Task<GitStatusRefreshResult?, Never> = Task.detached(priority: .userInitiated) { // keep blocking git I/O off the coordinator actor
			await Self.load(root: root, loader: loader)
		}
		activeTask = task
		let result = await withTaskCancellationHandler(operation: {
			await task.value
		}, onCancel: {
			task.cancel()
		})
		guard requestGeneration == generation, !Task.isCancelled else {
			return nil
		}
		activeTask = nil
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

	private static func load(root: URL, loader: @escaping Loader) async -> GitStatusRefreshResult? {
		var failure: String?
		for attempt in 0 ..< 2 {
			guard !Task.isCancelled else {
				return nil
			}
			do {
				let snapshot = try loader(root)
				guard !Task.isCancelled else {
					return nil
				}
				return .snapshot(snapshot)
			} catch {
				failure = String(describing: error)
				guard attempt == 0, !Task.isCancelled else {
					break
				}
				do {
					try await Task.sleep(nanoseconds: 50_000_000)
				} catch {
					return nil
				}
			}
		}
		return failure.map(GitStatusRefreshResult.failure)
	}
}
