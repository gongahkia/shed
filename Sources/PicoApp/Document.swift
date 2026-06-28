import AppKit
import Darwin
import Dispatch
import Foundation
import PicoEditor
import PicoRender

final class PicoDocumentController: NSDocumentController {
	override init() {
		super.init()
		PicoTabCoordinator.shared.install(documentController: self)
		PicoWorkspaceController.shared.install(documentController: self)
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		PicoTabCoordinator.shared.install(documentController: self)
		PicoWorkspaceController.shared.install(documentController: self)
	}

	override var defaultType: String? {
		"public.data"
	}

	@discardableResult
	func openDocument(at url: URL) -> Bool {
		let typeName = defaultType ?? "public.data"
		if let document = document(for: url) {
			if document.windowControllers.isEmpty {
				document.makeWindowControllers()
			}
			document.showWindows()
			noteRecentDocumentIfNeeded(document)
			return true
		}
		do {
			let document = try makeDocument(withContentsOf: url, ofType: typeName)
			addDocument(document)
			document.makeWindowControllers()
			document.showWindows()
			return true
		} catch {
			NSLog("failed to open \(url.path): \(error)")
			return false
		}
	}

	override func addDocument(_ document: NSDocument) {
		super.addDocument(document)
		noteRecentDocumentIfNeeded(document)
		PicoTabCoordinator.shared.refresh()
	}

	override func removeDocument(_ document: NSDocument) {
		super.removeDocument(document)
		PicoTabCoordinator.shared.refresh()
	}

	override func makeUntitledDocument(ofType typeName: String) throws -> NSDocument {
		PicoDocument()
	}

	override func makeDocument(withContentsOf url: URL, ofType typeName: String) throws -> NSDocument {
		try PicoDocument(contentsOf: url, ofType: typeName)
	}

	private func noteRecentDocumentIfNeeded(_ document: NSDocument) {
		guard let url = document.fileURL else {
			return
		}
		noteNewRecentDocument(document)
		noteNewRecentDocumentURL(url)
	}
}

final class PicoDocument: NSDocument {
	var editor = Editor()
	private weak var editorView: MetalTextView?
	private let fileWatcherQueue = DispatchQueue(label: "dev.pico.editor.file-watcher")
	private var fileWatchSource: DispatchSourceFileSystemObject?
	private var pendingExternalChangePrompt = false

