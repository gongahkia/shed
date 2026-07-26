import AppKit
import Foundation
import ItsyDebugger

@MainActor final class DebugVariablesCoordinator: NSObject, NSOutlineViewDataSource, NSOutlineViewDelegate {
	private let activeSessionProvider: () -> DebugAppSession?
	private var panel: NSPanel?
	private var contentView: NSView?
	private var statusLabel: NSTextField?
	private var outlineView: NSOutlineView?
	private var rootNodes: [DebugVariableNode] = []
	private var generation = 0

	init(activeSessionProvider: @escaping () -> DebugAppSession?) {
		self.activeSessionProvider = activeSessionProvider
		super.init()
	}

	@objc func showVariables(_ sender: Any?) {
		let panel = makePanelIfNeeded()
		center(panel, relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshVariables(nil)
	}

	func refreshIfVisible() {
		if panel?.isVisible == true || (contentView?.window != nil && contentView?.window !== panel) {
			refreshVariables(nil)
		}
	}

	func debuggerContentView() -> NSView {
		let contentView = makeContentViewIfNeeded()
		panel?.orderOut(nil)
		return contentView
	}

	func prepareForDebuggerPresentation() {
		panel?.orderOut(nil)
		refreshVariables(nil)
	}

	func clear() {
		generation += 1
		rootNodes = []
		outlineView?.reloadData()
		setStatus(L10n.string("No active debug session"), isError: true)
	}

	private func makePanelIfNeeded() -> NSPanel {
		let panel: NSPanel
		if let existing = self.panel {
			panel = existing
		} else {
			panel = NSPanel(
				contentRect: NSRect(x: 0, y: 0, width: 620, height: 520),
				styleMask: [.titled, .closable, .resizable, .utilityWindow],
				backing: .buffered,
				defer: false
			)
			panel.title = L10n.string("Variables")
			panel.isReleasedWhenClosed = false
			self.panel = panel
		}
		let contentView = makeContentViewIfNeeded()
		contentView.removeFromSuperview()
		panel.contentView = contentView
		return panel
	}

	private func makeContentViewIfNeeded() -> NSView {
		if let contentView {
			return contentView
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 620, height: 520))
		configureView(contentView)
		self.contentView = contentView
		return contentView
	}

	private func configureView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshVariables(_:)))
		let header = NSStackView(views: [statusLabel, refreshButton])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let outlineView = NSOutlineView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("variables"))
		column.title = L10n.string("Variables")
		column.resizingMask = .autoresizingMask
		outlineView.addTableColumn(column)
		outlineView.outlineTableColumn = column
		outlineView.headerView = nil
		outlineView.rowSizeStyle = .small
		outlineView.dataSource = self
		outlineView.delegate = self
		outlineView.target = self
		outlineView.doubleAction = #selector(editSelectedVariable(_:))
		let scrollView = NSScrollView()
		scrollView.documentView = outlineView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		header.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		self.statusLabel = statusLabel
		self.outlineView = outlineView
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(660, max(500, hostFrame.width - 140))
		let height = min(580, max(340, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	@objc private func refreshVariables(_ sender: Any?) {
		guard let session = activeSessionProvider() else {
			setRootNodes([], status: L10n.string("No active debug session"), isError: true)
			return
		}
		generation += 1
		let currentGeneration = generation
		setStatus(L10n.string("Refreshing"), isError: false)
		Task(priority: .userInitiated) { [weak self] in
			do {
				guard let frameID = await session.debugSession.focusedFrameID else {
					throw DebugVariablesError.missingFocusedFrame
				}
				let scopes = try await session.debugSession.scopes(for: frameID)
				let nodes = scopes.map(DebugVariableNode.scope)
				Task { @MainActor in
					guard let self, self.generation == currentGeneration, self.activeSessionProvider() === session else {
						return
					}
					self.setRootNodes(nodes, status: L10n.string("\(nodes.count) scopes"), isError: false)
				}
			} catch {
				Task { @MainActor in
					guard let self, self.generation == currentGeneration, self.activeSessionProvider() === session else {
						return
					}
					self.setRootNodes([], status: String(describing: error), isError: true)
				}
			}
		}
	}

	private func setRootNodes(_ nodes: [DebugVariableNode], status: String, isError: Bool) {
		rootNodes = nodes
		outlineView?.reloadData()
		setStatus(status, isError: isError)
	}

	private func setStatus(_ status: String, isError: Bool) {
		statusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		statusLabel?.stringValue = status
	}

	func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
		guard let node = item as? DebugVariableNode else {
			return rootNodes.count
		}
		return node.children?.count ?? 0
	}

	func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
		guard let node = item as? DebugVariableNode else {
			return rootNodes[index]
		}
		return node.children?[index] ?? DebugVariableNode.placeholder
	}

	func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
		(item as? DebugVariableNode)?.variablesReference ?? 0 > 0
	}

	func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
		guard let node = item as? DebugVariableNode else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("VariableCell")
		let cell = outlineView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textField.lineBreakMode = .byTruncatingMiddle
		textField.textColor = canEdit(node) ? .labelColor : .secondaryLabelColor
		textField.stringValue = node.title
		if textField.superview == nil {
			textField.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(textField)
			NSLayoutConstraint.activate([
				textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 4),
				textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -4),
				textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
			cell.textField = textField
		}
		return cell
	}

	func outlineViewItemWillExpand(_ notification: Notification) {
		guard let node = notification.userInfo?["NSObject"] as? DebugVariableNode,
		      node.children == nil,
		      node.variablesReference > 0
		else {
			return
		}
		loadChildren(for: node)
	}

	private func loadChildren(for node: DebugVariableNode) {
		guard let session = activeSessionProvider() else {
			return
		}
		let reference = node.variablesReference
		node.children = []
		setStatus(L10n.string("Loading \(node.name)"), isError: false)
		Task(priority: .userInitiated) { [weak self, weak node] in
			do {
				let variables = try await session.debugSession.variables(for: reference)
				let children = variables.map { DebugVariableNode.variable($0, parentReference: reference) }
				Task { @MainActor in
					guard let self, let node, self.activeSessionProvider() === session else {
						return
					}
					node.children = children
					self.outlineView?.reloadItem(node, reloadChildren: true)
					self.outlineView?.expandItem(node)
					self.setStatus(L10n.string("\(children.count) variables"), isError: false)
				}
			} catch {
				Task { @MainActor in
					guard let self, let node, self.activeSessionProvider() === session else {
						return
					}
					node.children = nil
					self.outlineView?.reloadItem(node, reloadChildren: true)
					self.setStatus(String(describing: error), isError: true)
				}
			}
		}
	}

	@objc private func editSelectedVariable(_ sender: Any?) {
		guard let outlineView,
		      outlineView.selectedRow >= 0,
		      let node = outlineView.item(atRow: outlineView.selectedRow) as? DebugVariableNode,
		      canEdit(node),
		      let variable = node.variable,
		      let parentReference = node.parentReference,
		      let session = activeSessionProvider(),
		      let value = VariableEditPanel.value(for: variable)
		else {
			return
		}
		setStatus(L10n.string("Setting \(variable.name)"), isError: false)
		Task(priority: .userInitiated) { [weak self, weak node] in
			do {
				let updated = try await session.debugSession.setVariable(variablesReference: parentReference, name: variable.name, value: value)
				Task { @MainActor in
					guard let self, let node, self.activeSessionProvider() === session else {
						return
					}
					node.replaceVariable(updated)
					self.outlineView?.reloadItem(node, reloadChildren: true)
					self.setStatus(L10n.string("Set \(updated.name)"), isError: false)
				}
			} catch {
				Task { @MainActor in
					guard let self, self.activeSessionProvider() === session else {
						return
					}
					self.setStatus(String(describing: error), isError: true)
				}
			}
		}
	}

	private func canEdit(_ node: DebugVariableNode) -> Bool {
		activeSessionProvider()?.supportsSetVariable == true && node.parentReference != nil
	}
}

