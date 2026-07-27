import AppKit
import ItsyEditor
import ItsyRender

@MainActor final class EditorPaneTabBarController: NSObject {
	let view = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 32))
	private let scrollView = NSScrollView()
	private let stackView = NSStackView()
	private var tabIDsByTag: [Int: ObjectIdentifier] = [:]
	private var boundsObserver: NSObjectProtocol?
	var selectTab: ((ObjectIdentifier) -> Void)?
	var closeTab: ((ObjectIdentifier) -> Void)?

	override init() {
		super.init()
		configureView()
		boundsObserver = NotificationCenter.default.addObserver(
			forName: NSView.boundsDidChangeNotification,
			object: scrollView.contentView,
			queue: nil
		) { [weak self] _ in
			MainActor.assumeIsolated {
				self?.layoutTabContent()
			}
		}
		view.isHidden = true
	}

	deinit {
		if let boundsObserver {
			NotificationCenter.default.removeObserver(boundsObserver)
		}
	}

	func setTabs(_ tabs: [ItsyTab]) {
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

	func applyTheme(_ palette: AppThemePalette) {
		view.layer?.backgroundColor = palette.tabInactiveBackground.cgColor
	}

	private func configureView() {
		view.wantsLayer = true
		view.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		scrollView.drawsBackground = false
		scrollView.borderType = .noBorder
		scrollView.hasHorizontalScroller = true
		scrollView.hasVerticalScroller = false
		scrollView.autohidesScrollers = true
		scrollView.scrollerStyle = .overlay
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		scrollView.contentView.postsBoundsChangedNotifications = true
		stackView.orientation = .horizontal
		stackView.alignment = .centerY
		stackView.spacing = 2
		stackView.edgeInsets = NSEdgeInsets(top: 3, left: 6, bottom: 3, right: 6)
		scrollView.documentView = stackView
		view.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: view.topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
			view.heightAnchor.constraint(equalToConstant: 32),
		])
	}

	private func layoutTabContent() {
		stackView.layoutSubtreeIfNeeded()
		let fit = stackView.fittingSize
		let height = max(view.bounds.height, 32)
		stackView.frame = NSRect(x: 0, y: 0, width: fit.width, height: height)
		scrollView.documentView = stackView
	}

	private func makeTabView(_ tab: ItsyTab, tag: Int) -> NSView {
		let container = NSView()
		container.wantsLayer = true
		container.layer?.backgroundColor = tab.isSelected
			? AppTheme.palette.tabActiveBackground.cgColor
			: AppTheme.palette.tabInactiveBackground.cgColor

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
		selectButton.setAccessibilityLabel(L10n.string("Tab: \(tab.title)"))
		selectButton.contentTintColor = tab.isSelected ? AppTheme.palette.tabActiveForeground : AppTheme.palette.tabInactiveForeground

		let closeButton = NSButton(title: L10n.string("X"), target: self, action: #selector(closeTab(_:)))
		closeButton.tag = tag
		closeButton.isBordered = false
		closeButton.font = .systemFont(ofSize: 11, weight: .regular)
		closeButton.toolTip = L10n.string("Close")
		closeButton.setAccessibilityLabel(L10n.string("Close tab: \(tab.title)"))
		closeButton.contentTintColor = AppTheme.palette.tabInactiveForeground

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
		selectTab?(tabID)
	}

	@objc private func closeTab(_ sender: NSButton) {
		guard let tabID = tabIDsByTag[sender.tag] else {
			return
		}
		closeTab?(tabID)
	}
}

@MainActor struct EditorPane {
	let viewController: NSViewController
	let editorView: MetalTextView
	let tabBarController: EditorPaneTabBarController