	override var fileURL: URL? {
		didSet { restartFileWatcher() }
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

	override func updateChangeCount(_ change: NSDocument.ChangeType) {
		super.updateChangeCount(change)
		PicoTabCoordinator.shared.refresh()
	}

	override func read(from data: Data, ofType typeName: String) throws {
		guard let text = String(data: data, encoding: .utf8) else {
			throw CocoaError(.fileReadCorruptFile)
		}
		editor = Editor(text: text)
		editorView?.editor = editor
		restartFileWatcher()
	}

	override func data(ofType typeName: String) throws -> Data {
		if let editorView {
			editor = editorView.editor
		}
		return Data(editor.text.utf8)
	}

	override func makeWindowControllers() {
		let controller = EditorWindowController(document: self)
		addWindowController(controller)
	}

	func attach(_ view: MetalTextView) {
		editorView = view
		view.editor = editor
		view.editorDidChange = { [weak self] editor in
			self?.editor = editor
			self?.updateChangeCount(.changeDone)
		}
		view.saveRequested = { [weak self] in
			self?.save(nil)
		}
		view.closeRequested = { [weak self] in
			self?.close()
		}
		restartFileWatcher()
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
		alert.messageText = "\(displayName ?? url.lastPathComponent) changed on disk"
		alert.informativeText = "Reload the file from disk?"
		alert.addButton(withTitle: "Reload")
		alert.addButton(withTitle: "Keep Editing")
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

final class EditorWindowController: NSWindowController {
	private let fileTreeView = FileTreeSidebarView(frame: NSRect(x: 0, y: 0, width: 240, height: 672))
	private let tabBarView = TabBarView(frame: NSRect(x: 0, y: 0, width: 960, height: 32))
	private let findBarView = FindBarView(frame: NSRect(x: 0, y: 0, width: 960, height: 38))
	private let editorView: MetalTextView
	private var findMatches: [Range<Int>] = []
	private var selectedFindMatchIndex: Int?

	init(document: PicoDocument) {
		editorView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		let editorContainer = NSView(frame: editorView.frame)
		let editorStack = NSStackView(frame: NSRect(x: 240, y: 0, width: 960, height: 672))
		editorStack.orientation = .vertical
		editorStack.alignment = .width
		editorStack.distribution = .fill
		editorStack.spacing = 0
		tabBarView.setContentHuggingPriority(.required, for: .vertical)
		editorContainer.setContentHuggingPriority(.defaultLow, for: .vertical)
		editorContainer.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
		editorView.translatesAutoresizingMaskIntoConstraints = false
		findBarView.translatesAutoresizingMaskIntoConstraints = false
		findBarView.isHidden = true
		editorContainer.addSubview(editorView)
		editorContainer.addSubview(findBarView)
		NSLayoutConstraint.activate([
			editorView.leadingAnchor.constraint(equalTo: editorContainer.leadingAnchor),
			editorView.trailingAnchor.constraint(equalTo: editorContainer.trailingAnchor),
			editorView.topAnchor.constraint(equalTo: editorContainer.topAnchor),
			editorView.bottomAnchor.constraint(equalTo: editorContainer.bottomAnchor),
			findBarView.leadingAnchor.constraint(equalTo: editorContainer.leadingAnchor),
			findBarView.trailingAnchor.constraint(equalTo: editorContainer.trailingAnchor),
			findBarView.topAnchor.constraint(equalTo: editorContainer.topAnchor),
		])
		editorStack.addArrangedSubview(tabBarView)
		editorStack.addArrangedSubview(editorContainer)

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
		window.title = document.fileURL?.lastPathComponent ?? "Untitled"
		window.contentView = splitView
		super.init(window: window)
		window.delegate = self
		document.attach(editorView)
		findBarView.onDismiss = { [weak self] in
			self?.setFindBarVisible(false)
		}
		findBarView.onStateChange = { [weak self] _ in
			self?.refreshFindMatches()
		}
		findBarView.onFindNext = { [weak self] in
			self?.findNext()
		}
		PicoWorkspaceController.shared.register(fileTreeView)
		PicoTabCoordinator.shared.register(tabBarView)
		window.makeFirstResponder(editorView)
	}

	required init?(coder: NSCoder) {
		nil
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
		PicoTabCoordinator.shared.refresh()
	}

	func focusEditor() {
		window?.makeFirstResponder(editorView)
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

	private func setFindBarVisible(_ visible: Bool) {
		findBarView.isHidden = !visible
		editorView.topContentInset = visible ? 38 : 0
		if visible {
			refreshFindMatches()
			findBarView.focusQuery()
		} else {
			findMatches = []
			selectedFindMatchIndex = nil
			editorView.setFindMatchRanges([])
			focusEditor()
		}
	}

	private func refreshFindMatches() {
		guard !findBarView.isHidden, let expression = findBarView.regularExpression() else {
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

	private func selectFindMatch(direction: Int) {
		guard !findBarView.isHidden else {
			setFindBarVisible(true)
			return
		}
		refreshFindMatches()
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
		focusEditor()
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

extension EditorWindowController: NSWindowDelegate {
	func windowDidBecomeKey(_ notification: Notification) {
		PicoTabCoordinator.shared.refresh()
	}

	func windowDidBecomeMain(_ notification: Notification) {
		PicoTabCoordinator.shared.refresh()
	}

	func windowWillClose(_ notification: Notification) {
		PicoTabCoordinator.shared.refresh()
	}
}
