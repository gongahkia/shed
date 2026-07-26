@testable import ItsyApp
import AppKit
import Foundation
import ItsyConfig
import ItsyEditor
import Testing

@MainActor
@Test func pieceTreeDocumentWriteBypassesDataSerialization() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent(UUID().uuidString)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}

	var editor = Editor(text: "alpha\ngamma", storage: .pieceTree)
	editor.setSelection(SelectionSet(primary: Selection(anchor: 6, head: 6)))
	editor.insert("beta\n")

	let document = ItsyDocument()
	document.editor = editor

	let url = directory.appendingPathComponent("saved.txt")
	try document.write(
		to: url,
		ofType: "public.plain-text",
		for: .saveOperation,
		originalContentsURL: nil
	)

	#expect(try Data(contentsOf: url) == Data("alpha\nbeta\ngamma".utf8))
	#expect(try document.data(ofType: "public.plain-text") == Data("alpha\nbeta\ngamma".utf8))
}

@MainActor
@Test func documentCanDeinitializeOffMainDocumentActivityQueue() {
	var document: ItsyDocument? = ItsyDocument()
	let retainedDocument = Unmanaged.passRetained(document!).toOpaque()
	document = nil
	DispatchQueue(label: "NSDocument Activity").sync {
		Unmanaged<ItsyDocument>.fromOpaque(retainedDocument).release()
	}
}

@MainActor
@Test func documentPreservesDecodedEncodingAndSelectedNewlinePolicy() throws {
	let document = ItsyDocument()
	let body = try #require("alpha\r\nbeta".data(using: .utf16LittleEndian))
	let source = Data([0xFF, 0xFE]) + body
	try document.read(from: source, ofType: "public.plain-text")
	#expect(document.textFileSavePolicy.encoding == .utf16LittleEndian)
	#expect(document.textFileSavePolicy.newline == .preserve(.crlf))
	document.selectTextFileSavePolicy(TextFileSavePolicy(encoding: .utf8BOM, newline: .lf))
	let saved = try document.data(ofType: "public.plain-text")
	#expect(saved.starts(with: Data([0xEF, 0xBB, 0xBF])))
	#expect(String(data: saved.dropFirst(3), encoding: .utf8) == "alpha\nbeta")
}

@MainActor
@Test func lspWorkspaceUpdateIsUndoableAsOneGroup() {
	let document = ItsyDocument()
	document.editor = Editor(text: "one")
	document.applyLSPUpdatedText("two")
	#expect(editorStorageString(document.editor) == "two")
	document.editor.undo()
	#expect(editorStorageString(document.editor) == "one")
}

@MainActor
@Test func documentRestoresAndClearsDurableDirtyRecoveryJournal() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	defer { try? FileManager.default.removeItem(at: directory) }
	let url = directory.appendingPathComponent("note.txt")
	try Data("saved".utf8).write(to: url)
	let store = RecoveryJournalStore()
	try store.save(RecoveryJournal(fileURL: url, text: "dirty recovery"), workspaceRoot: directory)

	let document = ItsyDocument()
	defer { document.fileURL = nil }
	document.fileURL = url
	try document.read(from: url, ofType: "public.plain-text")
	#expect(editorStorageString(document.editor) == "dirty recovery")
	#expect(document.isDocumentEdited)
	#expect(document.recoveredJournalFileURL == url)
	try document.write(to: url, ofType: "public.plain-text")
	#expect(store.load(fileURL: url, workspaceRoot: directory) == nil)

	try store.save(RecoveryJournal(fileURL: url, text: "discard me"), workspaceRoot: directory)
	document.discardRecoveryJournal()
	#expect(store.load(fileURL: url, workspaceRoot: directory) == nil)
}

@MainActor
@Test func documentWithWindowControllerCanDeinitializeOffMainDocumentActivityQueue() {
	var document: ItsyDocument? = ItsyDocument()
	document?.makeWindowControllers()
	let retainedDocument = Unmanaged.passRetained(document!).toOpaque()
	document = nil
	DispatchQueue(label: "NSDocument Activity").sync {
		Unmanaged<ItsyDocument>.fromOpaque(retainedDocument).release()
	}
}

