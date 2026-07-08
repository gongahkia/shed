@preconcurrency import Dispatch
import Foundation
import ItsyEditor
import ItsyRender

@MainActor final class DocumentGitGutterController {
	private var refreshWorkItem: DispatchWorkItem?
	private(set) var decorator: GutterDecorator? {
		didSet {
			decoratorDidChange()
		}
	}

	var fileURL: () -> URL? = { nil }
	var decoratorDidChange: () -> Void = {}

	deinit {
		refreshWorkItem?.cancel()
	}

	func scheduleRefresh() {
		refreshWorkItem?.cancel()
		let workItem = DispatchWorkItem { [weak self] in
			self?.update()
		}
		refreshWorkItem = workItem
		DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: workItem)
	}

	func update() {
		guard
			let fileURL = fileURL(),
			let gitRoot = try? GitRepository.discoverRoot(containing: fileURL),
			let relativePath = LSPDiagnosticsAggregator.relativePath(forURI: fileURL.absoluteString, root: gitRoot)
		else {
			decorator = nil
			return
		}
		do {
			let repository = GitRepository(root: gitRoot)
			let files: [DiffFile] = switch ItsyGitHunkGutterCoordinator.currentMode {
			case .index:
				try repository.diffFiles(path: relativePath)
			case .head:
				try repository.diffFilesAgainstHead(path: relativePath)
			}
			let indicators = GitHunkIndicatorBuilder.indicators(files: files)
			decorator = indicators.isEmpty ? nil : GitHunkGutterDecorator(
				indicators: indicators,
				mode: ItsyGitHunkGutterCoordinator.currentMode,
				theme: AppTheme.palette.gitGutterSettings
			)
		} catch {
			decorator = nil
		}
	}
}
