import AppKit
import Darwin
import Dispatch
import Foundation
import ItsyEditor
import ItsyRender
import ItsySyntax

final class ItsyDocumentController: NSDocumentController {
	override init() {
		super.init()
		ItsyTabCoordinator.install(documentController: self)
		ItsyWorkspaceController.install(documentController: self)
		ItsyProblemGutterCoordinator.install(documentController: self)
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		ItsyTabCoordinator.install(documentController: self)
		ItsyWorkspaceController.install(documentController: self)
		ItsyProblemGutterCoordinator.install(documentController: self)
	}

	override var defaultType: String? {
		"public.data"
	}

	@discardableResult
	func openDocument(at url: URL) -> Bool {
		openDocument(at: url, line: nil, column: nil)
	}

	@discardableResult
	func openDocument(at url: URL, line: Int?, column: Int?) -> Bool {
		let typeName = defaultType ?? "public.data"
		let document: ItsyDocument
		if let existing = self.document(for: url) as? ItsyDocument {
			document = existing
			showDocument(document)
			noteRecentDocumentIfNeeded(document)
		} else {
			do {
				guard let made = try makeDocument(withContentsOf: url, ofType: typeName) as? ItsyDocument else {
					return false
				}
				document = made
				addDocument(document)
				showDocument(document)
			} catch {
				NSLog("failed to open \(url.path): \(error)")
				return false
			}
		}
		if let line {
			document.jumpTo(line: line, column: column ?? 1)
		}
		return true
	}

	func showDocument(_ document: ItsyDocument) {
		if let controller = activeEditorWindowController() {
			controller.display(document: document)
		} else {
			document.makeWindowControllers()
			document.showWindows()
		}
	}

	override func addDocument(_ document: NSDocument) {
		super.addDocument(document)
		noteRecentDocumentIfNeeded(document)
		ItsyTabCoordinator.refresh()
	}

	override func removeDocument(_ document: NSDocument) {
		super.removeDocument(document)
		ItsyTabCoordinator.refresh()
	}

	override func makeUntitledDocument(ofType typeName: String) throws -> NSDocument {
		ItsyDocument()
	}

	override func makeDocument(withContentsOf url: URL, ofType typeName: String) throws -> NSDocument {
		try ItsyDocument(contentsOf: url, ofType: typeName)
	}

	private func noteRecentDocumentIfNeeded(_ document: NSDocument) {
		guard let url = document.fileURL else {
			return
		}
		noteNewRecentDocument(document)
		noteNewRecentDocumentURL(url)
	}

	private func activeEditorWindowController() -> EditorWindowController? {
		if let controller = NSApp.keyWindow?.windowController as? EditorWindowController {
			return controller
		}
		return documents.lazy.compactMap { document in
			(document as? ItsyDocument)?.windowControllers.first as? EditorWindowController
		}.first
	}
}

final class ItsyDocument: NSDocument {
	static let handoffActivityType = "dev.itsy.editor.open-file"
	static let handoffURLKey = "url"
	static let handoffCursorOffsetKey = "cursorOffset"

	var editor = Editor()
	private var editorViews: [MetalTextView] = []
	private var syntaxPipeline: SyntaxPipeline?
	private var syntaxTheme: SyntaxTheme?
	private var syntaxTree: Tree?
	private var handoffActivity: NSUserActivity?
	private var syntaxHighlightSpans: [HighlightSpan] = []
	private let fileWatcherQueue = DispatchQueue(label: "dev.itsy.editor.file-watcher")
	private var fileWatchSource: DispatchSourceFileSystemObject?
	private var pendingExternalChangePrompt = false

	override var fileURL: URL? {
		didSet {
			configureSyntaxPipeline()
			updateHandoffActivity()
			restartFileWatcher()
		}
	}

	override init() {
		super.init()
	}

	deinit {
		stopFileWatcher()
	}

	override class var autosavesInPlace: Bool {
		true
	}

	override class var preservesVersions: Bool {
		true
	}

	override func updateChangeCount(_ change: NSDocument.ChangeType) {
		super.updateChangeCount(change)
		ItsyTabCoordinator.refresh()
	}

	override func read(from data: Data, ofType typeName: String) throws {
		guard let text = String(data: data, encoding: .utf8) else {
			throw CocoaError(.fileReadCorruptFile)
		}
		editor = Editor(text: text)
		for view in editorViews {
			view.editor = editor
		}
		configureSyntaxPipeline()
		refreshSyntaxHighlights()
		updateHandoffActivity()
		restartFileWatcher()
	}

	override func data(ofType typeName: String) throws -> Data {
		return Data(editor.text.utf8)
	}

	override func makeWindowControllers() {
		let controller = EditorWindowController(document: self)
		addWindowController(controller)
	}

	func attach(_ view: MetalTextView) {
		if !editorViews.contains(where: { $0 === view }) {
			editorViews.append(view)
		}
		view.editor = editor
		configureSyntaxPipeline()
		refreshSyntaxHighlights()
		view.editorDidChange = { [weak self, weak view] editor in
			guard let self, let view else {
				return
			}
			let oldRope = self.editor.rope
			let edits = editor.lastEditBatch
			self.editor = editor
			self.refreshSyntaxHighlights(edits: edits, oldRope: oldRope)
			self.updateHandoffActivity()
			self.syncSiblingEditorViews(source: view, editor: editor)
			self.updateChangeCount(.changeDone)
		}
		view.saveRequested = { [weak self] in
			self?.save(nil)
		}
		view.closeRequested = { [weak self] in
			self?.close()
		}
		ItsyProblemGutterCoordinator.apply(to: self)
		restartFileWatcher()
		updateHandoffActivity()
	}

	func detach(_ view: MetalTextView) {
		editorViews.removeAll { $0 === view }
	}

