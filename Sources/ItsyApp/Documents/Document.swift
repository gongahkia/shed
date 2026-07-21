// @file NSDocument integration for editor workspaces and tabs.
import AppKit
import Darwin
import Dispatch
import Foundation
import ItsyConfig
import ItsyEditor
import ItsyLSP
import ItsyRender
import ItsySyntax

@MainActor final class ItsyDocument: NSDocument {
	static let handoffActivityType = "dev.itsy.editor.open-file"
	static let handoffURLKey = "url"
	static let handoffCursorOffsetKey = "cursorOffset"
	private static let mappedReadThreshold = 1_048_576
	private static let firstPageIndexByteCount = 4 * 1024
	private static let settingsLanguageRegistry = LSPServerRegistryLoader.loadOrBundled()

	var editor = Editor()
	private(set) var textFileSavePolicy = TextFileSavePolicy()
	private(set) var textFileRequiresEncodingChoice = false
	private var undecidedTextFileData: Data?
	private var hasPresentedTextFileEncodingChoice = false
	var lspSurfaceRefreshRequested: (() -> Void)?
	var lspDocumentSaved: (() -> Void)?
	private var editorViews: [MetalTextView] = []
	private let syntax = DocumentSyntaxController()
	private var syntaxHighlightSpans: [TextHighlightSpan] = []
	private var lspHighlightSpans: [TextHighlightSpan] = []
	private var handoffActivity: NSUserActivity?
	private var breakpointGutterDecorator: GutterDecorator?
	private var problemGutterDecorator: GutterDecorator?
	private var lspGutterDecorator: GutterDecorator?
	private var activeGutterDecorator: GutterDecorator?
	private let fileWatcher = DocumentFileWatcher()
	private let gitGutter = DocumentGitGutterController()
	private let undoHistoryStore = UndoHistoryStore()
	private let recoveryJournalScheduler = RecoveryJournalScheduler()