	init() {
		recordBenchStage("editor_pane_view_init_begin")
		viewController = NSViewController()
		recordBenchStage("editor_pane_view_controller_end")
		tabBarController = EditorPaneTabBarController()
		editorView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		recordBenchStage("editor_pane_metal_view_end")
		let stack = NSStackView(frame: NSRect(x: 0, y: 0, width: 960, height: 672))
		stack.orientation = .vertical
		stack.alignment = .width
		stack.distribution = .fill
		stack.spacing = 0
		tabBarController.view.setContentHuggingPriority(.required, for: .vertical)
		editorView.setContentHuggingPriority(.defaultLow, for: .vertical)
		editorView.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
		stack.addArrangedSubview(tabBarController.view)
		stack.addArrangedSubview(editorView)
		viewController.view = stack
		recordBenchStage("editor_pane_view_init_end")
	}
}

struct EditorPaneLayout: Equatable {
	var vertical: Bool?
	var children: [EditorPaneLayout]

	static var leaf: EditorPaneLayout {
		EditorPaneLayout(vertical: nil, children: [])
	}

	static func split(vertical: Bool, children: [EditorPaneLayout]) -> EditorPaneLayout {
		EditorPaneLayout(vertical: vertical, children: children)
	}

	var encoded: String {
		guard let vertical else {
			return "L"
		}
		let marker = vertical ? "V" : "H"
		return "\(marker)[\(children.map(\.encoded).joined(separator: ","))]"
	}

	static func decode(_ value: String) -> EditorPaneLayout? {
		var index = value.startIndex
		guard let layout = decode(in: value, index: &index), index == value.endIndex else {
			return nil
		}
		return layout
	}

	private static func decode(in value: String, index: inout String.Index) -> EditorPaneLayout? {
		guard index < value.endIndex else {
			return nil
		}
		let marker = value[index]
		index = value.index(after: index)
		if marker == "L" {
			return .leaf
		}
		guard marker == "V" || marker == "H", index < value.endIndex, value[index] == "[" else {
			return nil
		}
		index = value.index(after: index)
		var children: [EditorPaneLayout] = []
		while index < value.endIndex, value[index] != "]" {
			guard let child = decode(in: value, index: &index) else {
				return nil
			}
			children.append(child)
			if index < value.endIndex, value[index] == "," {
				index = value.index(after: index)
			}
		}
		guard index < value.endIndex, value[index] == "]" else {
			return nil
		}
		index = value.index(after: index)
		guard !children.isEmpty else {
			return nil
		}
		return .split(vertical: marker == "V", children: children)
	}
}

@MainActor struct EditorPaneCoordinator {
	let rootSplitViewController = NSSplitViewController()
	private(set) var panes: [EditorPane] = []
	private var activePaneIndex = 0
	var focusedPaneIndex: Int {
		activePaneIndex
	}
	var activePane: EditorPane {
		panes[activePaneIndex]
	}

	init() {
		recordBenchStage("pane_coordinator_init_begin")
		let pane = EditorPane()
		panes = [pane]
		recordBenchStage("pane_coordinator_pane_end")
		rootSplitViewController.splitView.dividerStyle = .thin
		rootSplitViewController.addSplitViewItem(NSSplitViewItem(viewController: pane.viewController))
		recordBenchStage("pane_coordinator_init_end")
	}

	var view: NSView {
		rootSplitViewController.view
	}

	@discardableResult
	mutating func splitActive(vertical: Bool) -> EditorPane {
		let activePane = activePane
		let newPane = EditorPane()
		panes.append(newPane)
		let parent = activePane.viewController.parent as? NSSplitViewController ?? rootSplitViewController
		let index = parent.splitViewItems.firstIndex { $0.viewController === activePane.viewController } ?? 0
		let oldItem = parent.splitViewItems[index]
		parent.removeSplitViewItem(oldItem)
		let nested = NSSplitViewController()
		nested.splitView.isVertical = vertical
		nested.splitView.dividerStyle = .thin
		nested.addSplitViewItem(oldItem)
		nested.addSplitViewItem(NSSplitViewItem(viewController: newPane.viewController))
		parent.insertSplitViewItem(NSSplitViewItem(viewController: nested), at: index)
		activePaneIndex = panes.count - 1
		return newPane
	}