@MainActor
@Test func togglingSidebarReclaimsEditorSplitSpace() throws {
	let controller = EditorWindowController(document: ItsyDocument())
	defer { controller.close() }
	let window = try #require(controller.window)
	let splitView = try #require(window.contentView as? NSSplitView)
	window.setFrame(NSRect(x: 0, y: 0, width: 1200, height: 672), display: false)
	splitView.frame = NSRect(x: 0, y: 0, width: 1200, height: 672)
	splitView.layoutSubtreeIfNeeded()

	#expect(splitView.arrangedSubviews.count == 2)

	controller.toggleSidebar()
	splitView.layoutSubtreeIfNeeded()

	#expect(splitView.arrangedSubviews.count == 1)
	#expect(abs(splitView.arrangedSubviews[0].frame.minX) < 0.5)
	#expect(splitView.arrangedSubviews[0].frame.width > 1_100)

	controller.toggleSidebar()
	splitView.layoutSubtreeIfNeeded()

	#expect(splitView.arrangedSubviews.count == 2)
	#expect(abs(splitView.arrangedSubviews[0].frame.width - 240) < 2)
	#expect(splitView.arrangedSubviews[1].frame.minX > 200)
}

@MainActor
@Test func fileTreeUsesWorkbenchComponentHostAcrossSidebarMounts() throws {
	let controller = EditorWindowController(document: ItsyDocument())
	defer { controller.close() }
	let hostedView = try #require(controller.fileTreeHostView.subviews.first)
	#expect(controller.fileTreeComponentLifecycle == .visible)

	controller.toggleSidebar()
	#expect(controller.fileTreeComponentLifecycle == .unmounted)
	#expect(controller.fileTreeHostView.subviews.isEmpty)

	controller.toggleSidebar()
	#expect(controller.fileTreeComponentLifecycle == .visible)
	#expect(controller.fileTreeHostView.subviews.first === hostedView)
}

@MainActor
@Test func editorWindowHostsTerminalBelowAndGitAtTrailingEdge() throws {
	let controller = EditorWindowController(document: ItsyDocument())
	defer { controller.close() }
	let window = try #require(controller.window)
	let splitView = try #require(window.contentView as? NSSplitView)
	window.setFrame(NSRect(x: 0, y: 0, width: 1600, height: 900), display: false)
	splitView.layoutSubtreeIfNeeded()

	controller.setEmbeddedTerminalVisible(true)
	#expect(!controller.embeddedTerminalHostView.isHidden)
	#expect(controller.embeddedTerminalHostView.frame.height > 200)

	controller.setEmbeddedGitVisible(true)
	splitView.layoutSubtreeIfNeeded()
	#expect(splitView.arrangedSubviews.count == 3)
	#expect(controller.embeddedGitHostView.superview != nil)
	#expect(!controller.embeddedGitHostView.isHidden)
	#expect(controller.embeddedGitHostView.frame.width > 600)

	controller.setEmbeddedDebuggerVisible(true)
	splitView.layoutSubtreeIfNeeded()
	#expect(splitView.arrangedSubviews.count == 3)
	#expect(controller.embeddedGitHostView.isHidden)
	#expect(!controller.embeddedDebuggerHostView.isHidden)
	#expect(controller.embeddedDebuggerHostView.frame.width > 300)
	let debuggerSidebar = controller.embeddedDebuggerHostView.superview?.superview
	#expect(debuggerSidebar?.accessibilityLabel() == "Debugger sidebar")
	#expect(debuggerSidebar?.accessibilityValue() as? String == "Debugger")
	let debuggerActions = editorWindowDescendants(in: debuggerSidebar ?? NSView()).compactMap { $0 as? NSButton }.compactMap { $0.accessibilityLabel() }
	#expect(debuggerActions.contains("Focus Debugger sidebar"))
	#expect(debuggerActions.contains("Close Debugger sidebar"))

	controller.setEmbeddedTerminalVisible(false)
	controller.setEmbeddedGitVisible(false)
	controller.setEmbeddedDebuggerVisible(false)
	splitView.layoutSubtreeIfNeeded()
	#expect(controller.embeddedTerminalHostView.isHidden)
	#expect(splitView.arrangedSubviews.count == 2)
}

private func editorWindowDescendants(in view: NSView) -> [NSView] {
	view.subviews + view.subviews.flatMap(editorWindowDescendants)
}

@MainActor
@Test func editorWindowRestoresDedicatedDebuggerSidebarWidth() throws {
	let controller = EditorWindowController(document: ItsyDocument())
	defer { controller.close() }
	let window = try #require(controller.window)
	let splitView = try #require(window.contentView as? NSSplitView)
	window.setFrame(NSRect(x: 0, y: 0, width: 1600, height: 900), display: false)
	controller.restoreWorkspaceWorkbenchDividerState(.init(debuggerWidth: 410))
	controller.setEmbeddedDebuggerVisible(true)
	splitView.layoutSubtreeIfNeeded()

	#expect(abs(controller.embeddedDebuggerHostView.frame.width - 410) < 2)
}

