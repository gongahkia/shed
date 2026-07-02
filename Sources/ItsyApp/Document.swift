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
	private static let mappedReadThreshold = 1_048_576
	private static let firstPageIndexByteCount = 4 * 1024

	var editor = Editor()
	private var editorViews: [MetalTextView] = []
	private let syntax = DocumentSyntaxController()
	private var handoffActivity: NSUserActivity?
	private var breakpointGutterDecorator: GutterDecorator?
	private var problemGutterDecorator: GutterDecorator?
	private var activeGutterDecorator: GutterDecorator?
	private let fileWatcher = DocumentFileWatcher()
	private let gitGutter = DocumentGitGutterController()

	override var fileURL: URL? {
		didSet {
			syntax.configure(fileURL: fileURL)
			updateHandoffActivity()
			fileWatcher.restart()
			ItsyBreakpointGutterCoordinator.apply(to: self)
		}
	}

	override init() {
		super.init()
		syntax.setHighlightSpans = { [weak self] spans in
			self?.setHighlightSpans(spans)
		}
		fileWatcher.fileURL = { [weak self] in
			self?.fileURL
		}
		fileWatcher.currentText = { [weak self] in
			guard let self else {
				return ""
			}
			return editorStorageString(editor)
		}
		fileWatcher.displayName = { [weak self] url in
			self?.displayName ?? url.lastPathComponent
		}
		fileWatcher.promptWindow = { [weak self] in
			self?.windowControllers.first?.window
		}
		fileWatcher.reloadFromDisk = { [weak self] url in
			self?.reloadFromDisk(at: url)
		}
		gitGutter.fileURL = { [weak self] in
			self?.fileURL
		}
		gitGutter.decoratorDidChange = { [weak self] in
			self?.refreshGutterDecorators()
		}
	}

	deinit {
		fileWatcher.stop()
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
		installReadEditor(Editor(text: text), fileURL: nil)
	}

	override func read(from url: URL, ofType typeName: String) throws {
		guard try shouldReadMappedPieceTree(from: url) else {
			try super.read(from: url, ofType: typeName)
			return
		}
		let pieceTree = try PieceTree(
			readingMappedFile: url,
			indexedPrefixBytes: Self.firstPageIndexByteCount
		) {
			recordBenchStage("first_page_visible")
		}
		installReadEditor(Editor(pieceTree: pieceTree), fileURL: url)
	}

	override func data(ofType typeName: String) throws -> Data {
		switch editor.textStorage {
		case .rope:
			return Data(editorStorageString(editor).utf8)
		case .pieceTree:
			throw CocoaError(.fileWriteUnknown)
		}
	}

	override func write(to url: URL, ofType typeName: String) throws {
		if try writeEditorStorage(to: url) {
			return
		}
		try super.write(to: url, ofType: typeName)
	}

	override func write(
		to url: URL,
		ofType typeName: String,
		for saveOperation: NSDocument.SaveOperationType,
		originalContentsURL absoluteOriginalContentsURL: URL?
	) throws {
		if try writeEditorStorage(to: url) {
			return
		}
		try super.write(
			to: url,
			ofType: typeName,
			for: saveOperation,
			originalContentsURL: absoluteOriginalContentsURL
		)
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
		ItsyBreakpointGutterCoordinator.apply(to: self)
		ItsyGitHunkGutterCoordinator.apply(to: self)
		fileWatcher.restart()
		updateHandoffActivity()
	}

	func detach(_ view: MetalTextView) {
		editorViews.removeAll { $0 === view }
	}

	func setGutterDecorator(_ decorator: GutterDecorator?) {
		problemGutterDecorator = decorator
		refreshGutterDecorators()
	}

	func setBreakpointGutterDecorator(_ decorator: GutterDecorator?) {
		breakpointGutterDecorator = decorator
		refreshGutterDecorators()
	}

	private func refreshGutterDecorators() {
		let decorators = [breakpointGutterDecorator, problemGutterDecorator, gitGutter.decorator].compactMap { $0 }
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
		gitGutter.scheduleRefresh()
	}

	func updateGitHunkGutter() {
		gitGutter.update()
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

	private func writeEditorStorage(to url: URL) throws -> Bool {
		switch editor.textStorage {
		case .rope:
			return false
		case let .pieceTree(pieceTree):
			try pieceTree.saveTo(url: url)
			return true
		}
	}

	private func installReadEditor(_ newEditor: Editor, fileURL readURL: URL?) {
		editor = newEditor
		for view in editorViews {
			view.editor = editor
		}
		syntax.configure(fileURL: readURL ?? fileURL)
		syntax.refresh(editor: editor)
		updateHandoffActivity()
		fileWatcher.restart()
	}

	private func shouldReadMappedPieceTree(from url: URL) throws -> Bool {
		let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
		guard values.isRegularFile == true, let fileSize = values.fileSize else {
			return false
		}
		return fileSize > Self.mappedReadThreshold
	}

	private func reloadFromDisk(at url: URL) {
		do {
			try read(from: url, ofType: fileType ?? "public.data")
			updateChangeCount(.changeCleared)
		} catch {
			presentError(error)
		}
	}
}