	override var fileURL: URL? {
		didSet {
			MainActor.assumeIsolated {
				syntax.configure(fileURL: fileURL)
				configureTextEditBehavior()
				updateHandoffActivity()
				fileWatcher.restart()
				ItsyBreakpointGutterCoordinator.apply(to: self)
			}
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
		fileWatcher.isDocumentEdited = { [weak self] in
			self?.isDocumentEdited ?? false
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
		fileWatcher.compareExternalText = { [weak self] url, localText, diskText in
			self?.presentExternalFileComparison(url: url, localText: localText, diskText: diskText)
		}
		fileWatcher.mergeExternalText = { [weak self] url, localText, diskText in
			self?.mergeExternalText(url: url, localText: localText, diskText: diskText)
		}
		fileWatcher.discardDeletedBuffer = { [weak self] in
			self?.discardRecoveryJournal()
			self?.close()
		}
		gitGutter.fileURL = { [weak self] in
			self?.fileURL
		}
		gitGutter.decoratorDidChange = { [weak self] in
			self?.refreshGutterDecorators()
		}
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

	override func read(from data: Data, ofType _: String) throws {
		try MainActor.assumeIsolated {
			let decoded = try TextFileCodec.decode(data)
			textFileSavePolicy = decoded.savePolicy
			textFileRequiresEncodingChoice = decoded.requiresEncodingChoice
			undecidedTextFileData = decoded.requiresEncodingChoice ? data : nil
			hasPresentedTextFileEncodingChoice = false
			installReadEditor(Editor(text: decoded.text), fileURL: nil)
		}
	}

	override func read(from url: URL, ofType typeName: String) throws {
		try MainActor.assumeIsolated {
			guard let policy = try mappedReadPolicy(from: url) else {
				try super.read(from: url, ofType: typeName)
				restoreRecoveryJournalIfAvailable(fileURL: url)
				return
			}
			let pieceTree = try PieceTree(
				readingMappedFile: url,
				indexedPrefixBytes: Self.firstPageIndexByteCount
			) {
				recordBenchStage("first_page_visible")
			}
			textFileSavePolicy = policy
			textFileRequiresEncodingChoice = false
			undecidedTextFileData = nil
			installReadEditor(Editor(pieceTree: pieceTree, retainsUndoTreeSnapshots: false), fileURL: url)
			restoreRecoveryJournalIfAvailable(fileURL: url)
		}
	}

	override func data(ofType _: String) throws -> Data {
		try TextFileCodec.encode(editorStorageString(editor), policy: textFileSavePolicy)
	}

	override func write(to url: URL, ofType typeName: String) throws {
		try MainActor.assumeIsolated {
			try writeEditorStorage(to: url)
			discardRecoveryJournal(fileURL: fileURL ?? url)
		}
	}

	override func write(
		to url: URL,
		ofType typeName: String,
		for saveOperation: NSDocument.SaveOperationType,
		originalContentsURL absoluteOriginalContentsURL: URL?
	) throws {
		try MainActor.assumeIsolated {
			try writeEditorStorage(to: url)
			discardRecoveryJournal(fileURL: fileURL ?? url)
		}
	}

	override func makeWindowControllers() {
		recordBenchStage("document_make_window_controllers_begin")
		let controller = EditorWindowController(document: self)
		recordBenchStage("document_window_controller_init_end")
		addWindowController(controller)
		recordBenchStage("document_make_window_controllers_end")
	}

	override func save(_ sender: Any?) {
		super.save(sender)
		lspDocumentSaved?()
		scheduleGitHunkGutterRefresh()
	}

	func attach(_ view: MetalTextView) {
		recordBenchStage("document_attach_editor_begin")
		if !editorViews.contains(where: { $0 === view }) {
			editorViews.append(view)
		}
		view.editor = editor
		view.textEditBehaviorConfiguration = textEditBehaviorConfiguration()
		view.undoTreeChanged?(editor.history.tree)
		view.visibleLineRangeDidChange = { [weak self] _ in
			self?.refreshSyntaxHighlights()
			self?.lspSurfaceRefreshRequested?()
		}
		view.newlineInsertionTextProvider = { [weak self] editor in
			guard let self else {
				return "\n"
			}
			let tabWidth = currentEditorSettings().tabWidth
			return syntax.newlineText(editor: editor, tabWidth: tabWidth)
		}
		syntax.configure(fileURL: fileURL)
		refreshSyntaxHighlights()
		view.editorDidChange = { [weak self, weak view] editor in
			guard let self, let view else {
				return
			}
			let oldRope: Rope? = if case let .rope(rope) = self.editor.textStorage { rope } else { nil }
			let edits = editor.lastEditBatch
			self.editor = editor
			lspHighlightSpans = []
			refreshSyntaxHighlights(edits: edits, oldRope: oldRope)
			lspSurfaceRefreshRequested?()
			updateHandoffActivity()
			syncSiblingEditorViews(source: view, editor: editor)
			saveUndoHistoryIfAvailable()
			view.undoTreeChanged?(editor.history.tree)
			updateChangeCount(.changeDone)
			scheduleRecoveryJournal()
			scheduleGitHunkGutterRefresh()
		}
		view.saveRequested = { [weak self] in
			self?.save(nil)
		}
		view.closeRequested = { [weak self] in
			self?.close()
		}
		presentTextFileEncodingChoice(in: view.window)
		ItsyProblemGutterCoordinator.apply(to: self)
		ItsyBreakpointGutterCoordinator.apply(to: self)
		ItsyGitHunkGutterCoordinator.apply(to: self)
		fileWatcher.restart()
		updateHandoffActivity()
		recordBenchStage("document_attach_editor_end")
	}

	func detach(_ view: MetalTextView) {
		view.visibleLineRangeDidChange = nil
		view.newlineInsertionTextProvider = nil
		editorViews.removeAll { $0 === view }
		refreshSyntaxHighlights()
	}

	func selectTextFileSavePolicy(_ policy: TextFileSavePolicy) {
		textFileSavePolicy = policy
		textFileRequiresEncodingChoice = false
		undecidedTextFileData = nil
	}

	func discardRecoveryJournal() {
		guard let fileURL else {
			return
		}
		discardRecoveryJournal(fileURL: fileURL)
	}

	func chooseTextFileEncoding(_ encoding: TextFileEncoding) throws {
		guard let data = undecidedTextFileData else {
			return
		}
		let decoded = try TextFileCodec.decode(data, using: encoding)
		textFileSavePolicy = decoded.savePolicy
		textFileRequiresEncodingChoice = false
		undecidedTextFileData = nil
		installReadEditor(Editor(text: decoded.text), fileURL: fileURL)
	}

	func setGutterDecorator(_ decorator: GutterDecorator?) {
		problemGutterDecorator = decorator
		refreshGutterDecorators()
	}

	func setBreakpointGutterDecorator(_ decorator: GutterDecorator?) {
		breakpointGutterDecorator = decorator
		refreshGutterDecorators()
	}

	func setLSPGutterDecorator(_ decorator: GutterDecorator?) {
		lspGutterDecorator = decorator
		refreshGutterDecorators()
	}

	func setLSPSemanticHighlightSpans(_ spans: [TextHighlightSpan]) {
		lspHighlightSpans = spans
		applyHighlightSpans()
	}

	private func refreshGutterDecorators() {
		let decorators = [breakpointGutterDecorator, problemGutterDecorator, gitGutter.decorator, lspGutterDecorator]
			.compactMap { $0 }
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

	private func currentEditorSettings() -> ItsySettings.EditorSettings {
		let settings = ItsySettingsStore().load(
			workspaceRoot: ItsyWorkspaceController.currentRootURL,
			fallback: EditorPreferences.legacySettings()
		).settings
		let languageID = fileURL.flatMap { Self.settingsLanguageRegistry.languageID(for: $0) }
		return settings.editorSettings(languageID: languageID)
	}

	private func configureTextEditBehavior() {
		let configuration = textEditBehaviorConfiguration()
		for view in editorViews {
			view.textEditBehaviorConfiguration = configuration
		}
	}

	private func textEditBehaviorConfiguration() -> TextEditBehaviorConfiguration {
		let settings = currentEditorSettings()
		let indentationUnit = settings.useSpaces ? String(repeating: " ", count: settings.tabWidth) : "\t"
		return TextEditBehaviorConfiguration(
			autoPairs: settings.autoPairs,
			smartIndent: settings.smartIndent,
			indentationUnit: indentationUnit
		)
	}

	func scheduleGitHunkGutterRefresh() {
		gitGutter.scheduleRefresh()
	}

	func applyLSPUpdatedText(_ text: String) {
		var updated = Editor(text: text)
		updated.setSelection(clampedSelection(editor.selections, length: updated.textStorage.length))
		installReadEditor(updated, fileURL: fileURL)
		updateChangeCount(.changeDone)
		scheduleGitHunkGutterRefresh()
	}

	func updateGitHunkGutter() {
		gitGutter.update()
	}

	func reloadSyntaxTheme() {
		syntax.reloadTheme(editor: editor, viewportLineRange: visibleSyntaxLineRange())
	}

	func restoreHandoffCursorOffset(_ offset: Int) {
		let clamped = min(max(offset, 0), editor.textStorage.length)
		editor.setSelection(SelectionSet(primary: Selection(anchor: clamped, head: clamped)))
		for view in editorViews {
			view.editor = editor
		}
		updateHandoffActivity()
	}

	func workspaceWindowFileState() -> WorkspaceWindowFileState? {
		guard let fileURL else {
			return nil
		}
		let selection = editor.selections.primary
		let foldedRanges = (editorViews.first?.foldedLineRanges ?? []).map {
			WorkspaceRangeState(lowerBound: $0.lowerBound, upperBound: $0.upperBound)
		}
		return WorkspaceWindowFileState(
			path: fileURL.standardizedFileURL.path,
			selectionAnchor: selection.anchor,
			selectionHead: selection.head,
			foldedRanges: foldedRanges
		)
	}

	func restoreWorkspaceWindowFileState(_ state: WorkspaceWindowFileState) {
		let length = editor.textStorage.length
		let anchor = min(max(state.selectionAnchor, 0), length)
		let head = min(max(state.selectionHead, 0), length)
		editor.setSelection(SelectionSet(primary: Selection(anchor: anchor, head: head)))
		let foldedRanges = state.foldedRanges.compactMap { range -> Range<Int>? in
			guard range.lowerBound >= 0, range.upperBound > range.lowerBound else {
				return nil
			}
			return range.lowerBound ..< range.upperBound
		}
		for view in editorViews {
			view.editor = editor
			view.foldedLineRanges = foldedRanges
		}
		updateHandoffActivity()
	}

	func jumpTo(line: Int, column: Int) {
		let zeroLine = max(0, line - 1)
		let zeroColumn = max(0, column - 1)
		let storage = editor.textStorage
		guard zeroLine < storage.lineCount else {
			return
		}
		let lineRange = storage.lineRange(zeroLine)
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
			siblingEditor.setSelection(view.editor.selections)
			view.editor = siblingEditor
		}
	}

	private func setHighlightSpans(_ spans: [TextHighlightSpan]) {
		syntaxHighlightSpans = spans
		applyHighlightSpans()
	}

	private func applyHighlightSpans() {
		let spans = syntaxHighlightSpans + lspHighlightSpans
		for view in editorViews {
			view.highlightSpans = spans
		}
	}

	private func refreshSyntaxHighlights(edits: [Edit] = [], oldRope: Rope? = nil) {
		syntax.refresh(editor: editor, edits: edits, oldRope: oldRope, viewportLineRange: visibleSyntaxLineRange())
	}

	private func visibleSyntaxLineRange() -> Range<Int>? {
		let ranges = editorViews.map(\.visibleLineRange).filter { !$0.isEmpty }
		guard let first = ranges.first else {
			return nil
		}
		return ranges.dropFirst().reduce(first) { result, range in
			min(result.lowerBound, range.lowerBound) ..< max(result.upperBound, range.upperBound)
		}
	}

	private func writeEditorStorage(to url: URL) throws {
		switch editor.textStorage {
		case .rope:
			try writeAtomically(try data(ofType: fileType ?? "public.data"), to: url)
		case let .pieceTree(pieceTree):
			if textFileSavePolicy.encoding == .utf8, case .preserve = textFileSavePolicy.newline {
				do {
					try AtomicFileWriter.write(to: url) { descriptor in
						try pieceTree.write(to: descriptor, path: url.path)
					}
				} catch let error as AtomicFileWriteError {
					throw error.cocoaError
				}
			} else {
				try writeAtomically(try data(ofType: fileType ?? "public.data"), to: url)
			}
		}
	}

	private func writeAtomically(_ data: Data, to url: URL) throws {
		do {
			try AtomicFileWriter.write(data: data, to: url)
		} catch let error as AtomicFileWriteError {
			throw error.cocoaError
		}
	}

	private func installReadEditor(_ newEditor: Editor, fileURL readURL: URL?) {
		editor = newEditor
		loadUndoHistoryIfAvailable(fileURL: readURL ?? fileURL)
		for view in editorViews {
			view.editor = editor
			view.undoTreeChanged?(editor.history.tree)
		}
		syntax.configure(fileURL: readURL ?? fileURL)
		refreshSyntaxHighlights()
		updateHandoffActivity()
		fileWatcher.restart()
	}

	private func undoWorkspaceRoot(for fileURL: URL) -> URL {
		ItsyWorkspaceController.currentRootURL ?? fileURL.deletingLastPathComponent()
	}

	private func scheduleRecoveryJournal() {
		guard let fileURL, editor.retainsUndoTreeSnapshots else {
			return
		}
		let journal = RecoveryJournal(fileURL: fileURL, text: editorStorageString(editor))
		recoveryJournalScheduler.schedule(journal, workspaceRoot: undoWorkspaceRoot(for: fileURL))
	}

	private func discardRecoveryJournal(fileURL: URL) {
		recoveryJournalScheduler.discard(fileURL: fileURL, workspaceRoot: undoWorkspaceRoot(for: fileURL))
	}

	private func restoreRecoveryJournalIfAvailable(fileURL: URL) {
		guard let journal = RecoveryJournalStore().load(fileURL: fileURL, workspaceRoot: undoWorkspaceRoot(for: fileURL)),
		      let text = String(data: journal.text, encoding: .utf8)
		else {
			return
		}
		installReadEditor(Editor(text: text), fileURL: fileURL)
		updateChangeCount(.changeDone)
	}

	private func loadUndoHistoryIfAvailable(fileURL: URL?) {
		guard editor.retainsUndoTreeSnapshots,
		      let fileURL,
		      let tree = undoHistoryStore.load(fileURL: fileURL, workspaceRoot: undoWorkspaceRoot(for: fileURL)),
		      tree.currentNode?.text == Data(editorStorageString(editor).utf8)
		else {
			return
		}
		editor.history.replaceTree(tree)
	}

	private func saveUndoHistoryIfAvailable() {
		guard let fileURL, editor.retainsUndoTreeSnapshots else {
			return
		}
		try? undoHistoryStore.save(editor.history.tree, fileURL: fileURL, workspaceRoot: undoWorkspaceRoot(for: fileURL))
	}

	private func clampedSelection(_ selectionSet: SelectionSet, length: Int) -> SelectionSet {
		func clamped(_ selection: Selection) -> Selection {
			Selection(
				anchor: min(max(selection.anchor, 0), length),
				head: min(max(selection.head, 0), length),
				affinity: selection.affinity
			)
		}
		return SelectionSet(primary: clamped(selectionSet.primary), secondaries: selectionSet.secondaries.map(clamped))
	}

	private func mappedReadPolicy(from url: URL) throws -> TextFileSavePolicy? {
		let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
		guard values.isRegularFile == true, let fileSize = values.fileSize else {
			return nil
		}
		guard fileSize > Self.mappedReadThreshold else {
			return nil
		}
		return try TextFileCodec.mappedUTF8SavePolicy(at: url)
	}

	private func presentTextFileEncodingChoice(in window: NSWindow?) {
		guard textFileRequiresEncodingChoice, !hasPresentedTextFileEncodingChoice, let window else {
			return
		}
		hasPresentedTextFileEncodingChoice = true
		let alert = NSAlert()
		alert.messageText = L10n.string("Choose text encoding")
		alert.informativeText = L10n.string("This file has no encoding marker. Choose how to interpret and save it.")
		alert.addButton(withTitle: L10n.string("UTF-16 Little Endian"))
		alert.addButton(withTitle: L10n.string("UTF-16 Big Endian"))
		alert.addButton(withTitle: L10n.string("UTF-8"))
		alert.beginSheetModal(for: window) { [weak self] response in
			let encoding: TextFileEncoding = switch response {
			case .alertFirstButtonReturn: .utf16LittleEndian
			case .alertSecondButtonReturn: .utf16BigEndian
			default: .utf8
			}
			do {
				try self?.chooseTextFileEncoding(encoding)
			} catch {
				self?.presentError(error)
			}
		}
	}

	private func reloadFromDisk(at url: URL) {
		do {
			try read(from: url, ofType: fileType ?? "public.data")
			updateChangeCount(.changeCleared)
		} catch {
			presentError(error)
		}
	}

	private func presentExternalFileComparison(url: URL, localText: String, diskText: String) {
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 960, height: 640),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Compare \(displayName ?? url.lastPathComponent)")
		let splitView = NSSplitView(frame: panel.contentView?.bounds ?? .zero)
		splitView.isVertical = true
		splitView.autoresizingMask = [.width, .height]
		func textPane(title: String, text: String) -> NSView {
			let textView = NSTextView()
			textView.isEditable = false
			textView.isSelectable = true
			textView.isRichText = false
			textView.font = .monospacedSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular)
			textView.string = text
			let scrollView = NSScrollView()
			scrollView.hasVerticalScroller = true
			scrollView.documentView = textView
			let label = NSTextField(labelWithString: title)
			let container = NSView()
			container.addSubview(label)
			container.addSubview(scrollView)
			label.translatesAutoresizingMaskIntoConstraints = false
			scrollView.translatesAutoresizingMaskIntoConstraints = false
			NSLayoutConstraint.activate([
				label.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 8),
				label.topAnchor.constraint(equalTo: container.topAnchor, constant: 6),
				scrollView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
				scrollView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
				scrollView.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 4),
				scrollView.bottomAnchor.constraint(equalTo: container.bottomAnchor)
			])
			return container
		}
		splitView.addArrangedSubview(textPane(title: L10n.string("Local (unsaved)"), text: localText))
		splitView.addArrangedSubview(textPane(title: L10n.string("On disk"), text: diskText))
		panel.contentView = splitView
		panel.center()
		panel.makeKeyAndOrderFront(self)
	}

	private func mergeExternalText(url: URL, localText: String, diskText: String) {
		let text = "<<<<<<< Local (unsaved)\n\(localText)\n=======\n\(diskText)\n>>>>>>> On disk\n"
		installReadEditor(Editor(text: text), fileURL: url)
		updateChangeCount(.changeDone)
		scheduleRecoveryJournal()
	}
}