	mutating func closeActive() -> EditorPane? {
		guard panes.count > 1 else {
			return nil
		}
		let pane = activePane
		guard let parent = pane.viewController.parent as? NSSplitViewController, let index = parent.splitViewItems.firstIndex(where: { $0.viewController === pane.viewController }) else {
			return nil
		}
		parent.removeSplitViewItem(parent.splitViewItems[index])
		panes.removeAll { $0.viewController === pane.viewController }
		activePaneIndex = min(activePaneIndex, panes.count - 1)
		collapseIfNeeded(parent)
		return pane
	}

	mutating func closeOtherPanes() -> [EditorPane] {
		let kept = activePane
		let removed = panes.filter { $0.viewController !== kept.viewController }
		rootSplitViewController.splitViewItems.forEach { rootSplitViewController.removeSplitViewItem($0) }
		rootSplitViewController.addSplitViewItem(NSSplitViewItem(viewController: kept.viewController))
		panes = [kept]
		activePaneIndex = 0
		return removed
	}

	mutating func focusAdjacent(delta: Int) -> EditorPane {
		guard !panes.isEmpty else {
			return activePane
		}
		let next = (activePaneIndex + delta + panes.count) % panes.count
		activePaneIndex = next
		return activePane
	}

	@discardableResult
	mutating func focusPane(containing editorView: MetalTextView) -> EditorPane? {
		guard let index = panes.firstIndex(where: { $0.editorView === editorView }) else {
			return nil
		}
		activePaneIndex = index
		return panes[index]
	}

	@discardableResult
	mutating func focusPane(at index: Int) -> EditorPane? {
		guard panes.indices.contains(index) else {
			return nil
		}
		activePaneIndex = index
		return panes[index]
	}

	func layout() -> EditorPaneLayout {
		layout(for: rootSplitViewController)
	}

	mutating func restore(layout: EditorPaneLayout) -> [EditorPane] {
		rootSplitViewController.splitViewItems.forEach { rootSplitViewController.removeSplitViewItem($0) }
		panes = []
		let rootItem = splitViewItem(for: layout)
		rootSplitViewController.addSplitViewItem(rootItem)
		if !panes.isEmpty {
			activePaneIndex = 0
		}
		return panes
	}

	private func collapseIfNeeded(_ split: NSSplitViewController) {
		guard split !== rootSplitViewController, split.splitViewItems.count == 1, let child = split.splitViewItems.first else {
			return
		}
		guard let parent = split.parent as? NSSplitViewController, let index = parent.splitViewItems.firstIndex(where: { $0.viewController === split }) else {
			return
		}
		parent.removeSplitViewItem(parent.splitViewItems[index])
		split.removeSplitViewItem(child)
		parent.insertSplitViewItem(child, at: index)
	}

	private func layout(for controller: NSViewController) -> EditorPaneLayout {
		if panes.contains(where: { $0.viewController === controller }) {
			return .leaf
		}
		guard let split = controller as? NSSplitViewController else {
			return .leaf
		}
		return .split(vertical: split.splitView.isVertical, children: split.splitViewItems.map { layout(for: $0.viewController) })
	}

	private mutating func splitViewItem(for layout: EditorPaneLayout) -> NSSplitViewItem {
		NSSplitViewItem(viewController: viewController(for: layout))
	}

	private mutating func viewController(for layout: EditorPaneLayout) -> NSViewController {
		guard let vertical = layout.vertical else {
			let pane = EditorPane()
			panes.append(pane)
			return pane.viewController
		}
		let split = NSSplitViewController()
		split.splitView.isVertical = vertical
		split.splitView.dividerStyle = .thin
		for child in layout.children {
			split.addSplitViewItem(splitViewItem(for: child))
		}
		return split
	}
}