	func setGutterDecorator(_ decorator: GutterDecorator?) {
		for view in editorViews {
			view.gutterDecorator = decorator
		}
	}

	func reloadSyntaxTheme() {
		syntaxTheme = nil
		refreshSyntaxHighlights()
	}

	func restoreHandoffCursorOffset(_ offset: Int) {
		let clamped = min(max(offset, 0), editor.rope.length)
		editor.setSelection(SelectionSet(primary: Selection(anchor: clamped, head: clamped)))
		for view in editorViews {
			view.editor = editor
		}
		updateHandoffActivity()
	}

	func jumpTo(line: Int, column: Int) {
		let zeroLine = max(0, line - 1)
		let zeroColumn = max(0, column - 1)
		let rope = editor.rope
		guard zeroLine < rope.lineCount else {
			return
		}
		let lineRange = rope.lineRange(zeroLine)
		let offset = min(lineRange.upperBound, lineRange.lowerBound + zeroColumn)
		editor.setSelection(SelectionSet(primary: Selection(anchor: offset, head: offset)))
		for view in editorViews {
			view.editor = editor
		}
		updateHandoffActivity()
	}

	private func updateHandoffActivity() {
		guard let url = fileURL else {
			handoffActivity?.invalidate()
			handoffActivity = nil
			return
		}
		let activity = handoffActivity ?? NSUserActivity(activityType: Self.handoffActivityType)
		activity.title = displayName
		activity.isEligibleForHandoff = true
		activity.userInfo = [
			Self.handoffURLKey: url.absoluteString,
			Self.handoffCursorOffsetKey: editor.selections.primary.head,
		]
		activity.becomeCurrent()
		handoffActivity = activity
		for controller in windowControllers {
			controller.window?.userActivity = activity
		}
	}

	private func syncSiblingEditorViews(source: MetalTextView, editor: Editor) {
		for view in editorViews where view !== source {
			var siblingEditor = editor
			siblingEditor.selections = view.editor.selections
			view.editor = siblingEditor
		}
	}

	private func configureSyntaxPipeline() {
		guard let url = fileURL, let language = SyntaxPipeline.language(forFileURL: url) else {
			syntaxPipeline = nil
			syntaxTree = nil
			setHighlightSpans([])
			return
		}
		if syntaxPipeline?.language != language {
			syntaxPipeline = SyntaxPipeline(language: language)
			syntaxTree = nil
		}
	}

	private func refreshSyntaxHighlights(edits: [Edit] = [], oldRope: Rope? = nil) {
		guard var syntaxPipeline else {
			setHighlightSpans([])
			return
		}
		defer {
			self.syntaxPipeline = syntaxPipeline
		}
		do {
			if syntaxTheme == nil {
				syntaxTheme = try SyntaxTheme.loadUserOrDefault()
			}
			let spans: [HighlightSpan]
			if edits.count == 1, let edit = edits.first, isSingleLineEdit(edit), let oldRope, let tree = syntaxTree {
				let inputEdit = InputEdit(edit: edit, oldRope: oldRope, newRope: editor.rope)
				tree.edit(inputEdit)
				let newTree = try syntaxPipeline.parse(editor.rope, oldTree: tree)
				syntaxTree = newTree
				let dirtyRange = dirtyLineRange(containing: inputEdit.newEndByte)
				let dirtySpans = try syntaxPipeline.highlights(in: newTree, byteRange: dirtyRange)
				syntaxHighlightSpans = syntaxHighlightSpans.compactMap { $0.mapped(through: edit) }
				syntaxHighlightSpans.removeAll { $0.range.overlaps(dirtyRange) }
				syntaxHighlightSpans += dirtySpans
				spans = syntaxHighlightSpans
			} else {
				let tree = try syntaxPipeline.parse(editor.rope)
				syntaxTree = tree
				spans = try syntaxPipeline.highlights(in: tree)
				syntaxHighlightSpans = spans
			}
			let renderedSpans = spans.compactMap { span -> TextHighlightSpan? in
				guard let color = syntaxTheme?.color(for: span.capture) else {
					return nil
				}
				return TextHighlightSpan(range: span.range, color: SIMD4<Float>(color.red, color.green, color.blue, color.alpha))
			}
			setHighlightSpans(renderedSpans)
		} catch {
			setHighlightSpans([])
		}
	}

	private func setHighlightSpans(_ spans: [TextHighlightSpan]) {
		for view in editorViews {
			view.highlightSpans = spans
		}
	}

	private func dirtyLineRange(containing offset: Int) -> Range<Int> {
		let line = editor.rope.line(forOffset: min(offset, editor.rope.length))
		return editor.rope.lineRange(line)
	}

	private func isSingleLineEdit(_ edit: Edit) -> Bool {
		!edit.oldText.utf8.contains(10) && !edit.newText.utf8.contains(10)
	}

	private func restartFileWatcher() {
		stopFileWatcher()
		guard let url = fileURL, url.isFileURL else {
			return
		}
		let descriptor = open(url.path, O_EVTONLY)
		guard descriptor >= 0 else {
			return
		}
		let source = DispatchSource.makeFileSystemObjectSource(
			fileDescriptor: descriptor,
			eventMask: [.write, .delete, .rename, .extend],
			queue: fileWatcherQueue
		)
		source.setEventHandler { [weak self] in
			DispatchQueue.main.async {
				self?.externalFileDidChange()
			}
		}
		source.setCancelHandler {
			Darwin.close(descriptor)
		}
		fileWatchSource = source
		source.resume()
	}

	private func stopFileWatcher() {
		fileWatchSource?.cancel()
		fileWatchSource = nil
	}