@MainActor
@Test func editorWindowCollapsesTheSharedSecondarySidebarBeforeConstrainingTheEditor() throws {
	let controller = EditorWindowController(document: ItsyDocument())
	defer { controller.close() }
	let window = try #require(controller.window)
	let splitView = try #require(window.contentView as? NSSplitView)
	window.setFrame(NSRect(x: 0, y: 0, width: 900, height: 700), display: false)
	controller.setEmbeddedGitVisible(true)
	controller.setEmbeddedDebuggerVisible(true)
	splitView.layoutSubtreeIfNeeded()

	#expect(splitView.arrangedSubviews.count == 2)
	#expect(controller.embeddedGitHostView.isHidden)
	#expect(controller.embeddedDebuggerHostView.isHidden)

	window.setFrame(NSRect(x: 0, y: 0, width: 1600, height: 900), display: false)
	let lifecycle = try #require(window.delegate as? EditorWindowLifecycleCoordinator)
	lifecycle.windowDidResize(Notification(name: NSWindow.didResizeNotification, object: window))
	splitView.layoutSubtreeIfNeeded()
	#expect(splitView.arrangedSubviews.count == 3)
	#expect(!controller.embeddedDebuggerHostView.isHidden)
}

@MainActor
@Test func workspacePaneRestoreRebuildsTopologyAndFallsBackFromInvalidLayout() {
	let document = ItsyDocument()
	let controller = EditorWindowController(document: document)
	document.addWindowController(controller)
	defer { controller.close() }

	#expect(EditorPaneLayout.decode("V[L,L]")?.encoded == "V[L,L]")
	var coordinator = EditorPaneCoordinator()
	_ = coordinator.restore(layout: .split(vertical: true, children: [.leaf, .leaf]))
	#expect(coordinator.panes.count == 2)
	#expect(coordinator.layout().encoded == "V[V[L,L]]")
	controller.restoreWorkspacePaneLayout("V[L,L]")
	#expect(controller.paneLayoutEncoded == "V[V[L,L]]")

	controller.restoreWorkspacePaneLayout("V[]")
	#expect(controller.paneLayoutEncoded == "V[L]")
	let dividers = WorkspaceWorkbenchDividerState(sidebarWidth: 300, gitWidth: 520, debuggerWidth: 380)
	controller.restoreWorkspaceWorkbenchDividerState(dividers)
	#expect(controller.workspaceWorkbenchDividerState == dividers)
}

@MainActor
@Test func editorFocusTraversalCyclesVisibleShellControlsWithoutTraps() {
	let document = ItsyDocument()
	let controller = EditorWindowController(document: document)
	document.addWindowController(controller)
	defer { controller.close() }

	let initial = controller.focusTraversalTargetsForTesting
	#expect(initial.count >= 2)
	for index in initial.indices {
		#expect(initial[index].nextKeyView === initial[(index + 1) % initial.count])
		#expect(initial[index].previousKeyView === initial[(index + initial.count - 1) % initial.count])
	}
	controller.toggleSidebar()
	let withoutSidebar = controller.focusTraversalTargetsForTesting
	#expect(withoutSidebar.count < initial.count)
	for index in withoutSidebar.indices {
		#expect(withoutSidebar[index].nextKeyView === withoutSidebar[(index + 1) % withoutSidebar.count])
		#expect(withoutSidebar[index].previousKeyView === withoutSidebar[(index + withoutSidebar.count - 1) % withoutSidebar.count])
	}
}

@MainActor
@Test func workspacePaneRestoreReopensSelectedPaneDocumentsAndFocus() {
	let first = ItsyDocument()
	let second = ItsyDocument()
	first.fileURL = URL(fileURLWithPath: "/tmp/itsy-pane-first.swift")
	second.fileURL = URL(fileURLWithPath: "/tmp/itsy-pane-second.swift")
	NSDocumentController.shared.addDocument(first)
	NSDocumentController.shared.addDocument(second)
	defer {
		NSDocumentController.shared.removeDocument(first)
		NSDocumentController.shared.removeDocument(second)
	}

	let controller = EditorWindowController(document: first)
	first.addWindowController(controller)
	defer { controller.close() }
	var settings = ItsySettings()
	settings.editor.tabGroups = .pane
	controller.applySettings(settings)
	let states = [
		WorkspacePaneState(openPaths: [first.fileURL!.path], selectedPath: first.fileURL!.path),
		WorkspacePaneState(openPaths: [second.fileURL!.path], selectedPath: second.fileURL!.path),
	]

	controller.restoreWorkspacePaneLayout("V[L,L]", paneStates: states, focusedPaneIndex: 1)
	#expect(controller.workspacePaneStates == states)
	#expect(controller.workspaceFocusedPaneIndex == 1)
	#expect((controller.document as? ItsyDocument) === second)
}

