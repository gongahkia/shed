import AppKit
import ItsyEditor
import ItsyRender

enum ItsyProblemGutterCoordinator {
	private weak static var documentController: ItsyDocumentController?
	private static var rootURL: URL?
	private static var problems: [WorkspaceProblem] = []
	private static var selectProblem: ((Int) -> Void)?
	private static var openRelated: ((WorkspaceProblemRelatedInformation) -> Void)?

	static func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	static func setProblems(
		root: URL?,
		problems: [WorkspaceProblem],
		selectProblem: @escaping (Int) -> Void,
		openRelated: @escaping (WorkspaceProblemRelatedInformation) -> Void
	) {
		rootURL = root?.standardizedFileURL
		self.problems = problems
		self.selectProblem = selectProblem
		self.openRelated = openRelated
		applyAll()
	}

	static func apply(to document: ItsyDocument) {
		guard
			let rootURL,
			let fileURL = document.fileURL,
			let relativePath = LSPDiagnosticsAggregator.relativePath(forURI: fileURL.absoluteString, root: rootURL)
		else {
			document.setGutterDecorator(nil)
			return
		}
		let fileProblems = problems.enumerated()
			.filter { $0.element.path == relativePath }
			.map { (index: $0.offset, problem: $0.element) }
		guard !fileProblems.isEmpty, let selectProblem, let openRelated else {
			document.setGutterDecorator(nil)
			return
		}
		document.setGutterDecorator(ProblemGutterDecorator(
			problems: fileProblems,
			selectProblem: selectProblem,
			openRelated: openRelated
		))
	}

	private static func applyAll() {
		for document in documentController?.documents ?? [] {
			guard let document = document as? ItsyDocument else {
				continue
			}
			apply(to: document)
		}
	}
}

private final class ProblemGutterDecorator: GutterDecorator {
	private let problems: [(index: Int, problem: WorkspaceProblem)]
	private let selectProblem: (Int) -> Void
	private let openRelated: (WorkspaceProblemRelatedInformation) -> Void

	init(
		problems: [(index: Int, problem: WorkspaceProblem)],
		selectProblem: @escaping (Int) -> Void,
		openRelated: @escaping (WorkspaceProblemRelatedInformation) -> Void
	) {
		self.problems = problems
		self.selectProblem = selectProblem
		self.openRelated = openRelated
	}

	func gutterMarkers(in lineRange: Range<Int>, for _: MetalTextView) -> [GutterMarker] {
		problems.compactMap { item in
			let zeroLine = max(0, item.problem.line - 1)
			guard lineRange.contains(zeroLine) else {
				return nil
			}
			return GutterMarker(
				id: "\(item.index)",
				line: zeroLine,
				severity: item.problem.severity,
				message: item.problem.message
			)
		}
	}

	func gutterMarkerClicked(_ marker: GutterMarker, in _: MetalTextView) {
		guard let index = Int(marker.id) else {
			return
		}
		selectProblem(index)
	}

	func gutterPopoverViewController(for marker: GutterMarker, in _: MetalTextView) -> NSViewController? {
		guard let index = Int(marker.id), let problem = problems.first(where: { $0.index == index })?.problem else {
			return nil
		}
		return ProblemGutterPopoverViewController(problem: problem, openRelated: openRelated)
	}
}

private final class ProblemGutterPopoverViewController: NSViewController {
	private let problem: WorkspaceProblem
	private let openRelated: (WorkspaceProblemRelatedInformation) -> Void

	init(problem: WorkspaceProblem, openRelated: @escaping (WorkspaceProblemRelatedInformation) -> Void) {
		self.problem = problem
		self.openRelated = openRelated
		super.init(nibName: nil, bundle: nil)
	}

	required init?(coder _: NSCoder) {
		nil
	}

	override func loadView() {
		let width: CGFloat = 360
		let linkHeight: CGFloat = problem.relatedInformation.isEmpty ? 0 : CGFloat(problem.relatedInformation.count * 24 + 10)
		let height = min(280, max(72, 56 + linkHeight))
		let container = NSView(frame: NSRect(x: 0, y: 0, width: width, height: height))
		let stack = NSStackView()
		stack.orientation = .vertical
		stack.alignment = .leading
		stack.spacing = 6
		stack.translatesAutoresizingMaskIntoConstraints = false
		container.addSubview(stack)

		let message = NSTextField(wrappingLabelWithString: problem.message)
		message.font = .systemFont(ofSize: 12)
		message.textColor = .labelColor
		message.translatesAutoresizingMaskIntoConstraints = false
		stack.addArrangedSubview(message)
		message.widthAnchor.constraint(equalToConstant: width - 24).isActive = true

		if !problem.relatedInformation.isEmpty {
			let label = NSTextField(labelWithString: L10n.string("Related"))
			label.font = .systemFont(ofSize: 11, weight: .semibold)
			label.textColor = .secondaryLabelColor
			stack.addArrangedSubview(label)
		}

		for (index, item) in problem.relatedInformation.enumerated() {
			let button = NSButton(title: linkTitle(for: item), target: self, action: #selector(openRelatedLink(_:)))
			button.tag = index
			button.isBordered = false
			button.alignment = .left
			button.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
			button.contentTintColor = .linkColor
			button.lineBreakMode = .byTruncatingMiddle
			button.translatesAutoresizingMaskIntoConstraints = false
			stack.addArrangedSubview(button)
			button.widthAnchor.constraint(equalToConstant: width - 24).isActive = true
		}

		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 12),
			stack.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -12),
			stack.topAnchor.constraint(equalTo: container.topAnchor, constant: 10),
			stack.bottomAnchor.constraint(lessThanOrEqualTo: container.bottomAnchor, constant: -10),
		])
		preferredContentSize = container.frame.size
		view = container
	}

	@objc private func openRelatedLink(_ sender: NSButton) {
		guard sender.tag >= 0, sender.tag < problem.relatedInformation.count else {
			return
		}
		openRelated(problem.relatedInformation[sender.tag])
	}

	private func linkTitle(for item: WorkspaceProblemRelatedInformation) -> String {
		let column = item.column.map { ":\($0)" } ?? ""
		return "\(item.path):\(item.line)\(column)  \(item.message)"
	}
}
