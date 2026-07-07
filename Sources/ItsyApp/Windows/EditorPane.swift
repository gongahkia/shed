import AppKit
import ItsyEditor
import ItsyRender

struct EditorPane {
	let viewController: NSViewController
	let editorView: MetalTextView

	init() {
		recordBenchStage("editor_pane_view_init_begin")
		viewController = NSViewController()
		recordBenchStage("editor_pane_view_controller_end")
		editorView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		recordBenchStage("editor_pane_metal_view_end")
		viewController.view = editorView
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
		return .split(vertical: marker == "V", children: children)
	}
}

struct EditorPaneCoordinator {
	let rootSplitViewController = NSSplitViewController()
	private(set) var panes: [EditorPane] = []
	private var activePaneIndex = 0
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
