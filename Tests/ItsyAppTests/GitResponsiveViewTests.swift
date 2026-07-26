import AppKit
@testable import ItsyApp
import ItsyConfig
import ItsyWorkbenchLayout
import Testing

@Test @MainActor func gitResponsiveViewKeepsPanesAccessibleAndKeyboardNavigable() {
	_ = NSApplication.shared
	let coordinator = GitCoordinator(
		documentController: ItsyDocumentController(),
		activeDocumentProvider: { nil },
		settingsProvider: { ItsySettings.GitSettings(presentation: .sidebar) }
	)
	let full = coordinator.gitResponsiveViewStateForTesting(width: 960)
	#expect(full.mode == .full)
	#expect(full.visiblePaneIdentifiers == ["git.files", "git.diff"])
	#expect(full.focusIdentifiers.contains("git.fetch"))
	#expect(!full.focusIdentifiers.contains("git.more-actions"))
	#expect(coordinator.gitFocusTraversalIsClosedForTesting())

	let compact = coordinator.gitResponsiveViewStateForTesting(width: 640)
	#expect(compact.mode == .compact)
	#expect(compact.visiblePaneIdentifiers == ["git.files", "git.diff"])
	#expect(compact.focusIdentifiers.contains("git.more-actions"))
	#expect(!compact.focusIdentifiers.contains("git.fetch"))
	#expect(coordinator.gitFocusTraversalIsClosedForTesting())

	let files = coordinator.gitResponsiveViewStateForTesting(width: 400)
	#expect(files.mode == .files)
	#expect(files.visiblePaneIdentifiers == ["git.files"])
	#expect(files.focusIdentifiers.contains("git.compact-pane"))
	#expect(!files.focusIdentifiers.contains("git.diff-mode"))
	#expect(!files.focusIdentifiers.contains("git.amend"))
	#expect(!files.focusIdentifiers.contains("git.commit-output"))
	#expect(coordinator.gitFocusTraversalIsClosedForTesting())

	coordinator.selectGitCompactPaneForTesting(.diff)
	let diff = coordinator.gitResponsiveViewStateForTesting(width: 400)
	#expect(diff.mode == .diff)
	#expect(diff.visiblePaneIdentifiers == ["git.diff"])
	#expect(diff.focusIdentifiers.contains("git.diff-mode"))
	#expect(coordinator.gitFocusTraversalIsClosedForTesting())
}
