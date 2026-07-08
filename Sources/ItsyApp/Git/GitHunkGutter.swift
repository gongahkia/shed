// @file Git hunk gutter state and decoration.
import AppKit
import ItsyEditor
import ItsyRender

enum GitHunkGutterMode {
	case index
	case head

	var title: String {
		switch self {
		case .index:
			L10n.string("index")
		case .head:
			L10n.string("HEAD")
		}
	}
}

@MainActor enum ItsyGitHunkGutterCoordinator {
	private weak static var documentController: ItsyDocumentController?
	private static var mode: GitHunkGutterMode = .index

	static var currentMode: GitHunkGutterMode {
		mode
	}

	static func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	static func setMode(_ nextMode: GitHunkGutterMode) {
		guard mode != nextMode else {
			return
		}
		mode = nextMode
		applyAll()
	}

	static func apply(to document: ItsyDocument) {
		document.updateGitHunkGutter()
	}

	static func applyAll() {
		for document in documentController?.documents ?? [] {
			(document as? ItsyDocument)?.scheduleGitHunkGutterRefresh()
		}
	}
}

final class GitHunkGutterDecorator: GutterDecorator {
	private let indicators: [GitHunkIndicator]
	private let mode: GitHunkGutterMode

	init(indicators: [GitHunkIndicator], mode: GitHunkGutterMode) {
		self.indicators = indicators
		self.mode = mode
	}

	func gutterMarkers(in lineRange: Range<Int>, for _: MetalTextView) -> [GutterMarker] {
		indicators.enumerated().compactMap { offset, indicator in
			guard lineRange.contains(indicator.line) else {
				return nil
			}
			return GutterMarker(
				id: "git-\(offset)",
				line: indicator.line,
				severity: severity(for: indicator.kind),
				message: message(for: indicator.kind),
				color: color(for: indicator.kind),
				placement: indicator.kind == .deleted ? .betweenLines : .line
			)
		}
	}

	func gutterMarkerClicked(_: GutterMarker, in _: MetalTextView) {}

	func gutterPopoverViewController(for _: GutterMarker, in _: MetalTextView) -> NSViewController? {
		nil
	}

	private func severity(for kind: GitHunkIndicatorKind) -> WorkspaceProblemSeverity {
		switch kind {
		case .added:
			.info
		case .modified:
			.warning
		case .deleted:
			.error
		}
	}

	private func message(for kind: GitHunkIndicatorKind) -> String {
		let change = switch kind {
		case .added:
			L10n.string("Added")
		case .modified:
			L10n.string("Modified")
		case .deleted:
			L10n.string("Deleted")
		}
		return L10n.string("Git \(change) vs \(mode.title)")
	}

	private func color(for kind: GitHunkIndicatorKind) -> SIMD4<Float> {
		switch kind {
		case .added:
			SIMD4<Float>(0.28, 0.78, 0.46, 1.0)
		case .modified:
			SIMD4<Float>(0.95, 0.68, 0.18, 1.0)
		case .deleted:
			SIMD4<Float>(0.95, 0.25, 0.22, 1.0)
		}
	}
}