	private func externalFileDidChange() {
		guard !pendingExternalChangePrompt, let url = fileURL else {
			return
		}
		pendingExternalChangePrompt = true
		DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
			self?.promptForExternalFileChange(at: url)
		}
	}

	private func promptForExternalFileChange(at url: URL) {
		if (try? String(contentsOf: url, encoding: .utf8)) == editor.text {
			pendingExternalChangePrompt = false
			restartFileWatcher()
			return
		}
		let alert = NSAlert()
		alert.messageText = L10n.string("\(displayName ?? url.lastPathComponent) changed on disk")
		alert.informativeText = L10n.string("Reload the file from disk?")
		alert.addButton(withTitle: L10n.string("Reload"))
		alert.addButton(withTitle: L10n.string("Keep Editing"))
		if let window = windowControllers.first?.window {
			alert.beginSheetModal(for: window) { [weak self] response in
				self?.handleExternalFilePrompt(response, url: url)
			}
		} else {
			handleExternalFilePrompt(alert.runModal(), url: url)
		}
	}

	private func handleExternalFilePrompt(_ response: NSApplication.ModalResponse, url: URL) {
		if response == .alertFirstButtonReturn {
			reloadFromDisk(at: url)
		}
		pendingExternalChangePrompt = false
		restartFileWatcher()
	}

	private func reloadFromDisk(at url: URL) {
		do {
			let data = try Data(contentsOf: url)
			try read(from: data, ofType: fileType ?? "public.data")
			updateChangeCount(.changeCleared)
		} catch {
			presentError(error)
		}
	}
}

struct EditorPane {
	let viewController: NSViewController
	let editorView: MetalTextView

	init() {
		viewController = NSViewController()
		editorView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		viewController.view = editorView
	}
}

private struct EditorPaneLayout: Equatable {
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
		let pane = EditorPane()
		panes = [pane]
		rootSplitViewController.splitView.dividerStyle = .thin
		rootSplitViewController.addSplitViewItem(NSSplitViewItem(viewController: pane.viewController))
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

	fileprivate func layout() -> EditorPaneLayout {
		layout(for: rootSplitViewController)
	}

