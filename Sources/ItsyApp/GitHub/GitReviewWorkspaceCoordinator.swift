import AppKit
import ItsyEditor

enum GitReviewWorkspaceTransition: Equatable {
	case reviewWorkspaceOpened(URL)
	case restorationRequiredForDirtyReviewWorktree(URL)
	case cancelled
	case restored(URL)
	case restoredWithNotice(String)
	case restorationFailed(String)
}

@MainActor final class GitReviewWorkspaceCoordinator {
	private let persistWorkspaceState: () -> Void
	private let openWorkspace: (URL) -> Bool
	private var session: GitReviewModeSession?

	init(persistWorkspaceState: @escaping () -> Void, openWorkspace: @escaping (URL) -> Bool) {
		self.persistWorkspaceState = persistWorkspaceState
		self.openWorkspace = openWorkspace
	}

	func enter(_ session: GitReviewModeSession) -> GitReviewWorkspaceTransition {
		persistWorkspaceState()
		guard openWorkspace(session.reviewWorkspaceURL) else {
			return .restorationFailed("Could not open review workspace at \(session.reviewWorkspaceURL.path).")
		}
		self.session = session
		return .reviewWorkspaceOpened(session.reviewWorkspaceURL)
	}

	func exit(_ result: GitReviewModeExitResult) -> GitReviewWorkspaceTransition {
		switch result {
		case let .requiresReviewWorktreeExitChoice(reviewWorkspaceURL):
			return .restorationRequiredForDirtyReviewWorktree(reviewWorkspaceURL)
		case .cancelled:
			return .cancelled
		case let .readyToRestore(sourceWorkspaceURL):
			return restore(sourceWorkspaceURL, notice: nil)
		case let .readyToRestoreWithRetainedReviewWorktree(sourceWorkspaceURL, _, explanation):
			return restore(sourceWorkspaceURL, notice: explanation)
		}
	}

	private func restore(_ sourceWorkspaceURL: URL, notice: String?) -> GitReviewWorkspaceTransition {
		guard openWorkspace(sourceWorkspaceURL) else {
			return .restorationFailed("Could not restore workspace at \(sourceWorkspaceURL.path).")
		}
		session = nil
		return notice.map(GitReviewWorkspaceTransition.restoredWithNotice) ?? .restored(sourceWorkspaceURL)
	}
}