@MainActor
@Test func movingPaneTabPreservesDirtyDocumentAndRemainingTabs() {
	let first = ItsyDocument()
	let second = ItsyDocument()
	first.fileURL = URL(fileURLWithPath: "/tmp/itsy-move-first.swift")
	second.fileURL = URL(fileURLWithPath: "/tmp/itsy-move-second.swift")
	NSDocumentController.shared.addDocument(first)
	NSDocumentController.shared.addDocument(second)
	defer {
		NSDocumentController.shared.removeDocument(first)
		NSDocumentController.shared.removeDocument(second)
	}

	let controller = EditorWindowController(document: first)
	first.addWindowController(controller)
	defer { controller.close() }
	var settings = ItsySettings()
	settings.editor.tabGroups = .pane
	controller.applySettings(settings)
	let initialStates = [
		WorkspacePaneState(openPaths: [first.fileURL!.path, second.fileURL!.path], selectedPath: first.fileURL!.path),
		WorkspacePaneState(openPaths: [second.fileURL!.path], selectedPath: second.fileURL!.path),
	]
	controller.restoreWorkspacePaneLayout("V[L,L]", paneStates: initialStates, focusedPaneIndex: 0)
	first.updateChangeCount(.changeDone)
	#expect(first.isDocumentEdited)

	#expect(controller.moveActivePaneTab(toAdjacentPane: 1))
	#expect(controller.workspacePaneStates == [
		WorkspacePaneState(openPaths: [second.fileURL!.path], selectedPath: second.fileURL!.path),
		WorkspacePaneState(openPaths: [second.fileURL!.path, first.fileURL!.path], selectedPath: first.fileURL!.path),
	])
	#expect(controller.workspaceFocusedPaneIndex == 1)
	#expect((controller.document as? ItsyDocument) === first)
	#expect(first.isDocumentEdited)
	first.updateChangeCount(.changeCleared)
	#expect(!first.isDocumentEdited)
}

@MainActor
@Test func movingLastPaneTabClosesOnlyItsSourcePane() {
	let first = ItsyDocument()
	let second = ItsyDocument()
	first.fileURL = URL(fileURLWithPath: "/tmp/itsy-last-pane-first.swift")
	second.fileURL = URL(fileURLWithPath: "/tmp/itsy-last-pane-second.swift")
	NSDocumentController.shared.addDocument(first)
	NSDocumentController.shared.addDocument(second)
	defer {
		NSDocumentController.shared.removeDocument(first)
		NSDocumentController.shared.removeDocument(second)
	}

	let controller = EditorWindowController(document: first)
	first.addWindowController(controller)
	defer { controller.close() }
	var settings = ItsySettings()
	settings.editor.tabGroups = .pane
	controller.applySettings(settings)
	controller.restoreWorkspacePaneLayout("V[L,L]", paneStates: [
		WorkspacePaneState(openPaths: [first.fileURL!.path], selectedPath: first.fileURL!.path),
		WorkspacePaneState(openPaths: [second.fileURL!.path], selectedPath: second.fileURL!.path),
	], focusedPaneIndex: 0)

	#expect(controller.moveActivePaneTab(toAdjacentPane: 1))
	#expect(controller.paneLayoutEncoded == "V[L]")
	#expect(controller.workspacePaneStates == [
		WorkspacePaneState(openPaths: [second.fileURL!.path, first.fileURL!.path], selectedPath: first.fileURL!.path),
	])
	#expect(NSDocumentController.shared.documents.contains { $0 === first })
}

@MainActor
@Test func documentFileWatcherCanDeinitializeOffMainWithActiveSource() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent(UUID().uuidString)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}
	let url = directory.appendingPathComponent("watched.txt")
	try Data("value".utf8).write(to: url)

	var watcher: DocumentFileWatcher? = DocumentFileWatcher()
	watcher?.fileURL = { url }
	watcher?.restart()
	let retainedWatcher = Unmanaged.passRetained(watcher!).toOpaque()
	watcher = nil
	DispatchQueue(label: "NSDocument Activity").sync {
		Unmanaged<DocumentFileWatcher>.fromOpaque(retainedWatcher).release()
	}
}