	fileprivate mutating func restore(layout: EditorPaneLayout) -> [EditorPane] {
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

final class EditorWindowController: NSWindowController {
	private static let paneLayoutStateKey = "dev.itsy.editor.paneLayout"
	private static let quickLookPanelSelector = NSSelectorFromString("sharedPreviewPanel")
	private static let quickLookDataSourceSelector = NSSelectorFromString("setDataSource:")
	private static let quickLookDelegateSelector = NSSelectorFromString("setDelegate:")
	private static let quickLookReloadSelector = NSSelectorFromString("reloadData")
	private static let quickLookOrderFrontSelector = NSSelectorFromString("makeKeyAndOrderFront:")
	private static let quickLookFrameworkPaths = [
		"/System/Library/Frameworks/QuickLookUI.framework/QuickLookUI",
		"/System/Library/Frameworks/QuickLookUI.framework/Versions/A/QuickLookUI",
	]
	private static var quickLookHandle: UnsafeMutableRawPointer?
	private let fileTreeView = NSView(frame: NSRect(x: 0, y: 0, width: 240, height: 672))
	private let fileTreeOutlineView = NSOutlineView()
	private let fileTreeScrollView = NSScrollView()
	private let tabBarView = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 32))
	private let tabScrollView = NSScrollView()
	private let tabStackView = NSStackView()
	private let findBarView = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 38))
	private let findQueryField = NSTextField(frame: .zero)
	private let findReplaceField = NSTextField(frame: .zero)
	private let findRegexButton = NSButton(checkboxWithTitle: L10n.string("Regex"), target: nil, action: nil)
	private let findCaseButton = NSButton(checkboxWithTitle: L10n.string("Case"), target: nil, action: nil)
	private let findWholeWordButton = NSButton(checkboxWithTitle: L10n.string("Word"), target: nil, action: nil)
	private let findCloseButton = NSButton(title: L10n.string("X"), target: nil, action: nil)
	private let statusBarView = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 18))
	private let statusBarLabel = NSTextField(labelWithString: "")
	private var paneCoordinator = EditorPaneCoordinator()
	private var editorView: MetalTextView {
		paneCoordinator.activePane.editorView
	}
	private var fileTreeRootURL: NSURL?
	private var fileTreeChildCache: [NSURL: [NSURL]] = [:]
	private var gitSnapshot: GitWorkspaceSnapshot?
	private var fileTreeKeyMonitor: Any?
	private var fileTreePreviewURL: URL?
	private var tabIDsByTag: [Int: ObjectIdentifier] = [:]
	private var tabBoundsObserver: NSObjectProtocol?
	private var findKeyMonitor: Any?
	private var findMatches: [Range<Int>] = []
	private var selectedFindMatchIndex: Int?
	private var incrementalFindDirection: Int?

	init(document: ItsyDocument) {
		let editorContainer = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		let editorStack = NSStackView(frame: NSRect(x: 240, y: 0, width: 960, height: 672))
		editorStack.orientation = .vertical
		editorStack.alignment = .width
		editorStack.distribution = .fill
		editorStack.spacing = 0
		Self.configureFileTreeView(fileTreeView, outlineView: fileTreeOutlineView, scrollView: fileTreeScrollView)
		Self.configureTabBarView(tabBarView, scrollView: tabScrollView, stackView: tabStackView)
		Self.configureFindBarView(
			findBarView,
			queryField: findQueryField,
			replaceField: findReplaceField,
			regexButton: findRegexButton,
			caseButton: findCaseButton,
			wholeWordButton: findWholeWordButton,
			closeButton: findCloseButton
		)
		Self.configureStatusBarView(statusBarView, label: statusBarLabel)
		tabBarView.setContentHuggingPriority(.required, for: .vertical)
		statusBarView.setContentHuggingPriority(.required, for: .vertical)
		statusBarView.isHidden = true
		editorContainer.setContentHuggingPriority(.defaultLow, for: .vertical)
		editorContainer.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
		paneCoordinator.view.translatesAutoresizingMaskIntoConstraints = false
		findBarView.translatesAutoresizingMaskIntoConstraints = false
		findBarView.isHidden = true
		editorContainer.addSubview(paneCoordinator.view)
		editorContainer.addSubview(findBarView)
		NSLayoutConstraint.activate([
			paneCoordinator.view.leadingAnchor.constraint(equalTo: editorContainer.leadingAnchor),
			paneCoordinator.view.trailingAnchor.constraint(equalTo: editorContainer.trailingAnchor),
			paneCoordinator.view.topAnchor.constraint(equalTo: editorContainer.topAnchor),
			paneCoordinator.view.bottomAnchor.constraint(equalTo: editorContainer.bottomAnchor),
			findBarView.leadingAnchor.constraint(equalTo: editorContainer.leadingAnchor),
			findBarView.trailingAnchor.constraint(equalTo: editorContainer.trailingAnchor),
			findBarView.topAnchor.constraint(equalTo: editorContainer.topAnchor),
		])
		editorStack.addArrangedSubview(tabBarView)
		editorStack.addArrangedSubview(editorContainer)
		editorStack.addArrangedSubview(statusBarView)

		let splitView = NSSplitView(frame: NSRect(x: 0, y: 0, width: 1200, height: 672))
		splitView.isVertical = true
		splitView.dividerStyle = .thin
		splitView.autoresizingMask = [.width, .height]
		fileTreeView.translatesAutoresizingMaskIntoConstraints = false
		editorStack.translatesAutoresizingMaskIntoConstraints = false
		splitView.addArrangedSubview(fileTreeView)
		splitView.addArrangedSubview(editorStack)
		fileTreeView.widthAnchor.constraint(equalToConstant: 240).isActive = true
		let window = NSWindow(
			contentRect: splitView.frame,
			styleMask: [.titled, .closable, .miniaturizable, .resizable],
			backing: .buffered,
			defer: false
		)
		window.title = document.fileURL?.lastPathComponent ?? L10n.string("Untitled")
		window.isRestorable = true
		window.contentView = splitView
		super.init(window: window)
		installFileTreeActions()
		installTabBoundsObserver()
		installFindBarActions()
		window.delegate = self
		installPane(paneCoordinator.activePane, document: document)
		ItsyWorkspaceController.register(self)
		ItsyTabCoordinator.register(self)
		window.makeFirstResponder(editorView)
	}

	required init?(coder: NSCoder) {
		nil
	}

	deinit {
		if let fileTreeKeyMonitor {
			NSEvent.removeMonitor(fileTreeKeyMonitor)
		}
		if let findKeyMonitor {
			NSEvent.removeMonitor(findKeyMonitor)
		}
		if let tabBoundsObserver {
			NotificationCenter.default.removeObserver(tabBoundsObserver)
		}
	}

	private static func configureFileTreeView(_ fileTreeView: NSView, outlineView: NSOutlineView, scrollView: NSScrollView) {
		fileTreeView.wantsLayer = true
		fileTreeView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("name"))
		column.title = L10n.string("Files")
		column.resizingMask = .autoresizingMask
		outlineView.addTableColumn(column)
		outlineView.outlineTableColumn = column
		outlineView.headerView = nil
		outlineView.rowSizeStyle = .small
		outlineView.usesAlternatingRowBackgroundColors = false

		scrollView.documentView = outlineView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		fileTreeView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: fileTreeView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: fileTreeView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: fileTreeView.topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: fileTreeView.bottomAnchor),
		])
	}

	private func installFileTreeActions() {
		fileTreeOutlineView.dataSource = self
		fileTreeOutlineView.delegate = self
		fileTreeOutlineView.target = self
		fileTreeOutlineView.doubleAction = #selector(doubleClickFileTree(_:))
		fileTreeKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
			guard let self,
			      event.keyCode == 49,
			      self.window?.isKeyWindow == true,
			      self.window?.firstResponder === self.fileTreeOutlineView
			else {
				return event
			}
			self.previewFileTreeSelection()
			return nil
		}
	}

	func setWorkspaceRootURL(_ url: URL?) {
		fileTreeRootURL = url.map { $0 as NSURL }
		fileTreeChildCache.removeAll(keepingCapacity: true)
		fileTreeOutlineView.reloadData()
		if let fileTreeRootURL {
			fileTreeOutlineView.expandItem(fileTreeRootURL)
		}
	}

	func setGitSnapshot(_ snapshot: GitWorkspaceSnapshot?) {
		gitSnapshot = snapshot
		fileTreeOutlineView.reloadData()
	}

	func setIndexingStatus(_ text: String?) {
		if let text, !text.isEmpty {
			statusBarLabel.stringValue = text
			statusBarView.isHidden = false
		} else {
			statusBarLabel.stringValue = ""
			statusBarView.isHidden = true
		}
	}

	@objc private func doubleClickFileTree(_ sender: Any?) {
		let row = fileTreeOutlineView.clickedRow
		guard row >= 0, let url = fileTreeOutlineView.item(atRow: row) as? NSURL else {
			return
		}
		if isFileTreeDirectory(url) {
			if fileTreeOutlineView.isItemExpanded(url) {
				fileTreeOutlineView.collapseItem(url)
			} else {
				fileTreeOutlineView.expandItem(url)
			}
			return
		}
		_ = ItsyWorkspaceController.openFile(at: url as URL)
	}

	private func previewFileTreeSelection() {
		let row = fileTreeOutlineView.selectedRow
		guard row >= 0,
		      let url = fileTreeOutlineView.item(atRow: row) as? NSURL,
		      !isFileTreeDirectory(url)
		else {
			return
		}
		preview(url as URL)
	}

	private func isFileTreeDirectory(_ url: NSURL) -> Bool {
		let values = try? (url as URL).resourceValues(forKeys: [.isDirectoryKey])
		return values?.isDirectory == true
	}

	private func fileTreeTitle(for url: NSURL) -> String {
		let fileURL = url as URL
		let title = fileURL.lastPathComponent.isEmpty ? fileURL.path : fileURL.lastPathComponent
		guard let status = fileTreeGitStatus(for: fileURL) else {
			return title
		}
		return "\(title) [\(status)]"
	}

	private func fileTreeGitStatus(for url: URL) -> String? {
		guard let entry = gitSnapshot?.entry(for: url) else {
			return nil
		}
		if entry.kind == .untracked {
			return "?"
		}
		if entry.kind == .unmerged {
			return "U"
		}
		if entry.isStaged, entry.isUnstaged {
			return "*"
		}
		if entry.isStaged {
			return entry.indexStatus.map(String.init)
		}
		if entry.isUnstaged {
			return entry.worktreeStatus.map(String.init)
		}
		return nil
	}

	private func fileTreeChildren(of url: NSURL) -> [NSURL] {
		guard isFileTreeDirectory(url) else {
			return []
		}
		if let cached = fileTreeChildCache[url] {
			return cached
		}
		let keys: [URLResourceKey] = [.isDirectoryKey, .isHiddenKey]
		let contents = (try? FileManager.default.contentsOfDirectory(
			at: url as URL,
			includingPropertiesForKeys: keys,
			options: [.skipsPackageDescendants]
		)) ?? []
		let nodes = contents.compactMap { child -> NSURL? in
			let values = try? child.resourceValues(forKeys: Set(keys))
			if values?.isHidden == true {
				return nil
			}
			return child as NSURL
		}
		let sorted = nodes.sorted { lhs, rhs in
			let lhsDirectory = isFileTreeDirectory(lhs)
			let rhsDirectory = isFileTreeDirectory(rhs)
			if lhsDirectory != rhsDirectory {
				return lhsDirectory && !rhsDirectory
			}
			return fileTreeTitle(for: lhs).localizedStandardCompare(fileTreeTitle(for: rhs)) == .orderedAscending
		}
		fileTreeChildCache[url] = sorted
		return sorted
	}

	func preview(_ url: URL) {
		fileTreePreviewURL = url
		guard let panel = sharedQuickLookPanel() else {
			return
		}
		_ = panel.perform(Self.quickLookDataSourceSelector, with: self)
		_ = panel.perform(Self.quickLookDelegateSelector, with: self)
		_ = panel.perform(Self.quickLookReloadSelector)
		_ = panel.perform(Self.quickLookOrderFrontSelector, with: nil)
	}

	@objc(numberOfPreviewItemsInPreviewPanel:)
	func numberOfPreviewItems(in panel: AnyObject) -> Int {
		fileTreePreviewURL == nil ? 0 : 1
	}

	@objc(previewPanel:previewItemAtIndex:)
	func previewPanel(_ panel: AnyObject, previewItemAt index: Int) -> AnyObject? {
		fileTreePreviewURL as NSURL?
	}

	private func sharedQuickLookPanel() -> NSObject? {
		ensureQuickLookLoaded()
		guard let panelClass = NSClassFromString("QLPreviewPanel") as AnyObject?,
		      panelClass.responds(to: Self.quickLookPanelSelector)
		else {
			return nil
		}
		return panelClass.perform(Self.quickLookPanelSelector)?.takeUnretainedValue() as? NSObject
	}

	private func ensureQuickLookLoaded() {
		guard Self.quickLookHandle == nil, NSClassFromString("QLPreviewPanel") == nil else {
			return
		}
		for path in Self.quickLookFrameworkPaths {
			if let handle = dlopen(path, RTLD_LAZY | RTLD_LOCAL) {
				Self.quickLookHandle = handle
				return
			}
		}
	}

	private static func configureTabBarView(_ tabBarView: NSView, scrollView: NSScrollView, stackView: NSStackView) {
		tabBarView.wantsLayer = true
		tabBarView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
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
		tabBarView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: tabBarView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: tabBarView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: tabBarView.topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: tabBarView.bottomAnchor),
			tabBarView.heightAnchor.constraint(equalToConstant: 32),
		])
	}

	private func installTabBoundsObserver() {
		tabBoundsObserver = NotificationCenter.default.addObserver(
			forName: NSView.boundsDidChangeNotification,
			object: tabScrollView.contentView,
			queue: nil
		) { [weak self] _ in
			self?.layoutTabContent()
		}
	}

	func setTabs(_ tabs: [ItsyTab]) {
		for view in tabStackView.arrangedSubviews {
			tabStackView.removeArrangedSubview(view)
			view.removeFromSuperview()
		}
		tabIDsByTag = Dictionary(uniqueKeysWithValues: tabs.enumerated().map { index, tab in (index, tab.id) })
		for (index, tab) in tabs.enumerated() {
			tabStackView.addArrangedSubview(makeTabView(tab, tag: index))
		}
		layoutTabContent()
	}

	private func layoutTabContent() {
		tabStackView.layoutSubtreeIfNeeded()
		let fit = tabStackView.fittingSize
		let height = max(tabBarView.bounds.height, 32)
		let width = max(tabScrollView.contentView.bounds.width, fit.width)
		tabStackView.frame = NSRect(x: 0, y: 0, width: width, height: height)
		tabScrollView.documentView = tabStackView
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
		ItsyTabCoordinator.selectDocument(tabID)
	}

	@objc private func closeTab(_ sender: NSButton) {
		guard let tabID = tabIDsByTag[sender.tag] else {
			return
		}
		ItsyTabCoordinator.closeDocument(tabID)
	}

	private static func configureFindBarView(
		_ findBarView: NSView,
		queryField: NSTextField,
		replaceField: NSTextField,
		regexButton: NSButton,
		caseButton: NSButton,
		wholeWordButton: NSButton,
		closeButton: NSButton
	) {
		findBarView.wantsLayer = true
		findBarView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		findBarView.layer?.borderWidth = 1
		findBarView.layer?.borderColor = NSColor.separatorColor.cgColor

		queryField.placeholderString = L10n.string("Find")
		replaceField.placeholderString = L10n.string("Replace")
		for field in [queryField, replaceField] {
			field.font = .systemFont(ofSize: 12)
		}
		for button in [regexButton, caseButton, wholeWordButton] {
			button.font = .systemFont(ofSize: 11)
			button.setContentCompressionResistancePriority(.required, for: .horizontal)
		}
		closeButton.isBordered = false
		closeButton.font = .systemFont(ofSize: 11)
		closeButton.toolTip = L10n.string("Close")
		closeButton.setContentCompressionResistancePriority(.required, for: .horizontal)

		let stack = NSStackView()
		stack.orientation = .horizontal
		stack.alignment = .centerY
		stack.spacing = 8
		stack.edgeInsets = NSEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
		stack.translatesAutoresizingMaskIntoConstraints = false
		findBarView.addSubview(stack)

		queryField.widthAnchor.constraint(greaterThanOrEqualToConstant: 180).isActive = true
		replaceField.widthAnchor.constraint(greaterThanOrEqualToConstant: 180).isActive = true
		stack.addArrangedSubview(queryField)
		stack.addArrangedSubview(replaceField)
		stack.addArrangedSubview(regexButton)
		stack.addArrangedSubview(caseButton)
		stack.addArrangedSubview(wholeWordButton)
		stack.addArrangedSubview(closeButton)

		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: findBarView.leadingAnchor),
			stack.trailingAnchor.constraint(equalTo: findBarView.trailingAnchor),
			stack.topAnchor.constraint(equalTo: findBarView.topAnchor),
			stack.bottomAnchor.constraint(equalTo: findBarView.bottomAnchor),
			findBarView.heightAnchor.constraint(equalToConstant: 38),
		])
	}

	private static func configureStatusBarView(_ statusBarView: NSView, label: NSTextField) {
		statusBarView.wantsLayer = true
		statusBarView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		label.font = .systemFont(ofSize: 11)
		label.textColor = .secondaryLabelColor
		label.lineBreakMode = .byTruncatingTail
		label.translatesAutoresizingMaskIntoConstraints = false
		statusBarView.addSubview(label)
		NSLayoutConstraint.activate([
			label.leadingAnchor.constraint(equalTo: statusBarView.leadingAnchor, constant: 10),
			label.trailingAnchor.constraint(lessThanOrEqualTo: statusBarView.trailingAnchor, constant: -10),
			label.centerYAnchor.constraint(equalTo: statusBarView.centerYAnchor),
			statusBarView.heightAnchor.constraint(equalToConstant: 20),
		])
	}

	private func installFindBarActions() {
		for field in [findQueryField, findReplaceField] {
			field.delegate = self
		}
		findKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
			guard let self,
			      self.window?.isKeyWindow == true,
			      self.isFindFieldEditing,
			      event.modifierFlags.contains(.control)
			else {
				return event
			}
			if event.charactersIgnoringModifiers == "s" {
				self.selectFindMatchFromFindBar(direction: 1)
				return nil
			}
			if event.charactersIgnoringModifiers == "r" {
				self.selectFindMatchFromFindBar(direction: -1)
				return nil
			}
			return event
		}
		for button in [findRegexButton, findCaseButton, findWholeWordButton] {
			button.target = self
			button.action = #selector(findOptionChanged(_:))
		}
		findCloseButton.target = self
		findCloseButton.action = #selector(closeFindBar(_:))
	}

	private var isFindFieldEditing: Bool {
		guard let firstResponder = window?.firstResponder else {
			return false
		}
		return firstResponder === findQueryField.currentEditor() || firstResponder === findReplaceField.currentEditor()
	}

	private var findState: FindBarState {
		FindBarState(
			query: findQueryField.stringValue,
			replacement: findReplaceField.stringValue,
			usesRegex: findRegexButton.state == .on,
			isCaseSensitive: findCaseButton.state == .on,
			matchesWholeWord: findWholeWordButton.state == .on
		)
	}

	private func focusFindQuery() {
		window?.makeFirstResponder(findQueryField)
		findQueryField.currentEditor()?.selectAll(nil)
	}

	private func findRegularExpression() -> NSRegularExpression? {
		let state = findState
		guard !state.query.isEmpty else {
			return nil
		}
		let basePattern = state.usesRegex ? state.query : NSRegularExpression.escapedPattern(for: state.query)
		let pattern = state.matchesWholeWord ? "\\b(?:\(basePattern))\\b" : basePattern
		let options: NSRegularExpression.Options = state.isCaseSensitive ? [] : [.caseInsensitive]
		return try? NSRegularExpression(pattern: pattern, options: options)
	}

	@objc private func closeFindBar(_ sender: Any?) {
		setFindBarVisible(false)
	}

	@objc private func findOptionChanged(_ sender: Any?) {
		findStateDidChange()
	}

	override func windowDidLoad() {
		super.windowDidLoad()
		window?.center()
	}

	override func showWindow(_ sender: Any?) {
		super.showWindow(sender)
		window?.makeKeyAndOrderFront(sender)
		window?.orderFrontRegardless()
		focusEditor()
		ItsyTabCoordinator.refresh()
	}

	func display(document newDocument: ItsyDocument) {
		if self.document as? ItsyDocument === newDocument {
			showWindow(nil)
			focusEditor()
			ItsyTabCoordinator.refresh()
			return
		}
		if let oldDocument = self.document as? ItsyDocument {
			for pane in paneCoordinator.panes {
				oldDocument.detach(pane.editorView)
			}
			oldDocument.removeWindowController(self)
		}
		if !newDocument.windowControllers.contains(where: { $0 === self }) {
			newDocument.addWindowController(self)
		}
		for pane in paneCoordinator.panes {
			installPane(pane, document: newDocument)
		}
		window?.title = newDocument.fileURL?.lastPathComponent ?? newDocument.displayName
		window?.representedURL = newDocument.fileURL
		showWindow(nil)
		focusEditor()
		ItsyTabCoordinator.refresh()
	}

	override func encodeRestorableState(with coder: NSCoder) {
		super.encodeRestorableState(with: coder)
		coder.encode(paneCoordinator.layout().encoded, forKey: Self.paneLayoutStateKey)
	}

	override func restoreState(with coder: NSCoder) {
		super.restoreState(with: coder)
		guard
			let string = coder.decodeObject(forKey: Self.paneLayoutStateKey) as? String,
			let layout = EditorPaneLayout.decode(string),
			let document = document as? ItsyDocument
		else {
			return
		}
		for pane in paneCoordinator.panes {
			document.detach(pane.editorView)
		}
		for pane in paneCoordinator.restore(layout: layout) {
			installPane(pane, document: document)
		}
		focusEditor()
	}

	func focusEditor() {
		window?.makeFirstResponder(editorView)
	}

	private func installPane(_ pane: EditorPane, document: ItsyDocument) {
		let view = pane.editorView
		document.attach(view)
		view.keymapEngine = ItsyAppKeymap.makeEngine()
		view.commandRequested = { [weak self] commandID in
			self?.performKeymapCommand(commandID) ?? false
		}
		view.exCommandRequested = { [weak self] command in
			self?.performExCommand(command) ?? false
		}
		view.exCommandLineRequested = { [weak self] completion in
			ItsyCommandPaletteBridge.requestExCommand(relativeTo: self?.window, completion: completion)
		}
	}

	private func splitActivePane(vertical: Bool) {
		guard let document = document as? ItsyDocument else {
			return
		}
		let pane = paneCoordinator.splitActive(vertical: vertical)
		installPane(pane, document: document)
		focusEditor()
	}

	private func closeActivePane() -> Bool {
		guard let document = document as? ItsyDocument, let pane = paneCoordinator.closeActive() else {
			return false
		}
		document.detach(pane.editorView)
		focusEditor()
		return true
	}

	private func closeOtherPanes() {
		guard let document = document as? ItsyDocument else {
			return
		}
		for pane in paneCoordinator.closeOtherPanes() {
			document.detach(pane.editorView)
		}
		focusEditor()
	}

	private func focusAdjacentPane(delta: Int) {
		_ = paneCoordinator.focusAdjacent(delta: delta)
		focusEditor()
	}

	func performEditorMotion(_ motion: Motion) {
		editorView.performMotion(motion)
		focusEditor()
	}

	func toggleFindBar() {
		setFindBarVisible(findBarView.isHidden)
	}

	func findNext() {
		selectFindMatch(direction: 1)
	}

	func findPrevious() {
		selectFindMatch(direction: -1)
	}

	func startIncrementalSearch(direction: Int) {
		incrementalFindDirection = direction
		setFindBarVisible(true)
		selectFindMatch(direction: direction, focusEditorAfterSelection: false)
	}

	func selectAllFindMatches() {
		guard !findBarView.isHidden else {
			setFindBarVisible(true)
			return
		}
		refreshFindMatches()
		guard !findMatches.isEmpty else {
			return
		}
		selectedFindMatchIndex = nil
		editorView.selectUTF8Ranges(findMatches)
		focusEditor()
	}

	private func performKeymapCommand(_ commandID: String) -> Bool {
		switch commandID {
		case "file.open":
			NSDocumentController.shared.openDocument(nil)
		case "file.nextBuffer":
			ItsyTabCoordinator.selectAdjacentDocument(delta: 1)
		case "pane.close":
			if !closeActivePane() {
				(document as? NSDocument)?.close()
			}
		case "pane.closeOthers":
			closeOtherPanes()
		case "pane.splitHorizontal":
			splitActivePane(vertical: false)
		case "pane.splitVertical":
			splitActivePane(vertical: true)
		case "pane.focusRight", "pane.focusDown", "pane.focusNext":
			focusAdjacentPane(delta: 1)
		case "pane.focusLeft", "pane.focusUp", "pane.focusPrevious":
			focusAdjacentPane(delta: -1)
		case "edit.find":
			toggleFindBar()
		case "edit.findNext":
			findNext()
		case "edit.findPrevious":
			findPrevious()
		case "vim.searchForward":
			startIncrementalSearch(direction: 1)
		case "vim.searchBackward":
			startIncrementalSearch(direction: -1)
		case "emacs.isearchForward":
			startIncrementalSearch(direction: 1)
		case "emacs.isearchBackward":
			startIncrementalSearch(direction: -1)
		case "edit.selectAllFindMatches":
			selectAllFindMatches()
		default:
			return false
		}
		return true
	}

	private func performExCommand(_ command: String) -> Bool {
		switch command {
		case "w":
			(document as? NSDocument)?.save(nil)
		case "q":
			(document as? NSDocument)?.close()
		case "wq", "x":
			(document as? NSDocument)?.save(nil)
			(document as? NSDocument)?.close()
		case "bn":
			ItsyTabCoordinator.selectAdjacentDocument(delta: 1)
		case "bp":
			ItsyTabCoordinator.selectAdjacentDocument(delta: -1)
		default:
			if command.hasPrefix("e ") {
				return openExCommandPath(String(command.dropFirst(2)))
			}
			return false
		}
		return true
	}

	private func openExCommandPath(_ path: String) -> Bool {
		let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !trimmed.isEmpty else {
			return false
		}
		let url: URL
		if trimmed.hasPrefix("/") {
			url = URL(fileURLWithPath: trimmed)
		} else {
			let base = document?.fileURL?.deletingLastPathComponent() ?? ItsyWorkspaceController.currentRootURL ?? URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
			url = base.appendingPathComponent(trimmed)
		}
		return ItsyWorkspaceController.openFile(at: url)
	}

	private func setFindBarVisible(_ visible: Bool) {
		findBarView.isHidden = !visible
		editorView.topContentInset = visible ? 38 : 0
		if visible {
			refreshFindMatches()
			focusFindQuery()
		} else {
			incrementalFindDirection = nil
			findMatches = []
			selectedFindMatchIndex = nil
			editorView.setFindMatchRanges([])
			focusEditor()
		}
	}

	private func findStateDidChange() {
		let expression = findRegularExpression()
		findQueryField.textColor = findState.query.isEmpty || expression != nil ? .labelColor : .systemRed
		refreshFindMatches()
		if let incrementalFindDirection {
			selectFindMatch(direction: incrementalFindDirection, refreshBeforeSelecting: false, focusEditorAfterSelection: false)
		}
	}

	private func refreshFindMatches() {
		guard !findBarView.isHidden, let expression = findRegularExpression() else {
			findMatches = []
			selectedFindMatchIndex = nil
			editorView.setFindMatchRanges([])
			return
		}
		let text = editorView.editor.text
		let fullRange = NSRange(text.startIndex ..< text.endIndex, in: text)
		findMatches = expression.matches(in: text, range: fullRange).compactMap { result in
			guard result.range.length > 0, let range = Range(result.range, in: text) else {
				return nil
			}
			return utf8Range(range, in: text)
		}
		selectedFindMatchIndex = nil
		editorView.setFindMatchRanges(findMatches)
	}

	private func selectFindMatchFromFindBar(direction: Int) {
		selectFindMatch(direction: direction, focusEditorAfterSelection: false)
	}

	private func selectFindMatch(direction: Int, refreshBeforeSelecting: Bool = true, focusEditorAfterSelection: Bool = true) {
		guard !findBarView.isHidden else {
			setFindBarVisible(true)
			return
		}
		if refreshBeforeSelecting {
			refreshFindMatches()
		}
		guard !findMatches.isEmpty else {
			return
		}
		let selectedIndex: Int
		if let selectedFindMatchIndex {
			selectedIndex = wrappedIndex(selectedFindMatchIndex + direction, count: findMatches.count)
		} else if direction >= 0 {
			let cursor = editorView.editor.selections.primary.head
			selectedIndex = findMatches.firstIndex { $0.lowerBound >= cursor } ?? 0
		} else {
			let cursor = editorView.editor.selections.primary.head
			selectedIndex = findMatches.lastIndex { $0.upperBound <= cursor } ?? findMatches.count - 1
		}
		selectedFindMatchIndex = selectedIndex
		editorView.selectUTF8Range(findMatches[selectedIndex])
		if focusEditorAfterSelection {
			focusEditor()
		}
	}

	private func wrappedIndex(_ index: Int, count: Int) -> Int {
		(index % count + count) % count
	}

	private func utf8Range(_ range: Range<String.Index>, in text: String) -> Range<Int>? {
		guard
			let lowerIndex = range.lowerBound.samePosition(in: text.utf8),
			let upperIndex = range.upperBound.samePosition(in: text.utf8)
		else {
			return nil
		}
		let lower = text.utf8.distance(from: text.utf8.startIndex, to: lowerIndex)
		let upper = text.utf8.distance(from: text.utf8.startIndex, to: upperIndex)
		return lower ..< upper
	}
}

