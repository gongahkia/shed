import Foundation
@testable import ItsyApp
import ItsyEditor
import Testing

@MainActor @Test func gitReviewWorkspaceCoordinatorPersistsAndRestoresPriorWorkspace() {
	let source = URL(fileURLWithPath: "/workspace/source", isDirectory: true)
	let review = URL(fileURLWithPath: "/workspace/review", isDirectory: true)
	let session = GitReviewModeSession(pullRequestNumber: 7, sourceWorkspaceURL: source, sourceBranch: "main", reviewWorkspaceURL: review, remoteName: "origin", fetchedReference: "refs/itsy/review/test")
	var persistCount = 0
	var opened: [URL] = []
	let coordinator = GitReviewWorkspaceCoordinator(
		persistWorkspaceState: { persistCount += 1 },
		openWorkspace: { url in
			opened.append(url)
			return true
		}
	)
	#expect(coordinator.enter(session) == .reviewWorkspaceOpened(review))
	#expect(persistCount == 1)
	#expect(opened == [review])
	#expect(coordinator.exit(.readyToRestore(sourceWorkspaceURL: source)) == .restored(source))
	#expect(opened == [review, source])
}

@MainActor @Test func gitReviewWorkspaceCoordinatorReportsRetainedOrFailedRestoration() {
	let source = URL(fileURLWithPath: "/workspace/source", isDirectory: true)
	let review = URL(fileURLWithPath: "/workspace/review", isDirectory: true)
	let retaining = GitReviewWorkspaceCoordinator(persistWorkspaceState: {}, openWorkspace: { _ in true })
	#expect(retaining.exit(.readyToRestoreWithRetainedReviewWorktree(sourceWorkspaceURL: source, reviewWorkspaceURL: review, explanation: "Review retained")) == .restoredWithNotice("Review retained"))
	let failing = GitReviewWorkspaceCoordinator(persistWorkspaceState: {}, openWorkspace: { _ in false })
	guard case .restorationFailed = failing.exit(.readyToRestore(sourceWorkspaceURL: source)) else {
		Issue.record("expected restoration failure")
		return
	}
}