@MainActor
@Test func documentReadsLargeFilesThroughMappedPieceTree() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent(UUID().uuidString)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}

	let line = Array("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n".utf8)
	let bytes = Array(repeating: line, count: 13_000).flatMap { $0 }
	let sourceURL = directory.appendingPathComponent("large.log")
	try Data(bytes).write(to: sourceURL)

	let document = ItsyDocument()
	try document.read(from: sourceURL, ofType: "public.plain-text")

	#expect(document.editor.textStorage.kind == .pieceTree)
	#expect(!document.editor.retainsUndoTreeSnapshots)
	#expect(document.editor.history.tree.currentNode?.text.isEmpty == true)
	#expect(document.editor.textStorage.length == bytes.count)
	#expect(document.editor.textStorage.lineCount == 13_001)

	let savedURL = directory.appendingPathComponent("saved.log")
	try document.write(
		to: savedURL,
		ofType: "public.plain-text",
		for: .saveOperation,
		originalContentsURL: sourceURL
	)
	#expect(try Data(contentsOf: savedURL) == Data(bytes))
}

@Test func documentSyntaxRefreshParsesPieceTreeStorage() throws {
	let controller = DocumentSyntaxController()
	var spanCount = 0
	controller.setHighlightSpans = { spans in
		spanCount = spans.count
	}
	controller.configure(fileURL: URL(fileURLWithPath: "/tmp/sample.ts"))
	controller.refresh(editor: Editor(text: "const value = \"ok\";\n", storage: .pieceTree))
	#expect(spanCount > 0)
}

@Test func documentSyntaxRefreshLimitsHighlightsToViewportOverscan() throws {
	let controller = DocumentSyntaxController()
	var ranges: [Range<Int>] = []
	controller.setHighlightSpans = { spans in
		ranges = spans.map(\.range)
	}
	let text = (0 ..< 100)
		.map { "const value\($0) = \"ok\";" }
		.joined(separator: "\n")
	let editor = Editor(text: text, storage: .pieceTree)
	controller.configure(fileURL: URL(fileURLWithPath: "/tmp/sample.ts"))
	controller.refresh(editor: editor, viewportLineRange: 50 ..< 52)

	let storage = editor.textStorage
	let expected = storage.offset(forLine: 30) ..< storage.offset(forLine: 72)
	let visible = storage.offset(forLine: 50) ..< storage.offset(forLine: 52)
	#expect(!ranges.isEmpty)
	#expect(ranges.allSatisfy { $0.overlaps(expected) })
	#expect(ranges.contains { $0.overlaps(visible) })
}

@Test func documentSyntaxRefreshHandlesMultilineIncrementalEdit() throws {
	let controller = DocumentSyntaxController()
	var ranges: [Range<Int>] = []
	controller.setHighlightSpans = { spans in
		ranges = spans.map(\.range)
	}
	controller.configure(fileURL: URL(fileURLWithPath: "/tmp/sample.ts"))
	var editor = Editor(text: "const first = \"a\";\nconst third = \"c\";\n", storage: .rope)
	controller.refresh(editor: editor, viewportLineRange: 0 ..< 3)
	let oldRope = editor.rope
	let insertOffset = oldRope.offset(forLine: 1)
	editor.setSelection(SelectionSet(primary: Selection(anchor: insertOffset, head: insertOffset)))
	editor.insert("const second = \"b\";\n")
	controller.refresh(
		editor: editor,
		edits: editor.lastEditBatch,
		oldRope: oldRope,
		viewportLineRange: 0 ..< 4
	)

	let insertedLine = editor.textStorage.lineRange(1)
	#expect(!ranges.isEmpty)
	#expect(ranges.contains { $0.overlaps(insertedLine) })
}

@Test func documentSyntaxControllerReturnsQueryBackedNewlineIndent() throws {
	let controller = DocumentSyntaxController()
	controller.configure(fileURL: URL(fileURLWithPath: "/tmp/main.swift"))
	var editor = Editor(text: "func main() {}", storage: .pieceTree)
	let text = editor.textStorage.substring(0 ..< editor.textStorage.length)
	let brace = try #require(text.range(of: "{"))
	let offset = text[..<brace.upperBound].utf8.count
	editor.setSelection(SelectionSet(primary: Selection(anchor: offset, head: offset)))

	#expect(controller.newlineText(editor: editor, tabWidth: 2) == "\n  ")
}