private enum DebugVariablesError: Error, CustomStringConvertible {
	case missingFocusedFrame

	var description: String {
		switch self {
		case .missingFocusedFrame:
			return L10n.string("Select a stack frame first")
		}
	}
}

private final class DebugVariableNode: NSObject {
	private enum Payload {
		case scope(DebugScope)
		case variable(DebugVariable, parentReference: Int)
		case placeholder
	}

	static var placeholder: DebugVariableNode {
		DebugVariableNode(payload: .placeholder)
	}

	var children: [DebugVariableNode]?
	private var payload: Payload

	private init(payload: Payload) {
		self.payload = payload
	}

	static func scope(_ scope: DebugScope) -> DebugVariableNode {
		DebugVariableNode(payload: .scope(scope))
	}

	static func variable(_ variable: DebugVariable, parentReference: Int) -> DebugVariableNode {
		DebugVariableNode(payload: .variable(variable, parentReference: parentReference))
	}

	var name: String {
		switch payload {
		case let .scope(scope):
			return scope.name
		case let .variable(variable, _):
			return variable.name
		case .placeholder:
			return ""
		}
	}

	var title: String {
		switch payload {
		case let .scope(scope):
			return scope.name
		case let .variable(variable, _):
			let type = variable.type.map { " : \($0)" } ?? ""
			return "\(variable.name) = \(variable.value)\(type)"
		case .placeholder:
			return ""
		}
	}

	var variablesReference: Int {
		switch payload {
		case let .scope(scope):
			return scope.variablesReference
		case let .variable(variable, _):
			return variable.variablesReference
		case .placeholder:
			return 0
		}
	}

	var parentReference: Int? {
		if case let .variable(_, parentReference) = payload {
			return parentReference
		}
		return nil
	}

	var variable: DebugVariable? {
		if case let .variable(variable, _) = payload {
			return variable
		}
		return nil
	}

	func replaceVariable(_ variable: DebugVariable) {
		guard let parentReference else {
			return
		}
		payload = .variable(variable, parentReference: parentReference)
		children = nil
	}
}

@MainActor private enum VariableEditPanel {
	static func value(for variable: DebugVariable) -> String? {
		let field = NSTextField(string: variable.value)
		field.frame = NSRect(x: 0, y: 0, width: 320, height: 24)
		let alert = NSAlert()
		alert.messageText = variable.name
		alert.accessoryView = field
		alert.addButton(withTitle: L10n.string("Set"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else {
			return nil
		}
		return field.stringValue
	}
}
