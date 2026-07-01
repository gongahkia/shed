import AppKit
import Darwin
import Dispatch
import Foundation
import ItsyEditor
import ItsyLSP
import ItsyRender
import ItsySyntax



final class ItsyDocument: NSDocument {
	static let handoffActivityType = "dev.itsy.editor.open-file"
	static let handoffURLKey = "url"
	static let handoffCursorOffsetKey = "cursorOffset"

	var editor = Editor()
	private var editorViews: [MetalTextView] = []
	private let syntax = DocumentSyntaxController()
	private var handoffActivity: NSUserActivity?
	private var problemGutterDecorator: GutterDecorator?
	private var gitGutterDecorator: GutterDecorator?
	private var activeGutterDecorator: GutterDecorator?
	private var gitHunkRefreshWorkItem: DispatchWorkItem?
	private let fileWatcherQueue = DispatchQueue(label: "dev.itsy.editor.file-watcher")
	private var fileWatchSource: DispatchSourceFileSystemObject?
	private var pendingExternalChangePrompt = false

	override var fileURL: URL? {
		didSet {
			syntax.configure(fileURL: fileURL)
			updateHandoffActivity()
			restartFileWatcher()
		}
	}

	override init() {
		super.init()
		syntax.setHighlightSpans = { [weak self] spans in
			self?.setHighlightSpans(spans)
		}
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
		syntax.configure(fileURL: fileURL)
		syntax.refresh(editor: editor)
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

	override func save(_ sender: Any?) {
		super.save(sender)
		scheduleGitHunkGutterRefresh()
	}

	func attach(_ view: MetalTextView) {
		if !editorViews.contains(where: { $0 === view }) {
			editorViews.append(view)
		}
		view.editor = editor
		syntax.configure(fileURL: fileURL)
		syntax.refresh(editor: editor)
		view.editorDidChange = { [weak self, weak view] editor in
			guard let self, let view else {
				return
			}
			let oldRope = self.editor.rope
			let edits = editor.lastEditBatch
			self.editor = editor
			self.syntax.refresh(editor: editor, edits: edits, oldRope: oldRope)
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
		ItsyGitHunkGutterCoordinator.apply(to: self)
		restartFileWatcher()
		updateHandoffActivity()
	}

	func detach(_ view: MetalTextView) {
		editorViews.removeAll { $0 === view }
	}

	func setGutterDecorator(_ decorator: GutterDecorator?) {
		problemGutterDecorator = decorator
		refreshGutterDecorators()
	}

	func setGitGutterDecorator(_ decorator: GutterDecorator?) {
		gitGutterDecorator = decorator
		refreshGutterDecorators()
	}

	private func refreshGutterDecorators() {
		let decorators = [problemGutterDecorator, gitGutterDecorator].compactMap { $0 }
		switch decorators.count {
		case 0:
			activeGutterDecorator = nil
		case 1:
			activeGutterDecorator = decorators[0]
		default:
			activeGutterDecorator = CompositeGutterDecorator(decorators: decorators)
		}
		for view in editorViews {
			view.gutterDecorator = activeGutterDecorator
		}
	}

	func scheduleGitHunkGutterRefresh() {
		gitHunkRefreshWorkItem?.cancel()
		let workItem = DispatchWorkItem { [weak self] in
			self?.updateGitHunkGutter()
		}
		gitHunkRefreshWorkItem = workItem
		DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: workItem)
	}

	func updateGitHunkGutter() {
		guard
			let fileURL,
			let gitRoot = try? GitRepository.discoverRoot(containing: fileURL),
			let relativePath = LSPDiagnosticsAggregator.relativePath(forURI: fileURL.absoluteString, root: gitRoot)
		else {
			setGitGutterDecorator(nil)
			return
		}
		do {
			let repository = GitRepository(root: gitRoot)
			let files: [DiffFile]
			switch ItsyGitHunkGutterCoordinator.currentMode {
			case .index:
				files = try repository.diffFiles(path: relativePath)
			case .head:
				files = try repository.diffFilesAgainstHead(path: relativePath)
			}
			let indicators = GitHunkIndicatorBuilder.indicators(files: files)
			setGitGutterDecorator(indicators.isEmpty ? nil : GitHunkGutterDecorator(
				indicators: indicators,
				mode: ItsyGitHunkGutterCoordinator.currentMode
			))
		} catch {
			setGitGutterDecorator(nil)
		}
	}

	func reloadSyntaxTheme() {
		syntax.reloadTheme(editor: editor)
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

	private func setHighlightSpans(_ spans: [TextHighlightSpan]) {
		for view in editorViews {
			view.highlightSpans = spans
		}
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
