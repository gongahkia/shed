import AppKit

struct ItsyTab: Equatable {
	let id: ObjectIdentifier
	let title: String
	let isDirty: Bool
	let isSelected: Bool
}

final class TabBarView: NSView {
	var tabs: [ItsyTab] = [] {
		didSet { rebuildTabs() }
	}
	var onSelect: ((ObjectIdentifier) -> Void)?
	var onClose: ((ObjectIdentifier) -> Void)?

	private let scrollView = NSScrollView()
	private let stackView = NSStackView()
	private var tabIDsByTag: [Int: ObjectIdentifier] = [:]

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		configure()
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		scrollView.drawsBackground = false
		scrollView.borderType = .noBorder
		scrollView.hasHorizontalScroller = true
		scrollView.hasVerticalScroller = false
		scrollView.autohidesScrollers = true
		scrollView.scrollerStyle = .overlay
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		stackView.orientation = .horizontal
		stackView.alignment = .centerY
		stackView.spacing = 2
		stackView.edgeInsets = NSEdgeInsets(top: 3, left: 6, bottom: 3, right: 6)
		scrollView.documentView = stackView
		addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
			heightAnchor.constraint(equalToConstant: 32),
		])
	}

	override func layout() {
		super.layout()
		layoutTabContent()
	}

	private func rebuildTabs() {
		for view in stackView.arrangedSubviews {
			stackView.removeArrangedSubview(view)
			view.removeFromSuperview()
		}
		tabIDsByTag = Dictionary(uniqueKeysWithValues: tabs.enumerated().map { index, tab in (index, tab.id) })
		for (index, tab) in tabs.enumerated() {
			stackView.addArrangedSubview(makeTabView(tab, tag: index))
		}
		layoutTabContent()
	}

	private func layoutTabContent() {
		stackView.layoutSubtreeIfNeeded()
		let fit = stackView.fittingSize
		let height = max(bounds.height, 32)
		let width = max(scrollView.contentView.bounds.width, fit.width)
		stackView.frame = NSRect(x: 0, y: 0, width: width, height: height)
		scrollView.documentView = stackView
	}

	private func makeTabView(_ tab: ItsyTab, tag: Int) -> NSView {
		let container = NSView()
		container.wantsLayer = true
		container.layer?.backgroundColor = tab.isSelected
			? NSColor.selectedControlColor.withAlphaComponent(0.24).cgColor
			: NSColor.clear.cgColor

		let stack = NSStackView()
		stack.orientation = .horizontal
		stack.alignment = .centerY
		stack.spacing = 4
		stack.edgeInsets = NSEdgeInsets(top: 0, left: 8, bottom: 0, right: 6)
		stack.translatesAutoresizingMaskIntoConstraints = false
		container.addSubview(stack)

		let title = tab.isDirty ? "• \(tab.title)" : tab.title
		let selectButton = NSButton(title: title, target: self, action: #selector(selectTab(_:)))
		selectButton.tag = tag
		selectButton.isBordered = false
		selectButton.font = .systemFont(ofSize: 12, weight: tab.isSelected ? .semibold : .regular)
		selectButton.lineBreakMode = .byTruncatingMiddle
		selectButton.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
		selectButton.toolTip = tab.title

		let closeButton = NSButton(title: L10n.string("X"), target: self, action: #selector(closeTab(_:)))
		closeButton.tag = tag
		closeButton.isBordered = false
		closeButton.font = .systemFont(ofSize: 11, weight: .regular)
		closeButton.toolTip = L10n.string("Close")

		stack.addArrangedSubview(selectButton)
		stack.addArrangedSubview(closeButton)
		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			stack.topAnchor.constraint(equalTo: container.topAnchor),
			stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
			container.heightAnchor.constraint(equalToConstant: 26),
			container.widthAnchor.constraint(greaterThanOrEqualToConstant: 96),
			container.widthAnchor.constraint(lessThanOrEqualToConstant: 220),
		])
		return container
	}

	@objc private func selectTab(_ sender: NSButton) {
		guard let tabID = tabIDsByTag[sender.tag] else {
			return
		}
		onSelect?(tabID)
	}

	@objc private func closeTab(_ sender: NSButton) {
		guard let tabID = tabIDsByTag[sender.tag] else {
			return
		}
		onClose?(tabID)
	}
}

enum ItsyTabCoordinator {
	private static weak var documentController: ItsyDocumentController?
	private static let tabBars = NSHashTable<TabBarView>.weakObjects()

	static func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
		refresh()
	}

	static func register(_ tabBar: TabBarView) {
		tabBars.add(tabBar)
		tabBar.onSelect = { id in
			selectDocument(id)
		}
		tabBar.onClose = { id in
			closeDocument(id)
		}
		refresh()
	}

	static func refresh() {
		let documents = itsyDocuments()
		let selectedID = selectedDocument().map(ObjectIdentifier.init)
		let tabs = documents.map { document in
			ItsyTab(
				id: ObjectIdentifier(document),
				title: title(for: document),
				isDirty: document.isDocumentEdited,
				isSelected: ObjectIdentifier(document) == selectedID
			)
		}
		for tabBar in tabBars.allObjects {
			tabBar.tabs = tabs
		}
	}

	static func selectAdjacentDocument(delta: Int) {
		let documents = itsyDocuments()
		guard !documents.isEmpty, let selected = selectedDocument(), let index = documents.firstIndex(where: { $0 === selected }) else {
			return
		}
		let next = (index + delta + documents.count) % documents.count
		selectDocument(ObjectIdentifier(documents[next]))
	}

	private static func selectDocument(_ id: ObjectIdentifier) {
		guard let document = itsyDocuments().first(where: { ObjectIdentifier($0) == id }) else {
			return
		}
		documentController?.showDocument(document)
	}

	private static func closeDocument(_ id: ObjectIdentifier) {
		guard let document = itsyDocuments().first(where: { ObjectIdentifier($0) == id }) else {
			return
		}
		document.close()
		refresh()
	}

	private static func itsyDocuments() -> [ItsyDocument] {
		(documentController?.documents ?? []).compactMap { $0 as? ItsyDocument }
	}

	private static func selectedDocument() -> ItsyDocument? {
		NSApplication.shared.keyWindow?.windowController?.document as? ItsyDocument
	}

	private static func title(for document: ItsyDocument) -> String {
		if let fileName = document.fileURL?.lastPathComponent {
			return fileName
		}
		return document.displayName
	}
}