extension EditorWindowController: NSWindowDelegate, NSTextFieldDelegate, NSOutlineViewDataSource, NSOutlineViewDelegate {
	func windowDidBecomeKey(_ notification: Notification) {
		ItsyTabCoordinator.refresh()
	}

	func windowDidBecomeMain(_ notification: Notification) {
		ItsyTabCoordinator.refresh()
	}

	func windowWillClose(_ notification: Notification) {
		ItsyTabCoordinator.refresh()
	}

	func controlTextDidChange(_ notification: Notification) {
		guard let field = notification.object as? NSTextField, field === findQueryField || field === findReplaceField else {
			return
		}
		findStateDidChange()
	}

	func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
		guard control === findQueryField || control === findReplaceField else {
			return false
		}
		switch commandSelector {
		case #selector(NSResponder.insertNewline(_:)):
			selectFindMatchFromFindBar(direction: 1)
			return true
		case #selector(NSResponder.cancelOperation(_:)):
			setFindBarVisible(false)
			return true
		default:
			return false
		}
	}

	func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
		if let url = item as? NSURL {
			return fileTreeChildren(of: url).count
		}
		return fileTreeRootURL == nil ? 0 : 1
	}

	func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
		if let url = item as? NSURL {
			return fileTreeChildren(of: url)[index]
		}
		guard let fileTreeRootURL else {
			preconditionFailure("root node requested before workspace root was set")
		}
		return fileTreeRootURL
	}

	func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
		(item as? NSURL).map(isFileTreeDirectory(_:)) == true
	}

	func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
		guard let url = item as? NSURL else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("FileTreeCell")
		let cell = outlineView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.lineBreakMode = .byTruncatingMiddle
		textField.font = .systemFont(ofSize: 12)
		textField.stringValue = fileTreeTitle(for: url)
		if textField.superview == nil {
			textField.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(textField)
			NSLayoutConstraint.activate([
				textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 2),
				textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -2),
				textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
			cell.textField = textField
		}
		return cell
	}
}
